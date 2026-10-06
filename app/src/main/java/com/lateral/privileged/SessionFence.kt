package com.lateral.privileged

/** Epochs are independent of startup attempts. Old work cannot act on a replacement owner. */
internal class SessionFence<T : Any> {
    data class Session<T>(val generation: Long, val value: T)
    @Volatile var current: Session<T>? = null
        private set
    @Volatile var generation = 0L
        private set

    @Synchronized fun install(value: T): Session<T> {
        current?.takeIf { it.value === value }?.let { return it }
        return Session(++generation, value).also { current = it }
    }

    @Synchronized fun invalidate(expected: Session<T>?): Boolean {
        if (expected == null || current !== expected) return false
        current = null
        generation++
        return true
    }

    fun matches(expected: Session<T>?): Boolean = expected != null && current === expected
}
