package com.lateral.beast

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.platform.app.InstrumentationRegistry
import com.lateral.InputSettings
import com.lateral.OmarchyThemes
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportMapThemeAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun sameIndicatorTracksEveryThemeWithoutScrolling() = onMain {
        val context = instrumentation.targetContext
        val previous = InputSettings.theme
        val view = ViewportMapView(context).apply {
            layout(0, 0, 100, 4)
            viewportWidthPx = 50
            contentWidthPx = 100
            scrollOffsetPx = 25
        }
        try {
            for (theme in OmarchyThemes.all) {
                InputSettings.setTheme(context, theme)
                // Reuse the same view and unchanged geometry, as in a live theme switch.
                capture(view) { bitmap ->
                    assertEquals("${theme.id} track", theme.selection, bitmap.getPixel(0, 2))
                    assertEquals("${theme.id} indicator", theme.accent, bitmap.getPixel(50, 2))
                    assertEquals("${theme.id} trailing track", theme.selection, bitmap.getPixel(99, 2))
                }
            }
        } finally {
            InputSettings.setTheme(context, previous)
        }
    }

    @Test fun themedIndicatorPreservesScrollGeometry() = onMain {
        val theme = InputSettings.theme
        val view = ViewportMapView(instrumentation.targetContext).apply {
            layout(0, 0, 100, 4)
            viewportWidthPx = 50
            contentWidthPx = 100
        }
        capture(view) {
            assertEquals(theme.accent, it.getPixel(0, 2))
            assertEquals(theme.selection, it.getPixel(99, 2))
        }
        view.scrollOffsetPx = 50
        capture(view) {
            assertEquals(theme.selection, it.getPixel(0, 2))
            assertEquals(theme.accent, it.getPixel(99, 2))
        }
        view.contentWidthPx = 50
        capture(view) {
            assertEquals(theme.accent, it.getPixel(0, 2))
            assertEquals(theme.accent, it.getPixel(99, 2))
        }
        view.contentWidthPx = 0
        capture(view) {
            assertEquals(theme.selection, it.getPixel(0, 2))
            assertEquals(theme.selection, it.getPixel(99, 2))
        }
    }

    private fun capture(view: ViewportMapView, check: (Bitmap) -> Unit) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            check(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
