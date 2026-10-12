package com.lateral.beast

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** Converts context clicks into real, asynchronous touch holds understood by Compose. */
internal class DesktopLongPress {
    private var target: View? = null
    private var finish: Runnable? = null
    private var downAt = 0L
    private var x = 0
    private var y = 0

    fun start(view: View, screenX: Int, screenY: Int, stillValid: () -> Boolean) {
        cancel()
        if (!stillValid()) return
        target = view
        x = screenX
        y = screenY
        downAt = SystemClock.uptimeMillis()
        dispatch(view, MotionEvent.ACTION_DOWN)
        val release = Runnable {
            finish = null
            target = null
            dispatch(view, if (stillValid()) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL)
        }
        // A backdated DOWN followed immediately by UP does not trigger Compose's timer.
        // Compose's pointer timeout itself yields before completing. If rendering blocks
        // the main thread past both deadlines, a single delayed UP can beat that final
        // yield. Arm the release in a second queue turn so the timeout can complete first.
        val settle = Runnable {
            finish = release
            view.postDelayed(release, 150L)
        }
        // DOWN can enqueue the Compose coroutine that starts its timeout. Start our
        // clock only after that queued work, including when the caller blocks this turn.
        val arm = Runnable {
            finish = settle
            view.postDelayed(settle, ViewConfiguration.getLongPressTimeout().toLong())
        }
        finish = arm
        view.post(arm)
    }

    fun cancel() {
        val view = target ?: return
        finish?.let(view::removeCallbacks)
        finish = null
        target = null
        dispatch(view, MotionEvent.ACTION_CANCEL)
    }

    private fun dispatch(view: View, action: Int) {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action,
            (x - location[0]).toFloat(), (y - location[1]).toFloat(), 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}
