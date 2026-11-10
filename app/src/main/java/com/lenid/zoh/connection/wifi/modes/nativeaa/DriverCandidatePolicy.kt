package com.lenid.zoh.connection.wifi.modes.nativeaa

/**
 * Which paired Bluetooth devices can be a Native AA driver. Only a phone answers the wake poke,
 * and anything that does calls looks like a phone by device class. A class that rules a device
 * out wins over its records, because a car head unit with its own dialer advertises the Audio
 * Gateway too; otherwise that record, the one the poke dials, is what "phone" means here.
 */
object DriverCandidatePolicy {

    enum class Verdict { PHONE, UNKNOWN, NOT_A_PHONE }

    enum class Reason {
        AUDIO_GATEWAY, HANDS_FREE_UNIT, LOW_ENERGY_ONLY, CLASS_PHONE, CLASS_NOT_PHONE,
        GATEWAY_BUT_CLASS_NOT_PHONE, GATEWAY_BUT_CLASS_UNCATEGORIZED, PINNED, NO_SIGNAL
    }

    data class Classification(val verdict: Verdict, val reason: Reason)

    /**
     * How a stored MAC vouches for a device. USER is the preferred phone the user picked, and it
     * can name a watch; PROVEN is written only by a completed handshake, so it is always a phone.
     */
    enum class Pin { NONE, USER, PROVEN }

    /** Hands-Free and Headset Audio Gateway: the phone's half of the profile. */
    const val HFP_AG_UUID = "0000111f-0000-1000-8000-00805f9b34fb"
    const val HSP_AG_UUID = "00001112-0000-1000-8000-00805f9b34fb"

    /** Hands-Free unit and A2DP Sink: the car's half, which a dongle, a radio or a headset carries. */
    const val HFP_HF_UUID = HfpServiceRecordPolicy.HANDS_FREE_UUID
    const val A2DP_SINK_UUID = "0000110b-0000-1000-8000-00805f9b34fb"

    // Values of android.bluetooth.BluetoothClass.Device and BluetoothDevice.DEVICE_TYPE_LE, copied
    // so the policy needs no android import.
    const val MAJOR_PHONE = 0x0200
    const val MAJOR_AUDIO_VIDEO = 0x0400
    const val MAJOR_PERIPHERAL = 0x0500
    const val MAJOR_IMAGING = 0x0600
    const val MAJOR_WEARABLE = 0x0700
    const val MAJOR_TOY = 0x0800
    const val MAJOR_HEALTH = 0x0900
    const val MAJOR_UNCATEGORIZED = 0x1f00
    const val AV_WEARABLE_HEADSET = 0x0404
    const val AV_HANDSFREE = 0x0408
    const val AV_MICROPHONE = 0x0410
    const val AV_LOUDSPEAKER = 0x0414
    const val AV_HEADPHONES = 0x0418
    const val AV_PORTABLE_AUDIO = 0x041c
    const val AV_CAR_AUDIO = 0x0420
    const val AV_HIFI_AUDIO = 0x0428
    const val DEVICE_TYPE_UNKNOWN = 0
    const val DEVICE_TYPE_LE = 2

    private val AUDIO_GATEWAY_UUIDS = setOf(HFP_AG_UUID, HSP_AG_UUID)
    private val CAR_SIDE_UUIDS = setOf(HFP_HF_UUID, A2DP_SINK_UUID)
    private val NON_PHONE_MAJORS = setOf(MAJOR_WEARABLE, MAJOR_PERIPHERAL, MAJOR_IMAGING, MAJOR_HEALTH, MAJOR_TOY)
    private val NON_PHONE_AUDIO = setOf(
        AV_WEARABLE_HEADSET, AV_HANDSFREE, AV_MICROPHONE, AV_LOUDSPEAKER,
        AV_HEADPHONES, AV_PORTABLE_AUDIO, AV_CAR_AUDIO, AV_HIFI_AUDIO
    )

