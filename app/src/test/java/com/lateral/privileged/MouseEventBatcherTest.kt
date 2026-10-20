package com.lateral.privileged

import org.junit.Assert.assertEquals
import org.junit.Test

class MouseEventBatcherTest {
    @Test fun coalescesMotionWithoutMovingItAcrossButtonTransitions() {
        val queued = ArrayDeque<Runnable>()
        val delivered = mutableListOf<String>()
        val batcher = MouseEventBatcher<Any>(
            post = { queued.addLast(it) },
            onMotion = { _, dx, dy, wheel -> delivered += "move:$dx,$dy,$wheel" },
            onButton = { _, code, pressed -> delivered += "button:$code,$pressed" },
        )
        val session = Any()

        batcher.motion(session, 2, 3, 0)
        batcher.motion(session, 4, 1, 0)
        batcher.button(session, 272, true)
        batcher.motion(session, 5, 0, 0)
        batcher.button(session, 272, false)

        assertEquals(4, queued.size)
        while (queued.isNotEmpty()) queued.removeFirst().run()
        assertEquals(
            listOf("move:6,4,0", "button:272,true", "move:5,0,0", "button:272,false"),
            delivered,
        )
    }

    @Test fun reversalAndWheelKeepTheirOriginalCursorOrder() {
        val queued = ArrayDeque<Runnable>()
        val delivered = mutableListOf<String>()
        val session = Any()
        val batcher = MouseEventBatcher<Any>(
            post = { queued.addLast(it) },
            onMotion = { _, dx, dy, wheel -> delivered += "$dx,$dy,$wheel" },
            onButton = { _, _, _ -> },
        )

        batcher.motion(session, -10, 0, 0)
        batcher.motion(session, 10, 0, 0)
        batcher.motion(session, 0, 0, 1)
        batcher.motion(session, 3, 0, 0)

        while (queued.isNotEmpty()) queued.removeFirst().run()
        assertEquals(listOf("-10,0,0", "10,0,0", "0,0,1", "3,0,0"), delivered)
    }

    @Test fun newMotionWhileDrainingGetsItsOwnUiTurn() {
        val queued = ArrayDeque<Runnable>()
        val delivered = mutableListOf<Int>()
        val session = Any()
        lateinit var batcher: MouseEventBatcher<Any>
        batcher = MouseEventBatcher(
            post = { queued.addLast(it) },
            onMotion = { _, dx, _, _ ->
                delivered += dx
                if (dx == 1) batcher.motion(session, 2, 0, 0)
            },
            onButton = { _, _, _ -> },
        )

        batcher.motion(session, 1, 0, 0)
        queued.removeFirst().run()
        assertEquals(1, queued.size)
        queued.removeFirst().run()
        assertEquals(listOf(1, 2), delivered)
    }
}
