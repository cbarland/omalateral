package com.lateral.privileged

/** Wireless bootstrap arguments are separate from the USB admission protocol. */
internal data class AdbStartupArgs(val attemptId: Long, val processName: String) {
    companion object {
        fun parse(args: Array<String>): AdbStartupArgs? {
            if (args.none { it.startsWith("--adb-") }) return null
            require(args.size == 2 && args[0].startsWith("--adb-attempt=") &&
                args[1].startsWith("--adb-name=")) { "Invalid ADB startup arguments" }
            val attempt = args[0].substringAfter('=').toLong()
            val name = args[1].substringAfter('=')
            require(attempt > 0 && name.matches(Regex("lateral_[a-zA-Z0-9_]+_privileged")))
            return AdbStartupArgs(attempt, name)
        }
    }
}
