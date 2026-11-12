package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayUltraTest {
    private val config = AirPlayConfig(
        deviceName = "test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02",
        sourceVersion = "950.7.1",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        cluster = CarPlayClusterDisplay.config(1280, 720, scalePercent = 100),
    )

    // The iPhone's SETUP proposal, as an iOS 27 iPhone sent it.
    private val proposed = listOf(
        "uiContext", "viewAreas", "cornerMasks", "focusTransfer", "h.264Level5.1", "mainBuffered", "altScreen",
        "enhancedSiri", "hevc", "fileTransfer", "uiSync", "vehicleStateProtocol", "sessionManagement",
        "videoPlayback", "logTransfer", "iAPChannel",
    )

    @Test fun ordinaryCarPlayEnablesNoUltraFeature() {
        val enabled = setupEnabledFeatures(config, proposed)
        assertTrue(CarPlayUltra.SESSION_FEATURES.none { it in enabled })
        assertFalse(AirPlayInfoPlist.build(config).containsKey("uiContextNowOnDisplayURLs"))
    }

    @Test fun theExperimentEnablesOnlyWhatTheIphoneProposed() {
        val ultra = config.copy(ultraFeatures = CarPlayUltra.SESSION_FEATURES)
        assertTrue(setupEnabledFeatures(ultra, proposed).containsAll(CarPlayUltra.SESSION_FEATURES))
        val older = setupEnabledFeatures(ultra, listOf("iAPChannel", "uiSync"))
        assertTrue("uiSync" in older)
        assertFalse("vehicleStateProtocol" in older)
    }

    @Test fun uiContextDeclaresWhatEachScreenShows() {
        val info = AirPlayInfoPlist.build(config.copy(ultraFeatures = setOf("uiContext")))
        assertEquals(emptyList<String>(), info["uiContextLastOnDisplayURLs"])
        assertEquals(listOf(CarPlayClusterDisplay.MAP_URL), info["uiContextNowOnDisplayURLs"])
    }

    @Test fun theUltraInfoRequestDecodes() {
        // An iPhone 17 Pro Max on iOS 27.2 with uiContext enabled: stopSessionReasons are 16-byte integers.
        val hex = "62706c6973743030d301020304080d5d616c7453637265656e55524c735d7569436f6e7465787455524c735f101273746f70" +
            "53657373696f6e526561736f6e73a30506075f101f6d6170733a2f6361722f696e737472756d656e74636c75737465722f6d61" +
            "705f102b6d6170733a2f6361722f696e737472756d656e74636c75737465722f696e737472756374696f6e636172645f101b6d" +
            "6170733a2f6361722f696e737472756d656e74636c7573746572a4090a0b0c556d6170733a5c6d6f62696c6570686f6e653a5b" +
            "6e6f77706c6179696e673a596d657373616765733aa20e0f1400000000000000000000000000000001140000000000000000000000" +
            "00000000020008000f001d002b004000440066009400b200b700bd00ca00d600e000e300f4000000000000020100000000000000" +
            "1000000000000000000000000000000105"
        val request = BplistCodec.decode(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()) as Map<*, *>
        assertEquals(listOf(1L, 2L), request["stopSessionReasons"])
        assertEquals(listOf("maps:", "mobilephone:", "nowplaying:", "messages:"), request["uiContextURLs"])
        assertEquals(3, (request["altScreenURLs"] as List<*>).size)
    }

    @Test fun anOverrideNarrowsTheFeaturesToKnownNames() {
        assertEquals(CarPlayUltra.DEFAULT_FEATURES, CarPlayUltra.features(null))
        assertEquals(setOf("uiSync", "vehicleStateProtocol"), CarPlayUltra.features(" uiSync,altScreen , vehicleStateProtocol"))
        assertTrue(CarPlayUltra.features("").isEmpty())
    }

    @Test fun packagesAreDescribedAsPlistsOrHex() {
        val plist = CarPlayUltra.describePackage("sync", 0x10, 7, BplistCodec.encode(linkedMapOf("speed" to 42L)))
        assertTrue(plist, plist.startsWith("type=sync command=0x10 seq=7 body="))
        assertTrue(plist, plist.contains("speed=42"))
        assertTrue(CarPlayUltra.describePackage("comm", 0, 1, byteArrayOf(1, 0xab.toByte())).endsWith("01ab"))
    }

    @Test fun aProbedStreamLogsEachPackageAndAnswersSync() {
        val readKey = ByteArray(32) { 1 }
        val writeKey = ByteArray(32) { 2 }
        val logs = CopyOnWriteArrayList<String>()
        val loopback = InetAddress.getLoopbackAddress()
        val channel = VideoSettingsChannel(readKey, writeKey, loopback, label = "ultra TEST", logPackages = true) { logs += it }
        try {
            val port = channel.listen(loopback)
            Socket(loopback, port).use { socket ->
                socket.soTimeout = 3000
                val body = BplistCodec.encode(linkedMapOf("gear" to "D"))
                val size = 32 + body.size
                val sync = ByteArray(32)
                sync[2] = (size shr 8).toByte()
                sync[3] = size.toByte()
                "sync".toByteArray(Charsets.US_ASCII).copyInto(sync, 4)
                sync[27] = 5
                val plain = sync + body
                val aad = byteArrayOf(plain.size.toByte(), (plain.size shr 8).toByte())
                socket.getOutputStream().write(aad + AirPlayCrypto.chachaSeal(readKey, AirPlayCrypto.nonce64(0), plain, aad))
                socket.getOutputStream().flush()
                val reply = ByteArray(2 + 32 + 16)
                var read = 0
                while (read < reply.size) {
                    val n = socket.getInputStream().read(reply, read, reply.size - read)
                    assertTrue("channel closed before replying", n >= 0)
                    read += n
                }
                val opened = AirPlayCrypto.chachaOpen(writeKey, AirPlayCrypto.nonce64(0), reply.copyOfRange(2, reply.size), reply.copyOfRange(0, 2))
                assertEquals("rply", String(opened, 4, 4, Charsets.US_ASCII))
            }
            val line = logs.single { it.startsWith("ultra TEST rx #1 ") }
            assertTrue(line, line.contains("type=sync") && line.contains("seq=5") && line.contains("gear=D"))
        } finally {
            channel.close()
        }
    }
}
