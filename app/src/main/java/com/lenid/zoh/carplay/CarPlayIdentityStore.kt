package com.lenid.zoh.carplay

import android.content.Context
import android.util.Base64
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import org.json.JSONObject
import java.io.File

/** CarPlay pairing data is separate from AA preferences and excluded from Android backups. */
internal class CarPlayIdentityStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "carplay-pairing.json")
    private val data = if (file.exists()) JSONObject(file.readText()) else JSONObject()
    private fun decode(s: String) = Base64.decode(s, Base64.NO_WRAP)
    private fun encode(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    @Synchronized private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(data.toString())
        check(tmp.renameTo(file)) { "Cannot persist CarPlay pairing" }
    }
    @Synchronized fun identity(): AirPlayIdentity {
        if (data.has("private")) return AirPlayIdentity(decode(data.getString("private")),
            decode(data.getString("public")), data.getString("id"))
        return AirPlayIdentity.generate().also {
            data.put("private", encode(it.privateKey)).put("public", encode(it.publicKey)).put("id", it.pairingId)
            save()
        }
    }
    @Synchronized fun pairings(): PairingStore {
        val stored = data.optJSONObject("peers") ?: JSONObject().also { data.put("peers", it) }
        var loading = true
        val result = PairingStore { id, key -> synchronized(this) {
            if (!loading) { stored.put(id, encode(key)); save() }
        } }
        stored.keys().forEach { id -> result.save(id, decode(stored.getString(id))) }
        loading = false
        return result
    }
    @Synchronized fun lockdown(): com.shilapi.xcertplay.transport.LockdownPairRecord? {
        val r = data.optJSONObject("lockdown") ?: return null
        return com.shilapi.xcertplay.transport.LockdownPairRecord.restore(
            hostId = r.getString("hostId"), systemBuid = r.getString("systemBuid"), wifiMacAddress = r.getString("wifiMac"),
            devicePublicKeyPem = decode(r.getString("devicePublic")), deviceCertificatePem = decode(r.getString("deviceCert")),
            hostPrivateKeyPem = decode(r.getString("hostPrivate")), hostCertificatePem = decode(r.getString("hostCert")),
            rootPrivateKeyPem = decode(r.getString("rootPrivate")), rootCertificatePem = decode(r.getString("rootCert")))
    }
    @Synchronized fun saveLockdown(r: com.shilapi.xcertplay.transport.LockdownPairRecord) {
        data.put("lockdown", JSONObject().put("hostId", r.hostId).put("systemBuid", r.systemBuid).put("wifiMac", r.wifiMacAddress)
            .put("devicePublic", encode(r.devicePublicKeyPem)).put("deviceCert", encode(r.deviceCertificatePem))
            .put("hostPrivate", encode(r.hostPrivateKeyPem)).put("hostCert", encode(r.hostCertificatePem))
            .put("rootPrivate", encode(r.rootPrivateKeyPem)).put("rootCert", encode(r.rootCertificatePem)))
        save()
    }
    @Synchronized fun clearLockdown() { data.remove("lockdown"); save() }
}
