package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayVehicleStateTest {
    private val climateAndSeats = CarPlayVehicleProfile(climate = true, seatHeating = true)

    private fun request(command: String, values: Any?, transaction: Long = 7): ByteArray = Opack.encode(
        linkedMapOf("pluginID" to 0L, "pluginData" to Opack.encode(
            linkedMapOf("command" to command, "transactionID" to transaction, "values" to values),
        )),
    )

    private fun inner(bytes: ByteArray): Map<*, *> = Opack.decode((Opack.decode(bytes) as Map<*, *>)["pluginData"] as ByteArray) as Map<*, *>

    @Test fun opackRoundTripsWhatTheProtocolUses() {
        val value = linkedMapOf<Any?, Any?>(
            "small" to 5L, "negative" to -6727L, "wide" to 0x3100_0017L, "real" to 21.5, "flag" to true,
            "text" to "seat_front_left", "data" to byteArrayOf(1, 2, 3), "list" to (0L..20L).toList(), 520L to null,
        )
        val decoded = Opack.decode(Opack.encode(value)) as Map<*, *>
        assertEquals(value.keys.toList(), decoded.keys.toList())
        assertEquals(-6727L, decoded["negative"])
        assertEquals(0x3100_0017L, decoded["wide"])
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded["data"] as ByteArray)
        assertEquals((0L..20L).toList(), decoded["list"])
        // Short forms: 0..39 in one byte, a 4-character string in five.
        assertArrayEquals(byteArrayOf(0x0d), Opack.encode(5L))
        assertArrayEquals(byteArrayOf(0x44, 0x74, 0x65, 0x78, 0x74), Opack.encode("text"))
    }

    @Test fun theInfoEntryDescribesClimateAndEncodesAsAPlist() {
        val (key, info) = CarPlayVehicleState(profile = climateAndSeats).infoEntry()
        assertEquals("vehicleStateProtocolInfo", key)
        assertEquals("1.0", info["protocolVersion"])
        val accessories = ((info["pluginConfigs"] as List<*>).single() as Map<*, *>)["accessories"] as List<*>
        assertEquals(listOf(512L, 1024L, 768L), accessories.map { (it as Map<*, *>)["iid"] })
        BplistCodec.decode(BplistCodec.encode(mapOf(key to info))) // the /info reply carries it as a plist
    }

    @Test fun theIphoneConfiguresFollowsAndWrites() {
        val sent = mutableListOf<Pair<CarPlayVehicleState.Lane, ByteArray>>()
        val written = mutableListOf<Pair<String, Any?>>()
        val state = CarPlayVehicleState(profile = climateAndSeats, onPhoneWrite = { name, value -> written += name to value; true })
        state.attach(CarPlayVehicleState.Lane.NORMAL) { sent += CarPlayVehicleState.Lane.NORMAL to it }
        state.attach(CarPlayVehicleState.Lane.HIGH) { sent += CarPlayVehicleState.Lane.HIGH to it }

        state.receive(CarPlayVehicleState.Lane.NORMAL, request("configRequest", null))
        val config = inner(sent.removeAt(0).second)
        assertEquals("configResponse", config["command"])
        assertEquals(7L, config["transactionID"])
        assertEquals(3, ((config["values"] as Map<*, *>)["accessories"] as List<*>).size)

        state.receive(CarPlayVehicleState.Lane.NORMAL, request("registerRequest", listOf(520L, 9999L)))
        val register = inner(sent.removeAt(0).second)
        assertEquals(mapOf(520L to 21.0), register["values"])
        assertEquals(mapOf(9999L to -6727L), register["errors"])

        state.receive(CarPlayVehicleState.Lane.NORMAL, request("writeRequest", mapOf(520L to 22.5, 521L to 30.0)))
        val (lane, reply) = sent.removeAt(0)
        assertEquals(CarPlayVehicleState.Lane.NORMAL, lane)
        assertEquals(mapOf(520L to 0L, 521L to -6727L), inner(reply)["errors"]) // 521 is the car's own reading
        val (updateLane, update) = sent.removeAt(0)
        assertEquals(CarPlayVehicleState.Lane.HIGH, updateLane)
        assertEquals("updateNotify", inner(update)["command"])
        assertEquals(listOf("driverTargetTemperature" to 22.5), written)

        state.carSet("driverCurrentTemperature", 19.5) // not followed: nothing sent
        assertTrue(sent.isEmpty())
    }

    private fun accessories(state: CarPlayVehicleState): Map<Long, Map<*, *>> =
        (((state.infoEntry().second["pluginConfigs"] as List<*>).single() as Map<*, *>)["accessories"] as List<*>)
            .associate { (it as Map<*, *>)["iid"] as Long to it }

    private fun services(accessory: Map<*, *>?): Map<Long, List<Map<*, *>>> = (accessory?.get("services") as List<*>? ?: emptyList<Any>())
        .associate { s -> (s as Map<*, *>)["iid"] as Long to (s["characteristics"] as List<*>).map { it as Map<*, *> } }

    @Test fun carDataIsDescribedOnlyWhereASourceGivesIt() {
        assertEquals(listOf(512L, 1024L, 768L), accessories(CarPlayVehicleState(profile = climateAndSeats)).keys.toList())
        assertEquals(emptySet<CarPlayVehicleState.CarData>(), CarPlayVehicleState(profile = climateAndSeats).describedCarData)

        val all = CarPlayVehicleState(profile = climateAndSeats, carData = CarPlayVehicleState.CarData.entries.toSet())
        val described = accessories(all)
        assertEquals(listOf(512L, 1024L, 768L, 1536L, 1792L, 2048L), described.keys.toList())
        // Charging, energy and outside temperature have no accessory; without a Vehicle-app line they are not described.
        assertEquals(setOf(CarPlayVehicleState.CarData.FUEL_LEVEL, CarPlayVehicleState.CarData.RANGE,
            CarPlayVehicleState.CarData.ODOMETER, CarPlayVehicleState.CarData.TYRES), all.describedCarData)
        // LIVI's fuel-efficiency service (1544) needs a consumption reading, which no source gives.
        assertEquals(listOf(1537L, 1540L), services(described[1536L]).keys.toList())
        assertEquals(listOf(1793L, 1797L), services(described[1792L]).keys.toList())
        assertEquals(listOf(2049L, 2054L, 2059L, 2064L), services(described[2048L]).keys.toList())
        val fuelLevel = services(described[1536L]).getValue(1537L).first()
        assertEquals(0x4100_0002L, fuelLevel["type"])
        assertEquals(1205L, fuelLevel["units"])
        assertFalse("no reading, no invented 0", fuelLevel.containsKey("initialValue"))
        val range = services(described[1536L]).getValue(1540L)
        assertEquals(listOf(1541L, 1542L, 1543L), range.map { it["iid"] })
        assertEquals(listOf(151L, 164L, null), range.map { it["units"] })
        assertEquals(4L, range[0]["format"])
        assertEquals(4_294_967_295L, range[0]["maximumValue"])
        assertEquals(true, range[2]["initialValue"]) // fuelRangeHidden until the car gives one
        val tyre = services(described[2048L]).getValue(2049L)
        assertEquals(listOf("tire_front_left", 3L), listOf(tyre[1]["initialValue"], tyre[3]["initialValue"]))
        assertEquals(932L, tyre[0]["units"])
        BplistCodec.decode(BplistCodec.encode(mapOf(all.infoEntry().first to all.infoEntry().second)))

        val rangeOnly = CarPlayVehicleState(profile = climateAndSeats, carData = setOf(CarPlayVehicleState.CarData.RANGE))
        val partial = accessories(rangeOnly)
        assertEquals(listOf(512L, 1024L, 768L, 1536L, 1792L), partial.keys.toList())
        assertEquals(listOf(1540L), services(partial[1536L]).keys.toList())
        assertEquals(listOf(1793L), services(partial[1792L]).keys.toList())
        assertEquals(setOf(CarPlayVehicleState.CarData.RANGE), rangeOnly.describedCarData)
    }

    @Test fun carReadingsReachTheIphoneAsOneUpdate() {
        val state = CarPlayVehicleState(profile = climateAndSeats, carData = CarPlayVehicleState.CarData.entries.toSet())
        val sent = mutableListOf<ByteArray>()
        state.attach(CarPlayVehicleState.Lane.HIGH) { sent += it }
        state.attach(CarPlayVehicleState.Lane.NORMAL) { sent += it }
        val followed = listOf(1538L, 1541L, 1542L, 1543L, 1794L, 1796L, 1798L, 2050L, 2053L, 2055L, 2058L)
        state.receive(CarPlayVehicleState.Lane.NORMAL, request("registerRequest", followed))
        val register = inner(sent.removeAt(0))
        assertEquals(mapOf(1543L to true, 1796L to true, 2053L to 3L, 2058L to 3L), register["values"])
        assertEquals(setOf(1538L, 1541L, 1542L, 1794L, 1798L, 2050L, 2055L), (register["errors"] as Map<*, *>).keys)

        state.carReads(CarPlayVehicleState.CarReading(fuelPercent = 63.6, rangeKm = 312.0, odometerKm = 12_345.4,
            tyrePressureKpa = listOf(230, null, 228, 231)))
        val update = inner(sent.single().also { sent.clear() })
        assertEquals("updateNotify", update["command"])
        assertEquals(mapOf(1538L to 64L, 1541L to 312L, 1542L to 194L, 1543L to false, 1794L to 312L, 1796L to false,
            1798L to 12_345L, 2050L to 230L, 2053L to 1L), update["values"])

        state.carReads(CarPlayVehicleState.CarReading(fuelPercent = 63.6, rangeKm = 312.0, odometerKm = 12_345.4,
            tyrePressureKpa = listOf(230, null, 228, 231)))
        assertTrue("nothing changed, nothing sent", sent.isEmpty())

        // The source is lost: the range hides and the sensors wait again; the last fuel level stays.
        state.carReads(CarPlayVehicleState.CarReading())
        assertEquals(mapOf(1543L to true, 1796L to true, 2053L to 3L), inner(sent.single())["values"])
    }

    @Test fun theVehicleAppListsTarmacsLinesAndFollowsTheCar() {
        val state = CarPlayVehicleState(profile = climateAndSeats, carData = setOf(CarPlayVehicleState.CarData.RANGE), settings = listOf(
            CarPlayVehicleState.SettingLine("TarmacVersion", "Version", "info.circle", "0.3.0"),
            CarPlayVehicleState.SettingLine("TarmacRange", "Range", "road.lanes", data = CarPlayVehicleState.CarData.RANGE),
            CarPlayVehicleState.SettingLine("TarmacTyres", "Tyre pressures", "tirepressure", data = CarPlayVehicleState.CarData.TYRES),
        ))
        val described = accessories(state)
        assertEquals(listOf(1280L, 512L, 1024L, 768L, 1536L, 1792L), described.keys.toList())
        // Two lines (tyres are not described, so neither is their line) and the section after them, as in LIVI.
        val lines = services(described[1280L])
        assertEquals(listOf(1281L, 1289L, 1297L), lines.keys.toList())
        assertEquals(listOf("TarmacVersion", "Version", "0.3.0", 0L, "info.circle", 0L, "Tarmac"),
            lines.getValue(1281L).map { it["initialValue"] })
        assertEquals(listOf(false, true), listOf(1281L, 1289L).map { lines.getValue(it)[2]["mutable"] })
        assertEquals("—", lines.getValue(1289L)[2]["initialValue"])
        assertEquals(listOf("Tarmac", 0L, "Tarmac"), lines.getValue(1297L).map { it["initialValue"] })

        val sent = mutableListOf<ByteArray>()
        state.attach(CarPlayVehicleState.Lane.HIGH) { sent += it }
        state.receive(CarPlayVehicleState.Lane.HIGH, request("registerRequest", listOf(1292L)))
        sent.clear()
        state.carReads(CarPlayVehicleState.CarReading(rangeKm = 312.4, tyrePressureKpa = listOf(230, 231, 229, 230)))
        assertEquals(mapOf(1292L to "312 km"), inner(sent.single())["values"])
    }

    @Test fun linesGroupIntoSectionsAndTyresGetARowEach() {
        val battery = CarPlayVehicleState.Section("TarmacBattery", "Battery")
        val tyres = CarPlayVehicleState.Section("TarmacTyres", "Tyres")
        val charging = CarPlayVehicleState.SettingLine("TarmacCharging", "Charging", "powerplug",
            data = CarPlayVehicleState.CarData.CHARGING, section = battery, yes = "Yes", no = "No")
        val state = CarPlayVehicleState(profile = climateAndSeats, carData = setOf(CarPlayVehicleState.CarData.CHARGING, CarPlayVehicleState.CarData.TYRES),
            settings = listOf(charging) + (0..1).map { wheel ->
                CarPlayVehicleState.SettingLine("TarmacTyre$wheel", "Wheel $wheel", "tirepressure",
                    data = CarPlayVehicleState.CarData.TYRES, section = tyres, wheel = wheel)
            } + CarPlayVehicleState.SettingLine("TarmacVersion", "Version", "info.circle", "0.3.0"))
        val lines = services(accessories(state)[1280L])
        // Four lines from 1281, then a section every four ids, in the order of their first line.
        assertEquals(listOf(1281L, 1289L, 1297L, 1305L, 1313L, 1317L, 1321L), lines.keys.toList())
        assertEquals(listOf("TarmacBattery", 0L, "Battery"), lines.getValue(1313L).map { it["initialValue"] })
        assertEquals(listOf("Tarmac", 2L, "Tarmac"), lines.getValue(1321L).map { it["initialValue"] })
        assertEquals(listOf(0L, 0L, 1L, 0L), listOf(1281L, 1289L, 1297L, 1305L).map { lines.getValue(it)[5]["initialValue"] })
        assertEquals(CarPlayVehicleState.CarData.entries.filter { it == CarPlayVehicleState.CarData.CHARGING ||
            it == CarPlayVehicleState.CarData.TYRES }.toSet(), state.describedCarData)

        val sent = mutableListOf<ByteArray>()
        state.attach(CarPlayVehicleState.Lane.HIGH) { sent += it }
        state.receive(CarPlayVehicleState.Lane.HIGH, request("registerRequest", listOf(1284L, 1292L, 1300L)))
        sent.clear()
        state.carReads(CarPlayVehicleState.CarReading(charging = false, tyrePressureKpa = listOf(230, 231),
            tyreTemperatureC = listOf(24, null)))
        assertEquals(mapOf(1284L to "No", 1292L to "230 kPa · 24 °C", 1300L to "231 kPa"), inner(sent.single())["values"])
    }

    @Test fun carDataIsTheCarsAndCannotBeWrittenByTheIphone() {
        val state = CarPlayVehicleState(profile = climateAndSeats, carData = setOf(CarPlayVehicleState.CarData.FUEL_LEVEL))
        val out = state.handle(CarPlayVehicleState.Lane.NORMAL, request("writeRequest", mapOf(1538L to 100L)))
        assertEquals(mapOf(1538L to -6727L), inner(out.single().second)["errors"])
    }

    @Test fun climateAndSeatsAreDescribedOnlyWhereTheCarReportsThem() {
        fun accessories(profile: CarPlayVehicleProfile) = (((CarPlayVehicleState(profile = profile).infoEntry().second
            ["pluginConfigs"] as List<*>).single() as Map<*, *>)["accessories"] as List<*>).map { (it as Map<*, *>)["iid"] }
        assertEquals(listOf(768L), accessories(CarPlayVehicleProfile.NONE))
        assertEquals(listOf(512L, 768L), accessories(CarPlayVehicleProfile(climate = true, seatHeating = false)))
    }

    @Test fun aRefusedWriteGivesTheIphoneTheCarsValueBack() {
        val sent = mutableListOf<ByteArray>()
        val state = CarPlayVehicleState(onPhoneWrite = { _, _ -> false })
        state.attach(CarPlayVehicleState.Lane.NORMAL) { sent += it }
        state.attach(CarPlayVehicleState.Lane.HIGH) { sent += it }
        state.receive(CarPlayVehicleState.Lane.NORMAL, request("registerRequest", listOf(520L)))
        state.carSet("driverTargetTemperature", 19.5)
        sent.clear()

        state.receive(CarPlayVehicleState.Lane.NORMAL, request("writeRequest", mapOf(520L to 24.0)))
        assertEquals(mapOf(520L to -6727L), inner(sent[0])["errors"])
        assertEquals(mapOf(520L to 19.5), inner(sent[1])["values"])
        state.receive(CarPlayVehicleState.Lane.NORMAL, request("readRequest", listOf(520L)))
        assertEquals(mapOf(520L to 19.5), inner(sent[2])["values"])
    }

    @Test fun theRadioIsDescribedWithItsCommandsOnlyWhereThereIsATuner() {
        fun accessories(profile: CarPlayVehicleProfile) = ((CarPlayVehicleState(profile = profile).infoEntry().second
            ["pluginConfigs"] as List<*>).single() as Map<*, *>)["accessories"] as List<*>
        assertTrue(accessories(CarPlayVehicleProfile.NONE).none { (it as Map<*, *>)["iid"] in setOf(4096L, 4352L) })
        val radio = accessories(CarPlayVehicleProfile(climate = false, seatHeating = false, radio = true))
        assertEquals(listOf(4096L, 4352L, 768L), radio.map { (it as Map<*, *>)["iid"] })
        val nowPlaying = ((radio[1] as Map<*, *>)["services"] as List<*>).single() as Map<*, *>
        val controls = (nowPlaying["controls"] as List<*>).map { it as Map<*, *> }
        assertEquals((4360L..4366L).toList(), controls.map { it["iid"] })
        assertEquals(listOf("frequency", "sourceIdentifier"),
            (controls[5]["requestParameters"] as List<*>).map { (it as Map<*, *>)["name"] })
        val fm = ((radio[0] as Map<*, *>)["services"] as List<*>).map { it as Map<*, *> }[1]
        val items = (fm["characteristics"] as List<*>).map { it as Map<*, *> }.single { it["iid"] == 4118L }
        assertEquals(true, items["largePayload"])
        assertEquals(4119L, items["iidNotifier"])
        // The artwork's empty initial value is data, which the /info plist carries too.
        BplistCodec.decode(BplistCodec.encode(mapOf("info" to CarPlayVehicleState(profile = CarPlayVehicleProfile.ALL).infoEntry().second)))
    }

    @Test fun theIphonesRadioCommandsReachTheTuner() {
        val commands = mutableListOf<CarRadioCommand>()
        val sent = mutableListOf<ByteArray>()
        val state = CarPlayVehicleState(profile = CarPlayVehicleProfile.ALL, onRadio = { commands += it })
        state.attach(CarPlayVehicleState.Lane.NORMAL) { sent += it }
        state.receive(CarPlayVehicleState.Lane.NORMAL, request("controlRequest", mapOf(
            4365L to mapOf("frequency" to 101_500L, "sourceIdentifier" to "FM"),
            4364L to mapOf("identifier" to "fm-99100"),
            4362L to emptyMap<String, Any?>(),
            4999L to emptyMap<String, Any?>(),
        )))
        assertEquals(listOf(CarRadioCommand.TuneFrequency(101_500), CarRadioCommand.Tune(CarRadioBand.FM, "fm-99100"), CarRadioCommand.Next),
            commands)
        val reply = inner(sent.single())
        assertEquals("controlResponse", reply["command"])
        assertEquals(setOf(4365L, 4364L, 4362L), (reply["values"] as Map<*, *>).keys)
        assertEquals(mapOf(4999L to -6735L), reply["errors"])
    }

    @Test fun theTunersStateReachesTheIphoneAndBumpsTheListsNotifier() {
        val sent = mutableListOf<ByteArray>()
        val state = CarPlayVehicleState(profile = CarPlayVehicleProfile.ALL)
        state.attach(CarPlayVehicleState.Lane.HIGH) { sent += it }
        state.attach(CarPlayVehicleState.Lane.NORMAL) { sent += it }
        state.receive(CarPlayVehicleState.Lane.NORMAL, request("registerRequest", listOf(4117L, 4118L, 4119L, 4359L, 4115L)))
        sent.clear()
        val station = CarRadioStation(CarPlayRadio.fmId(101_500), "Tarmac FM", frequencyKhz = 101_500)
        state.carSetRadio(CarRadioReading(frequencyKhz = 101_500, fmStations = listOf(station), fmCurrent = station.id, playing = true))
        val update = inner(sent.single())["values"] as Map<*, *>
        assertEquals(101_500L, update[4117L])
        assertEquals(1L, update[4119L])
        assertEquals(1L, update[4359L])
        assertEquals(false, update[4115L])
        assertEquals("Tarmac FM", ((update[4118L] as List<*>).single() as Map<*, *>)["mediaItemName"])

        sent.clear()
        state.carSetRadio(CarRadioReading(frequencyKhz = 101_500, fmStations = listOf(station), fmCurrent = station.id, playing = true))
        assertTrue("nothing changed, nothing sent", sent.isEmpty())
        assertEquals("101.5", CarPlayRadio.fmName(101_500))
    }

    @Test fun theCarTakesTheMainAudioWhileItsRadioPlays() {
        val take = (CarPlayRadio.mainAudio(take = true)["params"] as Map<*, *>)["resources"] as List<*>
        assertEquals(mapOf("resourceID" to 2L, "transferType" to 1L, "transferPriority" to 500L, "takeConstraint" to 100L,
            "borrowConstraint" to 100L, "unborrowConstraint" to 100L), take.single())
        val untake = (CarPlayRadio.mainAudio(take = false)["params"] as Map<*, *>)["resources"] as List<*>
        assertEquals(mapOf("resourceID" to 2L, "transferType" to 2L), untake.single())
        assertEquals("changeModes", CarPlayRadio.mainAudio(take = true)["type"])
    }

    @Test fun panelsAddACarDrawnCluster() {
        val info = AirPlayInfoPlist.build(AirPlayConfig(
            deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:02", sourceVersion = "950.7.1",
            main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
            cluster = CarPlayClusterDisplay.config(1280, 720, scalePercent = 100),
        ))
        val panels = AirPlayInfoPlist.displayPanels(info)
        assertEquals(3, panels.size)
        assertEquals(emptyList<String>(), panels[0]["properties"])
        assertEquals(listOf("showsInstruments"), panels[1]["properties"])
        assertEquals(emptyList<Any?>(), panels[2]["initialVideoStreams"])
        assertTrue(panels[2].containsKey("displayPlugins"))
    }
}
