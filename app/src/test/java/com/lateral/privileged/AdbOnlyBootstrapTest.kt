package com.lateral.privileged

import org.junit.Assert.*
import org.junit.Test

class AdbOnlyBootstrapTest {
    @Test fun admissionRequiresShellAndCanonicalToken() {
        val token = "ab".repeat(16)
        assertTrue(UsbLifeline.allowsAdmission(2000, token))
        for (uid in listOf(0, 1000, 10000)) assertFalse(UsbLifeline.allowsAdmission(uid, token))
        for (bad in listOf(null, "", "a".repeat(31), "g".repeat(32))) {
            assertFalse(UsbLifeline.allowsAdmission(2000, bad))
        }
    }

    @Test fun explicitModeGeneratesFreshTokensAndPreservesAoa() {
        val first = UsbLifeline.mode(arrayOf("--usb-adb-only"))
        val second = UsbLifeline.mode(arrayOf("--usb-adb-only"))
        assertTrue(first.adbOnly)
        assertTrue(UsbLifeline.allowsAdmission(2000, first.token))
        assertNotEquals(first.token, second.token)
        val aoa = UsbLifeline.mode(arrayOf("--usb-lifeline=" + "AB".repeat(16)))
        assertFalse(aoa.adbOnly)
        assertEquals("ab".repeat(16), aoa.token)
        assertNull(UsbLifeline.mode(emptyArray()).token)
        assertThrows(IllegalArgumentException::class.java) {
            UsbLifeline.mode(arrayOf("--usb-adb-only", "extra"))
        }
    }

    @Test fun correlatedAdmissionMayStageBesideWirelessButRejectsForeignBinder() {
        val attempt = UsbBootstrapAttempt("ab".repeat(16), Long.MAX_VALUE)
        fun offer(current: String?, healthy: Boolean, binder: String, token: String) =
            BinderOfferPolicy.decide(current, healthy, "admitted", binder, token, attempt, true)
        assertEquals(BinderOfferPolicy.Decision.ACCEPT, offer("wireless", true, "admitted", attempt.token))
        assertEquals(BinderOfferPolicy.Decision.REJECT, offer(null, false, "other", attempt.token))
        assertEquals(BinderOfferPolicy.Decision.REJECT, offer(null, false, "admitted", "stale"))
        assertEquals(BinderOfferPolicy.Decision.ACCEPT, offer(null, false, "admitted", attempt.token))
        attempt.cancel()
        assertFalse(attempt.commit { fail("Timed out admission published") })
        assertFalse(attempt.admitted.get())
        assertEquals(BinderOfferPolicy.Decision.REJECT, offer(null, false, "admitted", attempt.token))
        assertEquals(BinderOfferPolicy.Decision.IGNORE, offer("admitted", true, "admitted", attempt.token))
    }

    @Test fun expiredAdmissionFailsClosed() {
        val attempt = UsbBootstrapAttempt("ab".repeat(16), System.nanoTime() - 1)
        assertFalse(attempt.valid())
        assertFalse(attempt.commit { fail("Expired admission published") })
    }
}
