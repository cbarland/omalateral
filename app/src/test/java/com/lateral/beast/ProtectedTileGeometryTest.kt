package com.lateral.beast

import org.junit.Assert.*
import org.junit.Test

class ProtectedTileGeometryTest {
    @Test fun mapsSupersampledProducerToTileWithoutChangingItsAppCoordinates() {
        assertArrayEquals(floatArrayOf(0f,0f,1600f,1200f,.5f,0f,0f,.5f,0f,0f),
            ProtectedTileGeometry.slices(1600,1200,800,600), .0001f)
    }
    @Test fun allowsNonuniformScaleForActualHostBounds() {
        assertArrayEquals(floatArrayOf(0f,0f,400f,800f,2f,0f,0f,.75f,0f,0f),
            ProtectedTileGeometry.slices(400,800,800,600), .0001f)
    }
    @Test fun rejectsUnavailableAndUnboundedSurfaces() {
        assertNull(ProtectedTileGeometry.slices(0,800,800,600))
        assertNull(ProtectedTileGeometry.slices(400,800,0,600))
        assertNull(ProtectedTileGeometry.slices(16385,800,800,600))
    }
}
