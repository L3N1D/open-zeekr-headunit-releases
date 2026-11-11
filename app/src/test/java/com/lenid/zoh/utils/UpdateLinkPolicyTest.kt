package com.lenid.zoh.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateLinkPolicyTest {

    private val base = "https://github.com/L3N1D/open-zeekr-headunit-releases/releases"
    private val page = "$base/tag/v.3.5.0-beta3"
    private val apk = "$base/download/v.3.5.0-beta3/com.lenid.zoh_3.5.0-beta3.apk"
    private val debugApk = "$base/download/v.3.5.0-beta3/com.lenid.zoh_3.5.0-beta3_debug.apk"

    @Test
    fun picksTheReleaseApkAndSkipsTheDebugBuild() {
        val assets = listOf(
            "com.lenid.zoh_3.5.0-beta3_debug.apk" to debugApk,
            "com.lenid.zoh_3.5.0-beta3.apk" to apk,
        )
        assertEquals(apk, UpdateLinkPolicy.pickApk(assets))
    }

    @Test
    fun supportsTheExistingRepositoryReleaseName() {
        assertEquals(apk, UpdateLinkPolicy.pickApk(listOf("open-zeekr-headunit.apk" to apk)))
        assertNull(UpdateLinkPolicy.pickApk(listOf("open-zeekr-headunit.apk" to "http://example.com/update.apk")))
    }

    @Test
    fun noReleaseApkGivesNull() {
        assertNull(UpdateLinkPolicy.pickApk(emptyList()))
        assertNull(UpdateLinkPolicy.pickApk(listOf("notes.txt" to "$base/notes.txt")))
        assertNull(UpdateLinkPolicy.pickApk(listOf("x_debug.apk" to debugApk)))
    }

    @Test
    fun skipsArtifactsForOtherApplicationIds() {
        val oldAsset = "com.example.otherapp_3.5.0.apk" to "$base/old.apk"
        assertNull(UpdateLinkPolicy.pickApk(listOf(oldAsset)))
        assertEquals(apk, UpdateLinkPolicy.pickApk(listOf(oldAsset, "com.lenid.zoh_3.5.0.apk" to apk)))
    }

    @Test
    fun apkIsPrimaryAndReleasePageIsSecondary() {
        assertEquals(
            UpdateLinkPolicy.Choice.APK to UpdateLinkPolicy.Choice.RELEASE_PAGE,
            UpdateLinkPolicy.choices(apk)
        )
    }

    @Test
    fun noApkLeavesOnlyTheReleasePage() {
        assertEquals(UpdateLinkPolicy.Choice.RELEASE_PAGE to null, UpdateLinkPolicy.choices(null))
    }
}
