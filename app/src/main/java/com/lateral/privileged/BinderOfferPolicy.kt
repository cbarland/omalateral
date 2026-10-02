package com.lateral.privileged

/** Pure admission policy; equality is Binder identity, never just a matching token. */
internal object BinderOfferPolicy {
    enum class Decision { IGNORE, REJECT, WAIT_FOR_SHELL, ACCEPT }
    fun <T> decide(accepted: T?, healthy: Boolean, candidate: T?, offered: T,
        token: String?, attempt: UsbBootstrapAttempt?, shellAccepted: Boolean): Decision {
        if (accepted == offered) return Decision.IGNORE
        // A correlated, live USB attempt may stage alongside the current owner. The
        // service keeps that owner published until the candidate passes readiness.
        if (healthy) return if (token != null && attempt?.matches(token) == true &&
            (candidate == null || candidate == offered)) {
            if (shellAccepted) Decision.ACCEPT else Decision.WAIT_FOR_SHELL
        } else Decision.REJECT
        if (token == null) return if (attempt?.valid() == true) Decision.REJECT else Decision.ACCEPT
        if (attempt?.matches(token) != true || (candidate != null && candidate != offered)) {
            return Decision.REJECT
        }
        return if (shellAccepted) Decision.ACCEPT else Decision.WAIT_FOR_SHELL
    }

    fun <T> currentDeath(current: T?, dead: T, generation: Long, deadGeneration: Long): Boolean =
        current == dead && generation == deadGeneration
}
