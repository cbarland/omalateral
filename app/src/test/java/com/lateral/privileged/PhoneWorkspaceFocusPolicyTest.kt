package com.lateral.privileged

import org.junit.Assert.*
import org.junit.Test

class PhoneWorkspaceFocusPolicyTest {
    @Test fun recognizesBothPhoneWorkspaces() {
        assertTrue(PhoneWorkspaceFocusPolicy.accepts("com.lateral", "com.lateral.MainActivity", "com.lateral"))
        assertTrue(PhoneWorkspaceFocusPolicy.accepts("com.lateral", "com.lateral.beast.BeastActivity", "com.lateral"))
    }
    @Test fun neverTreatsHostedOrOtherFlavorAppsAsThePhoneWorkspace() {
        assertFalse(PhoneWorkspaceFocusPolicy.accepts("com.lateral.dev", "com.lateral.MainActivity", "com.lateral"))
        assertFalse(PhoneWorkspaceFocusPolicy.accepts("com.lateral", "other.Activity", "com.lateral"))
    }

    @Test fun restoreRequiresBeastPlacementAndPhoneWorkspaceFocusIndependently() {
        assertTrue(PhoneWorkspaceFocusPolicy.restoreVerified(4, 4, 7, 7))
        // A correctly placed Beast task does not prove display 0 still focuses PhoneUI.
        assertFalse(PhoneWorkspaceFocusPolicy.restoreVerified(4, 4, 9, 7))
        assertFalse(PhoneWorkspaceFocusPolicy.restoreVerified(1, 4, 7, 7))
        assertFalse(PhoneWorkspaceFocusPolicy.restoreVerified(null, 4, 7, 7))
    }
}
