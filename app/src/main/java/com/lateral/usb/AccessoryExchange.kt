package com.lateral.usb

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Fixed-size wire exchange; cancellation is provided by closing the transport. */
internal object AccessoryExchange {
    fun exchange(input: InputStream, output: OutputStream) {
        output.write(AccessoryProtocol.ping())
        val reply = ByteArray(4)
        var offset = 0
        while (offset < reply.size) {
            val count = input.read(reply, offset, reply.size - offset)
            if (count < 0) throw EOFException("EOF before PONG")
            if (count == 0) throw IOException("Zero-byte read")
            offset += count
        }
        if (!AccessoryProtocol.isPong(reply)) throw IOException("Unexpected four-byte reply")
    }
}
