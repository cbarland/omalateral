package com.lateral.usb

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal interface ProbeTransport : Closeable {
    val input: InputStream
    val output: OutputStream
}

/** One worker, one watchdog, and one replaceable pending request, even if open stalls. */
internal class ProbeCoordinator(private val timeoutMs: Long) : Closeable {
    private enum class State { AWAITING_OPEN, TRANSFERRING, CANCELLED, COMPLETED }
    private class Request(val open: () -> ProbeTransport, val result: (Result<Unit>) -> Unit) {
        var state = State.AWAITING_OPEN
        var transport: ProbeTransport? = null
        var timer: ScheduledFuture<*>? = null
    }

    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor()
    private val watchdog = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private var active: Request? = null
    private var pending: Request? = null
    private var draining = false
    private var closed = false

    fun submit(open: () -> ProbeTransport, result: (Result<Unit>) -> Unit) = synchronized(lock) {
        if (closed) return@synchronized
        active?.let { terminate(it, State.CANCELLED) }
        pending = Request(open, result)
        if (!draining) {
            draining = true
            worker.execute { drain() }
        }
    }

    fun cancel() = synchronized(lock) {
        pending = null
        active?.let { terminate(it, State.CANCELLED) }
    }

    private fun terminate(request: Request, state: State): Boolean {
        if (request.state == State.CANCELLED || request.state == State.COMPLETED) return false
        request.state = state
        request.timer?.cancel(false)
        request.timer = null
        val transport = request.transport
        request.transport = null
        // Close the actual streams, not just an interrupt or a duplicate descriptor.
        runCatching { transport?.close() }
        return true
    }

    private fun drain() {
        while (true) {
            val request = synchronized(lock) {
                val next = pending
                pending = null
                if (next == null) {
                    draining = false
                    return
                }
                active = next
                next.timer = watchdog.schedule({
                    synchronized(lock) {
                        if (terminate(next, State.CANCELLED)) {
                            next.result(Result.failure(IOException("PING/PONG deadline exceeded")))
                        }
                    }
                }, timeoutMs, TimeUnit.MILLISECONDS)
                next
            }
            val result = runCatching {
                val transport = request.open()
                val installed = synchronized(lock) {
                    if (request.state == State.AWAITING_OPEN) {
                        request.transport = transport
                        request.state = State.TRANSFERRING
                        true
                    } else false
                }
                if (!installed) {
                    transport.close()
                    throw IOException("Probe cancelled before transport installation")
                }
                AccessoryExchange.exchange(transport.input, transport.output)
            }
            synchronized(lock) {
                if (terminate(request, State.COMPLETED)) request.result(result)
                active = null
            }
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            cancel()
            // Do not depend on interruption of Binder or stream I/O.
            worker.shutdown()
            watchdog.shutdown()
        }
    }
}
