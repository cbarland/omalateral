package com.lateral.beast

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

class FreeformWindowAndroidTest {
    @Test fun dragGripResizesFloatingWindowOnExternalDisplay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val display = BeastActivity.findExternalDisplay(context)
        assumeNotNull(display)
        val scenario = ActivityScenario.launch<BeastActivity>(Intent(context, BeastActivity::class.java)
            .setAction(BeastActivity.ACTION_ENSURE_WORKSPACE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ActivityOptions.makeBasic().apply { launchDisplayId = checkNotNull(display).displayId }.toBundle())
        var task: BeastTask? = null
        try {
            scenario.onActivity { activity ->
                task = WorkspaceState.launchNew("com.omalateral.test.drag.size", "Fixture", "Drag fixture")
                task!!.placement = TaskPlacement.BEAST_VISIBLE
                WorkspaceState.toggleFloating(task!!.id)
                activity.javaClass.getDeclaredMethod("hideLauncher").apply { isAccessible = true }.invoke(activity)
            }
            val deadline = SystemClock.uptimeMillis() + 5_000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    @Suppress("UNCHECKED_CAST")
                    val cards = activity.javaClass.getDeclaredField("cards").apply { isAccessible = true }
                        .get(activity) as Map<Long, BeastTaskCard>
                    val card = cards[task!!.id]
                    ready = card != null && card.width > 0 && card.height > 0
                }
                if (!ready) Thread.sleep(50)
            }
            assertTrue("Floating card was never laid out", ready)
            scenario.onActivity { activity ->
                @Suppress("UNCHECKED_CAST")
                val cards = activity.javaClass.getDeclaredField("cards").apply { isAccessible = true }
                    .get(activity) as Map<Long, BeastTaskCard>
                val card = checkNotNull(cards[task!!.id])
                val handle = card.javaClass.getDeclaredField("resizeHandle").apply { isAccessible = true }.get(card) as View
                val bounds = Rect()
                assertTrue(handle.getGlobalVisibleRect(bounds))
                val before = task!!.floatingWidth
                val x = bounds.centerX().toFloat()
                val y = bounds.centerY().toFloat()
                val now = SystemClock.uptimeMillis()
                for ((action, dx, dy) in listOf(
                    Triple(MotionEvent.ACTION_DOWN, 0f, 0f),
                    Triple(MotionEvent.ACTION_MOVE, 120f, 60f),
                    Triple(MotionEvent.ACTION_UP, 120f, 60f),
                )) {
                    val event = MotionEvent.obtain(now, now + 20, action, x + dx, y + dy, 0)
                    try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
                }
                assertTrue("Resize grip did not change freeform width", task!!.floatingWidth > before + .03f)
                val resizedWidth = task!!.floatingWidth
                WorkspaceState.removeClosed(task!!.id)
                task = WorkspaceState.launchNew("com.omalateral.test.drag.size", "Fixture", "Drag fixture")
                assertEquals("Resized width was not remembered for the next window", resizedWidth,
                    task!!.floatingWidth, .001f)
            }
        } finally {
            instrumentation.runOnMainSync { task?.let { WorkspaceState.removeClosed(it.id) } }
            scenario.close()
        }
    }

    @Test fun resizedAppWindowKeepsItsSizeWhenOpenedAgain() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            WorkspaceState.init(context)
            val packageName = "com.omalateral.test.freeform.size"
            val first = WorkspaceState.launchNew(packageName, "Fixture", "Size fixture")
            try {
                WorkspaceState.floatingBounds(first.id, .1f, .1f, .42f, .56f)
                WorkspaceState.removeClosed(first.id)
                val reopened = WorkspaceState.launchNew(packageName, "Fixture", "Size fixture")
                try {
                    assertEquals(.42f, reopened.floatingWidth, .001f)
                    assertEquals(.56f, reopened.floatingHeight, .001f)
                    val saved = JSONObject(context.getSharedPreferences("workspace_tasks", 0)
                        .getString("freeform_sizes.v1", "{}")!!).getJSONArray(packageName)
                    assertEquals(.42, saved.getDouble(0), .001)
                    assertEquals(.56, saved.getDouble(1), .001)
                    WorkspaceState.toggleFloating(reopened.id)
                    assertTrue(reopened.floating)
                    assertEquals(.42f, reopened.floatingWidth, .001f)
                } finally {
                    WorkspaceState.removeClosed(reopened.id)
                }
            } finally {
                WorkspaceState.removeClosed(first.id)
            }
        }
    }
}
