package com.lateral.usb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ProbeCoordinatorTest {
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(3, TimeUnit.SECONDS))

    @Test fun fragmentedPongAndExactPing() {
        val input = object : ByteArrayInputStream("PONG".toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(1, len))
        }
        val output = ByteArrayOutputStream()
        AccessoryExchange.exchange(input, output)
        assertArrayEquals("PING".toByteArray(), output.toByteArray())
    }

    @Test fun malformedAndEarlyEofFail() {
        for (reply in listOf("PING", "pong", "", "PON")) {
            assertTrue(runCatching {
                AccessoryExchange.exchange(ByteArrayInputStream(reply.toByteArray()), ByteArrayOutputStream())
            }.isFailure)
        }
    }

    private class Connection : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
        val peer = listener.accept()
        val reading = CountDownLatch(1)
        val released = CountDownLatch(1)
        val closes = AtomicInteger()
        val transport = object : ProbeTransport {
            override val input = object : FilterInputStream(client.getInputStream()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    reading.countDown()
                    return try { super.read(b, off, len) } finally { released.countDown() }
                }
            }
            override val output = client.getOutputStream()
            override fun close() {
                closes.incrementAndGet()
                client.close()
            }
        }
        override fun close() {
            client.close()
            peer.close()
            listener.close()
        }
    }

    @Test fun surrogateCrossThreadCloseReleasesReader() {
        Connection().use { connection ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<Boolean> {
                    runCatching { connection.transport.input.read(ByteArray(4)) }.isFailure
                }
                await(connection.reading)
                assertFalse(future.isDone)
                connection.transport.close()
                assertTrue(future.get(3, TimeUnit.SECONDS))
                await(connection.released)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun withheldReplyTimesOutAndClosesReader() {
        Connection().use { connection ->
            ProbeCoordinator(500).use { coordinator ->
                val completed = CountDownLatch(1)
                var result: Result<Unit>? = null
                coordinator.submit({ connection.transport }) { result = it; completed.countDown() }
                await(connection.reading)
                await(completed)
                await(connection.released)
                assertTrue(result!!.isFailure)
                assertEquals(1, connection.closes.get())
            }
        }
    }

    @Test fun explicitCloseCancelsBlockedReadWithoutResult() {
        Connection().use { connection ->
            val callbacks = AtomicInteger()
            val coordinator = ProbeCoordinator(10_000)
            try {
                coordinator.submit({ connection.transport }) { callbacks.incrementAndGet() }
                await(connection.reading)
                coordinator.close()
                await(connection.released)
                assertEquals(1, connection.closes.get())
                assertEquals(0, callbacks.get())
            } finally { coordinator.close() }
        }
    }

    @Test fun cancelledOpenClosesLateResourceAndOnlyLatestPendingRuns() {
        ProbeCoordinator(10_000).use { coordinator ->
            val opening = CountDownLatch(1)
            val releaseOpen = CountDownLatch(1)
            val lateClosed = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val callbacks = AtomicInteger()
            val skipped = AtomicInteger()
            fun transport(onClose: () -> Unit = {}) = object : ProbeTransport {
                override val input = ByteArrayInputStream("PONG".toByteArray())
                override val output = ByteArrayOutputStream()
                override fun close() = onClose()
            }
            try {
                coordinator.submit({
                    opening.countDown()
                    check(releaseOpen.await(3, TimeUnit.SECONDS))
                    transport { lateClosed.countDown() }
                }) { callbacks.incrementAndGet() }
                await(opening)
                repeat(100) {
                    coordinator.submit({ skipped.incrementAndGet(); transport() }) { callbacks.incrementAndGet() }
                }
                coordinator.submit({ transport() }) { completed.countDown() }
                releaseOpen.countDown()
                await(lateClosed)
                await(completed)
                assertEquals(0, skipped.get())
                assertEquals(0, callbacks.get())
            } finally { releaseOpen.countDown() }
        }
    }
}
