package com.lateral.usb

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lateral.privileged.UsbBootstrapAttempt
import java.io.Closeable
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

internal fun <T> usbBounded(action: () -> T): T {
    val task = FutureTask<T> { action() }
    Thread(task, "usb-test-operation").apply { isDaemon = true; start() }
    return task.get(20, TimeUnit.SECONDS)
}

/** Android descriptors with the same input-owner/output-borrower close ordering as AOA. */
internal class BootstrapPipes : Closeable {
    private class Endpoint(pfd: ParcelFileDescriptor) : Closeable {
        val fd = pfd.fileDescriptor
        val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        val output = FileOutputStream(fd)
        override fun close() { try { input.close() } finally { output.close() } }
    }
    private val incoming = ParcelFileDescriptor.createPipe()
    private val outgoing = ParcelFileDescriptor.createPipe()
    private val reader = Endpoint(incoming[0])
    private val writer = Endpoint(outgoing[1])
    private val peerWriter = Endpoint(incoming[1])
    private val peerReader = Endpoint(outgoing[0])
    val entered = CountDownLatch(1)
    val exited = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val closes = AtomicInteger()
    val thread = AtomicReference<Thread>()
    val failure = AtomicReference<Throwable>()
    val transport = object : ProbeTransport {
        override val input = object : FilterInputStream(reader.input) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                thread.set(Thread.currentThread())
                entered.countDown()
                return try { super.read(bytes, offset, length) }
                catch (t: Throwable) { failure.set(t); throw t }
                finally { exited.countDown() }
            }
        }
        override val output = writer.output
        override fun close() {
            closes.incrementAndGet()
            try { try { reader.close() } finally { writer.close() } }
            finally { closed.countDown() }
        }
    }
    fun read(size: Int): ByteArray = ByteArray(size).also { bytes ->
        var offset = 0
        while (offset < size) {
            val count = peerReader.input.read(bytes, offset, size - offset)
            check(count > 0) { "Unexpected coordinator EOF" }
            offset += count
        }
    }
    fun write(bytes: ByteArray) { peerWriter.output.write(bytes); peerWriter.output.flush() }
    fun assertReleased() {
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertTrue(exited.await(5, TimeUnit.SECONDS))
        assertFalse(reader.fd.valid())
        assertFalse(writer.fd.valid())
        assertEquals(1, closes.get())
    }
    override fun close() {
        for (endpoint in listOf(reader, writer, peerWriter, peerReader)) runCatching { endpoint.close() }
    }
}

@RunWith(AndroidJUnit4::class)
class UsbBootstrapCoordinatorAndroidTest {
    private val unusedService = object : BootstrapService {
        override fun begin(attempt: UsbBootstrapAttempt) = error("Preflight must not admit")
        override fun shellAccepted(attempt: UsbBootstrapAttempt) = error("Preflight must not accept")
        override fun cancel(attempt: UsbBootstrapAttempt) = attempt.cancel()
    }

    private fun blockedPreflight(timeout: Boolean) {
        val pipes = BootstrapPipes()
        val coordinator = UsbBootstrapCoordinator("/data/app/test.apk", unusedService,
            preflightMs = if (timeout) 2_000 else 30_000)
        val completed = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val result = AtomicReference<Result<Unit>>()
        try {
            coordinator.submit({ pipes.transport }) {
                result.set(it); callbacks.incrementAndGet(); completed.countDown()
            }
            assertArrayEquals("PING".toByteArray(), usbBounded { pipes.read(4) })
            assertTrue(pipes.entered.await(5, TimeUnit.SECONDS))
            assertFalse(pipes.exited.await(200, TimeUnit.MILLISECONDS))
            if (timeout) {
                assertTrue(completed.await(5, TimeUnit.SECONDS))
                assertEquals("USB bootstrap deadline exceeded", result.get().exceptionOrNull()?.message)
            }
            usbBounded { coordinator.close() }
            pipes.assertReleased()
            assertNotNull(pipes.failure.get())
            pipes.thread.get().join(5_000)
            assertFalse(pipes.thread.get().isAlive)
            assertEquals(if (timeout) 1 else 0, callbacks.get())
        } finally {
            usbBounded { coordinator.close(); pipes.close() }
        }
    }

    @Test fun withheldPreflightWatchdogReleasesReader() = blockedPreflight(true)
    @Test fun explicitCloseReleasesReaderWithoutCallback() = blockedPreflight(false)
}
