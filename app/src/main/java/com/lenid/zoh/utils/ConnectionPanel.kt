package com.lenid.zoh.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.lenid.zoh.R

/** Shared presentation only: no transport, permissions or launcher state. */
internal class ConnectionPanel(context: Context, title: Int, subtitle: Int, icon: Int, window: android.view.Window, close: () -> Unit) {
    val root = FrameLayout(context)
    val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val sheet = ScrollView(context)
    private fun dp(value: Int) = (value * ctx.resources.displayMetrics.density).toInt()
    private val ctx = context
    private val scrim = View(context)
    private val windowEffect = ConnectionWindowEffect(window, scrim)
    private val transition = ConnectionPanelTransition(root, sheet, windowEffect, ConnectionOrigin.takeBounds())
    private fun color(id: Int) = ContextCompat.getColor(ctx, id)
    fun text(value: Int, size: Float, muted: Boolean = false) = TextView(ctx).apply {
        setText(value); textSize = size
        setTextColor(color(if (muted) R.color.home_text_muted else R.color.home_text))
    }
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    init {
        root.setBackgroundColor(Color.TRANSPARENT)
        val backdrop = FrameLayout(ctx)
        scrim.setBackgroundColor(Color.BLACK)
        scrim.alpha = 0f
        backdrop.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = windowEffect.attach()
            override fun onViewDetachedFromWindow(view: View) = windowEffect.detach()
        })
        root.setOnClickListener { close() }
        sheet.apply {
            isClickable = true; clipToOutline = true; isVerticalScrollBarEnabled = false
            background = shape(color(R.color.expressive_surface), 32)
            elevation = dp(20).toFloat()
            addView(body)
        }
        body.setPadding(dp(32), dp(28), dp(32), dp(28))
        val heading = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(ImageView(ctx).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(color(R.color.home_apps_icon))
            background = shape(color(R.color.home_apps_tile), 20); setPadding(dp(16), dp(16), dp(16), dp(16))
        }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(20) })
        heading.addView(text(title, 34f).apply { setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            setIconResource(R.drawable.ic_close_white); iconTint = ColorStateList.valueOf(color(R.color.home_text))
            iconSize = dp(26); iconPadding = 0; minWidth = 0; minimumWidth = 0
            setPadding(dp(14), 0, dp(14), 0)
            contentDescription = ctx.getString(R.string.close)
            setOnClickListener { close() }
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        body.addView(heading)
        body.addView(text(subtitle, 18f, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(24) })
        root.addView(sheet, FrameLayout.LayoutParams(dp(880), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(40); bottomMargin = dp(24)
        })
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val params = sheet.layoutParams as FrameLayout.LayoutParams
            val width = minOf(dp(880), (root.width - dp(32)).coerceAtLeast(1))
            if (params.width != width) { params.width = width; sheet.layoutParams = params }
        }
        transition.prepareEnter()
    }

    fun setBackgroundVisible(visible: Boolean) = windowEffect.setVisible(visible)

    fun setCardVisible(visible: Boolean) {
        sheet.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Full-size projection above the backdrop and below the card. */
    fun addProjectionView(view: View) {
        root.addView(view, root.indexOfChild(sheet), FrameLayout.LayoutParams(-1, -1))
    }

    fun addOverlay(view: View, params: FrameLayout.LayoutParams) {
        root.addView(view, params)
    }

    fun dismiss(after: () -> Unit) = transition.dismiss(after)

    fun option(title: Int, description: Int, icon: Int, click: () -> Unit): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
        setPadding(dp(24), dp(24), dp(24), dp(24))
        background = RippleDrawable(ColorStateList.valueOf(0x221fa846), shape(color(R.color.home_tile), 24), null)
        isFocusable = true; setOnClickListener { click() }
        addView(ImageView(ctx).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(color(R.color.home_wifi)) }, LinearLayout.LayoutParams(dp(34), dp(34)))
        addView(text(title, 23f).apply { setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        addView(text(description, 16f, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
}
