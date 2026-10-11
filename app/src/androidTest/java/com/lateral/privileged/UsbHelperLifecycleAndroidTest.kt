package com.lateral.privileged

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.graphics.SurfaceTexture
import android.view.Surface
import android.hardware.display.DisplayManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lateral.usb.BootstrapPipes
import com.lateral.usb.BootstrapProtocol
import com.lateral.usb.UsbBootstrapCoordinator
import com.lateral.usb.usbBounded
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.exitProcess

/** Test-APK-only wireless publisher: deliberately never invokes the global orphan sweep. */
object IsolatedWirelessHelper {
    @JvmStatic fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        val context = activityThread.getMethod("getSystemContext").invoke(thread) as android.content.Context
        val server = PrivilegedServer().also { it.setContext(context) }
        Thread({
            try { while (System.`in`.read() != -1) { } }
            finally { try { server.exit() } finally { exitProcess(0) } }
        }, "test-wireless-stdin").apply { isDaemon = true; start() }
        // Exercise the actual wireless provider publication, without production test switches.
        PrivilegedServer.Companion.javaClass.getDeclaredMethod(
            "sendBinderToApp", IBinder::class.java, String::class.java, java.lang.Long::class.java,
        ).apply { isAccessible = true }.invoke(PrivilegedServer.Companion, server, null, args.single().toLong())
        Looper.loop()
    }
}

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class UsbHelperLifecycleAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val apk get() = instrumentation.targetContext.applicationInfo.sourceDir

    private class Helper(val descriptors: Array<ParcelFileDescriptor>) : Closeable {
        val output = ParcelFileDescriptor.AutoCloseInputStream(descriptors[0])
        val input = ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1])
        var pid: Int = 0
        private var stdinClosed = false
        fun eof() { if (!stdinClosed) { stdinClosed = true; input.close() } }
        fun awaitExit() { usbBounded {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (true) {
                val stat = ParcelFileDescriptor.AutoCloseInputStream(
                    automation.executeShellCommand("cat /proc/$pid/stat")).use { it.readBytes().toString(Charsets.UTF_8) }
                if (stat.isBlank() || stat.substringAfterLast(") ").startsWith("Z ")) break
                check(System.nanoTime() < deadline) { "Owned helper $pid did not exit" }
                Thread.sleep(50)
            }
            // UiAutomation's output pump may wait for its stdin pump even after process
            // death. Close input only after independently observing that exact PID exit.
            eof()
            val buffer = ByteArray(1024)
            while (output.read(buffer) != -1) { }
        } }
        override fun close() {
            try { eof(); if (pid > 0) awaitExit() }
            finally { runCatching { output.close() } }
        }
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    private fun launch(token: String? = null, adbOnly: Boolean = false, detached: Boolean = false): Helper {
        val classpath = if (token == null && !adbOnly && !detached) "$apk:${instrumentation.context.applicationInfo.sourceDir}" else apk
        val entry = if (token == null && !adbOnly && !detached) IsolatedWirelessHelper::class.java.name else PrivilegedServer::class.java.name
        val argument = when { detached -> " --usb-adb-bootstrap"; adbOnly -> " --usb-adb-only"; token != null -> " --usb-lifeline=$token"; else -> " ${prepareWirelessAttempt()}" }
        val helper = Helper(instrumentation.uiAutomation.executeShellCommandRw("sh"))
        try {
            // exec preserves the printed PID and hands this retained stdin to app_process.
            helper.input.write(("echo \$\$; exec env CLASSPATH=${quote(classpath)} " +
                "app_process /system/bin --nice-name=lateral_usb_test $entry$argument\n").toByteArray())
            helper.input.flush()
            helper.pid = usbBounded {
                val line = StringBuilder()
                while (true) {
                    val next = helper.output.read()
                    check(next >= 0 && line.length < 16) { "Missing shell PID" }
                    if (next == 10) break
                    line.append(next.toChar())
                }
                line.toString().trim().toInt().also { check(it > 0) }
            }
            return helper
        } catch (failure: Throwable) { helper.close(); throw failure }
    }

    private fun awaitReady(helper: Helper) = usbBounded {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        val line = StringBuilder()
        while (System.nanoTime() < deadline) {
            val next = helper.output.read()
            check(next >= 0) { "ADB-only helper exited before READY" }
            if (next == '\n'.code) {
                if (line.toString().trim() == "LATERAL_USB_READY") return@usbBounded
                line.setLength(0)
            } else if (line.length < 256) line.append(next.toChar())
        }
        error("Timed out waiting for LATERAL_USB_READY")
    }

    private fun currentBinder(): IBinder? = (PrivilegedService::class.java
        .getDeclaredMethod("getService").apply { isAccessible = true }
        .invoke(PrivilegedService) as? IPrivilegedService)?.asBinder()

    /** Stage the real ADB callback path, with discovery replaced by our isolated shell publisher. */
    private fun prepareWirelessAttempt(): Long {
        PrivilegedService.init(instrumentation.targetContext)
        val gate = PrivilegedService::class.java.getDeclaredField("activation").apply { isAccessible = true }
            .get(PrivilegedService) as BackendAttemptGate
        gate.selectAfterStop(PrivilegeBackendId.WIRELESS_ADB, Result.success(Unit))
        val attempt = checkNotNull(gate.begin())
        val backend = PrivilegedService::class.java.getDeclaredField("adbBackend").apply { isAccessible = true }
            .get(PrivilegedService) as AdbPrivilegeBackend
        (AdbPrivilegeBackend::class.java.getDeclaredField("currentAttempt").apply { isAccessible = true }
            .get(backend) as java.util.concurrent.atomic.AtomicLong).set(attempt)
        val callback = PrivilegedService::class.java.getDeclaredMethod("backendCallback", PrivilegeBackendId::class.java)
            .apply { isAccessible = true }.invoke(PrivilegedService, PrivilegeBackendId.WIRELESS_ADB)
        AdbPrivilegeBackend::class.java.getDeclaredField("callback").apply { isAccessible = true }.set(backend, callback)
        return attempt
    }

    private fun awaitState(predicate: () -> Boolean) {
        val changed = CountDownLatch(1)
        val listener = { if (predicate()) changed.countDown(); Unit }
        PrivilegedService.addListener(listener)
        try {
            if (predicate()) return
            assertTrue("Service transition timed out", changed.await(15, TimeUnit.SECONDS))
            assertTrue(predicate())
        } finally { PrivilegedService.removeListener(listener) }
    }

    private fun requireIsolatedService() {
        assumeFalse("Do not displace an existing helper", currentBinder()?.pingBinder() == true)
    }

    private fun admit(): UsbBootstrapAttempt {
        val attempt = UsbBootstrapAttempt(BootstrapProtocol.tokenHex(BootstrapProtocol.newToken()),
            System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
        PrivilegedService.beginUsb(attempt)
        assertTrue(attempt.admitted.get(10, TimeUnit.SECONDS))
        PrivilegedService.usbShellAccepted(attempt)
        return attempt
    }

    private fun observeDeath(binder: IBinder): CountDownLatch {
        assertFalse("Must be a remote Binder", binder is Binder)
        return CountDownLatch(1).also { died -> binder.linkToDeath({ died.countDown() }, 0) }
    }

    private fun bootToken(pipes: BootstrapPipes): String {
        assertArrayEquals("PING".toByteArray(), usbBounded { pipes.read(4) })
        pipes.write("PONG".toByteArray())
        val header = usbBounded { pipes.read(7) }
        assertArrayEquals(byteArrayOf(66, 79, 79, 84, 1), header.copyOfRange(0, 5))
        val length = (header[5].toInt() and 255) or ((header[6].toInt() and 255) shl 8)
        assertEquals(apk, usbBounded { pipes.read(length) }.toString(Charsets.UTF_8))
        return BootstrapProtocol.tokenHex(usbBounded { pipes.read(16) }).also {
            pipes.write(byteArrayOf(66, 83, 84, 65, 1, 1))
        }
    }

    @Test fun helperDeathReleasesRetainedCoordinatorTransportAndIsolatesReplacement() {
        requireIsolatedService()
        val coordinator = UsbBootstrapCoordinator(apk)
        val pipes = BootstrapPipes()
        val replacement = BootstrapPipes()
        val callbacks = AtomicInteger()
        var first: Helper? = null
        var second: Helper? = null
        try {
            val ready = CountDownLatch(1)
            val result = AtomicReference<Result<Unit>>()
            coordinator.submit({ pipes.transport }) {
                callbacks.incrementAndGet(); result.set(it); ready.countDown()
            }
            first = launch(bootToken(pipes))
            assertTrue(ready.await(15, TimeUnit.SECONDS))
            assertTrue(result.get().toString(), result.get().isSuccess)
            val binder = checkNotNull(currentBinder())
            val leaseField = PrivilegedService::class.java.getDeclaredField("usbLease").apply { isAccessible = true }
            val lease = checkNotNull(leaseField.get(PrivilegedService))
            val oldAttempt = lease.javaClass.getDeclaredField("attempt").apply { isAccessible = true }
                .get(lease) as UsbBootstrapAttempt
            val died = observeDeath(binder)
            assertEquals(0, pipes.closes.get())
            first.eof()
            assertTrue(died.await(15, TimeUnit.SECONDS))
            first.awaitExit()
            pipes.assertReleased()
            awaitState { !PrivilegedService.isBridgeReady() }
            val nextReady = CountDownLatch(1)
            coordinator.submit({ replacement.transport }) {
                callbacks.incrementAndGet(); result.set(it); nextReady.countDown()
            }
            second = launch(bootToken(replacement))
            assertTrue(nextReady.await(15, TimeUnit.SECONDS))
            assertTrue(result.get().toString(), result.get().isSuccess)
            val nextBinder = checkNotNull(currentBinder())
            assertNotEquals(binder, nextBinder)
            PrivilegedService.cancelUsb(oldAttempt)
            assertFalse(replacement.closed.await(300, TimeUnit.MILLISECONDS))
            assertTrue(nextBinder.pingBinder())
            assertEquals(nextBinder, currentBinder())
            usbBounded { coordinator.close() }
            replacement.assertReleased()
            second.awaitExit()
            assertEquals(1, pipes.closes.get())
            assertEquals(2, callbacks.get())
        } finally {
            usbBounded { coordinator.close(); first?.close(); second?.close(); pipes.close(); replacement.close() }
        }
    }

    @Test fun realEarlyOfferWaitsForShellWithoutBlockingServiceWorker() {
        requireIsolatedService()
        val attempt = UsbBootstrapAttempt(BootstrapProtocol.tokenHex(BootstrapProtocol.newToken()),
            System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
        PrivilegedService.beginUsb(attempt)
        assertTrue(attempt.admitted.get(10, TimeUnit.SECONDS))
        val helper = launch(attempt.token)
        try {
            // Observe the candidate on its owning worker, while the helper's provider call waits.
            val worker = PrivilegedService::class.java.getDeclaredField("worker").apply { isAccessible = true }
                .get(PrivilegedService) as java.util.concurrent.ExecutorService
            val candidateField = PrivilegedService::class.java.getDeclaredField("usbCandidate").apply { isAccessible = true }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var candidate: IBinder? = null
            while (candidate == null && System.nanoTime() < deadline) {
                candidate = worker.submit<IBinder?> { candidateField.get(PrivilegedService) as? IBinder }
                    .get(1, TimeUnit.SECONDS)
                if (candidate == null) Thread.sleep(20)
            }
            val offered = checkNotNull(candidate)
            assertTrue(offered.pingBinder())
            assertFalse(attempt.ready.isDone)
            val acknowledgment = PrivilegedService.offerUsbBinder(offered, attempt.token)
            assertThrows(java.util.concurrent.TimeoutException::class.java) {
                acknowledgment.get(200, TimeUnit.MILLISECONDS)
            }
            assertTrue(worker.submit<Boolean> { true }.get(1, TimeUnit.SECONDS))
            PrivilegedService.usbShellAccepted(attempt)
            assertTrue(attempt.ready.get(10, TimeUnit.SECONDS))
            val accepted = checkNotNull(acknowledgment.get(5, TimeUnit.SECONDS))
            assertTrue(accepted.getBoolean(BinderReceiverProvider.EXTRA_ACCEPTED))
            assertTrue(checkNotNull(accepted.getBinder(BinderReceiverProvider.EXTRA_OWNER)).pingBinder())
            assertEquals(offered, currentBinder())
            assertTrue(offered.pingBinder())
        } finally {
            PrivilegedService.cancelUsb(attempt)
            usbBounded { helper.close() }
        }
    }

    /** Selected only by the external host experiment; never force-stops its own runner. */
    @Test fun externalOwnerDeathWithHostStdin() {
        val token = InstrumentationRegistry.getArguments().getString("externalUsbToken")
        assumeTrue("External host token absent; separate owner-death probe", token != null)
        require(checkNotNull(token).matches(Regex("[0-9a-fA-F]{32}"))) { "Invalid externalUsbToken" }
        requireIsolatedService()
        val attempt = UsbBootstrapAttempt(token.lowercase(),
            System.nanoTime() + TimeUnit.SECONDS.toNanos(120))
        try {
            PrivilegedService.beginUsb(attempt)
            assertTrue(attempt.admitted.get(10, TimeUnit.SECONDS))
            PrivilegedService.usbShellAccepted(attempt)
            instrumentation.sendStatus(0, Bundle().apply { putString("usb_phase", "admitted") })
            assertTrue(attempt.ready.get(120, TimeUnit.SECONDS))
            assertTrue(checkNotNull(currentBinder()).pingBinder())
            instrumentation.sendStatus(0, Bundle().apply { putString("usb_phase", "ready") })
            CountDownLatch(1).await(120, TimeUnit.SECONDS)
            fail("External parent did not force-stop the owner within 120 seconds")
        } finally { PrivilegedService.cancelUsb(attempt) }
    }

    @Test fun realUsbReadyThenStdinEofKillsRemoteHelper() {
        requireIsolatedService()
        val attempt = admit()
        val helper = launch(attempt.token)
        try {
            assertTrue(attempt.ready.get(15, TimeUnit.SECONDS))
            assertTrue(PrivilegedService.isBridgeReady())
            val binder = checkNotNull(currentBinder())
            val competing = UsbBootstrapAttempt(BootstrapProtocol.tokenHex(BootstrapProtocol.newToken()),
                System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
            PrivilegedService.beginUsb(competing)
            assertFalse("a second USB attempt displaced the active lease",
                competing.admitted.get(5, TimeUnit.SECONDS))
            assertTrue(competing.refusalReason.orEmpty().contains("already owns"))
            assertEquals(binder, currentBinder())
            assertTrue(binder.pingBinder())
            val died = observeDeath(binder)
            helper.eof()
            assertTrue("stdin EOF did not kill helper", died.await(15, TimeUnit.SECONDS))
            helper.awaitExit()
            awaitState { !PrivilegedService.isBridgeReady() }
        } finally {
            PrivilegedService.cancelUsb(attempt)
            usbBounded { helper.close() }
        }
    }

    @Test fun adbOnlyReadyCreatesTrustedDisplayAndStdinEofCleansUp() {
        requireIsolatedService()
        val helper = launch(adbOnly = true)
        var displayId = -1
        var remote: IPrivilegedService? = null
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(64, 64) }
        val surface = Surface(texture)
        try {
            awaitReady(helper)
            assertEquals(PrivilegedService.BridgeTransport.USB, PrivilegedService.bridgeTransport())
            assertFalse("committed USB lease must suppress orphan helper sweep",
                PrivilegedService.maySweepOrphanHelpers())
            val binder = checkNotNull(currentBinder())
            assertTrue("published Binder is not alive", binder.pingBinder())
            remote = IPrivilegedService.Stub.asInterface(checkNotNull(currentBinder()))
            displayId = remote!!.createVirtualDisplay("lateral-adb-acceptance", 64, 64,
                instrumentation.targetContext.resources.displayMetrics.densityDpi, surface)
            assertTrue("trusted virtual display creation failed", displayId >= 0)
            val display = instrumentation.targetContext.getSystemService(DisplayManager::class.java)
                .getDisplay(displayId)
            assertNotNull("created display is not visible", display)
            val trustedFlag = android.view.Display::class.java.getField("FLAG_TRUSTED").getInt(null)
            assertTrue("created display is not trusted",
                display!!.flags and trustedFlag != 0)
            remote!!.releaseVirtualDisplay(displayId)
            displayId = -1
            val died = observeDeath(binder)
            helper.eof()
            assertTrue("stdin EOF did not kill helper", died.await(15, TimeUnit.SECONDS))
            helper.awaitExit()
            awaitState { !PrivilegedService.isBridgeReady() }
            assertEquals(PrivilegedService.BridgeTransport.NONE, PrivilegedService.bridgeTransport())
            assertTrue("orphan helper sweep should resume after cleanup",
                PrivilegedService.maySweepOrphanHelpers())
        } finally {
            if (displayId >= 0) runCatching { remote?.releaseVirtualDisplay(displayId) }
            surface.release()
            texture.release()
            usbBounded { helper.close() }
        }
    }

    @Test fun detachedBootstrapSurvivesStdinEofAndOwnerCancellationCleansUp() {
        requireIsolatedService()
        val helper = launch(detached = true)
        var displayId = -1
        var remote: IPrivilegedService? = null
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(64, 64) }
        val surface = Surface(texture)
        try {
            awaitReady(helper)
            val binder = checkNotNull(currentBinder())
            remote = IPrivilegedService.Stub.asInterface(checkNotNull(currentBinder()))
            displayId = remote!!.createVirtualDisplay("lateral-detached-usb", 64, 64,
                instrumentation.targetContext.resources.displayMetrics.densityDpi, surface)
            assertTrue("trusted virtual display creation failed", displayId >= 0)
            val display = instrumentation.targetContext.getSystemService(DisplayManager::class.java).getDisplay(displayId)
            assertNotNull(display)
            val trustedFlag = android.view.Display::class.java.getField("FLAG_TRUSTED").getInt(null)
            assertTrue(display!!.flags and trustedFlag != 0)
            remote!!.releaseVirtualDisplay(displayId)
            displayId = -1
            val died = observeDeath(binder)
            helper.eof()
            assertFalse("detached helper died on stdin EOF", died.await(500, TimeUnit.MILLISECONDS))
            displayId = remote!!.createVirtualDisplay("lateral-after-usb-eof", 64, 64,
                instrumentation.targetContext.resources.displayMetrics.densityDpi, surface)
            assertTrue("privileged operation failed after stdin EOF", displayId >= 0)
            remote!!.releaseVirtualDisplay(displayId)
            displayId = -1
            assertTrue(binder.pingBinder())
            val lease = checkNotNull(PrivilegedService::class.java.getDeclaredField("usbLease")
                .apply { isAccessible = true }.get(PrivilegedService))
            val attempt = lease.javaClass.getDeclaredField("attempt").apply { isAccessible = true }
                .get(lease) as UsbBootstrapAttempt
            PrivilegedService.cancelUsb(attempt)
            assertTrue("owner cancellation did not clean helper", died.await(15, TimeUnit.SECONDS))
            helper.awaitExit()
        } finally {
            if (displayId >= 0) runCatching { remote?.releaseVirtualDisplay(displayId) }
            surface.release(); texture.release()
            usbBounded { helper.close() }
        }
    }

    @Test fun lateApplicationInitializationPreservesAdbOnlyReadyHelper() {
        requireIsolatedService()
        val helper = launch(adbOnly = true)
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(64, 64) }
        val surface = Surface(texture)
        var displayId = -1
        var remote: IPrivilegedService? = null
        val refreshed = CountDownLatch(1)
        val listener = { refreshed.countDown(); Unit }
        try {
            awaitReady(helper)
            val binder = checkNotNull(currentBinder())
            assertEquals(PrivilegedService.BridgeTransport.USB, PrivilegedService.bridgeTransport())
            remote = IPrivilegedService.Stub.asInterface(checkNotNull(currentBinder()))

            PrivilegedService.addListener(listener)
            PrivilegedService.init(instrumentation.targetContext)
            assertTrue("late init did not notify state listeners", refreshed.await(5, TimeUnit.SECONDS))
            awaitState { PrivilegedService.state == PrivilegedService.State.READY }
            assertEquals(PrivilegedService.BridgeTransport.USB, PrivilegedService.bridgeTransport())
            assertEquals("late init replaced the published Binder", binder, currentBinder())
            assertTrue("published Binder died during late init", binder.pingBinder())

            displayId = remote!!.createVirtualDisplay("lateral-late-init", 64, 64,
                instrumentation.targetContext.resources.displayMetrics.densityDpi, surface)
            assertTrue("privileged operation failed after late init", displayId >= 0)
            remote!!.releaseVirtualDisplay(displayId)
            displayId = -1
        } finally {
            PrivilegedService.removeListener(listener)
            if (displayId >= 0) runCatching { remote?.releaseVirtualDisplay(displayId) }
            surface.release()
            texture.release()
            helper.eof()
            usbBounded { helper.close() }
        }
    }

    @Test fun duplicateAdbOnlyHelperIsRefusedWithoutReplacingOwner() {
        requireIsolatedService()
        val owner = launch(adbOnly = true)
        try {
            awaitReady(owner)
            val accepted = checkNotNull(currentBinder())
            assertTrue(accepted.pingBinder())
            val duplicate = launch(adbOnly = true)
            duplicate.use { it.awaitExit() }
            assertEquals("duplicate displaced the accepted Binder", accepted, currentBinder())
            assertTrue("original helper died after duplicate offer", accepted.pingBinder())
            val died = observeDeath(accepted)
            owner.eof()
            assertTrue(died.await(15, TimeUnit.SECONDS))
            owner.awaitExit()
            awaitState { !PrivilegedService.isBridgeReady() }
        } finally { usbBounded { owner.close() } }
    }

    @Test fun coordinatorRetainsTransportUntilDetachAndReleasesExactRemoteLease() {
        requireIsolatedService()
        val pipes = BootstrapPipes()
        val coordinator = UsbBootstrapCoordinator(apk, bootstrapMs = 4_000)
        val result = AtomicReference<Result<Unit>>()
        val completed = CountDownLatch(1)
        val callbacks = AtomicInteger()
        var helper: Helper? = null
        try {
            coordinator.submit({ pipes.transport }) {
                callbacks.incrementAndGet(); result.set(it); completed.countDown()
            }
            assertArrayEquals("PING".toByteArray(), usbBounded { pipes.read(4) })
            pipes.write("PONG".toByteArray())
            val header = usbBounded { pipes.read(7) }
            assertArrayEquals(byteArrayOf(66, 79, 79, 84, 1), header.copyOfRange(0, 5))
            val length = (header[5].toInt() and 255) or ((header[6].toInt() and 255) shl 8)
            assertEquals(apk, usbBounded { pipes.read(length) }.toString(Charsets.UTF_8))
            val token = BootstrapProtocol.tokenHex(usbBounded { pipes.read(16) })
            pipes.write(byteArrayOf(66, 83, 84, 65, 1, 1))
            helper = launch(token)
            assertTrue(completed.await(15, TimeUnit.SECONDS))
            assertTrue(result.get().toString(), result.get().isSuccess)
            val binder = checkNotNull(currentBinder())
            val died = observeDeath(binder)
            // Survive both coordinator and service startup deadlines, with stdin still open.
            assertFalse(pipes.closed.await(4_500, TimeUnit.MILLISECONDS))
            assertTrue(binder.pingBinder())
            assertTrue(PrivilegedService.isBridgeReady())
            usbBounded { coordinator.cancel() }
            pipes.assertReleased()
            assertTrue("Detach did not release accepted helper", died.await(15, TimeUnit.SECONDS))
            helper.awaitExit()
            usbBounded { coordinator.close() }
            assertEquals(1, pipes.closes.get())
            assertEquals(1, callbacks.get())
            awaitState { !PrivilegedService.isBridgeReady() }
        } finally {
            usbBounded { coordinator.close(); helper?.close(); pipes.close() }
        }
    }

    @Test fun staleUsbOfferCannotDisplaceHealthyWirelessHelper() {
        requireIsolatedService()
        val stale = admit()
        PrivilegedService.cancelUsb(stale)
        val wireless = launch()
        try {
            awaitState { PrivilegedService.isBridgeReady() }
            val accepted = checkNotNull(currentBinder())
            val died = observeDeath(accepted)
            launch(stale.token).use { rejected -> rejected.awaitExit() }
            PrivilegedService.cancelUsb(stale)
            assertFalse(died.await(300, TimeUnit.MILLISECONDS))
            assertEquals(accepted, currentBinder())
            assertTrue(PrivilegedService.isBridgeReady())
            wireless.eof()
            assertTrue(died.await(15, TimeUnit.SECONDS))
            wireless.awaitExit()
            awaitState { !PrivilegedService.isBridgeReady() }
        } finally { usbBounded { wireless.close() } }
    }

    @Test fun correlatedUsbHandoffKeepsWirelessOwnerUntilCandidateIsReady() {
        requireIsolatedService()
        val wireless = launch()
        var usb: Helper? = null
        val attempt = UsbBootstrapAttempt(BootstrapProtocol.tokenHex(BootstrapProtocol.newToken()),
            System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
        val prematureUsb = AtomicReference(false)
        val readyToReady = CountDownLatch(1)
        val listener = {
            if (PrivilegedService.bridgeTransport() == PrivilegedService.BridgeTransport.USB &&
                !attempt.ready.isDone) prematureUsb.set(true)
            if (PrivilegedService.state == PrivilegedService.State.READY && attempt.ready.isDone)
                readyToReady.countDown()
            Unit
        }
        PrivilegedService.addListener(listener)
        try {
            awaitState { PrivilegedService.isBridgeReady() }
            val original = checkNotNull(currentBinder())
            assertEquals(PrivilegedService.BridgeTransport.WIRELESS, PrivilegedService.bridgeTransport())
            val initialized = CountDownLatch(1)
            val initListener = { initialized.countDown(); Unit }
            PrivilegedService.addListener(initListener)
            try {
                PrivilegedService.init(instrumentation.targetContext)
                assertTrue("late init did not refresh wireless owner", initialized.await(5, TimeUnit.SECONDS))
                assertTrue("late init reset a healthy wireless owner", PrivilegedService.isBridgeReady())
                assertEquals(PrivilegedService.BridgeTransport.WIRELESS, PrivilegedService.bridgeTransport())
                assertEquals(original, currentBinder())
            } finally { PrivilegedService.removeListener(initListener) }
            val originalDeath = observeDeath(original)
            PrivilegedService.beginUsb(attempt)
            assertTrue(attempt.admitted.get(10, TimeUnit.SECONDS))
            assertFalse("staging USB admission must not permit helper sweep",
                PrivilegedService.maySweepOrphanHelpers())
            usb = launch(attempt.token)
            // Until both shell and Binder sides are acknowledged, the wireless helper remains
            // the published service and continues to answer Binder liveness checks.
            assertTrue(original.pingBinder())
            assertEquals(original, currentBinder())
            assertEquals(PrivilegedService.BridgeTransport.WIRELESS, PrivilegedService.bridgeTransport())
            PrivilegedService.usbShellAccepted(attempt)
            assertTrue("USB candidate did not become ready", attempt.ready.get(15, TimeUnit.SECONDS))
            assertFalse("listener observed USB before ready acknowledgement", prematureUsb.get())
            assertEquals(PrivilegedService.BridgeTransport.USB, PrivilegedService.bridgeTransport())
            assertFalse("committed USB lease must suppress orphan helper sweep",
                PrivilegedService.maySweepOrphanHelpers())
            val replacement = checkNotNull(currentBinder())
            assertNotEquals(original, replacement)
            assertTrue(replacement.pingBinder())
            assertTrue("previous owner was not retired after readiness", originalDeath.await(15, TimeUnit.SECONDS))
            assertTrue(PrivilegedService.isBridgeReady())
            assertTrue("ready-to-ready handoff notification was not delivered",
                readyToReady.await(5, TimeUnit.SECONDS))
        } finally {
            PrivilegedService.removeListener(listener)
            PrivilegedService.cancelUsb(attempt)
            usb?.let { usbBounded { it.close() } }
            wireless.eof()
            usbBounded { wireless.close() }
            assertEquals(PrivilegedService.BridgeTransport.NONE, PrivilegedService.bridgeTransport())
        }
    }
}
