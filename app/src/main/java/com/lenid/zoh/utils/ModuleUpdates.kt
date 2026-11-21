package com.lenid.zoh.utils

import android.content.Context
import android.util.Base64
import com.lenid.zoh.BuildConfig
import com.lenid.zoh.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.net.ssl.HttpsURLConnection

enum class ModuleProfile(val id: String, val title: Int, val field: String, val default: Int, val range: IntRange) {
    AA("android-auto", R.string.module_aa, "watchdogPollMs", 1500, 1000..2500),
    CARPLAY("carplay", R.string.module_carplay, "firstTcpTimeoutMs", 30000, 20000..60000),
    ZEEKR("zeekr-integration", R.string.module_zeekr, "artQuality", 90, 70..95),
    UI("ui", R.string.module_ui, "cardEnterMs", 420, 250..650),
    AUTOKIT("autokit", R.string.module_autokit, "discoveryPollMs", 1000, 750..2500);

    val value: Int get() = ModuleUpdates.value(this)
}

object ModuleUpdates {
    private const val BASE = "https://raw.githubusercontent.com/L3N1D/open-zeekr-headunit-releases/main/module-updates/"
    private val lock = Mutex()
    private var active: Map<ModuleProfile, JSONObject> = emptyMap()
    private fun prefs(context: Context) = context.getSharedPreferences("module_profiles_v1", Context.MODE_PRIVATE)
    private fun compatible(obj: JSONObject) =
        BuildConfig.VERSION_CODE in obj.getInt("minCore")..obj.getInt("maxCore")

    fun initialize(context: Context) {
        active = ModuleProfile.values().mapNotNull { module ->
            val data = prefs(context).getString(module.id, null) ?: return@mapNotNull null
            runCatching { JSONObject(data) }.getOrNull()?.takeIf {
                runCatching { compatible(it) && it.getInt(module.field) in module.range }.getOrDefault(false)
            }?.let { module to it }
        }.toMap()
    }

    fun value(module: ModuleProfile) = active[module]?.getInt(module.field) ?: module.default

    fun activeRevisions(): Map<String, Int> = ModuleProfile.values().associate {
        it.id to (active[it]?.getInt("revision") ?: 1)
    }

    fun pending(context: Context): List<ModuleProfile> = ModuleProfile.values().filter { module ->
        val stored = prefs(context).getString(module.id, null)
        stored != null && runCatching {
            val profile = JSONObject(stored)
            compatible(profile) && profile.getInt("revision") > (active[module]?.getInt("revision") ?: 1)
        }.getOrDefault(false)
    }

    val changes = androidx.lifecycle.MutableLiveData<Unit>()

    fun describe(context: Context): String = ModuleProfile.values().joinToString("\n\n") { module ->
        val current = active[module]?.getInt("revision") ?: 1
        val p = prefs(context)
        val staged = p.getString(module.id, null)?.let { JSONObject(it) }
        val next = staged?.optInt("revision") ?: 1
        val status = when {
            p.contains("requires_" + module.id) -> context.getString(R.string.module_requires)
            next > current && staged != null && compatible(staged) -> context.getString(R.string.module_pending, next)
            current == 1 -> context.getString(R.string.module_builtin)
            else -> active[module]?.optString("notes").orEmpty()
        }
        context.getString(module.title) + " · v" + current + "\n" + status
    }

    private fun download(path: String): ByteArray {
        val connection = URL(BASE + path).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 6000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = false
            check(connection.responseCode == 200)
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 65536)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun refresh(context: Context): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                fetch(context)
            } catch (e: Exception) {
                AppLog.w("Module catalog: ${e.javaClass.simpleName}")
                R.string.module_failed
            }
        }
    }

    private fun fetch(context: Context): Int {
        val envelope = JSONObject(String(download("stable.json"), Charsets.UTF_8))
        val payload = Base64.decode(envelope.getString("payload"), Base64.DEFAULT)
        val key = context.resources.openRawResource(R.raw.module_update_key).use { it.readBytes() }
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key)))
        verifier.update(payload)
        require(verifier.verify(Base64.decode(envelope.getString("signature"), Base64.DEFAULT)))
        val catalog = JSONObject(String(payload, Charsets.UTF_8))
        require(catalog.getInt("schema") == 1)
        val entries = catalog.getJSONArray("modules")
        return stageProfiles(context, entries, ::download)
    }

    internal fun stageProfiles(context: Context, entries: org.json.JSONArray, load: (String) -> ByteArray): Int {
        val catalog = mutableMapOf<ModuleProfile, JSONObject>()
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val module = ModuleProfile.values().firstOrNull { it.id == entry.getString("id") } ?: continue
            require(catalog.put(module, entry) == null)
        }
        require(catalog.size == ModuleProfile.values().size)
        var partial = false
        for ((module, entry) in catalog) {
            try {
                stageOne(context, module, entry, load)
            } catch (e: Exception) {
                AppLog.w("Module ${module.id}: ${e.javaClass.simpleName}")
                partial = true
            }
        }
        changes.postValue(Unit)
        return if (partial) R.string.module_partial else R.string.module_checked
    }

    private fun stageOne(context: Context, module: ModuleProfile, entry: JSONObject, load: (String) -> ByteArray) {
        val storage = prefs(context)
        val editor = storage.edit()
        val revision = entry.getInt("revision")
        require(revision > 0)
        val current = storage.getString(module.id, null)?.let { JSONObject(it).getInt("revision") } ?: 1
        if (revision < current) return
        val requirement = "requires_" + module.id
        if (!compatible(entry)) {
            check(editor.putInt(requirement, revision).commit())
            return
        }
        editor.remove(requirement)
        if (revision == current) {
            if (storage.contains(requirement)) check(editor.commit())
            return
        }
        val bytes = load("packages/${module.id}/$revision.json")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(hash == entry.getString("sha256"))
        val json = String(bytes, Charsets.UTF_8)
        val profile = JSONObject(json)
        require(profile.getString("id") == module.id && profile.getInt("revision") == revision)
        require(compatible(profile) && profile.getInt(module.field) in module.range)
        check(editor.putString(module.id, json).commit())
    }
}
