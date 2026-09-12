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
}
