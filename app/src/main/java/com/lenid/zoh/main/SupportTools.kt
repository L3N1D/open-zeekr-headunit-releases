package com.lenid.zoh.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lenid.zoh.App
import com.lenid.zoh.R
import com.lenid.zoh.utils.*
import java.text.DateFormat
import java.util.Date

/** Small user actions; no transport ownership or window-flag changes. */
internal class SupportTools(private val fragment: Fragment) {
    private val c get() = fragment.requireContext()
    private val prefs get() = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun dialog(title: Int) = MaterialAlertDialogBuilder(c, R.style.DarkAlertDialog).setTitle(title)
    private fun show(builder: MaterialAlertDialogBuilder) {
        val window = builder.show()
        val lifecycle = fragment.viewLifecycleOwner.lifecycle
        val observer = object : DefaultLifecycleObserver { override fun onStop(owner: LifecycleOwner) { window.dismiss() } }
        lifecycle.addObserver(observer)
        window.setOnDismissListener { lifecycle.removeObserver(observer) }
    }
    private fun message(title: Int, text: String) = show(dialog(title).setMessage(text).setPositiveButton(android.R.string.ok, null))
    fun open() {
        val labels = intArrayOf(R.string.support_why, R.string.support_history, R.string.support_profiles,
            R.string.support_recover, R.string.support_mark, R.string.support_marked, R.string.support_compatibility,
            R.string.support_wake, R.string.support_backup, R.string.support_quick)
        show(dialog(R.string.support_tools).setItems(labels.map(c::getString).toTypedArray()) { _, index ->
            when(index) {
                0 -> explain()
                1 -> history()
                2 -> DriverProfilesDialog(fragment).show()
                3 -> recover()
                4 -> mark()
                5 -> marked()
                6 -> compatibility()
                7 -> show(dialog(R.string.support_wake).setMessage(R.string.support_wake_note)
                    .setMultiChoiceItems(arrayOf(c.getString(R.string.support_wake)), booleanArrayOf(prefs.getBoolean("support-wake-picture", false))) { _, _, checked -> prefs.edit().putBoolean("support-wake-picture", checked).apply() }
                    .setPositiveButton(android.R.string.ok, null))
                8 -> fragment.findNavController().navigate(R.id.settingsFragment)
                9 -> chooseQuickAction()
            }
        }.setNegativeButton(android.R.string.cancel, null))
    }
    fun runQuickAction() { when(prefs.getInt("support-quick-action", 0)) { 1 -> recover(); 2 -> HudMapLauncher.open(fragment.requireActivity(), fragment.viewLifecycleOwner.lifecycleScope); 3 -> ImageDiagnosticsDialog.show(fragment); else -> mark() } }
    private fun chooseQuickAction() {
        val choices = intArrayOf(R.string.support_mark, R.string.support_recover, R.string.home_hud_map, R.string.image_diagnostics_title)
        show(dialog(R.string.support_quick).setSingleChoiceItems(choices.map(c::getString).toTypedArray(), prefs.getInt("support-quick-action", 0)) { d, which -> prefs.edit().putInt("support-quick-action", which).apply(); d.dismiss() }.setNegativeButton(android.R.string.cancel, null))
    }
    private fun recover() { com.lenid.zoh.utils.ToastUtils.showToast(c, if(PictureRecovery.request(c)) R.string.support_recover_requested else R.string.support_recover_unavailable, Toast.LENGTH_LONG, force = true) }
    private fun explain() {
        val app = App.provide(c)
        val s = app.settings
        val relevant = ConnectionIssueBannerPolicy.relevantNow(s.wifiConnectionMode.id, s.nativeApStrategy, s.showsWifi())
        val text = buildString {
            appendLine(DiagnosticSnapshot.status(c))
            if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(c, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                appendLine(c.getString(R.string.support_bt_permission))
            if (!app.commManager.isConnected) {
                val applied = ConnectionIssueBannerPolicy.remedyApplied("", "", s.staticBSSID, s.staticP2pBSSID)
                ConnectionIssues.standing(c).filter { it.issue in relevant && it.issue !in applied }.take(4).forEach {
                    appendLine(c.getString(ConnectionIssueText.label(it.issue)))
                    appendLine(DateFormat.getDateTimeInstance().format(Date(it.raisedAtEpochMs)))
                }
            }
            append(c.getString(R.string.support_observed_only))
        }
        message(R.string.support_why, text)
    }
    private fun history() {
        val text = ConnectionJournal.entries(c).joinToString("\n") { (time, event) -> DateFormat.getDateTimeInstance().format(Date(time)) + " · " + label(event) }.ifBlank { c.getString(R.string.support_history_empty) }
        show(dialog(R.string.support_history).setMessage(text).setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.support_clear) { _, _ -> ConnectionJournal.clear(c) })
    }
    private fun label(event: String): String = when(event) {
        "AA:Connecting", "AA:Connected", "AA:StartingTransport" -> "Android Auto: " + c.getString(R.string.stage_connecting)
        "AA:TransportStarted", "AA:HandshakeComplete" -> "Android Auto: " + c.getString(R.string.support_connected)
        "AA:Disconnected", "CP:disconnected" -> c.getString(R.string.support_disconnected)
        "CP:connected" -> "CarPlay: " + c.getString(R.string.support_connected)
        "dongle:active" -> "AutoKit: " + c.getString(R.string.support_connected)
        "dongle:inactive" -> "AutoKit: " + c.getString(R.string.support_disconnected)
        "picture:requested" -> c.getString(R.string.support_recover_requested)
        "screen:awake" -> c.getString(R.string.support_awake)
        "problem:marked" -> c.getString(R.string.support_mark)
        else -> c.getString(R.string.connection_summary_error)
    }
    private fun mark() {
        val root = fragment.view ?: return
        val text = DiagnosticSnapshot.snapshot(c, root.width, root.height) + "\n" + ConnectionJournal.entries(c).take(15).joinToString("\n") { (time, event) -> "$time ${label(event)}" }
        val file = java.io.File(c.filesDir, "problem-mark.txt")
        runCatching { file.writeText(CrashJournal.captureProblem(c, text)) }
            .onSuccess { ConnectionJournal.record(c, "problem:marked"); com.lenid.zoh.utils.ToastUtils.showToast(c, R.string.support_mark_saved, Toast.LENGTH_SHORT, force = true) }
            .onFailure { com.lenid.zoh.utils.ToastUtils.showToast(c, R.string.problem_report_failed, Toast.LENGTH_LONG, force = true) }
    }
    private fun marked() {
        val text = runCatching { java.io.File(c.filesDir, "problem-mark.txt").readText().take(32000) }.getOrNull()
        if(text == null) return message(R.string.support_marked, c.getString(R.string.support_history_empty))
        val context = c
        show(dialog(R.string.support_marked).setMessage(text + "\n\n" + context.getString(if(CrashJournal.sendReports(context) && CrashJournal.uploadConfigured()) R.string.problem_report_send_notice else R.string.problem_report_local_notice)).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.problem_report_save) { _, _ -> CrashJournal.reportCapturedProblem(context, text) { ok -> com.lenid.zoh.utils.ToastUtils.showToast(context, if(ok) R.string.problem_report_saved else R.string.problem_report_failed, Toast.LENGTH_LONG, force = true) } })
    }
    private fun compatibility() { message(R.string.support_compatibility, CompanionCompatibility.report(c)) }
}
