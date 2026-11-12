package com.lenid.zoh.carplay

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Immutable negotiated canvas; window changes select an area, never recreate the session. */
internal data class CarPlayViewport(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
    val aspect get() = width.toDouble() / height
    fun fitIn(canvas: CarPlayViewport): CarPlayViewport {
        val scale = min(canvas.width.toDouble() / width, canvas.height.toDouble() / height)
        return CarPlayViewport((width * scale).roundToInt().coerceIn(1, canvas.width),
            (height * scale).roundToInt().coerceIn(1, canvas.height))
    }
    fun closest(areas: List<CarPlayViewport>): Int =
        areas.indices.minByOrNull { abs(areas[it].aspect - aspect) } ?: 0
}
