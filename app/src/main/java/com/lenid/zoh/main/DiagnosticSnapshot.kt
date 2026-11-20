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

/** Read-only snapshot, independent of dialogs and view lifecycle. */
internal object DiagnosticSnapshot {
    fun status(context: android.content.Context): String {
        val app = App.provide(context)
        val aa = app.commManager.connectionState.value
        if (aa is CommManager.ConnectionState.TransportStarted || aa is CommManager.ConnectionState.HandshakeComplete)
            return context.getString(if (app.videoDecoder.framesRenderedThisSession > 0)
                R.string.connection_summary_video else R.string.connection_summary_wait_video)
        if (aa is CommManager.ConnectionState.Connecting || aa is CommManager.ConnectionState.Connected ||
            aa is CommManager.ConnectionState.StartingTransport)
            return "Android Auto: " + context.getString(ConnectionStageTracker.stage.value?.label
                ?: R.string.stage_connecting)
        val cp = DirectCarPlayService.diagnosticStatus
        if (cp.isNotBlank() && cp != context.getString(R.string.carplay_ready)) return "CarPlay: $cp"
        val dongle = DongleService.state.value
        if (dongle.active) return "AutoKit: ${dongle.status}"
        if (aa is CommManager.ConnectionState.Error)
            return context.getString(R.string.connection_summary_error) + ": " + CrashJournal.mask(aa.message).take(160)
        ConnectionStageTracker.stage.value?.let { return "Android Auto: ${context.getString(it.label)}" }
        return context.getString(R.string.connection_summary_idle)
    }

    fun snapshot(context: android.content.Context, width: Int, height: Int): String {
        val app = App.provide(context)
        val now = System.nanoTime() / 1_000_000
        val connected = app.commManager.isConnected
        val profile = ProjectionDiagnostics.profile.takeIf { connected }
        val video = ProjectionDiagnostics.video.takeIf { connected && profile != null && it != null && it.atMs >= profile.atMs }
        return buildString {
            appendLine(status(context))
            appendLine(context.getString(R.string.diagnostics_window, width, height))
            appendLine("AA video=${app.videoDecoder.videoWidth}x${app.videoDecoder.videoHeight}; softwareHEVC=${app.videoDecoder.usingBundledSoftwareHevc}")
            appendLine(context.getString(R.string.diagnostics_selected_fps, app.settings.fpsLimit))
            appendLine(context.getString(R.string.diagnostics_announced_fps, profile?.fps?.toString() ?: "—"))
            appendLine(context.getString(R.string.diagnostics_cap,
                if (profile == null) "—" else context.getString(if (profile.reduced)
                    (if (profile.recoveryCap) R.string.diagnostics_cap_recovery else R.string.diagnostics_cap_yes)
                    else R.string.diagnostics_cap_no)))
            appendLine("narrowBandProfileCap=${app.settings.narrowBandProfileCap}; videoProfileStarvationCap=${app.settings.videoProfileStarvationCap}")
            if (video != null) {
                appendLine("Codec: ${video.codec}")
                appendLine(context.getString(R.string.diagnostics_video_rates, video.fedFps, video.renderedFps))
                appendLine(context.getString(R.string.diagnostics_sample_age, (now - video.atMs).coerceAtLeast(0) / 1000))
                appendLine("intervalMs=${video.intervalMs}; presentedFps=${video.presentedFps ?: "—"}")
            } else appendLine(context.getString(R.string.diagnostics_no_sample))
            appendLine(context.getString(R.string.diagnostics_fps_note))
            appendLine("AA state=${app.commManager.connectionState.value.javaClass.simpleName}; stage=${ConnectionStageTracker.stage.value?.name ?: "—"}")
            appendLine("AA framesRendered=${app.videoDecoder.framesRenderedThisSession}; lastInputMs=${app.videoDecoder.lastInputBytesReceivedMs}; lastRenderedMs=${app.videoDecoder.lastFrameRenderedMs}")
            appendLine(CarPlayDiagnostics.report())
        }
    }
}
