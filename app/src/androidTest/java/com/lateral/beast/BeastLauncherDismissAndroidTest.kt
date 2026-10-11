package com.lateral.beast

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lateral.MainActivity
import com.lateral.InputController
import com.lateral.PhoneProxyEditText
import com.lateral.TrackpadEvent
import com.lateral.TransientPanelCoordinator
import com.lateral.privileged.PrivilegeBackendId
import com.lateral.privileged.PrivilegedService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Launcher regression on two emulator displays; no privileged helper is required. */
@RunWith(AndroidJUnit4::class)
class BeastLauncherDismissAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun phoneRoutedClicksKeysAndRepeatedOpensDismissWithoutClickThrough() {
        val display = checkNotNull(context.getSystemService(DisplayManager::class.java).displays.firstOrNull {
            it.displayId != 0 && it.name == "Emulator 2D Display"
        }) { "Create a secondary emulator display before running this regression" }
        lateinit var phone: MainActivity
        lateinit var beast: BeastActivity
        PrivilegedService.selectBackend(PrivilegeBackendId.SHIZUKU)
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        await("PhoneUI resumed before workspace startup") {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
            if (activity != null) { phone = activity; true } else false
        }
        instrumentation.runOnMainSync { BeastWorkspaceController.ensure(phone, display) }
        await("both activities created") {
            val activities = listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED).flatMap {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
            }
            val phoneActivity = activities.filterIsInstance<MainActivity>().firstOrNull()
            val beastActivity = activities.filterIsInstance<BeastActivity>().firstOrNull {
                !it.isFinishing && it.display?.displayId == display.displayId
            }
            if (phoneActivity != null && beastActivity != null) {
                phone = phoneActivity
                beast = beastActivity
                true
            } else false
        }
        try {
            fun launcher() = field<View>(beast, "launcher")
            fun open() {
                instrumentation.runOnMainSync { invoke(beast, "showLauncher") }
                await("launcher visible with phone search active") {
                    launcher().visibility == View.VISIBLE && LauncherSearchSession.active &&
                        launcher().width > 0
                }
                // Shell actions have a 320ms debounce; wait for a deliberate second action.
                Thread.sleep(400)
            }
            fun closed() = await("launcher dismissed and modal ownership released") {
                launcher().visibility == View.VISIBLE && launcher().translationZ == 0f && !LauncherSearchSession.active &&
                    !TransientPanelCoordinator.isActiveOnDisplay(display.displayId)
            }
            fun closePoint(): Pair<Int, Int> {
                var point = 0 to 0
                // The embedded home stays mounted; a toolbar click dismisses its modal panel.
                instrumentation.runOnMainSync { point = center(checkNotNull(findText(beast.window.decorView, "+APPS"))) }
                return point
            }
            open()
            val (tapX, tapY) = closePoint()
            instrumentation.runOnMainSync {
                val controller = field<InputController>(phone, "inputController")
                controller.handleEvent(TrackpadEvent.Move(
                    (tapX - controller.cursorX) / controller.sensitivity,
                    (tapY - controller.cursorY) / controller.sensitivity,
                ))
                controller.handleEvent(TrackpadEvent.LeftClick)
            }
            closed()
            repeat(3) {
                open()
                instrumentation.runOnMainSync {
                    LauncherSearchSession.updateQuery("settings")
                    invoke(beast, "showLauncher")
                    assertTrue(LauncherSearchSession.active)
                    assertEquals("settings", LauncherSearchSession.query)
                }
                val (x, y) = closePoint()
                instrumentation.runOnMainSync {
                    assertTrue(BeastInputRouter.click(display.displayId, x, y, MotionEvent.BUTTON_PRIMARY))
                }
                closed()
            }
            open()
            instrumentation.runOnMainSync {
                val root = field<View>(beast, "root")
                val bounds = Rect().also { root.getGlobalVisibleRect(it) }
                assertTrue(BeastInputRouter.click(display.displayId, bounds.left + 5,
                    bounds.top + 1, MotionEvent.BUTTON_PRIMARY))
            }
            closed()
            open()
            val (x, y) = closePoint()
            instrumentation.runOnMainSync {
                assertTrue(BeastInputRouter.pointer(display.displayId, x, y, MotionEvent.ACTION_DOWN,
                    MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY))
                assertTrue(BeastInputRouter.pointer(display.displayId, x, y, MotionEvent.ACTION_UP,
                    MotionEvent.BUTTON_PRIMARY, 0))
            }
            closed()
            for (code in listOf(KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK)) {
                open()
                instrumentation.runOnMainSync {
                    assertTrue(phone.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)))
                    assertTrue(phone.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)))
                }
                closed()
            }
            open()
            instrumentation.runOnMainSync {
                val search = field<PhoneProxyEditText>(phone, "beastSearchField")
                assertTrue(search.onKeyPreIme(KeyEvent.KEYCODE_BACK,
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
                assertTrue(search.onKeyPreIme(KeyEvent.KEYCODE_BACK,
                    KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
            }
            closed()
            open()
            instrumentation.runOnMainSync { phone.onBackPressedDispatcher.onBackPressed() }
            closed()
            // Outside clicks over +APPS must dismiss, without invoking that covered control.
            open()
            instrumentation.runOnMainSync {
                val (outsideX, outsideY) = center(checkNotNull(findText(beast.window.decorView, "+APPS")))
                val downAt = SystemClock.uptimeMillis()
                mouse(beast, outsideX, outsideY, MotionEvent.ACTION_DOWN, downAt)
                mouse(beast, outsideX, outsideY, MotionEvent.ACTION_UP, downAt)
            }
            closed()
            open()
            val (oldX, oldY) = closePoint()
            instrumentation.runOnMainSync {
                assertTrue(BeastInputRouter.pointer(display.displayId, oldX, oldY, MotionEvent.ACTION_DOWN,
                    MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY))
                LauncherSearchSession.close()
                invoke(beast, "showLauncher")
                assertTrue(BeastInputRouter.pointer(display.displayId, oldX, oldY, MotionEvent.ACTION_UP,
                    MotionEvent.BUTTON_PRIMARY, 0))
            }
            Thread.sleep(400)
            instrumentation.runOnMainSync {
                assertEquals("old UP cannot close the replacement menu", View.VISIBLE, launcher().visibility)
                assertTrue("Phone search retains modal ownership while Compose transitions", field<Boolean>(beast, "launcherActive"))
                val (homeX, homeY) = center(launcher())
                assertTrue(BeastInputRouter.pinch(display.displayId, homeX, homeY, 1.5f))
                assertTrue(BeastInputRouter.scroll(display.displayId, homeX, homeY, -1f))
                assertTrue("phone search provides a visible dismissal", field<View>(phone, "phoneProxyClose").isShown)
                field<View>(phone, "phoneProxyClose").performClick()
            }
            closed()
            open()
            instrumentation.runOnMainSync { invoke(beast, "restoreWorkspaceFocus") }
            closed()
        } finally {
            instrumentation.runOnMainSync {
                LauncherSearchSession.close()
                beast.finish()
                phone.finish()
            }
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(50)
        }
        fail(label)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(activity: Activity, name: String): T = activity.javaClass
        .getDeclaredField(name).apply { isAccessible = true }.get(activity) as T

    private fun invoke(activity: Activity, name: String) = activity.javaClass
        .getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)

    private fun findText(view: View, text: String): TextView? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findText(view.getChildAt(index), text)?.let { return it }
        }
        return null
    }

    private fun center(view: View): Pair<Int, Int> = Rect().also {
        assertTrue("control visible", view.getGlobalVisibleRect(it))
    }.let { it.centerX() to it.centerY() }

    private fun mouse(activity: Activity, x: Int, y: Int, action: Int, downAt: Long) {
        val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }),
            arrayOf(MotionEvent.PointerCoords().apply { this.x = x.toFloat(); this.y = y.toFloat() }),
            0, if (action == MotionEvent.ACTION_DOWN) MotionEvent.BUTTON_PRIMARY else 0,
            1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}
