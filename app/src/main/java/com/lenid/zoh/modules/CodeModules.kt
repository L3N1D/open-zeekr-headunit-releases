package com.lenid.zoh.modules

import com.shilapi.xcertplay.transport.TransportIo

import android.content.Context
import android.util.Base64
import com.lenid.zoh.BuildConfig
import com.lenid.zoh.utils.ModuleProfile
import com.lenid.zoh.utils.ModuleUpdates
import org.json.JSONObject
import java.security.MessageDigest

internal enum class CodeModule(val profile: ModuleProfile, val arity: Int, val results: IntRange, val builtinRevision: Int) {
    AA(ModuleProfile.AA, 2, 1..3, BuildConfig.MODULE_REV_ANDROID_AUTO),
    CARPLAY(ModuleProfile.CARPLAY, 4, 0..1, BuildConfig.MODULE_REV_CARPLAY),
    ZEEKR(ModuleProfile.ZEEKR, 2, 0..1, BuildConfig.MODULE_REV_ZEEKR_INTEGRATION),
    UI(ModuleProfile.UI, 4, 0..1, BuildConfig.MODULE_REV_UI),
    AUTOKIT(ModuleProfile.AUTOKIT, 1, 120..320, BuildConfig.MODULE_REV_AUTOKIT);

    fun evaluate(vararg inputs: Int): Int? = CodeModules.run(this, "apply", results, inputs)
}

internal enum class ModuleFunction(val module: CodeModule, val export: String, val results: IntRange, val sinceAbi: Int = 2, val arity: Int = 1) {
    VERSION_REQUESTS(CodeModule.AA, "versionRequests", 1..3),
    MEDIA_BUTTON(CodeModule.CARPLAY, "mediaButton", 0..4),
    ACTIVATE_MEDIA(CodeModule.ZEEKR, "activateMedia", 0..1),
    RESIZE_DURATION(CodeModule.UI, "resizeDuration", 100..1000),
    AUDIO_ROUTE(CodeModule.AUTOKIT, "audioRoute", 1..3),
    HANDSHAKE_FRAME(CodeModule.AA, "handshakeFrame", 0..2, 3),
    CONNECT_ACTION(CodeModule.CARPLAY, "connectAction", 0..3, 3),
    OPEN_STEP(CodeModule.AUTOKIT, "openStep", 0..11, 3),
    READ_ERROR(CodeModule.AA, "readError", 0..1, 4, 3),
    VERSION_FAILURE(CodeModule.AA, "versionFailure", 0..2, 4, 2),
    WINDOW_HEIGHT(CodeModule.CARPLAY, "windowHeight", 240..4096, 4),
    MEDIA_KEY(CodeModule.ZEEKR, "mediaKey", 0..127, 4),
    WIDGET_HEIGHT(CodeModule.UI, "widgetHeight", 0..Int.MAX_VALUE, 4, 4),
    WIDGET_ROOM(CodeModule.UI, "widgetRoom", Int.MIN_VALUE..Int.MAX_VALUE, 4, 4),
    PCM_FORMAT(CodeModule.AUTOKIT, "pcmFormat", 0..5, 4),
    AA_IO(CodeModule.AA, "transfer", 0..Int.MAX_VALUE, 5, 3),
    AA_OPEN(CodeModule.AA, "openTransport", 0..2, 5, 2),
    CARPLAY_IO(CodeModule.CARPLAY, "transfer", 0..Int.MAX_VALUE, 5, 3),
    CARPLAY_OPEN(CodeModule.CARPLAY, "openTransport", 0..2, 5, 2),
    AUTOKIT_IO(CodeModule.AUTOKIT, "transfer", 0..Int.MAX_VALUE, 5, 3),
    AUTOKIT_OPEN(CodeModule.AUTOKIT, "openTransport", 0..2, 5, 2),
    AA_RETRY(CodeModule.AA, "usbRetry", 0..2000, 5),
    CARPLAY_RETRY(CodeModule.CARPLAY, "usbRetry", 0..2000, 5),
    AUTOKIT_RETRY(CodeModule.AUTOKIT, "usbRetry", 0..2000, 5);

    fun evaluate(vararg inputs: Int): Int? = CodeModules.run(module, export, results, inputs)
}

internal object TransportModules {
    private fun engine(io: ModuleFunction, open: ModuleFunction, retry: ModuleFunction) = TransportIo(
        { mode, remaining, transferred -> io.evaluate(mode, remaining, transferred) },
        { path, position -> open.evaluate(path, position) },
        { failed -> retry.evaluate(failed) },
    )
    val androidAuto = engine(ModuleFunction.AA_IO, ModuleFunction.AA_OPEN, ModuleFunction.AA_RETRY)
    val carPlay = engine(ModuleFunction.CARPLAY_IO, ModuleFunction.CARPLAY_OPEN, ModuleFunction.CARPLAY_RETRY)
    val autoKit = engine(ModuleFunction.AUTOKIT_IO, ModuleFunction.AUTOKIT_OPEN, ModuleFunction.AUTOKIT_RETRY)
}

