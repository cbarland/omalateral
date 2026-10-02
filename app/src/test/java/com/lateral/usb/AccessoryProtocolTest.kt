package com.lateral.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessoryProtocolTest {
    @Test fun identityRequiresAllThreeExactFields() {
        assertTrue(AccessoryProtocol.matches("LATERAL_", "USB Host Prototype", "1.0"))
        assertFalse(AccessoryProtocol.matches("lateral_", "USB Host Prototype", "1.0"))
        assertFalse(AccessoryProtocol.matches("LATERAL_", "USB Host Prototype ", "1.0"))
        assertFalse(AccessoryProtocol.matches("LATERAL_", "USB Host Prototype", "1.1"))
        assertFalse(AccessoryProtocol.matches(null, "USB Host Prototype", "1.0"))
        assertFalse(AccessoryProtocol.matches("LATERAL_", null, "1.0"))
        assertFalse(AccessoryProtocol.matches("LATERAL_", "USB Host Prototype", null))
    }

    @Test fun wireFormatHasNoTerminatorAndRequiresCompletePong() {
        assertArrayEquals("PING".toByteArray(Charsets.US_ASCII), AccessoryProtocol.ping())
        assertTrue(AccessoryProtocol.isPong("PONG".toByteArray(Charsets.US_ASCII)))
        for (invalid in listOf("", "P", "PON", "PING", "pong", "PONG\n", "PONGx")) {
            assertFalse(AccessoryProtocol.isPong(invalid.toByteArray(Charsets.US_ASCII)))
        }
    }
}
