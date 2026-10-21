package com.lateral

import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.view.Display
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lateral.beast.BeastActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSettingsRoutingAndroidTest {
    @Test fun phoneUiSettingsActionOpensItsPanelOnThePhone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = DesktopShellSettings.autoStart(context)
        DesktopShellSettings.setAutoStart(context, false)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity()
        val images = ImageReader.newInstance(800, 600, PixelFormat.RGBA_8888, 2)
        val external = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "Phone settings route", 800, 600, 160, images.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or (1 shl 10),
        )
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_INPUT_SETTINGS)).use { scenario ->
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    assertTrue(BeastActivity.findExternalDisplay(context) != null)
                    assertEquals(Display.DEFAULT_DISPLAY, activity.display!!.displayId)
                    assertTrue(TransientPanelCoordinator.isActiveOnDisplay(Display.DEFAULT_DISPLAY))
                }
            }
        } finally {
            external.release()
            images.close()
            DesktopShellSettings.setAutoStart(context, previous)
            automation.dropShellPermissionIdentity()
        }
    }
}