    /**
     * [uuids] are the bond's cached service records, null or empty when never fetched. A ruled-out
     * class beats the gateway record, then the records beat the rest; an LE-only bond cannot carry
     * the RFCOMM link at all; a class nobody ruled out is UNKNOWN, and a USER pin lifts only that.
     * A gateway record on an Uncategorized class is UNKNOWN too: an intercom or two-way radio
     * advertises it for call passthrough and sets no class, and one was picked as the driver.
     */
    fun classify(
        uuids: Collection<String>?,
        hasDeviceClass: Boolean,
        majorDeviceClass: Int,
        deviceClass: Int,
        deviceType: Int,
        pin: Pin = Pin.NONE
    ): Classification {
        if (pin == Pin.PROVEN) return Classification(Verdict.PHONE, Reason.PINNED)

        val records = uuids.orEmpty().map { it.lowercase() }
        val gateway = records.any { it in AUDIO_GATEWAY_UUIDS }
        val ruledOutByClass = hasDeviceClass && (
            majorDeviceClass in NON_PHONE_MAJORS ||
                (majorDeviceClass == MAJOR_AUDIO_VIDEO && deviceClass in NON_PHONE_AUDIO)
            )
        if (gateway && ruledOutByClass) return Classification(Verdict.NOT_A_PHONE, Reason.GATEWAY_BUT_CLASS_NOT_PHONE)
        val uncategorized = hasDeviceClass && majorDeviceClass == MAJOR_UNCATEGORIZED
        if (gateway && !uncategorized) return Classification(Verdict.PHONE, Reason.AUDIO_GATEWAY)

        val byClass = when {
            gateway -> Classification(Verdict.UNKNOWN, Reason.GATEWAY_BUT_CLASS_UNCATEGORIZED)
            records.any { it in CAR_SIDE_UUIDS } -> Classification(Verdict.NOT_A_PHONE, Reason.HANDS_FREE_UNIT)
            deviceType == DEVICE_TYPE_LE -> Classification(Verdict.NOT_A_PHONE, Reason.LOW_ENERGY_ONLY)
            !hasDeviceClass -> Classification(Verdict.UNKNOWN, Reason.NO_SIGNAL)
            majorDeviceClass == MAJOR_PHONE -> Classification(Verdict.PHONE, Reason.CLASS_PHONE)
            ruledOutByClass -> Classification(Verdict.NOT_A_PHONE, Reason.CLASS_NOT_PHONE)
            else -> Classification(Verdict.UNKNOWN, Reason.NO_SIGNAL)
        }
        if (pin == Pin.USER && byClass.verdict == Verdict.UNKNOWN) return Classification(Verdict.PHONE, Reason.PINNED)
        return byClass
    }

    /** The tier a list is offered at: any phone wins, else the unclassified, else nothing. */
    fun offeredVerdict(verdicts: Collection<Verdict>): Verdict = when {
        Verdict.PHONE in verdicts -> Verdict.PHONE
        Verdict.UNKNOWN in verdicts -> Verdict.UNKNOWN
        else -> Verdict.NOT_A_PHONE
    }

    /** The reason in a reporter's words, for the log line beside each device. */
    fun reasonText(classification: Classification, deviceClass: Int = 0): String = when (classification.reason) {
        Reason.AUDIO_GATEWAY -> "advertises the Audio Gateway record"
        Reason.HANDS_FREE_UNIT -> "advertises the Hands-Free unit or A2DP Sink record and no Audio Gateway"
        Reason.LOW_ENERGY_ONLY -> "bonded over Bluetooth LE only"
        Reason.CLASS_PHONE -> "device class is phone"
        Reason.CLASS_NOT_PHONE -> "device class 0x%04x is not a phone".format(deviceClass)
        Reason.GATEWAY_BUT_CLASS_NOT_PHONE ->
            "advertises the Audio Gateway record but device class 0x%04x is not a phone".format(deviceClass)
        Reason.GATEWAY_BUT_CLASS_UNCATEGORIZED ->
            "advertises the Audio Gateway record but its device class is uncategorized"
        Reason.PINNED -> if (classification.verdict == Verdict.PHONE) "vouched for by a stored MAC" else "pinned"
        Reason.NO_SIGNAL -> "no service record or device class says either way"
    }
}
