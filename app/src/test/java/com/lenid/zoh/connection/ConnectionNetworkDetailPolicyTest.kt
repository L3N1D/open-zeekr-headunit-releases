package com.lenid.zoh.connection

import com.lenid.zoh.R
import com.lenid.zoh.connection.ConnectionNetworkDetail.Note
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionNetworkDetailPolicyTest {

    @Test
    fun `a 5 GHz group names its frequency`() {
        assertEquals(
            ConnectionNetworkDetailPolicy.Line(R.string.network_detail_5ghz, listOf(5805)),
            ConnectionNetworkDetailPolicy.lineFor(ConnectionNetworkDetail(5805))
        )
    }

    @Test
    fun `a 2_4 GHz group names its channel`() {
        assertEquals(
            ConnectionNetworkDetailPolicy.Line(R.string.network_detail_24ghz, listOf(6)),
            ConnectionNetworkDetailPolicy.lineFor(ConnectionNetworkDetail(2437))
        )
    }

    @Test
    fun `an unreported frequency only says the group is up`() {
        assertEquals(
            ConnectionNetworkDetailPolicy.Line(R.string.network_detail_unknown),
            ConnectionNetworkDetailPolicy.lineFor(ConnectionNetworkDetail(0))
        )
    }

    @Test
    fun `a band mismatch says the retry is coming`() {
        assertEquals(
            ConnectionNetworkDetailPolicy.Line(R.string.network_detail_retrying_5ghz),
            ConnectionNetworkDetailPolicy.lineFor(ConnectionNetworkDetail(2437, Note.RETRYING_5GHZ))
        )
    }

    @Test
    fun `a client-unfriendly channel is named as the problem`() {
        assertEquals(
            ConnectionNetworkDetailPolicy.Line(R.string.network_detail_unfriendly_channel, listOf(12)),
            ConnectionNetworkDetailPolicy.lineFor(ConnectionNetworkDetail(2467, Note.CLIENT_UNFRIENDLY_CHANNEL))
        )
    }
}
