package com.lenid.zoh.view

import org.junit.Assert.*
import org.junit.Test

class ProjectionResizeMotionPolicyTest {
    @Test fun widgetExpansionAndCollapse() {
        assertTrue(ProjectionResizeMotionPolicy.shouldAnimate(1275, 876, 1796, 876))
        assertTrue(ProjectionResizeMotionPolicy.shouldAnimate(1796, 876, 1275, 876))
    }
    @Test fun noInitialLayoutRotationOrSmallInsetChange() {
        assertFalse(ProjectionResizeMotionPolicy.shouldAnimate(0, 0, 1275, 876))
        assertFalse(ProjectionResizeMotionPolicy.shouldAnimate(1796, 876, 876, 1796))
        assertFalse(ProjectionResizeMotionPolicy.shouldAnimate(1796, 876, 1780, 876))
        assertFalse(ProjectionResizeMotionPolicy.shouldAnimate(1796, 876, 0, 0))
    }
}
