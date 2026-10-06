package com.lateral.beast

import org.junit.Assert.*
import org.junit.Test

class RetainedDisplayCacheTest {
    @Test fun sameHelperCanReattachButReplacementCannotReuseDisplay() {
        val cache = RetainedDisplayCache()
        cache.retain(42L, 9, 1L)
        assertEquals(9, cache.take(42L, 1L))
        assertNull(cache.take(42L, 1L))
        cache.retain(42L, 9, 1L)
        assertNull(cache.take(42L, 3L))
        cache.retain(42L, 9, 3L)
        assertEquals(9, cache.take(42L, 3L))
    }
}
