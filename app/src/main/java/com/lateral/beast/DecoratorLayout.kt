package com.lateral.beast

import kotlin.math.min

/** Scale chrome to fit around a truly centered navigation group without overflow. */
internal fun decoratorGeometryScale(uiScale: Float, availableWidthPx: Int, density: Float): Float {
    val desired = uiScale.coerceIn(.85f, 1.15f)
    if (availableWidthPx <= 0 || !density.isFinite() || density <= 0f) return desired
    // Reserve equal space for the larger side so the middle controls stay centered, plus
    // enough room between the navigation arrows for a useful (ellipsized) app title.
    val centeredMinimumDp = 2 * 126 + 60 + 64 + 12
    return min(desired, availableWidthPx / (centeredMinimumDp * density)).coerceAtLeast(.1f)
}
