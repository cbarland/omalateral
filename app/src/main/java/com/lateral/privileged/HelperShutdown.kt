package com.lateral.privileged

import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Completion means observed process death, rather than merely sending a stop transaction. */
internal object HelperShutdown {
    fun awaitDeath(binder: IBinder?, stop: () -> Unit) {
        if (binder == null) {
            stop()
            return
        }
        if (!binder.pingBinder()) { runCatching(stop); return }
        val died = CountDownLatch(1)
        val death = IBinder.DeathRecipient { died.countDown() }
        try {
            val failure = runCatching {
                binder.linkToDeath(death, 0)
                stop()
            }.exceptionOrNull()
            if (binder.pingBinder() && !died.await(5, TimeUnit.SECONDS)) {
                throw IllegalStateException("Helper shutdown could not be confirmed", failure)
            }
        } finally {
            runCatching { binder.unlinkToDeath(death, 0) }
        }
    }
}
