package com.lenid.zoh.connection.wifi.direct

import org.junit.Assert.assertEquals
import org.junit.Test

class StationStandDownSettlePolicyTest {

    @Test
    fun `still associated inside the ceiling waits`() {
        assertEquals(
            StationStandDownSettlePolicy.Step.WAIT,
            StationStandDownSettlePolicy.step(stillAssociated = true, waitedMs = 0)
        )
    }

    @Test
    fun `having left creates immediately`() {
        assertEquals(
            StationStandDownSettlePolicy.Step.CREATE,
            StationStandDownSettlePolicy.step(stillAssociated = false, waitedMs = 0)
        )
    }

    @Test
    fun `the ceiling creates even while still associated`() {
        assertEquals(
            StationStandDownSettlePolicy.Step.CREATE,
            StationStandDownSettlePolicy.step(
                stillAssociated = true,
                waitedMs = StationStandDownSettlePolicy.CEILING_MS
            )
        )
    }

    @Test
    fun `an unreadable station does not hold the group up`() {
        assertEquals(
            StationStandDownSettlePolicy.Step.CREATE,
            StationStandDownSettlePolicy.step(stillAssociated = null, waitedMs = 0)
        )
    }

    @Test
    fun `the ceiling is the verify delay it replaces`() {
        assertEquals(StationStandDown.VERIFY_DELAY_MS, StationStandDownSettlePolicy.CEILING_MS)
    }
}
