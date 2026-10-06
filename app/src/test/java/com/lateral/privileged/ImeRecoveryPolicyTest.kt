package com.lateral.privileged

import org.junit.Assert.*
import org.junit.Test

class ImeRecoveryPolicyTest {
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
