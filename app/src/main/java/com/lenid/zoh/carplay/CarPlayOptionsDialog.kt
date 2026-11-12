package com.lenid.zoh.carplay

import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.lenid.zoh.R

/** Separate settings entry: never reconnects a session or changes the projection card. */
internal object CarPlayOptionsDialog {
    fun show(context: Context, export: () -> Unit) {
        val pad = (24 * context.resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, pad / 2)
        }
        fun toggle(title: Int, summary: Int, key: String, checked: Boolean) {
            content.addView(MaterialSwitch(context).apply {
                setText(title); isChecked = checked
                setOnCheckedChangeListener { _, value -> CarPlayOptions.set(context, key, value) }
            }, LinearLayout.LayoutParams(-1, -2))
            content.addView(TextView(context).apply { setText(summary); setPadding(0, 0, 0, pad) })
        }
        toggle(R.string.carplay_ultra_title, R.string.carplay_ultra_summary, "ultra", CarPlayOptions.ultra(context))
        toggle(R.string.carplay_trace_title, R.string.carplay_trace_summary, "trace", CarPlayOptions.trace(context))
        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog).setTitle(R.string.carplay_options_title)
            .setView(ScrollView(context).apply { addView(content) })
            .setNeutralButton(R.string.carplay_export_report) { _, _ -> export() }
            .setPositiveButton(android.R.string.ok, null).show()
    }
}
