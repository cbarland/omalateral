package com.lateral.beast

import org.junit.Assert.*
import org.junit.Test

class OmarchyLayoutTest {
    @Test fun newWindowSplitsFocusedPaneAndPreservesOtherPanes() {
        val layout = DwindleLayout()
        layout.sync(listOf(1, 2), 1, 1000, 600)
        val before = layout.bounds(1000, 600, 10, 10)
        layout.sync(listOf(1, 2, 3), 3, 1000, 600, mapOf(3L to 1L))
        val after = layout.bounds(1000, 600, 10, 10)
        assertEquals(before[2], after[2])
        assertEquals(after[1]!!.x, after[3]!!.x)
        assertTrue(after[1]!!.bottom < after[3]!!.y)
        assertEquals(990, after[2]!!.right)
    }

    @Test fun splitRatioAndOrientationSurviveSaveAndDisplayResize() {
        val layout = DwindleLayout()
        layout.sync(listOf(1, 2), 1, 1000, 600)
        layout.toggleSplit(1)
        layout.resize(1, false, .1f)
        val restored = DwindleLayout(layout.save())
        assertEquals(layout.bounds(1920, 1080, 10, 10), restored.bounds(1920, 1080, 10, 10))
        val boxes = restored.bounds(1920, 1080, 10, 10)
        assertTrue(boxes[1]!!.height > boxes[2]!!.height)
        assertEquals(boxes[1]!!.x, boxes[2]!!.x)
    }

    @Test fun closingPaneCollapsesOnlyItsParentAndKeepsRemainingWindows() {
        val layout = DwindleLayout()
        layout.sync(listOf(1, 2, 3), null, 1000, 600)
        layout.sync(listOf(1, 3), 3, 1000, 600)
        val boxes = layout.bounds(1000, 600, 10, 10)
        assertEquals(setOf(1L, 3L), boxes.keys)
        assertEquals(580, boxes[3]!!.height)
        layout.swap(1, 3)
        assertEquals(boxes[1], layout.bounds(1000, 600, 10, 10)[3])
    }

    @Test fun focusPrefersSharedEdgeAndStopsAtWorkspaceBoundary() {
        val boxes = mapOf(1L to WindowBounds(0, 0, 500, 1000),
            2L to WindowBounds(510, 0, 490, 490), 3L to WindowBounds(510, 510, 490, 490))
        assertEquals(1L, directionalWindow(boxes, 3, WindowDirection.LEFT))
        assertEquals(2L, directionalWindow(boxes, 3, WindowDirection.UP))
        assertNull(directionalWindow(boxes, 1, WindowDirection.LEFT))
    }

    @Test fun invalidSavedTreeStartsCleanAndNegativeLaunchIdsRoundTrip() {
        assertTrue(DwindleLayout("H,broken,L,1").bounds(100, 100, 0, 0).isEmpty())
        val layout = DwindleLayout()
        layout.sync(listOf(-1, -2), -1, 100, 100)
        assertEquals(layout.bounds(100, 100, 5, 5), DwindleLayout(layout.save()).bounds(100, 100, 5, 5))
    }
}
