package com.lenid.zoh.connection.wifi.modes.nativeaa

import com.lenid.zoh.connection.wifi.modes.nativeaa.DriverCandidatePolicy.Pin
import com.lenid.zoh.connection.wifi.modes.nativeaa.DriverCandidatePolicy.Reason
import com.lenid.zoh.connection.wifi.modes.nativeaa.DriverCandidatePolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class DriverCandidatePolicyTest {

    private val audioGateway = DriverCandidatePolicy.HFP_AG_UUID
    private val headsetGateway = DriverCandidatePolicy.HSP_AG_UUID
    private val handsFree = DriverCandidatePolicy.HFP_HF_UUID
    private val a2dpSink = DriverCandidatePolicy.A2DP_SINK_UUID
    private val classic = 1

    private fun classify(
        uuids: List<String>? = null,
        hasClass: Boolean = true,
        major: Int = 0,
        deviceClass: Int = 0,
        type: Int = classic,
        pin: Pin = Pin.NONE
    ) = DriverCandidatePolicy.classify(uuids, hasClass, major, deviceClass, type, pin)

    private fun assertVerdict(verdict: Verdict, reason: Reason, actual: DriverCandidatePolicy.Classification) {
        assertEquals(DriverCandidatePolicy.Classification(verdict, reason), actual)
    }

    @Test
    fun `the audio gateway record is a phone when the class does not rule it out`() {
        assertVerdict(Verdict.PHONE, Reason.AUDIO_GATEWAY, classify(uuids = listOf(audioGateway), hasClass = false))
        assertVerdict(Verdict.PHONE, Reason.AUDIO_GATEWAY, classify(uuids = listOf(headsetGateway)))
        assertVerdict(
            Verdict.PHONE, Reason.AUDIO_GATEWAY,
            classify(uuids = listOf(audioGateway), major = DriverCandidatePolicy.MAJOR_PHONE, deviceClass = 0x020c)
        )
        assertVerdict(Verdict.PHONE, Reason.AUDIO_GATEWAY, classify(uuids = listOf(audioGateway), major = 0x0100))
    }

    @Test
    fun `a head unit with its own dialer is not a phone despite its audio gateway record`() {
        assertVerdict(
            Verdict.NOT_A_PHONE, Reason.GATEWAY_BUT_CLASS_NOT_PHONE,
            classify(uuids = listOf(audioGateway, handsFree), major = DriverCandidatePolicy.MAJOR_AUDIO_VIDEO, deviceClass = DriverCandidatePolicy.AV_HANDSFREE)
        )
        assertVerdict(
            Verdict.NOT_A_PHONE, Reason.GATEWAY_BUT_CLASS_NOT_PHONE,
            classify(uuids = listOf(headsetGateway), major = DriverCandidatePolicy.MAJOR_AUDIO_VIDEO, deviceClass = DriverCandidatePolicy.AV_CAR_AUDIO)
        )
        assertVerdict(
            Verdict.NOT_A_PHONE, Reason.GATEWAY_BUT_CLASS_NOT_PHONE,
            classify(uuids = listOf(audioGateway), major = DriverCandidatePolicy.MAJOR_WEARABLE, deviceClass = 0x0704)
        )
    }

    @Test
    fun `an uncategorized class with a gateway record is unknown, not a phone`() {
        assertVerdict(
            Verdict.UNKNOWN, Reason.GATEWAY_BUT_CLASS_UNCATEGORIZED,
            classify(uuids = listOf(audioGateway), major = DriverCandidatePolicy.MAJOR_UNCATEGORIZED, deviceClass = 0x1f00)
        )
        assertVerdict(
            Verdict.UNKNOWN, Reason.GATEWAY_BUT_CLASS_UNCATEGORIZED,
            classify(uuids = listOf(headsetGateway, handsFree), major = DriverCandidatePolicy.MAJOR_UNCATEGORIZED, deviceClass = 0x1f00)
        )
        assertEquals(
            "advertises the Audio Gateway record but its device class is uncategorized",
            DriverCandidatePolicy.reasonText(classify(uuids = listOf(audioGateway), major = DriverCandidatePolicy.MAJOR_UNCATEGORIZED))
        )
    }

    @Test
    fun `a pin lifts an uncategorized gateway device`() {
        assertVerdict(
            Verdict.PHONE, Reason.PINNED,
            classify(uuids = listOf(audioGateway), major = DriverCandidatePolicy.MAJOR_UNCATEGORIZED, pin = Pin.USER)
        )
        assertVerdict(
            Verdict.PHONE, Reason.PINNED,
            classify(uuids = listOf(audioGateway), major = DriverCandidatePolicy.MAJOR_UNCATEGORIZED, pin = Pin.PROVEN)
        )
    }

    @Test
    fun `the reason names both the record and the class that overruled it`() {
        val nav = classify(uuids = listOf(audioGateway), major = DriverCandidatePolicy.MAJOR_AUDIO_VIDEO, deviceClass = DriverCandidatePolicy.AV_HANDSFREE)
        assertEquals(
            "advertises the Audio Gateway record but device class 0x0408 is not a phone",
            DriverCandidatePolicy.reasonText(nav, DriverCandidatePolicy.AV_HANDSFREE)
        )
    }

    @Test
    fun `a device carrying both halves of hands-free is a phone`() {
        assertVerdict(Verdict.PHONE, Reason.AUDIO_GATEWAY, classify(uuids = listOf(handsFree, audioGateway)))
    }

    @Test
    fun `a wireless dongle advertising only the hands-free unit is not a phone`() {
        assertVerdict(
            Verdict.NOT_A_PHONE, Reason.HANDS_FREE_UNIT,
            classify(uuids = listOf(handsFree), major = DriverCandidatePolicy.MAJOR_AUDIO_VIDEO, deviceClass = DriverCandidatePolicy.AV_HANDSFREE)
        )
    }

    @Test
    fun `a car radio advertising hands-free unit and a2dp sink is not a phone`() {
        assertVerdict(
            Verdict.NOT_A_PHONE, Reason.HANDS_FREE_UNIT,
            classify(uuids = listOf(handsFree, a2dpSink), major = DriverCandidatePolicy.MAJOR_AUDIO_VIDEO, deviceClass = DriverCandidatePolicy.AV_CAR_AUDIO)
        )
    }

    @Test
    fun `a headset advertising only a2dp sink is not a phone`() {
        assertVerdict(
            Verdict.NOT_A_PHONE, Reason.HANDS_FREE_UNIT,
            classify(uuids = listOf(a2dpSink), major = DriverCandidatePolicy.MAJOR_AUDIO_VIDEO, deviceClass = DriverCandidatePolicy.AV_HEADPHONES)
        )
    }

    @Test
    fun `a watch bonded over low energy with no class is not a phone`() {
        assertVerdict(
            Verdict.NOT_A_PHONE, Reason.LOW_ENERGY_ONLY,
            classify(uuids = null, hasClass = false, type = DriverCandidatePolicy.DEVICE_TYPE_LE)
        )
    }

    @Test
    fun `a classic watch is ruled out by its class`() {
        assertVerdict(
            Verdict.NOT_A_PHONE, Reason.CLASS_NOT_PHONE,
            classify(major = DriverCandidatePolicy.MAJOR_WEARABLE, deviceClass = 0x0704)
        )
    }

    @Test
    fun `a phone whose records were never fetched is a phone by class`() {
        assertVerdict(
            Verdict.PHONE, Reason.CLASS_PHONE,
            classify(uuids = null, major = DriverCandidatePolicy.MAJOR_PHONE, deviceClass = 0x020c)
        )
    }

    @Test
    fun `an unreadable class with no records is unknown, not a phone`() {
        assertVerdict(Verdict.UNKNOWN, Reason.NO_SIGNAL, classify(uuids = null, hasClass = false))
    }

    @Test
    fun `a computer class is unknown, never a phone`() {
        assertVerdict(Verdict.UNKNOWN, Reason.NO_SIGNAL, classify(major = 0x0100, deviceClass = 0x010c))
    }

    @Test
    fun `an audio device of an unlisted subclass is unknown, never a phone`() {
        assertVerdict(
            Verdict.UNKNOWN, Reason.NO_SIGNAL,
            classify(major = DriverCandidatePolicy.MAJOR_AUDIO_VIDEO, deviceClass = 0x0430)
        )
    }

    @Test
    fun `an audio output class is not a phone`() {
        for (subclass in listOf(
            DriverCandidatePolicy.AV_LOUDSPEAKER, DriverCandidatePolicy.AV_HEADPHONES,
            DriverCandidatePolicy.AV_WEARABLE_HEADSET, DriverCandidatePolicy.AV_PORTABLE_AUDIO,
            DriverCandidatePolicy.AV_HIFI_AUDIO, DriverCandidatePolicy.AV_CAR_AUDIO,
            DriverCandidatePolicy.AV_HANDSFREE, DriverCandidatePolicy.AV_MICROPHONE
        )) {
            assertVerdict(
                Verdict.NOT_A_PHONE, Reason.CLASS_NOT_PHONE,
                classify(major = DriverCandidatePolicy.MAJOR_AUDIO_VIDEO, deviceClass = subclass)
            )
        }
    }

    @Test
    fun `record matching ignores case and an empty list reads as unfetched`() {
        assertVerdict(Verdict.PHONE, Reason.AUDIO_GATEWAY, classify(uuids = listOf(audioGateway.uppercase())))
        assertVerdict(
            Verdict.PHONE, Reason.CLASS_PHONE,
            classify(uuids = emptyList(), major = DriverCandidatePolicy.MAJOR_PHONE, deviceClass = 0x020c)
        )
    }

    @Test
    fun `a preferred pin never lifts a device that was ruled out`() {
        assertVerdict(
            Verdict.NOT_A_PHONE, Reason.LOW_ENERGY_ONLY,
            classify(hasClass = false, type = DriverCandidatePolicy.DEVICE_TYPE_LE, pin = Pin.USER)
        )
        assertVerdict(
            Verdict.NOT_A_PHONE, Reason.CLASS_NOT_PHONE,
            classify(major = DriverCandidatePolicy.MAJOR_WEARABLE, deviceClass = 0x0704, pin = Pin.USER)
        )
        assertVerdict(
            Verdict.NOT_A_PHONE, Reason.HANDS_FREE_UNIT,
            classify(uuids = listOf(handsFree), pin = Pin.USER)
        )
        assertVerdict(
            Verdict.NOT_A_PHONE, Reason.GATEWAY_BUT_CLASS_NOT_PHONE,
            classify(uuids = listOf(audioGateway), major = DriverCandidatePolicy.MAJOR_AUDIO_VIDEO, deviceClass = DriverCandidatePolicy.AV_HANDSFREE, pin = Pin.USER)
        )
    }

    @Test
    fun `a preferred pin lifts only an unknown device`() {
        assertVerdict(Verdict.PHONE, Reason.PINNED, classify(hasClass = false, pin = Pin.USER))
        assertVerdict(Verdict.PHONE, Reason.PINNED, classify(major = 0x0100, pin = Pin.USER))
    }

    @Test
    fun `a proven pin is a phone whatever the records and class say`() {
        assertVerdict(
            Verdict.PHONE, Reason.PINNED,
            classify(uuids = listOf(handsFree), major = DriverCandidatePolicy.MAJOR_WEARABLE, type = DriverCandidatePolicy.DEVICE_TYPE_LE, pin = Pin.PROVEN)
        )
        assertVerdict(
            Verdict.PHONE, Reason.PINNED,
            classify(uuids = listOf(audioGateway), major = DriverCandidatePolicy.MAJOR_AUDIO_VIDEO, deviceClass = DriverCandidatePolicy.AV_HANDSFREE, pin = Pin.PROVEN)
        )
    }

    @Test
    fun `the offered tier is phones, else the unclassified, else nothing`() {
        assertEquals(Verdict.PHONE, DriverCandidatePolicy.offeredVerdict(listOf(Verdict.NOT_A_PHONE, Verdict.UNKNOWN, Verdict.PHONE)))
        assertEquals(Verdict.UNKNOWN, DriverCandidatePolicy.offeredVerdict(listOf(Verdict.NOT_A_PHONE, Verdict.UNKNOWN)))
        assertEquals(Verdict.NOT_A_PHONE, DriverCandidatePolicy.offeredVerdict(listOf(Verdict.NOT_A_PHONE)))
        assertEquals(Verdict.NOT_A_PHONE, DriverCandidatePolicy.offeredVerdict(emptyList()))
    }
}
