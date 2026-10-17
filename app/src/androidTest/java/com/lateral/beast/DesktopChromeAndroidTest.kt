package com.lateral.beast

import android.app.ActivityOptions
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lateral.InputSettings
import dev.omalauncher.RuntimeOwner
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real external window validation; geometry fixtures never pretend to be running apps. */
class DesktopChromeAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun compactBarAndDecoratorsNeverOverlapAtLargeScale() = instrumentation.runOnMainSync {
        fun item(width: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(width, -1) }
        for (width in listOf(320, 600, 1280, 3840)) {
            val optional = item(150)
            val left = LinearLayout(context).apply { addView(item(90)); addView(optional) }
            val right = LinearLayout(context).apply { addView(item(90)) }
            val center = TextView(context).apply { text = "12:00  OCT 12  100%"; textSize = 20f }
            val bar = DesktopBarLayout(context, left, center, right, listOf(optional))
            layout(bar, width, 60)
            assertTrue("Controls overlap at $width", left.right <= right.left)
            if (center.visibility == View.VISIBLE) {
                assertEquals(width / 2f, (center.left + center.right) / 2f, 1f)
                assertTrue(center.left >= left.right)
                assertTrue(center.right <= right.left)
            }
        }
        for (scale in listOf(.85f, 1f, 1.5f, 2f)) for (width in listOf(360, 600, 1100)) {
            val gate = BeastControlDebouncer().apply {
                // Layout-only fixtures have no real attachment delay.
                javaClass.getDeclaredField("blockedUntil").apply { isAccessible = true }.setLong(this, 0L)
            }
            val card = BeastTaskCard(context, gate).apply { uiScale = scale; fontScale = scale; layoutDirection = View.LAYOUT_DIRECTION_LTR }
            try {
                val task = BeastTask(-999, "fixture", "Fixture", "A very long window title", "Fixture",
                    fullscreenReturnMode = PresentationMode.PHONE)
                card.bind(task, true)
                val density = context.resources.displayMetrics.density
                repeat(3) { layout(card, (width * density).toInt(), (700 * density).toInt()) }
                val chrome = field<ViewGroup>(card, "chrome")
                val title = field<View>(card, "noteNavigation")
                val left = field<View>(card, "leftControls")
                val right = field<View>(card, "rightControls")
                assertTrue("Controls collide at $width / $scale", left.right <= right.left)
                if (title.visibility == View.VISIBLE) {
                    assertEquals(chrome.width / 2f, (title.left + title.right) / 2f, 1f)
                    assertTrue("Title starts ${title.left} before left group ${left.right} at $width / $scale", title.left >= left.right)
                    assertTrue("Title ends ${title.right} after right group ${right.left} at $width / $scale", title.right <= right.left)
                }
                val resizeHandle = field<View>(card, "resizeHandle")
                assertEquals(View.GONE, resizeHandle.visibility)
                task.floating = true
                card.bind(task, true)
                assertEquals(View.VISIBLE, resizeHandle.visibility)
                card.setFullscreen(true)
                assertEquals(View.GONE, resizeHandle.visibility)
                val exit = field<TextView>(card, "fullscreenExit")
                assertEquals("EXIT  P", exit.text.toString())
                assertEquals(View.VISIBLE, exit.visibility)
                assertEquals(View.GONE, chrome.visibility)
                var restored = false
                card.onFullscreen = { restored = true }
                exit.performClick()
                assertTrue("Fullscreen escape remains actionable", restored)
            } finally { card.release() }
        }
    }

    @Test fun externalHomeThemesMenusAndFullscreenStayIntegrated() {
        lateinit var runtime: dev.omalauncher.Runtime
        onMain { runtime = (context.applicationContext as RuntimeOwner).runtime }
        val previousTheme = runtime.themes.picked
        val previousPalette = InputSettings.theme
        val display = checkNotNull(BeastActivity.findExternalDisplay(context))
        val options = ActivityOptions.makeBasic().apply { launchDisplayId = display.displayId }
        var fixture: BeastTask? = null
        val scenario = ActivityScenario.launch<BeastActivity>(Intent(context, BeastActivity::class.java)
            .setAction(BeastActivity.ACTION_ENSURE_WORKSPACE).putExtra("embeddedDesktop", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle())
        try {
            lateinit var activity: BeastActivity
            scenario.onActivity { activity = it }
            await("External desktop and OWL widgets") {
                activity.window.decorView.width > 0 && runtime.widgets.specs.count { it.program != null } >= 3
            }
            for (theme in listOf("catppuccin", "catppuccin-latte")) {
                onMain { runtime.themes.pick(runtime.themes.bundled.first { it.name == theme }) }
                await("Palette synchronized: $theme") { InputSettings.theme.id == theme }
                Thread.sleep(400)
                capture(activity, "home-$theme")
                onMain {
                    val bar = field<View>(activity, "toolbar")
                    checkNotNull(find(bar, "●")).performClick()
                }
                await("Workspace menu") { field<Dialog?>(activity, "workspaceMenu")?.isShowing == true }
                Thread.sleep(200)
                onMain {
                    val dialog = checkNotNull(field<Dialog?>(activity, "workspaceMenu"))
                    assertNotNull(find(dialog.window!!.decorView, "CAPTURE"))
                    assertNotNull(find(dialog.window!!.decorView, "THEMES"))
                    val capture = checkNotNull(find(dialog.window!!.decorView, "CAPTURE"))
                    updateBeastHover(capture.parent as View, true)
                    assertEquals(InputSettings.theme.brightForeground, capture.currentTextColor)
                }
                capture(activity, "menu-$theme", fieldOnMain(activity, "workspaceMenu"))
                onMain { field<Dialog>(activity, "workspaceMenu").dismiss() }
            }
            // A community override may keep the same theme ID while changing colors.
            val palette = dev.omalauncher.ui.Palette.current
            onMain { dev.omalauncher.ui.Palette.apply(InputSettings.theme.palette() + ("accent" to "#42AABC")) }
            await("Same-ID palette override reaches workspace") { InputSettings.accentColor == android.graphics.Color.rgb(66, 170, 188) }
            onMain { dev.omalauncher.ui.Palette.restore(palette) }
            await("Original palette restored") { InputSettings.accentColor != android.graphics.Color.rgb(66, 170, 188) }
            onMain {
                fixture = WorkspaceState.launchNew("com.omalateral.test.fixture", "Fixture", "LAYOUT FIXTURE")
                fixture!!.placement = TaskPlacement.BEAST_VISIBLE
                WorkspaceState.setMode(fixture!!.id, PresentationMode.PHONE)
            }
            Thread.sleep(400)
            onMain {
                assertTrue(field<View>(activity, "launcher").isShown)
                assertEquals(View.VISIBLE, field<View>(activity, "workspaceSurface").visibility)
                WorkspaceState.setMode(fixture!!.id, PresentationMode.FULLSCREEN)
            }
            Thread.sleep(200)
            onMain {
                val layer = field<ViewGroup>(activity, "fullscreenLayer")
                assertTrue(layer.isShown)
                checkNotNull(find(layer, "EXIT  P")).performClick()
                assertEquals(PresentationMode.PHONE, fixture!!.mode)
            }
            Thread.sleep(BeastControlDebouncer.DEBOUNCE_MS + 100)
            onMain {
                checkNotNull(find(field(activity, "toolbar"), "●")).performClick()
                val menu = field<Dialog>(activity, "workspaceMenu")
                (checkNotNull(find(menu.window!!.decorView, "CLOSE ALL…")).parent as View).performClick()
                assertTrue(WorkspaceState.tasks.any { it.id == fixture!!.id })
                val confirm = field<Dialog>(activity, "workspaceMenu")
                assertNotNull(find(confirm.window!!.decorView, "CLOSE ALL WINDOWS?"))
                (checkNotNull(find(confirm.window!!.decorView, "CANCEL")).parent as View).performClick()
                assertTrue(WorkspaceState.tasks.any { it.id == fixture!!.id })
            }
        } finally {
            onMain {
                fixture?.let { WorkspaceState.removeClosed(it.id) }
                runtime.themes.choices.firstOrNull { it.name == previousTheme }?.let(runtime.themes::pick)
                InputSettings.setTheme(context, previousPalette)
            }
            scenario.close()
        }
    }

    @Test fun realAndroidWindowKeepsItsContentsAcrossPresentationChanges() {
        val bootstrap = com.lateral.privileged.UsbHelperLifecycleAndroidTest()
        org.junit.Assume.assumeFalse("Do not replace an existing helper", bootstrap.currentService()?.asBinder()?.pingBinder() == true)
        val helper = bootstrap.launch()
        var scenario: ActivityScenario<BeastActivity>? = null
        var task: BeastTask? = null
        var previousExcluded = emptySet<String>()
        val excludedField = AndroidTaskSynchronizer.javaClass.getDeclaredField("homePackages").apply { isAccessible = true }
        try {
            await("Helper ready") { com.lateral.privileged.PrivilegedService.isBridgeReady() }
            // MainActivity owns this in normal startup; this isolated test opens Beast directly.
            onMain {
                AndroidTaskSynchronizer.init(context)
                @Suppress("UNCHECKED_CAST")
                previousExcluded = excludedField.get(AndroidTaskSynchronizer) as Set<String>
                // ActivityScenario's own placeholder is not a user app. Hosting it
                // would move the test harness off display 0 and block scenario.close().
                excludedField.set(AndroidTaskSynchronizer, previousExcluded + instrumentation.context.packageName)
            }
            Thread.sleep(600)
            val display = checkNotNull(BeastActivity.findExternalDisplay(context))
            scenario = ActivityScenario.launch(Intent(context, BeastActivity::class.java)
                .setAction(BeastActivity.ACTION_ENSURE_WORKSPACE).putExtra("embeddedDesktop", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().apply { launchDisplayId = display.displayId }.toBundle())
            lateinit var activity: BeastActivity
            scenario.onActivity { activity = it }
            await("Workspace ready") { activity.window.decorView.width > 0 }
            onMain { task = WorkspaceState.launchNew("com.google.android.documentsui", "com.android.documentsui.files.FilesActivity", "Files") }
            await("Files hosted") {
                val hosted = WorkspaceState.tasks.firstOrNull { it.packageName == "com.google.android.documentsui" && it.placement == TaskPlacement.BEAST_VISIBLE }
                if (hosted != null) task = hosted
                hosted?.androidTaskId != null
            }
            Thread.sleep(1000)
            capture(activity, "android-phone-width")
            onMain { WorkspaceState.setMode(task!!.id, PresentationMode.TABLET) }
            Thread.sleep(600)
            capture(activity, "android-tablet-width")
            onMain { WorkspaceState.setMode(task!!.id, PresentationMode.FULLSCREEN) }
            Thread.sleep(600)
            onMain { checkNotNull(find(field(activity, "fullscreenLayer"), "EXIT  T")).performClick() }
            await("Returns to tablet") { task!!.mode == PresentationMode.TABLET }
            onMain { WorkspaceState.minimize(task!!.id) }
            Thread.sleep(400)
            onMain { WorkspaceState.focus(task!!.id) }
            await("Window restored") { task!!.placement == TaskPlacement.BEAST_VISIBLE }
            Thread.sleep(700)
            capture(activity, "android-restored")
        } finally {
            onMain { task?.let { AndroidTaskSynchronizer.closeTask(it) {} } }
            Thread.sleep(300)
            scenario?.close()
            helper.close()
            onMain { excludedField.set(AndroidTaskSynchronizer, previousExcluded) }
        }
    }

    private fun capture(activity: BeastActivity, name: String, dialog: Dialog? = null) {
        val done = CountDownLatch(1)
        var result = -1
        lateinit var bitmap: Bitmap
        onMain {
            val window = dialog?.window ?: activity.window
            bitmap = Bitmap.createBitmap(window!!.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(window, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        try {
            val directory = File(context.getExternalFilesDir(null), "desktop-design").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            Thread.sleep(100)
        }
        var details = ""
        onMain { details = "${com.lateral.privileged.PrivilegedService.state}: ${WorkspaceState.tasks}" }
        fail("$message — $details")
    }
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
    private fun find(view: View, label: String): TextView? {
        if (view is TextView && view.text.toString() == label) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), label)?.let { return it }
        return null
    }
    private fun fieldOnMain(instance: Any, name: String): Dialog? {
        var result: Dialog? = null
        onMain { result = field(instance, name) }
        return result
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(instance: Any, name: String): T = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) as T
}
