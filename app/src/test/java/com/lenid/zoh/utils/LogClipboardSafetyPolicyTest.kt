package com.lenid.zoh.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogClipboardSafetyPolicyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun smallFileReturnsExactContentWithoutTruncation() {
        val file = tempFolder.newFile("small.log")
        val content = "Line 1: system started\nLine 2: connected\nLine 3: finished\n"
        file.writeText(content, Charsets.UTF_8)

        val result = LogExporter.readLogFileForClipboard(file)
        assertEquals(content, result)
    }

    @Test
    fun emptyFileReturnsEmptyString() {
        val file = tempFolder.newFile("empty.log")
        file.writeText("", Charsets.UTF_8)

        val result = LogExporter.readLogFileForClipboard(file)
        assertEquals("", result)
    }

    @Test
    fun exactLimitFileReturnsExactContent() {
        val file = tempFolder.newFile("exact.log")
        val limit = LogExporter.CLIPBOARD_SAFE_LIMIT_BYTES
        val data = ByteArray(limit) { (it % 26 + 'a'.code).toByte() }
        file.writeBytes(data)

        val result = LogExporter.readLogFileForClipboard(file)
        assertEquals(String(data, Charsets.UTF_8), result)
    }

    @Test
    fun largeFileTruncatesMiddleAndPreservesHeadAndTailWithinSafeLimit() {
        val file = tempFolder.newFile("large.log")
        val totalSize = 500 * 1024 // 500 KB
        val headMarker = "START_OF_LOG_MARKER_TEST_HEAD\n"
        val tailMarker = "\nEND_OF_LOG_MARKER_TEST_TAIL\n"

        val buffer = ByteArray(totalSize) { (it % 10 + '0'.code).toByte() }
        // Inject markers at the very beginning and very end
        headMarker.toByteArray(Charsets.UTF_8).copyInto(buffer, 0)
        val tailBytes = tailMarker.toByteArray(Charsets.UTF_8)
        tailBytes.copyInto(buffer, totalSize - tailBytes.size)

        file.writeBytes(buffer)

        val result = LogExporter.readLogFileForClipboard(file)

        // Verifications
        assertTrue("Result should contain truncation notice", result.contains("[TRUNCATED for clipboard:"))
        assertTrue("Result should contain head marker", result.contains(headMarker))
        assertTrue("Result should contain tail marker", result.contains(tailMarker))
        assertTrue("Result should contain omitted notice", result.contains("bytes omitted in the middle"))

        // UTF-8 bytes must stay under 300 KB to safely respect Android 4 Binder limits (1 MB total process limit)
        val resultBytes = result.toByteArray(Charsets.UTF_8).size
        assertTrue("Result byte size ($resultBytes) must remain bounded near safe limit", resultBytes <= 260 * 1024)
    }
}
