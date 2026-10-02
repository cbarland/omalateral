package com.lateral.privileged

internal object UsbLifeline {
    data class Mode(val token: String?, val adbOnly: Boolean, val detached: Boolean = false)

    fun mode(args: Array<String>): Mode = if (args.contentEquals(arrayOf("--usb-adb-bootstrap"))) {
        Mode(java.util.UUID.randomUUID().toString().replace("-", ""), true, true)
    } else if (args.contentEquals(arrayOf("--usb-adb-only"))) {
        Mode(java.util.UUID.randomUUID().toString().replace("-", ""), true)
    } else Mode(parse(args), false)

    fun allowsAdmission(uid: Int, token: String?): Boolean =
        uid == 2000 && token?.matches(Regex("[0-9a-f]{32}")) == true

    fun parse(args: Array<String>): String? {
        if (args.isEmpty()) return null
        require(args.size == 1 && args[0].startsWith("--usb-lifeline="))
        return args[0].substringAfter('=').also {
            require(it.matches(Regex("[0-9a-fA-F]{32}")))
        }.lowercase()
    }
}
