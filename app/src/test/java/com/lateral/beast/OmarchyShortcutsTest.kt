package com.lateral.beast

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class OmarchyShortcutsTest {
    @Test fun menuAndAppRolesMatchOmarchy() {
        assertEquals(OmarchyCommand.MENU, OmarchyShortcuts.match(KeyEvent.KEYCODE_SPACE, true, false, false, false)?.command)
        assertEquals(OmarchyCommand.SEARCH, OmarchyShortcuts.match(KeyEvent.KEYCODE_A, true, false, false, false)?.command)
        assertEquals(OmarchyCommand.APPS, OmarchyShortcuts.match(KeyEvent.KEYCODE_D, true, false, false, false)?.command)
        assertNull(OmarchyShortcuts.match(KeyEvent.KEYCODE_L, true, false, false, false))
        assertEquals(OmarchyCommand.TERMINAL, OmarchyShortcuts.match(KeyEvent.KEYCODE_ENTER, true, false, false, false)?.command)
        assertEquals(OmarchyCommand.BROWSER, OmarchyShortcuts.match(KeyEvent.KEYCODE_ENTER, true, true, false, false)?.command)
        assertEquals(OmarchyCommand.FILES, OmarchyShortcuts.match(KeyEvent.KEYCODE_F, true, true, false, false)?.command)
    }

    @Test fun workspaceTenAndSilentMoveKeepDistinctCommands() {
        assertEquals(OmarchyShortcut(OmarchyCommand.WORKSPACE, 10), OmarchyShortcuts.match(KeyEvent.KEYCODE_0, true, false, false, false))
        assertEquals(OmarchyShortcut(OmarchyCommand.MOVE_WORKSPACE, 2, silent = true), OmarchyShortcuts.match(KeyEvent.KEYCODE_2, true, true, true, false))
        assertNull(OmarchyShortcuts.match(KeyEvent.KEYCODE_2, true, false, false, true))
    }

    @Test fun focusSwapAndResizeDoNotConfuseModifiers() {
        assertEquals(OmarchyShortcut(OmarchyCommand.FOCUS, direction = WindowDirection.UP), OmarchyShortcuts.match(KeyEvent.KEYCODE_DPAD_UP, true, false, false, false))
        assertEquals(OmarchyShortcut(OmarchyCommand.SWAP, direction = WindowDirection.UP), OmarchyShortcuts.match(KeyEvent.KEYCODE_DPAD_UP, true, true, false, false))
        assertEquals(OmarchyShortcut(OmarchyCommand.RESIZE, 25, horizontal = false), OmarchyShortcuts.match(KeyEvent.KEYCODE_EQUALS, true, true, true, false))
        assertNull(OmarchyShortcuts.match(KeyEvent.KEYCODE_C, false, false, false, true))
    }
}
