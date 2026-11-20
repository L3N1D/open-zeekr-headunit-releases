package com.lenid.zoh.main

import android.bluetooth.BluetoothManager
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lenid.zoh.App
import com.lenid.zoh.R
import com.lenid.zoh.carplay.DirectCarPlayService
import com.lenid.zoh.dongle.DongleService
import org.json.JSONArray
import org.json.JSONObject

/** Explicit driver selection while idle; never switches away from a live phone. */
internal class DriverProfilesDialog(private val fragment: Fragment) {
    private val c get() = fragment.requireContext()
    private val prefs get() = c.getSharedPreferences("settings", 0)
    private fun profiles(): JSONArray = runCatching {
        val input = JSONArray(prefs.getString("driver-profiles", "[]"))
        val clean = JSONArray()
        for(i in 0 until minOf(input.length(), 10)) {
            val row = input.getJSONObject(i)
            if(row.getString("name").length in 1..40 && row.getInt("dpi") in 0..640 &&
                row.getInt("fps") in 1..120 && row.getInt("resolution") in 0..100 &&
                (row.getString("phone").isBlank() || android.bluetooth.BluetoothAdapter.checkBluetoothAddress(row.getString("phone")))) clean.put(row)
        }
        clean
    }.getOrDefault(JSONArray())
    private fun idle(): Boolean {
        val busy = App.provide(c).commManager.isBusy || DirectCarPlayService.sessionRunning() || DongleService.state.value.active
        if (busy) com.lenid.zoh.utils.ToastUtils.showToast(c, R.string.support_profile_idle, Toast.LENGTH_LONG, force = true)
        return !busy
    }
    fun show() {
        val data = profiles()
        val names = (0 until data.length()).map { data.getJSONObject(it).getString("name") }.toTypedArray()
        MaterialAlertDialogBuilder(c, R.style.DarkAlertDialog).setTitle(R.string.support_profiles)
            .setItems(names) { _, index ->
                val profile = data.getJSONObject(index)
                MaterialAlertDialogBuilder(c, R.style.DarkAlertDialog).setTitle(profile.getString("name"))
                    .setMessage(R.string.support_profile_note)
                    .setPositiveButton(R.string.support_profile_apply) { _, _ ->
                        if (idle()) {
                            com.lenid.zoh.utils.PictureSettingsTrial.begin(c)
                            val s = App.provide(c).settings
                            s.dpiPixelDensity = profile.getInt("dpi")
                            s.fpsLimit = profile.getInt("fps")
                            s.resolutionId = profile.getInt("resolution")
                            s.nativePreferredDeviceMac = profile.getString("phone")
                            fragment.view?.let { PictureTrialNotice.show(it) }
                            com.lenid.zoh.utils.ToastUtils.showToast(c, R.string.settings_saved, Toast.LENGTH_SHORT, force = true)
                        }
                    }.setNeutralButton(R.string.support_clear) { _, _ -> data.remove(index); prefs.edit().putString("driver-profiles", data.toString()).apply() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }.setPositiveButton(R.string.support_profile_save) { _, _ -> save() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun save() {
        if(!idle()) return
        val name = EditText(c).apply { setHint(R.string.support_profile_name); filters = arrayOf(android.text.InputFilter.LengthFilter(40)) }
        MaterialAlertDialogBuilder(c, R.style.DarkAlertDialog).setTitle(R.string.support_profile_save).setView(name)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val title = name.text.toString().trim()
                if(title.isNotBlank()) choosePhone(title)
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun choosePhone(name: String) {
        val devices = runCatching { c.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
        val names = devices.map { runCatching { it.name ?: it.address }.getOrDefault("Bluetooth") }
        MaterialAlertDialogBuilder(c, R.style.DarkAlertDialog).setTitle(R.string.support_profile_phone)
            .setItems((listOf(c.getString(R.string.support_profile_current)) + names).toTypedArray()) { _, index ->
                if(!idle()) return@setItems
                val s = App.provide(c).settings
                val mac = if(index == 0) s.nativePreferredDeviceMac else runCatching { devices[index - 1].address }.getOrDefault("")
                val data = profiles()
                if(data.length() >= 10) data.remove(0)
                data.put(JSONObject().put("name", name).put("dpi", s.dpiPixelDensity).put("fps", s.fpsLimit).put("resolution", s.resolutionId).put("phone", mac))
                prefs.edit().putString("driver-profiles", data.toString()).apply()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
}
