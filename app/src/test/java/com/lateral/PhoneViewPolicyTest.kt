package com.lateral

import org.junit.Assert.*
import org.junit.Test

class PhoneViewPolicyTest {
    @Test fun disabledPreferenceNeverOffersAndDisablingExits() {
        val policy = PhoneViewPolicy()
        policy.update(true, false)
        assertFalse(policy.takeOffer())
        assertFalse(policy.setActive(true, true))
        policy.setEnabled(true)
        assertFalse(policy.active)
        assertTrue(policy.takeOffer())
        assertTrue(policy.setActive(true, true))
        policy.setEnabled(false)
        assertFalse(policy.active)
        assertFalse(policy.takeOffer())
    }

    @Test fun armingWithoutKeyboardDoesNotOfferOrEnter() {
        val policy = PhoneViewPolicy().apply { setEnabled(true) }
        policy.update(false, false)
        assertFalse(policy.takeOffer())
        assertTrue(policy.canEnter)
        assertFalse(policy.setActive(true, true))
        repeat(10) { policy.update(false, false) }
        assertFalse(policy.active)
        policy.update(false, true)
        assertFalse(policy.active)
    }

    @Test fun missingBridgeBlocksAcceptedEntry() {
        val policy = PhoneViewPolicy().apply { setEnabled(true) }
        policy.update(false, false)
        assertFalse(policy.setActive(true, false))
        policy.update(true, false)
        assertTrue(policy.takeOffer())
        assertFalse(policy.setActive(true, false))
        assertTrue(policy.setActive(true, true))
    }

    @Test fun failedPresentationAllowsRetryWithoutReconnectingKeyboard() {
        val policy = PhoneViewPolicy().apply { setEnabled(true) }
        policy.update(true, false)
        assertTrue(policy.takeOffer())
        policy.offerPresentationFailed()
        assertTrue(policy.takeOffer())
        assertFalse(policy.takeOffer())
    }

    @Test fun keyboardWithoutDisplayOffersOnceAndAllowsManualEntryAfterDecline() {
        val policy = PhoneViewPolicy().apply { setEnabled(true) }
        policy.update(true, false)
        assertTrue(policy.takeOffer())
        assertFalse(policy.takeOffer())
        assertTrue(policy.setActive(true, true))
        policy.setActive(false)
        assertFalse(policy.takeOffer())
        assertTrue(policy.setActive(true, true))
    }

    @Test fun keyboardRemovalExitsAndReconnectOffersAgain() {
        val policy = PhoneViewPolicy().apply { setEnabled(true) }
        policy.update(true, false)
        policy.setActive(true, true)
        policy.update(false, false)
        assertFalse(policy.active)
        assertFalse(policy.setActive(true))
        policy.update(true, false)
        assertTrue(policy.takeOffer())
    }

    @Test fun externalDisplayAlwaysWinsIncludingSimultaneousKeyboardConnection() {
        val policy = PhoneViewPolicy().apply { setEnabled(true) }
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
        val policy = PhoneViewPolicy().apply { setEnabled(true) }
        policy.update(true, false)
        policy.setActive(true, true)
        repeat(10) { policy.update(true, false) }
        assertTrue(policy.active)
        assertFalse(policy.takeOffer())
    }

    @Test fun foregroundedPhoneUiGetsFreshOfferWithoutDeviceReconnect() {
        val policy = PhoneViewPolicy().apply { setEnabled(true) }
        policy.update(true, false)
        assertTrue(policy.takeOffer())
        assertFalse(policy.takeOffer())
        policy.phoneUiFocused()
        assertTrue(policy.takeOffer())
        assertFalse(policy.takeOffer())
    }
}
