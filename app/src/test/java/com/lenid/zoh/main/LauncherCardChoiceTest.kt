package com.lenid.zoh.main

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class LauncherCardChoiceTest {
    @Test fun yandexInTheCardIsRememberedWhenConfiguredPackageIsEmpty() {
        assertEquals("ru.yandex.yandexnavi", LauncherCard.previousPackage("null", "ru.yandex.yandexnavi"))
        assertEquals("other.navigator", LauncherCard.previousPackage("other.navigator", "ru.yandex.yandexnavi"))
    }

    @Test fun onlyKnownNavigatorWindowsAreSelected() {
        assertEquals(true, LauncherCard.matchesMapDisplay("TaskVirtualDisplay@123", 1275, 876))
        assertEquals(true, LauncherCard.matchesMapDisplay("TaskVirtualDisplay@123", 1796, 876))
        assertEquals(false, LauncherCard.matchesMapDisplay("TaskVirtualDisplay@123", 1920, 1080))
        assertEquals(false, LauncherCard.matchesMapDisplay("HUD", 1275, 876))
    }

    @Test fun storedChoiceSurvivesClearedLauncherSettingAndBootRestoresOnlyWhenEnabled() {
        val context = mock<Context>()
        val resolver = mock<android.content.ContentResolver>()
        val prefs = mock<SharedPreferences>()
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(context.packageName).thenReturn("com.lenid.zoh")
        whenever(context.getSharedPreferences("launcher_card", Context.MODE_PRIVATE)).thenReturn(prefs)
        whenever(prefs.contains("card_wanted")).thenReturn(true)

        for (enabled in listOf(true, false)) {
            whenever(prefs.getBoolean("card_wanted", false)).thenReturn(enabled)
            mockStatic(Settings.Global::class.java).use { globals ->
                assertEquals(enabled, LauncherCard.isEnabled(context))
                LauncherCard.reassert(context)
                globals.verify({ Settings.Global.putString(resolver, "navi_package", "com.lenid.zoh") },
                    org.mockito.Mockito.times(if (enabled) 1 else 0))
            }
        }
    }
}
