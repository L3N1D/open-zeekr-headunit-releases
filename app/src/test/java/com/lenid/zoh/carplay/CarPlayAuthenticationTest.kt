package com.lenid.zoh.carplay
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
class CarPlayAuthenticationTest {
    private fun archive(vararg files: Pair<String,ByteArray>): ByteArray {
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> files.forEach { (name,data) ->
            zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry()
        } }
        return out.toByteArray()
    }
    @Test fun acceptsOnlyTheTwoExplicitInputs() {
        val result=CarPlayAuthentication.readPair(archive("offline-mfi/identity.pk8" to byteArrayOf(1),
            "offline-mfi/certificate.p7b" to byteArrayOf(2)).inputStream())
        assertEquals(setOf("identity.pk8","certificate.p7b"),result.keys)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsTraversal() {
        CarPlayAuthentication.readPair(archive("../identity.pk8" to byteArrayOf(1)).inputStream())
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsMissingCertificate() {
        CarPlayAuthentication.readPair(archive("identity.pk8" to byteArrayOf(1)).inputStream())
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsOversizedInput() {
        CarPlayAuthentication.readPair(archive("identity.pk8" to ByteArray(16385)).inputStream())
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsUnrelatedPayload() {
        CarPlayAuthentication.readPair(archive("other.apk" to byteArrayOf(1)).inputStream())
    }
}
