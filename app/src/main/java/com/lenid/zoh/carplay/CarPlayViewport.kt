package com.lenid.zoh.carplay

import com.lenid.zoh.modules.CodeModule
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Immutable negotiated canvas; window changes select an area, never recreate the session. */
internal data class CarPlayViewport(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
    val aspect get() = width.toDouble() / height
    fun fitIn(canvas: CarPlayViewport): CarPlayViewport {
        val horizontal = canvas.width.toDouble() / width
        val vertical = canvas.height.toDouble() / height
        // The i32 aspect comparison is exact for supported display sizes.
        val decision = if (maxOf(width, height, canvas.width, canvas.height) <= 32767)
            CodeModule.CARPLAY.evaluate(width, height, canvas.width, canvas.height) else null
        val scale = when (decision) {
            1 -> horizontal
            0 -> vertical
            else -> min(horizontal, vertical)
        }
        return CarPlayViewport((width * scale).roundToInt().coerceIn(1, canvas.width),
            (height * scale).roundToInt().coerceIn(1, canvas.height))
    }
    fun closest(areas: List<CarPlayViewport>): Int =
        areas.indices.minByOrNull { abs(areas[it].aspect - aspect) } ?: 0
}
