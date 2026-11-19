package com.lenid.zoh.utils

/** Identity recorded when the report was created, never the build uploading it later. */
internal data class CrashReportVersion(val name: String, val code: String, val revision: String?) {
    val release get() = "open-zeekr-headunit@$name"

    companion object {
        private val appLine = Regex("""^App: ([A-Za-z0-9][A-Za-z0-9._+-]{0,99}) \(([0-9]{1,10})(?:, ([A-Za-z0-9._+-]{1,100}))?\)$""")

        fun fromReport(text: String): CrashReportVersion? {
            // Only the fixed header, not stack traces, user text or recent log entries.
            val header = text.take(8192).replace("\r\n", "\n").substringBefore("\n\n").lines()
            // Android's historical exit record has no version; older builds wrote the collecting
            // build there, which may already be an update. Do not trust that identity.
            if (header.firstOrNull()?.startsWith("Last exit: ") == true) return null
            val candidates = header.windowed(3).filter {
                it[1].startsWith("Device: ") && it[2].startsWith("Time: ")
            }.mapNotNull { appLine.matchEntire(it[0]) }
            val match = candidates.singleOrNull() ?: return null
            val code = match.groupValues[2].toLongOrNull()?.takeIf { it in 1..Int.MAX_VALUE } ?: return null
            return CrashReportVersion(match.groupValues[1], code.toString(), match.groupValues[3].ifEmpty { null })
        }
    }
}
