package com.lateral.beast

/** Available space wins over scaled preferred minimums on short landscape displays. */
internal fun popupDimension(desired: Int, preferredMinimum: Int, available: Int): Int {
    val maximum = available.coerceAtLeast(1)
    return desired.coerceIn(preferredMinimum.coerceIn(1, maximum), maximum)
}
