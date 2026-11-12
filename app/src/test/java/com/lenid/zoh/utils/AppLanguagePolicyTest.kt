package com.lenid.zoh.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguagePolicyTest {
    @Test fun `new install and former system default use Russian`() {
        for (value in listOf(null, "", " ")) assertEquals("ru", AppLanguagePolicy.normalize(value))
    }

    @Test fun `explicit English survives update and regional imports`() {
        for (value in listOf("en", "en-US", "en_GB", " EN "))
            assertEquals("en", AppLanguagePolicy.normalize(value))
    }

    @Test fun `Russian variants normalize to Russian`() {
        for (value in listOf("ru", "ru-RU", "ru_RU"))
            assertEquals("ru", AppLanguagePolicy.normalize(value))
    }

    @Test fun `removed or invalid languages fall back to Russian`() {
        for (value in listOf("de", "zh-TW", "pt-rBR", "invalid"))
            assertEquals("ru", AppLanguagePolicy.normalize(value))
    }
}
