package com.lateral

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lateral.beast.BeastTask
import com.lateral.beast.WorkspaceState
import org.junit.Test
import java.io.File

/** Captures native PhoneUI with explicitly named fixture windows for the walkthrough. */
class PhoneWindowsWalkthroughAndroidTest {
    @Test fun captureWalkthrough() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val autoStart = DesktopShellSettings.autoStart(context)
        DesktopShellSettings.setAutoStart(context, false)
        val fixtures = mutableListOf<BeastTask>()
        var previous = 1
        var sync: (() -> Unit)? = null
        var dialog: AlertDialog? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun ui(action: () -> Unit) = instrumentation.runOnMainSync(action)
                fun click(text: String) = ui {
                    descendants(dialog!!.window!!.decorView).filterIsInstance<Button>()
                        .first { it.text.toString() == text }.performClick()
                }
                fun capture(name: String, bottom: Boolean = false) {
                    instrumentation.waitForIdleSync()
                    ui {
                        val root = dialog!!.window!!.decorView
                        if (bottom) descendants(root).filterIsInstance<ScrollView>().first().fullScroll(View.FOCUS_DOWN)
                    }
                    instrumentation.waitForIdleSync()
                    ui {
                        val root = dialog!!.window!!.decorView
                        check(root.width > 0 && root.height > 0)
                        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                        root.draw(Canvas(bitmap))
                        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                }
                scenario.onActivity { activity ->
                    WorkspaceState.init(context)
                    previous = WorkspaceState.activeWorkspace
                    sync = WorkspaceState.onImmediateSyncRequested
                    WorkspaceState.onImmediateSyncRequested = null
                    val free = (1..10).first { n -> WorkspaceState.tasks.none { it.workspace == n } }
                    WorkspaceState.switchWorkspace(free)
                    fixtures += WorkspaceState.launchNew("fixture.files", "Fixture", "Files (demo)")
                    fixtures += WorkspaceState.launchNew("fixture.browser", "Fixture", "Browser (demo)")
                    fixtures.forEach { WorkspaceState.focus(it.id) }
                    WorkspaceState.focus(fixtures.first().id)
                    dialog = PhoneWindowsPanel.show(activity)
                }
                capture("windows-01-overview")
                click("Move to…")
                capture("windows-02-workspaces")
                click("Cancel move")
                click("Float")
                capture("windows-03-floating", bottom = true)
                click("Put on shelf")
                click("Shelf (1)")
                capture("windows-04-shelf", bottom = true)
                ui { dialog?.dismiss() }
            }
        } finally {
            instrumentation.runOnMainSync {
                dialog?.dismiss()
                fixtures.forEach { WorkspaceState.removeClosed(it.id) }
                WorkspaceState.switchWorkspace(previous)
                WorkspaceState.onImmediateSyncRequested = sync
            }
            DesktopShellSettings.setAutoStart(context, autoStart)
        }
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
