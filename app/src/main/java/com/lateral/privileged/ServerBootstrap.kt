package com.lateral.privileged

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AdbStream

/**
 * Starts [PrivilegedServer] inside an `app_process` invocation over an open ADB shell stream.
 *
 * The backend retains the stream and explicitly stops its helper before releasing it.
 * Stream closure alone is not proof that a detached app_process has exited.
 */
object ServerBootstrap {

    private const val TAG = "Lateral/Privileged"

    /** Fully-qualified class with the `@JvmStatic fun main` `app_process` will invoke. */
    private const val SERVER_CLASS = "com.lateral.privileged.PrivilegedServer"

    /**
     * Launch [PrivilegedServer] over [adb]. Returns the live shell stream (keep it open) or
     * `null` if the APK path could not be resolved or the stream could not be opened.
     */
    fun start(context: Context, adb: AdbConnectionManager, attemptId: Long): AdbStream? {
        val apk = context.applicationInfo.sourceDir
        if (apk.isNullOrEmpty()) {
            Log.e(TAG, "no APK path on applicationInfo — cannot start server")
            return null
        }
        // `CLASSPATH=APK app_process /system/bin --nice-name=NAME CLASS` is the long-standing
        // pattern for running an app's class as a privileged process: the shell sets
        // CLASSPATH, app_process boots the runtime against it, and the named class's
        // `main(String[])` becomes the entry point.
        val serverName = serverName(context)
        val command = "CLASSPATH='$apk' app_process /system/bin --nice-name=$serverName $SERVER_CLASS --adb-attempt=$attemptId --adb-name=$serverName"
        Log.i(TAG, "starting privileged server: $command")
        return runCatching { adb.openStream("shell:$command") }
            .onFailure { Log.e(TAG, "openStream failed", it) }
            .getOrNull()
    }

    private fun serverName(context: Context): String =
        "lateral_${context.packageName.replace('.', '_')}_privileged"
}
