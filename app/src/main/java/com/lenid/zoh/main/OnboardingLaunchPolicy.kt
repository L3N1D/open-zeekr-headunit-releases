package com.lenid.zoh.main

/** Automatic setup belongs to a fresh install, never to an APK update. */
internal object OnboardingLaunchPolicy {
    fun shouldShow(completedVersion: Int, updatedInstall: Boolean, manual: Boolean): Boolean =
        manual || (completedVersion == 0 && !updatedInstall)
}
