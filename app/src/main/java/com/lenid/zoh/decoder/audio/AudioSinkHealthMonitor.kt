package com.lenid.zoh.decoder.audio

import com.lenid.zoh.aap.LinkGapMonitor

/**
 * Whether the speaker actually stuttered, and when.
 *
 * Nothing measured the sink before this: a log could say the link was healthy and the picture
 * whole and still not answer whether the audio broke up, so four investigations in a row ended at
 * the link. The two that matter on the mixer path are [Report.silentCycles] and the depth, because
 * a mixer pads a starved channel with zeros and so the track itself never underruns.
 *
 * Pure and clock-free: the caller passes the time and the running totals, so a measured session
 * replays in a unit test.
 */
class AudioSinkHealthMonitor(
    private val channelName: String,
    private val sampleRateInHz: Int
) {

    private var started = false
    private var windowStartMs = 0L
    private var prev: Sample? = null
    private var minDepthFrames = Int.MAX_VALUE
    private var maxQueuedChunks = 0

    /** One observation. The totals are cumulative for the sink's lifetime; deltas are taken here. */
    data class Sample(
        val underrunsTotal: Long = 0,
        val silentCyclesTotal: Long = 0,
        val rebanksTotal: Long = 0,
        val droppedChunksTotal: Long = 0,
        val droppedFramesTotal: Long = 0,
        /** Decoded frames shed because the sink could not take them. AAC only. */
        val shedFramesTotal: Long = 0,
        /** Audio held and not yet played: the track's own backlog, or the mixer's ring. */
        val depthFrames: Int = 0,
        val queuedChunks: Int = 0,
        /** One chunk's playing time, so the queue can be reported as a delay and not only a count. */
        val chunkDurationMs: Int = 0,
        /** The ceiling actually in force, which the floors raise above the setting. Zero is none. */
        val queueCapacityChunks: Int = 0,
        /** What the track can hold at all, which is not what was asked for. */
        val capacityFrames: Int = 0,
        /** What the framework will actually keep filled, which is not the capacity either. */
        val effectiveFrames: Int = 0,
        /** The cushion this sink banks before it plays. The one of the three we choose. */
        val targetFrames: Int = 0
    )

    fun sample(nowMs: Long, s: Sample): Report? {
        if (!started) {
            started = true
            windowStartMs = nowMs
            prev = s
            return null
        }

        if (s.depthFrames < minDepthFrames) minDepthFrames = s.depthFrames
        if (s.queuedChunks > maxQueuedChunks) maxQueuedChunks = s.queuedChunks

        val elapsedMs = nowMs - windowStartMs
        if (elapsedMs < LinkGapMonitor.WINDOW_MS) return null

        val before = prev ?: s
        val report = Report(
            channelName = channelName,
            sampleRateInHz = sampleRateInHz,
            windowMs = elapsedMs,
            underruns = delta(s.underrunsTotal, before.underrunsTotal),
            silentCycles = delta(s.silentCyclesTotal, before.silentCyclesTotal),
            rebanks = delta(s.rebanksTotal, before.rebanksTotal),
            droppedChunks = delta(s.droppedChunksTotal, before.droppedChunksTotal),
            droppedFrames = delta(s.droppedFramesTotal, before.droppedFramesTotal),
            shedFrames = delta(s.shedFramesTotal, before.shedFramesTotal),
            depthFrames = s.depthFrames,
            minDepthFrames = if (minDepthFrames == Int.MAX_VALUE) s.depthFrames else minDepthFrames,
            maxQueuedChunks = maxQueuedChunks,
            chunkDurationMs = s.chunkDurationMs,
            queueCapacityChunks = s.queueCapacityChunks,
            capacityFrames = s.capacityFrames,
            effectiveFrames = s.effectiveFrames,
            targetFrames = s.targetFrames
        )

        windowStartMs = nowMs
        prev = s
        minDepthFrames = Int.MAX_VALUE
        maxQueuedChunks = 0
        return report
    }

    /** A counter that went backwards is a restarted sink, not a negative count. */
    private fun delta(now: Long, before: Long): Long = (now - before).coerceAtLeast(0L)

    fun reset() {
        started = false
        windowStartMs = 0L
        prev = null
        minDepthFrames = Int.MAX_VALUE
        maxQueuedChunks = 0
    }

    /** One window of sink health. */
    data class Report(
        val channelName: String,
        val sampleRateInHz: Int,
        val windowMs: Long,
        val underruns: Long,
        val silentCycles: Long,
        val rebanks: Long,
        val droppedChunks: Long,
        val droppedFrames: Long,
        val shedFrames: Long,
        val depthFrames: Int,
        val minDepthFrames: Int,
        val maxQueuedChunks: Int,
        val chunkDurationMs: Int,
        val queueCapacityChunks: Int,
        val capacityFrames: Int,
        val effectiveFrames: Int,
        val targetFrames: Int
    ) {
        fun ms(frames: Long): Long =
            if (sampleRateInHz <= 0) 0L else frames * 1000L / sampleRateInHz

        /**
         * The queue's own backlog as a duration. A count cannot be read as a delay, which is how a
         * queue minutes deep looked like nothing at all on an unbounded sink.
         */
        private val queuedBacklog: String
            get() = SinkQueueOverflowPolicy.backlogMs(maxQueuedChunks, chunkDurationMs)
                .takeIf { it >= 0 }?.let { " (${it}ms)" } ?: ""

        /**
         * The ceiling beside the depth. The floors raise it above the setting on a short chunk, so a
         * queue sitting at 143 against a setting of 50 is at its ceiling rather than misbehaving.
         */
        private val queuedCeiling: String
            get() = if (queueCapacityChunks > 0) "/$queueCapacityChunks" else "/unbounded"

        /** Whether this window is worth a reader's attention. */
        val healthy: Boolean
            get() = underruns == 0L && silentCycles == 0L && droppedChunks == 0L &&
                droppedFrames == 0L && shedFrames == 0L

        override fun toString(): String =
            "audio sink $channelName over ${windowMs}ms: underruns=$underruns, " +
                "silentCycles=$silentCycles, rebanks=$rebanks, dropped=$droppedChunks, " +
                "droppedFrames=$droppedFrames, shed=$shedFrames, " +
                "depth=${ms(depthFrames.toLong())}ms (min ${ms(minDepthFrames.toLong())}ms), " +
                "queued=$maxQueuedChunks$queuedCeiling$queuedBacklog, " +
                "capacity=$capacityFrames frames (${ms(capacityFrames.toLong())}ms), " +
                "effective=$effectiveFrames frames (${ms(effectiveFrames.toLong())}ms), " +
                "target=$targetFrames frames (${ms(targetFrames.toLong())}ms)"
    }
}