/** Programs are checked before staging and again before activation. Live sessions retain their snapshot. */
internal object CodeModules {
    private data class Active(val revision: Int, val programs: Map<String, PolicyProgram>)
    @Volatile private var active: Map<CodeModule, Active> = emptyMap()
    @Volatile private var staged: Map<CodeModule, Int> = emptyMap()
    private fun prefs(context: Context) = context.getSharedPreferences("code_modules_v1", Context.MODE_PRIVATE)

    private fun entry(catalog: JSONObject, module: CodeModule): JSONObject {
        require(catalog.getInt("abi") in 1..5)
        val item = catalog.getJSONObject("modules").getJSONObject(module.profile.id)
        require(item.getInt("revision") > 0 && item.getInt("minCore") <= BuildConfig.VERSION_CODE &&
            BuildConfig.VERSION_CODE <= item.getInt("maxCore"))
        return item
    }

    private fun validate(item: JSONObject, bytes: ByteArray, module: CodeModule, abi: Int): Active {
        require(bytes.size <= 4096)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(digest == item.getString("sha256"))
        return Active(item.getInt("revision"), decode(module, bytes, abi))
    }

    private fun decode(module: CodeModule, bytes: ByteArray, abi: Int): Map<String, PolicyProgram> {
        val signatures = mutableMapOf("apply" to module.arity)
        ModuleFunction.values().filter { it.module == module && abi >= it.sinceAbi }.forEach { signatures[it.export] = it.arity }
        return PolicyProgram.decode(bytes, signatures)
    }

    fun initialize(context: Context, key: ByteArray = ModuleUpdates.key(context)) {
        active = CodeModule.values().mapNotNull { module ->
            val id = module.profile.id
            val stored = prefs(context).getString(id, null)
            val update = stored?.let { runCatching {
                val bundle = JSONObject(it)
                val catalog = ModuleUpdates.verify(key, bundle.getJSONObject("envelope"))
                validate(entry(catalog, module), Base64.decode(bundle.getString("wasm"), Base64.DEFAULT), module, catalog.getInt("abi"))
            }.getOrNull() }
            val selected = update?.takeIf { it.revision >= module.builtinRevision } ?: runCatching {
                val bytes = context.assets.open("code-modules/$id/${module.builtinRevision}.wasm").use { it.readBytes() }
                Active(module.builtinRevision, decode(module, bytes, 5))
            }.getOrNull()
            selected?.let { module to it }
        }.toMap()
        staged = emptyMap()
    }

    fun run(module: CodeModule, export: String, results: IntRange, args: IntArray): Int? =
        active[module]?.programs?.get(export)?.run(args)?.takeIf { it in results }

    fun activeRevision(profile: ModuleProfile) = CodeModule.valueOf(profile.name).let { active[it]?.revision ?: it.builtinRevision }
    fun stagedRevision(profile: ModuleProfile) = staged[CodeModule.valueOf(profile.name)] ?: activeRevision(profile)

    fun revisions(): Map<String, Int> = active.mapKeys { it.key.profile.id }.mapValues { it.value.revision }

    /** Called under the same single-writer lock as profile refresh. Separate signed feed and versions. */
    fun refresh(context: Context, key: ByteArray = ModuleUpdates.key(context), load: (String) -> ByteArray): Boolean {
        val envelope = JSONObject(String(load("code-modules/stable-v5.json"), Charsets.UTF_8))
        val catalog = ModuleUpdates.verify(key, envelope)
        require(catalog.getInt("abi") == 5)
        val storage = prefs(context)
        val verified = staged.toMutableMap()
        var complete = true
        for (module in CodeModule.values()) {
            try {
                val id = module.profile.id
                val item = entry(catalog, module)
                val revision = item.getInt("revision")
                val cached = storage.getString(id, null)?.let {
                    runCatching {
                        val saved = JSONObject(it)
                        val signed = ModuleUpdates.verify(key, saved.getJSONObject("envelope"))
                        validate(entry(signed, module), Base64.decode(saved.getString("wasm"), Base64.DEFAULT), module, signed.getInt("abi")).revision
                    }.getOrNull()
                } ?: module.builtinRevision
                verified[module] = cached
                if (revision <= maxOf(cached, active[module]?.revision ?: module.builtinRevision)) continue
                val bytes = load("code-modules/packages/$id/$revision.wasm")
                validate(item, bytes, module, catalog.getInt("abi"))
                val bundle = JSONObject().put("revision", revision).put("envelope", envelope)
                    .put("wasm", Base64.encodeToString(bytes, Base64.NO_WRAP))
                check(storage.edit().putString(id, bundle.toString()).commit())
                verified[module] = revision
            } catch (e: Exception) {
                complete = false
            }
        }
        staged = verified.toMap()
        return complete
    }
}
