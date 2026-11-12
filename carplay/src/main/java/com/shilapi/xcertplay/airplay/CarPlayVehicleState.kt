// SPDX-License-Identifier: GPL-3.0-or-later
// Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI): livi-cp/src/vehicle_state.rs and vehicle_state/
// (climate.rs, body.rs, resources.rs, settings.rs, media.rs, wire.rs, rules.rs). Additional terms: docs/licenses/LIVI-NOTICE.txt.
package com.shilapi.xcertplay.airplay

/**
 * CarPlay's vehicle-state protocol (session feature `vehicleStateProtocol`), part of CarPlay Ultra; see
 * docs/CARPLAY_ULTRA.md.
 *
 * The car describes itself as accessories, services and numbered characteristics in `/info`
 * ([INFO_KEY]) and again on request. The iPhone then reads, follows and writes characteristics over two
 * type-130 data streams ([DATASTREAM_UUID], [HIGH_PRIORITY_DATASTREAM_UUID]) as OPACK messages. Values the
 * iPhone writes reach [onPhoneWrite]; values the car changes go to the iPhone with [carSets].
 *
 * It describes vehicle resources, plus climate, seat heating and the radio where [profile] says the head unit
 * reports them; the read-only car data in [carData] (fuel or charge, range, odometer, tyres), fed by [carReads]; and
 * [settings], the read-only lines iOS's Vehicle app shows as the car's settings. The radio's commands from the iPhone
 * reach [onRadio]; its state goes to the iPhone with [carSetRadio].
 */
