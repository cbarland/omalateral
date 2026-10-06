package com.lateral.privileged

import org.junit.Assert.*
import org.junit.Test

class SessionFenceTest {
    @Test fun sameOwnerIsIdempotent() {
        val fence = SessionFence<Any>(); val owner = Any()
        val first = fence.install(owner)
        assertSame(first, fence.install(owner))
        assertEquals(1L, fence.generation)
    }
    @Test fun oldDeathCannotInvalidateReplacementAndLossIsIdempotent() {
        val fence = SessionFence<Any>()
        val old = fence.install(Any()); assertTrue(fence.invalidate(old))
        val lostGeneration = fence.generation
        assertFalse(fence.invalidate(old)); assertEquals(lostGeneration, fence.generation)
        val fresh = fence.install(Any())
        assertFalse(fence.invalidate(old)); assertTrue(fence.matches(fresh))
        assertTrue(fresh.generation > old.generation)
    }
    @Test fun inputQueuedBeforeDeathIsDiscarded() {
        val fence = SessionFence<Any>(); val old = fence.install(Any()); var injections = 0
        val queued = { if (fence.matches(old)) injections++ }
        fence.invalidate(old); fence.install(Any()); queued()
        assertEquals(0, injections)
    }
    @Test fun oldQueryStillCompletesWithUnavailableAtDelivery() {
        val fence = SessionFence<Any>(); val old = fence.install(Any())
        var inFlight = true; var result: String? = "pending"
        val deliver = { result = if (fence.matches(old)) "old snapshot" else null; inFlight = false }
        fence.invalidate(old); fence.install(Any()); deliver()
        assertNull(result); assertFalse(inFlight)
    }
    @Test fun nullOwnerNeverAuthorizesWork() {
        val fence = SessionFence<Any>()
        assertFalse(fence.matches(null)); assertFalse(fence.invalidate(null))
        assertEquals(0L, fence.generation)
    }
}
