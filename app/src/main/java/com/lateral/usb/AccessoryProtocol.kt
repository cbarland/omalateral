package com.lateral.usb

internal object AccessoryProtocol {
    fun matches(manufacturer: String?, model: String?, version: String?): Boolean =
        manufacturer == "LATERAL_" && model == "USB Host Prototype" && version == "1.0"

    fun ping(): ByteArray = byteArrayOf(0x50, 0x49, 0x4e, 0x47)

    fun isPong(bytes: ByteArray): Boolean =
        bytes.contentEquals(byteArrayOf(0x50, 0x4f, 0x4e, 0x47))
}
