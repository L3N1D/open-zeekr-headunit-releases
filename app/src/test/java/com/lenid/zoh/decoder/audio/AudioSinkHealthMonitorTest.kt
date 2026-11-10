package com.lenid.zoh.decoder.audio

import com.lenid.zoh.aap.LinkGapMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSinkHealthMonitorTest {

    private val window = LinkGapMonitor.WINDOW_MS

    private fun monitor() = AudioSinkHealthMonitor("AUDIO", 48000)

    @Test
    fun `the queued line carries the backlog as a delay when the chunk size is known`() {
        val m = monitor()
        val chunkMs = SinkQueueOverflowPolicy.chunkDurationMs(2_048, 48_000)
        m.sample(0L, AudioSinkHealthMonitor.Sample())
        val r = m.sample(window, AudioSinkHealthMonitor.Sample(queuedChunks = 1_920, chunkDurationMs = chunkMs))
        assertNotNull(r)
        assertTrue(r.toString(), r.toString().contains("queued=1920/unbounded (${1_920L * chunkMs}ms)"))
    }

    @Test
    fun `an unsized backlog is left as a count rather than reported as no delay`() {
        val m = monitor()
        m.sample(0L, AudioSinkHealthMonitor.Sample())
        val r = m.sample(window, AudioSinkHealthMonitor.Sample(queuedChunks = 12))
        assertNotNull(r)
        assertTrue(r.toString(), r.toString().contains("queued=12/unbounded,"))
    }

    @Test
    fun `the ceiling in force is printed beside the depth, not the setting that was asked for`() {
        // A 7ms chunk raises a setting of 50 to 143, and a queue sitting there read as a fault.
        val m = monitor()
        val capacity = SinkQueueOverflowPolicy.capacityChunks(50, 7)
        m.sample(0L, AudioSinkHealthMonitor.Sample())
        val r = m.sample(
            window,
            AudioSinkHealthMonitor.Sample(
                queuedChunks = capacity, chunkDurationMs = 7, queueCapacityChunks = capacity
            )
        )
        assertNotNull(r)
        assertTrue(r.toString(), r.toString().contains("queued=143/143 (1001ms)"))
    }

    @Test
    fun `the first sample only opens the window`() {
        assertNull(monitor().sample(0L, AudioSinkHealthMonitor.Sample()))
    }

    @Test
    fun `nothing is reported before the window closes`() {
        val m = monitor()
        m.sample(0L, AudioSinkHealthMonitor.Sample())
        assertNull(m.sample(window - 1, AudioSinkHealthMonitor.Sample(underrunsTotal = 5)))
    }

    @Test
    fun `totals are reported as deltas over the window`() {
        val m = monitor()
        m.sample(0L, AudioSinkHealthMonitor.Sample(underrunsTotal = 10, silentCyclesTotal = 100))
        val r = m.sample(window, AudioSinkHealthMonitor.Sample(underrunsTotal = 13, silentCyclesTotal = 190))
        assertNotNull(r)
        assertEquals(3L, r!!.underruns)
        assertEquals(90L, r.silentCycles)
        assertFalse(r.healthy)
    }

    @Test
    fun `a restarted sink does not report a negative count`() {
        val m = monitor()
        m.sample(0L, AudioSinkHealthMonitor.Sample(underrunsTotal = 50))
        val r = m.sample(window, AudioSinkHealthMonitor.Sample(underrunsTotal = 0))
        assertEquals(0L, r!!.underruns)
    }

    @Test
    fun `the minimum depth over the window is kept, not just the last one`() {
        val m = monitor()
        m.sample(0L, AudioSinkHealthMonitor.Sample(depthFrames = 9600))
        m.sample(window / 2, AudioSinkHealthMonitor.Sample(depthFrames = 480))
        val r = m.sample(window, AudioSinkHealthMonitor.Sample(depthFrames = 9600))
        assertEquals(480, r!!.minDepthFrames)
        assertEquals(9600, r.depthFrames)
        assertEquals(10L, r.ms(480))
    }

    @Test
    fun `the deepest queue over the window is kept`() {
        val m = monitor()
        m.sample(0L, AudioSinkHealthMonitor.Sample())
        m.sample(window / 2, AudioSinkHealthMonitor.Sample(queuedChunks = 44))
        val r = m.sample(window, AudioSinkHealthMonitor.Sample(queuedChunks = 2))
        assertEquals(44, r!!.maxQueuedChunks)
    }

    @Test
    fun `a clean window reads healthy and still reports`() {
        val m = monitor()
        m.sample(0L, AudioSinkHealthMonitor.Sample(depthFrames = 9600, capacityFrames = 30752))
        val r = m.sample(window, AudioSinkHealthMonitor.Sample(depthFrames = 9600, capacityFrames = 30752))
        assertNotNull(r)
        assertTrue(r!!.healthy)
        // A rate only means something beside the window where nothing was wrong.
        assertTrue(r.toString().contains("underruns=0"))
        assertTrue(r.toString().contains("capacity=30752 frames (640ms)"))
    }

    @Test
    fun `capacity, effective and target are three different numbers`() {
        // They were not: the line printed the effective size as the capacity and the bank target
        // as the effective size, so a whole round graded a figure that was never read off a track.
        val m = monitor()
        val s = AudioSinkHealthMonitor.Sample(
            capacityFrames = 30752, effectiveFrames = 19200, targetFrames = 9600
        )
        m.sample(0L, s)
        val r = m.sample(window, s)!!.toString()
        assertTrue(r.contains("capacity=30752 frames (640ms)"))
        assertTrue(r.contains("effective=19200 frames (400ms)"))
        assertTrue(r.contains("target=9600 frames (200ms)"))
    }

    @Test
    fun `a shed frame is not a window worth calling healthy`() {
        val m = monitor()
        m.sample(0L, AudioSinkHealthMonitor.Sample())
        val r = m.sample(window, AudioSinkHealthMonitor.Sample(shedFramesTotal = 13))
        assertEquals(13L, r!!.shedFrames)
        assertFalse(r.healthy)
    }

    @Test
    fun `windows do not overlap or carry state forward`() {
        val m = monitor()
        m.sample(0L, AudioSinkHealthMonitor.Sample(underrunsTotal = 0, depthFrames = 100))
        m.sample(window, AudioSinkHealthMonitor.Sample(underrunsTotal = 4, depthFrames = 100))
        val second = m.sample(window * 2, AudioSinkHealthMonitor.Sample(underrunsTotal = 4, depthFrames = 9600))
        assertEquals(0L, second!!.underruns)
        assertEquals(9600, second.minDepthFrames)
    }

    @Test
    fun `reset forgets the session`() {
        val m = monitor()
        m.sample(0L, AudioSinkHealthMonitor.Sample(underrunsTotal = 7))
        m.reset()
        assertNull(m.sample(window * 5, AudioSinkHealthMonitor.Sample(underrunsTotal = 9)))
    }

    @Test
    fun `an unreadable sample rate reports zero milliseconds rather than dividing by zero`() {
        val m = AudioSinkHealthMonitor("AUDIO1", 0)
        m.sample(0L, AudioSinkHealthMonitor.Sample())
        val r = m.sample(window, AudioSinkHealthMonitor.Sample(depthFrames = 480))
        assertEquals(0L, r!!.ms(480))
    }
}
