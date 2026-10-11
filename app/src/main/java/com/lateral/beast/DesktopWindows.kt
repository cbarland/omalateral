package com.lateral.beast

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.os.SystemClock
import com.lateral.WorkspaceCursor

/** Mirrors the pointer above separate Compose windows and routes PhoneUI gestures to them. */
internal class DesktopWindows(private val displayId: () -> Int) {
    private data class Entry(val root: ViewGroup, val dismiss: () -> Unit, val cursor: BeastCursorOverlay,
        val layout: View.OnLayoutChangeListener)
    private val entries = mutableListOf<Entry>()
    private var captured: Entry? = null
    private var capturing = false
    private var downAt = 0L
    private var primary = false
    private val cursorChanged: () -> Unit = { entries.forEach { it.cursor.postInvalidateOnAnimation() } }

    init { WorkspaceCursor.addListener(cursorChanged) }

    fun attach(view: View, dismiss: () -> Unit): () -> Unit {
        val root = view as? ViewGroup ?: return {}
        val cursor = BeastCursorOverlay(root.context, true, displayId)
        val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            cursor.layout(0, 0, root.width, root.height)
        }
        // ViewGroupOverlay draws after all children/elevation, without consuming pointer events.
        root.overlay.add(cursor)
        cursor.layout(0, 0, root.width, root.height)
        root.addOnLayoutChangeListener(layout)
        val entry = Entry(root, dismiss, cursor, layout)
        entries += entry
        return { remove(entry) }
    }

    private fun remove(entry: Entry) {
        entries.remove(entry)
        entry.root.removeOnLayoutChangeListener(entry.layout)
        entry.root.overlay.remove(entry.cursor)
    }

    fun pointer(x: Int, y: Int, action: Int, button: Int): Boolean {
        if (action == MotionEvent.ACTION_DOWN) {
            captured = null
            capturing = false
            val top = entries.lastOrNull() ?: return false
            captured = top
            capturing = true
            downAt = SystemClock.uptimeMillis()
            primary = button == MotionEvent.BUTTON_PRIMARY
        }
        if (!capturing) return false
        captured?.takeIf { it in entries }?.let { target ->
            val origin = IntArray(2)
            target.root.getLocationOnScreen(origin)
            val localX = x - origin[0]
            val localY = y - origin[1]
            if (action == MotionEvent.ACTION_DOWN &&
                (localX < 0 || localY < 0 || localX >= target.root.width || localY >= target.root.height)) {
                target.dismiss()
            } else if (primary) {
                val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action,
                    localX.toFloat(), localY.toFloat(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { target.root.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            captured = null
            capturing = false
        }
        return true
    }

    fun click(x: Int, y: Int, button: Int): Boolean {
        if (!pointer(x, y, MotionEvent.ACTION_DOWN, button)) return false
        pointer(x, y, MotionEvent.ACTION_UP, button)
        return true
    }

    fun scroll(x: Int, y: Int, amount: Float): Boolean {
        val target = entries.lastOrNull() ?: return false
        val origin = IntArray(2)
        target.root.getLocationOnScreen(origin)
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = (x - origin[0]).toFloat(); this.y = (y - origin[1]).toFloat()
            setAxisValue(MotionEvent.AXIS_VSCROLL, amount)
        })
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0)
        try { target.root.dispatchGenericMotionEvent(event) } finally { event.recycle() }
        return true
    }

    fun close() {
        WorkspaceCursor.removeListener(cursorChanged)
        entries.toList().forEach(::remove)
        captured = null
        capturing = false
    }
}