class CarPlayVehicleState(
    private val log: (String) -> Unit = {},
    profile: CarPlayVehicleProfile = CarPlayVehicleProfile.ALL,
    /**
     * A characteristic the iPhone changed, by name. True accepts it; false refuses it, and the iPhone is given the
     * car's value back.
     */
    var onPhoneWrite: (name: String, value: Any?) -> Boolean = { _, _ -> true },
    /** A radio command from the iPhone (play, tune, next…), for the head unit's tuner. */
    var onRadio: (CarRadioCommand) -> Unit = {},
    /** The car data described; only what a source on this head unit gives, so iOS never shows invented values. */
    carData: Set<CarData> = emptySet(),
    /** Lines for the Vehicle app; a line for car data that is not described is left out. */
    settings: List<SettingLine> = emptyList(),
) {
    enum class Lane { HIGH, NORMAL }

    /** Read-only car data the Vehicle app can show; the car owns every one of them. */
    enum class CarData {
        /** The fuel gauge: the tank, or an electric car's charge, as LIVI shows it. */
        FUEL_LEVEL,
        RANGE,
        ODOMETER,
        TYRES,
        // LIVI has no accessory for these three; they are only Vehicle-app lines ([SettingLine]).
        CHARGING,
        BATTERY_ENERGY,
        OUTSIDE_TEMPERATURE,
    }

    /** One reading of the car; null is unknown. Tyre lists are front left, front right, rear left, rear right. */
    data class CarReading(
        val fuelPercent: Double? = null,
        val rangeKm: Double? = null,
        val odometerKm: Double? = null,
        val tyrePressureKpa: List<Int?> = emptyList(),
        val charging: Boolean? = null,
        val batteryEnergyKwh: Double? = null,
        val tyreTemperatureC: List<Int?> = emptyList(),
        val outsideTemperatureC: Double? = null,
    )

    /**
     * A read-only line in the Vehicle app (LIVI's automaker settings, `info_line`), in [section], which iOS shows under
     * its name. Sections appear in the order of their first line. A [data] line shows that car data as text, follows
     * the car's readings and reads "—" while it is unknown; any other line shows [value]. A yes/no value
     * ([CarData.CHARGING]) reads [yes] or [no]; a tyre line names its [wheel] (0 front left to 3 rear right) and shows
     * its pressure, with its temperature when the car gives one.
     */
    data class SettingLine(
        val id: String,
        val label: String,
        val symbol: String,
        val value: String = "",
        val data: CarData? = null,
        val section: Section = Section(),
        val yes: String = "",
        val no: String = "",
        val wheel: Int? = null,
    )

    /** A group of lines in the Vehicle app; [id] stays the same in every language. */
    data class Section(val id: String = "Tarmac", val name: String = "Tarmac")

    private enum class Format(val wire: Long) {
        BOOL(0), UINT8(1), UINT32(3), UINT64(4), INT32(7), FLOAT(9), STRING(10), DATA(11), DICT(12), ARRAY(13);

        val counts: Boolean get() = this == UINT32 || this == UINT64
    }
    private enum class Owner { PHONE, CAR, SHARED }

    private class Characteristic(
        val iid: Long,
        val type: Long,
        val name: String,
        val format: Format,
        val owner: Owner,
        val initial: Any?,
        range: Triple<Double, Double, Double>? = null,
        val units: Long? = null,
        val mutable: Boolean = true,
        /** For a large value (lists, artwork): a counter the car bumps on every change, which the iPhone follows. */
        val notifier: Long? = null,
        /** Whether the value may be "no value" (an empty current station, an unknown frequency). */
        val invalidable: Boolean = false,
    ) {
        // LIVI gives every count (uint32, uint64) the range 0 to u32::MAX unless one is set.
        val range = range ?: COUNT_RANGE.takeIf { format.counts }

        /** [n] on this characteristic's steps and range, in its format (LIVI's `take`). */
        fun take(n: Double): Any? {
            if (!n.isFinite()) return null
            val stepped = range?.let { (min, max, step) -> (min + Math.round((n - min) / step) * step).coerceIn(min, max) } ?: n
            return if (format == Format.FLOAT) stepped else Math.round(stepped)
        }

        fun describe(): Map<String, Any?> = linkedMapOf<String, Any?>(
            "iid" to iid,
            "type" to type,
            "format" to format.wire,
            "mutable" to mutable,
            "writable" to (owner != Owner.CAR),
            "oemWritable" to (owner != Owner.PHONE),
            "priority" to HIGH_PRIORITY,
        ).apply {
            range?.let { (min, max, step) ->
                fun number(n: Double): Any = if (format == Format.FLOAT) n else n.toLong()
                this["minimumValue"] = number(min)
                this["maximumValue"] = number(max)
                this["stepValue"] = number(step)
            }
            if (format in LENGTHY) this["maximumLength"] = if (notifier != null) LARGE_LENGTH else MAX_LENGTH
            notifier?.let {
                this["largePayload"] = true
                this["iidNotifier"] = it
            }
            units?.let { this["units"] = it }
            if (invalidable) this["supportsInvalid"] = true
            if (initial != null) this["initialValue"] = initial
        }
    }

    /** A command the iPhone may send; it is answered at once (LIVI's urgent commands). */
    private class Control(val iid: Long, val type: Long, val name: String, val parameters: List<Pair<String, Format>> = emptyList()) {
        fun describe(): Map<String, Any?> = linkedMapOf<String, Any?>(
            "iid" to iid,
            "type" to type,
            "sender" to SENT_BY_PHONE,
            "hasResponse" to true,
            "priority" to HIGH_PRIORITY,
        ).apply {
            if (parameters.isNotEmpty()) {
                this["requestParameters"] = parameters.map { (name, format) -> linkedMapOf("name" to name, "format" to format.wire) }
            }
        }
    }

    private class Service(val iid: Long, val type: Long, val characteristics: List<Characteristic>, val controls: List<Control> = emptyList())
    private class Accessory(val name: String, val iid: Long, val type: Long, val services: List<Service>)

    private val settingLines = settings.filter { it.data == null || it.data in carData }
    private val accessories = listOfNotNull(
        automakerSettings(settingLines),
        climate().takeIf { profile.climate },
        seats().takeIf { profile.seatHeating },
        media().takeIf { profile.radio },
        nowPlaying().takeIf { profile.radio },
        vehicleResources(),
    ) + carDataAccessories(carData)
    private val controls = accessories.flatMap { a -> a.services.flatMap { it.controls } }.associateBy { it.iid }
    private val byIid = accessories.flatMap { a -> a.services.flatMap { it.characteristics } }.associateBy { it.iid }
    private val byName = byIid.values.associateBy { it.name }
    private val values = LinkedHashMap<Long, Any?>().apply { byIid.values.forEach { c -> c.initial?.let { put(c.iid, it) } } }
    private val followed = HashSet<Long>()
    private val senders = java.util.EnumMap<Lane, (ByteArray) -> Unit>(Lane::class.java)

    /** Connects a lane's data stream; replies and updates go out through it. */
    @Synchronized fun attach(lane: Lane, send: (ByteArray) -> Unit) {
        senders[lane] = send
    }

    /** One message from the iPhone on [lane]; the replies go out on their lanes. */
    @Synchronized fun receive(lane: Lane, message: ByteArray) = transmit(handle(lane, message))

    /** A value the car changed; sent to the iPhone when it follows it. */
    @Synchronized fun carSet(name: String, value: Any?) = transmit(carSets(name, value))

    /** A reading of the car data; what changed goes to the iPhone in one update. */
    @Synchronized fun carReads(reading: CarReading) = transmit(carReadings(reading))

    /** The car data that is described. */
    val describedCarData: Set<CarData> = carData.filterTo(java.util.EnumSet.noneOf(CarData::class.java)) { data ->
        CAR_DATA_IIDS[data].orEmpty().any(byIid::containsKey) || settingLines.any { it.data == data }
    }
    /** The head unit's tuner now; what changed goes to the iPhone. */
    @Synchronized fun carSetRadio(radio: CarRadioReading) = transmit(radioSets(radio))

    private fun transmit(out: List<Pair<Lane, ByteArray>>) =
        out.forEach { (lane, bytes) -> (senders[lane] ?: senders.values.firstOrNull())?.invoke(bytes) }

    /** The `/info` entry, a plist dictionary. */
    fun infoEntry(): Pair<String, Map<String, Any?>> = INFO_KEY to linkedMapOf(
        "protocolVersion" to PROTOCOL_VERSION,
        "pluginCount" to 1L,
        "pluginMapping" to linkedMapOf(PLUGIN to PLUGIN_ID),
        "pluginConfigs" to listOf(linkedMapOf("name" to PLUGIN, "pluginID" to PLUGIN_ID, "accessories" to accessoryList())),
    )

    /** Answers one message from the iPhone; each reply goes out on the lane given with it. */
    fun handle(lane: Lane, message: ByteArray): List<Pair<Lane, ByteArray>> {
        val outer = runCatching { Opack.decode(message) as? Map<*, *> }.getOrNull()
            ?: return emptyList<Pair<Lane, ByteArray>>().also { log("vehicle-state: ${message.size} B not OPACK") }
        val request = (outer["pluginData"] as? ByteArray)?.let { runCatching { Opack.decode(it) as? Map<*, *> }.getOrNull() }
        if (request == null || (outer["pluginID"] as? Long) != PLUGIN_ID) {
            log("vehicle-state: message for plugin ${outer["pluginID"]} without readable data")
            return emptyList()
        }
        val transaction = request["transactionID"]
        val command = request["command"] as? String
        val requested = request["values"]
        return when (command) {
            "configRequest" -> {
                log("vehicle-state: iPhone reads the vehicle description")
                listOf(lane to wrap(linkedMapOf("command" to "configResponse", "transactionID" to transaction,
                    "values" to linkedMapOf("accessories" to accessoryList()))))
            }
            "registerRequest" -> {
                val iids = iids(requested)
                followed += iids
                log("vehicle-state: iPhone follows ${iids.mapNotNull { byIid[it]?.name ?: "#$it" }}")
                listOf(lane to wrap(answer("registerResponse", transaction, iids)))
            }
            "readRequest" -> listOf(lane to wrap(answer("readResponse", transaction, iids(requested))))
            "writeRequest" -> write(lane, requested as? Map<*, *> ?: emptyMap<Any?, Any?>(), transaction)
            "controlRequest" -> listOf(lane to wrap(control((requested as? Map<*, *>).orEmpty(), transaction)))
            else -> emptyList<Pair<Lane, ByteArray>>().also { log("vehicle-state: iPhone sent $command, not handled") }
        }
    }

    /** The car changed [name] (from a head-unit reading); returns the update for the iPhone, if it follows it. */
    fun carSets(name: String, value: Any?): List<Pair<Lane, ByteArray>> {
        val c = byName[name] ?: return emptyList()
        val changed = LinkedHashMap<Long, Any?>()
        set(c, value, changed)
        return if (changed.isNotEmpty()) listOf(updateNotify(changed)) else emptyList()
    }

    /** The radio's characteristics for [radio] (LIVI's `set_radio`); returns the update for what the iPhone follows. */
    fun radioSets(radio: CarRadioReading): List<Pair<Lane, ByteArray>> {
        val changed = LinkedHashMap<Long, Any?>()
        fun put(iid: Long, value: Any?) { byIid[iid]?.let { set(it, value, changed) } }
        fun items(stations: List<CarRadioStation>, band: CarRadioBand) = stations.map { radioItem(it, band) }
        put(DAB_DISABLED, radio.dabStations.isEmpty())
        put(DAB_ITEMS, items(radio.dabStations, CarRadioBand.DAB))
        put(DAB_CURRENT_ITEM, radio.dabCurrent.orEmpty())
        put(FM_DISABLED, false)
        put(FM_ITEMS, items(radio.fmStations, CarRadioBand.FM))
        put(FM_CURRENT_ITEM, radio.fmCurrent.orEmpty())
        radio.frequencyKhz?.takeIf { it in CarPlayRadio.FM_LOWEST_KHZ..CarPlayRadio.FM_HIGHEST_KHZ }
            ?.let { put(FM_FREQUENCY, it.toLong()) }
        put(NOW_PLAYING_SOURCE, radio.band.source)
        put(NOW_PLAYING_TITLE, radio.title)
        put(NOW_PLAYING_ARTIST, radio.artist)
        put(PLAYBACK_STATE, if (radio.playing) PLAYING else PAUSED)
        return if (changed.isNotEmpty()) listOf(updateNotify(changed)) else emptyList()
    }

    private fun set(c: Characteristic, value: Any?, changed: MutableMap<Long, Any?>) {
        val held = values[c.iid]
        if (held == value || (held is ByteArray && value is ByteArray && held.contentEquals(value))) return
        values[c.iid] = value
        if (c.iid in followed) changed[c.iid] = value
        c.notifier?.let { notifier ->
            val count = ((values[notifier] as? Long ?: 0L) + 1) % NOTIFIER_WRAP
            values[notifier] = count
            if (notifier in followed) changed[notifier] = count
        }
    }

    /** Answers the iPhone's commands; the radio's go to [onRadio] (LIVI's `control`). */
    private fun control(requested: Map<*, *>, transaction: Any?): Map<String, Any?> {
        val heard = CarRadioBand.named(values[NOW_PLAYING_SOURCE] as? String) ?: CarRadioBand.FM
        val answered = LinkedHashMap<Any?, Any?>()
        val refused = LinkedHashMap<Any?, Long>()
        for ((key, parameters) in requested) {
            val name = (key as? Long)?.let(controls::get)?.name
            if (name == null) {
                refused[key] = NO_SUCH_COMMAND
                continue
            }
            val given = parameters as? Map<*, *> ?: emptyMap<Any?, Any?>()
            fun text(k: String) = given[k] as? String
            val band = text("sourceIdentifier")?.let(CarRadioBand::named) ?: heard
            val command = when (name) {
                "play" -> CarRadioCommand.Play
                "pause" -> CarRadioCommand.Pause
                "nextItem" -> CarRadioCommand.Next
                "previousItem" -> CarRadioCommand.Previous
                "tuneToIdentifier" -> text("identifier")?.let { CarRadioCommand.Tune(band, it) }
                "tuneToFrequency" -> (given["frequency"] as? Long)?.takeIf { band == CarRadioBand.FM }
                    ?.let { CarRadioCommand.TuneFrequency(it.toInt()) }
                "changeMediaSource" -> text("identifier")?.let(CarRadioBand::named)?.let { CarRadioCommand.Source(it) }
                else -> null
            }
            log("vehicle-state: iPhone pressed $name $given")
            command?.let(onRadio)
            answered[key] = null
        }
        return linkedMapOf<String, Any?>("command" to "controlResponse", "transactionID" to transaction).apply {
            if (answered.isNotEmpty()) this["values"] = answered
            if (refused.isNotEmpty()) this["errors"] = refused
        }
    }

    /**
     * The updates for one car-data reading (LIVI's `set_gauges`): a range in kilometres and miles, the tyres with
     * their sensors active. A range or tyre that is unknown again is hidden or waiting again (Tarmac's addition),
     * so the iPhone does not keep showing a value nobody reads any more.
     */
    fun carReadings(reading: CarReading): List<Pair<Lane, ByteArray>> {
        val given = mutableListOf<Pair<Long, Any?>>()
        reading.fuelPercent?.let { given += FUEL_LEVEL to it }
        for ((km, distances) in listOf(reading.rangeKm to listOf(FUEL_RANGE, REMAINING_RANGE), reading.odometerKm to listOf(ODOMETER))) {
            for (d in distances) {
                if (km != null) {
                    given += d.km to km.coerceAtLeast(0.0)
                    given += d.km + 1 to km.coerceAtLeast(0.0) / KM_PER_MILE
                }
                d.hidden?.let { given += it to (km == null) }
            }
        }
        for (wheel in WHEELS.indices) {
            val kpa = reading.tyrePressureKpa.getOrNull(wheel)
            val iid = tyrePressureIid(wheel)
            if (kpa != null) given += iid to kpa.coerceAtLeast(0).toDouble()
            given += iid + 3 to if (kpa != null) SENSOR_ACTIVE else SENSOR_WAITING
        }
        settingLines.forEachIndexed { n, line -> if (line.data != null) given += settingLineIid(n) + 3 to settingText(line, reading) }
        val changed = LinkedHashMap<Long, Any?>()
        for ((iid, raw) in given) {
            val c = byIid[iid] ?: continue
            val value = if (raw is Double) c.take(raw) else raw
            if (value == null || values[iid] == value) continue
            values[iid] = value
            log("vehicle-state: car set ${c.name} to $value")
            if (iid in followed) changed[iid] = value
        }
        return if (changed.isEmpty()) emptyList() else listOf(updateNotify(changed))
    }

    private fun write(lane: Lane, written: Map<*, *>, transaction: Any?): List<Pair<Lane, ByteArray>> {
        val results = LinkedHashMap<Long, Long>()
        val changed = LinkedHashMap<Long, Any?>()
        for ((key, value) in written) {
            val iid = key as? Long ?: continue
            val c = byIid[iid]
            if (c == null || c.owner == Owner.CAR) {
                log("vehicle-state: iPhone may not write #$iid")
                results[iid] = NO_VALUE
                continue
            }
            val held = values[iid]
            if (held == value) {
                results[iid] = WRITTEN
                continue
            }
            if (!onPhoneWrite(c.name, value)) {
                log("vehicle-state: refused ${c.name} = $value from the iPhone")
                results[iid] = NO_VALUE
                if (iid in followed) changed[iid] = held
                continue
            }
            log("vehicle-state: iPhone set ${c.name} to $value")
            results[iid] = WRITTEN
            values[iid] = value
            if (iid in followed) changed[iid] = value
        }
        val out = mutableListOf(lane to wrap(linkedMapOf("command" to "writeResponse", "transactionID" to transaction, "errors" to results)))
        if (changed.isNotEmpty()) out += updateNotify(changed)
        return out
    }

    private fun answer(command: String, transaction: Any?, iids: List<Long>): Map<String, Any?> {
        val held = LinkedHashMap<Long, Any?>()
        val missing = LinkedHashMap<Long, Long>()
        for (iid in iids) if (values.containsKey(iid)) held[iid] = values[iid] else missing[iid] = NO_VALUE
        return linkedMapOf<String, Any?>("command" to command, "transactionID" to transaction, "values" to held).apply {
            if (missing.isNotEmpty()) this["errors"] = missing
        }
    }

    private fun updateNotify(changed: Map<Long, Any?>) =
        Lane.HIGH to wrap(linkedMapOf("command" to "updateNotify", "values" to changed))

    private fun iids(value: Any?): List<Long> = (value as? List<*>).orEmpty().filterIsInstance<Long>()

    private fun wrap(inner: Map<String, Any?>): ByteArray =
        Opack.encode(linkedMapOf("pluginID" to PLUGIN_ID, "pluginData" to Opack.encode(inner)))

    private fun accessoryList(): List<Map<String, Any?>> = accessories.map { a ->
        linkedMapOf(
            "iid" to a.iid,
            "type" to a.type,
            "version" to ACCESSORY_VERSION,
            "services" to a.services.map { s ->
                linkedMapOf<String, Any?>("iid" to s.iid, "type" to s.type, "characteristics" to s.characteristics.map { it.describe() })
                    .apply { if (s.controls.isNotEmpty()) this["controls"] = s.controls.map { it.describe() } }
            },
        )
    }

    companion object {
        const val FEATURE = "vehicleStateProtocol"
        const val INFO_KEY = "vehicleStateProtocolInfo"
        const val DATASTREAM_UUID = "3E2F3C61-AAD0-42CB-A8AA-BF22186DA62E"
        const val HIGH_PRIORITY_DATASTREAM_UUID = "FF4A6720-F2BE-4F56-A3E1-DB3B4E37D634"

        private const val PROTOCOL_VERSION = "1.0"
        private const val PLUGIN = "Tarmac"
        private const val PLUGIN_ID = 0L
        private const val ACCESSORY_VERSION = "1.0"
        private const val HIGH_PRIORITY = 1L
        private const val MAX_LENGTH = 65535L
        private const val LARGE_LENGTH = 2_147_483_647L
        private const val SENT_BY_PHONE = 0L
        private const val NOTIFIER_WRAP = 256L
        private val LENGTHY = setOf(Format.STRING, Format.DATA, Format.DICT, Format.ARRAY)
        private const val CELSIUS = 1082L
        private const val KILOMETERS = 151L
        private const val MILES = 164L
        private const val KILOPASCALS = 932L
        private const val PERCENT = 1205L
        private val COUNT_RANGE = Triple(0.0, 4_294_967_295.0, 1.0)
        private const val NO_VALUE = -6727L
        private const val WRITTEN = 0L
        private const val NO_SUCH_COMMAND = -6735L
        private const val TEMPERATURE_LO = 16.0
        private const val TEMPERATURE_HI = 28.0

        private const val ON_TYPE = 0x3000_0002L
        private const val AUTO_TYPE = 0x3000_005FL
        private const val LAYOUT_TYPE = 0x3600_0065L

        private fun shared(iid: Long, type: Long, name: String, format: Format, initial: Any?) =
            Characteristic(iid, type, name, format, Owner.SHARED, initial)

        private fun layout(iid: Long, key: String) =
            Characteristic(iid, LAYOUT_TYPE, "vehicleLayoutKey", Format.STRING, Owner.CAR, key, mutable = false)

        private fun on(iid: Long, name: String, initial: Boolean) = shared(iid, ON_TYPE, name, Format.BOOL, initial)

        private fun zone(iid: Long, names: List<String>, key: String) = Service(iid, 0x1100_0002L, listOf(
            on(iid + 1, names[0], true),
            Characteristic(iid + 2, 0x3100_0017L, names[1], Format.FLOAT, Owner.SHARED, 21.0,
                Triple(TEMPERATURE_LO, TEMPERATURE_HI, 0.5), CELSIUS),
            Characteristic(iid + 3, 0x3100_0019L, names[2], Format.FLOAT, Owner.CAR, 21.0, Triple(-40.0, 85.0, 0.5), CELSIUS),
            layout(iid + 4, key),
        ))

        private fun climate() = Accessory("climate", 512, 0x0100_0001L, listOf(
            Service(513, 0x1100_0001L, listOf(
                shared(514, 0x3100_0002L, "hvacOn", Format.BOOL, true),
                shared(515, 0x3100_0003L, "compressorOn", Format.BOOL, true),
                shared(516, 0x3100_0022L, "maxACOn", Format.BOOL, false),
                shared(517, 0x3100_0029L, "maxDefrostOn", Format.BOOL, false),
            )),
            zone(518, listOf("driverTemperatureOn", "driverTargetTemperature", "driverCurrentTemperature"), "seat_front_left"),
            zone(532, listOf("passengerTemperatureOn", "passengerTargetTemperature", "passengerCurrentTemperature"), "seat_front_right"),
            Service(523, 0x1100_0007L, listOf(
                on(524, "fanOn", true),
                Characteristic(525, 0x3100_0012L, "fanLevel", Format.UINT8, Owner.SHARED, 3L, Triple(0.0, 7.0, 1.0)),
                shared(526, AUTO_TYPE, "fanAutoMode", Format.BOOL, true),
                layout(531, "seat_front"),
            )),
            Service(527, 0x1100_0011L, listOf(on(528, "recirculationOn", false))),
            Service(529, 0x1100_0008L, listOf(on(530, "autoClimateOn", true))),
            Service(537, 0x1100_0012L, listOf(on(538, "zonesSynced", false), layout(539, "seat_front"))),
        ))

        private fun seats() = Accessory("seats", 1024, 0x0150_0001L, listOf(
            seatHeating(1025, "driverSeatHeatingLevel", "driverSeatHeatingOn", "seat_front_left"),
            seatHeating(1029, "passengerSeatHeatingLevel", "passengerSeatHeatingOn", "seat_front_right"),
        ))

        private fun seatHeating(iid: Long, level: String, onName: String, key: String) = Service(iid, 0x2200_0001L, listOf(
            Characteristic(iid + 1, 0x3100_0021L, level, Format.INT32, Owner.SHARED, 0L, Triple(0.0, 2.0, 1.0)),
            on(iid + 2, onName, false),
            layout(iid + 3, key),
        ))

        // LIVI's media and nowPlaying accessories (media.rs). Names are Tarmac's own: DAB and FM share iOS's names.
        private const val DAB_SERVICE = 4097L
        private const val DAB_DISABLED = 4100L
        private const val DAB_CURRENT_ITEM = 4102L
        private const val DAB_ITEMS = 4103L
        private const val FM_SERVICE = 4112L
        private const val FM_DISABLED = 4115L
        private const val FM_CURRENT_ITEM = 4116L
        private const val FM_FREQUENCY = 4117L
        private const val FM_ITEMS = 4118L
        private const val NOW_PLAYING_SERVICE = 4353L
        private const val NOW_PLAYING_SOURCE = 4354L
        private const val NOW_PLAYING_TITLE = 4355L
        private const val NOW_PLAYING_ARTIST = 4356L
        private const val PLAYBACK_STATE = 4359L
        private const val PAUSED = 0L
        private const val PLAYING = 1L
        private const val DAB_SEMANTIC = 6L
        private const val FM_SEMANTIC = 2L

        private fun car(iid: Long, type: Long, name: String, format: Format, initial: Any?, range: Triple<Double, Double, Double>? = null,
            mutable: Boolean = true, notifier: Long? = null, invalidable: Boolean = false) =
            Characteristic(iid, type, name, format, Owner.CAR, initial, range, mutable = mutable, notifier = notifier, invalidable = invalidable)

        private fun sourceHead(service: Long, band: CarRadioBand, semantic: Long, prefix: String) = listOf(
            car(service + 1, 0x3000_0019L, "$prefix.mediaSourceIdentifier", Format.STRING, band.source, mutable = false),
            car(service + 2, 0x3200_0025L, "$prefix.mediaSourceSemanticType", Format.UINT8, semantic, Triple(0.0, 8.0, 1.0), mutable = false),
            car(service + 3, 0x3200_0011L, "$prefix.mediaSourceDisabled", Format.BOOL, true),
        )

        private fun media() = Accessory("media", 4096, 0x0300_0001L, listOf(
            Service(DAB_SERVICE, 0x1400_0006L, sourceHead(DAB_SERVICE, CarRadioBand.DAB, DAB_SEMANTIC, "dab") + listOf(
                car(4101, 0x3000_0001L, "dab.mediaSourceLabel", Format.STRING, "DAB+"),
                car(DAB_CURRENT_ITEM, 0x3200_0026L, "dab.currentMediaItem", Format.STRING, "", invalidable = true),
                car(DAB_ITEMS, 0x3200_0012L, "dab.mediaItems", Format.ARRAY, emptyList<Any?>(), notifier = 4105),
                car(4104, 0x3200_0031L, "dab.mediaItemImages", Format.ARRAY, emptyList<Any?>(), notifier = 4106),
            )),
            Service(FM_SERVICE, 0x1400_0006L, sourceHead(FM_SERVICE, CarRadioBand.FM, FM_SEMANTIC, "fm") + listOf(
                car(FM_CURRENT_ITEM, 0x3200_0026L, "fm.currentMediaItem", Format.STRING, "", invalidable = true),
                car(FM_FREQUENCY, 0x3200_0007L, "fm.currentFrequency", Format.UINT32, CarPlayRadio.FM_LOWEST_KHZ.toLong(),
                    Triple(CarPlayRadio.FM_LOWEST_KHZ.toDouble(), CarPlayRadio.FM_HIGHEST_KHZ.toDouble(), CarPlayRadio.FM_STEP_KHZ.toDouble()),
                    invalidable = true),
                car(FM_ITEMS, 0x3200_0012L, "fm.mediaItems", Format.ARRAY, emptyList<Any?>(), notifier = 4119),
            )),
        ))

        private fun nowPlaying() = Accessory("nowPlaying", 4352, 0x0F00_0001L, listOf(
            Service(NOW_PLAYING_SERVICE, 0x1400_0001L, listOf(
                car(NOW_PLAYING_SOURCE, 0x3200_0023L, "currentMediaSource", Format.STRING, CarRadioBand.FM.source),
                car(NOW_PLAYING_TITLE, 0x3200_0003L, "title", Format.STRING, ""),
                car(NOW_PLAYING_ARTIST, 0x3200_0004L, "artist", Format.STRING, ""),
                car(4357, 0x3200_0005L, "album", Format.STRING, ""),
                car(4358, 0x3200_0020L, "artwork", Format.DATA, ByteArray(0), notifier = 4367),
                car(PLAYBACK_STATE, 0x3200_0034L, "playbackState", Format.UINT8, PAUSED, Triple(0.0, 2.0, 1.0)),
            ), controls = listOf(
                Control(4360, 0x0F00_0032L, "play"),
                Control(4361, 0x0F00_0033L, "pause"),
                Control(4362, 0x0F00_0035L, "nextItem"),
                Control(4363, 0x0F00_0036L, "previousItem"),
                Control(4364, 0x0F00_004BL, "tuneToIdentifier", listOf("identifier" to Format.STRING, "sourceIdentifier" to Format.STRING)),
                Control(4365, 0x0F00_004CL, "tuneToFrequency", listOf("frequency" to Format.UINT32, "sourceIdentifier" to Format.STRING)),
                Control(4366, 0x0F00_004DL, "changeMediaSource", listOf("identifier" to Format.STRING)),
            )),
        ))

        /** One station as iOS lists it (LIVI's `radio_item`). */
        private fun radioItem(s: CarRadioStation, band: CarRadioBand): Map<String, Any?> = linkedMapOf<String, Any?>(
            "identifier" to s.id,
            "mediaItemName" to s.name,
            "mediaItemShortName" to s.shortName,
            "mediaItemType" to band.source.lowercase(java.util.Locale.ROOT),
            "mediaItemCategory" to 0L,
        ).apply {
            if (s.ensemble.isNotEmpty()) this["ensemble"] = s.ensemble
            s.frequencyKhz?.let { this["frequency"] = it.toLong() }
        }

        private fun vehicleResources() = Accessory("vehicle resources", 768, 0x0120_0002L, listOf(
            Service(769, 0x2000_0001L, listOf(
                Characteristic(770, 0x4800_0004L, "interiorStyle", Format.STRING, Owner.CAR, "5Seater", mutable = false),
            )),
        ))

        // Car data, from LIVI's vehicle_state/body.rs (fuel, trip computer, tires) with LIVI's ids and type codes. LIVI
        // always describes them and starts at 0; Tarmac describes only [carData] and leaves a reading unset until the
        // car gives one, so the iPhone gets "no value" (-6727) rather than an invented 0. LIVI's fuel-efficiency
        // service (1544) is left out: no Tarmac source reads consumption.
        private class Distance(val km: Long, val hidden: Long?, val names: List<String>)

        private const val FUEL_LEVEL = 1538L
        private const val LOW_FUEL_PCT = 10L
        private val FUEL_RANGE = Distance(1541, 1543, listOf("fuelRangeKm", "fuelRangeMiles", "fuelRangeHidden"))
        private val REMAINING_RANGE = Distance(1794, 1796, listOf("remainingRangeKm", "remainingRangeMiles", "remainingRangeHidden"))
        private val ODOMETER = Distance(1798, null, listOf("odometerKm", "odometerMiles"))
        private const val KM_PER_MILE = 1.609_344
        private val WHEELS = listOf("tire_front_left", "tire_front_right", "tire_rear_left", "tire_rear_right")
        private const val TIRES = 2048L
        private const val PRESSURE_UNDEFINED = 0L
        private const val SENSOR_ACTIVE = 1L
        private const val SENSOR_WAITING = 3L

        private fun tyrePressureIid(wheel: Int) = TIRES + 2 + 5L * wheel

        /** The characteristics that carry each kind of car data, for [describedCarData]. */
        private val CAR_DATA_IIDS = mapOf(
            CarData.FUEL_LEVEL to listOf(FUEL_LEVEL),
            CarData.RANGE to listOf(FUEL_RANGE.km, REMAINING_RANGE.km),
            CarData.ODOMETER to listOf(ODOMETER.km),
            CarData.TYRES to WHEELS.indices.map(::tyrePressureIid),
        )

        private fun distance(iid: Long, type: Long, d: Distance) = Service(iid, type, listOfNotNull(
            Characteristic(d.km, 0x3000_004AL, d.names[0], Format.UINT64, Owner.CAR, null, units = KILOMETERS),
            Characteristic(d.km + 1, 0x3000_004BL, d.names[1], Format.UINT64, Owner.CAR, null, units = MILES),
            d.hidden?.let { Characteristic(it, 0x3600_0023L, d.names[2], Format.BOOL, Owner.CAR, true) },
        ))

        // The Vehicle app's lines, from LIVI's vehicle_state/settings.rs: lines from 1281, eight ids each, then the
        // sections, four ids each (LIVI's two lines and one section put it at 1297). LIVI's lines are fixed; a car-data
        // line's value changes, as LIVI's version-line symbol does once its image archive is fetched.
        private const val UNKNOWN = "—"

        private fun settingLineIid(n: Int) = 1281L + 8 * n

        private fun automakerSettings(lines: List<SettingLine>): Accessory? = if (lines.isEmpty()) null else {
            fun fixed(iid: Long, type: Long, name: String, value: String) =
                Characteristic(iid, type, name, Format.STRING, Owner.CAR, value, mutable = false)
            fun order(iid: Long, name: String, n: Int) =
                Characteristic(iid, 0x3000_0003L, name, Format.UINT8, Owner.CAR, n.toLong(), Triple(0.0, 100.0, 1.0), mutable = false)
            val sections = lines.map { it.section }.distinctBy { it.id }
            Accessory("automaker settings", 1280, 0x0500_0001L, lines.mapIndexed { n, line ->
                val iid = settingLineIid(n)
                Service(iid, 0x1600_0015L, listOf(
                    fixed(iid + 1, 0x3000_0019L, "settingIdentifier", line.id),
                    fixed(iid + 2, 0x3000_0001L, "settingLabel", line.label),
                    Characteristic(iid + 3, 0x3000_0017L, "settingValue", Format.STRING, Owner.CAR,
                        if (line.data != null) UNKNOWN else line.value.ifEmpty { "?" }, mutable = line.data != null),
                    Characteristic(iid + 4, 0x3600_0006L, "settingsCategory", Format.UINT8, Owner.CAR, 0L,
                        Triple(0.0, 15.0, 1.0), mutable = false),
                    fixed(iid + 5, 0x3000_005EL, "settingSymbol", line.symbol),
                    order(iid + 6, "settingSortOrder", lines.take(n).count { it.section.id == line.section.id }),
                    fixed(iid + 7, 0x3600_0028L, "settingSection", line.section.id),
                ))
            } + sections.mapIndexed { k, section ->
                val iid = settingLineIid(lines.size) + 4L * k
                Service(iid, 0x1600_0020L, listOf(
                    fixed(iid + 1, 0x3000_0019L, "sectionIdentifier", section.id),
                    order(iid + 2, "sectionSortOrder", k),
                    fixed(iid + 3, 0x3600_0004L, "sectionName", section.name),
                ))
            })
        }

        /** A car-data line's text, in the units the sources give: %, km, kWh, kPa and °C. */
        private fun settingText(line: SettingLine, reading: CarReading): String {
            val locale = java.util.Locale.getDefault()
            return when (line.data) {
                CarData.FUEL_LEVEL -> reading.fuelPercent?.let { "${Math.round(it)} %" }
                CarData.RANGE -> reading.rangeKm?.let { "${Math.round(it)} km" }
                CarData.ODOMETER -> reading.odometerKm?.let { String.format(locale, "%,d km", Math.round(it)) }
                CarData.TYRES -> line.wheel?.let { wheel ->
                    reading.tyrePressureKpa.getOrNull(wheel)?.let { kpa ->
                        listOfNotNull("$kpa kPa", reading.tyreTemperatureC.getOrNull(wheel)?.let { "$it °C" }).joinToString(" · ")
                    }
                }
                CarData.CHARGING -> reading.charging?.let { if (it) line.yes else line.no }
                CarData.BATTERY_ENERGY -> reading.batteryEnergyKwh?.let { String.format(locale, "%.1f kWh", it) }
                CarData.OUTSIDE_TEMPERATURE -> reading.outsideTemperatureC?.let { "${Math.round(it)} °C" }
                null -> null
            } ?: UNKNOWN
        }

        private fun carDataAccessories(data: Set<CarData>): List<Accessory> = listOf(
            Accessory("fuel", 1536, 0x0900_0006L, listOfNotNull(
                Service(1537, 0x1A00_0005L, listOf(
                    Characteristic(FUEL_LEVEL, 0x4100_0002L, "fuelLevel", Format.UINT8, Owner.CAR, null,
                        Triple(0.0, 100.0, 1.0), PERCENT),
                    Characteristic(1539, 0x4100_001AL, "fuelLevelMarkerLow", Format.UINT8, Owner.CAR, LOW_FUEL_PCT,
                        Triple(0.0, 100.0, 1.0), PERCENT, mutable = false),
                )).takeIf { CarData.FUEL_LEVEL in data },
                distance(1540, 0x1A00_0015L, FUEL_RANGE).takeIf { CarData.RANGE in data },
            )),
            Accessory("trip computer", 1792, 0x0400_0001L, listOfNotNull(
                distance(1793, 0x1A00_000BL, REMAINING_RANGE).takeIf { CarData.RANGE in data },
                distance(1797, 0x1A00_000AL, ODOMETER).takeIf { CarData.ODOMETER in data },
            )),
            Accessory("tires", TIRES, 0x0A00_0001L, if (CarData.TYRES !in data) emptyList() else WHEELS.mapIndexed { wheel, key ->
                val iid = tyrePressureIid(wheel)
                Service(iid - 1, 0x1B00_0001L, listOf(
                    Characteristic(iid, 0x3000_001BL, "tirePressureKpa", Format.UINT32, Owner.CAR, null,
                        Triple(0.0, 1000.0, 1.0), KILOPASCALS),
                    layout(iid + 1, key),
                    Characteristic(iid + 2, 0x3000_001CL, "tirePressureState", Format.UINT8, Owner.CAR, PRESSURE_UNDEFINED,
                        Triple(0.0, 5.0, 1.0)),
                    Characteristic(iid + 3, 0x3000_001AL, "tireSensorState", Format.UINT8, Owner.CAR, SENSOR_WAITING,
                        Triple(0.0, 4.0, 1.0)),
                ))
            }),
        ).filter { it.services.isNotEmpty() }
    }
}
