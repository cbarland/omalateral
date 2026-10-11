package com.lateral.beast

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lateral.InputController
import com.lateral.TrackpadEvent
import com.lateral.TrackpadView
import dev.omalauncher.RuntimeOwner
import dev.omalauncher.ui.LauncherState
import org.junit.Assert.*
import org.junit.Test

class OmaDesktopLongPressAndroidTest {
    private fun <T> field(owner: Any, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
    }

    @Test fun touchpadRawMouseAndNativeMouseOpenHomeMenu() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
        val reader = ImageReader.newInstance(1920, 1080, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .createVirtualDisplay("omalateral-gesture-test", 1920, 1080, 160, reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or (1 shl 10))
        try {
            val targetDisplay = checkNotNull(BeastActivity.findExternalDisplay(context))
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = targetDisplay.displayId }
            ActivityScenario.launch<BeastActivity>(Intent(context, BeastActivity::class.java)
                .setAction(BeastActivity.ACTION_ENSURE_WORKSPACE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle()).use { scenario ->
                fun await(message: String, predicate: () -> Boolean) {
                    val deadline = SystemClock.uptimeMillis() + 15_000
                    var ready = false
                    while (!ready && SystemClock.uptimeMillis() < deadline) {
                        instrumentation.runOnMainSync { ready = predicate() }
                        if (!ready) Thread.sleep(50)
                    }
                    assertTrue(message, ready)
                }
                val runtime = (context.applicationContext as RuntimeOwner).runtime
                await("Widgets loaded") { runtime.widgets.specs.count { it.program != null } >= 3 }
                lateinit var state: LauncherState
                lateinit var controller: InputController
                lateinit var pad: TrackpadView
                val events = mutableListOf<TrackpadEvent>()
                scenario.onActivity { activity ->
                    assertEquals(targetDisplay.displayId, activity.display?.displayId)
                    state = field(field<OmaDesktop>(activity, "omaDesktop"), "state")
                    controller = InputController {}.apply {
                        sensitivity = 1f
                        setDisplay(targetDisplay)
                        handleEvent(TrackpadEvent.Move(1780f - cursorX, 550f - cursorY))
                    }
                    // Attached for real Handler timing, invisible so it never covers home.
                    pad = TrackpadView(activity).apply {
                        visibility = View.INVISIBLE
                        onMouseEvent = { events += it; controller.handleEvent(it) }
                    }
                    field<FrameLayout>(activity, "root").addView(pad, FrameLayout.LayoutParams(800, 500))
                }
                instrumentation.waitForIdleSync()
                Thread.sleep(500)
                val hold = ViewConfiguration.getLongPressTimeout().toLong()
                fun padEvent(action: Int) = scenario.onActivity {
                    val now = SystemClock.uptimeMillis()
                    val event = MotionEvent.obtain(now, now, action, 400f, 250f, 0)
                    try { pad.onTouchEvent(event) } finally { event.recycle() }
                }
                fun dismiss() {
                    scenario.onActivity { activity ->
                        activity.javaClass.getDeclaredMethod("hideLauncher").apply { isAccessible = true }.invoke(activity)
                    }
                    instrumentation.waitForIdleSync()
                    Thread.sleep(200)
                }

                padEvent(MotionEvent.ACTION_DOWN)
                padEvent(MotionEvent.ACTION_UP)
                Thread.sleep(50)
                padEvent(MotionEvent.ACTION_DOWN)
                Thread.sleep(hold + 100)
                padEvent(MotionEvent.ACTION_UP)
                await("Tap then tap-and-hold opens Omalauncher home menu") { state.homeMenuOpen }
                scenario.onActivity { assertEquals(1, events.count { it == TrackpadEvent.RightClick }) }
                Thread.sleep(hold + 200)
                dismiss()

                // Raw USB/Bluetooth mouse path, through the real controller and router.
                scenario.onActivity { controller.handleMouseButton(273, true) }
                Thread.sleep(40)
                scenario.onActivity { controller.handleMouseButton(273, false) }
                await("Captured external mouse right-click opens home menu") { state.homeMenuOpen }
                Thread.sleep(hold + 200)
                dismiss()

                // Android's native external-display mouse path, through InputDispatcher.
                val mouseDownAt = SystemClock.uptimeMillis()
                fun mouse(action: Int, buttons: Int) {
                    val properties = arrayOf(MotionEvent.PointerProperties().apply {
                        id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE
                    })
                    val coordinates = arrayOf(MotionEvent.PointerCoords().apply { x = 1780f; y = 550f })
                    val event = MotionEvent.obtain(mouseDownAt, SystemClock.uptimeMillis(), action,
                        1, properties, coordinates, 0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
                    MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                        .invoke(event, targetDisplay.displayId)
                    if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
                        MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType)
                            .invoke(event, MotionEvent.BUTTON_SECONDARY)
                    }
                    try { assertTrue("Inject external mouse event", instrumentation.uiAutomation.injectInputEvent(event, true)) }
                    finally { event.recycle() }
                }
                mouse(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_SECONDARY)
                mouse(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_SECONDARY)
                mouse(MotionEvent.ACTION_BUTTON_RELEASE, 0)
                mouse(MotionEvent.ACTION_UP, 0)
                await("Native external mouse right-click opens home menu") { state.homeMenuOpen }
                Thread.sleep(hold + 200)
                dismiss()

                scenario.onActivity {
                    controller.handleEvent(TrackpadEvent.Move(1250f - controller.cursorX, 300f - controller.cursorY))
                    controller.handleMouseButton(273, true)
                    controller.handleMouseButton(273, false)
                }
                await("Right-click on a widget enters its edit mode") { state.editing != null }
                Thread.sleep(hold + 200)
                dismiss()
                scenario.onActivity {
                    controller.handleEvent(TrackpadEvent.Move(1780f - controller.cursorX, 550f - controller.cursorY))
                }

                scenario.onActivity { activity ->
                    activity.routeAppPointer(targetDisplay.displayId, 1780, 550, MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_SECONDARY)
                    activity.routeAppPointer(targetDisplay.displayId, 1780, 550, MotionEvent.ACTION_CANCEL, MotionEvent.BUTTON_SECONDARY, 0)
                }
                Thread.sleep(hold + 200)
                scenario.onActivity { assertFalse("Cancelled click cannot open menu", state.homeMenuOpen) }
                scenario.onActivity { activity ->
                    activity.routeAppPointer(targetDisplay.displayId, 1780, 550, MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_SECONDARY)
                    activity.routeAppPointer(targetDisplay.displayId, 1600, 550, MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_SECONDARY)
                    activity.routeAppPointer(targetDisplay.displayId, 1600, 550, MotionEvent.ACTION_UP, MotionEvent.BUTTON_SECONDARY, 0)
                }
                Thread.sleep(hold + 200)
                scenario.onActivity { assertFalse("Right-button drag cannot open menu", state.homeMenuOpen) }
                scenario.onActivity { activity ->
                    activity.routeAppClick(targetDisplay.displayId, 1780, 550, MotionEvent.BUTTON_SECONDARY)
                    activity.javaClass.getDeclaredMethod("hideLauncher").apply { isAccessible = true }.invoke(activity)
                }
                Thread.sleep(hold + 200)
                scenario.onActivity { assertFalse("Dismissal cancels an in-flight synthetic hold", state.homeMenuOpen) }
            }
        } finally {
            display.release()
            reader.close()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
