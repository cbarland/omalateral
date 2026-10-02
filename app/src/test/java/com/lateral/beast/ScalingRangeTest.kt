package com.lateral.beast

import com.lateral.DisplayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/** Exhaust every selectable scaling step, including the ultrawide UI range. */
class ScalingRangeTest {
    @Test fun allSelectableProfilesProduceFiniteBoundedRendering() {
        for (uiStep in 0..83) for (hostedStep in 0..88) for (samplingStep in 0..20) {
            val profile = DisplayProfile(uiScale = .85f + uiStep * .05f,
                hostedScale = .6f + hostedStep * .05f,
                supersampling = 1f + samplingStep * .05f).sanitized()
            assertEquals(profile, profile.sanitized())
            val render = HostedAppDensity.renderScale(profile.hostedScale, profile.supersampling)
            assertTrue(render.isFinite())
            assertTrue(render in HostedAppDensity.MIN_RENDER_SCALE..HostedAppDensity.MAX_RENDER_SCALE)
            val dpi = HostedAppDensity.supersampledDensity(442, profile.supersampling)
            assertTrue(dpi in 72..HostedAppDensity.MAX_RENDER_DPI)
            assertEquals(profile.hostedScale, profile.supersampling / (.78f * render), .00001f)
        }
    }

    @Test fun everyUiStepFitsPopupInPhoneAndGlassesLandscapeHeights() {
        for (uiStep in 0..83) for (height in listOf(1, 720, 1080, 1216)) {
            val scale = .85f + uiStep * .05f
            val density = 442f / 160f
            val minimum = (180 * scale * density).roundToInt()
            val maximum = minOf((height * .78f).roundToInt(), (760 * scale * density).roundToInt())
            val result = popupDimension(height, minimum, maximum)
            assertTrue(result in 1..maximum.coerceAtLeast(1))
        }
    }
}
