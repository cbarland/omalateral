package com.lateral.privileged

import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Both the remote stop transaction and observed death are bounded. */
internal object HelperShutdown {
    fun awaitDeath(binder: IBinder?, stop: () -> Unit) {
        val died = CountDownLatch(1)
        val death = IBinder.DeathRecipient { died.countDown() }
        val linked = binder != null && runCatching { binder.linkToDeath(death, 0); true }.getOrDefault(false)
        val request = FutureTask { runCatching(stop) }
        Thread(request, "lateral-helper-stop").apply { isDaemon = true; start() }
        try {
            val outcome = request.get(5, TimeUnit.SECONDS)
            if (binder == null) { outcome.getOrThrow(); return }
            if (binder.pingBinder() && !died.await(5, TimeUnit.SECONDS)) {
                throw IllegalStateException("Helper shutdown could not be confirmed", outcome.exceptionOrNull())
            }
        } finally {
            request.cancel(true)
            if (linked) runCatching { binder?.unlinkToDeath(death, 0) }
        }
    }
}
