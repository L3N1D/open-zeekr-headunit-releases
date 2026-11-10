package com.lenid.zoh.main.settings

import android.text.format.DateUtils
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.lenid.zoh.App
import com.lenid.zoh.BuildConfig
import com.lenid.zoh.R
import com.lenid.zoh.main.UpdatePrompt
import com.lenid.zoh.utils.AppLog
import com.lenid.zoh.utils.CrashJournal
import com.lenid.zoh.utils.ToastUtils
import com.lenid.zoh.utils.UpdateChecker
import com.lenid.zoh.utils.UpdateInfo
import com.lenid.zoh.utils.UpdateInstaller
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The updates tab (layout_setting_update_panel): checks GitHub when it is first shown, offers a new
 * release with what it fixes, and downloads it with the progress on the card instead of a dialog.
 * It belongs to the settings screen, not to a list row, so a check or a download keeps going while
 * the list is rebuilt, and the row shows it again when it is bound.
 */
class UpdatePanel(private val fragment: Fragment) {

    private enum class Status { IDLE, CHECKING, LATEST, AVAILABLE, DOWNLOADING, FAILED }

    private var status = Status.IDLE
    private var info: UpdateInfo? = null
    private var percent = 0
    private var notesOpen = false
    private var view: View? = null

    fun bind(row: View) {
        view = row
        row.findViewById<View>(R.id.updateAppCard).setOnClickListener { check() }
        row.findViewById<MaterialButton>(R.id.updateInstallButton).setOnClickListener { install() }
        row.findViewById<MaterialButton>(R.id.updateNotesButton).setOnClickListener {
            val notes = info?.notes.orEmpty()
            if (notes.isEmpty()) {
                info?.let { UpdateChecker.openGitHubReleases(row.context, it.releaseUrl) }
            } else {
                notesOpen = !notesOpen
                render()
            }
        }
        bindSwitch(row, R.id.updateAtStartRow, R.id.updateAtStartSwitch, UpdatePrompt.checkAtStartEnabled(row.context)) {
            UpdatePrompt.setCheckAtStart(row.context, it)
        }
        bindSwitch(row, R.id.updateReportsRow, R.id.updateReportsSwitch, CrashJournal.sendReports(row.context)) {
            CrashJournal.setSendReports(row.context, it)
        }
        render()
        if (status == Status.IDLE) check()
    }

    private fun bindSwitch(row: View, rowId: Int, switchId: Int, value: Boolean, onChange: (Boolean) -> Unit) {
        val switch = row.findViewById<MaterialSwitch>(switchId)
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = value
        switch.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        row.findViewById<View>(rowId).setOnClickListener { switch.toggle() }
    }

    private fun check() {
        if (status == Status.CHECKING || status == Status.DOWNLOADING) return
        val context = fragment.context ?: return
        status = Status.CHECKING
        render()
        fragment.lifecycleScope.launch {
            val result = UpdateChecker.check(context)
            result.onSuccess {
                UpdatePrompt.noteChecked(context)
                info = it
                status = if (it.isUpdateAvailable) Status.AVAILABLE else Status.LATEST
            }.onFailure {
                status = Status.FAILED
            }
            render()
        }
    }

    private fun install() {
        val activity = fragment.activity ?: return
        val update = info ?: return
        if (status == Status.DOWNLOADING) return
        if (update.isPlayStore) {
            UpdateChecker.openPlayStore(activity)
            return
        }
        val url = update.apkUrl ?: run {
            UpdateChecker.openGitHubReleases(activity, update.releaseUrl)
            return
        }
        if (App.provide(activity).commManager.isConnected) {
            ToastUtils.showToast(activity, R.string.update_wait_disconnect, Toast.LENGTH_LONG, force = true)
            return
        }
        if (!UpdateInstaller.canInstall(activity)) {
            // One-time: Android asks the user to let this app install updates.
            ToastUtils.showToast(activity, R.string.update_install_permission, Toast.LENGTH_LONG, force = true)
            UpdateInstaller.openInstallPermission(activity)
            return
        }
        status = Status.DOWNLOADING
        percent = 0
        render()
        fragment.lifecycleScope.launch {
            val result = UpdateInstaller.download(activity, url) { p ->
                activity.runOnUiThread { percent = p; render() }
            }
            status = Status.AVAILABLE
            render()
            result.fold(
                onSuccess = { apk -> UpdateInstaller.install(activity, apk) },
                onFailure = {
                    AppLog.w("UpdatePanel: download failed: ${it.message}")
                    ToastUtils.showToast(activity, R.string.update_download_failed, Toast.LENGTH_LONG, force = true)
                }
            )
        }
    }

