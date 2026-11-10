package com.lenid.zoh.aap

import com.lenid.zoh.aap.protocol.proto.NavigationStatus

/**
 * A road the phone leaves out means unknown, never unchanged.
 * Each road comes only from the newest message of its own kind.
 */
object NavigationRoadPolicy {
    fun maneuverRoad(
        state: NavigationStatus.NavigationState?,
        detail: NavigationStatus.NextTurnDetail?
    ): String? {
        if (state != null) {
            return state.stepsList.firstOrNull()
                ?.takeIf { it.hasRoad() && it.road.hasName() }
                ?.road?.name
                ?.takeIf { it.isNotBlank() }
        }
        return detail?.takeIf { it.hasRoad() }?.road?.takeIf { it.isNotBlank() }
    }

    fun currentRoad(position: NavigationStatus.NavigationCurrentPosition?): String? =
        position
            ?.takeIf { it.hasCurrentRoad() && it.currentRoad.hasName() }
            ?.currentRoad?.name
            ?.takeIf { it.isNotBlank() }
}
