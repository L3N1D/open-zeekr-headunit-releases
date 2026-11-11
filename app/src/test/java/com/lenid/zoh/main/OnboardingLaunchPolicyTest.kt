package com.lenid.zoh.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingLaunchPolicyTest {
    @Test fun `restoring completed setup does not reopen wizard`() {
        assertFalse(OnboardingLaunchPolicy.shouldShow(2, 2, false))
    }
    @Test fun `fresh installation still gets setup`() {
        assertTrue(OnboardingLaunchPolicy.shouldShow(0, 2, false))
    }
    @Test fun `older setup version gets new steps`() {
        assertTrue(OnboardingLaunchPolicy.shouldShow(1, 2, false))
    }
    @Test fun `manual setup stays available after completion`() {
        assertTrue(OnboardingLaunchPolicy.shouldShow(2, 2, true))
    }
    @Test fun `downgrade does not repeat completed setup`() {
        assertFalse(OnboardingLaunchPolicy.shouldShow(3, 2, false))
    }
}
