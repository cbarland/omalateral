package com.lateral.beast

/** One uncropped, flat native mirror, in the tile SurfaceView's local coordinates. */
internal object ProtectedTileGeometry {
    fun slices(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): FloatArray? {
        if (sourceWidth !in 1..16384 || sourceHeight !in 1..16384 || width !in 1..16384 || height !in 1..16384) return null
        return floatArrayOf(0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat(),
            width.toFloat()/sourceWidth, 0f, 0f, height.toFloat()/sourceHeight, 0f, 0f)
    }
}
