package com.lateral.privileged

import org.junit.Assert.*
import org.junit.Test

class BackendAttemptGateTest {
    private class FakeBackend {
        val starts = mutableListOf<Long>()
        var stopCompletion: ((Result<Unit>) -> Unit)? = null
        fun start(attempt: Long) { starts += attempt }
        fun stop(complete: (Result<Unit>) -> Unit) { stopCompletion = complete }
    }
    private class Driver {
        val gate = BackendAttemptGate(PrivilegeBackendId.SHIZUKU)
        val shizuku = FakeBackend()
        val adb = FakeBackend()
        var ready = false
        fun ensure() {
            if (ready) return
            val attempt = gate.begin() ?: return
            (if (gate.selected == PrivilegeBackendId.SHIZUKU) shizuku else adb).start(attempt)
        }
        fun switch(next: PrivilegeBackendId) {
            gate.invalidate()
            shizuku.stop { result -> if (gate.selectAfterStop(next, result)) { ready = false; ensure() } }
        }
    }
    @Test fun repeatedStartupBindsOnlySelectedBackend() {
        val driver = Driver()
        repeat(20) { driver.ensure() }
        assertEquals(1, driver.shizuku.starts.size)
        assertTrue(driver.adb.starts.isEmpty())
        assertTrue(driver.gate.accepts(PrivilegeBackendId.SHIZUKU, driver.shizuku.starts.single()))
    }
    @Test fun switchWaitsForConfirmedStopAndRejectsOldCallbacks() {
        val driver = Driver(); driver.ensure()
        val old = driver.gate.attemptId
        driver.switch(PrivilegeBackendId.WIRELESS_ADB)
        assertEquals(PrivilegeBackendId.SHIZUKU, driver.gate.selected)
        assertTrue(driver.adb.starts.isEmpty())
        assertFalse(driver.gate.accepts(PrivilegeBackendId.SHIZUKU, old))
        driver.shizuku.stopCompletion!!(Result.success(Unit))
        assertEquals(PrivilegeBackendId.WIRELESS_ADB, driver.gate.selected)
        assertEquals(1, driver.adb.starts.size)
        assertFalse(driver.gate.accepts(PrivilegeBackendId.SHIZUKU, old))
    }
    @Test fun failedStopKeepsSelectionAndDoesNotStartReplacement() {
        val driver = Driver(); driver.ensure(); driver.switch(PrivilegeBackendId.WIRELESS_ADB)
        driver.shizuku.stopCompletion!!(Result.failure(IllegalStateException("still alive")))
        assertEquals(PrivilegeBackendId.SHIZUKU, driver.gate.selected)
        assertTrue(driver.adb.starts.isEmpty())
    }
    @Test fun deniedOrLostShizukuNeverFallsBackToAdb() {
        val driver = Driver(); driver.ensure()
        driver.gate.finished(driver.gate.attemptId)
        driver.ensure()
        assertEquals(2, driver.shizuku.starts.size)
        assertTrue(driver.adb.starts.isEmpty())
    }
    @Test fun callbackFromTimedOutAttemptCannotFinishNewAttempt() {
        val gate = BackendAttemptGate(PrivilegeBackendId.SHIZUKU)
        val old = gate.begin()!!; gate.finished(old)
        val fresh = gate.begin()!!; gate.finished(old)
        assertNull(gate.begin())
        assertFalse(gate.accepts(PrivilegeBackendId.SHIZUKU, old))
        assertTrue(gate.accepts(PrivilegeBackendId.SHIZUKU, fresh))
    }
}
