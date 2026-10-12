package com.lateral.beast

import android.content.Context
import android.content.Intent
import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lateral.InputSettings
import dev.omalauncher.RuntimeOwner
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Captures the actual external window, without the emulator's translucent overlay display. */
class OmaDesktopAndroidTest {
    @Test fun externalDesktopLoadsRealWidgetsAndSynchronizesTheme() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
        val reader = ImageReader.newInstance(1920, 1080, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = manager.createVirtualDisplay("omalateral-test", 1920, 1080, 160, reader.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or (1 shl 10))
        try {
            val targetDisplay = checkNotNull(BeastActivity.findExternalDisplay(context))
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = targetDisplay.displayId }
            ActivityScenario.launch<BeastActivity>(Intent(context, BeastActivity::class.java)
                .setAction(BeastActivity.ACTION_ENSURE_WORKSPACE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle()).use { scenario ->
                val runtime = (context.applicationContext as RuntimeOwner).runtime
                val deadline = System.currentTimeMillis() + 20_000
                var ready = false
                while (!ready && System.currentTimeMillis() < deadline) {
                    instrumentation.runOnMainSync { ready = runtime.widgets.specs.count { it.program != null } >= 3 }
                    if (!ready) Thread.sleep(200)
                }
                assertTrue("Bundled OWL widgets must compile and load", ready)
                scenario.onActivity { activity ->
                    assertEquals(targetDisplay.displayId, activity.display?.displayId)
                    runtime.themes.pick(runtime.themes.bundled.first { it.name == "catppuccin" })
                }
                instrumentation.waitForIdleSync()
                Thread.sleep(1000)
                scenario.onActivity { assertEquals("catppuccin", InputSettings.theme.id) }
                val copied = CountDownLatch(1)
                var result = -1
                var captureWidth = 0
                var captureHeight = 0
                scenario.onActivity { activity ->
                    captureWidth = activity.window.decorView.width
                    captureHeight = activity.window.decorView.height
                }
                val image = Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)
                scenario.onActivity { activity ->
                    PixelCopy.request(activity.window, image, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
                }
                assertTrue(copied.await(10, TimeUnit.SECONDS))
                assertEquals("External window capture", PixelCopy.SUCCESS, result)
                File(context.getExternalFilesDir(null), "omalateral-external.png").outputStream().use {
                    assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                image.recycle()
                lateinit var desktop: View
                lateinit var task: BeastTask
                scenario.onActivity { activity ->
                    desktop = field(activity, "launcher")
                    assertTrue("Home is visible by default", desktop.isShown)
                    assertEquals(0f, desktop.translationZ)
                    task = WorkspaceState.launchNew("com.omalateral.test.fixture", "Fixture", "TEST TILE")
                    task.placement = TaskPlacement.BEAST_VISIBLE
                    WorkspaceState.setMode(task.id, PresentationMode.PHONE)
                }
                instrumentation.waitForIdleSync()
                Thread.sleep(500)
                val tileCopied = CountDownLatch(1)
                var tileResult = -1
                val tileImage = Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)
                scenario.onActivity { activity ->
                    PixelCopy.request(activity.window, tileImage, { tileResult = it; tileCopied.countDown() }, Handler(Looper.getMainLooper()))
                }
                assertTrue(tileCopied.await(10, TimeUnit.SECONDS))
                assertEquals(PixelCopy.SUCCESS, tileResult)
                File(context.getExternalFilesDir(null), "omalateral-desktop-tiles.png").outputStream().use {
                    assertTrue(tileImage.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                tileImage.recycle()
                scenario.onActivity { activity ->
                    val strip = field<ViewGroup>(activity, "taskStrip")
                    assertEquals("App tile remains above desktop", 1, strip.childCount)
                    assertTrue(strip.getChildAt(0).isShown)
                    assertSame(desktop, field<View>(activity, "launcher"))
                    assertTrue(desktop.isShown)
                    val layer = desktop.parent as ViewGroup
                    assertTrue(layer.indexOfChild(desktop) < layer.indexOfChild(field<View>(activity, "fullscreenLayer")))
                    field<OmaDesktop>(activity, "omaDesktop").widgets()
                }
                instrumentation.waitForIdleSync()
                Thread.sleep(300)
                scenario.onActivity { activity ->
                    assertEquals("Launcher panels rise above app tiles", 1f, desktop.translationZ)
                    activity.javaClass.getDeclaredMethod("hideLauncher").apply { isAccessible = true }.invoke(activity)
                    WorkspaceState.setMode(task.id, PresentationMode.FULLSCREEN)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertTrue(field<View>(activity, "fullscreenLayer").isShown)
                    assertSame(desktop, field<View>(activity, "launcher"))
                    WorkspaceState.setMode(task.id, PresentationMode.PHONE)
                    assertTrue(checkNotNull(findLabel(field(activity, "toolbar"), "MIN ALL")).performClick())
                    assertEquals(TaskPlacement.BEAST_MINIMIZED, task.placement)
                    assertEquals(0, field<ViewGroup>(activity, "taskStrip").childCount)
                    assertTrue(desktop.isShown)
                    assertSame(desktop, field<View>(activity, "launcher"))
                    WorkspaceState.removeClosed(task.id)
                }
            }
        } finally {
            display.release()
            reader.close()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun findLabel(root: View, text: String): android.widget.TextView? {
        if (root is android.widget.TextView && root.text.toString() == text) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            findLabel(root.getChildAt(i), text)?.let { return it }
        }
        return null
    }

    private fun <T> field(activity: BeastActivity, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T
    }

    @Test fun exposedDesktopReceivesInputButWindowGesturesNeverClickThrough() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            var desktopDowns = 0
            var tileDowns = 0
            val root = FrameLayout(context)
            val desktop = object : View(context) {
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) desktopDowns++
                    return true
                }
            }
            val tile = object : View(context) {
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) tileDowns++
                    return true
                }
            }
            val strip = DesktopWorkspaceScrollView(context) { x, _ -> x < 300 }
            val content = FrameLayout(context).apply { addView(tile, FrameLayout.LayoutParams(300, 600)) }
            strip.addView(content, FrameLayout.LayoutParams(1000, 600))
            root.addView(desktop, FrameLayout.LayoutParams(1000, 600))
            root.addView(strip, FrameLayout.LayoutParams(1000, 600))
            root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1000, 600)
            fun event(action: Int, x: Float) {
                val event = MotionEvent.obtain(0, 0, action, x, 200f, 0)
                try { root.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            event(MotionEvent.ACTION_DOWN, 700f)
            event(MotionEvent.ACTION_UP, 700f)
            assertEquals(1, desktopDowns)
            assertEquals(0, tileDowns)
            event(MotionEvent.ACTION_DOWN, 100f)
            event(MotionEvent.ACTION_MOVE, 700f)
            event(MotionEvent.ACTION_UP, 700f)
            assertEquals("A tile gesture cannot fall through into desktop", 1, desktopDowns)
            assertEquals(1, tileDowns)
            event(MotionEvent.ACTION_DOWN, 700f)
            event(MotionEvent.ACTION_UP, 700f)
            assertEquals("Next gesture in the gap reaches desktop", 2, desktopDowns)
        }
    }
}
