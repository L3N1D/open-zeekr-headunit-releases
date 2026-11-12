package com.lenid.zoh.main.settings

import android.content.Context
import android.content.DialogInterface
import android.text.InputType
import android.widget.EditText
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lenid.zoh.R

/** Presentation only; callers own validation, pending values, persistence and dismissal order. */
internal object SettingsDialogs {
    fun showSingleChoice(
        context: Context,
        @StringRes title: Int,
        labels: Array<String>,
        selectedIndex: Int,
        @StringRes cancelLabel: Int? = null,
        onSelect: (DialogInterface, Int) -> Unit,
    ) {
        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(title)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which -> onSelect(dialog, which) }
            .apply { if (cancelLabel != null) setNegativeButton(cancelLabel, null) }
            .show()
    }

    /** Preserve the platform styling used by the audio queue dialogs. */
    fun showPlatformSingleChoice(
        context: Context,
        @StringRes title: Int,
        labels: Array<String>,
        selectedIndex: Int,
        @StringRes cancelLabel: Int? = null,
        onSelect: (DialogInterface, Int) -> Unit,
    ) {
        android.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which -> onSelect(dialog, which) }
            .apply { if (cancelLabel != null) setNegativeButton(cancelLabel, null) }
            .show()
    }

    fun showNumericInputDialog(
        context: Context,
        title: String,
        message: String?,
        initialValue: Int,
        onConfirm: (Int) -> Unit
    ) {
        val editView = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (initialValue == 0 && title.contains("DPI", true)) "" else initialValue.toString())
        }

        val container = inputContainer(context, editView)

        val dialog = MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(title)
            .apply { if (message != null) setMessage(message) }
            .setView(container)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val newVal = (editView.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                onConfirm(newVal)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(editView.windowToken, 0)
            }
            .create()

        dialog.window?.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        dialog.show()
        editView.requestFocus()
    }

    fun showTextInputDialog(
        context: Context,
        titleResId: Int,
        initialValue: String,
        onConfirm: (String) -> Unit
    ) {
        val editView = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(initialValue)
        }

        val container = inputContainer(context, editView)

        val dialog = MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(titleResId)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                onConfirm(editView.text.toString())
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(editView.windowToken, 0)
            }
            .create()

        dialog.window?.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        dialog.show()
        editView.requestFocus()
    }

    private fun inputContainer(context: Context, editView: EditText): android.widget.FrameLayout {
        val container = android.widget.FrameLayout(context)
        val params = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val margin = (24 * context.resources.displayMetrics.density).toInt()
        params.setMargins(margin, 8, margin, 8)
        container.addView(editView, params)

        return container
    }
}
