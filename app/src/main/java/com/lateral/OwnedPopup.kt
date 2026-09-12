package com.lateral

/** Main-thread popup ownership, independent of a dialog's themed Context wrapper. */
internal class OwnedPopup<T : Any>(private val dismiss: (T) -> Unit) {
    var current: T? = null
        private set
    private var owner: Any? = null

    fun attach(owner: Any, popup: T) {
        clear()
        this.owner = owner
        current = popup
    }

    fun dismissed(popup: T) {
        if (current === popup) {
            current = null
            owner = null
        }
    }

    fun clearFor(owner: Any) {
        if (this.owner === owner) clear()
    }

    fun clear() {
        val popup = current ?: return
        // Clear before framework callbacks, including synchronous/reentrant dismissal.
        current = null
        owner = null
        try {
            dismiss(popup)
        } catch (error: IllegalArgumentException) {
            // Android may already have removed the Activity's window (notably on Nubia).
            // A stale popup must not interrupt device-change policy or workspace teardown.
            if (error.message?.contains("not attached to window manager") != true) throw error
        }
    }
}
