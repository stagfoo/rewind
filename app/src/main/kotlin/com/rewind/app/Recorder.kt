package com.rewind.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.Display
import java.nio.ByteBuffer

/**
 * Keeps the last few seconds of the big screen (and the game's sound) as compressed video in
 * memory. Nothing touches storage until [save]. The hardware encoder does the work, so it costs
 * little battery; about 10 MB of memory per 8 seconds at the default quality.
 */
class Recorder(private val ctx: Context, private val projection: MediaProjection, windowSeconds: Int, private val withSound: Boolean) {

    companion object {
        const val TAG = "Rewind"
        private const val BITRATE = 10_000_000
        private const val FPS = 60
        private const val RATE = 48_000
        private const val CHANNELS = 2
    }

    val video = Ring(windowSeconds * 1_000_000L)
    val audio = Ring(windowSeconds * 1_000_000L)
    @Volatile private var videoFormat: MediaFormat? = null
    @Volatile private var audioFormat: MediaFormat? = null
    @Volatile var hasSound = false; private set

    private val thread = HandlerThread("rewind-video").apply { start() }
    private lateinit var codec: MediaCodec
    private var display: VirtualDisplay? = null
    private var audioThread: Thread? = null
    @Volatile private var running = false

    fun setWindow(seconds: Int) { video.windowUs = seconds * 1_000_000L; audio.windowUs = seconds * 1_000_000L }

    fun start() {
        running = true
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = d.mode
        val rot = d.rotation
        // The big screen as it's shown now (landscape on the Thor), at an even size the encoder takes.
        var w = mode.physicalWidth; var h = mode.physicalHeight
        if (rot == 1 || rot == 3) { val t = w; w = h; h = t }
        w = w and 1.inv(); h = h and 1.inv()
        val dpi = ctx.resources.displayMetrics.densityDpi

        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            // A keyframe every second: the clip can start at most a second before the window.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // A still screen sends no frames; repeat the last one so time keeps moving.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, i: Int) {}
            override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) { videoFormat = f }
            override fun onOutputBufferAvailable(c: MediaCodec, i: Int, info: MediaCodec.BufferInfo) {
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val b = c.getOutputBuffer(i)!!
                        video.add(Sample(copy(b, info), info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0))
                    }
                    c.releaseOutputBuffer(i, false)
                } catch (e: IllegalStateException) { /* stopping */ }
            }
            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) { Log.e(TAG, "video encoder", e) }
        }, Handler(thread.looper))
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        display = projection.createVirtualDisplay("Rewind", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        Log.i(TAG, "recording ${w}x$h")

        if (withSound && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            audioThread = Thread({ soundLoop() }, "rewind-audio").apply { start() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun soundLoop() {
        val rec = try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val pcm = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord.Builder().setAudioFormat(pcm).setBufferSizeInBytes(maxOf(min, 8192) * 4)
                .setAudioPlaybackCaptureConfig(cfg).build()
        } catch (e: Exception) { Log.w(TAG, "no game sound", e); return }

        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        rec.startRecording()
        hasSound = true
        val info = MediaCodec.BufferInfo()
        val bytesPerFrame = 2 * CHANNELS
        var frames = 0L
        val startUs = System.nanoTime() / 1000
        try {
            while (running) {
                val i = enc.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = enc.getInputBuffer(i)!!
                    buf.clear()
                    val n = rec.read(buf, minOf(buf.capacity(), 8192))
                    if (n > 0) {
                        // Time from samples counted, on the same clock as the video frames.
                        enc.queueInputBuffer(i, 0, n, startUs + frames * 1_000_000L / RATE, 0)
                        frames += n / bytesPerFrame
                    } else enc.queueInputBuffer(i, 0, 0, startUs + frames * 1_000_000L / RATE, 0)
                }
                while (true) {
                    val o = enc.dequeueOutputBuffer(info, 0)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { audioFormat = enc.outputFormat; continue }
                    if (o < 0) break
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        audio.add(Sample(copy(enc.getOutputBuffer(o)!!, info), info.presentationTimeUs, true))
                    }
                    enc.releaseOutputBuffer(o, false)
                }
            }
        } catch (e: Exception) { Log.w(TAG, "sound stopped", e) }
        finally {
            hasSound = false
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            try { enc.stop() } catch (_: Exception) {}
            enc.release()
        }
    }

    private fun copy(b: ByteBuffer, info: MediaCodec.BufferInfo): ByteArray {
        b.position(info.offset); b.limit(info.offset + info.size)
        return ByteArray(info.size).also { b.get(it) }
    }

    fun stop() {
        running = false
        try { display?.release() } catch (_: Exception) {}
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        audioThread?.join(500)
        thread.quitSafely()
        video.clear(); audio.clear()
    }

    class Saved(val name: String, val seconds: Int, val withSound: Boolean)

    /** Write what's in the buffer to Movies/Rewind as an MP4. Call off the main thread. */
    fun save(game: String?): Saved? {
        val vf = videoFormat ?: return null
        val v = video.snapshot()
        if (v.size < 2) return null
        val start = v.first().ptsUs
        val af = audioFormat
        val a = if (af != null) Clips.audioFrom(audio.snapshot(), start) else emptyList()

        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US).format(java.util.Date())
        val name = Clips.name(game, stamp)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Rewind")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
        try {
            cr.openFileDescriptor(uri, "rw")!!.use { pfd ->
                val mux = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val vt = mux.addTrack(vf)
                val at = if (a.isNotEmpty()) mux.addTrack(af!!) else -1
                mux.start()
                val info = MediaCodec.BufferInfo()
                for ((isVideo, s) in Clips.interleave(v, a)) {
                    info.set(0, s.data.size, s.ptsUs - start, if (s.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    mux.writeSampleData(if (isVideo) vt else at, ByteBuffer.wrap(s.data), info)
                }
                mux.stop(); mux.release()
            }
            cr.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
            cr.delete(uri, null, null)
            return null
        }
        return Saved(name, Clips.seconds(v.last().ptsUs - start), a.isNotEmpty())
    }
}
