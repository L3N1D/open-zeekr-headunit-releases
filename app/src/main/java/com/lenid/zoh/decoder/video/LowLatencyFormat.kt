package com.lenid.zoh.decoder.video

import android.media.MediaFormat
import android.os.Build

/**
 * The low-latency keys for the decoders that do not go through [DecoderConfigLadder]: the AutoKit
 * dongle and the cluster stream. Both run on Zeekr's Snapdragon 8155, where Codec2's
 * `c2.qti.avc.decoder` honours Qualcomm's own switch besides Android 11's official one. The vendor
 * key goes only to a Qualcomm component, since another may refuse a configure with a key it
 * does not know.
 */
object LowLatencyFormat {

    fun apply(format: MediaFormat, codecName: String) {
        if (Build.VERSION.SDK_INT >= DecoderConfigLadder.LOW_LATENCY_API) {
            format.setInteger(DecoderConfigLadder.KEY_LOW_LATENCY, 1)
        }
        // Realtime: the codec is clocked for frames as they come, not for battery.
        format.setInteger(DecoderConfigLadder.KEY_PRIORITY, 0)
        if (isQualcomm(codecName)) format.setInteger(DecoderConfigLadder.QUALCOMM_LOW_LATENCY, 1)
    }

    internal fun isQualcomm(codecName: String): Boolean {
        val name = codecName.lowercase()
        return name.startsWith("c2.qti.") || name.startsWith("omx.qcom.")
    }
}
