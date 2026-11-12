package com.lenid.zoh.utils

import android.graphics.RectF
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.view.doOnPreDraw

/** Tile-origin motion only; window effects follow the same timing as the card. */
internal class ConnectionPanelTransition(
    private val root: View,
    private val sheet: View,
    private val windowEffect: ConnectionWindowEffect,
    private val sourceBounds: RectF?,
) {
    private var closing = false
    private var originScale = 0.82f
    private var originX = 0f
    private var originY = 0f

    fun prepareEnter() {
        sheet.alpha = 0f
        sheet.doOnPreDraw {
            if (!closing) {
                updateOrigin()
                sheet.pivotX = sheet.width / 2f
                sheet.pivotY = sheet.height / 2f
                sheet.translationX = originX
                sheet.translationY = originY
                sheet.scaleX = originScale
                sheet.scaleY = originScale
                val easing = DecelerateInterpolator(1.6f)
                windowEffect.animateTo(1f, ENTER_DURATION_MS, easing)
                sheet.animate().alpha(1f).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setInterpolator(easing).setDuration(ENTER_DURATION_MS).start()
            }
        }
    }

    fun dismiss(after: () -> Unit) {
        if (closing) return
        closing = true
        updateOrigin()
        sheet.animate().cancel()
        val easing = AccelerateDecelerateInterpolator()
        windowEffect.animateTo(0f, EXIT_DURATION_MS, easing)
        sheet.animate().alpha(0f).translationX(originX).translationY(originY)
            .scaleX(originScale).scaleY(originScale).setInterpolator(easing)
            .setDuration(EXIT_DURATION_MS).withEndAction(after).start()
    }

    private fun updateOrigin() {
        val bounds = sourceBounds ?: return
        if (sheet.width <= 0 || sheet.height <= 0) return
        originScale = minOf(bounds.width() * root.width / sheet.width,
            bounds.height() * root.height / sheet.height).coerceIn(0.15f, 1f)
        originX = bounds.centerX() * root.width - sheet.left - sheet.width / 2f
        originY = bounds.centerY() * root.height - sheet.top - sheet.height / 2f
    }

    private companion object {
        const val ENTER_DURATION_MS = 420L
        const val EXIT_DURATION_MS = 300L
    }
}
