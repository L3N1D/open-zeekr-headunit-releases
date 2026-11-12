package com.lenid.zoh.carplay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.google.android.material.button.MaterialButton
import com.lenid.zoh.R
import com.lenid.zoh.utils.ConnectionPanel

/** Connection controls only. No service, permission, surface or transport ownership. */
internal class CarPlayConnectionView(
    private val context: Context,
    private val panel: ConnectionPanel,
    onConnect: (Boolean) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    private val controls: LinearLayout
    private val status: TextView
    private val stopButton: MaterialButton
    private val footer: LinearLayout
    private val projectionBar: LinearLayout

    init {
        controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(panel.option(R.string.carplay_usb, R.string.carplay_usb_hint, R.drawable.ic_usb) {
            onConnect(false)
        }, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(8) })
        controls.addView(panel.option(R.string.carplay_wireless, R.string.carplay_wifi_hint, R.drawable.ic_wifi) {
            onConnect(true)
        }, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(8) })
        panel.body.addView(controls, LinearLayout.LayoutParams(-1, dp(208)))
        status = panel.text(R.string.carplay_ready, 18f, true).apply {
            setPadding(dp(4), dp(24), dp(4), dp(16))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        panel.body.addView(status)
        footer = LinearLayout(context).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        stopButton = button(R.string.carplay_stop) { onStop() }
        footer.addView(stopButton)
        panel.body.addView(footer)
        projectionBar = LinearLayout(context).apply {
            visibility = View.GONE
            addView(button(R.string.back) { onClose() })
            addView(button(R.string.carplay_stop) { onStop() })
        }
        panel.addOverlay(projectionBar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
    }

    fun setStatus(@StringRes message: Int) = status.setText(message)
    fun setStatus(message: CharSequence) { status.text = message }

    fun render(connected: Boolean, running: Boolean) {
        panel.setBackgroundVisible(!connected)
        panel.setCardVisible(!connected)
        projectionBar.visibility = if (connected) View.VISIBLE else View.GONE
        footer.visibility = if (running) View.VISIBLE else View.GONE
        for (i in 0 until controls.childCount) {
            controls.getChildAt(i).isEnabled = !running
            controls.getChildAt(i).alpha = if (running) 0.5f else 1f
        }
    }

    fun adaptOptions(screenWidthDp: Int) {
        val compact = screenWidthDp < 600
        controls.orientation = if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        controls.layoutParams = (controls.layoutParams as LinearLayout.LayoutParams).apply {
            height = if (compact) -2 else dp(208)
        }
        for (i in 0 until controls.childCount) {
            controls.getChildAt(i).layoutParams = if (compact)
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            else LinearLayout.LayoutParams(0, -1, 1f).apply {
                if (i == 0) marginEnd = dp(8) else marginStart = dp(8)
            }
        }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun button(text: Int, click: () -> Unit) = MaterialButton(context).apply {
        setText(text)
        setOnClickListener { click() }
    }
}
