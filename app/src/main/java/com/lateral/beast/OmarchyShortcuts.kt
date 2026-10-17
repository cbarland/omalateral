package com.lateral.beast

import android.view.KeyEvent

internal enum class OmarchyCommand {
    MENU, SEARCH, APPS, TERMINAL, BROWSER, FILES, CLOSE, CLOSE_ALL, FULLSCREEN, FLOAT, PIN,
    SPLIT, FOCUS, SWAP, WORKSPACE, MOVE_WORKSPACE, CYCLE_WORKSPACE, PREVIOUS_WORKSPACE,
    CYCLE_WINDOW, SCRATCHPAD, MOVE_SCRATCHPAD, RESIZE, GROUP, UNGROUP, GROUP_FOCUS, GROUP_JOIN, MAXIMIZE, PSEUDO,
}

internal data class OmarchyShortcut(val command: OmarchyCommand, val argument: Int = 0,
    val direction: WindowDirection? = null, val silent: Boolean = false, val horizontal: Boolean = true)

/** Shared by the external window and PhoneUI; Android system-reserved chords remain system-owned. */
internal object OmarchyShortcuts {
    var handler: ((OmarchyShortcut) -> Unit)? = null
    private val claimedKeys = mutableSetOf<Int>()
    var superHeld = false
        private set
    var modifiersChanged: ((Boolean) -> Unit)? = null

    fun dispatch(event: KeyEvent): Boolean {
        val held = if (event.keyCode == KeyEvent.KEYCODE_META_LEFT || event.keyCode == KeyEvent.KEYCODE_META_RIGHT) event.action == KeyEvent.ACTION_DOWN else event.isMetaPressed
        if (superHeld != held) { superHeld = held; modifiersChanged?.invoke(held) }
        val current = handler ?: return false
        if (event.action == KeyEvent.ACTION_UP && claimedKeys.remove(event.keyCode)) return true
        val shortcut = match(event.keyCode, event.isMetaPressed, event.isShiftPressed, event.isAltPressed, event.isCtrlPressed) ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        claimedKeys += event.keyCode
        if (event.repeatCount == 0 || shortcut.command == OmarchyCommand.RESIZE) current(shortcut)
        return true
    }

    fun detach(owner: (OmarchyShortcut) -> Unit) {
        if (handler === owner) { handler = null; claimedKeys.clear(); superHeld = false; modifiersChanged?.invoke(false); modifiersChanged = null }
    }

    fun match(key: Int, superKey: Boolean, shift: Boolean, alt: Boolean, ctrl: Boolean): OmarchyShortcut? {
        if (!superKey) return when {
            alt && !ctrl && key == KeyEvent.KEYCODE_TAB -> OmarchyShortcut(OmarchyCommand.CYCLE_WINDOW, if (shift) -1 else 1)
            ctrl && alt && !shift && key == KeyEvent.KEYCODE_FORWARD_DEL -> OmarchyShortcut(OmarchyCommand.CLOSE_ALL)
            else -> null
        }
        val number = when (key) {
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> key - KeyEvent.KEYCODE_1 + 1
            KeyEvent.KEYCODE_0 -> 10
            else -> null
        }
        if (number != null && !ctrl && (!alt || shift)) return OmarchyShortcut(
            if (shift) OmarchyCommand.MOVE_WORKSPACE else OmarchyCommand.WORKSPACE, number, silent = alt)
        if (number in 1..5 && alt && !shift && !ctrl) return OmarchyShortcut(OmarchyCommand.GROUP_FOCUS, number ?: 1, silent = true)
        val direction = when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT -> WindowDirection.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> WindowDirection.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> WindowDirection.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> WindowDirection.DOWN
            else -> null
        }
        if (direction != null && !alt && !ctrl) return OmarchyShortcut(if (shift) OmarchyCommand.SWAP else OmarchyCommand.FOCUS, direction = direction)
        if (direction != null && alt && !ctrl && !shift) return OmarchyShortcut(OmarchyCommand.GROUP_JOIN, direction = direction)
        if (direction != null && ctrl && !alt && !shift && direction in listOf(WindowDirection.LEFT, WindowDirection.RIGHT)) return OmarchyShortcut(OmarchyCommand.GROUP_FOCUS, if (direction == WindowDirection.LEFT) -1 else 1)
        if (key == KeyEvent.KEYCODE_TAB && alt && !ctrl) return OmarchyShortcut(OmarchyCommand.GROUP_FOCUS, if (shift) -1 else 1)
        if (key == KeyEvent.KEYCODE_TAB && !alt) return OmarchyShortcut(
            if (ctrl) OmarchyCommand.PREVIOUS_WORKSPACE else OmarchyCommand.CYCLE_WORKSPACE, if (shift) -1 else 1)
        if (key in listOf(KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS)) return OmarchyShortcut(
            OmarchyCommand.RESIZE, (if (key == KeyEvent.KEYCODE_EQUALS) 1 else -1) * (if (ctrl) 300 else if (alt) 25 else 100), horizontal = !shift)
        if (key == KeyEvent.KEYCODE_ENTER && !ctrl && !alt) return OmarchyShortcut(if (shift) OmarchyCommand.BROWSER else OmarchyCommand.TERMINAL)
        if (key == KeyEvent.KEYCODE_F && shift && !alt && !ctrl) return OmarchyShortcut(OmarchyCommand.FILES)
        if (key == KeyEvent.KEYCODE_F && alt && !shift && !ctrl) return OmarchyShortcut(OmarchyCommand.MAXIMIZE)
        if (key == KeyEvent.KEYCODE_B && shift && !alt && !ctrl) return OmarchyShortcut(OmarchyCommand.BROWSER)
        if (shift || ctrl) return null
        if (key == KeyEvent.KEYCODE_S) return OmarchyShortcut(if (alt) OmarchyCommand.MOVE_SCRATCHPAD else OmarchyCommand.SCRATCHPAD)
        if (key == KeyEvent.KEYCODE_G) return OmarchyShortcut(if (alt) OmarchyCommand.UNGROUP else OmarchyCommand.GROUP)
        if (alt) return null
        return when (key) {
            KeyEvent.KEYCODE_SPACE -> OmarchyShortcut(OmarchyCommand.MENU)
            KeyEvent.KEYCODE_A -> OmarchyShortcut(OmarchyCommand.SEARCH)
            KeyEvent.KEYCODE_D -> OmarchyShortcut(OmarchyCommand.APPS)
            KeyEvent.KEYCODE_W -> OmarchyShortcut(OmarchyCommand.CLOSE)
            KeyEvent.KEYCODE_F -> OmarchyShortcut(OmarchyCommand.FULLSCREEN)
            KeyEvent.KEYCODE_T -> OmarchyShortcut(OmarchyCommand.FLOAT)
            KeyEvent.KEYCODE_O -> OmarchyShortcut(OmarchyCommand.PIN)
            KeyEvent.KEYCODE_J -> OmarchyShortcut(OmarchyCommand.SPLIT)
            KeyEvent.KEYCODE_P -> OmarchyShortcut(OmarchyCommand.PSEUDO)
            else -> null
        }
    }
}
