package com.lateral.beast

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.LauncherApps
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.lateral.InputSettings
import com.lateral.OmarchyTheme
import com.lateral.DisplayPreferences
import dev.omalauncher.desktop.IDesktopBridge
import dev.omalauncher.desktop.IDesktopHost
import dev.omalauncher.desktop.IDesktopSession
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Live, separately consented provider surface. The embedded runtime is a lazy fallback. */
@android.annotation.TargetApi(30)
internal class ExternalOmaDesktop private constructor(
    private val activity: ComponentActivity,
    private val providers: List<String>,
    private val embedded: () -> DesktopSession,
    private val onLaunch: () -> Unit,
    private val onOverlay: (Boolean) -> Unit,
    private val onConnection: (Boolean) -> Unit,
) : DesktopSession {
    companion object {
        const val ACTION = "dev.omalauncher.action.EXTERNAL_DESKTOP"
        val PACKAGES = if (com.lateral.BuildConfig.DEBUG)
            listOf("dev.omalauncher.bridgepreview", "dev.omalauncher.debug", "dev.omalauncher")
        else listOf("dev.omalauncher.bridgepreview", "dev.omalauncher")

        fun available(context: Context): List<String> {
            if (Build.VERSION.SDK_INT < 30) return emptyList()
            val home = context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
            )?.activityInfo?.packageName
            return PACKAGES.filter { pkg ->
                context.packageManager.queryIntentServices(Intent(ACTION).setPackage(pkg), 0)
                    .any { it.serviceInfo.exported && it.serviceInfo.enabled && it.serviceInfo.applicationInfo.enabled }
            }.sortedBy { it != home }
        }

        fun create(activity: ComponentActivity, embedded: () -> DesktopSession, onLaunch: () -> Unit,
            onOverlay: (Boolean) -> Unit, onConnection: (Boolean) -> Unit): ExternalOmaDesktop? {
            val available = available(activity)
            val requested = if (com.lateral.BuildConfig.DEBUG) activity.intent.getStringExtra("desktopProvider") else null
            val providers = if (requested in available) listOf(requested!!) + available.filterNot { it == requested } else available
            return if (providers.isEmpty()) null else ExternalOmaDesktop(activity, providers, embedded, onLaunch, onOverlay, onConnection)
        }
    }

    override val view = FrameLayout(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val statusWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "omalauncher-desktop-status").apply { isDaemon = true }
    }
    // Binder timeouts cannot interrupt a stuck provider. Allow one independent retry,
    // then reject further work instead of growing a queue behind blocked calls.
    private val connectionWorker = ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
        SynchronousQueue(), { task -> Thread(task, "omalauncher-desktop-connection").apply { isDaemon = true } })
    private val inputWorker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(128), { task -> Thread(task, "omalauncher-desktop-input").apply { isDaemon = true } })
    private val prefs = activity.getSharedPreferences("external_omalauncher", Context.MODE_PRIVATE)
    private var index = 0
    private var bound = false
    @Volatile private var closed = false
    private var visible = true
    private var consenting = false
    private var bridge: IDesktopBridge? = null
    @Volatile private var remote: IDesktopSession? = null
    private var connectionEpoch = 0L
    private var connecting = false
    private var opening = false
    private var remoteDisplayId = -1
    private var surface: SurfaceView? = null
    private var local: DesktopSession? = null
    private var overlay = false
    private var supportsHomeMenu = false
    private var desiredWorkspace = 1
    private var sentWorkspace: Int? = null
    var sessionId: String? = null
        private set
    var details = Bundle()
        private set
    var status = "Connecting to Omalauncher"
        private set
    val connected get() = remote != null
    val candidatePackages get() = providers
    private val provider get() = providers[index]

    private fun deniedKey(): String = "denied_" + provider + "_" +
        runCatching { activity.packageManager.getPackageInfo(provider, 0).longVersionCode }.getOrDefault(0L)

    private fun message(text: String) {
        status = text
        android.util.Log.i("ExternalOma", text)
        if (remote == null && local == null) {
            view.removeAllViews()
            view.addView(TextView(activity).apply { this.text = text; setTextColor(InputSettings.theme.foreground); setPadding(24, 24, 24, 24) })
        }
    }

    private val callbacks = object : IDesktopHost.Stub() {
        override fun onAndroidLaunch(id: String, component: ComponentName, user: UserHandle, serial: Long) {
            handler.post {
                if (closed || id != sessionId) return@post
                val users = activity.getSystemService(UserManager::class.java)
                if (users.getSerialNumberForUser(user) != serial) return@post report("The app profile was removed")
                if (users.isQuietModeEnabled(user)) {
                    runCatching { users.requestQuietModeEnabled(false, user) }
                        .onFailure { report("Enable work apps on the phone: ${it.message}") }
                    return@post
                }
                val launcher = activity.getSystemService(LauncherApps::class.java)
                val launchable = runCatching {
                    launcher.getActivityList(component.packageName, user)
                        .any { it.componentName == component }
                }.getOrDefault(false)
                if (!launchable) return@post report("Android app is unavailable in this profile")
                if (user == Process.myUserHandle()) {
                    WorkspaceState.launchNew(component.packageName, component.className, component.shortClassName)
                    onLaunch()
                } else {
                    // Never convert a work-profile request into the helper's owner-profile launch.
                    val displayId = activity.display?.displayId
                        ?: return@post report("External display is unavailable")
                    runCatching {
                        launcher.startMainActivity(component, user, null,
                            ActivityOptions.makeBasic().apply { launchDisplayId = displayId }.toBundle())
                    }.onSuccess { onLaunch() }.onFailure { report("Android refused the work-app launch: ${it.message}") }
                }
            }
        }
        override fun onStatus(id: String, state: String, message: String) {
            handler.post {
                if (closed || id != sessionId) return@post
                if (state == "revoked") {
                    prefs.edit().putBoolean(deniedKey(), true).apply()
                    fallback("Omalauncher approval was revoked; using the local desktop")
                } else if (state in listOf("disconnected", "failed")) fallback("Omalauncher disconnected; using the local desktop")
                else if (state == "unsupported_action") report(message)
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed || name.packageName != provider) return
            bridge = IDesktopBridge.Stub.asInterface(binder)
            connect()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            if (closed || name.packageName != provider) return
            bridge = null
            dropRemote()
            message("Reconnecting to Omalauncher")
            handler.postDelayed({ if (!closed && bridge == null) { unbind(); bind() } }, 1000)
        }
        override fun onBindingDied(name: ComponentName) { onServiceDisconnected(name) }
        override fun onNullBinding(name: ComponentName) { if (!closed && name.packageName == provider) nextProvider() }
    }

    private val placementObserver = ViewTreeObserver.OnPreDrawListener {
        if (!closed && remote != null && remoteDisplayId != activity.display?.displayId) {
            view.post {
                if (!closed && remote != null && remoteDisplayId != activity.display?.displayId) { dropRemote(); mountSurface() }
            }
        }
        true
    }

    init {
        view.post(::bind)
        view.viewTreeObserver.addOnPreDrawListener(placementObserver)
    }

    private fun uiScale(): Float = activity.display?.let { display ->
        DisplayPreferences.load(activity, DisplayPreferences.target(activity, display)).uiScale
    } ?: 1f

    private fun bind() {
        if (closed || bound) return
        bound = runCatching { activity.bindService(Intent(ACTION).setPackage(provider), connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) nextProvider()
    }

    private fun unbind() {
        connectionEpoch++
        connecting = false
        opening = false
        if (bound) runCatching { activity.unbindService(connection) }
        bound = false
        bridge = null
        supportsHomeMenu = false
    }

    private fun nextProvider() {
        unbind()
        if (++index < providers.size) bind()
        else { index = providers.lastIndex; fallback("Installed Omalauncher needs a compatible bridge update; using the local desktop") }
    }

    private fun connect() {
        if (closed || consenting || remote != null || connecting || opening) return
        val candidate = bridge ?: return
        val epoch = ++connectionEpoch
        connecting = true
        try {
            connectionWorker.execute {
                val result = runCatching {
                    val negotiated = candidate.negotiate(1)
                    val consent = if (negotiated.getString("status") == "consent_required")
                        candidate.requestConsent() else null
                    negotiated to consent
                }
                handler.post {
                    if (closed || bridge !== candidate || connectionEpoch != epoch) return@post
                    connecting = false
                    result.onFailure { fallback("Omalauncher connection failed: ${it.message}") }
                        .getOrNull()?.let { (negotiated, consent) ->
                            val capabilities = negotiated.getStringArrayList("capabilities").orEmpty()
                            if (!capabilities.containsAll(listOf("home_surface", "pointer", "home_pages", "appearance"))) {
                                nextProvider(); return@post
                            }
                            supportsHomeMenu = "home_menu" in capabilities
                            when (negotiated.getString("status")) {
                                "ready" -> mountSurface()
                                "consent_required" -> {
                                    if (prefs.getBoolean(deniedKey(), false)) {
                                        fallback("Omalauncher approval required; using the local desktop")
                                        return@post
                                    }
                                    @Suppress("DEPRECATION")
                                    val intent = consent?.getParcelable<PendingIntent>("consent")
                                        ?: return@post fallback("Omalauncher consent is unavailable")
                                    consenting = true
                                    message("Approve Omalateral on the phone to use Omalauncher")
                                    ExternalDesktopConsent.begin(this, activity, intent, ::onConsentResult)
                                }
                                else -> nextProvider()
                            }
                        }
                }
            }
        } catch (_: RejectedExecutionException) { connecting = false; fallback("Omalauncher connection unavailable") }
        handler.postDelayed({
            if (!closed && connectionEpoch == epoch && connecting) {
                connecting = false
                fallback("Omalauncher connection timed out")
            }
        }, 5_000)
    }

    fun onConsentResult(resultCode: Int) {
        if (closed) return
        consenting = false
        if (resultCode == Activity.RESULT_OK) { prefs.edit().remove(deniedKey()).apply(); connect() }
        else { prefs.edit().putBoolean(deniedKey(), true).apply(); fallback("Approval declined; using Omalateral's local desktop") }
    }

    fun refresh() { if (!closed && !consenting && remote == null) { if (bridge == null) { unbind(); bind() } else connect() } }
    fun requestConnection() { prefs.edit().remove(deniedKey()).apply(); refresh() }

    private fun mountSurface() {
        if (surface != null) return
        local?.close(); local = null
        view.removeAllViews()
        surface = object : SurfaceView(activity) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) requestFocus()
                return sendPointer(event)
            }
            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = sendPointer(event)
        }.apply {
            isFocusable = true; isFocusableInTouchMode = true
            // Default SurfaceView ordering keeps the workspace chrome/cursor above the child surface.
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = openSurface()
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    if (remote != null && remoteDisplayId != activity.display?.displayId) {
                        dropRemote()
                        mountSurface()
                    } else if (remote == null) openSurface() else sendRemote { resize(width, height) }
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) { if (surface?.holder === holder) dropRemote() }
            })
        }
        view.addView(surface, FrameLayout.LayoutParams(-1, -1))
    }

    private fun openSurface() {
        val target = surface ?: return
        val token = target.hostToken ?: return
        val candidate = bridge ?: return
        val displayId = activity.display?.displayId ?: return
        if (closed || remote != null || opening || target.width <= 0 || target.height <= 0) return
        val options = Bundle().apply {
            putInt("displayId", displayId)
            putInt("width", target.width); putInt("height", target.height)
            putString("launchMode", "host")
            putString("profile", if (com.lateral.BuildConfig.DEBUG) activity.intent.getStringExtra("desktopProfile") ?: "omalateral_workspace_v2" else "omalateral_workspace_v2")
            putBoolean("pagesSideBySide", false)
            putBoolean("inheritPhoneLayout", false)
            putBoolean("workspacePages", true)
            putFloat("uiScale", uiScale())
        }
        val epoch = ++connectionEpoch
        opening = true
        try {
            connectionWorker.execute {
                val result = runCatching {
                    val opened = candidate.openSession(1, options, token, callbacks)
                    val session = IDesktopSession.Stub.asInterface(opened.getBinder("session"))
                    Triple(opened, session, if (opened.getString("status") == "connected") session?.getSurface() else null)
                }
                if (closed) {
                    result.getOrNull()?.let { (_, session, child) ->
                        child?.release()
                        runCatching { session?.close() }
                    }
                    return@execute
                }
                handler.post {
                    val opened = result.getOrNull()
                    if (closed || bridge !== candidate || connectionEpoch != epoch || surface !== target) {
                        opened?.third?.release()
                        opened?.second?.let(::closeSessionAsync)
                        return@post
                    }
                    opening = false
                    if (result.isFailure) return@post fallback("Omalauncher surface failed: ${result.exceptionOrNull()?.message}")
                    val response = checkNotNull(opened)
                    if (response.first.getString("status") != "connected") {
                        response.second?.let(::closeSessionAsync)
                        return@post fallback("Omalauncher: ${response.first.getString("status")}")
                    }
                    val session = response.second ?: return@post fallback("Omalauncher session was unavailable")
                    val child = response.third ?: return@post fallback("Omalauncher surface was unavailable")
                    val id = response.first.getString("sessionId")
                    if (id.isNullOrBlank()) {
                        child.release(); closeSessionAsync(session)
                        return@post fallback("Omalauncher session had no identity")
                    }
                    try { target.setChildSurfacePackage(child) }
                    catch (error: Exception) {
                        child.release(); closeSessionAsync(session)
                        return@post fallback("Omalauncher surface failed: ${error.message}")
                    }
                    sessionId = id
                    remoteDisplayId = displayId
                    remote = session
                    sendRemote { setVisible(visible) }
                    sentWorkspace = null
                    workspace(desiredWorkspace)
                    status = "Connected to $provider"
                    android.util.Log.i("ExternalOma", "$status on display $displayId")
                    onConnection(true)
                    handler.post(poll)
                }
            }
        } catch (_: RejectedExecutionException) { opening = false; fallback("Omalauncher connection unavailable") }
        handler.postDelayed({
            if (!closed && connectionEpoch == epoch && opening) {
                opening = false
                fallback("Omalauncher surface timed out")
            }
        }, 7_000)
    }

    private val poll = object : Runnable {
        override fun run() {
            val session = remote ?: return
            if (closed) return
            try {
                statusWorker.execute {
                    val result = runCatching { session.describe() }
                    handler.post {
                        if (closed || remote !== session) return@post
                        result.onFailure { fallback("Omalauncher session ended: ${it.message}") }
                            .getOrNull()?.let { info ->
                                if (info.getString("sessionId") == sessionId) {
                                    details = info
                                    val nextOverlay = info.getBoolean("overlay")
                                    if (overlay != nextOverlay) { overlay = nextOverlay; onOverlay(overlay) }
                                    info.getBundle("palette")?.let { palette ->
                                        val name = info.getString("theme") ?: "omalauncher"
                                        OmarchyTheme.fromPalette(name, name, palette.keySet().associateWith { palette.getString(it).orEmpty() })?.let { theme ->
                                            if (theme != InputSettings.theme) InputSettings.setTheme(activity, theme)
                                        }
                                    }
                                }
                            }
                        if (!closed && remote === session) handler.postDelayed(this, 300)
                    }
                }
            } catch (_: RejectedExecutionException) {
                // The activity has closed while a poll was queued.
            }
        }
    }

    private fun sendRemote(work: IDesktopSession.() -> Unit) {
        val session = remote ?: return
        try {
            inputWorker.execute {
                if (closed || remote !== session) return@execute
                runCatching { session.work() }.onFailure { error -> handler.post {
                    if (!closed && remote === session) fallback("Omalauncher session ended: ${error.message}")
                } }
            }
        } catch (_: RejectedExecutionException) {
            handler.post { if (!closed && remote === session) fallback("Omalauncher input stalled; using the local desktop") }
        }
    }

    private fun closeSessionAsync(session: IDesktopSession) {
        try { inputWorker.execute { runCatching { session.close() } } }
        catch (_: RejectedExecutionException) {
            Thread({ runCatching { session.close() } }, "omalauncher-late-close")
                .apply { isDaemon = true }.start()
        }
    }

    private fun sendPointer(event: MotionEvent): Boolean {
        val session = remote ?: return false
        val copy = MotionEvent.obtain(event)
        try {
            inputWorker.execute {
                try {
                    if (!closed && remote === session) session.pointer(copy)
                } catch (error: Exception) {
                    handler.post {
                        if (!closed && remote === session) fallback("Omalauncher session ended: ${error.message}")
                    }
                } finally {
                    copy.recycle()
                }
            }
        } catch (_: RejectedExecutionException) {
            copy.recycle()
            handler.post { if (!closed && remote === session) fallback("Omalauncher input stalled; using the local desktop") }
            return false
        }
        return true
    }

    private fun dropRemote() {
        connectionEpoch++
        connecting = false
        opening = false
        handler.removeCallbacks(poll)
        val old = remote
        val oldSurface = surface
        surface = null
        remote = null; sessionId = null; remoteDisplayId = -1; details = Bundle()
        sentWorkspace = null
        old?.let(::closeSessionAsync)
        if (oldSurface?.parent === view) view.removeView(oldSurface)
        if (old != null) onConnection(false)
        if (overlay) { overlay = false; onOverlay(false) }
    }

    private fun fallback(reason: String) {
        dropRemote()
        surface = null
        if (closed) return
        if (local == null) local = embedded()
        if (local!!.view.parent !== view) {
            view.removeAllViews()
            view.addView(local!!.view, FrameLayout.LayoutParams(-1, -1))
        }
        local!!.visible(visible)
        status = reason
        android.util.Log.w("ExternalOma", reason)
    }

    private fun report(message: String) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    override fun visible(visible: Boolean) { this.visible = visible; local?.visible(visible); sendRemote { setVisible(visible) } }
    override fun search(query: String, submit: Boolean) { if (local != null) local!!.search(query, submit) else sendRemote { search(query, submit) } }
    override fun apps() { if (local != null) local!!.apps() else sendRemote { action("drawer") } }
    override fun widgets() { if (local != null) local!!.widgets() else sendRemote { action("widgets") } }
    override fun workspace(number: Int) {
        if (number !in 1..10) return
        desiredWorkspace = number
        if (remote == null || sentWorkspace == number) return
        sentWorkspace = number
        sendRemote { action("workspace:$number") }
    }
    override fun homeMenu(): Boolean {
        local?.let { return it.homeMenu() }
        if (remote == null || !supportsHomeMenu) return false
        sendRemote { action("home_menu") }
        return true
    }
    override fun themes() { if (local != null) local!!.themes() else report("Choose a theme in Omalauncher on the phone; the workspace follows it automatically") }
    override fun dismissPanels() { local?.dismissPanels(); sendRemote { action("dismiss") }; onOverlay(false) }
    override fun key(event: KeyEvent): Boolean {
        local?.let { return it.key(event) }
        if (event.isMetaPressed && event.keyCode in listOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_A)) {
            if (event.action == KeyEvent.ACTION_DOWN) { sendRemote { action("search") }; LauncherSearchSession.begin() }
            return true
        }
        val session = remote ?: return false
        val copy = KeyEvent(event)
        try {
            inputWorker.execute {
                try { if (!closed && remote === session) session.key(copy) }
                catch (error: Exception) { handler.post {
                    if (!closed && remote === session) fallback("Omalauncher session ended: ${error.message}")
                } }
            }
        } catch (_: RejectedExecutionException) {
            handler.post { if (!closed && remote === session) fallback("Omalauncher input stalled; using the local desktop") }
            return false
        }
        return event.keyCode != KeyEvent.KEYCODE_ESCAPE
    }
    override fun popupClick(x: Int, y: Int, button: Int) = local?.popupClick(x, y, button) ?: false
    override fun popupPointer(x: Int, y: Int, action: Int, button: Int) = local?.popupPointer(x, y, action, button) ?: false
    override fun popupScroll(x: Int, y: Int, amount: Float) = local?.popupScroll(x, y, amount) ?: false
    override fun hover(x: Int, y: Int) {
        val target = surface ?: return
        val inverse = Matrix()
        target.transformMatrixToGlobal(inverse)
        if (!inverse.invert(inverse)) return
        val point = floatArrayOf(x.toFloat(), y.toFloat())
        inverse.mapPoints(point)
        if (point[0] !in 0f..target.width.toFloat() || point[1] !in 0f..target.height.toFloat()) return
        val now = android.os.SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }),
            arrayOf(MotionEvent.PointerCoords().apply { this.x = point[0]; this.y = point[1] }),
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0)
        try { sendPointer(event) } finally { event.recycle() }
    }
    override fun close() {
        closed = true
        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(placementObserver)
        ExternalDesktopConsent.cancel(this)
        // Leave in-flight connection completions queued so they can release a
        // SurfacePackage and close a session that arrives after this activity ends.
        handler.removeCallbacks(poll)
        dropRemote()
        local?.close(); local = null
        unbind()
        view.removeAllViews()
        statusWorker.shutdownNow()
        connectionWorker.shutdownNow()
        inputWorker.shutdown()
    }
}
