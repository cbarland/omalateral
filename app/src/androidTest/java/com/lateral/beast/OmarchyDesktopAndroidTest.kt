package com.lateral.beast

import android.app.ActivityOptions
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lateral.DesktopShellSettings
import com.lateral.privileged.PrivilegedService
import com.lateral.privileged.UsbHelperLifecycleAndroidTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Genuine independently hosted Android tasks; fixture windows are labeled as fixtures. */
class OmarchyDesktopAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun <T> ui(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 25_000
        while (SystemClock.uptimeMillis() < deadline) { if (ui(predicate)) return; Thread.sleep(100) }
        error("Timed out: $label")
    }
    @Suppress("UNCHECKED_CAST") private fun <T> field(owner: Any, name: String): T =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
    private fun chord(activity: BeastActivity, key: Int, extra: Int = 0) = ui {
        val now = SystemClock.uptimeMillis()
        assertTrue(activity.window.callback.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0, KeyEvent.META_META_ON or extra)))
        // UP has no modifiers: the consumed chord must not leak a partial key into an app.
        assertTrue(activity.window.callback.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0)))
    }

    @Test fun tiledTasksKeepTheirIdentityAcrossWorkspacesGroupsFloatingAndMenuInput() {
        val bootstrap = UsbHelperLifecycleAndroidTest()
        val helper = if (bootstrap.currentService()?.asBinder()?.pingBinder() != true) bootstrap.launch() else null
        val tasks = mutableListOf<BeastTask>()
        var activity: BeastActivity? = null
        var previousWorkspace = 1
        var testWorkspace = 10
        var otherWorkspace = 9
        val autoStartWasEnabled = DesktopShellSettings.autoStart(context)
        // The test owns an embedded desktop. Prevent PhoneUI's reconnect watchdog
        // from opening a second, bridge-backed session during keyboard assertions.
        DesktopShellSettings.setAutoStart(context, false)
        try {
            await("Helper ready") { PrivilegedService.isBridgeReady() }
            ui { WorkspaceState.init(context); AndroidTaskSynchronizer.init(context) }
            val display = checkNotNull(BeastActivity.findExternalDisplay(context))
            activity = instrumentation.startActivitySync(Intent(context, BeastActivity::class.java)
                .setAction(BeastActivity.ACTION_ENSURE_WORKSPACE).putExtra("embeddedDesktop", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().apply { launchDisplayId = display.displayId }.toBundle()) as BeastActivity
            val host = activity
            await("Desktop window") { host.window.decorView.width > 0 }
            ui {
                previousWorkspace = WorkspaceState.activeWorkspace
                val empty = (1..10).filter { number -> WorkspaceState.tasks.none { it.workspace == number } }
                check(empty.size >= 2) { "Two unused test workspaces are required" }
                testWorkspace = empty.last(); otherWorkspace = empty[empty.lastIndex - 1]
                WorkspaceState.switchWorkspace(testWorkspace)
                tasks += WorkspaceState.launchNew(instrumentation.context.packageName, FixtureOneActivity::class.java.name, "FIXTURE ONE")
                tasks += WorkspaceState.launchNew(instrumentation.context.packageName, FixtureTwoActivity::class.java.name, "FIXTURE TWO")
                tasks += WorkspaceState.launchNew("com.google.android.documentsui", "com.android.documentsui.files.FilesActivity", "Files")
            }
            await("Three real Android tasks") { tasks.all { it.androidTaskId != null && it.androidDisplayId > 0 } }
            await("Three tiled panes") {
                val cards = field<Map<Long, BeastTaskCard>>(host, "cards")
                tasks.all { cards[it.id]?.let { card -> card.isShown && card.width > 100 && card.height > 100 } == true }
            }
            val identities = tasks.map { it.androidTaskId to it.androidDisplayId }
            ui {
                val cards = field<Map<Long, BeastTaskCard>>(host, "cards")
                val boxes = tasks.map { Rect().also { box -> cards.getValue(it.id).getGlobalVisibleRect(box) } }
                for (i in boxes.indices) for (j in i + 1 until boxes.size) assertFalse("Tiled panes overlap", Rect.intersects(boxes[i], boxes[j]))
                tasks.forEach { assertEquals(View.VISIBLE, field<View>(cards.getValue(it.id), "chrome").visibility) }
                assertTrue("Workspace fits a single viewport", field<View>(host, "workspaceSurface").width > 0)
            }
            ui {
                val viewport = field<View>(host, "workspaceSurface")
                val inset = (10 * host.resources.displayMetrics.density).toInt()
                val before = WorkspaceState.windowBounds(viewport.width, viewport.height, inset, inset)
                val cards = field<Map<Long, BeastTaskCard>>(host, "cards")
                val from = Rect(); field<View>(cards.getValue(tasks.first().id), "noteNavigation").getGlobalVisibleRect(from)
                val to = Rect(); cards.getValue(tasks[1].id).getGlobalVisibleRect(to)
                val now = SystemClock.uptimeMillis()
                assertFalse("Title dragging requires no keyboard modifier", OmarchyShortcuts.superHeld)
                assertTrue(host.routeAppPointer(display.displayId, from.centerX(), from.centerY(), MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY))
                assertTrue(host.routeAppPointer(display.displayId, to.centerX(), to.centerY(), MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY))
                assertTrue(host.routeAppPointer(display.displayId, to.centerX(), to.centerY(), MotionEvent.ACTION_UP, MotionEvent.BUTTON_PRIMARY, 0))

                assertFalse("Dragging a tile keeps it tiled", tasks.first().floating)
                val after = WorkspaceState.windowBounds(viewport.width, viewport.height, inset, inset)
                assertEquals("Dropped tile occupies the other pane", before[tasks[1].id], after[tasks.first().id])
                assertEquals(before[tasks.first().id], after[tasks[1].id])
            }
            Thread.sleep(1500) // Allow Files to finish its initial launch before the visual capture.
            capture(host, "dwindle")
            ui {
                val card = field<Map<Long, BeastTaskCard>>(host, "cards").getValue(tasks.first().id)
                val title = Rect(); field<View>(card, "noteNavigation").getGlobalVisibleRect(title)
                val x = title.centerX(); val y = title.centerY()
                assertTrue(host.routeAppPointer(display.displayId, x, y, MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY))
                assertTrue(host.routeAppPointer(display.displayId, x, y, MotionEvent.ACTION_UP, MotionEvent.BUTTON_PRIMARY, 0))
                assertFalse("A title tap leaves the pane tiled", tasks.first().floating)

                assertTrue(host.routeAppPointer(display.displayId, x, y, MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY))
                assertTrue(host.routeAppPointer(display.displayId, x + 100, y + 45, MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY))
                assertTrue(host.routeAppPointer(display.displayId, x + 100, y + 45, MotionEvent.ACTION_UP, MotionEvent.BUTTON_PRIMARY, 0))
                assertTrue("Dropping a tile away from another pane keeps it floating", tasks.first().floating)
                WorkspaceState.toggleFloating(tasks.first().id)
            }
            ui { WorkspaceState.focus(tasks.last().id) }
            val workspaceKey = if (otherWorkspace == 10) KeyEvent.KEYCODE_0 else KeyEvent.KEYCODE_1 + otherWorkspace - 1
            chord(host, workspaceKey, KeyEvent.META_SHIFT_ON)
            await("Window moved to another workspace") { tasks.last().workspace == otherWorkspace && WorkspaceState.activeWorkspace == otherWorkspace }
            ui { WorkspaceState.switchWorkspace(testWorkspace) }
            ui { WorkspaceState.focus(tasks.first().id) }
            chord(host, KeyEvent.KEYCODE_T)
            await("Floating pane") {
                val card = field<Map<Long, BeastTaskCard>>(host, "cards")[tasks.first().id]
                tasks.first().floating && card != null && card.left == (card.layoutParams as? android.widget.FrameLayout.LayoutParams)?.leftMargin
            }
            val beforeMove = tasks.first().floatingX
            ui {
                val card = field<Map<Long, BeastTaskCard>>(host, "cards").getValue(tasks.first().id)
                val bounds = Rect(); card.getGlobalVisibleRect(bounds)
                val delta = if (beforeMove > .5f) -100 else 100
                val now = SystemClock.uptimeMillis()
                OmarchyShortcuts.dispatch(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_META_LEFT, 0, KeyEvent.META_META_ON))
                assertTrue(host.routeAppPointer(display.displayId, bounds.centerX(), bounds.centerY(), MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY))
                assertTrue(host.routeAppPointer(display.displayId, bounds.centerX()+delta, bounds.centerY()+40, MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY))
                assertTrue(host.routeAppPointer(display.displayId, bounds.centerX()+delta, bounds.centerY()+40, MotionEvent.ACTION_UP, MotionEvent.BUTTON_PRIMARY, 0))
                OmarchyShortcuts.dispatch(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_META_LEFT, 0))
                assertTrue("Super-drag moves the floating window", kotlin.math.abs(tasks.first().floatingX - beforeMove) > .01f)
            }
            chord(host, KeyEvent.KEYCODE_O)
            ui { WorkspaceState.switchWorkspace(otherWorkspace) }
            await("Pinned pane follows workspace") { field<Map<Long, BeastTaskCard>>(host, "cards")[tasks.first().id]?.isShown == true }
            ui { WorkspaceState.focus(tasks.first().id) }
            chord(host, KeyEvent.KEYCODE_O)
            ui { WorkspaceState.moveToWorkspace(tasks.last().id, testWorkspace); WorkspaceState.focus(tasks[1].id) }
            chord(host, KeyEvent.KEYCODE_G)
            val groupTarget = ui {
                val geometry = WorkspaceState.windowBounds(3000, 1000, 10, 10)
                val direction = WindowDirection.entries.first { directionalWindow(geometry, tasks[1].id, it) != null }
                val target = checkNotNull(directionalWindow(geometry, tasks[1].id, direction))
                WorkspaceState.joinDirectionalGroup(direction, geometry)
                assertEquals(tasks.first { it.id == target }.group, tasks[1].group)
                target
            }
            await("Inactive grouped window is detached") {
                field<Map<Long, BeastTaskCard>>(host, "cards")[groupTarget]?.isShown == false
            }
            chord(host, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_CTRL_ON)
            assertEquals(groupTarget, ui { WorkspaceState.focusedTaskId })
            await("Previously focused group member is detached") {
                field<Map<Long, BeastTaskCard>>(host, "cards")[tasks[1].id]?.isShown == false
            }
            await("Group title tabs") { field<View>(field<Map<Long, BeastTaskCard>>(host, "cards").getValue(groupTarget), "groupBar").isShown }
            ui {
                WorkspaceState.toggleFloating(groupTarget)
                WorkspaceState.moveFloating(groupTarget, .05f, .05f)
                assertEquals(tasks[1].floatingX, tasks.first { it.id == groupTarget }.floatingX, .001f)
                assertEquals(tasks[1].floatingY, tasks.first { it.id == groupTarget }.floatingY, .001f)
                WorkspaceState.toggleFloating(groupTarget)
            }
            ui { WorkspaceState.toggleGroup(tasks[1].id); WorkspaceState.focus(tasks.last().id) }
            chord(host, KeyEvent.KEYCODE_F)
            await("Fullscreen") { tasks.last().mode == PresentationMode.FULLSCREEN }
            chord(host, KeyEvent.KEYCODE_F)
            await("Restored tiled mode") { tasks.last().mode != PresentationMode.FULLSCREEN }
            ui { WorkspaceState.focus(tasks[1].id) }
            chord(host, KeyEvent.KEYCODE_S, KeyEvent.META_ALT_ON)
            assertFalse(ui { WorkspaceState.visibleTasks().any { it.id == tasks[1].id } })
            chord(host, KeyEvent.KEYCODE_S)
            await("Scratchpad appears") { field<Map<Long, BeastTaskCard>>(host, "cards")[tasks[1].id]?.isShown == true }
            chord(host, KeyEvent.KEYCODE_SPACE)
            await("Omarchy menu") { field<Dialog?>(host, "workspaceMenu")?.isShowing == true }
            ui {
                val menu = checkNotNull(field<Dialog?>(host, "workspaceMenu"))
                fun editor(view: View): EditText? = if (view is EditText) view else if (view is ViewGroup)
                    (0 until view.childCount).firstNotNullOfOrNull { editor(view.getChildAt(it)) } else null
                val search = checkNotNull(editor(menu.window!!.decorView))
                search.performClick()
                val connection = checkNotNull(DesktopTextInputSession.connect(EditorInfo()))
                assertTrue(connection.commitText("workspace layout", 1))
                assertEquals("workspace layout", search.text.toString())
                connection.closeConnection()
            }
            capture(host, "menu", ui { field<Dialog>(host, "workspaceMenu") })
            ui { field<Dialog>(host, "workspaceMenu").dismiss() }
            chord(host, KeyEvent.KEYCODE_A)
            await("Omalauncher search opens") { LauncherSearchSession.active }
            await("FlorisBoard follows the phone search editor") {
                val phone = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<com.lateral.MainActivity>().firstOrNull()
                phone != null && field<View>(phone, "embeddedKeyboardView").isShown &&
                    dev.patrickgold.florisboard.embedded.EmbeddedImeRuntime.editorSession.value?.packageName == context.packageName
            }
            ui { LauncherSearchSession.close() }
            ui { field<DesktopSession>(host, "omaDesktop").widgets() }
            await("Omalauncher widget search is visible") {
                widgetSearch(host) != null
            }
            ui {
                val point = checkNotNull(widgetSearch(host)).boundsInWindow.center
                assertTrue(host.routeAppClick(display.displayId, point.x.toInt(), point.y.toInt(), MotionEvent.BUTTON_PRIMARY))
            }
            await("Widget pane owns the phone editor") { DesktopTextInputSession.active }
            await("FlorisBoard follows widget search") {
                val phone = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<com.lateral.MainActivity>().firstOrNull()
                phone != null && field<View>(phone, "embeddedKeyboardView").isShown &&
                    dev.patrickgold.florisboard.embedded.EmbeddedImeRuntime.editorSession.value?.packageName == context.packageName
            }
            ui { DesktopTextInputSession.close(); field<DesktopSession>(host, "omaDesktop").dismissPanels() }
            assertEquals(identities, ui { tasks.map { it.androidTaskId to it.androidDisplayId } })
        } finally {
            ui {
                tasks.forEach { task -> AndroidTaskSynchronizer.closeTask(task) {} }
                if (WorkspaceState.scratchpadVisible) WorkspaceState.toggleScratchpad()
                WorkspaceState.switchWorkspace(testWorkspace)
                WorkspaceState.switchWorkspace(previousWorkspace)
                activity?.finish()
            }
            helper?.close()
            DesktopShellSettings.setAutoStart(context, autoStartWasEnabled)
        }
    }

    private fun widgetSearch(activity: BeastActivity) = views(activity.window.decorView)
        .filterIsInstance<ViewRootForTest>()
        .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) }
        .firstOrNull { it.config.getOrNull(SemanticsProperties.EditableText) != null }

    private fun views(root: View): List<View> = listOf(root) +
        if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    private fun capture(activity: BeastActivity, name: String, dialog: Dialog? = null) {
        Thread.sleep(250)
        val window = dialog?.window ?: activity.window
        lateinit var bitmap: Bitmap
        val done = CountDownLatch(1); var result = -1
        ui {
            bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(window, bitmap, { status -> result = status; done.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue(done.await(8, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
        val directory = File(context.getExternalFilesDir(null), "test-captures/omarchy").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
