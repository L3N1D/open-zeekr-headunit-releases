package com.lenid.zoh.carplay

import android.content.Context
import android.net.Uri
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

internal object CarPlayAuthentication {
    fun present(context: Context): Boolean {
        val dir = File(context.noBackupFilesDir, "offline-mfi")
        return File(dir, "identity.pk8").isFile && File(dir, "certificate.p7b").isFile
    }
    /** Explicit user-selected input only. No credentials are included in source or APK assets. */
    @Synchronized fun import(context: Context, uri: Uri) {
        val pair = context.contentResolver.openInputStream(uri)?.use(::readPair) ?: error("No input")
        val staging = File(context.noBackupFilesDir, "carplay-auth-import").apply { deleteRecursively(); mkdirs() }
        val destination = File(context.noBackupFilesDir, "offline-mfi")
        val previous = File(context.noBackupFilesDir, "carplay-auth-previous")
        try {
            pair.forEach { (name, bytes) -> File(staging, name).writeBytes(bytes) }
            LocalMfiAuthenticationClient.load(staging)
            previous.deleteRecursively()
            if (destination.exists()) check(destination.renameTo(previous))
            if (!staging.renameTo(destination)) {
                previous.renameTo(destination)
                error("Cannot save authentication")
            }
            previous.deleteRecursively()
        } finally {
            pair.values.forEach { it.fill(0) }
            staging.deleteRecursively()
        }
    }
    fun readPair(input: InputStream): Map<String, ByteArray> {
        val expected = setOf("identity.pk8", "certificate.p7b")
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 3)
                if (entry.isDirectory) { require(entry.name == "offline-mfi/"); continue }
                val name = entry.name.removePrefix("offline-mfi/")
                require(name in expected && name !in result)
                val bytes = zip.readBytesBounded(16 * 1024)
                require(bytes.isNotEmpty())
                result[name] = bytes
            }
        }
        require(result.keys == expected)
        return result
    }
    private fun InputStream.readBytesBounded(max: Int): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            require(result.size() + n <= max)
            result.write(buffer, 0, n)
        }
        return result.toByteArray()
    }
}
