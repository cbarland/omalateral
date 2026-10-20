package com.lateral.beast

import android.view.KeyEvent
import android.view.View

/** Home surface and input actions shared by the embedded and external desktops. */
interface DesktopSession {
    val view: View
    fun visible(visible: Boolean)
    fun search(query: String, submit: Boolean)
    fun apps()
    fun widgets()
    fun workspace(number: Int) = Unit
    fun homeMenu(): Boolean = false
    fun themes()
    fun dismissPanels()
    fun key(event: KeyEvent): Boolean
    fun hover(x: Int, y: Int) = Unit
    fun popupClick(x: Int, y: Int, button: Int): Boolean = false
    fun popupPointer(x: Int, y: Int, action: Int, button: Int): Boolean = false
    fun popupScroll(x: Int, y: Int, amount: Float): Boolean = false
    fun close()
}
