// SPDX-License-Identifier: GPL-3.0-or-later
// Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI): livi-cp/src/vehicle_state/media.rs.
// Additional terms: docs/licenses/LIVI-NOTICE.txt.
package com.shilapi.xcertplay.airplay

/** A radio source CarPlay's Radio app knows, by LIVI's source identifier. */
enum class CarRadioBand(val source: String) {
    DAB("DAB"),
    FM("FM");

    companion object {
        fun named(source: String?): CarRadioBand? = entries.firstOrNull { it.source == source }
    }
}

/** One station in a source's list: a preset, or a DAB service. [id] is what the iPhone tunes back to. */
data class CarRadioStation(
    val id: String,
    val name: String,
    val shortName: String = name,
    val frequencyKhz: Int? = null,
    val ensemble: String = "",
)

/** The head unit's tuner as CarPlay's Radio app shows it. Empty lists and null values are what the tuner did not say. */
data class CarRadioReading(
    val band: CarRadioBand = CarRadioBand.FM,
    val frequencyKhz: Int? = null,
    val fmStations: List<CarRadioStation> = emptyList(),
    val fmCurrent: String? = null,
    val dabStations: List<CarRadioStation> = emptyList(),
    val dabCurrent: String? = null,
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false,
)

/** What the iPhone asked the radio to do (LIVI's `RadioCommand`). */
sealed class CarRadioCommand {
    data object Play : CarRadioCommand()
    data object Pause : CarRadioCommand()
    data object Next : CarRadioCommand()
    data object Previous : CarRadioCommand()
    data class Tune(val band: CarRadioBand, val id: String) : CarRadioCommand()
    data class TuneFrequency(val khz: Int) : CarRadioCommand()
    data class Source(val band: CarRadioBand) : CarRadioCommand()
}

object CarPlayRadio {
    const val FM_LOWEST_KHZ = 87_500
    const val FM_HIGHEST_KHZ = 108_000
    const val FM_STEP_KHZ = 100

    /** An FM station's identifier: its frequency, so a tune from the iPhone needs no lookup. */
    fun fmId(khz: Int): String = "fm-$khz"

    fun fmKhz(id: String): Int? = id.removePrefix("fm-").takeIf { it != id }?.toIntOrNull()

    /**
     * The `changeModes` command that gives the main audio to the car while its radio plays ([take]) and back to the
     * iPhone when it stops, shaped like LIVI's screen borrow, as a car with its own radio holds the audio.
     */
    fun mainAudio(take: Boolean): Map<String, Any?> = linkedMapOf(
        "type" to "changeModes",
        "params" to linkedMapOf("resources" to listOf(linkedMapOf<String, Any?>(
            "resourceID" to RESOURCE_MAIN_AUDIO,
            "transferType" to if (take) TRANSFER_TAKE else TRANSFER_UNTAKE,
        ).apply {
            if (take) {
                this["transferPriority"] = PRIORITY_USER_INITIATED
                this["takeConstraint"] = CONSTRAINT_ANYTIME
                this["borrowConstraint"] = CONSTRAINT_ANYTIME
                this["unborrowConstraint"] = CONSTRAINT_ANYTIME
            }
        })),
    )

    private const val RESOURCE_MAIN_AUDIO = 2L
    private const val TRANSFER_TAKE = 1L
    private const val TRANSFER_UNTAKE = 2L
    private const val PRIORITY_USER_INITIATED = 500L
    private const val CONSTRAINT_ANYTIME = 100L

    /** "101.5", the way a radio shows an FM frequency. */
    fun fmName(khz: Int): String = String.format(java.util.Locale.ROOT, "%d.%d", khz / 1000, khz % 1000 / 100)
}