    private fun render() {
        val row = view ?: return
        val context = row.context
        val last = UpdatePrompt.lastCheck(context)
        val checked = if (last == 0L) context.getString(R.string.update_never_checked)
            else context.getString(R.string.update_checked_at, DateUtils.formatDateTime(context, last,
                DateUtils.FORMAT_SHOW_TIME or (if (DateUtils.isToday(last)) 0 else DateUtils.FORMAT_SHOW_DATE)))
        row.findViewById<TextView>(R.id.updateVersionLine).text =
            context.getString(R.string.update_version_line, BuildConfig.VERSION_NAME, checked)

        row.findViewById<Chip>(R.id.updateStatusChip).apply {
            text = when (status) {
                Status.IDLE, Status.CHECKING -> context.getString(R.string.update_status_checking)
                Status.LATEST -> context.getString(R.string.update_status_latest)
                Status.AVAILABLE -> context.getString(R.string.update_status_available)
                Status.DOWNLOADING -> context.getString(R.string.update_status_downloading, percent)
                Status.FAILED -> context.getString(R.string.update_status_failed)
            }
            chipIcon = if (status == Status.LATEST) androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_segment_check) else null
            isChipIconVisible = status == Status.LATEST
        }

        val update = info?.takeIf { it.isUpdateAvailable }
        val card = row.findViewById<View>(R.id.updateAvailableCard)
        card.visibility = if (update != null) View.VISIBLE else View.GONE
        if (update == null) return
        row.findViewById<TextView>(R.id.updateAvailableTitle).text =
            context.getString(R.string.update_available_version, update.latestVersionName)
        row.findViewById<Chip>(R.id.updateSizeChip).apply {
            visibility = if (update.apkSize > 0) View.VISIBLE else View.GONE
            text = context.getString(R.string.update_size_mb,
                String.format(Locale.getDefault(), "%.0f", update.apkSize / 1_048_576.0))
        }
        val notes = readableNotes(update.notes)
        row.findViewById<TextView>(R.id.updateNotes).apply {
            visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
            text = notes
            maxLines = if (notesOpen) Int.MAX_VALUE else 4
        }
        row.findViewById<MaterialButton>(R.id.updateNotesButton).setText(when {
            notes.isEmpty() -> R.string.update_open_release
            notesOpen -> R.string.update_less
            else -> R.string.update_whats_new
        })
        row.findViewById<LinearProgressIndicator>(R.id.updateProgress).apply {
            visibility = if (status == Status.DOWNLOADING) View.VISIBLE else View.GONE
            setProgressCompat(percent, true)
        }
        row.findViewById<MaterialButton>(R.id.updateInstallButton).apply {
            isEnabled = status != Status.DOWNLOADING
            text = if (status == Status.DOWNLOADING) context.getString(R.string.update_status_downloading, percent)
                else context.getString(R.string.update_install_now)
        }
    }

    /** The release text without the checksum line and markdown marks, which mean nothing to a driver. */
    private fun readableNotes(body: String) = body.lineSequence()
        .map { it.trim() }
        // The heading repeats the version the card already names, and the checksum means nothing here.
        .filterNot { it.startsWith("#") || it.startsWith("SHA-256", ignoreCase = true) }
        .map { if (it.startsWith("- ") || it.startsWith("* ")) "• " + it.drop(2) else it }
        .joinToString("\n").replace("**", "").replace(Regex("\n{3,}"), "\n\n").trim()
}
