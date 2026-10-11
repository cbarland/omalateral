package com.lateral.beast

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArraySet

/** In-process, exact-editor connection; never copies text into launcher search or a second app. */
object DesktopTextInputSession {
    class Editor internal constructor(
        val createConnection: (EditorInfo) -> InputConnection,
        val dismiss: () -> Unit,
    ) {
        internal val connections = mutableListOf<InputConnectionWrapper>()
    }
    var editor: Editor? = null
        private set
    val active get() = editor != null
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val disconnected = Proxy.newProxyInstance(InputConnection::class.java.classLoader,
        arrayOf(InputConnection::class.java)) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            else -> null
        }
    } as InputConnection
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    fun begin(factory: (EditorInfo) -> InputConnection, dismiss: () -> Unit): Editor {
        HostedTextInputSession.close()
        return Editor(factory, dismiss).also {
            val previous = editor
            editor = it
            previous?.let(::release)
            listeners.forEach { listener -> listener() }
        }
    }

    fun connect(info: EditorInfo): InputConnection? {
        val current = editor ?: return null
        val delegate = current.createConnection(info)
        return InputConnectionWrapper(delegate, true).also { current.connections += it }
    }

    /** Old coroutine cleanup must not close a newer editor's keyboard. */
    fun release(expected: Editor) {
        val connections = expected.connections.toList()
        expected.connections.clear()
        connections.forEach {
            it.closeConnection()
            // InputConnectionWrapper dereferences its target even after closeConnection.
            // Late IME calls must be harmless, not crash or reach the next editor.
            it.setTarget(disconnected)
        }
        if (editor !== expected) return
        editor = null
        listeners.forEach { it() }
    }

    fun close() {
        val current = editor ?: return
        release(current)
        current.dismiss()
    }
}
