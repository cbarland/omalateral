package com.lateral.beast

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserManager
import android.net.Uri
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lateral.WorkspaceCursor
import com.lateral.DisplayPreferences
import com.lateral.privileged.PrivilegedService
import com.lateral.privileged.UsbHelperLifecycleAndroidTest
import dev.omalauncher.desktop.IDesktopHost
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Exercises Omalateral's real PhoneUI route into a separately running Omalauncher. */
class ExternalOmaDesktopAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun selectedBridgePreviewHomeIsPreferred() {
        val context = instrumentation.targetContext
        val providers = ExternalOmaDesktop.available(context)
        assumeTrue("Install the bridgepreview provider fixture", "dev.omalauncher.bridgepreview" in providers)
        val home = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
        )?.activityInfo?.packageName
        assumeTrue("Select the bridgepreview fixture as Home", home == "dev.omalauncher.bridgepreview")
        assertEquals(home, providers.first())
    }

    private fun ui(work: () -> Unit) = instrumentation.runOnMainSync(work)
    private fun await(message: String, check: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 30_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var ready = false
            ui { ready = check() }
            if (ready) return
            Thread.sleep(100)
        }
        fail("Timed out: $message")
    }
    private fun field(activity: BeastActivity, name: String): Any? =
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun allowNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString()?.equals("allow", true) == true && node.packageName.toString() in ExternalOmaDesktop.PACKAGES) return node
        for (index in 0 until node.childCount) allowNode(node.getChild(index))?.let { return it }
        return null
    }

    @Test fun galleryScreenshotContainsTheInheritedHomeAndWidgets() {
        val context = instrumentation.targetContext
        val output = checkNotNull(context.getSystemService(DisplayManager::class.java).displays.firstOrNull {
            it.displayId != Display.DEFAULT_DISPLAY && it.flags and Display.FLAG_SECURE != 0
        }) { "A secure secondary emulator display is required" }
        val bootstrap = UsbHelperLifecycleAndroidTest()
        check(bootstrap.currentService() == null) { "Do not replace an existing helper" }
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
        val helper = bootstrap.launch()
        try {
            await("helper ready") { PrivilegedService.isBridgeReady() }
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = output.displayId }
            ActivityScenario.launch<BeastActivity>(Intent(context, BeastActivity::class.java)
                .putExtra("desktopProvider", "dev.omalauncher.bridgepreview")
                .putExtra("desktopProfile", "capture_" + java.util.UUID.randomUUID().toString().take(20))
                .setAction(BeastActivity.ACTION_ENSURE_WORKSPACE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle()).use { scenario ->
                lateinit var activity: BeastActivity
                scenario.onActivity { activity = it }
                if (InstrumentationRegistry.getArguments().getString("allowConsent") == "true") {
                    val deadline = android.os.SystemClock.uptimeMillis() + 10_000
                    while (android.os.SystemClock.uptimeMillis() < deadline) {
                        var connected = false
                        ui { connected = (field(activity, "omaDesktop") as? ExternalOmaDesktop)?.connected == true }
                        if (connected) break
                        allowNode(instrumentation.uiAutomation.rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Thread.sleep(100)
                    }
                }
                lateinit var desktop: ExternalOmaDesktop
                await("provider home") {
                    val candidate = field(activity, "omaDesktop") as? ExternalOmaDesktop
                    if (candidate?.connected == true && (candidate.details.getIntArray("pages")?.size ?: 0) >= 2) {
                        desktop = candidate; true
                    } else false
                }
                val pageCount = checkNotNull(desktop.details.getIntArray("pages")).size
                var previousWorkspace = 1
                ui {
                    previousWorkspace = WorkspaceState.activeWorkspace
                    val emptyWorkspace = (1..10).firstOrNull { candidate ->
                        WorkspaceState.tasks.none { it.workspace == candidate || it.pinned }
                    } ?: error("No empty workspace for isolated home capture")
                    WorkspaceState.switchWorkspace(emptyWorkspace)
                }
                try {
                    Thread.sleep(800)
                    val result = AtomicReference<BeastScreenshotController.Result?>()
                    BeastScreenshotController(activity, { null }, { desktop.connected },
                        PrivilegedService::captureExternalDisplayAsync, result::set).use { controller ->
                        ui { assertTrue(controller.request(BeastScreenshotController.ScreenshotTarget.FULL_DISPLAY)) }
                        await("composed screenshot") { result.get() != null }
                    }
                    val saved = checkNotNull(result.get() as? BeastScreenshotController.Result.Saved) {
                        "Desktop screenshot failed: ${result.get()}"
                    }
                    assertTrue("Screenshot should be available in Gallery", saved.galleryVisible)
                    val uri = Uri.parse(saved.location)
                    try {
                        val bitmap = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
                        assertNotNull("Saved screenshot is a valid image", bitmap)
                        bitmap!!.usePixels { image ->
                            File(context.getExternalFilesDir(null), "bridge-composed-capture.png").outputStream().use {
                                image.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            assertEquals(output.mode.physicalWidth, image.width)
                            val colors = HashSet<Int>()
                            for (y in 90 until image.height - 50 step 8) for (x in 16 until image.width / pageCount - 16 step 8) {
                                colors += image.getPixel(x, y)
                            }
                            assertTrue("Inherited home and widgets are absent from the screenshot (${colors.size} colors)", colors.size > 30)
                        }
                    } finally { context.contentResolver.delete(uri, null, null) }
                } finally { ui { WorkspaceState.switchWorkspace(previousWorkspace) } }
            }
        } finally {
            helper.close()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private inline fun Bitmap.usePixels(block: (Bitmap) -> Unit) {
        try { block(this) } finally { recycle() }
    }

    @Test fun previewProviderOpensAndDismissesStockHomeMenu() {
        val context = instrumentation.targetContext
        assertTrue("Install the bridge provider fixture", "dev.omalauncher.bridgepreview" in ExternalOmaDesktop.available(context))
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
        val images = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 2)
        val display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "Omalauncher home menu acceptance", 1280, 720, 160, images.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or (1 shl 10),
        )
        try {
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = display.display.displayId }
            ActivityScenario.launch<BeastActivity>(Intent(context, BeastActivity::class.java)
                .putExtra("desktopProvider", "dev.omalauncher.bridgepreview")
                .putExtra("desktopProfile", "menu_" + java.util.UUID.randomUUID().toString().take(20))
                .setAction(BeastActivity.ACTION_ENSURE_WORKSPACE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle()).use { scenario ->
                lateinit var activity: BeastActivity
                scenario.onActivity { activity = it }
                if (InstrumentationRegistry.getArguments().getString("allowConsent") == "true") {
                    val deadline = android.os.SystemClock.uptimeMillis() + 10_000
                    while (android.os.SystemClock.uptimeMillis() < deadline) {
                        var connected = false
                        ui { connected = (field(activity, "omaDesktop") as? ExternalOmaDesktop)?.connected == true }
                        if (connected) break
                        allowNode(instrumentation.uiAutomation.rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Thread.sleep(100)
                    }
                }
                lateinit var desktop: ExternalOmaDesktop
                await("automatic provider connection") {
                    val candidate = field(activity, "omaDesktop") as? ExternalOmaDesktop
                    if (candidate?.connected == true) { desktop = candidate; true } else false
                }
                val activityDisplay = checkNotNull(activity.display)
                val target = DisplayPreferences.target(activity, activityDisplay)
                val original = DisplayPreferences.load(context, target)
                await("initial provider UI scale") {
                    kotlin.math.abs(desktop.details.getFloat("uiScale") - original.uiScale) < .01f
                }
                try {
                    val changed = if (original.uiScale < 1.95f) 2f else 1f
                    val before = desktop
                    DisplayPreferences.save(context, target, original.copy(uiScale = changed))
                    await("provider widget and text scale follows the display profile") {
                        val current = field(activity, "omaDesktop") as? ExternalOmaDesktop
                        if (current !== before && current?.connected == true &&
                            kotlin.math.abs(current.details.getFloat("uiScale") - changed) < .01f) {
                            desktop = current
                            true
                        } else false
                    }
                    assertFalse("The previous provider session must close after UI rebuild", before.connected)
                } finally {
                    DisplayPreferences.save(context, target, original)
                    await("restored provider UI scale") {
                        val current = field(activity, "omaDesktop") as? ExternalOmaDesktop
                        if (current?.connected == true &&
                            kotlin.math.abs(current.details.getFloat("uiScale") - original.uiScale) < .01f) {
                            desktop = current
                            true
                        } else false
                    }
                }
                ui { assertTrue("The preview provider exposes its stock home menu", desktop.homeMenu()) }
                await("home-menu action opens the provider menu") { desktop.details.getBoolean("homeMenuOpen") }
                ui { desktop.dismissPanels() }
                await("dismiss closes the provider home menu") { !desktop.details.getBoolean("homeMenuOpen") }
                ui { desktop.apps() }
                await("stock app panel opens") { desktop.details.getBoolean("overlay") }
                ui { desktop.search("settings", false) }
                await("stock search receives external query") { desktop.details.getString("query") == "settings" }
                ui { desktop.dismissPanels() }
                await("stock app panel closes") { !desktop.details.getBoolean("overlay") }
                ui { desktop.widgets() }
                await("stock widget store opens") { desktop.details.getBoolean("overlay") }
                ui { desktop.dismissPanels() }
                await("stock widget store closes") { !desktop.details.getBoolean("overlay") }
            }
        } finally {
            display.release()
            images.close()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun previewProviderShowsInheritedPagesAndPhonePointerControlsTheLiveSurface() {
        val context = instrumentation.targetContext
        assertTrue("Install the bridge provider fixture", ExternalOmaDesktop.available(context).isNotEmpty())
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
        val images = ImageReader.newInstance(3840, 1200, PixelFormat.RGBA_8888, 3)
        var latest: Bitmap? = null
        var captureRequested = false
        var pageCount = 0
        var pageColors = emptyList<Int>()
        images.setOnImageAvailableListener({ source ->
            source.acquireLatestImage()?.use { image ->
                if (!captureRequested) return@use
                val plane = image.planes[0]
                pageColors = (0 until pageCount).map { page ->
                    val colors = HashSet<Int>()
                    val x0 = page * image.width / pageCount
                    val x1 = (page + 1) * image.width / pageCount
                    for (y in 80 until image.height - 100 step 8) for (x in x0 + 12 until x1 - 12 step 8) {
                        val at = y * plane.rowStride + x * plane.pixelStride
                        val color = (plane.buffer.get(at).toInt() and 255) or ((plane.buffer.get(at + 1).toInt() and 255) shl 8) or ((plane.buffer.get(at + 2).toInt() and 255) shl 16)
                        colors += color
                    }
                    colors.size
                }
                // Other inherited pages may legitimately be empty.
                if (pageColors.firstOrNull()?.let { it <= 10 } != false) return@use
                captureRequested = false
                // Gralloc need not map the last row's trailing padding. Copy valid row pixels
                // through a heap buffer, rather than asking native memcpy to read padded rows.
                val bytes = ByteArray(image.width * image.height * 4)
                val buffer = plane.buffer.duplicate()
                for (row in 0 until image.height) {
                    buffer.position(row * plane.rowStride)
                    buffer.get(bytes, row * image.width * 4, image.width * 4)
                }
                val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
                latest?.recycle()
                latest = bitmap
            }
        }, Handler(Looper.getMainLooper()))
        val manager = context.getSystemService(DisplayManager::class.java)
        val display = manager.createVirtualDisplay("VITURE Beast bridge acceptance", 3840, 1200, 160, images.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or (1 shl 10))
        try {
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = display.display.displayId }
            ActivityScenario.launch<BeastActivity>(Intent(context, BeastActivity::class.java)
                .putExtra("desktopProvider", "dev.omalauncher.bridgepreview")
                .putExtra("desktopProfile", "acceptance_" + java.util.UUID.randomUUID().toString().take(20))
                .setAction(BeastActivity.ACTION_ENSURE_WORKSPACE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle()).use { scenario ->
                lateinit var activity: BeastActivity
                scenario.onActivity { activity = it }
                // Emulator fixtures can automate their consent. Real-device use still asks the user.
                if (InstrumentationRegistry.getArguments().getString("allowConsent") == "true") {
                    val deadline = android.os.SystemClock.uptimeMillis() + 10_000
                    while (android.os.SystemClock.uptimeMillis() < deadline) {
                        var connected = false
                        ui { connected = (field(activity, "omaDesktop") as? ExternalOmaDesktop)?.connected == true }
                        if (connected) break
                        allowNode(instrumentation.uiAutomation.rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Thread.sleep(100)
                    }
                }
                lateinit var desktop: ExternalOmaDesktop
                await("automatic provider connection") {
                    val candidate = field(activity, "omaDesktop") as? ExternalOmaDesktop
                    if (candidate?.connected == true) { desktop = candidate; true } else false
                }
                try {
                    await("inherited pages") { (desktop.details.getIntArray("pages")?.size ?: 0) >= 2 }
                } catch (error: AssertionError) {
                    throw AssertionError("${error.message}; provider=${desktop.status}; details=${desktop.details}", error)
                }
                assertTrue(desktop.details.getBoolean("pagesSideBySide"))
                pageCount = checkNotNull(desktop.details.getIntArray("pages")).size
                val tasksBeforeInvalidLaunch = WorkspaceState.tasks.size
                val callbacks = desktop.javaClass.getDeclaredField("callbacks").apply { isAccessible = true }
                    .get(desktop) as IDesktopHost
                val user = Process.myUserHandle()
                callbacks.onAndroidLaunch(checkNotNull(desktop.sessionId),
                    ComponentName("com.android.settings", "com.android.settings.NeverARealLauncherActivity"),
                    user, context.getSystemService(UserManager::class.java).getSerialNumberForUser(user))
                ui { assertEquals("Provider cannot launch an arbitrary owner-profile component",
                    tasksBeforeInvalidLaunch, WorkspaceState.tasks.size) }
                await("host cursor is visible") { WorkspaceCursor.position().visible && (field(activity, "cursorOverlay") as View).isShown }
                Thread.sleep(1500)
                ui { captureRequested = true; (field(activity, "root") as View).postInvalidateOnAnimation() }
                await("rendered inherited home; last region counts=$pageColors") { latest != null }
                assertEquals("Desktop sizing must keep all source pages", pageCount, desktop.details.getIntArray("pages")!!.size)
                Thread.sleep(500)
                ui {
                    File(context.getExternalFilesDir(null), "bridge-all-pages.png").outputStream().use {
                        latest!!.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    desktop.apps()
                }
                await("provider app panel opens") { desktop.details.getBoolean("overlay") }
                val beforeScroll = desktop.details.getInt("drawerScrollIndex") * 10000 + desktop.details.getInt("drawerScrollOffset")
                ui {
                    val target = desktop.view.getChildAt(0)
                    val point = floatArrayOf(target.width / 2f, target.height / 2f)
                    val transform = Matrix()
                    target.transformMatrixToGlobal(transform)
                    transform.mapPoints(point)
                    assertTrue(BeastInputRouter.scroll(activity.display!!.displayId, point[0].toInt(), point[1].toInt(), -4f))
                }
                await("phone wheel scroll moves the provider app list") {
                    desktop.details.getInt("drawerScrollIndex") * 10000 + desktop.details.getInt("drawerScrollOffset") > beforeScroll
                }
                ui { LauncherSearchSession.begin(); LauncherSearchSession.updateQuery("settings") }
                await("phone search edits only the external session") { desktop.details.getString("query") == "settings" }
                ui { LauncherSearchSession.close(); desktop.dismissPanels() }
                await("dismiss closes the remote panel") { !desktop.details.getBoolean("overlay") }
                assertTrue("Cursor stays above the child surface", (field(activity, "cursorOverlay") as View).isShown)
            }
        } finally {
            ui { LauncherSearchSession.close() }
            display.release()
            images.close()
            ui { latest?.recycle() }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
