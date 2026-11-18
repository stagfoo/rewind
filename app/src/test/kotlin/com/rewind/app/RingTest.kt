package com.rewind.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RingTest {
    /** 60 fps video with a keyframe every second, for [seconds]. */
    private fun record(r: Ring, seconds: Int) {
        for (f in 0 until seconds * 60) r.add(Sample(ByteArray(100), f * 16_667L, f % 60 == 0))
    }

    @Test fun keepsAtLeastTheWindowAndStartsOnAKeyframe() {
        val r = Ring(30_000_000)
        record(r, 100)
        val s = r.snapshot()
        assertTrue(s.first().key)
        val span = s.last().ptsUs - s.first().ptsUs
        assertTrue("span $span", span >= 30_000_000 - 20_000 && span <= 31_000_000)
    }

    @Test fun shortRecordingKeepsEverything() {
        val r = Ring(30_000_000)
        record(r, 5)
        assertEquals(300, r.snapshot().size)
    }

    @Test fun ignoresFramesBeforeTheFirstKeyframe() {
        val r = Ring(30_000_000)
        r.add(Sample(ByteArray(1), 0, false))
        r.add(Sample(ByteArray(1), 1, true))
        assertEquals(1L, r.snapshot().first().ptsUs)
    }

    @Test fun sizeLimitWins() {
        val r = Ring(60_000_000, maxBytes = 100L * 60 * 10) // ten seconds' worth
        record(r, 40)
        assertTrue(r.bytes <= 100L * 60 * 11)
        assertTrue(r.snapshot().first().key)
    }

    @Test fun shorterWindowTrimsOnNextFrame() {
        val r = Ring(60_000_000)
        record(r, 50)
        r.windowUs = 15_000_000
        r.add(Sample(ByteArray(100), 50 * 60 * 16_667L, true))
        assertTrue(r.spanUs() <= 16_000_000)
    }

    @Test fun audioAllKeys() {
        val r = Ring(10_000_000)
        for (i in 0 until 2000) r.add(Sample(ByteArray(10), i * 21_333L, true))
        assertTrue(r.spanUs() in 9_990_000..10_030_000)
    }

    @Test fun interleavesByTime() {
        val v = listOf(Sample(ByteArray(1), 0, true), Sample(ByteArray(1), 20, false), Sample(ByteArray(1), 40, false))
        val a = listOf(Sample(ByteArray(1), 10, true), Sample(ByteArray(1), 30, true))
        assertEquals(listOf(0L, 10L, 20L, 30L, 40L), Clips.interleave(v, a).map { it.second.ptsUs })
        assertEquals(listOf(30L), Clips.audioFrom(a, 25).map { it.ptsUs })
    }

    @Test fun names() {
        assertEquals("RetroArch 2026-11-19 21.04.33", Clips.name("RetroArch", "2026-11-19 21.04.33"))
        assertEquals("Clip 2026-11-19 21.04.33", Clips.name(null, "2026-11-19 21.04.33"))
        assertEquals("A B 2026", Clips.name("A/B", "2026"))
    }
}
