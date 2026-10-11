package com.lateral.beast

import android.view.KeyEvent
import android.view.View

/** Host-facing session boundary shared by the embedded desktop and a future bridge adapter. */
interface DesktopSession {
    val view: View
    fun visible(visible: Boolean)
    fun search(query: String, submit: Boolean)
    fun widgets()
    fun themes()
    fun dismissPanels()
    fun key(event: KeyEvent): Boolean
    fun close()
}

/** Returns only an authorized, usable session; unavailable/unsupported providers return null. */
internal fun interface ExternalDesktopConnector {
    fun connect(): DesktopSession?
}

/** No wire protocol has been agreed upstream. Never equate package presence with a connection. */
internal object PendingExternalDesktopBridge : ExternalDesktopConnector {
    override fun connect(): DesktopSession? = null
}

internal data class DesktopConnection(val session: DesktopSession, val shared: Boolean)

/** Keep embedded initialization lazy so a future connected provider does not start two runtimes. */
internal fun openDesktopSession(
    connector: ExternalDesktopConnector,
    embedded: () -> DesktopSession,
): DesktopConnection {
    val external = connector.connect()
    return if (external != null) DesktopConnection(external, shared = true)
    else DesktopConnection(embedded(), shared = false)
}
