package com.lateral.usb

import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom

/** Firmware bootstrap framing. Shell acceptance is not Binder readiness. */
internal object BootstrapProtocol {
    fun newToken(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun tokenHex(token: ByteArray): String {
        require(token.size == 16)
        return token.joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun boot(path: String, token: ByteArray): ByteArray {
        val bytes = path.toByteArray(Charsets.UTF_8)
        require(path.startsWith("/data/app/") && path.endsWith(".apk"))
        require(path.none { Character.isISOControl(it) })
        require(bytes.toString(Charsets.UTF_8) == path)
        require(bytes.size in 1..512 && token.size == 16)
        return byteArrayOf(66, 79, 79, 84, 1, (bytes.size and 255).toByte(),
            (bytes.size ushr 8).toByte()) + bytes + token
    }

    class StatusReader {
        private var interim = false
        private var terminal = false

        fun read(input: InputStream): Int {
            if (terminal) throw IOException("BSTA after terminal status")
            val frame = ByteArray(6)
            var offset = 0
            while (offset < frame.size) {
                val count = input.read(frame, offset, frame.size - offset)
                if (count < 0) throw IOException("Truncated BSTA")
                if (count == 0) throw IOException("Zero-byte BSTA read")
                offset += count
            }
            if (!frame.copyOfRange(0, 5).contentEquals(byteArrayOf(66, 83, 84, 65, 1))) {
                throw IOException("Invalid BSTA header/version")
            }
            val status = frame[5].toInt() and 255
            when (status) {
                0 -> {
                    if (interim) throw IOException("Repeated interim BSTA")
                    interim = true
                }
                1, 2, 3 -> terminal = true
                else -> throw IOException("Invalid BSTA status")
            }
            return status
        }
    }
}
