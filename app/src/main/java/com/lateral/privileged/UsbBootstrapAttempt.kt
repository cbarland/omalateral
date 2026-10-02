package com.lateral.privileged

import java.util.concurrent.CompletableFuture

/** Cancellation is visible immediately, including while the service worker is busy. */
internal class UsbBootstrapAttempt(val token: String, val deadlineNanos: Long) {
    val admitted = CompletableFuture<Boolean>()
    val ready = CompletableFuture<Boolean>()
    @Volatile var refusalReason: String? = null
    private val ended = CompletableFuture<Unit>()

    /** Durable even after READY; listeners are never invoked under the attempt lock. */
    fun onEnded(listener: () -> Unit) {
        ended.thenRun(listener)
    }
    @Volatile var cancelled = false
        private set
    fun valid(now: Long = System.nanoTime()): Boolean = !cancelled && now < deadlineNanos
    fun matches(offered: String, now: Long = System.nanoTime()): Boolean =
        valid(now) && token == offered
    @Synchronized fun commit(action: () -> Unit): Boolean {
        if (!valid()) return false
        action()
        return true
    }
    fun cancel() {
        synchronized(this) {
            if (cancelled) return
            cancelled = true
        }
        ended.complete(Unit)
        admitted.complete(false)
        ready.complete(false)
    }
}
