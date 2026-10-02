package com.lateral.privileged

import org.junit.Assert.*
import org.junit.Test

class UsbBootstrapAttemptTest {
    @Test fun detachedBootstrapModeIsStrictAndDistinctFromExistingModes() {
        val detached = UsbLifeline.mode(arrayOf("--usb-adb-bootstrap"))
        assertTrue(detached.adbOnly)
        assertTrue(detached.detached)
        assertTrue(detached.token?.matches(Regex("[0-9a-f]{32}")) == true)
        val ordinary = UsbLifeline.mode(arrayOf("--usb-adb-only"))
        assertTrue(ordinary.adbOnly)
        assertFalse(ordinary.detached)
        assertNull(UsbLifeline.mode(emptyArray()).token)
        listOf(arrayOf("--usb-adb-bootstrap", "extra"), arrayOf("--usb-lifeline=bad"))
            .forEach { args -> assertThrows(IllegalArgumentException::class.java) { UsbLifeline.mode(args) } }
    }

    @Test fun terminationSurvivesReadyAndLateRegistrationWithoutHoldingAttemptLock() {
        val attempt = UsbBootstrapAttempt("a".repeat(32), Long.MAX_VALUE)
        attempt.admitted.complete(true)
        attempt.ready.complete(true)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val listener = {
            assertFalse(Thread.holdsLock(attempt))
            calls.incrementAndGet()
            attempt.cancel()
        }
        attempt.onEnded(listener)
        attempt.cancel()
        attempt.cancel()
        attempt.onEnded(listener)
        assertEquals(2, calls.get())
        assertTrue(attempt.admitted.get())
        assertTrue(attempt.ready.get())
    }

    @Test fun tokenSessionDeadlineAndCancellationFailClosed() {
        val attempt = UsbBootstrapAttempt("a".repeat(32), 100)
        assertTrue(attempt.matches("a".repeat(32), 99))
        assertFalse(attempt.matches("b".repeat(32), 99))
        assertFalse(attempt.matches("a".repeat(32), 100))
        attempt.cancel()
        assertFalse(attempt.matches("a".repeat(32), 1))
        assertFalse(attempt.admitted.get())
        assertFalse(attempt.ready.get())
    }

    @Test fun helperArgumentsAreStrict() {
        assertNull(UsbLifeline.parse(emptyArray()))
        assertEquals("ab".repeat(16), UsbLifeline.parse(arrayOf("--usb-lifeline=" + "AB".repeat(16))))
        for (args in listOf(arrayOf("--usb-lifeline="), arrayOf("--usb-lifeline=" + "a".repeat(31)),
            arrayOf("--usb-lifeline=" + "z".repeat(32)), arrayOf("other"),
            arrayOf("--usb-lifeline=" + "a".repeat(32), "extra"))) {
            assertThrows(IllegalArgumentException::class.java) { UsbLifeline.parse(args) }
        }
    }
}
