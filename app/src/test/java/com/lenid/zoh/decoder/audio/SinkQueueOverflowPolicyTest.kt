package com.lenid.zoh.decoder.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SinkQueueOverflowPolicyTest {

    @Test
    fun `an 8192 byte media chunk is about 42 ms`() {
        assertEquals(42, SinkQueueOverflowPolicy.chunkDurationMs(2048, 48000))
    }

    @Test
    fun `the default 50 chunks already clears both floors on the media sink`() {
        assertEquals(50, SinkQueueOverflowPolicy.capacityChunks(50, 42))
    }

    @Test
    fun `a small setting is raised to hold the unacked window`() {
        // A legal 30-message burst must fit or we drop sound the protocol told the phone to send.
        assertEquals(
            SinkQueueOverflowPolicy.UNACKED_WINDOW_CHUNKS,
            SinkQueueOverflowPolicy.capacityChunks(5, 42)
        )
    }

    @Test
    fun `a long prompt chunk needs fewer entries and the window floor governs`() {
        // 16 kHz mono, 8192-byte message = 4096 frames = 256 ms, so 4 chunks make a second.
        val chunkMs = SinkQueueOverflowPolicy.chunkDurationMs(4096, 16000)
        assertEquals(256, chunkMs)
        assertEquals(SinkQueueOverflowPolicy.UNACKED_WINDOW_CHUNKS, SinkQueueOverflowPolicy.capacityChunks(10, chunkMs))
    }

    @Test
    fun `a very short chunk needs more entries to make the same milliseconds`() {
        // 10 ms chunks need 100 entries to hold a second, above both other floors.
        assertEquals(100, SinkQueueOverflowPolicy.capacityChunks(50, 10))
    }

    @Test
    fun `no limit stays no limit`() {
        assertEquals(0, SinkQueueOverflowPolicy.capacityChunks(0, 42))
    }

    @Test
    fun `an unreadable chunk duration falls back to the other floors`() {
        assertEquals(50, SinkQueueOverflowPolicy.capacityChunks(50, 0))
        assertEquals(0, SinkQueueOverflowPolicy.chunkDurationMs(2048, 0))
    }

    @Test
    fun `an unbounded queue is measured as a delay, not only as a count`() {
        val chunkMs = SinkQueueOverflowPolicy.chunkDurationMs(2_048, 48_000)
        // D-HP's round 2: 1920 chunks read as nothing, and was minutes of offset.
        assertTrue(
            SinkQueueOverflowPolicy.backlogMs(1_920, chunkMs) >
                SinkQueueOverflowPolicy.UNBOUNDED_BACKLOG_WARN_MS
        )
        assertEquals(0L, SinkQueueOverflowPolicy.backlogMs(0, chunkMs))
    }

    @Test
    fun `a backlog nothing can size says so rather than reporting none`() {
        assertEquals(-1L, SinkQueueOverflowPolicy.backlogMs(50, 0))
    }

    @Test
    fun `an AAC chunk measured by its encoded size mis-sizes the queue in both directions`() {
        // Measured: the AAC sink queued encoded bytes and divided them by a PCM frame's width, so a
        // 1024-frame access unit read as 336 frames. Both figures below are wrong by that ratio.
        val encodedAsFrames = 1_344 / 4
        val decoded = SinkQueueOverflowPolicy.chunkDurationMs(1_024, 48_000)
        val misread = SinkQueueOverflowPolicy.chunkDurationMs(encodedAsFrames, 48_000)
        assertEquals(21, decoded)
        assertEquals(7, misread)
        assertEquals(143, SinkQueueOverflowPolicy.capacityChunks(50, misread))
        assertEquals(50, SinkQueueOverflowPolicy.capacityChunks(50, decoded))
        // Round 3 read 17584ms off a queue of 2512 that was really more than three times that.
        assertEquals(17_584L, SinkQueueOverflowPolicy.backlogMs(2_512, misread))
        assertEquals(52_752L, SinkQueueOverflowPolicy.backlogMs(2_512, decoded))
    }

    @Test
    fun `the floor clears the burst a parked read thread delivers at once`() {
        // Measured: video dispatch parked the read thread for 200 ms at a time and the audio it
        // had not read arrived in one burst on release, which is what filled the queue.
        val chunkMs = SinkQueueOverflowPolicy.chunkDurationMs(2_048, 48_000)
        val capacity = SinkQueueOverflowPolicy.capacityChunks(20, chunkMs)
        assertTrue(capacity * chunkMs >= SinkQueueOverflowPolicy.MIN_QUEUE_MS)
    }
}
