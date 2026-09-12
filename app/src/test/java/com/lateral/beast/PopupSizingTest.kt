package com.lateral.beast

import org.junit.Assert.assertEquals
import org.junit.Test

class PopupSizingTest {
    @Test fun scaledMinimumMayExceedLandscapeHeight() {
        assertEquals(842, popupDimension(2000, 920, 842))
    }
    @Test fun respectsAvailableSpaceAndPreferredMinimum() {
        assertEquals(300, popupDimension(100, 300, 700))
        assertEquals(500, popupDimension(500, 300, 700))
        assertEquals(700, popupDimension(900, 300, 700))
    }
    @Test fun tinyViewportStillProducesValidDimensions() {
        assertEquals(1, popupDimension(100, 900, -50))
    }
}
