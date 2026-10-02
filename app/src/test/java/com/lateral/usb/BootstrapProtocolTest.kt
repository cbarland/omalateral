package com.lateral.usb

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class BootstrapProtocolTest {
    @Test fun exactFrameAndMaximumLength() {
        val path = "/data/app/" + "a".repeat(498) + ".apk"
        val token = ByteArray(16) { it.toByte() }
        val frame = BootstrapProtocol.boot(path, token)
        assertEquals(535, frame.size)
        assertArrayEquals(byteArrayOf(66,79,79,84,1,0,2), frame.copyOfRange(0,7))
        assertEquals(path, frame.copyOfRange(7,519).toString(Charsets.UTF_8))
        assertArrayEquals(token, frame.copyOfRange(519,535))
        assertEquals("000102030405060708090a0b0c0d0e0f", BootstrapProtocol.tokenHex(token))
    }

    @Test fun rejectsInvalidPathsAndTokens() {
        for (path in listOf("", "/tmp/app.apk", "/data/app/a.zip", "/data/app/a\u0000.apk",
            "/data/app/a\n.apk", "/data/app/" + "é".repeat(250) + ".apk",
            "/data/app/\uD800.apk")) {
            assertThrows(IllegalArgumentException::class.java) { BootstrapProtocol.boot(path, ByteArray(16)) }
        }
        for (size in listOf(0,15,17)) {
            assertThrows(IllegalArgumentException::class.java) {
                BootstrapProtocol.boot("/data/app/a.apk", ByteArray(size))
            }
        }
        assertEquals(16, BootstrapProtocol.newToken().size)
    }

    private fun frame(status: Int) = byteArrayOf(66,83,84,65,1,status.toByte())

    @Test fun fragmentedInterimAndTerminal() {
        val input = object : ByteArrayInputStream(frame(0) + frame(1)) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b,off,minOf(1,len))
        }
        val reader = BootstrapProtocol.StatusReader()
        assertEquals(0, reader.read(input))
        assertEquals(1, reader.read(input))
        assertThrows(IOException::class.java) { reader.read(ByteArrayInputStream(frame(1))) }
    }

    @Test fun rejectsMalformedAndRepeatedFrames() {
        for (bytes in listOf(frame(4), frame(255), frame(1).copyOf(5),
            frame(1).also { it[4] = 2 }, frame(1).also { it[0] = 0 })) {
            assertThrows(IOException::class.java) { BootstrapProtocol.StatusReader().read(ByteArrayInputStream(bytes)) }
        }
        val reader = BootstrapProtocol.StatusReader()
        reader.read(ByteArrayInputStream(frame(0)))
        assertThrows(IOException::class.java) { reader.read(ByteArrayInputStream(frame(0))) }
        for (status in 1..3) assertEquals(status,
            BootstrapProtocol.StatusReader().read(ByteArrayInputStream(frame(status))))
    }
}
