package com.lateral.beast

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

/** Only window gestures belong to this layer; gaps pass through to Omalauncher. */
internal class DesktopWorkspaceView(
    context: Context,
    private val occupies: (Int, Int) -> Boolean,
) : FrameLayout(context) {
    private var windowGesture = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            windowGesture = occupies(event.rawX.toInt(), event.rawY.toInt())
        }
        if (!windowGesture) return false
        // Keep the entire gesture with its original target, even when it moves into a gap.
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            windowGesture = false
        }
        return handled
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!occupies(event.rawX.toInt(), event.rawY.toInt())) return false
        return super.dispatchGenericMotionEvent(event)
    }
}
