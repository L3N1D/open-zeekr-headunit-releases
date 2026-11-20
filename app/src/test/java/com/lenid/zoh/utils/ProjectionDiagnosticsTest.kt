package com.lenid.zoh.utils
import org.junit.Assert.*
import org.junit.Test
class ProjectionDiagnosticsTest {
    @Test fun parsesActualThroughputAndPreservesInterval() {
        ProjectionDiagnostics.profile(60, 60, 1)
        ProjectionDiagnostics.onLog("Throughput over 5000ms: rendered=290 (58fps), fed=300 (60fps), dropped=1, skipped=2, concealed=0, inputWait=0ms, enqueueWait=0ms, codec=c2.qti.avc.decoder, presented=285 (57fps), latency=4", 5001)
        val v = requireNotNull(ProjectionDiagnostics.video)
        assertEquals("c2.qti.avc.decoder", v.codec)
        assertEquals(60, v.fedFps)
        assertEquals(58, v.renderedFps)
        assertEquals(57, v.presentedFps)
        assertEquals(5000L, v.intervalMs)
    }
    @Test fun newProfileClearsOldVideoAndRecordsReduction() {
        ProjectionDiagnostics.profile(30, 60, 42)
        assertNull(ProjectionDiagnostics.video)
        assertTrue(requireNotNull(ProjectionDiagnostics.profile).reduced)
        ProjectionDiagnostics.onLog("Throughput over invalid", 43)
        assertNull(ProjectionDiagnostics.video)
    }
    private fun sample(at: Long) = ProjectionDiagnostics.onLog(
        "Throughput over 5000ms: rendered=290 (58fps), fed=300 (60fps), dropped=2, codec=c2.qti.avc.decoder, presented=285 (57fps)", at)
    @Test fun captureExcludesPartialWindowsAndStopsAfterThirtySeconds() {
        ProjectionDiagnostics.profile(60, 60, 0)
        ProjectionDiagnostics.startCapture(1000)
        sample(5000)
        assertTrue(ProjectionDiagnostics.captured().isEmpty())
        sample(6000)
        sample(31000)
        sample(36000)
        assertEquals(listOf(6000L, 31000L), ProjectionDiagnostics.captured().map { it.atMs })
        assertEquals(0L, ProjectionDiagnostics.captureRemaining(40000))
        assertEquals(2L, ProjectionDiagnostics.video?.dropped)
    }
    @Test fun historyIsBoundedAndNewConnectionResetsCapture() {
        ProjectionDiagnostics.profile(60, 60, 0)
        repeat(100) { sample(it * 5000L) }
        assertTrue(ProjectionDiagnostics.samples(495000).size <= 13)
        assertTrue(ProjectionDiagnostics.samples(600000).isEmpty())
        ProjectionDiagnostics.startCapture(500000)
        sample(505000)
        ProjectionDiagnostics.profile(30, 60, 506000)
        assertTrue(ProjectionDiagnostics.captured().isEmpty())
        assertNull(ProjectionDiagnostics.captureRemaining(506000))
        assertTrue(ProjectionDiagnostics.samples(506000).isEmpty())
    }
}
