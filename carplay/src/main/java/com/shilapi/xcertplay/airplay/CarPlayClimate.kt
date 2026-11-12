package com.shilapi.xcertplay.airplay

import kotlin.math.roundToInt

/**
 * Which vehicle-state accessories Tarmac describes to the iPhone ([CarPlayVehicleState]). Climate, seat heating and
 * the radio are described only where the head unit reports them, so drivers never get controls that show nothing real.
 */
data class CarPlayVehicleProfile(val climate: Boolean, val seatHeating: Boolean, val radio: Boolean = false) {
    companion object {
        /** Everything Tarmac can describe; values stay in Tarmac. For a phone without a car, and for tests. */
        val ALL = CarPlayVehicleProfile(climate = true, seatHeating = true, radio = true)

        /** Vehicle resources only. */
        val NONE = CarPlayVehicleProfile(climate = false, seatHeating = false)
    }
}

/**
 * The car's climate as a head unit reports it, in CarPlay's terms. A null field is unknown: CarPlay keeps the
 * last value it had for it.
 */
data class CarClimateReading(
    val hvacOn: Boolean? = null,
    val compressorOn: Boolean? = null,
    val maxAcOn: Boolean? = null,
    val maxDefrostOn: Boolean? = null,
    val autoClimateOn: Boolean? = null,
    val recirculationOn: Boolean? = null,
    val zonesSynced: Boolean? = null,
    val driverTarget: CarTemperature? = null,
    val passengerTarget: CarTemperature? = null,
    val cabinCelsius: Double? = null,
    /** On CarPlay's scale, 0 to [CarPlayClimate.FAN_LEVEL_MAX]. */
    val fanLevel: Int? = null,
    /** On CarPlay's scale, 0 to [CarPlayClimate.SEAT_HEATING_MAX]. */
    val driverSeatHeating: Int? = null,
    val passengerSeatHeating: Int? = null,
)

/** A target temperature as a car's climate panel shows it: a value, or one of the panel's ends. */
sealed class CarTemperature {
    data class Celsius(val value: Double) : CarTemperature()
    data object Low : CarTemperature()
    data object High : CarTemperature()
}

enum class CarClimateZone { DRIVER, PASSENGER }

/** The climate switches the iPhone may change; comfort functions only (docs/CAR_CONTROLS.md). */
enum class CarClimateSwitch { AC, MAX_AC, RECIRCULATION, FRONT_DEFROST }

/** One change from the iPhone that passed the allow-list and its range check ([CarPlayClimate.command]). */
sealed class CarClimateCommand {
    data class Switch(val function: CarClimateSwitch, val on: Boolean) : CarClimateCommand()
    data class TargetTemperature(val zone: CarClimateZone, val celsius: Double) : CarClimateCommand()
    /** On CarPlay's scale, 0 to [CarPlayClimate.FAN_LEVEL_MAX]. */
    data class FanLevel(val level: Int) : CarClimateCommand()
    /** On CarPlay's scale, 0 to [CarPlayClimate.SEAT_HEATING_MAX]. */
    data class SeatHeating(val zone: CarClimateZone, val level: Int) : CarClimateCommand()
}

/** Turns a [CarClimateReading] into the characteristics [CarPlayVehicleState] describes, and iPhone writes into commands. */
object CarPlayClimate {
    const val TARGET_MIN_C = 16.0
    const val TARGET_MAX_C = 28.0
    const val TARGET_STEP_C = 0.5
    const val FAN_LEVEL_MAX = 7
    const val SEAT_HEATING_MAX = 2
    private const val CABIN_MIN_C = -40.0
    private const val CABIN_MAX_C = 85.0

