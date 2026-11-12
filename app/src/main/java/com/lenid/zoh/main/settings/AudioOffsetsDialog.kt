package com.lenid.zoh.main.settings

import android.view.LayoutInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lenid.zoh.R

/** Edits local slider values; the caller owns pending settings and persistence. */
internal object AudioOffsetsDialog {
    fun show(
        context: android.content.Context,
        mediaOffset: Int?,
        guidanceOffset: Int?,
        systemOffset: Int?,
        onConfirm: (Int, Int, Int) -> Unit,
    ) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_audio_offsets, null)

        val seekMedia = dialogView.findViewById<android.widget.SeekBar>(R.id.seek_media)
        val seekGuidance = dialogView.findViewById<android.widget.SeekBar>(R.id.seek_guidance)
        val seekSystem = dialogView.findViewById<android.widget.SeekBar>(R.id.seek_system)

        val textMedia = dialogView.findViewById<android.widget.TextView>(R.id.text_media_val)
        val textGuidance = dialogView.findViewById<android.widget.TextView>(R.id.text_guidance_val)
        val textSystem = dialogView.findViewById<android.widget.TextView>(R.id.text_system_val)

        // Mapping: 0 to 100 on SeekBar -> 0% to 200% Gain. Default is 50 (100% Gain, 0 Offset)
        // Offset = (seekValue - 50) * 2
        // seekValue = (offset / 2) + 50

        seekMedia.progress = ((mediaOffset ?: 0) / 2) + 50
        seekGuidance.progress = ((guidanceOffset ?: 0) / 2) + 50
        seekSystem.progress = ((systemOffset ?: 0) / 2) + 50

        val updateLabels = {
            textMedia.text = "${(seekMedia.progress * 2)}%"
            textGuidance.text = "${(seekGuidance.progress * 2)}%"
            textSystem.text = "${(seekSystem.progress * 2)}%"
        }
        updateLabels()

        val listener = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        }

        seekMedia.setOnSeekBarChangeListener(listener)
        seekGuidance.setOnSeekBarChangeListener(listener)
        seekSystem.setOnSeekBarChangeListener(listener)

        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(R.string.audio_volume_offset)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                onConfirm(
                    (seekMedia.progress - 50) * 2,
                    (seekGuidance.progress - 50) * 2,
                    (seekSystem.progress - 50) * 2,
                )
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
