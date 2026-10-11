package dev.omalauncher.linux

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.system.Os
import android.util.Log
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import java.io.File
import kotlin.concurrent.thread

private const val TAG = "open"

/**
 * `xdg-open` for the guest: URLs are written to a FIFO in the shared runtime
 * dir and opened with Android's browser. That's what makes agent logins work:
 * the OAuth page opens on the phone and redirects back to the agent's
 * localhost listener, since the guest shares the phone's network.
 */
class OpenBridge(
    private val context: Context,
    private val proot: Proot,
    private val onOpenFiles: (String) -> Unit,
) {
    private val fifo = File(proot.runtimeDir, "open.fifo")
    private val guestFifo = "${Proot.GUEST_RUNTIME_DIR}/${fifo.name}"

    private var started = false

    fun start() {
        if (started) return
        started = true
        fifo.delete()
        Os.mkfifo(fifo.path, 0x180) // 0600
        thread(name = "open-bridge", isDaemon = true) {
            while (true) {
                // Each writer closes the FIFO after one URL; reopen for the next.
                try {
                    fifo.bufferedReader().useLines { lines -> lines.forEach(::open) }
                } catch (e: Exception) {
                    Log.e(TAG, "bridge stopped", e)
                    return@thread
                }
            }
        }
    }

    private fun open(target: String) {
        val url = target.trim()
        // Written by the omarchy-files shim: open the Files app at a guest path.
        if (url.startsWith(FILES_PREFIX)) return onOpenFiles(url.removePrefix(FILES_PREFIX))
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Log.i(TAG, "ignoring non-URL $url")
            return
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no browser for $url")
        }
    }

    /** The shims; /usr/local/bin precedes /usr/bin, so xdg-open wins over xdg-utils if that's installed. */
    fun managed(): List<Managed> {
        val bin = File(proot.rootfs, "usr/local/bin")
        return listOf(
            Managed.text(File(bin, "xdg-open"), Policy.Owned, executable = true) {
                """
                #!/bin/sh
                # omalauncher: opens URLs in the Android browser (see OpenBridge.kt)
                timeout 2 sh -c 'printf "%s\n" "${'$'}1" > $guestFifo' sh "${'$'}1"
                """.trimIndent() + "\n"
            },
            Managed.text(File(bin, FILES_COMMAND), Policy.Owned, executable = true) {
                """
                #!/bin/sh
                # omalauncher: opens the launcher's Files app, at a folder if given (see OpenBridge.kt)
                dir=${'$'}(realpath -- "${'$'}{1:-.}" 2>/dev/null) || dir=${'$'}PWD
                [ -d "${'$'}dir" ] || dir=${'$'}(dirname -- "${'$'}dir")
                timeout 2 sh -c 'printf "%s\n" "${'$'}1" > $guestFifo' sh "$FILES_PREFIX${'$'}dir"
                """.trimIndent() + "\n"
            },
        )
    }

    private companion object {
        const val FILES_PREFIX = "omarchy-files:"
    }
}
