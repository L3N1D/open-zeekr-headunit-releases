package com.lenid.zoh.main

import com.lenid.zoh.aap.NativeTransport
import com.lenid.zoh.connection.wifi.modes.nativeaa.SoftApBssidPolicy
import com.lenid.zoh.utils.ConnectionIssue
import com.lenid.zoh.utils.StandingIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who sees the failure banner, and which one.
 *
 * Each rule here is a way of being wrong in front of a user: a warning beside a working session,
 * a warning that outlives its own remedy, or one that can never be got rid of. The suppression
 * cases are as load-bearing as the showing ones.
 */
class ConnectionIssueBannerPolicyTest {

    private fun bannerFor(
        standing: List<StandingIssue> = emptyList(),
        dismissedAtEpochMs: Long = 0L,
        sessionConnected: Boolean = false,
        onboardingComplete: Boolean = true,
        relevant: Set<ConnectionIssue> = ConnectionIssue.values().toSet(),
        remedyApplied: Set<ConnectionIssue> = emptySet()
    ) = ConnectionIssueBannerPolicy.bannerFor(
        standing, dismissedAtEpochMs, sessionConnected, onboardingComplete, relevant, remedyApplied
    )

    private fun standing(issue: ConnectionIssue, at: Long) = StandingIssue(issue, at)

    @Test
    fun `nothing standing shows nothing`() {
        assertNull(bannerFor())
    }

    @Test
    fun `a standing issue shows`() {
        assertEquals(
            ConnectionIssue.BSSID_UNAVAILABLE,
            bannerFor(standing = listOf(standing(ConnectionIssue.BSSID_UNAVAILABLE, 1_000L)))
        )
    }


    @Test
    fun `a dismissal after the raise hides it`() {
        assertNull(
            bannerFor(
                standing = listOf(standing(ConnectionIssue.BSSID_UNAVAILABLE, 1_000L)),
                dismissedAtEpochMs = 2_000L
            )
        )
    }

    @Test
    fun `a raise after the dismissal shows it again`() {
        assertEquals(
            ConnectionIssue.BSSID_UNAVAILABLE,
            bannerFor(
                standing = listOf(standing(ConnectionIssue.BSSID_UNAVAILABLE, 3_000L)),
                dismissedAtEpochMs = 2_000L
            )
        )
    }

    @Test
    fun `dismissing at the same instant still hides it`() {
        assertNull(
            bannerFor(
                standing = listOf(standing(ConnectionIssue.BSSID_UNAVAILABLE, 2_000L)),
                dismissedAtEpochMs = 2_000L
            )
        )
    }

    @Test
    fun `a live session suppresses it`() {
        assertNull(
            bannerFor(
                standing = listOf(standing(ConnectionIssue.BLUETOOTH_SENT_NO_DATA, 1_000L)),
                sessionConnected = true
            )
        )
    }

    @Test
    fun `unfinished onboarding suppresses it`() {
        assertNull(
            bannerFor(
                standing = listOf(standing(ConnectionIssue.BLUETOOTH_SENT_NO_DATA, 1_000L)),
                onboardingComplete = false
            )
        )
    }

    @Test
    fun `a zero stamp is not standing even if it is in the list`() {
        assertNull(bannerFor(standing = listOf(standing(ConnectionIssue.BSSID_UNAVAILABLE, 0L))))
    }

    @Test
    fun `a zero stamp never outranks a real one`() {
        assertEquals(
            ConnectionIssue.BLUETOOTH_SENT_NO_DATA,
            bannerFor(
                standing = listOf(
                    standing(ConnectionIssue.BSSID_UNAVAILABLE, 0L),
                    standing(ConnectionIssue.BLUETOOTH_SENT_NO_DATA, 1_000L)
                )
            )
        )
    }

    @Test
    fun `every issue can be shown`() {
        // A guard rather than a formality: a new enum value that no branch shows would be a
        // condition recorded on the connection path and then never told to anyone.
        for (issue in ConnectionIssue.values()) {
            assertNotNull(issue.name, bannerFor(standing = listOf(standing(issue, 1_000L))))
        }
    }

    // Which conditions the selected route can be blocked by.

    /**
     * A cable-only unit never brings the stack up, so no wireless verdict can explain anything.
     * The head unit server is the exception: Self Mode dials it with wireless unchosen.
     */
    @Test
    fun `only the head unit server condition survives wireless not being chosen`() {
        for (transport in NativeTransport.values()) {
            assertEquals(
                transport.name,
                setOf(ConnectionIssue.HEADUNIT_SERVER_NOT_ANSWERING, ConnectionIssue.STALE_USB_ACCESSORY),
                ConnectionIssueBannerPolicy.relevantNow(3, transport, wirelessSelected = false)
            )
        }
    }

