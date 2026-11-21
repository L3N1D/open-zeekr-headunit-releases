package com.lenid.zoh.modules

import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.lenid.zoh.carplay.CarPlayViewport
import com.lenid.zoh.view.ProjectionResizeMotionPolicy
import com.lenid.zoh.aap.VehicleTypePolicy
import com.lenid.zoh.aap.MediaSessionActivationPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature

class CodeModuleUpdateTest {
    @Test fun authenticatedCodeChangeActivatesOnRestart() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val ctx = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("code-module-test-" + name, mode)
        }
        val prefs = ctx.getSharedPreferences("code_modules_v1", 0)
        val phase = InstrumentationRegistry.getArguments().getString("phase")
        if (phase == "restart") {
            try {
                val pub = Base64.decode(checkNotNull(prefs.getString("test_public_key", null)), Base64.NO_WRAP)
                CodeModules.initialize(ctx, pub)
                assertEquals(CodeModule.UI.builtinRevision + 1, CodeModules.revisions()["ui"])
                assertEquals(CodeModule.AA.builtinRevision, CodeModules.revisions()["android-auto"])
                assertFalse(ProjectionResizeMotionPolicy.shouldAnimate(1000,600,1300,610))
                assertTrue(ProjectionResizeMotionPolicy.shouldAnimate(1300,600,1000,610))
            } finally {
                prefs.edit().clear().commit()
                CodeModules.initialize(target)
            }
            return
        }
        prefs.edit().clear().commit()
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val pub = key.public.encoded
        try {
            CodeModules.initialize(ctx, pub)
            assertEquals(5, CodeModules.revisions().size)
            assertEquals(3, ModuleFunction.VERSION_REQUESTS.evaluate(1))
            assertEquals(1, ModuleFunction.VERSION_REQUESTS.evaluate(0))
            assertEquals(3, ModuleFunction.MEDIA_BUTTON.evaluate(87))
            assertEquals(0, ModuleFunction.MEDIA_BUTTON.evaluate(99))
            assertEquals(0, ModuleFunction.ACTIVATE_MEDIA.evaluate(1))
            assertEquals(1, ModuleFunction.ACTIVATE_MEDIA.evaluate(0))
            assertEquals(450, ModuleFunction.RESIZE_DURATION.evaluate(521))
            assertEquals(450, ModuleFunction.RESIZE_DURATION.evaluate(-521))
            assertEquals(2, ModuleFunction.AUDIO_ROUTE.evaluate(2))
            assertEquals(3, ModuleFunction.AUDIO_ROUTE.evaluate(3))
            assertEquals(1, ModuleFunction.AUDIO_ROUTE.evaluate(4))
            assertEquals(3, VehicleTypePolicy.vehicleType(1, false))
            assertEquals(120, CodeModule.AUTOKIT.evaluate(70))
            assertTrue(MediaSessionActivationPolicy.shouldSyncAaMetadata(false, true))
            assertTrue(ProjectionResizeMotionPolicy.shouldAnimate(1000, 600, 1300, 610))
            assertEquals(CarPlayViewport(960, 720), CarPlayViewport(800, 600).fitIn(CarPlayViewport(1280,720)))

            val id = "ui"
            // Test-only revision changes real behavior: animate only if width actually shrinks.
            val old = ctx.assets.open("code-modules/$id/${CodeModule.UI.builtinRevision}.wasm").use { it.readBytes() }
            val body = byteArrayOf(0, 0x20, 2, 0x20, 0, 0x48, 0x0b)
            val bytes = old.replaceBody(0, body)
            val items = JSONObject()
            CodeModule.values().forEach {
                items.put(it.profile.id, JSONObject().put("revision", it.builtinRevision + if (it == CodeModule.UI) 1 else 0)
                    .put("minCore", 1).put("maxCore", Int.MAX_VALUE)
                    .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { n -> "%02x".format(n) }))
            }
            val data = JSONObject().put("abi", 5).put("modules", items).toString().toByteArray()
            val envelope = signed(data, key)
            val path = "code-modules/packages/ui/${CodeModule.UI.builtinRevision + 1}.wasm"
            fun fetch(bad: Boolean): List<String> {
                val calls = mutableListOf<String>()
                val ok = CodeModules.refresh(ctx, pub) {
                    calls.add(it)
                    when (it) {
                        "code-modules/stable-v5.json" -> envelope
                        path -> if (bad) old else bytes
                        else -> error("Unexpected download")
                    }
                }
                assertEquals(!bad, ok)
                return calls
            }
            fetch(true)
            assertFalse(prefs.contains(id))
            assertEquals(listOf("code-modules/stable-v5.json", path), fetch(false))
            assertTrue(ProjectionResizeMotionPolicy.shouldAnimate(1000,600,1300,610))
            if (phase == "stage") {
                assertTrue(prefs.edit().putString("test_public_key", Base64.encodeToString(pub, Base64.NO_WRAP)).commit())
                return
            }
            CodeModules.initialize(ctx, pub)
            assertFalse(ProjectionResizeMotionPolicy.shouldAnimate(1000,600,1300,610))
            assertTrue(ProjectionResizeMotionPolicy.shouldAnimate(1300,600,1000,610))
            assertEquals(CodeModule.UI.builtinRevision + 1, CodeModules.revisions()[id])
            assertEquals(CodeModule.AA.builtinRevision, CodeModules.revisions()["android-auto"])
            val wrong = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
            CodeModules.initialize(ctx, wrong.public.encoded)
            assertEquals(CodeModule.UI.builtinRevision, CodeModules.revisions()[id])
        } finally {
            if (phase != "stage") prefs.edit().clear().commit()
            CodeModules.initialize(target)
        }
    }

    @Test fun eachModuleUpdatesIndependently() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("independent-test-" + name, mode)
        }
        val storage = context.getSharedPreferences("code_modules_v1", 0)
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        try {
            for (function in ModuleFunction.values()) {
                storage.edit().clear().commit()
                CodeModules.initialize(context, key.public.encoded)
                val module = function.module
                val id = module.profile.id
                val initial = checkNotNull(function.evaluate(*IntArray(function.arity)))
                val replacement = if (initial == function.results.first.coerceAtLeast(0)) initial + 1 else function.results.first.coerceAtLeast(0)
                val next = module.builtinRevision + 1
                val original = context.assets.open("code-modules/$id/${module.builtinRevision}.wasm").use { it.readBytes() }
                val index = 1 + ModuleFunction.values().filter { it.module == module }.indexOf(function)
                val constant = if (replacement < 64) byteArrayOf(replacement.toByte()) else
                    byteArrayOf(((replacement and 127) or 128).toByte(), (replacement shr 7).toByte())
                val program = original.replaceBody(index, byteArrayOf(0, 0x41) + constant + byteArrayOf(0x0b))
                val hash = MessageDigest.getInstance("SHA-256").digest(program).joinToString("") { "%02x".format(it) }
                val items = JSONObject()
                CodeModule.values().forEach {
                    items.put(it.profile.id, JSONObject().put("revision", if (it == module) next else it.builtinRevision)
                        .put("minCore", 1).put("maxCore", Int.MAX_VALUE).put("sha256", hash))
                }
                val catalog = signed(JSONObject().put("abi", 5).put("modules", items).toString().toByteArray(), key)
                val requests = mutableListOf<String>()
                val path = "code-modules/packages/$id/$next.wasm"
                assertTrue(CodeModules.refresh(context, key.public.encoded) {
                    requests.add(it)
                    when (it) {
                        "code-modules/stable-v5.json" -> catalog
                        path -> program
                        else -> error(it)
                    }
                })
                assertEquals(listOf("code-modules/stable-v5.json", path), requests)
                assertEquals(initial, function.evaluate(*IntArray(function.arity)))
                if (function.export == "transfer") assertTransportWrites(module, listOf(5))
                CodeModules.initialize(context, key.public.encoded)
                assertEquals(replacement, function.evaluate(*IntArray(function.arity)))
                if (function.export == "transfer") assertTransportWrites(module, List(5) { 1 })
                for (other in CodeModule.values()) assertEquals(if (other == module) next else other.builtinRevision, CodeModules.activeRevision(other.profile))
                assertTrue(ProjectionResizeMotionPolicy.shouldAnimate(1275,876,1796,876))
                assertTrue(ProjectionResizeMotionPolicy.shouldAnimate(1796,876,1275,876))
            }
        } finally {
            storage.edit().clear().commit()
            CodeModules.initialize(target)
        }
    }

    private fun assertTransportWrites(module: CodeModule, expected: List<Int>) {
        val engine = when (module) {
            CodeModule.AA -> TransportModules.androidAuto
            CodeModule.CARPLAY -> TransportModules.carPlay
            CodeModule.AUTOKIT -> TransportModules.autoKit
            else -> error("No transport in $module")
        }
        val usbSizes = mutableListOf<Int>()
        if (module == CodeModule.AA) {
            assertEquals(5, engine.writeUsb(5, 3000, 1) { _, size, _ -> usbSizes.add(size); size })
            assertEquals(expected, usbSizes)
        } else if (module == CodeModule.AUTOKIT) {
            assertEquals(5, engine.transfer(com.shilapi.xcertplay.transport.TransportIo.Mode.FULL, 5) { _, size ->
                usbSizes.add(size); size
            })
            assertEquals(expected, usbSizes)
        } else {
            assertEquals(expected.first(), engine.queueBytes(5))
        }
        val loopback = java.net.InetAddress.getByName("127.0.0.1")
        java.net.ServerSocket(0, 1, loopback).use { listener ->
            listener.soTimeout = 2000
            java.net.Socket(loopback, listener.localPort).use { client ->
                listener.accept().use { peer ->
                    peer.soTimeout = 2000
                    val sizes = mutableListOf<Int>()
                    val output = object : java.io.FilterOutputStream(client.getOutputStream()) {
                        override fun write(data: ByteArray, off: Int, len: Int) {
                            sizes.add(len)
                            out.write(data, off, len)
                        }
                    }
                    val source = byteArrayOf(99, 1, 2, 3, 4, 5, 99)
                    engine.write(output, source, 1, 5)
                    output.flush()
                    val received = ByteArray(5)
                    java.io.DataInputStream(peer.getInputStream()).readFully(received)
                    assertArrayEquals(source.copyOfRange(1, 6), received)
                    assertEquals(expected, sizes)
                }
            }
        }
    }

    private fun signed(data: ByteArray, key: java.security.KeyPair): ByteArray {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(key.private)
        signer.update(data)
        return JSONObject().put("payload", Base64.encodeToString(data, Base64.NO_WRAP))
            .put("signature", Base64.encodeToString(signer.sign(), Base64.NO_WRAP)).toString().toByteArray()
    }

    private fun ByteArray.replaceBody(index: Int, replacement: ByteArray): ByteArray {
        val input = inputStream()
        val output = java.io.ByteArrayOutputStream()
        fun number(): Int {
            var result = 0
            var shift = 0
            while (shift < 32) {
                val byte = input.read()
                require(byte >= 0)
                result = result or ((byte and 127) shl shift)
                if (byte < 128) return result
                shift += 7
            }
            error("Invalid fixture length")
        }
        fun bytes(count: Int) = ByteArray(count).also { require(input.read(it) == count) }
        fun java.io.ByteArrayOutputStream.number(value: Int) {
            var remaining = value
            while (remaining >= 128) {
                write((remaining and 127) or 128)
                remaining = remaining ushr 7
            }
            write(remaining)
        }
        output.write(bytes(8))
        while (input.available() > 0) {
            val section = input.read()
            val length = number()
            output.write(section)
            if (section != 10) {
                output.number(length)
                output.write(bytes(length))
                continue
            }
            val payload = java.io.ByteArrayOutputStream()
            val count = number()
            require(index in 0 until count)
            payload.number(count)
            repeat(count) { i ->
                val old = bytes(number())
                val body = if (i == index) replacement else old
                payload.number(body.size)
                payload.write(body)
            }
            output.number(payload.size())
            payload.writeTo(output)
        }
        return output.toByteArray()
    }
}
