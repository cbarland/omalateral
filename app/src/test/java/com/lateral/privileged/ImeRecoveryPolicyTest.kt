package com.lateral.privileged

import org.junit.Assert.*
import org.junit.Test

class ImeRecoveryPolicyTest {
    @Test fun unknownSelectionNeverDiscardsThePreviousKeyboard() {
        assertEquals(ImeRecoveryPolicy.RestoreAction.RETRY,
            ImeRecoveryPolicy.restoreAction("lateral.floris", "gboard", ""))
        assertEquals(ImeRecoveryPolicy.RestoreAction.SELECT_PREVIOUS,
            ImeRecoveryPolicy.restoreAction("lateral.floris", "gboard", "lateral.floris"))
        assertEquals(ImeRecoveryPolicy.RestoreAction.DONE,
            ImeRecoveryPolicy.restoreAction("lateral.floris", "gboard", "gboard"))
    }
    @Test fun failuresRetainRecordUntilAnotherImeIsVerified() {
        for (failure in listOf(-1, -2, 1, 2, 3, 4, 5, 7, 8)) {
            assertFalse(ImeRecoveryPolicy.confirmed(failure, null, "session"))
            assertFalse(ImeRecoveryPolicy.confirmed(failure, "session", "session"))
            assertFalse(ImeRecoveryPolicy.confirmed(failure, "", "session"))
            assertTrue(ImeRecoveryPolicy.confirmed(failure, "user.keyboard", "session"))
        }
    }
    @Test fun successAndNotSelectedConfirmRestoration() {
        assertTrue(ImeRecoveryPolicy.confirmed(0, null, "session"))
        assertTrue(ImeRecoveryPolicy.confirmed(6, null, "session"))
    }
}
