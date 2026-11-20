package com.lenid.zoh.main

import android.app.Dialog
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lenid.zoh.App
import com.lenid.zoh.R
import com.lenid.zoh.carplay.CarPlayDiagnostics
import com.lenid.zoh.carplay.DirectCarPlayService
import com.lenid.zoh.connection.CommManager
import com.lenid.zoh.connection.ConnectionStageTracker
import com.lenid.zoh.dongle.DongleService
import com.lenid.zoh.utils.CrashJournal
import com.lenid.zoh.utils.ProjectionDiagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Presentation only. Sampling runs only while the home screen is STARTED. */
internal object HomeDiagnostics {
    fun bind(fragment: Fragment) {
        fragment.view?.let { PictureTrialNotice.show(it) }
        val tools = SupportTools(fragment)
        fragment.view?.findViewById<View>(R.id.home_support_tools)?.setOnClickListener { tools.open() }
        fragment.view?.findViewById<View>(R.id.home_quick_action)?.setOnClickListener { tools.runQuickAction() }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val context = fragment.requireContext().applicationContext
            val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CompanionCompatibility.report(context) }
            context.getSharedPreferences("post_update_check", 0).edit()
                .putInt("version", com.lenid.zoh.BuildConfig.VERSION_CODE).putString("report", report).apply()
        }
        var dialog: Dialog? = null
        var saving = false
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    while (true) {
                        val root = fragment.view ?: break
                        root.findViewById<TextView>(R.id.home_connection_summary)?.text = DiagnosticSnapshot.status(fragment.requireContext())
                        root.findViewById<View>(R.id.home_report_problem)?.apply {
                            isEnabled = !saving
                            setOnClickListener {
                                if (saving || dialog?.isShowing == true) return@setOnClickListener
                                val context = fragment.context ?: return@setOnClickListener
                                // Freeze the report before opening any dialog or changing focus.
                                val snapshot = DiagnosticSnapshot.snapshot(context, root.width, root.height)
                                val sending = CrashJournal.sendReports(context) && CrashJournal.uploadConfigured()
                                val note = android.widget.EditText(context).apply {
                                    setHint(R.string.problem_report_note)
                                    minLines = 2
                                    maxLines = 3
                                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                    filters = arrayOf(android.text.InputFilter.LengthFilter(500))
                                }
                                dialog = MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
                                    .setTitle(R.string.problem_report_title)
                                    .setMessage(snapshot + "\n\n" + context.getString(if (sending)
                                        R.string.problem_report_send_notice else R.string.problem_report_local_notice))
                                    .setView(note)
                                    .setPositiveButton(R.string.problem_report_save) { _, _ ->
                                        saving = true
                                        root.findViewById<View>(R.id.home_report_problem)?.isEnabled = false
                                        CrashJournal.reportProblem(context, snapshot + "\nUser description: " + note.text.toString(), "projection") { ok ->
                                            saving = false
                                            // This is a local-save receipt, not a claim of server delivery.
                                            com.lenid.zoh.utils.ToastUtils.showToast(context.applicationContext, if (ok)
                                                R.string.problem_report_saved else R.string.problem_report_failed,
                                                Toast.LENGTH_LONG, force = true)
                                        }
                                    }
                                    .setNegativeButton(android.R.string.cancel, null).show()
                            }
                        }
                        delay(1000)
                    }
                } finally { dialog?.dismiss(); dialog = null }
            }
        }
    }

}
