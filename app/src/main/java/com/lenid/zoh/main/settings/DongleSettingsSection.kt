package com.lenid.zoh.main.settings

import android.content.Intent
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lenid.zoh.R
import com.lenid.zoh.dongle.DongleActivity
import com.lenid.zoh.dongle.DongleService
import com.lenid.zoh.dongle.DongleSettings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The dongle tab: everything the AutoKit dongle is told in its handshake, plus what it reports about
 * itself. Unlike the AA settings these save at once; DongleService notices and runs a new handshake
 * (or switches night mode live), so there is nothing to Save.
 */
class DongleSettingsSection(private val fragment: Fragment, private val refresh: () -> Unit) {

    /** Redraws the tab when the dongle's status changes, while the settings are on screen. */
    fun watch(owner: LifecycleOwner) {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DongleService.state.drop(1).collect { refresh() }
            }
        }
    }

    fun addTo(items: MutableList<SettingItem>) {
        val context = fragment.requireContext()
        val s = DongleSettings(context)
        val state = DongleService.state.value
        fun str(@StringRes id: Int) = context.getString(id)

        items.add(SettingItem.CategoryHeader(DongleActivity.DONGLE_CATEGORY, R.string.category_dongle))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "dongle_enabled",
            nameResId = R.string.dongle_enabled,
            descriptionResId = null,
            descriptionOverride = if (state.active) state.status else str(R.string.dongle_off),
            isChecked = state.active,
            onCheckedChanged = { on ->
                if (on) DongleService.start(context) else DongleService.stop(context)
            }
        ))
        items.add(SettingItem.SettingEntry(
            stableId = "dongle_open",
            nameResId = R.string.dongle_open_screen,
            value = str(R.string.dongle_open_screen_hint),
            onClick = { fragment.startActivity(Intent(context, DongleActivity::class.java)) }
        ))
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "dongle_auto_open",
            nameResId = R.string.dongle_auto_open,
            descriptionResId = R.string.dongle_auto_open_hint,
            isChecked = s.autoOpen,
            onCheckedChanged = { s.autoOpen = it }
        ))

        choice(items, "dongle_resolution", R.string.dongle_resolution, s.resolution, listOf(
            "auto" to str(R.string.dongle_auto_screen), "800x480" to "800 × 480", "1280x720" to "1280 × 720",
            "1920x720" to "1920 × 720", "1920x1080" to "1920 × 1080")) { s.resolution = it }
        choice(items, "dongle_fps", R.string.dongle_fps, s.fps,
            listOf(30 to context.getString(R.string.dongle_fps_value, 30), 60 to context.getString(R.string.dongle_fps_value, 60))) {
            s.fps = it
        }
        choice(items, "dongle_dpi", R.string.dongle_dpi, s.dpi, listOf(0 to str(R.string.dongle_auto),
            120 to "120", 160 to "160", 200 to "200", 240 to "240", 280 to "280")) { s.dpi = it }
        choice(items, "dongle_night", R.string.dongle_night, s.nightMode, listOf(
            0 to str(R.string.dongle_night_system), 1 to str(R.string.dongle_night_day), 2 to str(R.string.dongle_night_night))) {
            s.nightMode = it
        }
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "dongle_rhd",
            nameResId = R.string.dongle_rhd,
            descriptionResId = R.string.dongle_rhd_hint,
            isChecked = s.rightHandDrive,
            onCheckedChanged = { s.rightHandDrive = it }
        ))
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "dongle_bt_audio",
            nameResId = R.string.dongle_bt_audio,
            descriptionResId = R.string.dongle_bt_audio_hint,
            isChecked = s.bluetoothAudio,
            onCheckedChanged = { s.bluetoothAudio = it }
        ))
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "dongle_mic",
            nameResId = R.string.dongle_mic,
            descriptionResId = R.string.dongle_mic_hint,
            isChecked = s.dongleMic,
            onCheckedChanged = { s.dongleMic = it }
        ))
        choice(items, "dongle_media_delay", R.string.dongle_media_delay, s.mediaDelay,
            listOf(0, 150, 300, 500, 1000).map { it to context.getString(R.string.dongle_ms, it) }) { s.mediaDelay = it }
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "dongle_wifi5g",
            nameResId = R.string.dongle_wifi5g,
            descriptionResId = R.string.dongle_wifi5g_hint,
            isChecked = s.wifi5g,
            onCheckedChanged = { s.wifi5g = it }
        ))
        items.add(SettingItem.SettingEntry(
            stableId = "dongle_name",
            nameResId = R.string.dongle_name,
            value = s.dongleName,
            onClick = { editText(R.string.dongle_name, s.dongleName, InputType.TYPE_CLASS_TEXT) { s.dongleName = it } }
        ))
        items.add(SettingItem.SettingEntry(
            stableId = "dongle_reconnect",
            nameResId = R.string.dongle_reconnect,
            value = str(R.string.dongle_reconnect_hint),
            isEnabled = state.active,
            onClick = { DongleService.sendReconnect(context) }
        ))
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "dongle_bridge",
            nameResId = R.string.dongle_bridge,
            descriptionResId = null,
            descriptionOverride = context.getString(R.string.dongle_bridge_hint, "${s.bridgeHost}:${DongleService.BRIDGE_PORT}"),
            isChecked = s.debugBridge,
            onCheckedChanged = { s.debugBridge = it }
        ))
        items.add(SettingItem.SettingEntry(
            stableId = "dongle_bridge_host",
            nameResId = R.string.dongle_bridge_host,
            value = s.bridgeHost,
            onClick = {
                editText(R.string.dongle_bridge_host, s.bridgeHost,
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { s.bridgeHost = it }
            }
        ))

        val info = DongleService.info
        if (info.isEmpty()) {
            items.add(SettingItem.SettingEntry(
                stableId = "dongle_info",
                nameResId = R.string.dongle_info,
                value = str(R.string.dongle_info_empty),
                isEnabled = false,
                onClick = {}
            ))
        } else {
            info.forEach { (k, v) ->
                items.add(SettingItem.SettingEntry(
                    stableId = "dongle_info_$k",
                    nameResId = R.string.dongle_info,
                    nameOverride = k,
                    value = v,
                    onClick = {}
                ))
            }
        }
    }

    private fun <T> choice(items: MutableList<SettingItem>, id: String, @StringRes title: Int, current: T,
                           options: List<Pair<T, String>>, set: (T) -> Unit) {
        val context = fragment.requireContext()
        items.add(SettingItem.SettingEntry(
            stableId = id,
            nameResId = title,
            value = options.firstOrNull { it.first == current }?.second ?: current.toString(),
            searchKeywords = options.joinToString(" ") { it.second },
            onClick = {
                MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
                    .setTitle(title)
                    .setSingleChoiceItems(options.map { it.second }.toTypedArray(),
                        options.indexOfFirst { it.first == current }) { d, i ->
                        set(options[i].first)
                        d.dismiss()
                        refresh()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        ))
    }

    private fun editText(@StringRes title: Int, value: String, type: Int, save: (String) -> Unit) {
        val context = fragment.requireContext()
        val input = EditText(context).apply {
            setText(value)
            inputType = type
            setSingleLine()
        }
        val box = FrameLayout(context).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) { save(text); refresh() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
