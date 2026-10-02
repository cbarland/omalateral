package com.lateral.usb

import com.lateral.privileged.PrivilegedService
import com.lateral.privileged.UsbBootstrapAttempt
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Owns the same transport across PONG and BOOT; never transfers it to ProbeCoordinator. */
internal interface BootstrapService {
    fun begin(attempt: UsbBootstrapAttempt)
    fun cancel(attempt: UsbBootstrapAttempt)
    fun shellAccepted(attempt: UsbBootstrapAttempt)
}

private object AndroidBootstrapService : BootstrapService {
    override fun begin(attempt: UsbBootstrapAttempt) = PrivilegedService.beginUsb(attempt)
    override fun cancel(attempt: UsbBootstrapAttempt) = PrivilegedService.cancelUsb(attempt)
    override fun shellAccepted(attempt: UsbBootstrapAttempt) = PrivilegedService.usbShellAccepted(attempt)
}

internal class UsbBootstrapCoordinator(
    private val apk: String,
    private val service: BootstrapService = AndroidBootstrapService,
    private val preflightMs: Long = 5_000,
    private val bootstrapMs: Long = 120_000,
) : Closeable {
    private class Request(val open: () -> ProbeTransport, val result: (Result<Unit>) -> Unit) {
        var ended = false
        var notified = false
        var transport: ProbeTransport? = null
        var timer: ScheduledFuture<*>? = null
        var deadlineVersion = 0L
        var attempt: UsbBootstrapAttempt? = null
    }
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor()
    private val watchdog = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private var active: Request? = null
    private var pending: Request? = null
    private var draining = false
    private var closed = false

    fun submit(open: () -> ProbeTransport, result: (Result<Unit>) -> Unit) {
        cancel()
        synchronized(lock) {
            if (closed) return
            pending = Request(open, result)
            if (!draining) {
                draining = true
                worker.execute { drain() }
            }
        }
    }

    fun cancel() {
        val request = synchronized(lock) { pending = null; active }
        request?.let { finish(it, null) }
    }

    private fun finish(request: Request, result: Result<Unit>?, timeoutVersion: Long? = null) {
        transition(request, result, retain = false, timeoutVersion = timeoutVersion)
    }

    /** Claim notification and detach ownership atomically; all external work follows unlock. */
    private fun transition(request: Request, result: Result<Unit>?, retain: Boolean,
        timeoutVersion: Long? = null) {
        var notify = false
        val resources = synchronized(lock) {
            if (request.ended) return
            if (retain && (active !== request || request.attempt?.cancelled != false)) return
            if (timeoutVersion != null && (request.deadlineVersion != timeoutVersion ||
                    request.attempt?.ready?.getNow(false) == true)) return
            notify = result != null && !request.notified
            if (notify) request.notified = true
            request.deadlineVersion++
            request.timer?.cancel(false)
            request.timer = null
            if (retain) null else {
                request.ended = true
                if (active === request) active = null
                val detached = request.transport to request.attempt
                request.transport = null
                request.attempt = null
                detached
            }
        }
        // Never hold the ownership lock across descriptor close, callbacks, or Binder work.
        try { resources?.second?.let { service.cancel(it) } }
        finally {
            runCatching { resources?.first?.close() }
            if (notify) request.result(checkNotNull(result))
        }
    }

    private fun deadline(request: Request, milliseconds: Long) {
        request.timer?.cancel(false)
        val version = ++request.deadlineVersion
        request.timer = watchdog.schedule({
            finish(request, Result.failure(IOException("USB bootstrap deadline exceeded")), version)
        }, milliseconds, TimeUnit.MILLISECONDS)
    }

    private fun drain() {
        while (true) {
            val request = synchronized(lock) {
                val next = pending
                pending = null
                if (next == null) { draining = false; return }
                active = next
                deadline(next, preflightMs)
                next
            }
            val result = runCatching {
                val transport = request.open()
                val installed = synchronized(lock) {
                    if (request.ended) false else { request.transport = transport; true }
                }
                if (!installed) {
                    transport.close()
                    throw IOException("Cancelled during accessory open")
                }
                AccessoryExchange.exchange(transport.input, transport.output)
                val token = BootstrapProtocol.newToken()
                val frame = BootstrapProtocol.boot(apk, token)
                val attempt = UsbBootstrapAttempt(BootstrapProtocol.tokenHex(token),
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(bootstrapMs))
                synchronized(lock) {
                    check(!request.ended) { "Cancelled after preflight" }
                    request.attempt = attempt
                    deadline(request, bootstrapMs)
                }
                attempt.onEnded {
                    finish(request, Result.failure(IOException("USB helper attempt ended")))
                }
                service.begin(attempt)
                if (!attempt.admitted.get(bootstrapMs, TimeUnit.MILLISECONDS)) {
                    throw IOException("USB bootstrap not admitted")
                }
                check(attempt.valid()) { "USB bootstrap cancelled before BOOT" }
                transport.output.write(frame)
                transport.output.flush()
                val reader = BootstrapProtocol.StatusReader()
                var status = reader.read(transport.input)
                if (status == 0) status = reader.read(transport.input)
                if (status != 1) throw IOException("Board rejected or failed bootstrap: $status")
                service.shellAccepted(attempt)
                val remaining = (attempt.deadlineNanos - System.nanoTime()).coerceAtLeast(1)
                if (!attempt.ready.get(remaining, TimeUnit.NANOSECONDS)) {
                    throw IOException("USB Binder was not accepted")
                }
            }
            if (result.isSuccess) {
                transition(request, result, retain = true)
            } else {
                finish(request, result)
            }
        }
    }

    override fun close() {
        synchronized(lock) { closed = true; pending = null }
        cancel()
        worker.shutdown()
        watchdog.shutdown()
    }
}
