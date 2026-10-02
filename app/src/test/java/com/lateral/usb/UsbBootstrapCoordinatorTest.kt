package com.lateral.usb

import com.lateral.privileged.UsbBootstrapAttempt
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class UsbBootstrapCoordinatorTest {
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(3, TimeUnit.SECONDS))
    private class Transport(status: Int = 1) : ProbeTransport {
        override val input = ByteArrayInputStream("PONG".toByteArray() + byteArrayOf(66,83,84,65,1,status.toByte()))
        override val output = ByteArrayOutputStream()
        val closed = CountDownLatch(1)
        val closes = AtomicInteger()
        override fun close() { closes.incrementAndGet(); closed.countDown() }
    }
    private class Service(val allow: Boolean = true) : BootstrapService {
        lateinit var attempt: UsbBootstrapAttempt
        val begun = CountDownLatch(1)
        val shell = CountDownLatch(1)
        override fun begin(attempt: UsbBootstrapAttempt) {
            this.attempt = attempt
            attempt.admitted.complete(allow)
            begun.countDown()
        }
        override fun cancel(attempt: UsbBootstrapAttempt) = attempt.cancel()
        override fun shellAccepted(attempt: UsbBootstrapAttempt) { shell.countDown() }
    }

    @Test fun shellAcceptanceAloneDoesNotFinishAndTransportSurvivesPreflight() {
        val service = Service()
        val transport = Transport()
        val done = CountDownLatch(1)
        UsbBootstrapCoordinator("/data/app/a.apk", service).use { coordinator ->
            coordinator.submit({ transport }) { assertTrue(it.isSuccess); done.countDown() }
            await(service.shell)
            assertFalse(done.await(100, TimeUnit.MILLISECONDS))
            assertEquals(1L, transport.closed.count)
            val bytes = transport.output.toByteArray()
            assertEquals("PINGBOOT", bytes.copyOfRange(0,8).toString(Charsets.US_ASCII))
            assertEquals(service.attempt.token, BootstrapProtocol.tokenHex(bytes.takeLast(16).toByteArray()))
            service.attempt.ready.complete(true)
            await(done)
            assertFalse(transport.closed.await(100, TimeUnit.MILLISECONDS))
            assertFalse(service.attempt.cancelled)
            coordinator.cancel()
            await(transport.closed)
            coordinator.cancel()
            assertTrue(service.attempt.cancelled)
            assertEquals(1, transport.closes.get())
        }
    }

    @Test fun healthyServiceDeniesBootAndBoardRejectionFails() {
        for (allow in listOf(false, true)) {
            val service = Service(allow)
            val transport = Transport(2)
            val done = CountDownLatch(1)
            UsbBootstrapCoordinator("/data/app/a.apk", service).use { coordinator ->
                coordinator.submit({ transport }) { assertTrue(it.isFailure); done.countDown() }
                await(done)
                assertEquals(1L, service.shell.count)
                if (!allow) assertEquals("PING", transport.output.toString("US-ASCII"))
                assertFalse(service.attempt.valid())
            }
        }
    }

    @Test fun readyThenTerminationClosesOnceAndCannotCloseReplacement() {
        val callbacks = AtomicInteger()
        val service = object : BootstrapService {
            lateinit var attempt: UsbBootstrapAttempt
            override fun begin(attempt: UsbBootstrapAttempt) {
                this.attempt = attempt
                attempt.admitted.complete(true)
            }
            override fun shellAccepted(attempt: UsbBootstrapAttempt) { attempt.ready.complete(true) }
            override fun cancel(attempt: UsbBootstrapAttempt) = attempt.cancel()
        }
        val old = Transport()
        val replacement = Transport()
        UsbBootstrapCoordinator("/data/app/a.apk", service).use { coordinator ->
            val ready = CountDownLatch(1)
            coordinator.submit({ old }) { assertTrue(it.isSuccess); callbacks.incrementAndGet(); ready.countDown() }
            await(ready)
            val dead = service.attempt
            dead.cancel()
            await(old.closed)
            val nextReady = CountDownLatch(1)
            coordinator.submit({ replacement }) {
                assertTrue(it.isSuccess); callbacks.incrementAndGet(); nextReady.countDown()
            }
            await(nextReady)
            dead.cancel()
            assertFalse(replacement.closed.await(100, TimeUnit.MILLISECONDS))
            coordinator.cancel()
            assertEquals(1, old.closes.get())
            assertEquals(1, replacement.closes.get())
            assertEquals(2, callbacks.get())
        }
    }

    @Test fun binderTimeoutClosesTransportAndInvalidatesToken() {
        val service = Service()
        val transport = Transport()
        val done = CountDownLatch(1)
        UsbBootstrapCoordinator("/data/app/a.apk", service, bootstrapMs = 200).use { coordinator ->
            coordinator.submit({ transport }) { assertTrue(it.isFailure); done.countDown() }
            await(service.shell)
            await(done)
            await(transport.closed)
            assertFalse(service.attempt.valid())
        }
    }

    @Test fun detachSuppressesCallbackAndLateOpenClosesWithoutPing() {
        val service = Service()
        val transport = Transport()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbacks = AtomicInteger()
        UsbBootstrapCoordinator("/data/app/a.apk", service).use { coordinator ->
            coordinator.submit({ entered.countDown(); await(release); transport }) { callbacks.incrementAndGet() }
            await(entered)
            coordinator.cancel()
            release.countDown()
            await(transport.closed)
            assertEquals(0, transport.output.size())
            assertEquals(0, callbacks.get())
        }
    }
}
