package com.lenid.zoh.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingLaunchPolicyTest {
    @Test fun `fresh install opens wizard`() {
        assertTrue(OnboardingLaunchPolicy.shouldShow(0, false, false))
    }
    @Test fun `completed setup remains closed after restart`() {
        assertFalse(OnboardingLaunchPolicy.shouldShow(2, false, false))
    }
    @Test fun `update does not reopen older completed setup`() {
        assertFalse(OnboardingLaunchPolicy.shouldShow(1, true, false))
    }
    @Test fun `update does not reopen unfinished setup`() {
        assertFalse(OnboardingLaunchPolicy.shouldShow(0, true, false))
    }
    @Test fun `manual setup remains available after update`() {
        assertTrue(OnboardingLaunchPolicy.shouldShow(2, true, true))
    }
    @Test fun `uninstall then fresh install opens setup again`() {
        assertFalse(OnboardingLaunchPolicy.shouldShow(2, true, false))
        assertTrue(OnboardingLaunchPolicy.shouldShow(0, false, false))
    }
}
