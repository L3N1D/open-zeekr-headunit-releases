package com.lenid.zoh.aap

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.AndroidRuntimeException
import android.widget.Toast
import com.lenid.zoh.R
import com.lenid.zoh.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.*

/** Invoke the real activity method; only the Android framework boundary is mocked. */
class ProjectionBackgroundLaunchTest {
    private fun exercise(isHome: Boolean, failure: RuntimeException? = null) {
        val activity = mock(AapProjectionActivity::class.java)
        val pm = mock(PackageManager::class.java)
        val resolved = mock(ResolveInfo::class.java)
        resolved.activityInfo = mock(ActivityInfo::class.java).apply {
            packageName = if (isHome) "com.lenid.zoh" else "factory.launcher"
        }
        `when`(activity.packageManager).thenReturn(pm)
        `when`(activity.packageName).thenReturn("com.lenid.zoh")
        `when`(pm.resolveActivity(any(Intent::class.java), eq(PackageManager.MATCH_DEFAULT_ONLY))).thenReturn(resolved)
        if (failure != null) doThrow(failure).`when`(activity).startActivity(any(Intent::class.java))
        val toast = mock(Toast::class.java)
        mockStatic(Toast::class.java).use { toasts ->
            toasts.`when`<Toast> {
                Toast.makeText(activity, R.string.exit_dialog_background_failed, Toast.LENGTH_LONG)
            }.thenReturn(toast)
            mockConstruction(Intent::class.java, withSettings().defaultAnswer(RETURNS_SELF)) { _, context ->
                // One implicit intent is also constructed by the pre-Android-10 home-role check.
                if (context.arguments().size == 2) {
                    assertEquals(activity, context.arguments()[0])
                    assertEquals(MainActivity::class.java, context.arguments()[1])
                } else {
                    assertEquals(listOf(Intent.ACTION_MAIN), context.arguments())
                }
            }.use { intents ->
                val method = AapProjectionActivity::class.java.getDeclaredMethod("moveToBackground")
                method.isAccessible = true
                method.invoke(activity)
                val launchIntent = intents.constructed().last()
                verify(launchIntent).addCategory(Intent.CATEGORY_HOME)
                verify(launchIntent).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (isHome) verify(launchIntent).setAction(Intent.ACTION_MAIN)
                verify(activity).startActivity(launchIntent)
                // Failure must not finish the activity, move its task, or touch the connection.
                verify(activity, never()).finish()
                verify(activity, never()).moveTaskToBack(anyBoolean())
                verifyNoMoreInteractions(*ignoreStubs(activity))
                if (failure != null) verify(toast).show() else verifyNoInteractions(toast)
            }
        }
    }

    @Test fun `system refusal of implicit HOME does not escape the exit menu`() {
        exercise(false, mock(AndroidRuntimeException::class.java))
    }

    @Test fun `successful implicit HOME retains its action category and flags`() {
        exercise(false)
    }

    @Test fun `successful default home launch still targets our main activity`() {
        exercise(true)
    }

    @Test fun `refused explicit home also leaves the projection alive`() {
        exercise(true, SecurityException("Launch refused"))
    }
}
