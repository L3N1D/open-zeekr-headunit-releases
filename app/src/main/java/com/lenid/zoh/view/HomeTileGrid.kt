package com.lenid.zoh.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * The home's quick actions as a grid of big tiles, two to a row, the rows sharing the height. Only
 * the tiles that are shown take a cell, so hiding a connection the driver does not use closes the
 * gap instead of leaving a hole; an odd last tile takes its whole row. The tiles' own sizes are not
 * used: the cell decides.
 */
class HomeTileGrid @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    private val gap = (8 * resources.displayMetrics.density).toInt()

    private fun shown() = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != View.GONE }

    private fun rows(count: Int) = (count + COLUMNS - 1) / COLUMNS

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val tiles = shown()
        val rows = rows(tiles.size)
        if (rows > 0) {
            val innerW = width - paddingLeft - paddingRight
            val cellH = (height - paddingTop - paddingBottom - gap * (rows - 1)) / rows
            val cellW = (innerW - gap * (COLUMNS - 1)) / COLUMNS
            tiles.forEachIndexed { i, tile ->
                val wide = i == tiles.size - 1 && tiles.size % COLUMNS == 1
                tile.measure(
                    MeasureSpec.makeMeasureSpec(if (wide) innerW else cellW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(maxOf(cellH, 0), MeasureSpec.EXACTLY)
                )
            }
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val tiles = shown()
        tiles.forEachIndexed { i, tile ->
            val row = i / COLUMNS
            val column = i % COLUMNS
            val left = paddingLeft + column * (tile.measuredWidth + gap)
            val top = paddingTop + row * (tile.measuredHeight + gap)
            tile.layout(left, top, left + tile.measuredWidth, top + tile.measuredHeight)
        }
    }

    private companion object {
        const val COLUMNS = 2
    }
}
