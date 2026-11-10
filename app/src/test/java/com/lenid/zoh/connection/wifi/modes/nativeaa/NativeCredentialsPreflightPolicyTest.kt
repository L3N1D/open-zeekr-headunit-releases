package com.lenid.zoh.connection.wifi.modes.nativeaa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCredentialsPreflightPolicyTest {

    private val realMac = "AA:BB:CC:DD:EE:FF"

    private fun probe(
        manualSsid: String = "",
        manualPassword: String = "",
        staticBssid: String? = "0",
        probedBssid: String? = realMac,
        bssidProbeConclusive: Boolean = true,
        locationServicesEnabled: Boolean? = true
    ) = PreflightProbe(
        manualSsid, manualPassword, staticBssid,
        probedBssid, bssidProbeConclusive, locationServicesEnabled
    )

    private fun wifiDirect(probe: PreflightProbe) =
        NativeCredentialsPreflightPolicy.evaluate(NativeTransport.WIFI_DIRECT, probe)

    // --- the case the whole feature exists for -------------------------------------------------




    // --- the half-finished override -------------------------------------------------------------





    // --- BSSID ----------------------------------------------------------------------------------



    @Test
    fun `a masked address is not an address`() {
        val report = wifiDirect(probe(probedBssid = "02:00:00:00:00:00"))
        assertEquals(FieldVerdict.MUST_BE_ENTERED, report.verdicts[CredentialField.BSSID])
    }

    @Test
    fun `nothing to read from is unknown, and unknown is never asked about`() {
        // The hotspot is off, or no P2P group exists. Every unit looks like a unit that cannot
        // report its own MAC at this moment, and prompting here would fire on healthy hardware.
        val report = wifiDirect(probe(probedBssid = null, bssidProbeConclusive = false))
        assertEquals(FieldVerdict.UNKNOWN, report.verdicts[CredentialField.BSSID])
        assertEquals(emptyList<CredentialField>(), report.mustEnter)
        assertFalse(report.hasFindings)
    }

    // --- location services ----------------------------------------------------------------------

    @Test
    fun `location off is offered instead of asking for a MAC`() {
        // The usual cause of a masked BSSID, and a switch beats a typed address: a static override
        // outranks every automatic source afterwards, so a MAC typed to work around this outlives
        // the problem it was for.
        val report = wifiDirect(probe(probedBssid = null, locationServicesEnabled = false))
        assertTrue(report.locationServicesOff)
        assertEquals(emptyList<CredentialField>(), report.mustEnter)
        assertTrue(report.hasFindings)
    }

    @Test
    fun `location off is not raised when the BSSID is already in hand`() {
        assertFalse(wifiDirect(probe(locationServicesEnabled = false)).locationServicesOff)
        assertFalse(wifiDirect(probe(
            staticBssid = realMac, probedBssid = null, locationServicesEnabled = false
        )).locationServicesOff)
    }


    @Test
    fun `location that could not be asked about is not location that is off`() {
        assertFalse(wifiDirect(probe(
            probedBssid = null, locationServicesEnabled = null
        )).locationServicesOff)
    }

    // --- transport scope --------------------------------------------------------------------------


}
