package com.lateral.beast

import kotlin.math.min
import kotlin.math.roundToInt

/** The secure source stays at its original size while its compositor tile changes shape. */
internal data class ProtectedTileFit(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val scale: Float,
    val left: Float,
    val top: Float,
) {
    val right: Float get() = left + sourceWidth * scale
    val bottom: Float get() = top + sourceHeight * scale

    fun slices(): FloatArray = floatArrayOf(
        0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat(),
        scale, 0f, 0f, scale, left, top,
    )

    /** PhoneUI and native composition must agree on the app-content rectangle. */
    fun sourcePoint(x: Float, y: Float, clampOutside: Boolean = false): Pair<Int, Int>? {
        if (!clampOutside && (x < left || x >= right || y < top || y >= bottom)) return null
        return ((x - left) / scale).roundToInt().coerceIn(0, sourceWidth - 1) to
            ((y - top) / scale).roundToInt().coerceIn(0, sourceHeight - 1)
    }
}

/** One uncropped, aspect-preserving native mirror in the tile's local coordinates. */
internal object ProtectedTileGeometry {
    fun fit(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): ProtectedTileFit? {
        if (sourceWidth !in 1..16384 || sourceHeight !in 1..16384 ||
            width !in 1..16384 || height !in 1..16384) return null
        val scale = min(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        return ProtectedTileFit(sourceWidth, sourceHeight, scale,
            (width - sourceWidth * scale) / 2f, (height - sourceHeight * scale) / 2f)
    }

    fun slices(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): FloatArray? =
        fit(sourceWidth, sourceHeight, width, height)?.slices()
}
