package com.lateral.beast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoratorLayoutTest {
    @Test fun wideCardsKeepRequestedGeometryScale() {
        assertEquals(1.15f, decoratorGeometryScale(2f, 1000, 2f), .0001f)
        assertEquals(.85f, decoratorGeometryScale(.85f, 1000, 2f), .0001f)
    }

    @Test fun narrowCardsShrinkEnoughForCenteredControlsAndTitle() {
        for (density in listOf(1f, 1.3f, 2f, 3f)) {
            for (width in listOf(200, 323, 469, 720)) {
                val scale = decoratorGeometryScale(2f, width, density)
                assertTrue(388f * density * scale <= width + .01f)
                assertTrue(scale in .1f..1.15f)
            }
        }
    }

    @Test fun unavailableWidthUsesRequestedScaleUntilLayout() {
        assertEquals(1f, decoratorGeometryScale(1f, 0, 2f), .0001f)
    }
}
