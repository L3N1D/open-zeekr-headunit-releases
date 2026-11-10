package com.lenid.zoh.aap

import com.lenid.zoh.aap.protocol.proto.NavigationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationRoadPolicyTest {

    private fun road(name: String?): NavigationStatus.NavigationRoad =
        NavigationStatus.NavigationRoad.newBuilder().apply { name?.let { setName(it) } }.build()

    private fun state(vararg roads: NavigationStatus.NavigationRoad?): NavigationStatus.NavigationState =
        NavigationStatus.NavigationState.newBuilder().apply {
            roads.forEach { r ->
                addSteps(NavigationStatus.NavigationStep.newBuilder().apply { r?.let { setRoad(it) } })
            }
        }.build()

    private fun detail(road: String): NavigationStatus.NextTurnDetail =
        NavigationStatus.NextTurnDetail.newBuilder().setRoad(road).buildPartial()

    private fun position(road: NavigationStatus.NavigationRoad?): NavigationStatus.NavigationCurrentPosition =
        NavigationStatus.NavigationCurrentPosition.newBuilder().apply { road?.let { setCurrentRoad(it) } }.build()

    @Test fun stateRoadIsTheManeuverRoad() {
        assertEquals("Road A", NavigationRoadPolicy.maneuverRoad(state(road("Road A")), null))
    }

    @Test fun stateWithoutStepRoadIsUnknown() {
        assertNull(NavigationRoadPolicy.maneuverRoad(state(null), null))
    }

    @Test fun blankStepRoadIsUnknown() {
        assertNull(NavigationRoadPolicy.maneuverRoad(state(road("")), null))
        assertNull(NavigationRoadPolicy.maneuverRoad(state(road("  ")), null))
    }

    @Test fun stateWithoutStepsIsUnknown() {
        assertNull(NavigationRoadPolicy.maneuverRoad(state(), null))
    }

    @Test fun stateWinsOverDetail() {
        assertEquals("Road B", NavigationRoadPolicy.maneuverRoad(state(road("Road B")), detail("Road D")))
    }

    @Test fun legacyDetailAloneIsUsed() {
        assertEquals("Road D", NavigationRoadPolicy.maneuverRoad(null, detail("Road D")))
    }

    @Test fun currentRoadNeverLeaksIntoManeuverRoad() {
        val s = state(road("Road B"))
        val p = position(road("Road X"))
        assertEquals("Road B", NavigationRoadPolicy.maneuverRoad(s, null))
        assertEquals("Road X", NavigationRoadPolicy.currentRoad(p))
    }

    @Test fun blankLegacyDetailIsUnknown() {
        assertNull(NavigationRoadPolicy.maneuverRoad(null, detail("")))
    }

    @Test fun stateWithNoRoadIgnoresLegacyDetail() {
        assertNull(NavigationRoadPolicy.maneuverRoad(state(null), detail("Road D")))
    }

    @Test fun nothingIsUnknown() {
        assertNull(NavigationRoadPolicy.maneuverRoad(null, null))
    }

    @Test fun positionRoadIsTheCurrentRoad() {
        assertEquals("Road X", NavigationRoadPolicy.currentRoad(position(road("Road X"))))
    }

    @Test fun positionWithoutRoadIsUnknown() {
        assertNull(NavigationRoadPolicy.currentRoad(position(null)))
    }

    @Test fun positionRoadWithoutNameOrBlankNameIsUnknown() {
        assertNull(NavigationRoadPolicy.currentRoad(position(road(null))))
        assertNull(NavigationRoadPolicy.currentRoad(position(road(""))))
    }

    @Test fun nullPositionIsUnknown() {
        assertNull(NavigationRoadPolicy.currentRoad(null))
    }
}
