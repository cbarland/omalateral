package com.lateral

/** The preference arms keyboard offers; only accepting an eligible offer enters. */
internal class PhoneViewPolicy {
    var enabled = false
        private set
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        offered = false
        if (!value) active = false
    }
    var eligible = false
        private set
    var active = false
        private set
    private var offered = false
    var canEnter = true
        private set
    var keyboardPresent = false
        private set

    fun update(keyboard: Boolean, externalDisplay: Boolean) {
        val keyboardDisconnected = keyboardPresent && !keyboard
        keyboardPresent = keyboard
        canEnter = !externalDisplay
        eligible = keyboard && !externalDisplay
        if (externalDisplay || keyboardDisconnected) active = false
        if (!eligible) {
            offered = false
        }
    }

    fun takeOffer(): Boolean {
        if (!enabled || !eligible || active || offered) return false
        offered = true
        return true
    }

    /** A newly foregrounded PhoneUI gets one fresh eligibility offer. */
    fun phoneUiFocused() {
        if (!active) offered = false
    }

    fun offerPresentationFailed() { offered = false }

    fun setActive(value: Boolean, bridgeReady: Boolean = false): Boolean {
        active = value && enabled && eligible && bridgeReady
        if (eligible) offered = true
        return active
    }
}
