package com.rewind.app

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Start/stop, save, and the few settings there are. */
class MainActivity : Activity() {

    private val BG = Color.parseColor("#14171F")
    private val CARD = Color.parseColor("#1F2430")
    private val INK = Color.parseColor("#ECEFF4")
    private val SOFT = Color.parseColor("#8C95A6")
    private val RED = Color.parseColor("#E8463A")
    private val LINE = Color.parseColor("#2E3544")

    private val main = Handler(Looper.getMainLooper())
    private val prefs get() = RecorderService.prefs(this)
    private lateinit var col: LinearLayout
    private val refresh = { render() }
    private var holding: TextView? = null
    private var shown = ""
    /** Once a second: the buffer line changes; the rest only when the state does. */
    private val tick = object : Runnable {
        override fun run() {
            val l = RecorderService.live
            val state = "${l != null}|${l?.saving}|${l?.lastSaved}|${l?.recorder?.hasSound}"
            if (state != shown) render() else holding?.text = holdingText()
            main.postDelayed(this, 1000)
        }
    }

    private fun holdingText(): String {
        val r = RecorderService.live?.recorder ?: return ""
        val secs = RecorderService.seconds(this)
        val have = Clips.seconds(r.video.spanUs())
        return "Holding ${minOf(have, secs)} of $secs s · ${r.video.bytes / (1 shl 20)} MB" +
            if (r.hasSound) " · with game sound" else if (prefs.getBoolean("sound", true)) " · no game sound" else ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        val frame = FrameLayout(this).apply {
            addView(col, FrameLayout.LayoutParams(minOf(dp(640), resources.displayMetrics.widthPixels), WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(frame) })
    }

    override fun onResume() {
        super.onResume()
        RecorderService.changed += refresh
        main.post(tick)
    }

    override fun onPause() {
        super.onPause()
        RecorderService.changed -= refresh
        main.removeCallbacks(tick)
    }

    // ---- starting ------------------------------------------------------------------------

    private fun start() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (prefs.getBoolean("sound", true) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.RECORD_AUDIO
        if (need.isNotEmpty() && !prefs.getBoolean("asked", false)) {
            prefs.edit().putBoolean("asked", true).apply()
            requestPermissions(need.toTypedArray(), 1); return
        }
        askToCapture()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code == 1) askToCapture()
    }

    private fun askToCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // The whole big screen, not one app: games hand off between frontend and emulator.
        val i = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else mpm.createScreenCaptureIntent()
        @Suppress("DEPRECATION") startActivityForResult(i, 2)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 2 || resultCode != RESULT_OK || data == null) return
        startForegroundService(Intent(this, RecorderService::class.java).setAction(RecorderService.START)
            .putExtra(RecorderService.EXTRA_CODE, resultCode).putExtra(RecorderService.EXTRA_DATA, data))
        // Back to the game: Rewind runs in the background from here.
        main.postDelayed({ moveTaskToBack(true) }, 400)
    }

    // ---- screen --------------------------------------------------------------------------

    private fun render() {
        val live = RecorderService.live
        val r = live?.recorder
        val secs = RecorderService.seconds(this)
        shown = "${live != null}|${live?.saving}|${live?.lastSaved}|${r?.hasSound}"
        holding = null
        col.removeAllViews()

        col.addView(text("Rewind", 30f, INK, bold = true))
        col.addView(text("Keeps the last few seconds of the screen. Save them when something happens; otherwise they're recorded over.", 15f, SOFT), lp(bottom = 16))

        val status = card()
        if (r != null) {
            status.addView(text("●  Recording", 20f, RED, bold = true))
            status.addView(text(holdingText(), 15f, SOFT).also { holding = it })
        } else {
            status.addView(text("Not recording", 20f, INK, bold = true))
            status.addView(text("Start it, then go back to your game. Android asks for permission each time Rewind starts.", 15f, SOFT))
        }
        live?.lastSaved?.let { status.addView(text("Last saved: $it", 14f, SOFT), lp(top = 6)) }
        col.addView(status, lp(bottom = 12))

        if (r != null) {
            col.addView(button(if (live.saving) "Saving…" else "Save the last $secs s", RED) { RecorderService.save(this) }, lp(bottom = 10))
            col.addView(button("Stop recording", CARD) {
                startService(Intent(this, RecorderService::class.java).setAction(RecorderService.STOP))
            }, lp(bottom = 20))
        } else {
            col.addView(button("Start recording", RED) { start() }, lp(bottom = 20))
        }

        col.addView(text("CLIP LENGTH", 13f, SOFT, bold = true), lp(bottom = 6))
        val lengths = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(15, 30, 60, 120).forEachIndexed { i, s ->
            lengths.addView(button(if (s < 60) "$s s" else "${s / 60} min", if (s == secs) RED else CARD, 15f) {
                prefs.edit().putInt("seconds", s).apply()
                RecorderService.live?.setWindow(s)
                render()
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        col.addView(lengths, lp(bottom = 6))
        col.addView(text("About 75 MB of memory per minute. Takes effect straight away.", 13f, SOFT), lp(bottom = 18))

        col.addView(text("GAME SOUND", 13f, SOFT, bold = true), lp(bottom = 6))
        val sound = prefs.getBoolean("sound", true)
        col.addView(button(if (sound) "On" else "Off", CARD, 16f) {
            prefs.edit().putBoolean("sound", !sound).apply()
            if (!sound && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3)
            render()
        }, lp(bottom = 6))
        col.addView(text("Records what the game plays (not the microphone). Some apps don't allow it. Changes apply next time recording starts.", 13f, SOFT), lp(bottom = 18))

        col.addView(text("CLIP NAMES", 13f, SOFT, bold = true), lp(bottom = 6))
        if (usageAccess()) col.addView(text("Clips are named after the game, e.g. \"RetroArch 2026-11-19 21.04.33\".", 15f, INK), lp(bottom = 18))
        else {
            col.addView(button("Allow usage access", CARD, 16f) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }, lp(bottom = 6))
            col.addView(text("Lets Rewind name clips after the game being played. Without it they're named by date.", 13f, SOFT), lp(bottom = 18))
        }

        col.addView(text("SAVING", 13f, SOFT, bold = true), lp(bottom = 6))
        col.addView(text("• \"Save clip\" in the notification\n" +
            "• The \"Save clip\" tile in quick settings (add it by editing the tiles)\n" +
            "• The \"Save clip\" app icon: map it to a button or add it to your frontend\n\n" +
            "Clips go to Movies/Rewind (${clipCount()} so far).", 15f, INK))
    }

    private fun usageAccess(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, applicationInfo.uid, packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun clipCount(): Int = try {
        contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("Movies/Rewind%"), null)?.use { it.count } ?: 0
    } catch (_: Exception) { 0 }

    // ---- views ---------------------------------------------------------------------------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun lp(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        topMargin = dp(top); bottomMargin = dp(bottom)
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(14).toFloat(); setStroke(dp(1), LINE) }
    }

    private fun button(label: String, color: Int, size: Float = 18f, onClick: () -> Unit): View = Button(this).apply {
        text = label; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); typeface = Typeface.DEFAULT_BOLD
        setTextColor(INK); stateListAnimator = null
        minHeight = dp(52); minimumHeight = dp(52)
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(12).toFloat(); setStroke(dp(1), LINE) }
        setOnClickListener { onClick() }
    }
}
