package com.lateral

import org.junit.Assert.*
import org.junit.Test

class PhoneViewPolicyTest {
    @Test fun manualEntryWithoutKeyboardSurvivesDeviceRefresh() {
        val policy = PhoneViewPolicy()
        policy.update(false, false)
        assertFalse(policy.takeOffer())
        assertTrue(policy.canEnter)
        assertTrue(policy.setActive(true, true))
        repeat(10) { policy.update(false, false) }
        assertTrue(policy.active)
        policy.update(false, true)
        assertFalse(policy.active)
    }

    @Test fun missingBridgeBlocksManualAndOfferedEntry() {
        val policy = PhoneViewPolicy()
        policy.update(false, false)
        assertFalse(policy.setActive(true, false))
        policy.update(true, false)
        assertTrue(policy.takeOffer())
        assertFalse(policy.setActive(true, false))
        assertTrue(policy.setActive(true, true))
    }

    @Test fun failedPresentationAllowsRetryWithoutReconnectingKeyboard() {
        val policy = PhoneViewPolicy()
        policy.update(true, false)
        assertTrue(policy.takeOffer())
        policy.offerPresentationFailed()
        assertTrue(policy.takeOffer())
        assertFalse(policy.takeOffer())
    }

    @Test fun keyboardWithoutDisplayOffersOnceAndAllowsManualEntryAfterDecline() {
        val policy = PhoneViewPolicy()
        policy.update(true, false)
        assertTrue(policy.takeOffer())
        assertFalse(policy.takeOffer())
        assertTrue(policy.setActive(true, true))
        policy.setActive(false)
        assertFalse(policy.takeOffer())
        assertTrue(policy.setActive(true, true))
    }

    @Test fun keyboardRemovalExitsAndReconnectOffersAgain() {
        val policy = PhoneViewPolicy()
        policy.update(true, false)
        policy.setActive(true, true)
        policy.update(false, false)
        assertFalse(policy.active)
        assertFalse(policy.setActive(true))
        policy.update(true, false)
        assertTrue(policy.takeOffer())
    }

    @Test fun externalDisplayAlwaysWinsIncludingSimultaneousKeyboardConnection() {
        val policy = PhoneViewPolicy()
        policy.update(true, false)
        policy.setActive(true, true)
        policy.update(true, true)
        assertFalse(policy.active)
        assertFalse(policy.takeOffer())
        assertFalse(policy.setActive(true, true))
        policy.update(false, true)
        assertFalse(policy.eligible)
        policy.update(true, false)
        assertTrue(policy.takeOffer())
    }

    @Test fun repeatedDeviceNotificationsDoNotExitOrOfferAgain() {
        val policy = PhoneViewPolicy()
        policy.update(true, false)
        policy.setActive(true, true)
        repeat(10) { policy.update(true, false) }
        assertTrue(policy.active)
        assertFalse(policy.takeOffer())
    }
}
