package dev.omalauncher.linux

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Opt-in GPU acceleration for Linux apps. On Mali GPUs (v10+: Pixel 7 and
 * newer, recent MediaTek) PanVK-kbase, a Mesa Vulkan driver that talks to
 * Android's own /dev/mali0, gives the guest Vulkan; Arch's stock Mesa runs
 * OpenGL on top of it with zink. Frames still reach us as wl_shm buffers
 * (MESA_VK_WSI_DEBUG=sw), so the compositor doesn't change.
 *
 * The driver is a prerelease and talks to a kernel interface that Android
 * updates can change, so it's only used after a check passes, and checked
 * again after every system update; until then apps render in software.
 */
class Gpu(
    context: Context,
    private val proot: Proot,
) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** The phone has a GPU we have a driver for. */
    val supported = File("/dev/mali0").exists()

    /** The user turned it on; it may still be inactive until [recheck] passes. */
    var wanted by mutableStateOf(prefs.getBoolean(WANTED, false))
        private set

    /** Linux apps started from now on render on the GPU. */
    var active by mutableStateOf(false)
        private set

    /** The GPU the last check found, e.g. "Mali-G715 MC7". */
    var device by mutableStateOf(prefs.getString(DEVICE, null))
        private set

    var state by mutableStateOf<JobState>(JobState.Idle)
        private set

    private val driver get() = File(proot.rootfs, DRIVER_PATH.removePrefix("/"))

    /** What changes under the driver: the kernel and Android's own GPU driver come with system updates. */
    private val build get() = Build.FINGERPRINT + " " + System.getProperty("os.version")

    /**
     * Activates the GPU if it passed its check on this system build. After
     * a system update, rechecks it first (in the background: apps render in
     * software meanwhile) and stays off if the check fails.
     */
    suspend fun recheck() {
        if (!supported || !wanted || active || state is JobState.Working) return
        if (!driver.isFile) return turnOff()
        if (prefs.getString(CHECKED_BUILD, null) == build) return activate()
        Log.i(TAG, "system changed since the last GPU check, checking again")
        if (run("checking", probeScript())) activate()
    }

    /** Installs the driver and Mesa, then checks them; turns on only if the check passes. */
    suspend fun turnOn() {
        if (!supported || state is JobState.Working) return
        prefs.edit().putBoolean(WANTED, true).apply()
        wanted = true
        if (run("installing", installScript() + " && " + probeScript())) {
            activate()
        } else {
            // Nothing to come back to on the next start; the failure stays on screen.
            prefs.edit().putBoolean(WANTED, false).apply()
            wanted = false
        }
    }

    /** Back to software rendering for apps started from now on. The driver stays, so turning on again is quick. */
    fun turnOff() {
        prefs.edit().putBoolean(WANTED, false).remove(CHECKED_BUILD).apply()
        wanted = false
        active = false
        state = JobState.Idle
        proot.graphicsEnv = SOFTWARE_ENV
    }

    private fun activate() {
        active = true
        proot.graphicsEnv = GPU_ENV
    }

    /** Runs [script]; on success remembers the build it passed on and the GPU's name from vulkaninfo. */
    private suspend fun run(
        action: String,
        script: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            state = JobState.Working(action, emptyList())
            val (ok, tail) = proot.runJob(script) { state = JobState.Working(action, it) }
            val name = tail.toList().firstNotNullOfOrNull { DEVICE_NAME.find(it)?.groupValues?.get(1) }
            Log.i(TAG, "$action: ${if (ok) "ok, $name" else "failed"}")
            if (ok) {
                device = name
                prefs.edit().putString(CHECKED_BUILD, build).putString(DEVICE, name).apply()
                state = JobState.Idle
            } else {
                state = JobState.Failed(tail.toList())
            }
            ok
        }

    /** Mesa (zink), the Vulkan loader and the probe tools from pacman; the pinned driver from its release, verified, unless it's already there. */
    private fun installScript() =
        pacmanInstall(listOf("mesa", "vulkan-icd-loader", "vulkan-tools", "mesa-utils")) + " && " +
            """
            { echo '$DRIVER_SHA256  $DRIVER_PATH' | sha256sum -c - 2>/dev/null || {
            curl -fL --retry 3 -o /tmp/panvk.so $DRIVER_URL &&
            echo '$DRIVER_SHA256  /tmp/panvk.so' | sha256sum -c - &&
            install -Dm755 /tmp/panvk.so $DRIVER_PATH && rm -f /tmp/panvk.so; }; } &&
            mkdir -p ${ICD_PATH.substringBeforeLast('/')} &&
            printf '%s\n' '{"file_format_version":"1.0.1","ICD":{"library_path":"$DRIVER_PATH","api_version":"1.4.363"}}' > $ICD_PATH
            """.trimIndent().replace("\n", " ")

    /** Vulkan finds the GPU through the driver, and OpenGL runs on it through zink: offscreen, no window needed. */
    private fun probeScript() =
        // Checks run while apps still render in software; that setting would win over zink.
        // One group, so a failed install before it skips it.
        "{ unset LIBGL_ALWAYS_SOFTWARE; " + GPU_ENV.joinToString(" ") { "export $it;" } +
            " vk=$(timeout 60 vulkaninfo --summary 2>/dev/null); echo \"\$vk\" | grep -E 'deviceName|driverID';" +
            " { echo \"\$vk\" | grep -q DRIVER_ID_MESA_PANVK || { echo 'Vulkan: the Mali driver did not load'; false; }; } &&" +
            " { timeout 60 eglinfo -B -p surfaceless 2>/dev/null | grep 'renderer: zink' || { echo 'OpenGL: zink did not start on the GPU'; false; }; }; }"

    companion object {
        private const val TAG = "gpu"
        private const val WANTED = "gpu_wanted"
        private const val CHECKED_BUILD = "gpu_checked_build"
        private const val DEVICE = "gpu_device"

        /** PanVK-kbase 0.1.0 rc2, the glibc build; tested on a Pixel 9a (Mali-G715). Pinned: newer releases get tested first. */
        private const val DRIVER_URL =
            "https://github.com/zenithblue-oss/panvk-kbase-android/releases/download/panvk-kbase-v0.1.0-rc2/libvulkan_panfrost-glibc-aarch64.so"
        private const val DRIVER_SHA256 = "0b8eaf709cd3559acec72b6fcf3acc24bcdb016bdc998adfedca6ff8eb60c820"

        /** Outside pacman's files, so system updates never touch it. */
        private const val DRIVER_PATH = "/usr/local/lib64/libvulkan_panfrost.so"
        private const val ICD_PATH = "/usr/share/vulkan/icd.d/panfrost_icd.aarch64.json"

        private val DEVICE_NAME = Regex("""deviceName\s*=\s*(.+)""")

        /** Today's default: Mesa renders on the CPU (llvmpipe). */
        val SOFTWARE_ENV = listOf("LIBGL_ALWAYS_SOFTWARE=1")

        /**
         * Vulkan only sees the PanVK driver, and presents through wl_shm; OpenGL is zink on it.
         * One app can still opt out with `GALLIUM_DRIVER=llvmpipe app`.
         */
        val GPU_ENV = listOf("VK_ICD_FILENAMES=$ICD_PATH", "MESA_VK_WSI_DEBUG=sw", "GALLIUM_DRIVER=zink")
    }
}
