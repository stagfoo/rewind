package com.rewind.app

/** One encoded video frame or audio packet. */
class Sample(val data: ByteArray, val ptsUs: Long, val key: Boolean)

/**
 * The last [windowUs] of encoded samples, oldest first. Old samples fall off the front a whole
 * keyframe interval at a time, so the oldest sample is always a keyframe and a clip cut from
 * here plays from its first frame. Holds at least [windowUs] once that much has been recorded
 * (up to one keyframe interval more), and never more than [maxBytes].
 */
class Ring(@Volatile var windowUs: Long, private val maxBytes: Long = 400L shl 20) {
    private val q = ArrayDeque<Sample>()
    var bytes = 0L; private set

    @Synchronized fun add(s: Sample) {
        if (q.isEmpty() && !s.key) return // a clip can't start mid-GOP
        q.addLast(s); bytes += s.data.size
        trim()
    }

    private fun trim() {
        val newest = q.last().ptsUs
        while (true) {
            // The next keyframe after the head: everything before it can go if what's left
            // still covers the window, or if the buffer is over its size limit.
            var i = 1
            while (i < q.size && !q[i].key) i++
            if (i >= q.size) return
            if (q[i].ptsUs > newest - windowUs && bytes <= maxBytes) return
            repeat(i) { bytes -= q.removeFirst().data.size }
        }
    }

    @Synchronized fun snapshot(): List<Sample> = q.toList()
    @Synchronized fun clear() { q.clear(); bytes = 0 }

    /** How much is buffered, in µs. */
    @Synchronized fun spanUs(): Long = if (q.size < 2) 0 else q.last().ptsUs - q.first().ptsUs
}

object Clips {
    /** Video and audio in one time order, for writing to the MP4 interleaved. */
    fun interleave(video: List<Sample>, audio: List<Sample>): List<Pair<Boolean, Sample>> {
        val out = ArrayList<Pair<Boolean, Sample>>(video.size + audio.size)
        var a = 0; var v = 0
        while (v < video.size || a < audio.size) {
            val takeVideo = a >= audio.size || (v < video.size && video[v].ptsUs <= audio[a].ptsUs)
            out += if (takeVideo) true to video[v++] else false to audio[a++]
        }
        return out
    }

    /** Audio from the first video frame on, so the clip doesn't open on sound over nothing. */
    fun audioFrom(audio: List<Sample>, startUs: Long) = audio.filter { it.ptsUs >= startUs }

    /** "Mario Kart 8 2026-11-19 21.04.33", or just the time when the game isn't known. */
    fun name(game: String?, stamp: String): String {
        val g = game?.replace(Regex("""[\\/:*?"<>|]"""), " ")?.replace(Regex("""\s+"""), " ")?.trim()
        return if (g.isNullOrEmpty()) "Clip $stamp" else "$g $stamp"
    }

    fun seconds(us: Long) = ((us + 500_000) / 1_000_000).toInt()
}
