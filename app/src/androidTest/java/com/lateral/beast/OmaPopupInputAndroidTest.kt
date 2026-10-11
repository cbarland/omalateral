package com.lateral.beast

import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import com.lateral.DesktopInputProxyView
import com.lateral.MainActivity
import com.lateral.WorkspaceCursor
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import dev.omalauncher.RuntimeOwner
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lateral.privileged.PrivilegedService
import com.lateral.privileged.PrivilegeBackendId
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production theme-install dialog; never downloads or installs a theme. */
class OmaPopupInputAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun popupDismissalDoesNotConsumeTheNextWorkspaceGesture() = onMain {
        val windows = DesktopWindows { 3 }
        val root = android.widget.FrameLayout(instrumentation.targetContext).apply { layout(0, 0, 100, 100) }
        val release = windows.attach(root) {}
        try {
            assertTrue(windows.pointer(50, 50, MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY))
            release()
            assertTrue("Finish the old captured stream", windows.pointer(50, 50, MotionEvent.ACTION_UP, MotionEvent.BUTTON_PRIMARY))
            val releaseAgain = windows.attach(root) {}
            assertTrue(windows.pointer(50, 50, MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY))
            releaseAgain()
            assertFalse("A new gesture belongs to the workspace", windows.pointer(50, 50, MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY))
            assertFalse(windows.pointer(50, 50, MotionEvent.ACTION_UP, MotionEvent.BUTTON_PRIMARY))
        } finally { windows.close() }
    }

    @Test fun replacingEditorRejectsStaleInputAndOldCleanup() = onMain {
        val target = View(instrumentation.targetContext)
        var dismissed = 0
        val first = DesktopTextInputSession.begin({ android.view.inputmethod.BaseInputConnection(target, true) }, {})
        val stale = checkNotNull(DesktopTextInputSession.connect(EditorInfo()))
        val second = DesktopTextInputSession.begin({ android.view.inputmethod.BaseInputConnection(target, true) }, { dismissed++ })
        try {
            DesktopTextInputSession.release(first)
            assertSame(second, DesktopTextInputSession.editor)
            assertFalse(stale.commitText("late", 1))
            assertFalse(stale.setComposingText("late", 1))
            assertFalse(stale.deleteSurroundingText(1, 0))
            assertNull(stale.getTextBeforeCursor(10, 0))
            DesktopTextInputSession.close()
            assertFalse(DesktopTextInputSession.active)
            assertEquals(1, dismissed)
        } finally { DesktopTextInputSession.release(second) }
    }

    @Test fun themeDialogRoutesPointerAndPhoneKeyboardAndRevokesOldConnection() {
        val context = instrumentation.targetContext
        val display = checkNotNull(BeastActivity.findExternalDisplay(context))
        val previousBackend = PrivilegedService.selectedBackend
        PrivilegedService.selectBackend(PrivilegeBackendId.SHIZUKU)
        var phone: MainActivity? = null
        var external: BeastActivity? = null
        try {
            onMain { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            await("PhoneUI ready") {
                phone = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()
                phone != null
            }
            onMain { BeastWorkspaceController.ensure(checkNotNull(phone), display) }
            await("External desktop ready") {
                external = listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED).flatMap {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
                }.filterIsInstance<BeastActivity>().firstOrNull { !it.isFinishing && it.display?.displayId == display.displayId }
                external != null
            }
            val beast = checkNotNull(external)
            await("Embedded runtime startup") { (context.applicationContext as RuntimeOwner).runtime.widgets.specs.count { it.program != null } >= 3 }
            instrumentation.waitForIdleSync()
            Thread.sleep(700) // Initial theme synchronization may rebuild the desktop once.
            onMain {
                // This regression needs no privileged helper. Dismiss its normal setup prompt,
                // which otherwise legitimately owns the phone's modal window focus.
                field<android.app.AlertDialog?>(checkNotNull(phone), "privilegeBackendDialog")
                    ?.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.performClick()
                field<DesktopSession>(beast, "omaDesktop").themes()
            }
            await("Theme install control") { field<Boolean>(beast, "launcherActive") && nodes(beast.window.decorView).any { text(it) == "[ install ]" && it.boundsInWindow.width > 0 } }
            onMain {
                val node = nodes(beast.window.decorView).last { text(it) == "[ install ]" && it.boundsInWindow.width > 0 }
                val point = node.boundsInWindow.center
                android.util.Log.i("OmaPopupTest", "install at $point, root=${beast.window.decorView.width}x${beast.window.decorView.height}")
                assertTrue(beast.routeAppClick(display.displayId, point.x.toInt(), point.y.toInt(), MotionEvent.BUTTON_PRIMARY))
            }
            await("Theme dialog opens") { dialogRoot() != null }
            await("Theme dialog keyboard session") { DesktopTextInputSession.active }
            lateinit var dialog: View
            lateinit var connection: InputConnection
            onMain {
                dialog = checkNotNull(dialogRoot())
                val windows = field<Any>(field<Any>(beast, "omaDesktop"), "windows")
                val entries = field<List<Any>>(windows, "entries")
                assertTrue("Dialog must register a top-layer pointer", entries.isNotEmpty())
                val cursor = field<View>(entries.last(), "cursor")
                assertNotNull("Pointer lives in the dialog overlay", cursor.parent)
                assertEquals(dialog.width, cursor.width)
                assertEquals(dialog.height, cursor.height)
            }
            await("Phone keyboard endpoint") {
                WindowInspector.getGlobalWindowViews().flatMap(::views)
                    .filterIsInstance<DesktopInputProxyView>().any { it.isShown && it.hasFocus() }
            }
            await("Software keyboard is visible on the phone") {
                WindowInspector.getGlobalWindowViews().flatMap(::views)
                    .filterIsInstance<DesktopInputProxyView>().any {
                        it.isShown && it.display?.displayId == android.view.Display.DEFAULT_DISPLAY &&
                            ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    }
            }
            onMain {
                val proxy = WindowInspector.getGlobalWindowViews().flatMap(::views)
                    .filterIsInstance<DesktopInputProxyView>().first { it.isShown }
                val info = EditorInfo()
                connection = checkNotNull(proxy.onCreateInputConnection(info))
                assertTrue("Original editor input type", info.inputType != 0)
                assertTrue(connection.setComposingText("github", 1))
            }
            await("Composing text reaches external field") { editable(dialog) == "github" }
            onMain { assertTrue(connection.commitText("https://github.com/example/theme", 1)) }
            await("Committed text reaches external field") { editable(dialog) == "https://github.com/example/theme" }
            onMain { connection.deleteSurroundingText(1, 0) }
            await("Backspace reaches external field") { editable(dialog) == "https://github.com/example/them" }
            onMain {
                connection.setSelection(0, 5)
                connection.commitText("http", 1)
            }
            await("Selection replacement reaches external field") { editable(dialog) == "http://github.com/example/them" }
            lateinit var dialogWindow: Window
            onMain {
                dialogWindow = views(dialog).filterIsInstance<DialogWindowProvider>().first().window
                WorkspaceCursor.moveTo(dialog.width / 2f, dialog.height / 2f,
                    dialog.width.toFloat(), dialog.height.toFloat())
            }
            Thread.sleep(150)
            val capture = capture(dialogWindow, dialog.width, dialog.height, "omalateral-theme-dialog-pointer.png")
            val cx = capture.width / 2
            val cy = capture.height / 2
            val pointerPixels = (cx until cx + 24).sumOf { x ->
                (cy until cy + 24).count { y -> capture.getPixel(x, y) == Color.rgb(244, 243, 238) }
            }
            assertTrue("Pointer must be visible above the composed dialog", pointerPixels > 10)
            capture.recycle()
            onMain {
                val cancel = nodes(dialog).first { text(it) == "cancel" }.boundsInWindow.center
                val origin = IntArray(2)
                dialog.getLocationOnScreen(origin)
                assertTrue(beast.routeAppClick(display.displayId, origin[0] + cancel.x.toInt(),
                    origin[1] + cancel.y.toInt(), MotionEvent.BUTTON_PRIMARY))
            }
            await("Dialog dismissal releases keyboard") { dialogRoot() == null && !DesktopTextInputSession.active }
            onMain {
                assertFalse("Late keystrokes cannot target another field", connection.commitText("stale", 1))
                field<DesktopSession>(beast, "omaDesktop").dismissPanels()
            }
        } finally {
            onMain {
                DesktopTextInputSession.close()
                phone?.finish()
                external?.finish()
                PrivilegedService.selectBackend(previousBackend)
            }
        }
    }

    private fun dialogRoot() = WindowInspector.getGlobalWindowViews().firstOrNull { root ->
        nodes(root).any { text(it) == "install theme" }
    }
    private fun editable(root: View) = nodes(root).firstNotNullOfOrNull {
        it.config.getOrNull(SemanticsProperties.EditableText)?.text
    }
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
    private fun nodes(root: View) = views(root).filterIsInstance<ViewRootForTest>()
        .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) }
    private fun views(root: View): List<View> = listOf(root) +
        if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun capture(window: Window, width: Int, height: Int, name: String): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = -1
        onMain { PixelCopy.request(window, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper())) }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 12_000
        do {
            var ready = false
            onMain { ready = predicate() }
            if (ready) return
            Thread.sleep(100)
        } while (System.currentTimeMillis() < deadline)
        fail(message)
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, name: String): T = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target) as T
}
