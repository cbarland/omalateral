package com.lateral.privileged

import android.content.Intent
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit physical-device setup check. Run with -e lateral_shizuku_setup true.
 * Wi-Fi is restored in finally, plus an independent on-device watchdog.
 * Requires existing Shizuku authorization and never changes pairing or app data.
 */
@RunWith(AndroidJUnit4::class)
class ShizukuSetupAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).use { it.readBytes().toString(Charsets.UTF_8).trim() }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "$description: ${PrivilegedService.state} / ${PrivilegedService.unavailableReason}" }
            Thread.sleep(100)
        }
    }

    private fun ready(): IPrivilegedService {
        assertEquals("Explicit setup-test opt-in required", "true",
            InstrumentationRegistry.getArguments().getString("lateral_shizuku_setup"))
        assertEquals(PrivilegeBackendId.SHIZUKU, PrivilegedService.selectedBackend)
        PrivilegedService.ensureRunning()
        await("Shizuku helper did not become ready") { PrivilegedService.isBridgeReady() }
        assertEquals(PrivilegeBackendId.SHIZUKU, PrivilegedService.activeBackend)
        assertEquals(PrivilegedService.BridgeTransport.SHIZUKU, PrivilegedService.bridgeTransport())
        assertFalse(File(instrumentation.targetContext.filesDir, "adb/lateral_paired_v2.flag").exists())
        return PrivilegedService.javaClass.getDeclaredMethod("getService").apply {
            isAccessible = true
        }.invoke(PrivilegedService) as IPrivilegedService
    }

    @Test fun repeatedStartupAndExplicitRestartPreserveBackendOwnership() {
        val original = ready()
        val generation = PrivilegedService.helperGeneration
        repeat(20) { PrivilegedService.ensureRunning() }
        Thread.sleep(500)
        assertEquals(generation, PrivilegedService.helperGeneration)
        assertEquals(original.asBinder(), ready().asBinder())
        val died = CountDownLatch(1)
        original.asBinder().linkToDeath({ died.countDown() }, 0)
        val completed = CountDownLatch(1)
        val outcome = AtomicReference<Result<Unit>>()
        PrivilegedService.restartHelper { outcome.set(it); completed.countDown() }
        assertTrue("Explicit restart did not complete", completed.await(20, TimeUnit.SECONDS))
        assertTrue(outcome.get().toString(), outcome.get().isSuccess)
        assertTrue("Restart left the old helper alive", died.await(5, TimeUnit.SECONDS))
        val replacement = ready()
        assertNotEquals(original.asBinder(), replacement.asBinder())
        assertTrue(PrivilegedService.helperGeneration > generation)
        Log.i("Lateral/ShizukuSetupTest", "PASS restart generation=$generation -> ${PrivilegedService.helperGeneration}")
    }

    @Test fun wifiOffKeepsSameHelperAndHostedTask() {
        val helper = ready()
        val generation = PrivilegedService.helperGeneration
        assertEquals("Wi-Fi must start enabled", "1", shell("settings get global wifi_on"))
        val reader = ImageReader.newInstance(800, 600, PixelFormat.RGBA_8888, 2)
        var displayId = -1
        try {
            val created = PrivilegedService.createHostedVirtualDisplay(
                "Lateral Shizuku setup test", 800, 600, 160, reader.surface, true)
            assertTrue("Trusted display creation failed: $created / ${PrivilegedService.imeRoutingError}",
                created is PrivilegedService.HostedDisplayResult.Ready)
            displayId = (created as PrivilegedService.HostedDisplayResult.Ready).displayId
            val activity = instrumentation.targetContext.packageManager.resolveActivity(
                Intent(Settings.ACTION_SETTINGS), 0)!!.activityInfo
            assertTrue(helper.launchOnDisplay(displayId, activity.packageName, activity.name))
            await("Settings was not hosted") { helper.displayHasPackage(displayId, activity.packageName) }
            val hostedTask = PrivilegedService.taskSnapshots().single {
                it.displayId == displayId && it.packageName == activity.packageName
            }.taskId
            reader.acquireLatestImage()?.close()
            // Recovery survives a test-process failure or loss of the PC's wireless ADB.
            shell("sh -c '(sleep 45; svc wifi enable) >/dev/null 2>&1 </dev/null &'")
            shell("svc wifi disable")
            await("Wi-Fi did not turn off") { shell("settings get global wifi_on") == "0" }
            repeat(6) {
                Thread.sleep(1000)
                assertEquals(generation, PrivilegedService.helperGeneration)
                assertEquals(helper.asBinder(), ready().asBinder())
                assertTrue(helper.displayHasPackage(displayId, activity.packageName))
                assertEquals(hostedTask, PrivilegedService.taskSnapshots().single {
                    it.displayId == displayId && it.packageName == activity.packageName
                }.taskId)
                assertTrue(PrivilegedService.ensureHostedDisplayImeRouting(displayId))
                reader.acquireLatestImage()?.close()
            }
            Log.i("Lateral/ShizukuSetupTest", "PASS wifi-off generation=$generation display=$displayId task=$hostedTask")
        } finally {
            shell("svc wifi enable")
            if (displayId >= 0) {
                PrivilegedService.releaseVirtualDisplay(displayId, generation)
                await("Test display was not released") { !helper.displayHasActivity(displayId) }
            }
            reader.close()
        }
    }
}
