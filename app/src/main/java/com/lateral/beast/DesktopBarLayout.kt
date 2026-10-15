package com.lateral.beast

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Three real layout regions: optional items yield before controls or text can overlap. */
internal class DesktopBarLayout(
    context: Context,
    private val leading: View,
    private val center: View,
    private val trailing: View,
    private val optional: List<View>,
) : ViewGroup(context) {
    init { addView(leading); addView(center); addView(trailing) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val natural = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val rowHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        fun measureSides() { leading.measure(natural, rowHeight); trailing.measure(natural, rowHeight) }
        optional.forEach { it.visibility = VISIBLE }
        center.visibility = VISIBLE
        center.measure(natural, rowHeight)
        measureSides()
        for (item in optional) {
            if (leading.measuredWidth + trailing.measuredWidth + center.measuredWidth + 32 <= width) break
            item.visibility = GONE
            measureSides()
        }
        // True screen center, never the center of a leftover, asymmetrical slot.
        val side = maxOf(leading.measuredWidth, trailing.measuredWidth)
        if (2 * side + center.measuredWidth + 24 > width) center.visibility = GONE
        if (leading.measuredWidth + trailing.measuredWidth > width) {
            trailing.measure(MeasureSpec.makeMeasureSpec(width / 2, MeasureSpec.AT_MOST), rowHeight)
            leading.measure(MeasureSpec.makeMeasureSpec((width - trailing.measuredWidth).coerceAtLeast(0), MeasureSpec.AT_MOST), rowHeight)
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        leading.layout(0, 0, leading.measuredWidth, height)
        trailing.layout(width - trailing.measuredWidth, 0, width, height)
        if (center.visibility != GONE) {
            val x = (width - center.measuredWidth) / 2
            center.layout(x, 0, x + center.measuredWidth, height)
        }
    }
}
