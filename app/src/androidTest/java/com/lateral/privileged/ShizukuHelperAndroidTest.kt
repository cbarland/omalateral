package com.lateral.privileged

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku

/** Requires an authorized v13+ shell Shizuku on the target ARM64 device; never prompts. */
@RunWith(AndroidJUnit4::class)
class ShizukuHelperAndroidTest {
    @Test fun authorizedWithoutPairingBindsOnceAndExplicitStopKillsOwnedHelper() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Do not interrupt a running workspace", !PrivilegedService.isBridgeReady())
        assumeTrue("Start and authorize shell Shizuku first", Shizuku.pingBinder() &&
            !Shizuku.isPreV11() && Shizuku.getVersion() >= 13 && Shizuku.getUid() == Process.SHELL_UID &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
        val marker = java.io.File(context.filesDir, "adb/lateral_paired_v2.flag")
        assumeTrue("Use a clean test install with no ADB pairing marker", !marker.exists())
        val backend = ShizukuPrivilegeBackend(context)
        val bound = CountDownLatch(1)
        val calls = AtomicInteger()
        val binder = AtomicReference<IBinder>()
        val error = AtomicReference<UnavailableReason>()
        val callback = object : PrivilegeBackend.Callback {
            override fun onConnecting(attemptId: Long) = Unit
            override fun onConnected(attemptId: Long, helper: IBinder) {
                calls.incrementAndGet(); binder.set(helper); bound.countDown()
            }
            override fun onUnavailable(attemptId: Long, reason: UnavailableReason) {
                error.set(reason); bound.countDown()
            }
        }
        val stopped = CountDownLatch(1)
        val stopResult = AtomicReference<Result<Unit>>()
        try {
            repeat(20) { backend.start(1L, callback) }
            assertTrue("UserService bind timed out", bound.await(25, TimeUnit.SECONDS))
            assertNull(error.get())
            val helper = checkNotNull(binder.get())
            assertEquals(PrivilegedProtocol.VERSION, IPrivilegedService.Stub.asInterface(helper).protocolVersion)
            assertEquals(1, calls.get())
            assertFalse("Shizuku touched the ADB pairing marker", marker.exists())
            val died = CountDownLatch(1)
            helper.linkToDeath({ died.countDown() }, 0)
            backend.stop { stopResult.set(it); stopped.countDown() }
            assertTrue(stopped.await(10, TimeUnit.SECONDS))
            assertTrue(stopResult.get().toString(), stopResult.get().isSuccess)
            assertTrue("Removal did not kill helper", died.await(5, TimeUnit.SECONDS))
        } finally {
            val cleanup = CountDownLatch(1)
            backend.stop { cleanup.countDown() }
            assertTrue(cleanup.await(10, TimeUnit.SECONDS))
        }
    }
}
