package com.lenid.zoh.connection.usb

/**
 * Turns the accessory-switch return code into the sentence a reporter's log needs.
 *
 * The native switch returns libusb's own code and the caller collapsed it to a boolean, so a device
 * that refuses Android Auto outright and one that never answered looked identical. The distinction
 * is the whole diagnosis: a stall is the device saying it does not speak AOAP.
 */
object AoaSwitchFailurePolicy {

    /** No device handle to switch. Ours, not libusb's, so it cannot be read as LIBUSB_ERROR_IO. */
    const val NO_HANDLE = -101

    /** The JNI call itself threw. Ours for the same reason. */
    const val CALL_FAILED = -102

    fun describe(code: Int): String = when (code) {
        0 -> "the switch succeeded"
        -1 -> "the transfer failed on the bus"
        -2 -> "the device does not support the accessory protocol"
        -4, -5 -> "the device left the bus during the switch"
        -7 -> "the device did not answer the Android Auto handshake in time"
        -9 -> "the device refused the Android Auto handshake (stalled), so it is not offering " +
            "Android Auto over USB"
        -12 -> "this unit's USB stack does not support the request"
        NO_HANDLE -> "the device could not be opened for the switch"
        CALL_FAILED -> "the switch call itself failed"
        else -> "libusb error $code"
    }

    /** The line both switch paths log on failure. */
    fun failureLine(code: Int): String =
        "UsbAccessoryMode: AOA switch failed: ${describe(code)} (code $code)"
}