    @Test
    fun `outside Native AA only the head unit server condition is relevant`() {
        // Every other condition is raised on a Native AA branch. This one is keyed on the endpoint
        // dialled rather than the mode, and every mode can dial Android Auto's own server.
        for (mode in listOf(0, 1, 2)) {
            for (transport in NativeTransport.values()) {
                assertEquals(
                    "mode=$mode transport=$transport",
                    setOf(ConnectionIssue.HEADUNIT_SERVER_NOT_ANSWERING, ConnectionIssue.STALE_USB_ACCESSORY),
                    ConnectionIssueBannerPolicy.relevantNow(
                        mode = mode, transport = transport, wirelessSelected = true
                    )
                )
            }
        }
    }

    @Test
    fun `the head unit server condition stays relevant inside Native AA too`() {
        for (transport in NativeTransport.values()) {
            assertTrue(
                transport.name,
                ConnectionIssue.HEADUNIT_SERVER_NOT_ANSWERING in
                    ConnectionIssueBannerPolicy.relevantNow(3, transport, wirelessSelected = true)
            )
        }
    }






    @Test
    fun `a link too slow for the video blocks both transports`() {
        // The only condition that is not about one route's way of getting a network. Both build one
        // and hand it to the phone, and either can be too slow to carry the picture over it.
        for (transport in NativeTransport.values()) {
            assertTrue(
                transport.name,
                ConnectionIssue.VIDEO_LINK_TOO_SLOW in
                    ConnectionIssueBannerPolicy.relevantNow(3, transport, true)
            )
        }
    }

    @Test
    fun `no setting is a remedy for a link too slow for the video`() {
        // Lowering the frame rate is a guess at the ceiling. Only a session that renders a frame
        // proves it was enough, and that is what retires the record.
        assertFalse(
            ConnectionIssue.VIDEO_LINK_TOO_SLOW in
                ConnectionIssueBannerPolicy.remedyApplied("ohu", "password", "EC:AB:3E:61:C5:7C")
        )
    }

    @Test
    fun `no setting is a remedy for a cycled stack`() {
        // The remedy is another app on the unit, which nothing in these settings reaches.
        assertFalse(
            ConnectionIssue.WIFI_DIRECT_STACK_CYCLED in
                ConnectionIssueBannerPolicy.remedyApplied("OHU-TEST", "testtest1234", "AA:BB:CC:DD:EE:FF")
        )
    }

    @Test
    fun `no setting is a remedy for a refused group`() {
        // A group forming disproves it outright, so the record retires itself. Nothing the user can
        // type reaches the radio's refusal, so hiding the banner on a typed value would hide a
        // condition that is still exactly as true as it was.
        assertFalse(
            ConnectionIssue.WIFI_DIRECT_GROUP_REFUSED in
                ConnectionIssueBannerPolicy.remedyApplied("OHU-TEST", "testtest1234", "AA:BB:CC:DD:EE:FF")
        )
    }



    @Test
    fun `a refused hands-free record is claimed by both Native transports and by no remedy`() {
        // It is raised where the listeners open, which both transports do, and nothing the user
        // can type changes this unit's own Bluetooth stack's answer.
        for (transport in NativeTransport.values()) {
            assertTrue(
                transport.name,
                ConnectionIssue.HANDS_FREE_RECORD_REFUSED in
                    ConnectionIssueBannerPolicy.relevantNow(3, transport, true)
            )
        }
        assertFalse(
            ConnectionIssue.HANDS_FREE_RECORD_REFUSED in
                ConnectionIssueBannerPolicy.remedyApplied("a name", "a password", "02:00:00:00:00:01", "02:00:00:00:00:02")
        )
    }


    @Test
    fun `a stale USB accessory is relevant on a cable-only unit`() {
        for (transport in NativeTransport.values()) {
            assertTrue(
                ConnectionIssue.STALE_USB_ACCESSORY in
                    ConnectionIssueBannerPolicy.relevantNow(3, transport, wirelessSelected = false)
            )
        }
    }

    @Test
    fun `a stale USB accessory is relevant in every wireless mode and transport`() {
        for (mode in 0..3) {
            for (transport in NativeTransport.values()) {
                assertTrue(
                    "mode=$mode transport=$transport",
                    ConnectionIssue.STALE_USB_ACCESSORY in
                        ConnectionIssueBannerPolicy.relevantNow(mode, transport, wirelessSelected = true)
                )
            }
        }
    }

    @Test
    fun `no setting is a remedy for a stale USB accessory`() {
        assertFalse(
            ConnectionIssue.STALE_USB_ACCESSORY in
                ConnectionIssueBannerPolicy.remedyApplied("a name", "a password", "02:00:00:00:00:01", "02:00:00:00:00:02")
        )
    }

    @Test
    fun `every issue is relevant on some route`() {
        // The same guard as `every issue can be shown`: a condition no route claims would be
        // recorded on the connection path and then never shown to anybody.
        val everRelevant = NativeTransport.values()
            .flatMap { ConnectionIssueBannerPolicy.relevantNow(3, it, true) }
            .toSet()
        for (issue in ConnectionIssue.values()) assertTrue(issue.name, issue in everRelevant)
    }

