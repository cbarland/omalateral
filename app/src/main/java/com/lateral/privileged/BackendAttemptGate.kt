package com.lateral.privileged

/** Pure selection/attempt correlation. A stop failure cannot select or start another backend. */
internal class BackendAttemptGate(initial: PrivilegeBackendId) {
    @Volatile var selected = initial
        private set
    @Volatile var attemptId = 0L
        private set
    private var sequence = 0L
    private var pending = false

    @Synchronized fun begin(): Long? {
        if (pending) return null
        pending = true
        attemptId = ++sequence
        return attemptId
    }
    fun accepts(backend: PrivilegeBackendId, attempt: Long): Boolean =
        attempt != 0L && backend == selected && attempt == attemptId
    @Synchronized fun finished(attempt: Long) { if (attemptId == attempt) pending = false }
    @Synchronized fun invalidate() { attemptId = ++sequence; pending = false }
    @Synchronized fun selectAfterStop(next: PrivilegeBackendId, result: Result<Unit>): Boolean {
        if (result.isFailure) return false
        invalidate()
        selected = next
        return true
    }
}
