package com.lenid.zoh.utils

import java.util.Locale

/** Normalize old preferences and imported settings to the two supported languages. */
internal object AppLanguagePolicy {
    const val DEFAULT = "ru"

    fun normalize(value: String?): String = when (
        value?.trim()?.replace('_', '-')?.substringBefore('-')?.lowercase(Locale.ROOT)
    ) {
        "en" -> "en"
        "ru" -> "ru"
        else -> DEFAULT
    }
}
