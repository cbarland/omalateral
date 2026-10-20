package com.lateral.beast

import org.junit.Assert.*
import org.junit.Test

class ProtectedTileGeometryTest {
    @Test fun mapsSupersampledProducerToTileWithoutChangingItsAppCoordinates() {
        assertArrayEquals(floatArrayOf(0f,0f,1600f,1200f,.5f,0f,0f,.5f,0f,0f),
            ProtectedTileGeometry.slices(1600,1200,800,600), .0001f)
    }
    @Test fun centersSecureSourceWithoutStretchingAfterWindowResize() {
        assertArrayEquals(floatArrayOf(0f,0f,400f,800f,.75f,0f,0f,.75f,250f,0f),
            ProtectedTileGeometry.slices(400,800,800,600), .0001f)
        assertArrayEquals(floatArrayOf(0f,0f,1600f,900f,.375f,0f,0f,.375f,0f,131.25f),
            ProtectedTileGeometry.slices(1600,900,600,600), .0001f)
    }
    @Test fun mapsPhoneInputOnlyThroughFittedAppContent() {
        val fit = checkNotNull(ProtectedTileGeometry.fit(400,800,800,600))
        assertEquals(200 to 400, fit.sourcePoint(400f,300f))
        assertEquals(0 to 0, fit.sourcePoint(250f,0f))
        assertNull(fit.sourcePoint(100f,300f))
        assertEquals(0 to 400, fit.sourcePoint(100f,300f,clampOutside=true))
    }
    @Test fun rejectsUnavailableAndUnboundedSurfaces() {
        assertNull(ProtectedTileGeometry.slices(0,800,800,600))
        assertNull(ProtectedTileGeometry.slices(400,800,0,600))
        assertNull(ProtectedTileGeometry.slices(16385,800,800,600))
    }
}