    /** The characteristic values for [reading], by name; unknown fields are left out. */
    fun characteristics(reading: CarClimateReading): Map<String, Any> = buildMap {
        fun flag(name: String, value: Boolean?) { value?.let { put(name, it) } }
        flag("hvacOn", reading.hvacOn)
        flag("driverTemperatureOn", reading.hvacOn)
        flag("passengerTemperatureOn", reading.hvacOn)
        flag("compressorOn", reading.compressorOn)
        flag("maxACOn", reading.maxAcOn)
        flag("maxDefrostOn", reading.maxDefrostOn)
        flag("autoClimateOn", reading.autoClimateOn)
        // With automatic climate on, the car also runs the fan by itself.
        flag("fanAutoMode", reading.autoClimateOn)
        flag("recirculationOn", reading.recirculationOn)
        flag("zonesSynced", reading.zonesSynced)
        reading.driverTarget?.let { put("driverTargetTemperature", target(it)) }
        reading.passengerTarget?.let { put("passengerTargetTemperature", target(it)) }
        reading.cabinCelsius?.takeIf { it in CABIN_MIN_C..CABIN_MAX_C }?.let { cabin ->
            val rounded = step(cabin)
            put("driverCurrentTemperature", rounded)
            put("passengerCurrentTemperature", rounded)
        }
        reading.fanLevel?.coerceIn(0, FAN_LEVEL_MAX)?.let { level ->
            put("fanLevel", level.toLong())
            put("fanOn", level > 0)
        }
        reading.driverSeatHeating?.coerceIn(0, SEAT_HEATING_MAX)?.let { level ->
            put("driverSeatHeatingLevel", level.toLong())
            put("driverSeatHeatingOn", level > 0)
        }
        reading.passengerSeatHeating?.coerceIn(0, SEAT_HEATING_MAX)?.let { level ->
            put("passengerSeatHeatingLevel", level.toLong())
            put("passengerSeatHeatingOn", level > 0)
        }
    }

    /**
     * The allow-list for writes: temperature, fan, A/C, recirculation, defrost and seat heating, each with its value
     * checked against the range Tarmac describes. Anything else from the iPhone (power, AUTO, SYNC, other
     * characteristics, wrong types, values out of range) is null and refused.
     */
    fun command(name: String, value: Any?): CarClimateCommand? {
        fun switch(function: CarClimateSwitch) = (value as? Boolean)?.let { CarClimateCommand.Switch(function, it) }
        fun level(max: Int): Int? = (value as? Number)?.toDouble()
            ?.takeIf { it.isFinite() && it == Math.rint(it) && it >= 0 && it <= max }?.toInt()
        fun temperature(zone: CarClimateZone) = (value as? Number)?.toDouble()
            ?.takeIf { it.isFinite() && it in TARGET_MIN_C..TARGET_MAX_C && step(it) == it }
            ?.let { CarClimateCommand.TargetTemperature(zone, it) }
        return when (name) {
            "compressorOn" -> switch(CarClimateSwitch.AC)
            "maxACOn" -> switch(CarClimateSwitch.MAX_AC)
            "recirculationOn" -> switch(CarClimateSwitch.RECIRCULATION)
            "maxDefrostOn" -> switch(CarClimateSwitch.FRONT_DEFROST)
            "driverTargetTemperature" -> temperature(CarClimateZone.DRIVER)
            "passengerTargetTemperature" -> temperature(CarClimateZone.PASSENGER)
            "fanLevel" -> level(FAN_LEVEL_MAX)?.let { CarClimateCommand.FanLevel(it) }
            "driverSeatHeatingLevel" -> level(SEAT_HEATING_MAX)?.let { CarClimateCommand.SeatHeating(CarClimateZone.DRIVER, it) }
            "passengerSeatHeatingLevel" ->
                level(SEAT_HEATING_MAX)?.let { CarClimateCommand.SeatHeating(CarClimateZone.PASSENGER, it) }
            else -> null
        }
    }

    /** CarPlay's range is fixed, so LO and HI and values beyond it show as its ends. */
    fun target(temperature: CarTemperature): Double = when (temperature) {
        CarTemperature.Low -> TARGET_MIN_C
        CarTemperature.High -> TARGET_MAX_C
        is CarTemperature.Celsius -> step(temperature.value).coerceIn(TARGET_MIN_C, TARGET_MAX_C)
    }

    /** A car's fan level from 0 to [carMax] on CarPlay's 0–7 scale; a car's lowest level never shows as off. */
    fun fanLevel(level: Int, carMax: Int): Int? {
        if (carMax <= 0 || level < 0 || level > carMax) return null
        if (level == 0) return 0
        return (level.toDouble() * FAN_LEVEL_MAX / carMax).roundToInt().coerceIn(1, FAN_LEVEL_MAX)
    }

    fun fahrenheitToCelsius(value: Double): Double = (value - 32.0) * 5.0 / 9.0

    private fun step(celsius: Double): Double = (celsius / TARGET_STEP_C).roundToInt() * TARGET_STEP_C
}
