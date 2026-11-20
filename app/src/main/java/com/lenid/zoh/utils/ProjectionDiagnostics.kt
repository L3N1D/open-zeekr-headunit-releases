package com.lenid.zoh.utils

/** Read-only telemetry. No transport, surface or decoder callbacks are owned here. */
internal object ProjectionDiagnostics {
    data class Profile(val fps: Int, val selectedFps: Int, val reduced: Boolean, val atMs: Long, val recoveryCap: Boolean)
    data class Video(val codec: String, val fedFps: Int, val renderedFps: Int,
                     val presentedFps: Int?, val intervalMs: Long, val atMs: Long, val dropped: Long? = null)
    @Volatile var profile: Profile? = null
        private set
    @Volatile var video: Video? = null
        private set
    private val throughput = Regex("Throughput over (\\d+)ms: rendered=\\d+ \\((\\d+)fps\\), fed=\\d+ \\((\\d+)fps\\).*?codec=([^,]+)(?:, presented=\\d+ \\((\\d+)fps\\))?")
    private val history = java.util.ArrayDeque<Video>()
    private val capture = mutableListOf<Video>()
    private var captureStart: Long? = null
    @Synchronized fun samples(now: Long): List<Video> = history.filter { it.atMs >= now - 60_000 }
    @Synchronized fun startCapture(now: Long) { capture.clear(); captureStart = now }
    @Synchronized fun captureRemaining(now: Long): Long? = captureStart?.let { (30_000 - (now - it)).coerceAtLeast(0) }
    @Synchronized fun captured(): List<Video> = capture.toList()
    @Synchronized fun profile(fps: Int, selectedFps: Int, atMs: Long, recoveryCap: Boolean = false) {
        video = null
        history.clear()
        capture.clear()
        captureStart = null
        profile = Profile(fps, selectedFps, fps < selectedFps, atMs, recoveryCap)
    }
    @Synchronized fun onLog(message: String, atMs: Long) {
        if (!message.contains("Throughput over ")) return
        val m = throughput.find(message) ?: return
        val interval = m.groupValues[1].toLongOrNull() ?: return
        val rendered = m.groupValues[2].toIntOrNull() ?: return
        val fed = m.groupValues[3].toIntOrNull() ?: return
        video = Video(m.groupValues[4], fed, rendered,
            m.groupValues[5].toIntOrNull(), interval, atMs,
            Regex("dropped=(\\d+)").find(message)?.groupValues?.get(1)?.toLongOrNull())
        val sample = video ?: return
        history.addLast(sample)
        while (history.size > 60 || (history.peekFirst()?.atMs ?: atMs) < atMs - 60_000) history.removeFirst()
        captureStart?.let { start ->
            // Only full measurement windows inside the capture belong to its result.
            if (atMs - interval >= start && atMs <= start + 30_000 && capture.size < 60) capture.add(sample)
        }
    }
}
