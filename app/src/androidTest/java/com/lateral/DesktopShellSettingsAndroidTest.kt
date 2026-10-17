package com.lateral

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lateral.beast.BeastActivity
import com.lateral.beast.BeastWorkspaceController
import org.junit.Assert.*
import org.junit.Test

class DesktopShellSettingsAndroidTest {
    @Test fun connectingExternalDisplayOpensWorkspaceAutomatically() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val previous = DesktopShellSettings.autoStart(context)
        val images = ImageReader.newInstance(1920, 1080, PixelFormat.RGBA_8888, 2)
        var display: android.hardware.display.VirtualDisplay? = null
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
        try {
            DesktopShellSettings.setAutoStart(context, true)
            ActivityScenario.launch(MainActivity::class.java).use {
                display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
                    "VITURE Beast auto-open acceptance", 1920, 1080, 160, images.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or (1 shl 10),
                )
                val displayId = display!!.display.displayId
                val deadline = System.currentTimeMillis() + 10_000
                while ((!BeastActivity.isWorkspaceVisible() ||
                        BeastActivity.workspaceDisplayId() != displayId) &&
                    System.currentTimeMillis() < deadline
                ) Thread.sleep(100)
                assertTrue("Omalateral should open on the new display",
                    BeastActivity.isWorkspaceVisible())
                assertEquals(displayId, BeastActivity.workspaceDisplayId())
            }
        } finally {
            display?.release()
            images.close()
            DesktopShellSettings.setAutoStart(context, previous)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun optingForTheSystemDesktopStopsWorkspaceStartupAndDisplayOverrides() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val previous = DesktopShellSettings.autoStart(context)
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
        val images = ImageReader.newInstance(1920, 1080, PixelFormat.RGBA_8888, 2)
        var display: android.hardware.display.VirtualDisplay? = null
        try {
            DesktopShellSettings.setAutoStart(context, false)
            display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay("VITURE Beast system desktop acceptance",
                1920, 1080, 160, images.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or (1 shl 10))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                Thread.sleep(1500)
                scenario.onActivity { activity ->
                    fun field(name: String) = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)
                    assertNotNull(field("externalDisplay"))
                    assertNull("The system desktop must keep its own display sizing", field("clearedExternalDisplayId"))
                    assertNull(field("launchedBeastDisplayId"))
                    assertFalse((field("trackpad") as android.view.View).isEnabled)
                    assertFalse(BeastWorkspaceController.ensure(activity, display!!.display))
                    assertFalse("PhoneUI must not create a competing desktop", BeastActivity.isWorkspaceActive())
                }
            }
        } finally {
            display?.release(); images.close()
            DesktopShellSettings.setAutoStart(context, previous)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
