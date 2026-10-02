package com.lateral.privileged

import org.junit.Assert.*
import org.junit.Test
import com.lateral.privileged.BinderOfferPolicy.Decision.*

class BinderOfferPolicyTest {
    private fun attempt() = UsbBootstrapAttempt("ab".repeat(16), System.nanoTime() + 10_000_000_000)

    @Test fun staleAndDuplicateOffersCannotDisplaceHealthyHelper() {
        val a = attempt()
        assertEquals(ACCEPT, BinderOfferPolicy.decide("wireless", true, null, "usb", a.token, a, true))
        assertEquals(IGNORE, BinderOfferPolicy.decide("usb", true, null, "usb", a.token, null, false))
        assertEquals(REJECT, BinderOfferPolicy.decide("usb", true, null, "other", a.token, null, false))
        assertEquals(REJECT, BinderOfferPolicy.decide(null, false, "first", "second", a.token, a, true))
        assertEquals(REJECT, BinderOfferPolicy.decide(null, false, null, "late", a.token, null, true))
    }

    @Test fun binderAndShellCanArriveInEitherOrderButNeedBoth() {
        val a = attempt()
        assertEquals(WAIT_FOR_SHELL, BinderOfferPolicy.decide(null, false, null, "usb", a.token, a, false))
        assertEquals(ACCEPT, BinderOfferPolicy.decide(null, false, "usb", "usb", a.token, a, true))
        assertEquals(ACCEPT, BinderOfferPolicy.decide(null, false, null, "usb", a.token, a, true))
        assertEquals(REJECT, BinderOfferPolicy.decide(null, false, null, "usb", "bad", a, true))
        assertEquals(REJECT, BinderOfferPolicy.decide(null, false, null, "wireless", null, a, false))
        a.cancel()
        assertEquals(REJECT, BinderOfferPolicy.decide(null, false, null, "usb", a.token, a, true))
        assertEquals(ACCEPT, BinderOfferPolicy.decide(null, false, null, "wireless", null, a, false))
    }

    @Test fun correlatedUsbCanStageBesideHealthyOwnerButForeignOrConcurrentOfferIsRejected() {
        val a = attempt()
        assertEquals(WAIT_FOR_SHELL,
            BinderOfferPolicy.decide("wireless", true, null, "usb", a.token, a, false))
        assertEquals(ACCEPT,
            BinderOfferPolicy.decide("wireless", true, "usb", "usb", a.token, a, true))
        assertEquals(REJECT,
            BinderOfferPolicy.decide("wireless", true, "usb", "second", a.token, a, true))
        assertEquals(REJECT,
            BinderOfferPolicy.decide("wireless", true, null, "foreign", "bad", a, true))
    }

    @Test fun cancelledCommitAndOldDeathCannotPublishOrClearNewService() {
        val a = attempt()
        a.cancel()
        assertFalse(a.commit { fail("Cancelled attempt published") })
        assertFalse(BinderOfferPolicy.currentDeath("new", "old", 2, 1))
        assertFalse(BinderOfferPolicy.currentDeath("same", "same", 2, 1))
        assertTrue(BinderOfferPolicy.currentDeath("new", "new", 2, 2))
    }
}
