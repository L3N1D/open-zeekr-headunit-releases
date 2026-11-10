package com.lenid.zoh.utils

import com.lenid.zoh.BuildConfig

/**
 * Chooses the links "Check for updates" offers. Some head unit browsers cannot run the script that
 * lists a release page's assets, so the APK link itself is the first choice on every version.
 */
object UpdateLinkPolicy {

    enum class Choice { APK, RELEASE_PAGE }

    /** Only this application's release artifacts, never APKs for the previous package. */
    fun pickApk(assets: List<Pair<String, String>>): String? {
        for ((name, url) in assets) {
            if (name.startsWith("${BuildConfig.APPLICATION_ID}_") &&
                name.endsWith(".apk") && !name.endsWith("_debug.apk") && url.isNotEmpty()) return url
        }
        return null
    }

    /** The dialog's (positive, neutral) buttons; with no release APK the page is the only one. */
    fun choices(apkUrl: String?): Pair<Choice, Choice?> =
        if (apkUrl != null) Choice.APK to Choice.RELEASE_PAGE else Choice.RELEASE_PAGE to null
}
