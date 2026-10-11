package com.lateral.beast

import android.view.KeyEvent
import android.view.View
import org.junit.Assert.*
import org.junit.Test

class DesktopSessionTest {
    private class Session : DesktopSession {
        override val view: View get() = error("No Android view needed for selection tests")
        override fun visible(visible: Boolean) = Unit
        override fun search(query: String, submit: Boolean) = Unit
        override fun widgets() = Unit
        override fun themes() = Unit
        override fun dismissPanels() = Unit
        override fun key(event: KeyEvent) = false
        override fun close() = Unit
    }

    @Test fun unavailableBridgeUsesLocalSession() {
        val local = Session()
        var starts = 0
        val connection = openDesktopSession(PendingExternalDesktopBridge) { starts++; local }
        assertSame(local, connection.session)
        assertFalse(connection.shared)
        assertEquals(1, starts)
    }

    @Test fun connectedAdapterDoesNotInitializeLocalRuntime() {
        val remote = Session()
        val connection = openDesktopSession(ExternalDesktopConnector { remote }) {
            error("Must not initialize a second runtime when connected")
        }
        assertSame(remote, connection.session)
        assertTrue(connection.shared)
    }
}
