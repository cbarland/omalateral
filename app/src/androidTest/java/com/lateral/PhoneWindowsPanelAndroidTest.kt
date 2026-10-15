package com.lateral

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lateral.beast.PresentationMode
import com.lateral.beast.WorkspaceState
import org.junit.Assert.*
import org.junit.Test

class PhoneWindowsPanelAndroidTest {
    @Test fun touchControlsArrangeMoveAndRestoreAWindowWithoutKeyboardEvents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val autoStart = DesktopShellSettings.autoStart(context)
        DesktopShellSettings.setAutoStart(context, false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var dismissed = false
                var phoneDisplayId = 0
                scenario.onActivity { activity ->
                    phoneDisplayId = activity.display!!.displayId
                    WorkspaceState.init(context)
                    val previous = WorkspaceState.activeWorkspace
                    val empty = (1..10).filter { number -> WorkspaceState.tasks.none { it.workspace == number } }
                    check(empty.size >= 2)
                    val sync = WorkspaceState.onImmediateSyncRequested
                    WorkspaceState.onImmediateSyncRequested = null
                    WorkspaceState.switchWorkspace(empty.last())
                    val task = WorkspaceState.launchNew("fixture.phone.windows", "Fixture", "Touch fixture")
                    WorkspaceState.focus(task.id)
                    var panel: AlertDialog? = null
                    try {
                        panel = PhoneWindowsPanel.show(activity) { dismissed = true }
                        val root = checkNotNull(panel).window!!.decorView
                        fun click(text: String) {
                            val buttons = descendants(root).filterIsInstance<Button>().toList()
                            val button = buttons.firstOrNull { it.text.toString() == text }
                                ?: error("Missing '$text'; visible controls: ${buttons.map { it.text }}")
                            assertTrue(button.performClick())
                        }
                        assertTrue(TransientPanelCoordinator.isActiveOnDisplay(activity.display!!.displayId))
                        click("Float")
                        assertTrue(task.floating)
                        val width = task.floatingWidth
                        click("Wider")
                        assertTrue(task.floatingWidth > width)
                        val x = task.floatingX
                        click("Move →")
                        assertTrue(task.floatingX > x)
                        click("Fullscreen")
                        assertEquals(PresentationMode.FULLSCREEN, task.mode)
                        click("Exit fullscreen")
                        assertEquals(PresentationMode.TABLET, task.mode)
                        click("Move to…")
                        val target = empty[empty.lastIndex - 1]
                        click("$target\n(0)")
                        assertEquals(target, task.workspace)
                        assertEquals(target, WorkspaceState.activeWorkspace)
                        click("Hide window")
                        assertTrue(task.minimized)
                        click("✓ Touch fixture · hidden")
                        assertFalse(task.minimized)
                        click("Put on shelf")
                        assertEquals(0, task.workspace)
                        click("Shelf (${WorkspaceState.tasks.count { it.workspace == 0 }})")
                        click("Return from shelf")
                        assertEquals(target, task.workspace)
                        assertNull(task.androidTaskId)
                        click("Done")
                    } finally {
                        panel?.dismiss()
                        WorkspaceState.removeClosed(task.id)
                        WorkspaceState.switchWorkspace(previous)
                        WorkspaceState.onImmediateSyncRequested = sync
                    }
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertTrue(dismissed)
                assertFalse(TransientPanelCoordinator.isActiveOnDisplay(phoneDisplayId))
            }
        } finally { DesktopShellSettings.setAutoStart(context, autoStart) }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
