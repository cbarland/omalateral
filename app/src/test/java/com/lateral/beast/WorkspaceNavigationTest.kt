package com.lateral.beast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceNavigationTest {
    private val ids = listOf(10L, 20L, 30L)

    @Test fun movesToAdjacentWindow() {
        assertEquals(10L, relativeFocusTarget(ids, 20L, -1))
        assertEquals(30L, relativeFocusTarget(ids, 20L, 1))
    }

    @Test fun stopsAtWorkspaceEdges() {
        assertNull(relativeFocusTarget(ids, 10L, -1))
        assertNull(relativeFocusTarget(ids, 30L, 1))
    }

    @Test fun entersFromNearestDirectionalEdgeWhenNothingIsFocused() {
        assertEquals(10L, relativeFocusTarget(ids, null, 1))
        assertEquals(30L, relativeFocusTarget(ids, null, -1))
    }
}
