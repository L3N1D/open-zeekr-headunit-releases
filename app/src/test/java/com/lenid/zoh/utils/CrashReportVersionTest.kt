package com.lenid.zoh.utils

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CrashReportVersionTest {
    private fun report(app: String) = "Logged error\nApp: $app\nDevice: QTI msmnile_gvmq for arm64, Android 11\nTime: 2026-11-19 09:41:06\n\n--- Recent log ---\n"

    @Test fun persistedOldReportKeepsOriginalReleaseAndCode() {
        val path = Files.createTempFile("crash-version", ".txt")
        try {
            Files.write(path, report("1.0.9 (131, e844bf2b2ed2)").toByteArray())
            val original = CrashReportVersion.fromReport(String(Files.readAllBytes(path)))!!
            assertEquals("open-zeekr-headunit@1.0.9", original.release)
            assertEquals("131", original.code)
            assertEquals("e844bf2b2ed2", original.revision)
            // Re-reading a queued report must not depend on the installed/uploading build.
            assertEquals(original, CrashReportVersion.fromReport(String(Files.readAllBytes(path))))
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun legacyHeaderWithoutRevisionIsSupported() {
        assertEquals(CrashReportVersion("1.0.1", "123", null), CrashReportVersion.fromReport(report("1.0.1 (123)")))
    }

    @Test fun prereleaseAndDirtyRevisionAreRetained() {
        assertEquals(CrashReportVersion("1.0.11-beta.1", "133", "abc123-dirty"),
            CrashReportVersion.fromReport(report("1.0.11-beta.1 (133, abc123-dirty)").replace("\n", "\r\n")))
    }

    @Test fun absentOrCorruptIdentityNeverUsesUploaderVersion() {
        for (app in listOf("", "unknown (historical process exit)", "1.0.9 (oops)", "1.0.9 (0)",
            "1.0.9 (-1)", "1.0.9 (9999999999)", "1.0.9 (131, bad revision)")) {
            assertNull(app, CrashReportVersion.fromReport(report(app)))
        }
        assertNull(CrashReportVersion.fromReport("No header"))
    }

    @Test fun historicalExitDoesNotClaimTheCollectingBuild() {
        assertNull(CrashReportVersion.fromReport(report("1.0.10 (132, new)")
            .replace("Logged error", "Last exit: anr (not responding)")))
    }

    @Test fun logAndUserTextCannotSupplyMissingHeader() {
        assertNull(CrashReportVersion.fromReport("No header\n\n" + report("1.0.10 (132, new)")))
        val text = report("1.0.9 (131, old)") + report("1.0.10 (132, new)")
        assertEquals("1.0.9", CrashReportVersion.fromReport(text)?.name)
    }
}
