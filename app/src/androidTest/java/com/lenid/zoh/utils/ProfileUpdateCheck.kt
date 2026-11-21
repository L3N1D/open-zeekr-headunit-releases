package com.lenid.zoh.utils

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import android.content.Context
import android.content.ContextWrapper
import com.lenid.zoh.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** With separate instrumentation runs, phase=stage and phase=restart also check process death. */
class ProfileUpdateCheck {
    @Test fun partialDownloadAndDeferredReport() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) =
                baseContext.getSharedPreferences("profile_check_" + name, mode)
        }
        val saved = File(target.cacheDir, "profile-check-user.txt")
        when (InstrumentationRegistry.getArguments().getString("phase")) {
            "stage" -> stage(isolated, saved)
            "restart" -> afterRestart(isolated, saved)
            null -> {
                stage(isolated, saved)
                afterRestart(isolated, saved)
            }
            else -> error("Unknown test phase")
        }
    }

    private fun stage(context: Context, saved: File) {
        check(context.getSharedPreferences("module_profiles_v1", 0).edit().clear().commit())
        ModuleUpdates.initialize(context)
        val entries = JSONArray()
        val packets = mutableMapOf<String, ByteArray>()
        for (module in ModuleProfile.values()) {
            val revision = if (module in listOf(ModuleProfile.CARPLAY, ModuleProfile.AUTOKIT, ModuleProfile.UI)) 2 else 1
            val entry = JSONObject().put("id", module.id).put("revision", revision)
                .put("minCore", 1).put("maxCore", Int.MAX_VALUE)
            val bytes = JSONObject(entry.toString()).put(module.field, module.default + 1).toString().toByteArray()
            packets["packages/${module.id}/$revision.json"] = bytes
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            entries.put(entry.put("sha256", hash))
        }
        // Fail between two successful downloads; both must survive.
        val ordered = JSONArray()
        listOf(0, 1, 4, 3, 2).forEach { ordered.put(entries.getJSONObject(it)) }
        val result = ModuleUpdates.stageProfiles(context, ordered) { path ->
            if (path.contains("autokit/")) throw IOException("Offline fixture")
            packets.getValue(path)
        }
        check(result == R.string.module_partial)
        check(ModuleUpdates.pending(context).toSet() == setOf(ModuleProfile.CARPLAY, ModuleProfile.UI))
        check(ModuleUpdates.activeRevisions().values.all { it == 1 })
        saved.writeText(CrashJournal.captureProblem(context, "local test"))
    }

    private fun afterRestart(context: Context, saved: File) {
        ModuleUpdates.initialize(context)
        check(ModuleUpdates.pending(context).isEmpty())
        val event = CrashJournal::class.java.getDeclaredMethod("event", File::class.java, JSONObject::class.java)
        event.isAccessible = true
        fun versions() = (event.invoke(CrashJournal, saved, null) as JSONObject)
            .getJSONObject("extra").getJSONObject("module_revisions")
        val historical = versions()
        check(historical.length() == 5 && historical.getInt("carplay") == 1 && historical.getInt("ui") == 1)
        saved.writeText(CrashJournal.captureProblem(context, "local test"))
        val current = versions()
        check(current.getInt("carplay") == 2 && current.getInt("ui") == 2 && current.getInt("autokit") == 1)
        check(ModuleProfile.CARPLAY.value == ModuleProfile.CARPLAY.default + 1)
        check(saved.delete())
        check(context.getSharedPreferences("module_profiles_v1", 0).edit().clear().commit())
    }
}
