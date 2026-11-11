package com.lenid.zoh.main

/** Android can restore the wizard task without going through MainActivity's setup check. */
internal object OnboardingLaunchPolicy {
    fun shouldShow(completedVersion: Int, currentVersion: Int, manual: Boolean): Boolean =
        manual || completedVersion < currentVersion
}
