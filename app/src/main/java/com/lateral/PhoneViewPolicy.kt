package com.lateral

/** Keyboard detection offers entry; manual entry only requires no external display. */
internal class PhoneViewPolicy {
    var eligible = false
        private set
    var active = false
        private set
    private var offered = false
    var canEnter = true
        private set
    private var keyboardPresent = false

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
        if (!eligible || active || offered) return false
        offered = true
        return true
    }

    fun offerPresentationFailed() { offered = false }

    fun setActive(value: Boolean, bridgeReady: Boolean = false): Boolean {
        active = value && canEnter && bridgeReady
        if (eligible) offered = true
        return active
    }
}
