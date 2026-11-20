package com.lenid.zoh.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lenid.zoh.App
import com.lenid.zoh.R
import com.lenid.zoh.carplay.DirectCarPlayService
import com.lenid.zoh.dongle.DongleService
import com.lenid.zoh.utils.CrashJournal
import com.lenid.zoh.utils.ProjectionDiagnostics
import com.lenid.zoh.utils.ToastUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Observes existing telemetry; never owns a transport, surface, decoder or window flags. */
internal object ImageDiagnosticsDialog {
    fun show(fragment: Fragment) {
        val context = fragment.requireContext()
        val app = App.provide(context)
        val padding = (20 * context.resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding)
        }
        val details = TextView(context).apply { textSize = 16f; setTextIsSelectable(true) }
        val chart = FpsChart(context)
        val progress = TextView(context).apply { textSize = 16f }
        val start = MaterialButton(context).apply { setText(R.string.image_diagnostics_capture) }
        val report = MaterialButton(context).apply { setText(R.string.problem_report_title) }
        content.addView(details)
        content.addView(chart, LinearLayout.LayoutParams(-1, (140 * context.resources.displayMetrics.density).toInt()))
        content.addView(TextView(context).apply { setText(R.string.image_diagnostics_help); textSize = 14f })
        content.addView(progress)
        content.addView(start)
        content.addView(report)
        fun now() = System.nanoTime() / 1_000_000
        start.setOnClickListener { ProjectionDiagnostics.startCapture(now()) }
        var textSnapshot = ""
        var saving = false
        val dialog = MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(R.string.image_diagnostics_title)
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton(android.R.string.ok, null).create()
        var confirmation: androidx.appcompat.app.AlertDialog? = null
        report.setOnClickListener {
            if (saving || confirmation?.isShowing == true) return@setOnClickListener
            val frozen = textSnapshot + "\n" + ProjectionDiagnostics.captured().joinToString("\n")
            confirmation = MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
                .setTitle(R.string.problem_report_title)
                .setMessage(R.string.image_diagnostics_report_notice)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.problem_report_save) { _, _ ->
                    saving = true
                    report.isEnabled = false
                    CrashJournal.reportProblem(context, frozen, "image-diagnostics") { ok ->
                        saving = false
                        report.isEnabled = true
                        ToastUtils.showToast(context.applicationContext, if (ok) R.string.problem_report_saved
                            else R.string.problem_report_failed, Toast.LENGTH_LONG, force = true)
                    }
                }.show()
        }
        dialog.show()
        val job = fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                while (dialog.isShowing && fragment.viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    val time = now()
                    val connected = app.commManager.isConnected
                    val profile = ProjectionDiagnostics.profile.takeIf { connected }
                    val video = ProjectionDiagnostics.video.takeIf { profile != null && it != null && it.atMs >= profile.atMs }
                    val samples = if (profile != null) ProjectionDiagnostics.samples(time) else emptyList()
                    textSnapshot = buildString {
                        appendLine(context.getString(R.string.diagnostics_selected_fps, app.settings.fpsLimit))
                        appendLine(context.getString(R.string.diagnostics_announced_fps, profile?.fps?.toString() ?: "—"))
                        appendLine(context.getString(R.string.diagnostics_cap, if (profile == null) "—" else context.getString(
                            if (!profile.reduced) R.string.diagnostics_cap_no else if (profile.recoveryCap)
                                R.string.diagnostics_cap_recovery else R.string.diagnostics_cap_yes)))
                        if (video == null) appendLine(context.getString(R.string.diagnostics_no_sample)) else {
                            appendLine(context.getString(R.string.image_diagnostics_decoder, video.codec))
                            appendLine(context.getString(R.string.diagnostics_video_rates, video.fedFps, video.renderedFps))
                            appendLine(context.getString(R.string.diagnostics_sample_age, (time - video.atMs).coerceAtLeast(0) / 1000))
                            appendLine(context.getString(R.string.image_diagnostics_dropped, video.dropped?.toString() ?: "—"))
                        }
                        appendLine(context.getString(R.string.diagnostics_fps_note))
                        DirectCarPlayService.diagnosticStatus.takeIf { it.isNotBlank() }?.let { appendLine("CarPlay: $it") }
                        DongleService.state.value.takeIf { it.active }?.let { appendLine("AutoKit: ${it.status}") }
                        val captured = ProjectionDiagnostics.captured()
                        if (captured.isNotEmpty()) appendLine(context.getString(R.string.image_diagnostics_capture_result,
                            captured.size, captured.minOf { it.renderedFps }, captured.maxOf { it.renderedFps }))
                    }
                    details.text = textSnapshot
                    chart.update(samples, time, details.currentTextColor)
                    val remaining = ProjectionDiagnostics.captureRemaining(time)
                    progress.text = when {
                        remaining == null -> ""
                        remaining > 0 -> context.getString(R.string.image_diagnostics_running, (remaining + 999) / 1000)
                        else -> context.getString(R.string.image_diagnostics_finished, ProjectionDiagnostics.captured().size)
                    }
                    start.isEnabled = connected && (remaining == null || remaining == 0L)
                    delay(1000)
                }
            } finally { confirmation?.dismiss(); dialog.dismiss() }
        }
        dialog.setOnDismissListener { confirmation?.dismiss(); job.cancel() }
    }

    private class FpsChart(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var samples = emptyList<ProjectionDiagnostics.Video>()
        private var time = 0L
        fun update(values: List<ProjectionDiagnostics.Video>, now: Long, color: Int) {
            samples = values; time = now; paint.color = color; invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val left = 44f * density
            val top = 22f * density
            val bottom = height - 22f * density
            val maximum = maxOf(60, samples.maxOfOrNull { it.renderedFps } ?: 60)
            paint.textSize = 12f * density
            paint.strokeWidth = density
            canvas.drawText("$maximum FPS", 0f, 14f * density, paint)
            canvas.drawText("0", 0f, bottom, paint)
            canvas.drawLine(left, bottom, width.toFloat(), bottom, paint)
            canvas.drawText("−60 s", left, height.toFloat() - 2f, paint)
            canvas.drawText("0 s", width - 24f * density, height.toFloat() - 2f, paint)
            // Separate interval bars deliberately leave missing measurements blank.
            paint.strokeWidth = 3f * density
            samples.forEach { sample ->
                val x2 = left + ((sample.atMs - time + 60_000).coerceIn(0, 60_000) / 60_000f) * (width - left)
                val x1 = left + ((sample.atMs - sample.intervalMs - time + 60_000).coerceIn(0, 60_000) / 60_000f) * (width - left)
                val y = bottom - sample.renderedFps.toFloat() / maximum * (bottom - top)
                canvas.drawLine(x1, y, x2, y, paint)
            }
        }
    }
}
