package com.lateral

import org.junit.Assert.*
import org.junit.Test

class OwnedPopupTest {
    @Test fun detachedWindowDoesNotAbortPolicyUpdateOrLeaveStalePopup() {
        val slot = OwnedPopup<Any> { throw IllegalArgumentException("not attached to window manager") }
        slot.attach(Any(), Any())
        slot.clear()
        assertNull(slot.current)
        slot.clear()
        val next = Any()
        slot.attach(Any(), next)
        assertSame(next, slot.current)
    }

    @Test fun onlyOwningActivityClearsPopup() {
        var dismissals = 0
        val slot = OwnedPopup<Any> { dismissals++ }
        val owner = Any()
        val popup = Any()
        slot.attach(owner, popup)
        slot.clearFor(Any())
        assertSame(popup, slot.current)
        slot.clearFor(owner)
        slot.clearFor(owner)
        assertNull(slot.current)
        assertEquals(1, dismissals)
    }

    @Test fun referencesAreClearedBeforeReentrantDismissal() {
        lateinit var slot: OwnedPopup<Any>
        var dismissals = 0
        slot = OwnedPopup {
            dismissals++
            assertNull(slot.current)
            slot.clear()
        }
        slot.attach(Any(), Any())
        slot.clear()
        assertEquals(1, dismissals)
    }

    @Test fun delayedDismissCallbackCannotClearReplacement() {
        val slot = OwnedPopup<Any> { }
        val old = Any()
        val next = Any()
        slot.attach(Any(), old)
        slot.attach(Any(), next)
        slot.dismissed(old)
        assertSame(next, slot.current)
        slot.dismissed(next)
        assertNull(slot.current)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unrelatedFailuresAreNotSwallowed() {
        val slot = OwnedPopup<Any> { throw IllegalArgumentException("unexpected") }
        slot.attach(Any(), Any())
        slot.clear()
    }
}
