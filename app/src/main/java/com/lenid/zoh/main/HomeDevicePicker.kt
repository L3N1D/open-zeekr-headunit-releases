package com.lenid.zoh.main

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.*
import com.lenid.zoh.R
import com.lenid.zoh.utils.HomeUiHelper

/** Home-only presentation. Transport, projection windows and panel animations stay with their owners. */
internal class HomeDevicePicker(context: Context) : LinearLayout(context) {
    var onRefresh: () -> Unit = {}
    var onConnect: (HomeDevice) -> Unit = {}
    var onCancel: () -> Unit = {}
    private var devices = emptyList<HomeDevice>()
    var selectedKey: String? = null
    private var palette: HomeUiHelper.Palette? = null
    private val header = LinearLayout(context)
    private val title = label(R.string.home_devices_title, 28f, true)
    private val subtitle = label(R.string.home_devices_subtitle, 16f)
    private val refresh = ImageButton(context).apply {
        setImageResource(R.drawable.ic_refresh_white)
        contentDescription = context.getString(R.string.home_devices_refresh)
        setOnClickListener { onRefresh() }
    }
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val hint = label(R.string.home_devices_hint, 14f)
    private val footer = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val connect = label(R.string.home_device_connect, 20f, true).apply {
        gravity = Gravity.CENTER; minHeight = dp(52); setPadding(dp(20), dp(8), dp(20), dp(8))
        setOnClickListener { devices.firstOrNull { it.key == selectedKey }?.let(onConnect) }
    }
    private val cancel = label(R.string.cancel, 16f).apply {
        gravity = Gravity.CENTER; minHeight = dp(48); setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { onCancel() }
    }
    private var bluetoothUnavailable = false
    init {
        orientation = VERTICAL
        if (Build.VERSION.SDK_INT >= 21) clipToOutline = true
        header.orientation = HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(24), dp(16), dp(16), dp(16))
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(title); addView(subtitle)
        }
        header.addView(texts, LayoutParams(0, -2, 1f))
        header.addView(refresh, LayoutParams(dp(52), dp(52)))
        addView(header, LayoutParams(-1, -2))
        list.setPadding(dp(16), dp(12), dp(16), dp(8))
        addView(ScrollView(context).apply { isFillViewport = true; addView(list) }, LayoutParams(-1, 0, 1f))
        hint.setPadding(dp(24), dp(4), dp(24), dp(8))
        addView(hint, LayoutParams(-1, -2))
        footer.setPadding(dp(24), dp(4), dp(24), dp(16))
        footer.addView(connect, LayoutParams(0, -2, 1f))
        footer.addView(cancel, LayoutParams(-2, -2))
        addView(footer, LayoutParams(-1, -2))
    }

    fun render(items: List<HomeDevice>, unavailable: Boolean, colors: HomeUiHelper.Palette,
               status: CharSequence?, canCancel: Boolean, refreshing: Boolean) {
        val rebuild = items != devices || colors !== palette || unavailable != bluetoothUnavailable
        devices = items
        selectedKey = HomeDeviceSelection.retain(selectedKey, items)
        bluetoothUnavailable = unavailable
        palette = colors
        background = shape(colors.panel, 24f)
        header.background = shape(colors.wifi, 0f)
        title.setTextColor(if (colors.isDark) colors.text else Color.WHITE)
        subtitle.setTextColor(if (colors.isDark) colors.text else Color.WHITE)
        subtitle.text = status?.takeIf { it.isNotBlank() } ?: context.getString(R.string.home_devices_subtitle)
        refresh.setColorFilter(if (colors.isDark) colors.text else Color.WHITE)
        refresh.background = shape(Color.TRANSPARENT, 26f)
        refresh.isEnabled = !refreshing
        refresh.alpha = if (refreshing) .45f else 1f
        hint.setTextColor(colors.textMuted)
        hint.setText(if (unavailable) R.string.home_devices_bluetooth_unavailable else R.string.home_devices_hint)
        cancel.visibility = if (canCancel) VISIBLE else GONE
        cancel.setTextColor(colors.text)
        if (rebuild) renderRows()
        updateConnect()
    }

    private fun renderRows() {
        val p = palette ?: return
        list.removeAllViews()
        if (devices.isEmpty()) {
            list.addView(label(R.string.home_devices_empty, 20f).apply {
                setTextColor(p.textMuted); gravity = Gravity.CENTER
                setPadding(dp(16), dp(28), dp(16), dp(28))
            }, LayoutParams(-1, -1))
            return
        }
        devices.forEach { device ->
            val chosen = selectedKey == device.key
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(76); setPadding(dp(16), dp(12), dp(16), dp(12))
                val fill = if (chosen) p.appsTile else p.tile
                val rounded = shape(fill, 18f)
                background = if (Build.VERSION.SDK_INT >= 21) RippleDrawable(
                    ColorStateList.valueOf(p.ripple), rounded, null) else rounded
                isSelected = chosen
            }
            row.addView(ImageView(context).apply {
                setImageResource(when (device.kind) {
                    HomeDevice.Kind.DONGLE -> R.drawable.ic_dongle_white
                    HomeDevice.Kind.CARPLAY_USB, HomeDevice.Kind.CARPLAY_WIRELESS -> R.drawable.ic_carplay
                    else -> R.drawable.ic_phone
                }); setColorFilter(p.text); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(16) })
            val texts = LinearLayout(context).apply { orientation = VERTICAL }
            texts.addView(TextView(context).apply {
                text = device.name; textSize = 20f; setTypeface(null, Typeface.BOLD); setTextColor(p.text)
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            texts.addView(TextView(context).apply {
                text = context.getString(when (device.kind) {
                    HomeDevice.Kind.AA_USB -> R.string.home_device_aa_usb
                    HomeDevice.Kind.AA_WIRELESS -> R.string.home_device_aa_wireless
                    HomeDevice.Kind.CARPLAY_USB -> R.string.home_device_cp_usb
                    HomeDevice.Kind.CARPLAY_WIRELESS -> R.string.home_device_cp_wireless
                    HomeDevice.Kind.DONGLE -> R.string.home_device_dongle
                }); textSize = 15f; setTextColor(p.textMuted)
            })
            row.addView(texts, LayoutParams(0, -2, 1f))
            row.addView(RadioButton(context).apply {
                isChecked = chosen; isClickable = false; isFocusable = false
                if (Build.VERSION.SDK_INT >= 21) buttonTintList = ColorStateList.valueOf(if (chosen) p.wifi else p.textMuted)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(48), dp(48)))
            row.setOnClickListener { selectedKey = device.key; renderRows(); updateConnect() }
            list.addView(row, LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun updateConnect() {
        val p = palette ?: return
        connect.isEnabled = selectedKey != null
        connect.alpha = if (connect.isEnabled) 1f else .45f
        connect.background = shape(p.wifi, 18f)
        connect.setTextColor(if (p.isDark) p.text else Color.WHITE)
    }
    private fun label(id: Int, size: Float, bold: Boolean = false) = TextView(context).apply {
        setText(id); textSize = size
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun shape(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius.toInt()).toFloat()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
