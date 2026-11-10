package com.lenid.zoh.utils

import android.R
import android.content.Context
import android.content.DialogInterface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object DialogUtils {
    /** Wraps a dialog body so it can scroll once the keyboard leaves it too little height. */
    private fun scrollable(container: LinearLayout): ScrollView =
        ScrollView(container.context).apply {
            isFillViewport = true
            addView(container)
        }

    /**
     * On a short, wide head-unit screen the keyboard can cover the dialog's buttons, leaving no
     * way to save or close it. Enter on the last field presses OK and on earlier fields moves on,
     * a tap anywhere in the dialog body outside a field closes the keyboard, and the window
     * resizes above the keyboard so the buttons stay on screen when there is room.
     */
    private fun fitKeyboard(dialog: AlertDialog, container: View, fields: List<EditText>) {
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        fields.forEachIndexed { index, field ->
            field.setSingleLine(true)
            field.imeOptions = TextDialogKeyPolicy.imeAction(isLastField = index == fields.lastIndex)
            field.setOnEditorActionListener { _, actionId, event ->
                if (!TextDialogKeyPolicy.isSubmit(actionId, event?.keyCode, event?.action)) {
                    return@setOnEditorActionListener false
                }
                if (index == fields.lastIndex) {
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.performClick()
                } else {
                    fields[index + 1].requestFocus()
                }
                true
            }
        }
        // The body takes focus from the field only for the tap that hides the keyboard, and lets
        // it go again afterwards, so it never becomes an invisible stop for D-pad or rotary keys.
        container.setOnFocusChangeListener { view, hasFocus ->
            if (!hasFocus) view.isFocusable = false
        }
        // On every view but the fields: a clickable child such as the checkbox keeps its taps,
        // so a listener on the container alone would never see them. Returning false leaves the
        // tap to do what it did before.
        val hideOnTouch = View.OnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                container.isFocusableInTouchMode = true
                container.requestFocus()
                val imm = container.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(container.windowToken, 0)
            }
            false
        }
        fun attach(view: View) {
            if (view is EditText) return
            view.setOnTouchListener(hideOnTouch)
            if (view is ViewGroup) for (i in 0 until view.childCount) attach(view.getChildAt(i))
        }
        attach(container)
    }

    fun showTextInputDialog(
        context: Context,
        titleResId: Int,
        currentValue: String?,
        onResult: (String) -> Unit,
    ) {
        showTextInputDialogWithMessage(context, titleResId, null, currentValue, onResult)
    }

    fun showTextInputDialogWithMessage(
        context: Context,
        titleResId: Int,
        messageResId: Int?,
        currentValue: String?,
        onResult: (String) -> Unit,
    ) {
        showTextInputDialogWithMessage(context, titleResId, messageResId, currentValue, null) { value, _ ->
            onResult(value)
        }
    }

    /** A checkbox under the field. Its value goes back with OK or Reset; Cancel drops both. */
    data class Checkbox(val labelResId: Int, val descriptionResId: Int?, val checked: Boolean)

    fun showTextInputDialogWithMessage(
        context: Context,
        titleResId: Int,
        messageResId: Int?,
        currentValue: String?,
        checkbox: Checkbox?,
        onResult: (String, Boolean) -> Unit,
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }

        if (messageResId != null) {
            val messageText = TextView(context).apply {
                setText(messageResId)
                val textColorAttr = TypedValue()
                context.theme.resolveAttribute(
                    R.attr.textColorSecondary,
                    textColorAttr,
                    true,
                )
                setTextColor(ContextCompat.getColor(context, textColorAttr.resourceId))
                textSize = 13f
                setPadding(0, 0, 0, 24)
            }
            container.addView(messageText)
        }

        val editText = EditText(context).apply {
            setText(currentValue)
            setSelection(text.length)
        }
        container.addView(editText)

        val checkView = checkbox?.let {
            CheckBox(context).apply {
                setText(it.labelResId)
                isChecked = it.checked
                setPadding(paddingLeft, paddingTop, paddingRight, 0)
            }.also { view -> container.addView(view) }
        }
        checkbox?.descriptionResId?.let { resId ->
            container.addView(TextView(context).apply {
                setText(resId)
                val textColorAttr = TypedValue()
                context.theme.resolveAttribute(R.attr.textColorSecondary, textColorAttr, true)
                setTextColor(ContextCompat.getColor(context, textColorAttr.resourceId))
                textSize = 13f
                setPadding(0, 0, 0, 16)
            })
        }
        val checked = { checkView?.isChecked ?: false }

        val dialog = MaterialAlertDialogBuilder(context, com.lenid.zoh.R.style.DarkAlertDialog)
            .setTitle(titleResId)
            .setView(scrollable(container))
            .setPositiveButton(R.string.ok) { _, _ ->
                val value = editText.text.toString().trim()
                onResult(value, checked())
            }
            .setNeutralButton(com.lenid.zoh.R.string.reset) { _, _ ->
                onResult("", checked())
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(editText.windowToken, 0)
            }
            .create()
        fitKeyboard(dialog, container, listOf(editText))

        dialog.window?.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
        )
        dialog.show()
        if (Settings(context).hudMirroring) {
            val root = dialog.window?.findViewById<android.view.View>(android.R.id.content) ?: dialog.window?.decorView
            root?.scaleX = -1.0f
        }
        editText.requestFocus()
    }

    /**
     * Two fields edited and applied as one value. The neutral button hands back a pair of empty
     * strings, the same convention the single-field dialog above uses for "let the app choose";
     * [onNeutral] is for a caller that has to tell that apart from an empty OK.
     */
    fun showTwoFieldDialog(
        context: Context,
        titleResId: Int,
        messageResId: Int,
        firstHintResId: Int,
        firstValue: String?,
        secondHintResId: Int,
        secondValue: String?,
        neutralResId: Int = com.lenid.zoh.R.string.reset,
        onNeutral: (() -> Unit)? = null,
        onResult: (String, String) -> Unit,
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }

        val messageText = TextView(context).apply {
            setText(messageResId)
            val textColorAttr = TypedValue()
            context.theme.resolveAttribute(R.attr.textColorSecondary, textColorAttr, true)
            setTextColor(ContextCompat.getColor(context, textColorAttr.resourceId))
            textSize = 13f
            setPadding(0, 0, 0, 24)
        }
        container.addView(messageText)

        val first = EditText(context).apply {
            setHint(firstHintResId)
            setText(firstValue)
            setSelection(text.length)
        }
        container.addView(first)

        val second = EditText(context).apply {
            setHint(secondHintResId)
            setText(secondValue)
        }
        container.addView(second)

        val dialog = MaterialAlertDialogBuilder(context, com.lenid.zoh.R.style.DarkAlertDialog)
            .setTitle(titleResId)
            .setView(scrollable(container))
            .setPositiveButton(R.string.ok) { _, _ ->
                onResult(first.text.toString().trim(), second.text.toString().trim())
            }
            .setNeutralButton(neutralResId) { _, _ -> onNeutral?.invoke() ?: onResult("", "") }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(first.windowToken, 0)
            }
            .create()
        fitKeyboard(dialog, container, listOf(first, second))

        dialog.window?.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
        )
        dialog.show()
        if (Settings(context).hudMirroring) {
            val root = dialog.window?.findViewById<android.view.View>(android.R.id.content) ?: dialog.window?.decorView
            root?.scaleX = -1.0f
        }
        first.requestFocus()
    }
}
