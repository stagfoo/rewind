package com.rewind.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.service.quicksettings.TileService
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Holds the recording while it runs: the screen-capture permission only lives as long as a
 * foreground service does. Intents: START (with the capture permission), SAVE, STOP.
 */
class RecorderService : Service() {

    companion object {
        const val START = "com.rewind.app.START"
        const val SAVE = "com.rewind.app.SAVE"
        const val STOP = "com.rewind.app.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "rewind"
        private const val NOTE = 1

        /** The live service, if recording. Same process, so the UI can just look. */
        @Volatile var live: RecorderService? = null; private set
        /** Listeners (the app screen) told when recording starts, stops or saves. */
        val changed = mutableListOf<() -> Unit>()

        fun prefs(ctx: Context) = ctx.getSharedPreferences("rewind", Context.MODE_PRIVATE)
        fun seconds(ctx: Context) = prefs(ctx).getInt("seconds", 30)

        fun save(ctx: Context) {
            ctx.startService(Intent(ctx, RecorderService::class.java).setAction(SAVE))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private var projection: MediaProjection? = null
    var recorder: Recorder? = null; private set
    var lastSaved: String? = null; private set
    @Volatile var saving = false; private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> begin(intent)
            SAVE -> saveClip()
            STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        if (recorder != null) return
        // Android 14+: the service must be in the foreground before the capture is taken.
        startForeground(NOTE, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try { data?.let { mpm.getMediaProjection(code, it) } } catch (e: Exception) { null }
        if (p == null) { stopSelf(); return }
        p.registerCallback(object : MediaProjection.Callback() {
            // Stopped from the system's cast/record chip, or the screen was locked on some builds.
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        projection = p
        val r = Recorder(this, p, seconds(this), prefs(this).getBoolean("sound", true))
        try { r.start() } catch (e: Exception) {
            toast("Couldn't start recording: ${e.message}"); stopSelf(); return
        }
        recorder = r
        live = this
        notifyChanged()
        TileService.requestListeningState(this, android.content.ComponentName(this, SaveTile::class.java))
    }

    fun setWindow(seconds: Int) {
        prefs(this).edit().putInt("seconds", seconds).apply()
        recorder?.setWindow(seconds)
        getSystemService(NotificationManager::class.java).notify(NOTE, note())
    }

    private fun saveClip() {
        val r = recorder ?: run { toast("Rewind isn't recording. Open Rewind to start it."); if (live == null) stopSelf(); return }
        if (saving) return
        saving = true
        buzz()
        val game = gameName()
        writer.execute {
            val s = r.save(game)
            main.post {
                saving = false
                if (s == null) toast("Nothing to save yet")
                else {
                    lastSaved = "${s.name} · ${s.seconds} s" + if (s.withSound) "" else " · no sound"
                    toast("Saved ${s.seconds} s clip")
                }
                notifyChanged()
            }
        }
    }

    /** The app on the big screen just before saving, from usage access (if granted). */
    private fun gameName(): String? = try {
        val usm = getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(now - 6 * 3_600_000L, now)
        val e = UsageEvents.Event()
        var pkg: String? = null
        val skip = setOf(packageName, "com.android.systemui")
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && e.packageName !in skip) pkg = e.packageName
        }
        pkg?.let { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }
    } catch (e: Exception) { null }

    private fun buzz() = try {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (_: Exception) {}

    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun note(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Rewind keeps the last few seconds"; setShowBadge(false)
            })
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val save = PendingIntent.getService(this, 1, Intent(this, RecorderService::class.java).setAction(SAVE), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, RecorderService::class.java).setAction(STOP), flags)
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), flags)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Keeping the last ${seconds(this)} seconds")
            .setContentText("Save clip keeps them; otherwise they're recorded over.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Save clip", save).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun notifyChanged() = main.post { changed.toList().forEach { it() } }

    override fun onDestroy() {
        live = null
        recorder?.stop(); recorder = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        writer.shutdown()
        notifyChanged()
        TileService.requestListeningState(this, android.content.ComponentName(this, SaveTile::class.java))
        super.onDestroy()
    }
}
