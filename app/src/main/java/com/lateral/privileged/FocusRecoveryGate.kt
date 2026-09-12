package com.lateral.privileged

/** Short transaction authority, explicitly revoked by Android navigation. */
internal class FocusRecoveryGate {
    private var blocked = false
    private var generation = 0L
    private var owner = -1
    private var deadline = 0L

    @Synchronized fun userNavigation() {
        blocked = true
        generation++
        deadline = 0
    }

    /** Only a new interaction in the foreground workspace may re-arm after navigation. */
    @Synchronized fun userInteraction(task: Int, top: Int?) {
        if (task >= 0 && top == task) blocked = false
    }

    @Synchronized fun begin(task: Int, top: Int?, now: Long): Long? {
        if (blocked || task < 0 || top != task) return null
        if (owner != task || now >= deadline) generation++
        owner = task
        deadline = now + 2500L
        return generation
    }

    @Synchronized fun ticket(task: Int, now: Long): Long? =
        if (!blocked && owner == task && now < deadline) generation else null

    @Synchronized fun permits(task: Int, ticket: Long, top: Int?, topIsHome: Boolean, now: Long): Boolean {
        if (blocked || owner != task || generation != ticket || now >= deadline) return false
        if (top == task || topIsHome) return true
        // Another app, Overview, or an unidentifiable foreground is never ours to replace.
        userNavigation()
        return false
    }
}
