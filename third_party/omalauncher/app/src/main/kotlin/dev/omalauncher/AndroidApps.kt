package dev.omalauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import android.os.UserHandle
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.omalauncher.ui.iconMask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AndroidApp(
    val label: String,
    val component: ComponentName,
    /** Preinstalled and never updated-in-place away from that: can't be uninstalled, only disabled. */
    val system: Boolean = false,
) {
    val intent: Intent
        get() =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/**
 * The phone's launchable apps, loaded once per process and kept current through
 * LauncherApps callbacks, so the drawer never waits on PackageManager.
 * Labels arrive first; icons fill in behind them.
 */
class AndroidAppCatalog(
    private val context: Context,
) {
    val apps = mutableStateListOf<AndroidApp>()
    val icons = mutableStateMapOf<ComponentName, ImageBitmap>()

    /** Alpha-only silhouettes of the same icons, for the monochrome icon style. */
    val masks = mutableStateMapOf<ComponentName, ImageBitmap>()

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val iconDispatcher = Dispatchers.IO.limitedParallelism(4)
    private var loading: Job? = null

    /** Icon edge in px: the drawer's 52dp icon at this screen's density. */
    private val iconPx = (52 * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)

    fun start() {
        launcherApps.registerCallback(
            object : LauncherApps.Callback() {
                override fun onPackageAdded(packageName: String, user: UserHandle) = reload()

                override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()

                // Updates can change the icon, so drop the cached one.
                override fun onPackageChanged(packageName: String, user: UserHandle) = reload(evict = packageName)

                override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()

                override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
            },
        )
        reload()
    }

    /** The system's App info page, as stock launchers open it. */
    fun openInfo(app: AndroidApp) {
        runCatching { launcherApps.startAppDetailsActivity(app.component, Process.myUserHandle(), null, null) }
            .onFailure { Log.w(TAG, "cannot open info for ${app.component}", it) }
    }

    /** Asks the system to uninstall; the list updates through the package callback. */
    fun uninstall(app: AndroidApp) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.component.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "cannot uninstall ${app.component}", it) }
    }

    private fun reload(evict: String? = null) {
        loading?.cancel()
        loading =
            scope.launch {
                if (evict != null) {
                    icons.keys.removeAll { it.packageName == evict }
                    masks.keys.removeAll { it.packageName == evict }
                }
                val infos =
                    withContext(Dispatchers.IO) {
                        runCatching { launcherApps.getActivityList(null, Process.myUserHandle()) }
                            .onFailure { Log.w(TAG, "cannot list apps", it) }
                            .getOrDefault(emptyList())
                            .filter { it.componentName.packageName != context.packageName }
                            .map { it to AndroidApp(it.label.toString(), it.componentName, it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) }
                            .sortedBy { it.second.label.lowercase() }
                    }
                val list = infos.map { it.second }
                if (list != apps.toList()) {
                    apps.clear()
                    apps += list
                }
                val present = list.mapTo(HashSet()) { it.component }
                icons.keys.retainAll(present)
                masks.keys.retainAll(present)
                for ((info, app) in infos) {
                    if (app.component in icons) continue
                    launch {
                        // Both styles are rendered up front so switching is instant; masks are 1 byte per pixel.
                        val rendered =
                            withContext(iconDispatcher) {
                                runCatching {
                                    val d = info.getIcon(0)
                                    val bmp = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
                                    d.setBounds(0, 0, iconPx, iconPx)
                                    d.draw(Canvas(bmp))
                                    bmp.asImageBitmap() to iconMask(d, iconPx).asImageBitmap()
                                }.getOrNull()
                            }
                        if (rendered != null) {
                            icons[app.component] = rendered.first
                            masks[app.component] = rendered.second
                        }
                    }
                }
            }
    }

    companion object {
        private const val TAG = "apps"
    }
}
