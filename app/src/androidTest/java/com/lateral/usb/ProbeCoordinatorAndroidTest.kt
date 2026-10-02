package com.lateral.usb

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
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

/** Real Android descriptors; no USB device, network, or privileged backend required. */
@RunWith(AndroidJUnit4::class)
class ProbeCoordinatorAndroidTest {
    private fun await(latch: CountDownLatch) =
        assertTrue("Timed out waiting for descriptor operation", latch.await(5, TimeUnit.SECONDS))

    // Never invoke potentially blocking close/read on the instrumentation thread.
    private fun <T> bounded(action: () -> T): T {
        val task = FutureTask<T> { action() }
        Thread(task, "aoa-test-operation").apply { isDaemon = true; start() }
        return task.get(5, TimeUnit.SECONDS)
    }

    private class Endpoint(val pfd: ParcelFileDescriptor) : Closeable {
        val fd = pfd.fileDescriptor
        val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        val output = FileOutputStream(fd)
        override fun close() {
            // Identical ownership and ordering to AccessoryController: no dup,
            // input owns the PFD and output only borrows its FileDescriptor.
            try { input.close() } finally { output.close() }
        }
    }

    private class Pipes : Closeable {
        private val incoming = ParcelFileDescriptor.createPipe()
        private val outgoing = ParcelFileDescriptor.createPipe()
        val reader = Endpoint(incoming[0])
        val peerWriter = Endpoint(incoming[1])
        val peerReader = Endpoint(outgoing[0])
        val writer = Endpoint(outgoing[1])
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val readFailure = AtomicReference<Throwable>()
        val bytesRead = AtomicInteger()
        val closes = AtomicInteger()
        val fragments = Array(4) { CountDownLatch(1) }
        val transport = object : ProbeTransport {
            override val input = object : FilterInputStream(reader.input) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    worker.set(Thread.currentThread())
                    entered.countDown()
                    return try {
                        super.read(b, off, len).also { count ->
                            if (count > 0) {
                                val total = bytesRead.addAndGet(count)
                                fragments[total - 1].countDown()
                            }
                        }
                    } catch (failure: Throwable) {
                        readFailure.set(failure)
                        throw failure
                    } finally { exited.countDown() }
                }
            }
            override val output = writer.output
            override fun close() {
                closes.incrementAndGet()
                try {
                    try { reader.close() } finally { writer.close() }
                } finally { closed.countDown() }
            }
        }

        fun ping(): ByteArray {
            val bytes = ByteArray(4)
            var offset = 0
            while (offset < bytes.size) {
                val count = peerReader.input.read(bytes, offset, bytes.size - offset)
                check(count > 0) { "EOF before PING" }
                offset += count
            }
            return bytes
        }

        override fun close() {
            // Peers stay open throughout assertions: EOF cannot fake cancellation.
            var failure: Throwable? = null
            for (endpoint in listOf(reader, writer, peerWriter, peerReader)) {
                try { endpoint.close() } catch (t: Throwable) { failure = failure ?: t }
            }
            failure?.let { throw it }
        }
    }

    private fun assertBlocked(pipes: Pipes) {
        assertArrayEquals("PING".toByteArray(Charsets.US_ASCII), bounded { pipes.ping() })
        await(pipes.entered)
        assertFalse("Read completed while peer withheld PONG", pipes.exited.await(200, TimeUnit.MILLISECONDS))
        assertTrue(pipes.reader.fd.valid())
    }

    private fun assertReleased(pipes: Pipes) {
        await(pipes.closed)
        await(pipes.exited)
        assertFalse("Read PFD still valid", pipes.reader.fd.valid())
        assertFalse("Write PFD still valid", pipes.writer.fd.valid())
        assertNotNull("Blocked read must fail on local close", pipes.readFailure.get())
        assertEquals(0, pipes.bytesRead.get())
        assertEquals(1, pipes.closes.get())
        assertEquals("Extra bytes after PING", -1, bounded { pipes.peerReader.input.read() })
    }

    private fun fixture(timeoutMs: Long, action: (Pipes, ProbeCoordinator) -> Unit) {
        val pipes = Pipes()
        val coordinator = ProbeCoordinator(timeoutMs)
        try { action(pipes, coordinator) } finally {
            try { bounded { coordinator.close() } } finally { bounded { pipes.close() } }
        }
    }

    @Test fun withheldPongWatchdogClosesOwnerAndReleasesAndroidRead() = fixture(2_000) { pipes, coordinator ->
        val completed = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        val callbacks = AtomicInteger()
        coordinator.submit({ pipes.transport }) {
            callbacks.incrementAndGet()
            result.set(it)
            completed.countDown()
        }
        assertBlocked(pipes)
        await(completed) // Also bounds watchdog's transport.close while holding its lock.
        assertEquals("PING/PONG deadline exceeded", result.get().exceptionOrNull()?.message)
        assertReleased(pipes)
        bounded { coordinator.close() }
        pipes.worker.get().join(5_000)
        assertFalse("Coordinator worker did not exit", pipes.worker.get().isAlive)
        assertEquals(1, callbacks.get())
    }

    @Test fun explicitCloseReleasesAndroidReadWithoutStaleCallback() = fixture(30_000) { pipes, coordinator ->
        val callbacks = AtomicInteger()
        coordinator.submit({ pipes.transport }) { callbacks.incrementAndGet() }
        assertBlocked(pipes)
        bounded { coordinator.close() }
        assertReleased(pipes)
        // Reader exit alone precedes drain's callback decision; wait for worker exit too.
        pipes.worker.get().join(5_000)
        assertFalse("Coordinator worker did not exit", pipes.worker.get().isAlive)
        assertEquals(0, callbacks.get())
    }

    @Test fun fragmentedPongAccumulatesExactlyFourBytes() = fixture(10_000) { pipes, coordinator ->
        val completed = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        coordinator.submit({ pipes.transport }) { result.set(it); completed.countDown() }
        assertBlocked(pipes)
        for ((index, byte) in "PONG".toByteArray(Charsets.US_ASCII).withIndex()) {
            bounded { pipes.peerWriter.output.write(byte.toInt()) }
            await(pipes.fragments[index]) // Force distinct kernel reads, not coalesced writes.
            if (index < 3) assertEquals(1L, completed.count)
        }
        await(completed)
        assertTrue(result.get().isSuccess)
        assertEquals(4, pipes.bytesRead.get())
        assertEquals(1, pipes.closes.get())
        assertFalse(pipes.reader.fd.valid())
        assertFalse(pipes.writer.fd.valid())
        assertEquals("Extra bytes after PING", -1, bounded { pipes.peerReader.input.read() })
    }
}
