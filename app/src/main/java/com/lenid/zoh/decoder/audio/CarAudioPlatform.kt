package com.lenid.zoh.decoder.audio

import android.os.Build
import com.lenid.zoh.utils.AppLog
import java.util.Locale

/**
 * Factory car infotainment (Geely/ECARX and its siblings) routes playback to amplifier buses by
 * audio attributes. A low-latency track there lands on a fast output that skips the car's media
 * DSP and amplifier tuning, so Android Auto music sounds thin and quiet, like a phone speaker.
 * On these platforms media must be a plain USAGE_MEDIA / CONTENT_TYPE_MUSIC track.
 */
internal object CarAudioPlatform {

    private val VENDOR_TOKENS = listOf("geely", "ecarx", "lynk", "zeekr", "proton", "galaxy_e")

    val isCarPlatform: Boolean by lazy { detect() }

    private fun detect(): Boolean {
        val fingerprint = listOf(
            Build.MANUFACTURER, Build.BRAND, Build.HARDWARE, Build.PRODUCT,
            Build.MODEL, Build.DEVICE, Build.BOARD
        ).joinToString("|") { it.orEmpty() }.lowercase(Locale.ROOT)
        val vendor = VENDOR_TOKENS.any { it in fingerprint }
        val carFramework = try {
            Class.forName("android.car.Car"); true
        } catch (_: Throwable) {
            false
        }
        val result = vendor || carFramework
        AppLog.i("CarAudioPlatform: car=$result (vendor=$vendor, carFramework=$carFramework, build=$fingerprint)")
        return result
    }
}
