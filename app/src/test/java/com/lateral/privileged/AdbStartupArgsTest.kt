package com.lateral.privileged

import org.junit.Assert.*
import org.junit.Test

class AdbStartupArgsTest {
    @Test fun wirelessArgumentsAreCorrelatedAndDoNotEnterUsbAdmission() {
        val args = arrayOf("--adb-attempt=7", "--adb-name=lateral_com_lateral_dev_privileged")
        assertEquals(AdbStartupArgs(7, "lateral_com_lateral_dev_privileged"), AdbStartupArgs.parse(args))
        assertNull(AdbStartupArgs.parse(arrayOf("--usb-adb-only")))
        assertNull(AdbStartupArgs.parse(emptyArray()))
    }

    @Test fun invalidOrAmbiguousAttemptsAreRejected() {
        for (args in listOf(
            arrayOf("--adb-attempt=0", "--adb-name=lateral_com_lateral_privileged"),
            arrayOf("--adb-attempt=4", "--adb-name=shizuku"),
            arrayOf("--adb-attempt=4"),
            arrayOf("--adb-attempt=4", "--adb-name=lateral_com_lateral_privileged", "--usb-adb-only"),
        )) assertThrows(IllegalArgumentException::class.java) { AdbStartupArgs.parse(args) }
    }
}
