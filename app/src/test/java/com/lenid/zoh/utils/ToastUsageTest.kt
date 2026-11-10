package com.lenid.zoh.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Show Toast Messages" only works if every toast goes through [ToastUtils]. It shipped with four
 * files converted and the rest raw, which is how the auto-connect toast ignored it for three
 * releases.
 */
class ToastUsageTest {

    @Test
    fun `no source file raises a toast without going through ToastUtils`() {
        // Both flavour source sets too: they are production code and only one of them is built.
        val roots = listOf("src/main/java", "src/github/java", "src/playstore/java").map(::File)
        assertTrue("src/main/java not found from ${File(".").absolutePath}", roots.all { it.isDirectory })

        val sources = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
        }
        // A wrong working directory would otherwise pass on an empty scan.
        assertTrue("only ${sources.size} sources scanned", sources.size > 200)

        val offenders = sources
            .filterNot { it.name == "ToastUtils.kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> RAW_TOAST.containsMatchIn(line) }
                    .map { (index, _) -> "${file.path}:${index + 1}" }
            }

        assertEquals(
            "call ToastUtils.showToast instead, with force = true only when the message must " +
                "arrive whatever the setting says:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }

    private companion object {
        val RAW_TOAST = Regex("""(?<![\w.])Toast\.makeText\s*\(""")
    }
}
