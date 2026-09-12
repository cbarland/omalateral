package com.lateral.privileged

import org.junit.Assert.*
import org.junit.Test

class FocusRecoveryGateTest {
    @Test fun transientHomeIsRepairableOnlyDuringForegroundOriginatedTransaction() {
        val gate = FocusRecoveryGate()
        assertNull(gate.begin(7, 1, 0))
        val ticket = gate.begin(7, 7, 0)!!
        assertTrue(gate.permits(7, ticket, 1, true, 100))
        assertFalse(gate.permits(7, ticket, 1, true, 2500))
    }
    @Test fun homeOrRecentsCancelsEvenIfWorkspaceStillAppearsTop() {
        val gate = FocusRecoveryGate()
        val ticket = gate.begin(7, 7, 0)!!
        gate.userNavigation()
        assertFalse(gate.permits(7, ticket, 1, true, 1))
        assertNull(gate.begin(7, 7, 2))
        assertNull(gate.ticket(7, 2))
    }
    @Test fun anotherAppRevokesLeaseRatherThanGettingReplaced() {
        val gate = FocusRecoveryGate()
        val ticket = gate.begin(7, 7, 0)!!
        assertFalse(gate.permits(7, ticket, 9, false, 100))
        assertFalse(gate.permits(7, ticket, 1, true, 110))
    }
    @Test fun unknownForegroundFailsClosed() {
        val gate = FocusRecoveryGate()
        val ticket = gate.begin(7, 7, 0)!!
        assertFalse(gate.permits(7, ticket, null, false, 100))
    }
    @Test fun onlyFreshForegroundInteractionRearmsAndOldCallbacksStayCancelled() {
        val gate = FocusRecoveryGate()
        val old = gate.begin(7, 7, 0)!!
        gate.userNavigation()
        gate.userInteraction(7, 9)
        assertNull(gate.begin(7, 7, 100))
        gate.userInteraction(7, 7)
        val fresh = gate.begin(7, 7, 200)!!
        assertFalse(gate.permits(7, old, 1, true, 201))
        assertTrue(gate.permits(7, fresh, 1, true, 201))
    }
    @Test fun concurrentBatchTransactionsShareAuthorityWithoutRevivingCancelledWork() {
        val gate = FocusRecoveryGate()
        val first = gate.begin(7, 7, 0)!!
        assertEquals(first, gate.begin(7, 7, 100))
        gate.userNavigation()
        assertNull(gate.begin(7, 7, 110))
        assertFalse(gate.permits(7, first, 1, true, 120))
    }
}
