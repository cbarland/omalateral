package com.lateral.beast

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class CompanionFallbackAndroidTest {
    @Test fun installationIsNeverMistakenForConnection() {
        assertEquals(OmalauncherPresence.ABSENT, OmalauncherPresence.classify(null))
        assertEquals(OmalauncherPresence.DISABLED, OmalauncherPresence.classify(false))
        assertEquals(OmalauncherPresence.BRIDGE_REQUIRED, OmalauncherPresence.classify(true))
    }

    @Test fun fallbackUsesEmbeddedDesktopAndKeepsHomeMounted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
        val reader = ImageReader.newInstance(1920, 1080, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .createVirtualDisplay("omalateral-fallback-test", 1920, 1080, 160, reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or (1 shl 10))
        try {
            val app = context.applicationContext
            val lazyRuntime = app.javaClass.getDeclaredField("runtime\$delegate").apply { isAccessible = true }.get(app) as Lazy<*>
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = checkNotNull(BeastActivity.findExternalDisplay(context)).displayId }
            ActivityScenario.launch<BeastActivity>(Intent(context, BeastActivity::class.java)
                .setAction(BeastActivity.ACTION_ENSURE_WORKSPACE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle()).use { scenario ->
                scenario.onActivity { activity ->
                    fun field(name: String) = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)
                    assertTrue("Embedded desktop is the production fallback", field("omaDesktop") is OmaDesktop)
                    assertTrue(lazyRuntime.isInitialized())
                    assertEquals(false, field("sharedDesktop"))
                    assertEquals(OmalauncherPresence.detect(context), field("omalauncherPresence"))
                    val toolbar = field("toolbar") as View
                    fun labels(view: View): List<String> = when (view) {
                        is TextView -> listOf(view.text.toString())
                        is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
                        else -> emptyList()
                    }
                    val text = labels(toolbar)
                    assertTrue(text.contains("+APPS"))
                    assertTrue(text.contains("WIDGETS"))
                    assertFalse(text.contains("DESKTOP"))
                    assertTrue(text.contains("MIN ALL"))
                    assertTrue(text.contains("DISPLAY"))
                    assertFalse(text.contains("→DISPLAY"))
                    assertTrue(text.contains("THEMES"))
                    assertTrue(text.contains("OMA: LOCAL"))
                    val controls = (toolbar as ViewGroup).getChildAt(0)
                    fun refreshToolbar() = activity.javaClass.getDeclaredMethod("renderToolbar")
                        .apply { isAccessible = true }.invoke(activity)
                    refreshToolbar()
                    assertSame("Task refreshes preserve measured toolbar controls", controls, toolbar.getChildAt(0))
                    val metrics = activity.resources.displayMetrics
                    val originalDensity = metrics.densityDpi
                    try {
                        // Simulate a density configuration update without changing the emulator.
                        metrics.densityDpi = originalDensity + 1
                        refreshToolbar()
                        assertNotSame("Density changes invalidate toolbar geometry", controls, toolbar.getChildAt(0))
                    } finally {
                        metrics.densityDpi = originalDensity
                        refreshToolbar()
                    }
                    val launcher = field("launcher") as View
                    assertEquals(View.VISIBLE, launcher.visibility)
                    activity.javaClass.getDeclaredMethod("setLauncherActive", Boolean::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(activity, true)
                    assertEquals(View.VISIBLE, launcher.visibility)
                    activity.javaClass.getDeclaredMethod("hideLauncher").apply { isAccessible = true }.invoke(activity)
                    assertEquals(View.VISIBLE, launcher.visibility)
                    assertEquals(0f, launcher.translationZ)
                    // Dismiss before Compose consumes the search request: it must not
                    // refocus the prompt and reopen the drawer on a later frame.
                    val desktop = field("omaDesktop") as OmaDesktop
                    val state = desktop.javaClass.getDeclaredField("state").apply { isAccessible = true }
                        .get(desktop) as dev.omalauncher.ui.LauncherState
                    desktop.search("", false)
                    assertNotNull(state.promptRequest)
                    desktop.dismissPanels()
                    assertNull("Dismiss cancels pending prompt activation", state.promptRequest)
                }
            }
        } finally {
            display.release()
            reader.close()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