    // Which conditions the user has already fixed.

    @Test
    fun `nothing set is no remedy`() {
        assertTrue(ConnectionIssueBannerPolicy.remedyApplied("", "", null).isEmpty())
    }



    @Test
    fun `a usable static BSSID is the remedy, a placeholder is not`() {
        assertTrue(
            ConnectionIssue.BSSID_UNAVAILABLE in
                ConnectionIssueBannerPolicy.remedyApplied("", "", "AA:BB:CC:DD:EE:FF")
        )
        // "0" is what the setting stores when unset, and the masked address is what Android hands
        // back to an unprivileged app - neither is something the user fixed.
        for (notAnAddress in listOf("0", "02:00:00:00:00:00", "")) {
            assertFalse(
                notAnAddress,
                ConnectionIssue.BSSID_UNAVAILABLE in
                    ConnectionIssueBannerPolicy.remedyApplied("", "", notAnAddress)
            )
        }
    }

    // What the two filters do to the banner.




    @Test
    fun `an older un-remedied condition beats a newer remedied one`() {
        assertEquals(
            ConnectionIssue.BLUETOOTH_SENT_NO_DATA,
            bannerFor(
                standing = listOf(
                    standing(ConnectionIssue.BLUETOOTH_SENT_NO_DATA, 1_000L),
                    standing(ConnectionIssue.BSSID_UNAVAILABLE, 9_000L)
                ),
                remedyApplied = setOf(ConnectionIssue.BSSID_UNAVAILABLE)
            )
        )
    }



    // What the publish sites leave standing, and what this screen then does with it.
    //
    // The pair of rules the record change rests on. A record is retired only by a disproof, so the
    // publish sites decline to retire records that are merely worked around - and the banner must
    // not then ask a user for work they have already done. The two halves were written apart and
    // could drift apart; UserExitHotspotPolicyTest has an assertion of the same shape.


    @Test
    fun `a BSSID record the handshake declines to retire is already hidden`() {
        var retired = 0
        var kept = 0
        for (override in listOf(
            null, "", "0", "not-a-mac", "02:00:00:00:00:00", "aa:bb:cc:dd:ee:ff", "AA-BB-CC-DD-EE-FF"
        )) {
            for (shell in listOf(null, "11:22:33:44:55:66", "00:00:00:00:00:00")) {
                for (hardware in listOf(null, "77:88:99:aa:bb:cc")) {
                    val resolved = SoftApBssidPolicy.choose(override, shell, hardware)
                    // No usable address is the abort branch, which raises rather than retires.
                    if (!SoftApBssidPolicy.isUsable(resolved)) continue
                    if (SoftApBssidPolicy.disprovesBssidUnavailable(resolved, override)) {
                        retired++
                        continue
                    }
                    assertTrue(
                        "override='$override' shell='$shell' hw='$hardware' -> '$resolved'",
                        ConnectionIssue.BSSID_UNAVAILABLE in
                            ConnectionIssueBannerPolicy.remedyApplied("", "", override)
                    )
                    kept++
                }
            }
        }
        assertTrue("no state retired the record", retired > 0)
        assertTrue("no state kept a record", kept > 0)
    }

    @Test
    fun `a wedged stack outranks the newer refusal it causes`() {
        val shown = bannerFor(standing = listOf(
            standing(ConnectionIssue.WIFI_DIRECT_STACK_STUCK, 1_000L),
            standing(ConnectionIssue.WIFI_DIRECT_GROUP_REFUSED, 5_000L)
        ))
        assertEquals(ConnectionIssue.WIFI_DIRECT_STACK_STUCK, shown)
    }

    @Test
    fun `a wedged stack does not outrank newer records other than the refusal`() {
        val shown = bannerFor(standing = listOf(
            standing(ConnectionIssue.WIFI_DIRECT_STACK_STUCK, 1_000L),
            standing(ConnectionIssue.WIFI_RADIO_OFF, 5_000L)
        ))
        assertEquals(ConnectionIssue.WIFI_RADIO_OFF, shown)
        val cycled = bannerFor(standing = listOf(
            standing(ConnectionIssue.WIFI_DIRECT_STACK_STUCK, 1_000L),
            standing(ConnectionIssue.WIFI_DIRECT_STACK_CYCLED, 5_000L)
        ))
        assertEquals(ConnectionIssue.WIFI_DIRECT_STACK_CYCLED, cycled)
    }

    @Test
    fun `a dismissed wedge hides neither a newer record nor the refusal`() {
        val shown = bannerFor(
            standing = listOf(
                standing(ConnectionIssue.WIFI_DIRECT_STACK_STUCK, 1_000L),
                standing(ConnectionIssue.WIFI_DIRECT_GROUP_REFUSED, 5_000L)
            ),
            dismissedAtEpochMs = 2_000L
        )
        assertEquals(ConnectionIssue.WIFI_DIRECT_GROUP_REFUSED, shown)
    }
}
