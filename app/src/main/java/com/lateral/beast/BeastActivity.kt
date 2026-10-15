package com.lateral.beast

import com.lateral.BuildConfig

import android.content.Context
import android.content.res.Configuration
import android.app.Dialog
import android.app.ActivityOptions
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.pm.ResolveInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.hardware.display.DisplayManager
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.graphics.Rect
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.OverScroller
import android.text.TextUtils
import android.view.inputmethod.InputMethodManager
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import androidx.appcompat.app.AppCompatActivity
import com.lateral.SimpleTextWatcher
import com.lateral.BarAlignment
import com.lateral.InputSettings
import com.lateral.InputSettingsPanel
import com.lateral.ExternalDisplayPanel
import com.lateral.PhoneView
import com.lateral.MainActivity
import com.lateral.WorkspaceCursor
import com.lateral.TransientPanelCoordinator
import com.lateral.privileged.PrivilegedService

/** The external-display workspace: an ordered one-dimensional strip of Android tasks. */
class BeastActivity : AppCompatActivity(), DisplayManager.DisplayListener, BeastInputRouter.Handler {
    companion object {
        val ACTION_ENSURE_WORKSPACE = BuildConfig.APPLICATION_ID + ".action.ENSURE_BEAST_WORKSPACE"
        val ACTION_SHOW_LAUNCHER = BuildConfig.APPLICATION_ID + ".action.SHOW_BEAST_LAUNCHER"
        private const val MOMENTUM_DELAY_MS = 70L
        private const val MIN_FLING_VELOCITY = 120f
        private const val SHOW_ALL_BATCH_TIMEOUT_MS = 6_000L
        private const val TASKBAR_TEXT_SIZE = 11f
        private const val TOOLBAR_TEXT_SIZE = 11f
        private const val TASKBAR_LABEL_CHARACTERS = 12
        private const val TASKBAR_HEIGHT_DP = 38
        private const val TASKBAR_STEP_CONTROL_WIDTH_DP = 28
        private const val TASKBAR_SCROLL_STEP_FRACTION = .68f
        private const val SYSTEM_STATUS_INTERVAL_MS = 1_000L
        private const val APP_DECORATOR_HEIGHT_DP = 32
        private const val APP_DECORATOR_MIN_WIDTH_DP = 300
        private const val TAG = "Lateral/Beast"
        private val BACKGROUND get() = InputSettings.theme.darkerBackground
        private val ACCENT get() = InputSettings.accentColor
        private val activeInstances = AtomicInteger(0)
        private val resumedInstances = AtomicInteger(0)
        @Volatile private var visibleDisplayId = -1
        @Volatile var phoneViewTaskId = -1
            private set

        fun isWorkspaceActive(): Boolean = activeInstances.get() > 0
        fun isWorkspaceVisible(): Boolean = resumedInstances.get() > 0
        fun workspaceDisplayId(): Int = visibleDisplayId

        /** Finds the active physical presentation display, preferring VITURE when present. */
        fun findExternalDisplay(context: Context): Display? {
            val displays = context.getSystemService(DisplayManager::class.java).displays
            return displays.firstOrNull { display ->
                display.displayId != Display.DEFAULT_DISPLAY &&
                    display.state == Display.STATE_ON &&
                    display.name.contains("VITURE Beast", ignoreCase = true)
            } ?: displays.firstOrNull { display ->
                display.displayId != Display.DEFAULT_DISPLAY &&
                    display.state == Display.STATE_ON &&
                    !display.name.startsWith("LATERAL_")
            }
        }
    }

    private lateinit var root: FrameLayout
    private var phoneViewSession = false
    private val phoneViewListener: () -> Unit = {
        if (phoneViewSession && !PhoneView.active && !isFinishing) {
            PhoneView.beginExit()
            finish()
        }
    }
    /** Scales rendered content, including the cursor, but leaves Android's logical input grid intact. */
    private lateinit var aspectLayer: FrameLayout
    private lateinit var workspaceScroll: HorizontalScrollView
    private lateinit var taskStrip: FrameLayout
    private lateinit var workspaceTabs: LinearLayout
    private lateinit var shellIndicators: TextView
    private var workspaceTabsKey: String? = null
    private val omarchyShortcutHandler: (OmarchyShortcut) -> Unit = { handleOmarchyShortcut(it) }
    private data class WindowDrag(val id: Long, val resize: Boolean, val x: Int, val y: Int, val bounds: WindowBounds,
        val wasFloating: Boolean, val savedLayout: String?, val tileTargets: Map<Long, Rect> = emptyMap(),
        val detached: Boolean = false)
    private var windowDrag: WindowDrag? = null
    private lateinit var toolbar: FrameLayout
    private data class ToolbarRenderKey(
        val theme: com.lateral.OmarchyTheme,
        val elementScale: Float,
        val fontScale: Float,
        val densityDpi: Int,
        val hasDesktop: Boolean,
        val shared: Boolean,
    )
    private var toolbarRenderKey: ToolbarRenderKey? = null
    private lateinit var taskbar: FrameLayout
    private lateinit var taskbarScroll: HorizontalScrollView
    private lateinit var taskbarContent: LinearLayout
    private lateinit var taskTabs: LinearLayout
    private lateinit var taskbarPrevious: TextView
    private lateinit var taskbarNext: TextView
    private lateinit var taskbarShell: LinearLayout
    private lateinit var viewportMap: ViewportMapView
    private lateinit var fullscreenLayer: FrameLayout
    private lateinit var launcher: FrameLayout
    private var omaDesktop: DesktopSession? = null
    private var sharedDesktop = false
    private var omalauncherPresence = OmalauncherPresence.ABSENT
    private var presenceReceiverRegistered = false
    private val presenceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.data?.schemeSpecificPart == OmalauncherPresence.PACKAGE) refreshOmalauncherPresence()
        }
    }

    private fun refreshOmalauncherPresence() {
        val next = OmalauncherPresence.detect(this)
        if (next == omalauncherPresence) return
        omalauncherPresence = next
        if (::toolbar.isInitialized) renderToolbar()
    }

    private fun showCompanionStatus() {
        val message = if (sharedDesktop) "Connected to an authorized external Omalauncher desktop session." else when (omalauncherPresence) {
            OmalauncherPresence.ABSENT -> "Using omalateral's embedded Omalauncher desktop. Its widgets, settings and optional Linux installation are local to omalateral."
            OmalauncherPresence.DISABLED -> "Standalone Omalauncher is disabled. Using omalateral's separate embedded desktop."
            OmalauncherPresence.BRIDGE_REQUIRED -> "Using omalateral's embedded desktop. Your installed Omalauncher is separate and unchanged. Sharing its Linux environment, agents and widgets requires a future bridge update in both apps."
        }
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }
    private var launcherActive = false
    private var desktopFocused = true
    private var routedLauncherOutside = false
    private var nativeLauncherOutside = false

    private fun windowAt(x: Int, y: Int): Boolean = cards.values.any { card ->
        val bounds = Rect()
        card.isShown && card.getGlobalVisibleRect(bounds) && bounds.contains(x, y)
    }

    private fun desktopAt(x: Int, y: Int): Boolean {
        val bounds = Rect()
        return ::launcher.isInitialized && launcher.isShown && launcher.getGlobalVisibleRect(bounds) && bounds.contains(x, y) &&
            (launcherActive || !windowAt(x, y))
    }

    private fun setLauncherActive(active: Boolean) {
        launcherActive = active
        if (!::launcher.isInitialized) return
        if (omaDesktop == null) launcher.visibility = if (active) View.VISIBLE else View.GONE
        // The home screen stays mounted below windows. Only its menus rise above them.
        launcher.translationZ = if (active) 1f else 0f
        if (active) desktopFocused = true
        if (::cursorOverlay.isInitialized) cursorOverlay.translationZ = 2f
    }
    private lateinit var launcherPanel: LinearLayout
    private lateinit var launcherQuery: EditText
    private lateinit var launcherResults: LinearLayout
    private lateinit var launcherScroll: ScrollView
    private lateinit var cursorOverlay: BeastCursorOverlay
    private val modalCursorOverlays = ConcurrentHashMap<Dialog, BeastCursorOverlay>()
    private var workspaceMenu: Dialog? = null
    private lateinit var systemStatus: TextView
    private val scrollMomentum by lazy { OverScroller(this) }
    private var momentumVelocity = 0f
    private var lastScrollAt = 0L
    private var momentumTargetsLauncher = false
    private val controlDebouncer = BeastControlDebouncer()
    private val shellControlKeys = java.util.WeakHashMap<View, String>()
    private var shellMouseControlKey: String? = null
    private val beastDisplayModeController by lazy { BeastDisplayModeController(this) }
    private val screenshotController by lazy {
        BeastScreenshotController(
            activity = this,
            focusedAppBitmap = {
                cards[WorkspaceState.focusedTaskId]?.captureBitmap()
            },
            onResult = ::onScreenshotResult,
        )
    }
    private val launcherPreferences by lazy { LauncherPreferences(applicationContext) }
    private lateinit var displayManager: DisplayManager
    private var uiFontScale = 1f
    private var uiElementScale = 1f
    private var countedResumed = false
    private var launcherLease: TransientPanelCoordinator.Lease? = null
    private var launcherSubmitSequence = 0L
    private var beastHoverTarget: View? = null
    private var hoverUpdateQueued = false
    private var routedPointerCard: BeastTaskCard? = null
    private var routedPointerButton = 0
    private var routedFullscreenExitPointer = false
    private val hoverUpdateFrame = Runnable {
        hoverUpdateQueued = false
        updateBeastHoverAtCursor()
    }
    private val pendingShowAllTaskIds = linkedSetOf<Long>()
    private val showAllTimeout = Runnable(::finishShowAllBatch)
    private val systemStatusTicker = object : Runnable {
        override fun run() {
            if (::systemStatus.isInitialized) systemStatus.text = systemStatusText()
            if (::shellIndicators.isInitialized) shellIndicators.text = systemIndicatorsText()
            if (::root.isInitialized) root.postDelayed(this, SYSTEM_STATUS_INTERVAL_MS)
        }
    }

    private val cards = linkedMapOf<Long, BeastTaskCard>()
    private var apps: List<ResolveInfo> = emptyList()
    private var filteredApps: List<ResolveInfo> = emptyList()
    private var recentPackageRank: Map<String, Int> = emptyMap()
    private val stateListener: () -> Unit = {
        runOnUiThread {
            renderWorkspace(WorkspaceState.consumeRevealFocusedTaskRequest())
            if (::launcher.isInitialized && launcherActive && omaDesktop == null) {
                populateLauncher(launcherQuery.text.toString())
            }
        }
    }
    private val appearanceListener: () -> Unit = { runOnUiThread(::applyAppearance) }
    private val phoneAppConfigurationListener: (InputSettings.PhoneAppConfiguration) -> Unit = {
        configuration -> runOnUiThread {
            // This resizes only hosted virtual displays. Beast chrome retains its own
            // per-mode controls while app density follows relative window height.
            cards.values.forEach { it.phoneAppConfiguration = phoneAppConfiguration() }
        }
    }
    private val launcherSearchListener: () -> Unit = {
        runOnUiThread {
            if (!::launcher.isInitialized) return@runOnUiThread
            if (!LauncherSearchSession.active) {
                if (launcherActive) hideLauncher()
                return@runOnUiThread
            }
            if (!launcherActive) return@runOnUiThread
            val query = LauncherSearchSession.query
            if (omaDesktop != null) {
                omaDesktop?.search(query, launcherSubmitSequence != LauncherSearchSession.submitSequence)
                launcherSubmitSequence = LauncherSearchSession.submitSequence
                return@runOnUiThread
            }
            if (launcherQuery.text.toString() != query) {
                launcherQuery.setText(query)
                launcherQuery.setSelection(launcherQuery.text.length)
            } else {
                populateLauncher(query)
            }
            if (launcherSubmitSequence != LauncherSearchSession.submitSequence) {
                launcherSubmitSequence = LauncherSearchSession.submitSequence
                filteredApps.firstOrNull()?.let(::launchFromLauncher)
            }
        }
    }
    private val privilegedListener: () -> Unit = {
        runOnUiThread {
            if (PrivilegedService.state == PrivilegedService.State.READY) {
                cards.values.forEach(BeastTaskCard::retryImeRouting)
                syncAndroidTasks()
            }
            renderWorkspace()
        }
    }
    private val cursorListener: () -> Unit = {
        keepCursorOnTop()
        cursorOverlay.postInvalidateOnAnimation()
        modalCursorOverlays.values.forEach { it.postInvalidateOnAnimation() }
        if (::root.isInitialized && !hoverUpdateQueued) {
            hoverUpdateQueued = true
            root.postOnAnimation(hoverUpdateFrame)
        }
    }
    private val cursorScrollListener: (Float) -> Unit = { wheel ->
        runOnUiThread { scrollWorkspace(wheel) }
    }
    private val momentumKick = Runnable(::startMomentum)
    private val momentumFrame = object : Runnable {
        override fun run() {
            if (!scrollMomentum.computeScrollOffset()) return
            if (momentumTargetsLauncher) {
                launcherScroll.scrollTo(0, scrollMomentum.currY)
            } else {
                workspaceScroll.scrollTo(scrollMomentum.currX, 0)
            }
            root.postOnAnimation(this)
        }
    }
    private val viewportListener: (Float) -> Unit = { position ->
        runOnUiThread {
            val maxScroll = (taskStrip.width - workspaceScroll.width).coerceAtLeast(0)
            val target = (maxScroll * position).toInt()
            if (kotlin.math.abs(workspaceScroll.scrollX - target) > 2) {
                workspaceScroll.scrollTo(target, 0)
            }
        }
    }
    private val resumeGeometryRefresh = Runnable {
        if (!::root.isInitialized || isFinishing || isDestroyed) return@Runnable
        // Screen locking and OEM display power-cycles can change insets or display metrics
        // while this activity is paused. Rebuild framing from the current viewport instead
        // of retaining card dimensions calculated before the lock screen appeared.
        enterImmersiveMode()
        applyAppearance()
        root.requestLayout()
        root.postOnAnimation {
            if (isFinishing || isDestroyed || !::workspaceScroll.isInitialized) return@postOnAnimation
            updateHorizontalAspectCorrection()
            updateTaskSizes()
            cards.values.forEach { it.renderedVerticalScale = renderedVerticalScale() }
            val maxScroll = (taskStrip.width - workspaceScroll.width).coerceAtLeast(0)
            workspaceScroll.scrollTo(
                (maxScroll * WorkspaceState.viewportPosition).roundToInt(), 0,
            )
            syncViewportMap()
            // Wait until card measurements have settled, then reassert each hosted
            // producer's buffer/display geometry. This repairs the short, top-aligned
            // app frame some devices restore after their screen powers back on.
            workspaceScroll.postOnAnimation {
                cards.values.forEach(BeastTaskCard::refreshAfterHostResume)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phoneViewSession = PhoneView.active && display?.displayId == Display.DEFAULT_DISPLAY
        if (phoneViewSession) {
            phoneViewTaskId = taskId
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        PhoneView.addListener(phoneViewListener)
        // Beast is controlled by PhoneUI or a hardware keyboard. It must never
        // become an IME target on the glasses display.
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        if (phoneViewSession) window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        window.setStatusBarColor(Color.TRANSPARENT)
        window.setNavigationBarColor(Color.TRANSPARENT)
        InputSettings.load(applicationContext)
        uiElementScale = displayProfile().uiScale
        uiFontScale = uiElementScale
        displayManager = getSystemService(DisplayManager::class.java)
        apps = loadApps()
        buildUi()
        BeastInputRouter.attach(this)
        if (!phoneViewSession) {
            OmarchyShortcuts.handler = omarchyShortcutHandler
        }
        activeInstances.incrementAndGet()
        // REDMAGIC OS does not create PhoneWindow's DecorView until setContentView().
        // Accessing window.insetsController earlier crashes inside PhoneWindow.
        window.decorView.post(::enterImmersiveMode)
        WorkspaceState.addListener(stateListener)
        WorkspaceState.addViewportListener(viewportListener)
        WorkspaceCursor.addListener(cursorListener)
        WorkspaceCursor.addScrollListener(cursorScrollListener)
        InputSettings.addAppearanceListener(appearanceListener)
        com.lateral.DisplayPreferences.addListener(appearanceListener)
        InputSettings.addPhoneAppConfigurationListener(phoneAppConfigurationListener)
        LauncherSearchSession.addListener(launcherSearchListener)
        PrivilegedService.addListener(privilegedListener)
        applyAppearance()
        renderWorkspace()
        root.post(systemStatusTicker)
        if (WorkspaceState.restoreFocusAfterDisplayModeSwitch(display?.mode?.physicalWidth) ||
            WorkspaceState.hasPendingDisplayModeFocusRestore()
        ) {
            restoreWorkspaceFocus()
        } else {
            // Restore existing windows over the persistent Omalauncher home screen.
            restoreWorkspaceFocus()
        }
        if (PrivilegedService.state == PrivilegedService.State.READY) syncAndroidTasks()
        omaDesktop?.visible(true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.action) {
            ACTION_SHOW_LAUNCHER -> showLauncher()
            ACTION_ENSURE_WORKSPACE -> restoreWorkspaceFocus()
        }
    }

    override fun onDestroy() {
        workspaceMenu?.dismiss()
        if (presenceReceiverRegistered) unregisterReceiver(presenceReceiver)
        desktopLongPress.cancel()
        omaDesktop?.close()
        omaDesktop = null
        PhoneView.removeListener(phoneViewListener)
        if (phoneViewTaskId == taskId) phoneViewTaskId = -1
        if (::root.isInitialized) root.removeCallbacks(systemStatusTicker)
        if (::root.isInitialized) root.removeCallbacks(hoverUpdateFrame)
        if (::root.isInitialized) root.removeCallbacks(resumeGeometryRefresh)
        beastHoverTarget?.let { updateBeastHover(it, false) }
        beastHoverTarget = null
        routedPointerCard?.cancelRoutedPointer()
        routedPointerCard = null
        routedPointerButton = 0
        BeastInputRouter.detach(this)
        if (::root.isInitialized) root.removeCallbacks(showAllTimeout)
        pendingShowAllTaskIds.clear()
        screenshotController.close()
        launcherLease?.release()
        launcherLease = null
        modalCursorOverlays.values.forEach { overlay ->
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
        modalCursorOverlays.clear()
        if (::displayManager.isInitialized) displayManager.unregisterDisplayListener(this)
        WorkspaceState.removeListener(stateListener)
        OmarchyShortcuts.detach(omarchyShortcutHandler)
        WorkspaceState.removeViewportListener(viewportListener)
        WorkspaceCursor.removeListener(cursorListener)
        WorkspaceCursor.removeScrollListener(cursorScrollListener)
        InputSettings.removeAppearanceListener(appearanceListener)
        com.lateral.DisplayPreferences.removeListener(appearanceListener)
        InputSettings.removePhoneAppConfigurationListener(phoneAppConfigurationListener)
        LauncherSearchSession.removeListener(launcherSearchListener)
        PrivilegedService.removeListener(privilegedListener)
        if (phoneViewSession || WorkspaceState.hasPendingDisplayModeFocusRestore() || !isFinishing) {
            cards.values.forEach(BeastTaskCard::retainForReattach)
        } else {
            cards.values.forEach(BeastTaskCard::release)
        }
        activeInstances.decrementAndGet()
        super.onDestroy()
        if (phoneViewSession && PhoneView.exiting) PhoneView.completeExit()
    }

    override fun onResume() {
        super.onResume()
        refreshOmalauncherPresence()
        visibleDisplayId = display?.displayId ?: -1
        if (!countedResumed) {
            countedResumed = true
            resumedInstances.incrementAndGet()
        }
        displayManager.registerDisplayListener(this, null)
        root.post(::keepOnExternalDisplay)
        root.removeCallbacks(resumeGeometryRefresh)
        root.post(resumeGeometryRefresh)
        AndroidTaskSynchronizer.requestImmediate()
        omaDesktop?.visible(true)
    }


    override fun onStop() {
        desktopLongPress.cancel()
        nativeDesktopSecondary = false
        secondaryCancelled = true
        omaDesktop?.visible(false)
        super.onStop()
    }

    override fun onPause() {
        visibleDisplayId = -1
        if (countedResumed) {
            countedResumed = false
            resumedInstances.decrementAndGet()
        }
        displayManager.unregisterDisplayListener(this)
        super.onPause()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        PrivilegedService.allowPhoneFocusRecovery(MainActivity.currentPhoneTaskId())
    }

    override fun onDisplayAdded(displayId: Int) = keepOnExternalDisplay()
    override fun onDisplayRemoved(displayId: Int) {
        keepOnExternalDisplay()
        if (displayId != display?.displayId && PrivilegedService.state == PrivilegedService.State.READY) {
            cards.values.forEach(BeastTaskCard::retryImeRouting)
        }
    }
    override fun onDisplayChanged(displayId: Int) {
        keepOnExternalDisplay()
        if (displayId == display?.displayId) root.post {
            applyAppearance()
            if (WorkspaceState.restoreFocusAfterDisplayModeSwitch(display?.mode?.physicalWidth)) {
                restoreWorkspaceFocus()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemKeyboard()
            if (::root.isInitialized) {
                root.removeCallbacks(resumeGeometryRefresh)
                root.post(resumeGeometryRefresh)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::root.isInitialized) {
            root.removeCallbacks(resumeGeometryRefresh)
            root.post(resumeGeometryRefresh)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!phoneViewSession && OmarchyShortcuts.dispatch(event)) return true
        if (event.isMetaPressed && event.keyCode == KeyEvent.KEYCODE_SLASH) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) showWorkspaceMenu()
            return true
        }
        if (omaDesktop == null && event.isMetaPressed && event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode in listOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_A)) {
            showLauncher()
            return true
        }
        if (omaDesktop != null && event.isMetaPressed && event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode in listOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_ENTER)) {
            setLauncherActive(true)
        }
        if (launcherActive || (omaDesktop != null && desktopFocused)) {
            if (omaDesktop?.key(event) == true) return true
            if (event.keyCode != KeyEvent.KEYCODE_ESCAPE) return super.dispatchKeyEvent(event)
        }
        if (phoneViewSession && event.isCtrlPressed &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                WorkspaceState.focusRelative(
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1,
                )
            }
            // Consume DOWN, repeats, and UP so the hosted app never receives a partial chord.
            return true
        }
        if (phoneViewSession && !launcherActive &&
            !TransientPanelCoordinator.isActiveOnDisplay(Display.DEFAULT_DISPLAY) &&
            event.keyCode !in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            cards[WorkspaceState.focusedTaskId]?.let { if (it.sendNativeKey(event)) return true }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ESCAPE && launcherActive) {
            hideLauncher()
            return true
        }
        if (!phoneViewSession && !launcherActive) {
            cards[WorkspaceState.focusedTaskId]?.let { if (it.sendNativeKey(event)) return true }
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::launcher.isInitialized && launcherActive) {
            hideLauncher()
        } else if (phoneViewSession) {
            PhoneView.setEnabled(this, false)
        } else {
            super.onBackPressed()
        }
    }

    private var shellMouseTarget: View? = null
    private var shellMouseDownX = 0f
    private var shellMouseDownY = 0f
    private var shellMouseCancelled = false
    private var routedLauncherRoot: View? = null
    private var routedLauncherView: View? = null
    private var routedLauncherLease: TransientPanelCoordinator.Lease? = null
    private var routedLauncherButton = 0
    private var routedLauncherDownAt = 0L
    private val desktopLongPress = DesktopLongPress()
    private var nativeDesktopSecondary = false
    private var secondaryDownX = 0
    private var secondaryDownY = 0
    private var secondaryCancelled = false

    private fun longPressDesktop(x: Int, y: Int) {
        val capturedRoot = root
        val capturedDesktop = launcher
        val capturedLease = launcherLease
        desktopFocused = true
        desktopLongPress.start(capturedRoot, x, y) {
            root === capturedRoot && launcher === capturedDesktop && launcherLease === capturedLease &&
                !isFinishing && !isDestroyed && capturedDesktop.isShown && desktopAt(x, y) &&
                (launcherActive || !TransientPanelCoordinator.isActiveOnDisplay(display?.displayId ?: -1))
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!phoneViewSession && handleWindowDrag(event.rawX.toInt(), event.rawY.toInt(), event.actionMasked,
                if (event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) MotionEvent.BUTTON_SECONDARY else MotionEvent.BUTTON_PRIMARY)) return true
        if (nativeLauncherOutside || (event.actionMasked == MotionEvent.ACTION_DOWN &&
                launcherActive && !desktopAt(event.rawX.toInt(), event.rawY.toInt()))) {
            nativeLauncherOutside = true
            routeAppPointer(display?.displayId ?: -1, event.rawX.toInt(), event.rawY.toInt(),
                event.actionMasked, MotionEvent.BUTTON_PRIMARY, event.buttonState)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                nativeLauncherOutside = false
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            desktopLongPress.cancel()
            desktopFocused = desktopAt(event.rawX.toInt(), event.rawY.toInt())
        }
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
            val secondaryDown = event.actionMasked == MotionEvent.ACTION_DOWN &&
                event.buttonState == MotionEvent.BUTTON_SECONDARY && desktopFocused &&
                (launcherActive || !TransientPanelCoordinator.isActiveOnDisplay(display?.displayId ?: -1))
            if (nativeDesktopSecondary || secondaryDown) {
                nativeDesktopSecondary = true
                routeAppPointer(display?.displayId ?: -1, event.rawX.toInt(), event.rawY.toInt(),
                    event.actionMasked, MotionEvent.BUTTON_SECONDARY, event.buttonState)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    nativeDesktopSecondary = false
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN && event.buttonState == MotionEvent.BUTTON_PRIMARY) {
                // Native mouse DOWN can be consumed by either HorizontalScrollView.
                // Hover targets are shell-owned controls only; hosted app surfaces are
                // deliberately not registered, so their native input remains untouched.
                val target = if (::root.isInitialized)
                    findBeastHoverTarget(root, event.rawX.toInt(), event.rawY.toInt()) else null
                shellMouseTarget = target?.takeIf { it.isClickable }
                shellMouseControlKey = shellMouseTarget?.let { shellControlKeys[it] }
                if (shellMouseTarget != null) {
                    onUserInteraction()
                    shellMouseDownX = event.rawX
                    shellMouseDownY = event.rawY
                    shellMouseCancelled = false
                    shellMouseTarget?.isPressed = true
                    return true
                }
            }
            shellMouseTarget?.let { target ->
                val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                if (kotlin.math.abs(event.rawX - shellMouseDownX) > slop ||
                    kotlin.math.abs(event.rawY - shellMouseDownY) > slop) shellMouseCancelled = true
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    shellMouseTarget = null
                    target.isPressed = false
                    val bounds = Rect()
                    val current = if (::root.isInitialized)
                        findBeastHoverTarget(root, event.rawX.toInt(), event.rawY.toInt()) else null
                    val sameControl = current === target || (shellMouseControlKey != null &&
                        current?.let { shellControlKeys[it] } == shellMouseControlKey)
                    shellMouseControlKey = null
                    if (event.actionMasked == MotionEvent.ACTION_UP && !shellMouseCancelled &&
                        sameControl && current != null && current.isShown && current.isEnabled &&
                        current.getGlobalVisibleRect(bounds) && bounds.contains(event.rawX.toInt(), event.rawY.toInt())) {
                        current.performClick()
                    }
                }
                return true
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // Android sends these between mouse DOWN/UP. The captured stream owns them;
        // forwarding them to Compose would create a second, unmatched pointer stream.
        if (nativeDesktopSecondary && event.actionMasked in setOf(
                MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE)) return true
        if (::launcher.isInitialized && desktopAt(event.rawX.toInt(), event.rawY.toInt())) {
            return super.dispatchGenericMotionEvent(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER)
        ) {
            val wheel = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (wheel != 0f) {
                // Give the view under the rendered cursor first refusal. A task
                // TextureView forwards the wheel into its VirtualDisplay; empty
                // workspace and launcher areas fall back to shell navigation.
                if (!launcherActive && super.dispatchGenericMotionEvent(event)) {
                    return true
                }
                scrollWorkspace(wheel)
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /** Android never receives hover events because Beast's cursor is app-rendered. */
    private fun updateBeastHoverAtCursor() {
        if (phoneViewSession) return
        if (!::root.isInitialized || root.width <= 0 || root.height <= 0) return
        val state = WorkspaceCursor.position()
        val target = if (state.visible) {
            val rootLocation = IntArray(2)
            root.getLocationOnScreen(rootLocation)
            val screenX = rootLocation[0] + state.xFraction * root.width
            val screenY = rootLocation[1] + state.yFraction * root.height
            val modalRoot = modalCursorOverlays.keys.lastOrNull { it.isShowing }?.window?.decorView
            findBeastHoverTarget(modalRoot ?: root, screenX.toInt(), screenY.toInt())
        } else {
            null
        }
        if (target === beastHoverTarget) return
        beastHoverTarget?.let { updateBeastHover(it, false) }
        beastHoverTarget = target
        target?.let { updateBeastHover(it, true) }
    }

    private fun findBeastHoverTarget(view: View, screenX: Int, screenY: Int): View? {
        // A modal overlay is a hit-test barrier, even where it has no hover control.
        if (view === root && ::launcher.isInitialized && launcherActive &&
            desktopAt(screenX, screenY)) {
            return findBeastHoverTarget(launcher, screenX, screenY)
        }
        if (view.visibility != View.VISIBLE || view.alpha <= 0f || !view.isShown) return null
        val bounds = Rect()
        if (!view.getGlobalVisibleRect(bounds)) return null
        val windowLocation = IntArray(2)
        view.rootView.getLocationOnScreen(windowLocation)
        bounds.offset(windowLocation[0], windowLocation[1])
        if (!bounds.contains(screenX, screenY)) return null
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                findBeastHoverTarget(view.getChildAt(index), screenX, screenY)?.let { return it }
            }
        }
        return view.takeIf { it.isEnabled && it.isBeastHoverTarget() }
    }

    override fun routeAppScroll(displayId: Int, x: Int, y: Int, amount: Float): Boolean {
        if (display?.displayId != displayId) return false
        if (omaDesktop?.popupScroll(x, y, amount * InputSettings.scrollSensitivity) == true) return true
        if (!launcherActive && TransientPanelCoordinator.isActiveOnDisplay(displayId)) return false
        if (desktopAt(x, y)) {
            val properties = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = x.toFloat()
                this.y = y.toFloat()
                setAxisValue(MotionEvent.AXIS_VSCROLL, amount * InputSettings.scrollSensitivity)
            })
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, properties, coords,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0)
            try { root.dispatchGenericMotionEvent(event) } finally { event.recycle() }
            return true
        }
        if (TransientPanelCoordinator.isActiveOnDisplay(displayId)) return false
        return visibleCardsTopFirst().any { it.routeScrollAt(x, y, amount) }
    }

    override fun routeAppPinch(displayId: Int, x: Int, y: Int, scale: Float): Boolean {
        if (display?.displayId != displayId) return false
        if (!launcherActive && TransientPanelCoordinator.isActiveOnDisplay(displayId)) return false
        if (desktopAt(x, y)) return true
        if (TransientPanelCoordinator.isActiveOnDisplay(displayId)) return false
        return visibleCardsTopFirst().any { it.routePinchAt(x, y, scale) }
    }

    override fun routeAppClick(displayId: Int, x: Int, y: Int, button: Int): Boolean {
        if (display?.displayId != displayId) return false
        if (omaDesktop?.popupClick(x, y, button) == true) return true
        if (launcherActive && !desktopAt(x, y)) {
            hideLauncher()
            return true
        }
        if (!launcherActive && TransientPanelCoordinator.isActiveOnDisplay(displayId)) return false
        desktopLongPress.cancel()
        if (desktopAt(x, y)) {
            desktopFocused = true
            if (button == MotionEvent.BUTTON_PRIMARY) {
                val target = root
                val downAt = SystemClock.uptimeMillis()
                dispatchLauncherTouch(target, x, y, MotionEvent.ACTION_DOWN, downAt)
                dispatchLauncherTouch(target, x, y, MotionEvent.ACTION_UP, downAt)
            } else if (button == MotionEvent.BUTTON_SECONDARY) {
                longPressDesktop(x, y)
            }
            return true
        }
        if (TransientPanelCoordinator.isActiveOnDisplay(displayId)) return false
        // Fullscreen puts the hosted TextureView over the entire Beast window. Intercept
        // clicks on the shell-owned exit affordance before the app-routing fast path.
        if (button == MotionEvent.BUTTON_PRIMARY && isFullscreenExitHit(x, y)) {
            fullscreenExitForFocusedTask()?.performClick()
            return true
        }
        desktopFocused = false
        return visibleCardsTopFirst().any { it.routeClickAt(x, y, button) }
    }

    override fun routeAppPointer(
        displayId: Int,
        x: Int,
        y: Int,
        action: Int,
        button: Int,
        buttonState: Int,
    ): Boolean {
        if (display?.displayId != displayId) return false
        if (!phoneViewSession && handleWindowDrag(x, y, action, button)) return true
        if (omaDesktop?.popupPointer(x, y, action, button) == true) return true
        routedLauncherRoot?.let { target ->
            // Consume the captured stream even if dismissal/rebuilding removes the view.
            val valid = target === root && routedLauncherView === launcher &&
                    routedLauncherLease === launcherLease && launcher.isShown &&
                    (launcherActive || !TransientPanelCoordinator.isActiveOnDisplay(displayId))
            if (routedLauncherOutside) {
                if (valid && action == MotionEvent.ACTION_UP && !desktopAt(x, y)) hideLauncher()
            } else if (routedLauncherButton == MotionEvent.BUTTON_SECONDARY) {
                val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                if (!valid || kotlin.math.abs(x - secondaryDownX) > slop ||
                    kotlin.math.abs(y - secondaryDownY) > slop) secondaryCancelled = true
                if (action == MotionEvent.ACTION_UP && !secondaryCancelled && desktopAt(x, y)) {
                    longPressDesktop(secondaryDownX, secondaryDownY)
                }
            } else if (routedLauncherButton == MotionEvent.BUTTON_PRIMARY) {
                if (valid) {
                    dispatchLauncherTouch(target, x, y, action, routedLauncherDownAt)
                } else {
                    dispatchLauncherTouch(target, x, y, MotionEvent.ACTION_CANCEL, routedLauncherDownAt)
                }
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                routedLauncherRoot = null
                routedLauncherView = null
                routedLauncherLease = null
                routedLauncherButton = 0
                routedLauncherOutside = false
            }
            return true
        }
        if (!launcherActive && TransientPanelCoordinator.isActiveOnDisplay(displayId)) return false
        if (action == MotionEvent.ACTION_DOWN && (desktopAt(x, y) || launcherActive)) {
            desktopLongPress.cancel()
            secondaryDownX = x
            secondaryDownY = y
            secondaryCancelled = false
            desktopFocused = true
            if (action == MotionEvent.ACTION_DOWN) {
                routedPointerCard?.cancelRoutedPointer()
                routedPointerCard = null
                routedFullscreenExitPointer = false
                routedLauncherRoot = root
                routedLauncherView = launcher
                routedLauncherLease = launcherLease
                routedLauncherButton = button
                routedLauncherDownAt = SystemClock.uptimeMillis()
                routedLauncherOutside = !desktopAt(x, y)
                if (button == MotionEvent.BUTTON_PRIMARY && !routedLauncherOutside) {
                    dispatchLauncherTouch(root, x, y, action, routedLauncherDownAt)
                }
            }
            return true
        }
        if (TransientPanelCoordinator.isActiveOnDisplay(displayId)) return false
        if (action == MotionEvent.ACTION_DOWN) {
            desktopLongPress.cancel()
            desktopFocused = false
            routedPointerCard?.cancelRoutedPointer()
            routedPointerCard = null
            routedPointerButton = 0
            routedFullscreenExitPointer = button == MotionEvent.BUTTON_PRIMARY &&
                isFullscreenExitHit(x, y)
            if (routedFullscreenExitPointer) return true
            val target = visibleCardsTopFirst().firstOrNull {
                it.routePointerAt(x, y, action, button, buttonState)
            } ?: return false
            routedPointerCard = target
            routedPointerButton = button
            return true
        }

        if (routedFullscreenExitPointer) {
            if (action == MotionEvent.ACTION_UP) {
                routedFullscreenExitPointer = false
                if (button == MotionEvent.BUTTON_PRIMARY) fullscreenExitForFocusedTask()?.performClick()
            } else if (action == MotionEvent.ACTION_CANCEL) {
                routedFullscreenExitPointer = false
            }
            return true
        }

        val target = routedPointerCard ?: return false
        // Once DOWN is captured, consume the whole stream even if the card disappears;
        // falling back to BeastActivity would create a mismatched click-through gesture.
        target.routePointerAt(x, y, action, routedPointerButton, buttonState)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            routedPointerCard = null
            routedPointerButton = 0
        }
        return true
    }

    /** PhoneUI owns window focus while searching; the launcher still owns its gestures. */
    private fun dispatchLauncherTouch(target: View, x: Int, y: Int, action: Int, downAt: Long) {
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        val event = MotionEvent.obtain(
            downAt, SystemClock.uptimeMillis(), action,
            (x - location[0]).toFloat(), (y - location[1]).toFloat(), 0,
        )
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        try {
            // Dispatch through the root so Android applies aspectLayer's inverse transform
            // and preserves the launcher's normal click/drag/ScrollView semantics.
            target.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun fullscreenExitForFocusedTask(): TextView? {
        val focusedId = WorkspaceState.focusedTaskId ?: return null
        val task = WorkspaceState.tasks.firstOrNull {
            it.id == focusedId && it.mode == PresentationMode.FULLSCREEN
        } ?: return null
        return cards[task.id]?.fullscreenExitControl()
    }

    private fun isFullscreenExitHit(x: Int, y: Int): Boolean {
        val exit = fullscreenExitForFocusedTask() ?: return false
        if (exit.visibility != View.VISIBLE || !exit.isEnabled) return false
        val bounds = Rect()
        return exit.getGlobalVisibleRect(bounds) && bounds.contains(x, y)
    }

    private fun buildUi() {
        desktopLongPress.cancel()
        omaDesktop?.close()
        omaDesktop = null
        omalauncherPresence = OmalauncherPresence.detect(this)
        if (!presenceReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            androidx.core.content.ContextCompat.registerReceiver(this, presenceReceiver, filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            presenceReceiverRegistered = true
        }
        root = FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }
        aspectLayer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        root.addView(aspectLayer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }
        toolbar = FrameLayout(this).apply {
            setBackgroundColor(InputSettings.theme.background)
            setPadding(uiDp(10), 0, uiDp(10), 0)
        }
        shell.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, uiDp(26),
        ))
        workspaceScroll = DesktopWorkspaceScrollView(this) { x, y ->
            omaDesktop == null || windowAt(x, y)
        }.apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
        }
        taskStrip = FrameLayout(this)
        workspaceScroll.addView(taskStrip, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shell.addView(workspaceScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        taskbar = FrameLayout(this).apply {
            // Keep overflow controls flush with the external-display edges. The navigation
            // row already reserves their width when visible, so an outer inset would make the
            // arrows miss the cursor when it is parked at the far left or right edge.
            setPadding(0, 0, 0, 0)
            setBackgroundColor(InputSettings.theme.background)
        }
        taskbarScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // Keep the tab row wrap-content so its configured alignment remains meaningful
            // when it does not overflow.
            isFillViewport = false
        }
        taskbarContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        taskTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        taskbarContent.addView(taskTabs, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        taskbarScroll.addView(taskbarContent, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        fun taskbarStepButton(symbol: String, description: String, direction: Int) =
            label(symbol, 20f, ACCENT, bold = true).apply {
                gravity = Gravity.CENTER
                contentDescription = description
                visibility = View.GONE
                setBeastClick { scrollTaskbarBy(direction) }
            }
        taskbarPrevious = taskbarStepButton("‹", "Show earlier task tabs", -1)
        taskbarNext = taskbarStepButton("›", "Show later task tabs", 1)
        val taskbarNavigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(taskbarPrevious, LinearLayout.LayoutParams(
                uiDp(TASKBAR_STEP_CONTROL_WIDTH_DP),
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(taskbarScroll, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f,
            ))
            addView(taskbarNext, LinearLayout.LayoutParams(
                uiDp(TASKBAR_STEP_CONTROL_WIDTH_DP),
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        taskbar.addView(taskbarNavigation, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        taskbarShell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(InputSettings.theme.darkBackground)
        }
        taskbarShell.addView(taskbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        viewportMap = ViewportMapView(this)
        taskbarShell.addView(viewportMap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, uiDp(2)))
        taskbarShell.visibility = if (phoneViewSession) View.VISIBLE else View.GONE
        shell.addView(taskbarShell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (phoneViewSession) uiDp(TASKBAR_HEIGHT_DP) else 0))
        aspectLayer.addView(shell, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fullscreenLayer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }
        aspectLayer.addView(fullscreenLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val embedded: () -> DesktopSession = {
            OmaDesktop(this, ::hideLauncher) { panelOpen ->
                // Compose may report the outgoing panel closed before a new search opens.
                // Phone search still owns the modal session throughout that transition.
                setLauncherActive(panelOpen || LauncherSearchSession.active)
            }
        }
        val session = embedded()
        omaDesktop = session
        sharedDesktop = false
        launcher = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(session.view, FrameLayout.LayoutParams(-1, -1))
        }
        // Keep the home below app tiles, with menus raised only while active.
        aspectLayer.addView(launcher, 0, FrameLayout.LayoutParams(-1, -1).apply {
            topMargin = uiDp(26)
            bottomMargin = if (phoneViewSession) uiDp(TASKBAR_HEIGHT_DP) else 0
        })
        cursorOverlay = BeastCursorOverlay(this)
        cursorOverlay.translationZ = 2f
        aspectLayer.addView(cursorOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        root.post {
            if (!phoneViewSession && !WorkspaceCursor.position().visible) WorkspaceCursor.moveTo(root.width / 2f, root.height / 2f, root.width.toFloat(), root.height.toFloat())
        }
        setLauncherActive(launcherActive)
        omaDesktop?.visible(true)

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            WorkspaceCursor.publishViewport(display?.displayId ?: -1, root.width, root.height)
            if (!phoneViewSession && !WorkspaceCursor.position().visible) WorkspaceCursor.moveTo(root.width / 2f, root.height / 2f, root.width.toFloat(), root.height.toFloat())
            updateHorizontalAspectCorrection()
            cards.values.forEach { it.renderedVerticalScale = renderedVerticalScale() }
            keepCursorOnTop()
        }

        workspaceScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateTaskSizes() }
        workspaceScroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            if (!phoneViewSession && WorkspaceState.visibleTasks().any { it.floating || it.pinned || it.workspace == 0 }) updateTaskSizes()
            viewportMap.scrollOffsetPx = scrollX
            syncViewportMap()
            val maxScroll = (taskStrip.width - workspaceScroll.width).coerceAtLeast(1)
            WorkspaceState.setViewportPosition(scrollX.toFloat() / maxScroll)
        }
        taskbarScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            updateTaskbarOverflowControls()
        }
        taskbarScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTaskbarOverflowControls()
        }
    }

    private fun enterImmersiveMode() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.decorView.windowInsetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun applyAppearance() {
        val mode = display?.mode
        val ultrawide = mode != null && mode.physicalWidth >= 3000
        // Hardware mode changes can bypass PhoneUI. Keep the persisted setting and
        // its switch in sync with the physical Beast timing whenever we observe it.
        if (!phoneViewSession && InputSettings.ultrawideEnabled != ultrawide) {
            InputSettings.setUltrawideEnabled(applicationContext, ultrawide)
        }
        val nextElementScale = displayProfile().uiScale
        val elementScaleChanged = kotlin.math.abs(uiElementScale - nextElementScale) >= .01f
        uiElementScale = nextElementScale
        val nextFontScale = nextElementScale
        val fontScaleChanged = kotlin.math.abs(uiFontScale - nextFontScale) >= .01f
        uiFontScale = nextFontScale
        if (::root.isInitialized) root.setBackgroundColor(InputSettings.theme.darkerBackground)
        if (::toolbar.isInitialized) toolbar.setBackgroundColor(InputSettings.theme.background)
        if (::taskbar.isInitialized) taskbar.setBackgroundColor(InputSettings.theme.background)
        if (::taskbarShell.isInitialized) taskbarShell.setBackgroundColor(InputSettings.theme.background)
        workspaceMenu?.dismiss()
        if (::viewportMap.isInitialized) viewportMap.invalidate()
        cards.values.forEach(BeastTaskCard::refreshAccent)
        if (::taskbarPrevious.isInitialized) {
            taskbarPrevious.setTextColor(InputSettings.accentColor)
            taskbarNext.setTextColor(InputSettings.accentColor)
        }
        refreshBeastHoverAppearance()
        if (::launcherPanel.isInitialized) applyLauncherBorder()
        if ((elementScaleChanged || fontScaleChanged) && ::root.isInitialized) {
            rebuildUiForElementScale()
            return
        }
        if (::launcherQuery.isInitialized) launcherQuery.textSize = 18f * uiFontScale
        cards.values.forEach { it.fontScale = uiFontScale }
        updateHorizontalAspectCorrection()
        renderWorkspace()
        refreshVisibleLauncher()
    }

    /** Rebuild only the visible launcher so its accent-colored controls repaint immediately. */
    private fun refreshVisibleLauncher() {
        if (omaDesktop != null) return
        if (!::launcher.isInitialized || !launcherActive) return
        val parent = launcher.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(launcher)
        if (index < 0) return
        val query = launcherQuery.text.toString()
        parent.removeViewAt(index)
        launcher = buildLauncher()
        parent.addView(
            launcher,
            index,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setLauncherActive(true)
        launcherQuery.setText(query)
        populateLauncher(query)
    }

    /**
     * REDMAGIC exposes Beast Ultrawide as a tall logical canvas (for example 3840×2381)
     * composed onto a 3840×1200 panel. Without correction it compresses vertically and
     * therefore stretches all workspace content horizontally. Widen the rendered layer by
     * the inverse factor before scaling it around its centre. The resulting transformed
     * bounds fill the monitor instead of producing a half-width, centred workspace.
     */
    private fun updateHorizontalAspectCorrection() {
        if (!::aspectLayer.isInitialized || !::root.isInitialized || root.width <= 0 || root.height <= 0) return
        val correction = horizontalAspectCorrection()
        val targetLayerWidth = (root.width / correction).roundToInt().coerceAtLeast(root.width)
        val params = aspectLayer.layoutParams as FrameLayout.LayoutParams
        val geometryChanged = params.width != targetLayerWidth ||
            kotlin.math.abs(aspectLayer.scaleX - correction) >= .001f
        if (params.width != targetLayerWidth || params.gravity != Gravity.CENTER) {
            params.width = targetLayerWidth
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.CENTER
            aspectLayer.layoutParams = params
        }
        aspectLayer.pivotX = targetLayerWidth / 2f
        aspectLayer.pivotY = root.height / 2f
        aspectLayer.scaleX = correction
        // The widened layer exactly cancels scaleX, so the visible cursor/input grid
        // once again spans the full physical display.
        val effectiveInputScale = correction * targetLayerWidth / root.width.toFloat()
        WorkspaceCursor.publishHorizontalTransform(display?.displayId ?: -1, effectiveInputScale)
        if (geometryChanged && ::workspaceScroll.isInitialized) {
            workspaceScroll.post(::updateTaskSizes)
        }
    }

    private fun horizontalAspectCorrection(): Float {
        if (!::root.isInitialized || root.width <= 0 || root.height <= 0) return 1f
        val mode = display?.mode ?: return 1f
        if (mode.physicalWidth < 3000 || mode.physicalWidth <= 0 || mode.physicalHeight <= 0) return 1f
        val physicalScaleX = mode.physicalWidth.toFloat() / root.width
        val physicalScaleY = mode.physicalHeight.toFloat() / root.height
        return (physicalScaleY / physicalScaleX).coerceIn(.40f, 1f)
    }

    private fun displayProfile() = com.lateral.DisplayPreferences.load(
        this, com.lateral.DisplayPreferences.target(this, display
            ?: getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)),
    )

    private fun phoneAppConfiguration(): InputSettings.PhoneAppConfiguration =
        InputSettings.PhoneAppConfiguration(
            InputSettings.phoneAppDensityDpi,
            InputSettings.phoneFontScale,
            InputSettings.phoneAppWindowHeightPx,
            displayProfile().hostedScale,
            displayProfile().supersampling,
        )

    /** Converts the logical Beast canvas height into physical panel pixels. */
    private fun renderedVerticalScale(): Float {
        val mode = display?.mode ?: return 1f
        if (!::root.isInitialized || root.height <= 0 || mode.physicalHeight <= 0) return 1f
        return (mode.physicalHeight.toFloat() / root.height).coerceIn(.1f, 1f)
    }

    private fun rebuildUiForElementScale() {
        val launcherWasVisible = ::launcher.isInitialized && launcherActive
        buildUi()
        renderWorkspace()
        if (launcherWasVisible) showLauncher() else restoreWorkspaceFocus()
        root.post {
            WorkspaceCursor.publishViewport(display?.displayId ?: -1, root.width, root.height)
        }
    }

    private fun renderWorkspace(revealFocusedTask: Boolean = false) {
        val tasks = WorkspaceState.tasks
        // An explicit launcher request becomes visible immediately, then receives its
        // Android task ID during reconciliation. Baseline imports remain PHONE/minimized.
        val shownTasks = if (phoneViewSession) tasks.filter {
            it.placement == TaskPlacement.BEAST_VISIBLE || it.placement == TaskPlacement.PENDING_BEAST
        } else WorkspaceState.visibleTasks()
        if (shownTasks.isEmpty()) desktopFocused = true
        val liveIds = tasks.mapTo(hashSetOf()) { it.id }
        cards.keys.filterNot(liveIds::contains).forEach { id ->
            cards.remove(id)?.let { card ->
                (card.parent as? ViewGroup)?.removeView(card)
                card.release()
            }
        }
        tasks.forEach { task ->
            val card = cards.getOrPut(task.id) { createCard(task) }
            card.uiScale = uiElementScale
            card.fontScale = uiFontScale
            card.phoneAppConfiguration = phoneAppConfiguration()
            card.renderedVerticalScale = this@BeastActivity.renderedVerticalScale()
            card.omarchyChrome = !phoneViewSession
            card.bind(task, WorkspaceState.focusedTaskId == task.id)
        }

        for (index in taskStrip.childCount - 1 downTo 0) {
            val card = taskStrip.getChildAt(index) as? BeastTaskCard ?: continue
            if (shownTasks.none { cards[it.id] === card }) taskStrip.removeViewAt(index)
        }

        val fullscreen = shownTasks.firstOrNull {
            it.id == WorkspaceState.focusedTaskId && it.mode == PresentationMode.FULLSCREEN
        }
        if (fullscreen != null) {
            workspaceScroll.visibility = View.GONE
            taskbarShell.visibility = View.GONE
            fullscreenLayer.visibility = View.VISIBLE
            val card = cards.getValue(fullscreen.id)
            card.setFullscreen(true)
            if (card.parent !== fullscreenLayer) {
                (card.parent as? ViewGroup)?.removeView(card)
                fullscreenLayer.removeAllViews()
                fullscreenLayer.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        } else {
            fullscreenLayer.removeAllViews()
            fullscreenLayer.visibility = View.GONE
            workspaceScroll.visibility = View.VISIBLE
            taskbarShell.visibility = if (phoneViewSession) View.VISIBLE else View.GONE
            shownTasks.forEachIndexed { index, task ->
                val card = cards.getValue(task.id)
                card.setFullscreen(false)
                if (card.parent !== taskStrip) {
                    (card.parent as? ViewGroup)?.removeView(card)
                    taskStrip.addView(card, index)
                } else if (taskStrip.indexOfChild(card) != index) {
                    taskStrip.removeView(card)
                    taskStrip.addView(card, index)
                }
            }
            updateTaskSizes()
            if (revealFocusedTask) revealFocusedTask()
            taskStrip.post(::syncViewportMap)
        }
        renderTaskbar(tasks)
        renderWorkspaceTabs()
    }

    private fun createCard(task: BeastTask) = BeastTaskCard(this, controlDebouncer).apply {
        setPhoneDisplay(phoneViewSession)
        uiScale = uiElementScale
        fontScale = uiFontScale
        phoneAppConfiguration = phoneAppConfiguration()
        renderedVerticalScale = this@BeastActivity.renderedVerticalScale()
        bind(task, false)
        onFocus = { focusTask(task) }
        onBack = { performBack() }
        onMove = { delta -> WorkspaceState.move(task.id, delta) }
        onMode = { WorkspaceState.setMode(task.id, task.mode.nextFramed()) }
        onFullscreen = {
            val currentMode = WorkspaceState.tasks.firstOrNull { it.id == task.id }?.mode ?: task.mode
            val nextMode = if (currentMode == PresentationMode.FULLSCREEN) {
                WorkspaceState.tasks.firstOrNull { it.id == task.id }?.fullscreenReturnMode
                    ?: PresentationMode.TABLET
            } else {
                PresentationMode.FULLSCREEN
            }
            Log.d(TAG, "fullscreen_toggle task=${task.id} $currentMode->$nextMode")
            WorkspaceState.setMode(
                task.id,
                nextMode,
            )
        }
        onMinimize = { WorkspaceState.minimize(task.id) }
        onCaptureApp = {
            requestScreenshot(BeastScreenshotController.ScreenshotTarget.FOCUSED_APP)
        }
        onWindowMenu = {
            focusTask(task)
            showCommands(task.label.uppercase(Locale.getDefault()), "WINDOW  /  ${task.mode.name.lowercase()}  /  ${InputSettings.theme.name}", listOf(
                WorkspaceCommand("FLOAT / TILE", "Super+T") { WorkspaceState.toggleFloating(task.id) },
                WorkspaceCommand("FULL WIDTH", "Keep the top bar · Super+Alt+F") { WorkspaceState.toggleMaximized(task.id) },
                WorkspaceCommand("PIN / UNPIN", "Follow workspace changes · Super+O") { WorkspaceState.toggleFloating(task.id, pin = true) },
                WorkspaceCommand("GROUP / UNGROUP", "New windows join the group · Super+G") { WorkspaceState.toggleGroup(task.id) },
                WorkspaceCommand("MOVE TO WORKSPACE", "Super+Shift+1–0") { showCommands("MOVE WINDOW", task.label, (1..10).map { workspace -> WorkspaceCommand("WORKSPACE $workspace", "Move and follow") { WorkspaceState.moveToWorkspace(task.id, workspace) } }) },
                WorkspaceCommand("PHONE WIDTH", "Tall Android layout") { WorkspaceState.setMode(task.id, PresentationMode.PHONE) },
                WorkspaceCommand("TABLET WIDTH", "Room for desktop-style apps") { WorkspaceState.setMode(task.id, PresentationMode.TABLET) },
                WorkspaceCommand("MOVE LEFT", "Move this window one slot earlier") { WorkspaceState.move(task.id, -1) },
                WorkspaceCommand("MOVE RIGHT", "Move this window one slot later") { WorkspaceState.move(task.id, 1) },
                WorkspaceCommand("CAPTURE APP", "App content only · unavailable for protected windows", canCapture()) {
                    if (WorkspaceState.focusedTaskId == task.id) onCaptureApp?.invoke()
                },
            ))
        }
        onDisplayReady = { displayId ->
            WorkspaceState.markPendingDisplayReady(task.id, displayId)
        }
        onTaskHosted = {
            WorkspaceState.markPendingHosted(task.id)
            onShowAllTaskHosted(task.id)
        }
        onRestoreFailed = { result ->
            Log.w(
                TAG,
                "restore_diagnostic result=${result.name} workspaceId=${task.id} " +
                    "androidTaskId=${task.androidTaskId} package=${task.packageName}",
            )
        }
        onClose = {
            closeTask(task)
        }
    }

    private fun updateTaskSizes() {
        if (!phoneViewSession) {
            val width = workspaceScroll.width
            val height = workspaceScroll.height
            if (width <= 0 || height <= 0) return
            val bounds = WorkspaceState.windowBounds(width, height, dp(10), dp(10))
            val contentWidth = maxOf(width, bounds.values.maxOfOrNull { it.right + dp(10) } ?: width)
            if (taskStrip.layoutParams.width != contentWidth) taskStrip.layoutParams = taskStrip.layoutParams.apply { this.width = contentWidth }
            bounds.forEach { (id, box) ->
                val card = cards[id] ?: return@forEach
                if (card.parent !== taskStrip) return@forEach
                val current = card.layoutParams as? FrameLayout.LayoutParams
                val task = WorkspaceState.tasks.firstOrNull { it.id == id }
                val x = box.x + if (WorkspaceState.layout() == WorkspaceLayout.SCROLLING && task?.let { it.floating || it.pinned || it.workspace == 0 } == true) workspaceScroll.scrollX else 0
                if (current?.width != box.width || current.height != box.height || current.leftMargin != x || current.topMargin != box.y) {
                    card.layoutParams = FrameLayout.LayoutParams(box.width, box.height).apply { leftMargin = x; topMargin = box.y }
                }
            }
            WorkspaceState.visibleTasks().filter { it.floating || it.pinned || it.workspace == 0 }.forEach { cards[it.id]?.bringToFront() }
            WorkspaceState.tasks.firstOrNull { it.id == WorkspaceState.focusedTaskId && (it.floating || it.pinned || it.maximized || it.workspace == 0) }?.let { cards[it.id]?.bringToFront() }
            if (WorkspaceState.layout() == WorkspaceLayout.DWINDLE) workspaceScroll.scrollTo(0, 0)
            return
        }
        val cardHeight = workspaceScroll.height - taskStrip.paddingTop - taskStrip.paddingBottom
        val appContentHeight = cardHeight - (dp(APP_DECORATOR_HEIGHT_DP) * uiElementScale.coerceIn(.85f, 1.15f)).roundToInt() -
            dp(OMARCHY_WINDOW_BORDER_DP) * 2
        if (appContentHeight <= 0) return
        // Derive width from the actual app surface height remaining after the unified
        // UI/text scale has laid out the toolbar, taskbar, strip padding, and decorator.
        val profile = displayProfile()
        val border = dp(OMARCHY_WINDOW_BORDER_DP) * 2
        val phoneWidth = (appContentHeight * profile.phoneAspect).roundToInt().coerceAtLeast(1) + border
        val tabletWidth = (appContentHeight * profile.tabletAspect).roundToInt().coerceAtLeast(1) + border
        var left = dp(8)
        WorkspaceState.tasks.filter {
            it.placement == TaskPlacement.BEAST_VISIBLE ||
                it.placement == TaskPlacement.PENDING_BEAST
        }.forEach { task ->
            val card = cards[task.id] ?: return@forEach
            val width = if (task.mode == PresentationMode.PHONE) phoneWidth else tabletWidth
            val margin = dp(12)
            val current = card.layoutParams as? FrameLayout.LayoutParams
            if (current?.width == width &&
                current.height == ViewGroup.LayoutParams.MATCH_PARENT &&
                current.leftMargin == left
            ) return@forEach
            card.layoutParams = FrameLayout.LayoutParams(
                width,
                cardHeight,
            ).apply { leftMargin = left }
            left += width + margin
        }
        taskStrip.layoutParams = taskStrip.layoutParams.apply { width = left }
    }

    private fun revealFocusedTask() {
        val card = cards[WorkspaceState.focusedTaskId] ?: return
        workspaceScroll.post {
            if (card.parent !== taskStrip || card.width <= 0) return@post
            val viewportLeft = workspaceScroll.scrollX
            val viewportRight = viewportLeft + workspaceScroll.width
            val margin = dp(12)
            val target = when {
                card.left < viewportLeft + margin -> card.left - margin
                card.right > viewportRight - margin -> card.right - workspaceScroll.width + margin
                else -> return@post
            }
            val maxScroll = (taskStrip.width - workspaceScroll.width).coerceAtLeast(0)
            workspaceScroll.smoothScrollTo(target.coerceIn(0, maxScroll), 0)
        }
    }

    private fun renderTaskbar(tasks: List<BeastTask>) {
        (taskbarContent.layoutParams as FrameLayout.LayoutParams).gravity = alignmentGravity(
            InputSettings.taskbarAlignment,
        )
        taskTabs.removeAllViews()
        tasks.forEachIndexed { index, task ->
            val focused = WorkspaceState.focusedTaskId == task.id
            val numberColor = if (focused) ACCENT else InputSettings.theme.lightForeground
            val labelColor = InputSettings.theme.foreground
            val number = label("%02d".format(index + 1), 11f, numberColor, bold = focused).apply {
                gravity = Gravity.CENTER; setPadding(uiDp(7), 0, uiDp(7), 0)
            }
            val title = label(task.shortLabel.uppercase(Locale.getDefault()), TASKBAR_TEXT_SIZE, labelColor, bold = focused).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                maxEms = TASKBAR_LABEL_CHARACTERS
                ellipsize = TextUtils.TruncateAt.END
            }
            val state = when {
                !task.supportsMultiWindow -> "PHONE"
                task.minimized -> "—"
                else -> task.mode.marker
            }
            val badge = label(state, 10f, InputSettings.theme.lightForeground).apply {
                gravity = Gravity.CENTER; setPadding(uiDp(8), 0, uiDp(7), 0)
            }
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = desktopTabBackground(focused, resources.displayMetrics.density)
                isClickable = true
                isFocusable = true
                contentDescription = "Task ${index + 1}, ${task.label}, ${if (task.minimized) "minimized" else state}${if (focused) ", focused" else ""}"
                tooltipText = contentDescription
                addView(number, LinearLayout.LayoutParams(-2, -1))
                addView(title, LinearLayout.LayoutParams(-2, -1))
                addView(badge, LinearLayout.LayoutParams(-2, -1))
                setBeastClick("task:${task.id}") { focusTask(task) }
                installBeastHover(listOf(number to { numberColor }, title to { labelColor }, badge to { InputSettings.theme.lightForeground }))
                taskTabs.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, uiDp(32)).apply {
                    marginEnd = uiDp(4)
                })
            }
        }
        if (tasks.isEmpty()) taskTabs.addView(label("  WORKSPACE READY  ·  +APPS TO BEGIN", 11f, InputSettings.theme.lightForeground).apply {
            gravity = Gravity.CENTER_VERTICAL; setBeastClick(::showLauncher)
            contentDescription = "Workspace empty. Open apps"
        }, LinearLayout.LayoutParams(-2, -1))
        taskbarScroll.post(::updateTaskbarOverflowControls)
        renderToolbar()
    }

    /** Shows edge controls only when tabs continue beyond the visible taskbar viewport. */
    private fun updateTaskbarOverflowControls() {
        if (!::taskbarScroll.isInitialized || taskbarScroll.width <= 0) return
        val overflowing = taskbarContent.width > taskbarScroll.width
        val contentParams = taskbarContent.layoutParams as? FrameLayout.LayoutParams
        val expectedGravity = if (overflowing) {
            Gravity.START or Gravity.CENTER_VERTICAL
        } else {
            alignmentGravity(InputSettings.taskbarAlignment)
        }
        if (contentParams != null && contentParams.gravity != expectedGravity) {
            contentParams.gravity = expectedGravity
            taskbarContent.layoutParams = contentParams
            taskbarContent.post(::updateTaskbarOverflowControls)
            return
        }
        val firstTab = taskTabs.getChildAt(0)
        val lastTab = taskTabs.getChildAt(taskTabs.childCount - 1)
        val contentOffset = taskbarContent.left - taskbarScroll.scrollX
        val firstTabStart = firstTab?.left?.plus(contentOffset) ?: 0
        val lastTabEnd = lastTab?.right?.plus(contentOffset) ?: 0
        val canShowEarlier = overflowing && firstTabStart < 0
        val canShowLater = overflowing && lastTabEnd > taskbarScroll.width
        val previousVisibility = if (canShowEarlier) View.VISIBLE else View.GONE
        val nextVisibility = if (canShowLater) View.VISIBLE else View.GONE
        if (taskbarPrevious.visibility != previousVisibility) {
            taskbarPrevious.visibility = previousVisibility
        }
        if (taskbarNext.visibility != nextVisibility) {
            taskbarNext.visibility = nextVisibility
        }
    }

    private fun scrollTaskbarBy(direction: Int) {
        if (direction == 0 || taskbarScroll.width <= 0) return
        val maxScroll = (taskbarContent.width - taskbarScroll.width).coerceAtLeast(0)
        val step = (taskbarScroll.width * TASKBAR_SCROLL_STEP_FRACTION).roundToInt().coerceAtLeast(1)
        taskbarScroll.smoothScrollTo(
            (taskbarScroll.scrollX + direction * step).coerceIn(0, maxScroll),
            0,
        )
    }

    /** Top bar contains workspace navigation and settings; the app strip stays below. */
    private fun renderToolbar() {
        val key = ToolbarRenderKey(InputSettings.theme, uiElementScale, uiFontScale,
            resources.displayMetrics.densityDpi, omaDesktop != null, sharedDesktop)
        // Task reconciliation/focus changes do not change these controls. Rebuilding
        // them on every task update creates an unmeasured frame that drops mouse DOWN.
        if (toolbar.childCount > 0 && toolbarRenderKey == key) return
        toolbarRenderKey = key
        toolbar.removeAllViews()
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun action(title: String, description: String, click: () -> Unit) = label(title, TOOLBAR_TEXT_SIZE, InputSettings.theme.foreground).apply {
            gravity = Gravity.CENTER; setPadding(uiDp(10), 0, uiDp(10), 0)
            setSingleLine(true); includeFontPadding = false
            contentDescription = description; tooltipText = description; isFocusable = true
            setBeastClick(click)
        }
        val brand = action("●", "Omarchy menu · Super+Space", ::showWorkspaceMenu).apply { setTextColor(ACCENT) }
        controls.addView(brand, LinearLayout.LayoutParams(-2, -1))
        workspaceTabs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        workspaceTabsKey = null
        controls.addView(workspaceTabs, LinearLayout.LayoutParams(-2, -1))
        val optional = mutableListOf<View>()
        val trailing = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val windows = action("≡", "Open windows · Alt+Tab", ::showWindowSwitcher)
        trailing.addView(windows, LinearLayout.LayoutParams(-2, -1))
        val appsButton = action("⌕", "Applications · Super+A", ::showLauncher)
        trailing.addView(appsButton, LinearLayout.LayoutParams(-2, -1))
        shellIndicators = action(systemIndicatorsText(), "Network, battery and desktop settings", ::showInputSettings)
        trailing.addView(shellIndicators, LinearLayout.LayoutParams(-2, -1))
        optional += listOf(windows, appsButton, shellIndicators)
        systemStatus = label(systemStatusText(), TOOLBAR_TEXT_SIZE, InputSettings.theme.brightForeground).apply {
            gravity = Gravity.CENTER
            contentDescription = "Day and time"
            maxLines = 1
        }
        toolbar.addView(DesktopBarLayout(this, controls, systemStatus, trailing, optional), FrameLayout.LayoutParams(-1, -1))
        renderWorkspaceTabs()
    }

    private fun renderWorkspaceTabs() {
        if (!::workspaceTabs.isInitialized) return
        val occupied = WorkspaceState.tasks.filter { it.placement != TaskPlacement.PHONE }.map { it.workspace }.filter { it in 1..10 }.toSet()
        val workspaces = ((1..5).toList() + occupied + WorkspaceState.activeWorkspace).distinct().sorted()
        val key = "${WorkspaceState.activeWorkspace}:${WorkspaceState.layout()}:$occupied:${InputSettings.theme.id}:$uiElementScale"
        if (workspaceTabsKey == key) return
        workspaceTabsKey = key
        workspaceTabs.removeAllViews()
        workspaces.forEach { number ->
            val active = number == WorkspaceState.activeWorkspace
            val symbol = if (active) if (WorkspaceState.layout() == WorkspaceLayout.DWINDLE) " ▣" else " ⇥" else ""
            workspaceTabs.addView(label("$number$symbol", TOOLBAR_TEXT_SIZE, if (active) ACCENT else if (number in occupied) InputSettings.theme.foreground else InputSettings.theme.muted).apply {
                gravity = Gravity.CENTER; setPadding(uiDp(7), 0, uiDp(7), 0)
                setSingleLine(true); includeFontPadding = false
                contentDescription = "Workspace $number${if (active) ", ${WorkspaceState.layout().name.lowercase()}, active" else ""}"
                tooltipText = if (active) "Cycle workspace layout · Super+L" else "Switch workspace · Super+$number"
                setBeastClick("workspace:$number") { if (active) WorkspaceState.toggleLayout() else WorkspaceState.switchWorkspace(number) }
            }, LinearLayout.LayoutParams(-2, -1))
        }
    }

    private fun showWindowSwitcher() = showCommands("WINDOWS", "Select a window to switch to its workspace", WorkspaceState.tasks.map { task ->
        WorkspaceCommand(task.label, "Workspace ${if (task.workspace == 0) "scratchpad" else task.workspace.toString()}${if (task.pinned) " · pinned" else ""}${if (task.minimized) " · hidden" else ""}") { focusTask(task) }
    })

    private fun handleOmarchyShortcut(shortcut: OmarchyShortcut) {
        if (!::root.isInitialized || isFinishing || isDestroyed) return
        if (shortcut.command != OmarchyCommand.MENU) workspaceMenu?.dismiss()
        if (shortcut.command != OmarchyCommand.MENU && shortcut.command != OmarchyCommand.APPS && launcherActive) hideLauncher()
        val task = WorkspaceState.tasks.firstOrNull { it.id == WorkspaceState.focusedTaskId }
        val geometry = WorkspaceState.windowBounds(workspaceScroll.width.coerceAtLeast(1), workspaceScroll.height.coerceAtLeast(1), dp(10), dp(10))
        when (shortcut.command) {
            OmarchyCommand.MENU -> if (workspaceMenu?.isShowing == true) workspaceMenu?.dismiss() else showWorkspaceMenu()
            OmarchyCommand.APPS -> showLauncher()
            OmarchyCommand.TERMINAL -> launchRoleApp("terminal")
            OmarchyCommand.BROWSER -> launchRoleApp("browser")
            OmarchyCommand.FILES -> launchRoleApp("files")
            OmarchyCommand.CLOSE -> task?.let(::closeTask)
            OmarchyCommand.CLOSE_ALL -> confirmCloseAllTasks()
            OmarchyCommand.FULLSCREEN -> task?.let { WorkspaceState.setMode(it.id, if (it.mode == PresentationMode.FULLSCREEN) it.fullscreenReturnMode ?: PresentationMode.TABLET else PresentationMode.FULLSCREEN) }
            OmarchyCommand.FLOAT -> task?.let { WorkspaceState.toggleFloating(it.id) }
            OmarchyCommand.PIN -> task?.let { WorkspaceState.toggleFloating(it.id, pin = true) }
            OmarchyCommand.LAYOUT -> WorkspaceState.toggleLayout()
            OmarchyCommand.SPLIT -> WorkspaceState.toggleSplit()
            OmarchyCommand.FOCUS, OmarchyCommand.SWAP -> shortcut.direction?.let { direction ->
                if (WorkspaceState.focusDirectional(direction, geometry, shortcut.command == OmarchyCommand.SWAP)) {
                    desktopFocused = false
                    if (shortcut.command == OmarchyCommand.FOCUS) warpCursorToFocusedWindow()
                }
            }
            OmarchyCommand.WORKSPACE -> { hideLauncher(); WorkspaceState.switchWorkspace(shortcut.argument) }
            OmarchyCommand.MOVE_WORKSPACE -> task?.let { WorkspaceState.moveToWorkspace(it.id, shortcut.argument, follow = !shortcut.silent) }
            OmarchyCommand.CYCLE_WORKSPACE -> WorkspaceState.switchWorkspace(((WorkspaceState.activeWorkspace - 1 + shortcut.argument + 10) % 10) + 1)
            OmarchyCommand.PREVIOUS_WORKSPACE -> WorkspaceState.switchWorkspace(WorkspaceState.previousWorkspace)
            OmarchyCommand.CYCLE_WINDOW -> {
                val visible = WorkspaceState.visibleTasks()
                if (visible.isNotEmpty()) focusTask(visible[(visible.indexOfFirst { it.id == task?.id } + shortcut.argument + visible.size) % visible.size])
            }
            OmarchyCommand.SCRATCHPAD -> WorkspaceState.toggleScratchpad()
            OmarchyCommand.MOVE_SCRATCHPAD -> task?.let { WorkspaceState.moveToWorkspace(it.id, 0, follow = false) }
            OmarchyCommand.RESIZE -> WorkspaceState.resizeFocused(shortcut.horizontal, shortcut.argument.toFloat() /
                (if (shortcut.horizontal) workspaceScroll.width else workspaceScroll.height).coerceAtLeast(1))
            OmarchyCommand.GROUP -> task?.let { WorkspaceState.toggleGroup(it.id) }
            OmarchyCommand.UNGROUP -> task?.let { WorkspaceState.toggleGroup(it.id, detach = true) }
            OmarchyCommand.GROUP_FOCUS -> WorkspaceState.focusGroup(if (shortcut.silent) 0 else shortcut.argument, if (shortcut.silent) shortcut.argument else null)
            OmarchyCommand.GROUP_JOIN -> shortcut.direction?.let { WorkspaceState.joinDirectionalGroup(it, geometry) }
            OmarchyCommand.MAXIMIZE -> task?.let { WorkspaceState.toggleMaximized(it.id) }
            OmarchyCommand.PSEUDO -> task?.let { WorkspaceState.togglePseudo(it.id) }
        }
    }

    private fun warpCursorToFocusedWindow() {
        root.post {
            val card = cards[WorkspaceState.focusedTaskId] ?: return@post
            val bounds = Rect()
            if (card.getGlobalVisibleRect(bounds)) WorkspaceCursor.moveTo(bounds.exactCenterX(), bounds.exactCenterY(), root.width.toFloat(), root.height.toFloat())
        }
    }

    private fun visibleCardsTopFirst(): List<BeastTaskCard> {
        if (!::taskStrip.isInitialized) return emptyList()
        val parent = if (::fullscreenLayer.isInitialized && fullscreenLayer.visibility == View.VISIBLE) fullscreenLayer else taskStrip
        return (0 until parent.childCount).map(parent::getChildAt).sortedBy { it.z }.asReversed().filterIsInstance<BeastTaskCard>()
    }

    private fun handleWindowDrag(x: Int, y: Int, action: Int, button: Int): Boolean {
        if (!::workspaceScroll.isInitialized || launcherActive || TransientPanelCoordinator.isActiveOnDisplay(display?.displayId ?: -1)) return false
        val viewport = Rect()
        if (!workspaceScroll.getGlobalVisibleRect(viewport) || viewport.width() <= 0 || viewport.height() <= 0) return false
        if (action == MotionEvent.ACTION_DOWN && (OmarchyShortcuts.superHeld ||
                visibleCardsTopFirst().firstOrNull { candidate -> val bounds = Rect(); candidate.getGlobalVisibleRect(bounds) && bounds.contains(x, y) }?.isTitleHit(x, y) == true)) {
            val card = visibleCardsTopFirst().firstOrNull { candidate -> val rect = Rect(); candidate.getGlobalVisibleRect(rect) && rect.contains(x, y) } ?: return false
            val task = cards.entries.firstOrNull { it.value === card }?.key ?: return false
            val rect = Rect(); card.getGlobalVisibleRect(rect)
            val box = WindowBounds(rect.left - viewport.left, rect.top - viewport.top, rect.width(), rect.height())
            val info = WorkspaceState.tasks.first { it.id == task }
            val resize = button == MotionEvent.BUTTON_SECONDARY
            val floating = info.floating || info.pinned || info.workspace == 0
            val targets = if (!floating && !resize) visibleCardsTopFirst().mapNotNull { candidate ->
                val id = cards.entries.firstOrNull { it.value === candidate }?.key ?: return@mapNotNull null
                val other = WorkspaceState.tasks.firstOrNull { it.id == id } ?: return@mapNotNull null
                if (id == task || other.floating || other.pinned || other.workspace == 0) return@mapNotNull null
                val bounds = Rect()
                if (candidate.getGlobalVisibleRect(bounds)) id to bounds else null
            }.toMap() else emptyMap()
            windowDrag = WindowDrag(task, resize, x, y, box, floating,
                if (!floating && !resize) WorkspaceState.savedTileLayout() else null, targets)
            WorkspaceState.focus(task)
            return true
        }
        val drag = windowDrag ?: return false
        if (action == MotionEvent.ACTION_MOVE) {
            val dx = x - drag.x; val dy = y - drag.y; val box = drag.bounds
            if (drag.resize && !drag.wasFloating) {
                WorkspaceState.resizeFocused(true, dx.toFloat() / viewport.width(), persist = false)
                WorkspaceState.resizeFocused(false, dy.toFloat() / viewport.height(), persist = false)
                windowDrag = drag.copy(x = x, y = y)
            } else if (drag.wasFloating || drag.detached || kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(8)) {
                if (!drag.wasFloating && !drag.detached) windowDrag = drag.copy(detached = true)
                WorkspaceState.floatingBounds(drag.id,
                    (box.x + if (drag.resize) 0 else dx).toFloat() / viewport.width(),
                    (box.y + if (drag.resize) 0 else dy).toFloat() / viewport.height(),
                    (box.width + if (drag.resize) dx else 0).toFloat() / viewport.width(),
                    (box.height + if (drag.resize) dy else 0).toFloat() / viewport.height(), persist = false)
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            windowDrag = null
            if (!drag.wasFloating && !drag.resize) {
                val target = if (drag.detached && action == MotionEvent.ACTION_UP)
                    drag.tileTargets.entries.firstOrNull { it.value.contains(x, y) }?.key else null
                if (!drag.detached || action == MotionEvent.ACTION_CANCEL || target != null)
                    WorkspaceState.finishTileDrag(drag.id, target, drag.savedLayout)
                else WorkspaceState.commitGeometry()
            } else WorkspaceState.commitGeometry()
        }
        return true
    }

    private fun launchRoleApp(role: String) {
        val installed = loadApps()
        val resolved = if (role == "browser") packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")), 0)?.activityInfo?.packageName else null
        val app = installed.firstOrNull { info ->
            val name = info.activityInfo.packageName
            when (role) {
                "terminal" -> name == "com.termux" || name.contains("terminal")
                "browser" -> name == resolved
                else -> name == "com.google.android.documentsui" || name == "com.android.documentsui"
            }
        }
        if (app != null) launchFromLauncher(app)
        else {
            android.widget.Toast.makeText(this, "Choose an installed $role app", android.widget.Toast.LENGTH_SHORT).show()
            showLauncher()
        }
    }

    private fun showWorkspaceMenu() {
        hideLauncher()
        val commands = buildList {
            add(WorkspaceCommand("APPLICATIONS", "Launch an Android app · Super+A", run = ::showLauncher))
            add(WorkspaceCommand("TERMINAL", "Open an installed terminal · Super+Return") { launchRoleApp("terminal") })
            add(WorkspaceCommand("BROWSER", "Open your Android browser · Super+Shift+Return") { launchRoleApp("browser") })
            add(WorkspaceCommand("WORKSPACE LAYOUT", "${WorkspaceState.layout().name.lowercase()} · Super+L") { WorkspaceState.toggleLayout() })
            add(WorkspaceCommand("WINDOWS", "Find a window on any workspace", run = ::showWindowSwitcher))
            add(WorkspaceCommand("WINDOW CONTROLS", "Floating, fullscreen, grouping and workspace moves", WorkspaceState.focusedTaskId != null) { cards[WorkspaceState.focusedTaskId]?.onWindowMenu?.invoke() })
            add(WorkspaceCommand("SCRATCHPAD", "Show or hide the scratchpad · Super+S") { WorkspaceState.toggleScratchpad() })
            if (omaDesktop != null) {
                add(WorkspaceCommand("WIDGETS", "Add widgets from Omalauncher") { setLauncherActive(true); omaDesktop?.widgets() })
                add(WorkspaceCommand("THEMES", if (sharedDesktop) "Change the theme in Omalauncher on the phone" else "One palette for your home and workspace", enabled = !sharedDesktop) { setLauncherActive(true); omaDesktop?.themes() })
            }
            add(WorkspaceCommand("SHOW ALL", "Restore windows in their existing order", WorkspaceState.tasks.isNotEmpty()) { showAllTasks() })
            add(WorkspaceCommand("MINIMIZE ALL", "Reveal your home without closing apps", WorkspaceState.tasks.isNotEmpty(), run = ::showOmaDesktop))
            add(WorkspaceCommand("CAPTURE", "Save the external workspace · protected content excluded", run = ::captureFullDisplay))
            add(WorkspaceCommand("DISPLAY", "Resolution, scale and external display mode", run = ::showExternalDisplaySettings))
            add(WorkspaceCommand("SETTINGS", "Input, shortcuts and personalization", run = ::showInputSettings))
            add(WorkspaceCommand(if (sharedDesktop) "OMA: SHARED" else "OMA: LOCAL", "Omalauncher connection details", run = ::showCompanionStatus))
            add(WorkspaceCommand("CLOSE ALL…", "Close Android tasks · confirmation required", WorkspaceState.tasks.isNotEmpty(), true) { confirmCloseAllTasks() })
        }
        showCommands("OMARCHY", "${InputSettings.theme.name} · workspace ${WorkspaceState.activeWorkspace} · Super+Space", commands)
    }

    private fun showCommands(title: String, subtitle: String, commands: List<WorkspaceCommand>) {
        workspaceMenu?.dismiss()
        workspaceMenu = WorkspaceCommandPanel.show(this, title, subtitle, commands, uiElementScale,
            ::attachModalCursorOverlay) { dialog -> detachModalCursorOverlay(dialog); if (workspaceMenu === dialog) workspaceMenu = null }
    }

    private fun confirmCloseAllTasks() = showCommands("CLOSE ALL WINDOWS?", "This removes tasks from Android Overview. Unsaved work may be lost.", listOf(
        WorkspaceCommand("CANCEL", "Keep everything open") {},
        WorkspaceCommand("CLOSE ALL WINDOWS", "Minimize instead if you only want to see home", destructive = true, run = ::closeAllTasks),
    ))

    private fun systemStatusText() = SimpleDateFormat("EEEE HH:mm", Locale.getDefault()).format(Date())

    private fun systemIndicatorsText(): String {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra("level", -1) ?: -1
        val scale = battery?.getIntExtra("scale", 100)?.coerceAtLeast(1) ?: 100
        val percent = if (level >= 0) ((level * 100f) / scale).roundToInt().coerceIn(0, 100) else null
        val network = runCatching {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            connectivity
                ?.getNetworkCapabilities(connectivity.activeNetwork)
                ?.let { capabilities ->
                    when {
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELL"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETH"
                        else -> "OFF"
                    }
                } ?: "OFF"
        }.getOrDefault("OFF")
        return "$network · ${percent?.let { "$it%" } ?: "--%"}"
    }

    private fun alignmentGravity(alignment: BarAlignment) = when (alignment) {
        BarAlignment.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
        BarAlignment.CENTER -> Gravity.CENTER
        BarAlignment.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
    }

    private fun syncViewportMap() {
        viewportMap.viewportWidthPx = workspaceScroll.width
        viewportMap.contentWidthPx = taskStrip.width.coerceAtLeast(workspaceScroll.width)
        viewportMap.scrollOffsetPx = workspaceScroll.scrollX
    }

    /** Fallback for the rare case Android initially places the workspace on the phone. */
    private fun moveToExternalDisplay() {
        val target = findExternalDisplay(this) ?: return
        if (display?.displayId == target.displayId) return
        BeastWorkspaceController.ensure(this, target)
    }

    /** Keep this Activity off the phone if a display-mode transition re-parents it. */
    private fun keepOnExternalDisplay() {
        if (isFinishing) return
        if (phoneViewSession) { phoneViewListener(); return }
        val target = findExternalDisplay(this)
        val currentId = display?.displayId
        when {
            target != null && currentId != target.displayId -> moveToExternalDisplay()
            target == null && currentId == Display.DEFAULT_DISPLAY -> finish()
        }
    }

    private fun showInputSettings() {
        hideLauncher()
        InputSettingsPanel.show(
            context = this,
            onModalVisibilityChanged = { visible -> cursorOverlay.visibility = if (phoneViewSession) View.GONE else if (visible) View.INVISIBLE else View.VISIBLE },
            onDialogWindowCreated = ::attachModalCursorOverlay,
            onDialogWindowDismissed = ::detachModalCursorOverlay,
        )
    }

    private fun showExternalDisplaySettings() {
        hideLauncher()
        HostedTextInputSession.close()
        ExternalDisplayPanel.show(
            context = this,
            displayProvider = { findExternalDisplay(this) },
            requestUltrawide = { enabled, complete ->
                val externalDisplay = findExternalDisplay(this)
                WorkspaceState.beginDisplayModeSwitch(enabled)
                val refreshRate = InputSettings.preferredBeastTiming?.refreshRate
                    ?: externalDisplay?.mode?.refreshRate?.roundToInt()?.takeIf { it > 0 }
                    ?: 60
                val height = if (enabled) 1200 else {
                    externalDisplay?.mode?.physicalHeight?.takeIf { it == 1080 || it == 1200 }
                        ?: 1200
                }
                val timing = InputSettings.BeastTiming(
                    if (enabled) 3840 else 1920,
                    height,
                    refreshRate,
                )
                beastDisplayModeController.setNativeTiming(
                    timing.width,
                    timing.height,
                    timing.refreshRate,
                ) { result ->
                    result.onSuccess {
                        InputSettings.setPreferredBeastTiming(applicationContext, timing)
                    }.onFailure {
                        WorkspaceState.cancelDisplayModeSwitch()
                    }
                    complete(result.map { enabled })
                }
            },
            requestDisplayMode = { display, mode, complete ->
                WorkspaceState.beginDisplayModeSwitch(mode.physicalWidth >= 3000)
                PrivilegedService.setUserPreferredDisplayMode(
                    display.displayId,
                    mode.physicalWidth,
                    mode.physicalHeight,
                    mode.refreshRate,
                ) { applied ->
                    if (applied) {
                        if (beastDisplayModeController.isBeastConnected()) {
                            InputSettings.setPreferredBeastTiming(
                                applicationContext,
                                InputSettings.BeastTiming(
                                    mode.physicalWidth,
                                    mode.physicalHeight,
                                    mode.refreshRate.roundToInt(),
                                ),
                            )
                        }
                        complete(Result.success(Unit))
                    } else {
                        WorkspaceState.cancelDisplayModeSwitch()
                        complete(Result.failure(IllegalStateException("Display mode was not accepted")))
                    }
                }
            },
            isBeastDisplay = {
                BuildConfig.VITURE_SDK_ENABLED && beastDisplayModeController.isBeastConnected()
            },
            requestBeastNativeTiming = { width, height, refreshRate, complete ->
                WorkspaceState.beginDisplayModeSwitch(width >= 3000)
                beastDisplayModeController.setNativeTiming(width, height, refreshRate) { result ->
                    result.onSuccess {
                        InputSettings.setPreferredBeastTiming(
                            applicationContext,
                            InputSettings.BeastTiming(width, height, refreshRate),
                        )
                    }.onFailure {
                        WorkspaceState.cancelDisplayModeSwitch()
                    }
                    complete(result)
                }
            },
            vitureSdkEnabled = BuildConfig.VITURE_SDK_ENABLED,
            onModalVisibilityChanged = { visible -> cursorOverlay.visibility = if (phoneViewSession) View.GONE else if (visible) View.INVISIBLE else View.VISIBLE },
            onDialogWindowCreated = ::attachModalCursorOverlay,
            onDialogWindowDismissed = ::detachModalCursorOverlay,
        )
    }

    /** Android dialogs are separate windows; mirror the workspace cursor into each one. */
    private fun attachModalCursorOverlay(dialog: Dialog) {
        if (phoneViewSession) return
        val decor = dialog.window?.decorView as? ViewGroup ?: return
        detachModalCursorOverlay(dialog)
        val overlay = BeastCursorOverlay(
            context = this,
            displayCoordinates = true,
            displayIdProvider = { display?.displayId ?: Display.DEFAULT_DISPLAY },
        )
        decor.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        modalCursorOverlays[dialog] = overlay
        overlay.postInvalidateOnAnimation()
    }

    private fun detachModalCursorOverlay(dialog: Dialog) {
        modalCursorOverlays.remove(dialog)?.let { overlay ->
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
    }

    /** Keep the rendered pointer above every dynamically rebuilt BeastUI layer. */
    private fun keepCursorOnTop() {
        if (phoneViewSession) {
            if (::cursorOverlay.isInitialized) cursorOverlay.visibility = View.GONE
            return
        }
        if (::cursorOverlay.isInitialized && cursorOverlay.parent === aspectLayer &&
            aspectLayer.indexOfChild(cursorOverlay) != aspectLayer.childCount - 1) {
            cursorOverlay.bringToFront()
        }
    }

    private fun scrollWorkspace(wheel: Float) {
        if (omaDesktop != null && launcherActive) {
            val position = WorkspaceCursor.position()
            routeAppScroll(display?.displayId ?: -1,
                (position.xFraction * root.width).toInt(),
                (position.yFraction * root.height).toInt(), wheel)
            return
        }
        val targetsLauncher = launcherActive
        val direction = if (InputSettings.invertScrollbarScroll) -1f else 1f
        val distance = if (targetsLauncher) {
            direction * wheel * dp(96)
        } else {
            // The desktop's visible viewport is a horizontal task strip; use a
            // vertical wheel/PhoneUI edge-scroll gesture to pan it.
            direction * wheel * workspaceScroll.width * .32f
        }
        val now = SystemClock.uptimeMillis()
        val elapsed = (now - lastScrollAt).coerceIn(1L, 120L)
        val sampleVelocity = distance * 1000f / elapsed
        momentumVelocity = if (now - lastScrollAt > 160L) {
            sampleVelocity
        } else {
            momentumVelocity * .55f + sampleVelocity * .45f
        }
        lastScrollAt = now
        momentumTargetsLauncher = targetsLauncher
        scrollMomentum.forceFinished(true)
        root.removeCallbacks(momentumKick)

        if (targetsLauncher) {
            launcherScroll.scrollBy(0, distance.toInt())
        } else {
            workspaceScroll.scrollBy(distance.toInt(), 0)
        }
        if (InputSettings.momentumEnabled) {
            root.postDelayed(momentumKick, MOMENTUM_DELAY_MS)
        }
    }

    private fun startMomentum() {
        if (!InputSettings.momentumEnabled) return
        if (kotlin.math.abs(momentumVelocity) < MIN_FLING_VELOCITY) return
        if (momentumTargetsLauncher) {
            val maxY = ((launcherScroll.getChildAt(0)?.height ?: 0) - launcherScroll.height).coerceAtLeast(0)
            scrollMomentum.fling(0, launcherScroll.scrollY, 0, momentumVelocity.toInt(), 0, 0, 0, maxY)
        } else {
            val maxX = (taskStrip.width - workspaceScroll.width).coerceAtLeast(0)
            scrollMomentum.fling(workspaceScroll.scrollX, 0, momentumVelocity.toInt(), 0, 0, maxX, 0, 0)
        }
        root.postOnAnimation(momentumFrame)
    }

    private fun buildLauncher(): FrameLayout {
        val overlay = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.argb(112, 7, 8, 9))
            isClickable = true
            setBeastClick { hideLauncher() }
        }
        launcherPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(uiDp(28), uiDp(22), uiDp(28), uiDp(18))
            isClickable = true
        }
        applyLauncherBorder()
        launcherQuery = EditText(this).apply {
            hint = "> _"
            isSingleLine = true
            typeface = Typeface.MONOSPACE
            textSize = 18f
            setTextColor(InputSettings.theme.brightForeground)
            setHintTextColor(InputSettings.theme.muted)
            setBackgroundColor(Color.TRANSPARENT)
            showSoftInputOnFocus = false
            addTextChangedListener(SimpleTextWatcher { value ->
                populateLauncher(value)
                LauncherSearchSession.updateQuery(value)
            })
            setOnEditorActionListener { _, _, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (enter || event == null) {
                    filteredApps.firstOrNull()?.let(::launchFromLauncher)
                    true
                } else false
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val close = label("× close", 11f, ACCENT).apply {
            gravity = Gravity.CENTER
            setPadding(uiDp(10), 0, uiDp(2), 0)
            setBeastClick(::hideLauncher)
        }
        launcherResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        launcherScroll = ScrollView(this).apply { addView(launcherResults) }
        header.addView(launcherQuery, LinearLayout.LayoutParams(0, uiDp(52), 1f))
        header.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, uiDp(52)))
        launcherPanel.addView(header)
        launcherPanel.addView(buildLauncherSortBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, uiDp(38)))
        launcherPanel.addView(launcherScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        launcherPanel.addView(label("esc:close", 10f, InputSettings.theme.muted).apply { gravity = Gravity.END })
        overlay.addView(launcherPanel, FrameLayout.LayoutParams(
            uiDp(420),
            uiDp(520),
            Gravity.CENTER,
        ))
        return overlay
    }

    private fun buildLauncherSortBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        LauncherSort.entries.forEach { option ->
            addView(label(option.title, 11f, if (launcherPreferences.sort == option) ACCENT else InputSettings.theme.muted).apply {
                gravity = Gravity.CENTER
                setPadding(uiDp(12), 0, uiDp(12), 0)
                setBeastClick {
                    launcherPreferences.sort = option
                    // Rebuild the launcher so the persisted active indicator and ordering agree.
                    val parent = launcher.parent as? ViewGroup
                    val index = parent?.indexOfChild(launcher) ?: -1
                    parent?.removeView(launcher)
                    launcher = buildLauncher()
                    if (parent != null && index >= 0) parent.addView(
                        launcher, index,
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                    )
                    showLauncher()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun showLauncher() {
        showLegacyLauncher()
        setLauncherActive(true)
        root.post { if (launcherActive) omaDesktop?.search(LauncherSearchSession.query, false) }
    }

    private fun showOmaDesktop() {
        hideLauncher()
        WorkspaceState.minimizeAll()
        desktopFocused = true
        omaDesktop?.view?.requestFocus()
        hideSystemKeyboard()
    }

    private fun showLegacyLauncher() {
        // Repeated intents and appearance rebuilds must not close our own search session.
        launcherLease?.release()
        launcherLease = null
        if (!LauncherSearchSession.active && !phoneViewSession) {
            launcherSubmitSequence = LauncherSearchSession.submitSequence
            LauncherSearchSession.begin()
            // REDMAGIC otherwise inherits this external display for MainActivity's
            // singleTask delivery, which relocates PhoneUI onto Beast and hides us.
            MainActivity.restorePhoneUiAfterExternalTaskBatch()
            runCatching {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_BEAST_LAUNCHER_SEARCH
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }, ActivityOptions.makeBasic().apply {
                    launchDisplayId = Display.DEFAULT_DISPLAY
                }.toBundle())
                // Starting MainActivity from the Beast display can expose Home on
                // display 0 after the shell move. Repair after the launch has had a
                // traversal to settle, and retry through the OEM transition window.
                schedulePhoneUiFocusRepair()
            }
        }
        lateinit var lease: TransientPanelCoordinator.Lease
        lease = TransientPanelCoordinator.claim(
            display?.displayId ?: Display.DEFAULT_DISPLAY,
            TransientPanelCoordinator.Kind.APPS,
        ) {
            if (::launcher.isInitialized) {
                omaDesktop?.dismissPanels()
                setLauncherActive(false)
                LauncherSearchSession.close()
                if (::root.isInitialized) root.requestFocus()
                hideSystemKeyboard()
            }
            if (launcherLease === lease) launcherLease = null
        }
        launcherLease = lease
        setLauncherActive(true)
        if (omaDesktop == null) {
            launcherQuery.setText(LauncherSearchSession.query)
            populateLauncher(LauncherSearchSession.query)
            launcherPanel.post(::fitLauncherPopup)
            launcherQuery.requestFocus()
            launcherQuery.post(::hideSystemKeyboard)
        } else {
            omaDesktop?.view?.requestFocus()
            hideSystemKeyboard()
        }
        // Refresh Android's Overview ordering each time the launcher opens. Once the
        // workspace is seeded this updates sorting only; it never rearranges its cards.
        if (PrivilegedService.state == PrivilegedService.State.READY) syncAndroidTasks()
    }

    private fun hideLauncher() {
        desktopLongPress.cancel()
        secondaryCancelled = true
        omaDesktop?.dismissPanels()
        setLauncherActive(false)
        desktopFocused = cards.values.none { it.isShown }
        LauncherSearchSession.close()
        launcherLease?.release()
        launcherLease = null
        root.requestFocus()
        hideSystemKeyboard()
    }

    private fun schedulePhoneUiFocusRepair() {
        if (phoneViewSession) return
        root.postDelayed({ MainActivity.restorePhoneUiAfterExternalTaskBatch() }, 220L)
    }

    private fun restoreWorkspaceFocus() {
        hideLauncher()
        renderWorkspace()
        root.post {
            cards[WorkspaceState.focusedTaskId]?.restoreFocus()
            hideSystemKeyboard()
        }
    }

    private fun hideSystemKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun populateLauncher(filter: String) {
        launcherResults.removeAllViews()
        filteredApps = orderedApps().asSequence()
            .filter { it.loadLabel(packageManager).toString().contains(filter, ignoreCase = true) }
            .toList()
        filteredApps.forEach { info -> launcherResults.addView(appLauncherRow(info)) }
        if (::launcherPanel.isInitialized) launcherPanel.post(::fitLauncherPopup)
    }

    /** Fit to the currently visible launcher labels, with safe display-relative caps. */
    private fun fitLauncherPopup() {
        if (!::launcherPanel.isInitialized || root.width == 0 || root.height == 0) return
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 15f * resources.displayMetrics.scaledDensity * uiFontScale
        }
        val widestLabel = filteredApps.maxOfOrNull {
            paint.measureText(it.loadLabel(packageManager).toString().lowercase())
        } ?: 0f
        val desiredWidth = (widestLabel + uiDp(150)).toInt()
        val maxWidth = minOf(root.width - uiDp(48), uiDp(760))
        val width = popupDimension(desiredWidth, uiDp(300), maxWidth)
        val desiredHeight = uiDp(120) + launcherResults.measuredHeight
        val maxHeight = minOf((root.height * .78f).toInt(), uiDp(760))
        val height = popupDimension(desiredHeight, uiDp(180), maxHeight)
        launcherPanel.layoutParams = (launcherPanel.layoutParams as FrameLayout.LayoutParams).apply {
            this.width = width
            this.height = height
            gravity = Gravity.CENTER
        }
    }

    private fun appLauncherRow(info: ResolveInfo): View {
        val packageName = info.activityInfo.packageName
        val openTasks = WorkspaceState.tasks
            .filter { it.packageName == packageName }
            .sortedBy(BeastTask::overviewRank)
        val distinctTitles = openTasks.map { it.instanceTitle.trim() }
        val useTitles = openTasks.size > 1 && distinctTitles.all(String::isNotBlank) &&
            distinctTitles.distinctBy(String::lowercase).size == openTasks.size
        val rowHeight = maxOf(uiDp(44), uiDp(30) * openTasks.size)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
                        addView(label(info.loadLabel(packageManager).toString().lowercase(), 15f, InputSettings.theme.foreground).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(uiDp(8), 0, uiDp(8), 0)
                setBeastClick { launchFromLauncher(info) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            if (openTasks.isNotEmpty()) {
                addView(LinearLayout(this@BeastActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    openTasks.forEachIndexed { index, task ->
                        val designation = when {
                            openTasks.size == 1 -> ""
                            useTitles -> " · ${task.instanceTitle.trim().take(32)}"
                            else -> " #${index + 1}"
                        }
                        addView(label("● open$designation", 11f, ACCENT).apply {
                            gravity = Gravity.CENTER_VERTICAL or Gravity.END
                            setPadding(uiDp(12), 0, uiDp(8), 0)
                            setBeastClick {
                            launcherPreferences.recordUse(packageName)
                            WorkspaceState.focus(task.id, reveal = true)
                            hideLauncher()
                        }
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, uiDp(30)))
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, rowHeight))
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight) }
    }

    private fun orderedApps(): List<ResolveInfo> {
        fun label(info: ResolveInfo) = info.loadLabel(packageManager).toString().lowercase()
        return when (launcherPreferences.sort) {
            LauncherSort.ALPHABETICAL -> apps.sortedBy(::label)
            LauncherSort.RECENTLY_USED -> apps.sortedWith(
                compareBy<ResolveInfo> { recentPackageRank[it.activityInfo.packageName] ?: Int.MAX_VALUE }
                    .thenByDescending { launcherPreferences.lastUsed(it.activityInfo.packageName) }
                    .thenBy(::label),
            )
            LauncherSort.MOST_USED -> apps.sortedWith(
                compareByDescending<ResolveInfo> { launcherPreferences.useCount(it.activityInfo.packageName) }
                    .thenByDescending { launcherPreferences.lastUsed(it.activityInfo.packageName) }
                    .thenBy(::label),
            )
        }
    }

    private fun launchFromLauncher(info: ResolveInfo) {
        launcherPreferences.recordUse(info.activityInfo.packageName)
        WorkspaceState.launchNew(
            info.activityInfo.packageName,
            info.activityInfo.name,
            info.loadLabel(packageManager).toString(),
        )
        hideLauncher()
    }

    private fun focusTask(task: BeastTask) {
        if (launcherActive) hideLauncher()
        desktopFocused = false
        // Input delivered inside the already-focused hosted task (pinch, scroll, Chrome
        // menu controls, and contextual actions) must not re-run a cross-display task
        // focus transaction. That transaction can briefly front the task on Android and
        // steal PhoneUI focus even though the user never changed apps.
        if (WorkspaceState.focusedTaskId == task.id && task.placement == TaskPlacement.BEAST_VISIBLE) {
            return
        }
        launcherPreferences.recordUse(task.packageName)
        WorkspaceState.focus(task.id, reveal = true)
    }

    /** Closing means terminate the Android task; minimizing is the non-destructive action. */
    private fun closeTask(task: BeastTask) {
        AndroidTaskSynchronizer.closeTask(task) { removed ->
            if (!removed) {
                android.widget.Toast.makeText(
                    this, "Could not remove ${task.label} from Android Overview",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun closeAllTasks() {
        WorkspaceState.closeAllCandidates().forEach { task ->
            AndroidTaskSynchronizer.closeTask(task) { }
        }
    }

    private fun showAllTasks() {
        root.removeCallbacks(showAllTimeout)
        pendingShowAllTaskIds.clear()
        WorkspaceState.tasks.asSequence()
            .filter { it.placement == TaskPlacement.PHONE && it.supportsMultiWindow }
            .mapTo(pendingShowAllTaskIds, BeastTask::id)
        WorkspaceState.showAll()
        if (pendingShowAllTaskIds.isNotEmpty()) {
            root.postDelayed(showAllTimeout, SHOW_ALL_BATCH_TIMEOUT_MS)
        }
    }

    private fun onShowAllTaskHosted(taskId: Long) {
        if (!pendingShowAllTaskIds.remove(taskId) || pendingShowAllTaskIds.isNotEmpty()) return
        root.removeCallbacks(showAllTimeout)
        finishShowAllBatch()
    }

    private fun finishShowAllBatch() {
        pendingShowAllTaskIds.clear()
        MainActivity.restorePhoneUiAfterExternalTaskBatch()
    }

    private fun captureFullDisplay() {
        requestScreenshot(BeastScreenshotController.ScreenshotTarget.FULL_DISPLAY)
    }

    private fun requestScreenshot(target: BeastScreenshotController.ScreenshotTarget) {
        if (!screenshotController.request(target)) {
            android.widget.Toast.makeText(this, "Capture already in progress", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun onScreenshotResult(result: BeastScreenshotController.Result) {
        when (result) {
            is BeastScreenshotController.Result.Saved -> {
                val message = if (result.galleryVisible) "Screenshot saved to Gallery" else "Screenshot saved"
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
            }
            is BeastScreenshotController.Result.Failed -> {
                android.widget.Toast.makeText(this, result.message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun syncAndroidTasks() {
        val recent = PrivilegedService.recentTasks()
        recentPackageRank = recent.map { it.packageName }.distinct().withIndex()
            .associate { it.value to it.index }
        AndroidTaskSynchronizer.requestImmediate()
        if (launcherActive && omaDesktop == null) populateLauncher(launcherQuery.text.toString())
    }

    private fun loadApps(): List<ResolveInfo> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size * uiFontScale
        typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(color)
    }

    private fun applyLauncherBorder() {
        if (!::launcherPanel.isInitialized) return
        launcherPanel.background = omarchyWindowBorder(
            focused = true,
            borderPx = dp(OMARCHY_WINDOW_BORDER_DP).coerceAtLeast(1),
        )
    }

    /** Task surfaces retain native dimensions; only LATERAL_ chrome uses uiDp(). */
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun uiDp(value: Int) = (dp(value) * uiElementScale).toInt()

    private fun View.setBeastClick(action: () -> Unit) = setBeastClick((this as? TextView)?.text?.toString() ?: "view:${System.identityHashCode(this)}", action)

    private fun View.setBeastClick(key: String, action: () -> Unit) {
        shellControlKeys[this] = key
        setOnClickListener { controlDebouncer.submit(action) }
        (this as? TextView)?.installBeastHover()
    }

}

/** Non-interactive, workspace-owned cursor that remains visible on the glasses. */
internal class BeastCursorOverlay(
    context: android.content.Context,
    private val displayCoordinates: Boolean = false,
    private val displayIdProvider: () -> Int = { Display.DEFAULT_DISPLAY },
) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(244, 243, 238)
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(22, 25, 26)
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 1.35f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val pointer = Path()

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val state = WorkspaceCursor.position()
        if (!state.visible || width == 0 || height == 0) return
        val locationOnScreen = IntArray(2)
        val viewport = WorkspaceCursor.viewport(displayIdProvider())
        val x: Float
        val y: Float
        if (displayCoordinates && viewport != null) {
            // The pointer fractions are display-space coordinates. A dialog's
            // decor has its own window origin, so translate into that origin.
            getLocationOnScreen(locationOnScreen)
            x = state.xFraction * viewport.width - locationOnScreen[0]
            y = state.yFraction * viewport.height - locationOnScreen[1]
        } else {
            x = state.xFraction * width
            y = state.yFraction * height
        }
        // Neutral, compact workstation pointer. Keep the hotspot at the exact
        // tip and draw from normalized geometry so the silhouette stays clean
        // at every display density without a bitmap halo.
        val size = resources.displayMetrics.density * 16f
        pointer.reset()
        pointer.moveTo(x, y)
        pointer.lineTo(x + size * .04f, y + size * .94f)
        pointer.lineTo(x + size * .30f, y + size * .69f)
        pointer.lineTo(x + size * .49f, y + size * 1.06f)
        pointer.lineTo(x + size * .66f, y + size * .97f)
        pointer.lineTo(x + size * .46f, y + size * .61f)
        pointer.lineTo(x + size * .82f, y + size * .61f)
        pointer.close()
        canvas.drawPath(pointer, fill)
        canvas.drawPath(pointer, stroke)
    }
}

internal class BeastTaskCard(
    context: android.content.Context,
    private val controlDebouncer: BeastControlDebouncer,
) : FrameLayout(context) {
    var onFocus: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null
    var onMove: ((Int) -> Unit)? = null
    var onMode: (() -> Unit)? = null
    var onFullscreen: (() -> Unit)? = null
    var onMinimize: (() -> Unit)? = null
    var onCaptureApp: (() -> Unit)? = null
    var onWindowMenu: (() -> Unit)? = null
    var onDisplayReady: ((Int) -> Unit)? = null
    var onTaskHosted: (() -> Unit)? = null
    var onRestoreFailed: ((PrivilegedService.FocusRestoreResult) -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private val column = LinearLayout(context)
    private val chrome = FrameLayout(context)
    private val groupBar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
    private var groupRenderKey: String? = null
    private val appLabel = TextView(context)
    private val modeButton = TextView(context)
    private val leftControls = LinearLayout(context)
    private val noteNavigation = LinearLayout(context)
    private val rightControls = LinearLayout(context)
    private lateinit var backButton: TextView
    private lateinit var windowMenuButton: TextView
    private val surface = TaskSurfaceView(context)
    private val fullscreenExit = TextView(context)
    private val controls = mutableListOf<TextView>()
    private val controlBaseWidths = mutableMapOf<TextView, Int>()
    private var decoratorScale = 1f
    private var boundTask: BeastTask? = null
    private var focused = false
    private var fullscreen = false
    var omarchyChrome = false
        set(value) {
            if (field == value) return
            field = value
            chrome.visibility = if (fullscreen) View.GONE else View.VISIBLE
            modeButton.visibility = if (value) View.GONE else View.VISIBLE
            applyUiScale()
            appliedBorderSignature = null
            applyWindowBorder()
        }
    private var appliedBorderSignature: Triple<Boolean, Boolean, com.lateral.OmarchyTheme>? = null
    private var backQueryGeneration = 0
    internal fun setPhoneDisplay(value: Boolean) {
        surface.nativePhoneInput = value
    }
    var uiScale = 1f
        set(value) {
            if (kotlin.math.abs(field - value) < .001f) return
            field = value
            applyUiScale()
        }
    var fontScale = 1f
        set(value) {
            if (kotlin.math.abs(field - value) < .001f) return
            field = value
            applyFontScale()
        }
    var appDensityDpi = 240
        set(value) {
            val next = value.coerceIn(MIN_HOSTED_APP_DPI, MAX_HOSTED_APP_DPI)
            if (field == next) return
            field = next
            surface.targetDensityDpi = next
        }
    var phoneAppConfiguration = InputSettings.PhoneAppConfiguration(240, 1f, 1, 1f, 1.25f)
        set(value) {
            if (field == value) return
            field = value
            updateAppDensityForRenderedHeight()
        }
    var renderedVerticalScale = 1f
        set(value) {
            val next = value.coerceIn(.1f, 1f)
            if (kotlin.math.abs(field - next) < .001f) return
            field = next
            updateAppDensityForRenderedHeight()
        }

    init {
        isClickable = true
        clipToPadding = true
        setOnClickListener { controlDebouncer.submit { onFocus?.invoke() } }
        surface.onInteraction = {
            onFocus?.invoke()
            refreshBackAvailability()
        }
        surface.onDisplayReady = {
            onDisplayReady?.invoke(it)
            updateCaptureAvailability()
            refreshBackAvailability()
        }
        surface.onTaskHosted = { onTaskHosted?.invoke() }
        surface.onRestoreFailed = { result -> onRestoreFailed?.invoke(result) }
        surface.onAvailabilityChanged = { updateCaptureAvailability() }
        surface.onKeyboardRoutingUnavailable = {
            boundTask?.let { task -> bind(task, false) }
        }
        surface.targetDensityDpi = appDensityDpi
        surface.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateAppDensityForRenderedHeight()
        }
        column.orientation = LinearLayout.VERTICAL
        chrome.setPadding(dp(4), 0, dp(4), 0)
        chrome.setBackgroundColor(InputSettings.theme.background)
        appLabel.typeface = Typeface.MONOSPACE
        appLabel.textSize = 11f * fontScale
        appLabel.setTextColor(InputSettings.theme.foreground)
        appLabel.maxLines = 1
        appLabel.ellipsize = TextUtils.TruncateAt.END
        appLabel.minWidth = 0
        appLabel.minEms = 0
        appLabel.includeFontPadding = false
        appLabel.contentDescription = "Window title: drag to move or swap; right-drag to resize"
        appLabel.tooltipText = appLabel.contentDescription
        backButton = control("←") { onBack?.invoke() }.apply {
            contentDescription = "Back"
            isEnabled = false
            alpha = .35f
        }
        controlBaseWidths[backButton] = 34
        leftControls.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(backButton, LinearLayout.LayoutParams(
                dp(34),
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }

        noteNavigation.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        appLabel.gravity = Gravity.CENTER
        noteNavigation.addView(appLabel, LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.MATCH_PARENT,
            1f,
        ))
        chrome.addView(noteNavigation, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER,
        ).apply {
            // Keep the title slot centered in the decorator even though the left
            // and right control groups have different widths.
            marginStart = dp(RIGHT_CONTROLS_WIDTH_DP)
            marginEnd = dp(RIGHT_CONTROLS_WIDTH_DP)
        })

        rightControls.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        modeButton.gravity = Gravity.CENTER
        modeButton.typeface = Typeface.MONOSPACE
        modeButton.textSize = 12f * fontScale
        modeButton.setTextColor(InputSettings.accentColor)
        modeButton.contentDescription = "Switch phone / tablet width"
        modeButton.tooltipText = modeButton.contentDescription
        modeButton.setOnClickListener { controlDebouncer.submit { onMode?.invoke() } }
        modeButton.installBeastHover { InputSettings.accentColor }
        windowMenuButton = control("⋯") {
            onWindowMenu?.invoke()
        }.apply {
            contentDescription = "Window actions: width, move, capture"
            tooltipText = contentDescription
        }
        controlBaseWidths[windowMenuButton] = 30
        leftControls.addView(windowMenuButton, LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.MATCH_PARENT))
        chrome.addView(leftControls, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.START or Gravity.CENTER_VERTICAL,
        ))
        rightControls.addView(control("−") { onMinimize?.invoke() }.apply { contentDescription = "Minimize window"; tooltipText = contentDescription })
        rightControls.addView(modeButton, LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.MATCH_PARENT))
        val fullscreenButton = control("□") { onFullscreen?.invoke() }
        fullscreenButton.contentDescription = "Enter fullscreen"
        fullscreenButton.tooltipText = fullscreenButton.contentDescription
        controlBaseWidths[fullscreenButton] = 36
        rightControls.addView(fullscreenButton, LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.MATCH_PARENT))
        rightControls.addView(control("×") { onClose?.invoke() }.apply {
            contentDescription = "Close window"; tooltipText = contentDescription
            setTextColor(InputSettings.theme.danger); installBeastHover { InputSettings.theme.danger }
        })
        chrome.addView(rightControls, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.END or Gravity.CENTER_VERTICAL,
        ))
        chrome.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val nextScale = decoratorGeometryScale(uiScale, right - left, resources.displayMetrics.density)
            if (kotlin.math.abs(nextScale - decoratorScale) >= .005f) {
                decoratorScale = nextScale
                applyDecoratorGeometry()
            }
            updateNavigationVisibility()
        }
        chrome.minimumWidth = 0
        column.addView(chrome, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))
        column.addView(groupBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))
        column.addView(surface, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullscreenExit.text = "EXIT  T"
        fullscreenExit.contentDescription = "Exit fullscreen and restore previous width"
        fullscreenExit.tooltipText = fullscreenExit.contentDescription
        fullscreenExit.gravity = Gravity.CENTER
        fullscreenExit.typeface = Typeface.MONOSPACE
        fullscreenExit.textSize = 12f * fontScale
        fullscreenExit.setTextColor(InputSettings.accentColor)
        fullscreenExit.setBackgroundColor(Color.argb(190, Color.red(InputSettings.theme.darkerBackground), Color.green(InputSettings.theme.darkerBackground), Color.blue(InputSettings.theme.darkerBackground)))
        fullscreenExit.visibility = View.GONE
        fullscreenExit.setOnClickListener { controlDebouncer.submit { onFullscreen?.invoke() } }
        fullscreenExit.installBeastHover { InputSettings.accentColor }
        addView(fullscreenExit, LayoutParams(dp(96), dp(36), Gravity.TOP or Gravity.END))
        applyUiScale()
        applyFontScale()
    }

    fun bind(task: BeastTask, focused: Boolean) {
        boundTask = task
        this.focused = focused
        val members = if (task.group == null) emptyList() else WorkspaceState.tasks.filter { it.group == task.group }
        groupBar.visibility = if (omarchyChrome && !fullscreen && members.isNotEmpty()) View.VISIBLE else View.GONE
        val groupKey = "${members.map { it.id to it.label }}:${task.id}:$focused:$fontScale:${InputSettings.theme.id}"
        if (groupRenderKey != groupKey) {
            groupRenderKey = groupKey
            groupBar.removeAllViews()
            members.forEach { member ->
                groupBar.addView(TextView(context).apply {
                    text = member.label; setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
                    typeface = Typeface.MONOSPACE; textSize = 11f * fontScale
                    gravity = Gravity.CENTER; setTextColor(if (member.id == task.id) InputSettings.accentColor else InputSettings.theme.foreground)
                    contentDescription = "Group window ${member.label}"; isFocusable = true
                    setOnClickListener { WorkspaceState.focus(member.id, reveal = true) }; installBeastHover()
                }, LinearLayout.LayoutParams(0, -1, 1f))
            }
        }
        surface.task = task
        appLabel.text = buildString {
            append(task.label)
            if (!task.supportsMultiWindow && task.placement == TaskPlacement.PHONE) append(" · phone only")
            if (PrivilegedService.imeRoutingState ==
                PrivilegedService.ImeRoutingState.UNAVAILABLE
            ) {
                append(" · keyboard routing unavailable")
                PrivilegedService.imeRoutingError.takeIf(String::isNotBlank)?.let {
                    append(" · ").append(it)
                }
            }
            task.closeError?.let { append(" · close failed") }
        }
        val routingAvailable = PrivilegedService.imeRoutingState !=
            PrivilegedService.ImeRoutingState.UNAVAILABLE
        surface.isEnabled = routingAvailable
        surface.alpha = if (routingAvailable) 1f else .22f
        modeButton.text = task.mode.marker
        appLabel.contentDescription = appLabel.text
        appLabel.tooltipText = appLabel.text
        appLabel.setTextColor(if (focused) InputSettings.theme.brightForeground else InputSettings.theme.foreground)
        modeButton.tooltipText = "${task.mode.name.lowercase()} width · click to switch"
        fullscreenExit.text = "EXIT  ${task.fullscreenReturnMode?.marker ?: PresentationMode.TABLET.marker}"
        fullscreenExit.contentDescription = "Exit fullscreen to ${task.fullscreenReturnMode?.name?.lowercase() ?: "tablet"} width"
        applyWindowBorder()
        updateCaptureAvailability()
        refreshBackAvailability()
    }

    fun refreshAccent() {
        modeButton.setTextColor(InputSettings.accentColor)
        fullscreenExit.setTextColor(InputSettings.accentColor)
        chrome.setBackgroundColor(InputSettings.theme.background)
        appLabel.setTextColor(if (focused) InputSettings.theme.brightForeground else InputSettings.theme.foreground)
        controls.forEach { it.setTextColor(if (it.text == "×") InputSettings.theme.danger else InputSettings.theme.foreground) }
        fullscreenExit.setBackgroundColor(Color.argb(190, Color.red(InputSettings.theme.darkerBackground), Color.green(InputSettings.theme.darkerBackground), Color.blue(InputSettings.theme.darkerBackground)))
        applyWindowBorder()
    }

    /** The title is a drag handle; toolbar buttons remain ordinary click targets. */
    internal fun isTitleHit(x: Int, y: Int): Boolean = chrome.visibility == View.VISIBLE &&
        Rect().let { bounds -> noteNavigation.getGlobalVisibleRect(bounds) && bounds.contains(x, y) }

    fun setFullscreen(fullscreen: Boolean) {
        this.fullscreen = fullscreen
        chrome.visibility = if (fullscreen) View.GONE else View.VISIBLE
        groupBar.visibility = if (!fullscreen && omarchyChrome && boundTask?.group != null) View.VISIBLE else View.GONE
        fullscreenExit.visibility = if (fullscreen) View.VISIBLE else View.GONE
        applyWindowBorder()
    }

    private fun applyWindowBorder() {
        val signature = Triple(fullscreen, focused, InputSettings.theme)
        if (appliedBorderSignature == signature) return
        appliedBorderSignature = signature
        val border = if (fullscreen) 0 else if (omarchyChrome) OMARCHY_WINDOW_BORDER_DP else dp(OMARCHY_WINDOW_BORDER_DP).coerceAtLeast(1)
        setPadding(border, border, border, border)
        background = if (fullscreen) {
            ColorDrawable(InputSettings.theme.darkerBackground)
        } else {
            omarchyWindowBorder(focused, border)
        }
    }

    private fun updateAppDensityForRenderedHeight() {
        if (surface.height <= 0) return
        val configuration = phoneAppConfiguration
        val renderedHeight = surface.height * renderedVerticalScale
        appDensityDpi = HostedAppDensity.supersampledDensity(
            HostedAppDensity.calculate(
                configuration.densityDpi,
                renderedHeight,
                configuration.windowHeightPx,
            ),
            configuration.supersampling,
        )
        surface.renderScale = HostedAppDensity.renderScale(
            configuration.hostedAppScale,
            configuration.supersampling,
        )
    }

    fun release() = surface.release()
    fun retainForReattach() = surface.retainForReattach()
    fun sendKey(keyCode: Int) = surface.sendKey(keyCode)
    fun sendNativeKey(event: KeyEvent) = surface.sendNativeKey(event)
    fun captureBitmap() = surface.captureBitmap()
    fun canCapture() = surface.canCapture()
    fun fullscreenExitControl(): TextView = fullscreenExit
    fun restoreFocus() = surface.requestFocus()
    fun refreshAfterHostResume() = surface.refreshAfterHostResume()
    fun retryImeRouting() = surface.retryImeRouting()
    fun routeScrollAt(globalX: Int, globalY: Int, amount: Float) =
        surface.routeScrollAt(globalX, globalY, amount)
    fun routePinchAt(globalX: Int, globalY: Int, scale: Float) =
        surface.routePinchAt(globalX, globalY, scale)
    fun routeClickAt(globalX: Int, globalY: Int, button: Int) =
        surface.routeClickAt(globalX, globalY, button)
    fun routePointerAt(
        globalX: Int,
        globalY: Int,
        action: Int,
        button: Int,
        buttonState: Int,
    ) = surface.routePointerAt(globalX, globalY, action, button, buttonState)
    fun cancelRoutedPointer() = surface.cancelRoutedPointer()

    fun performBack() {
        if (!backButton.isEnabled) return
        if (sendKey(KeyEvent.KEYCODE_BACK)) {
            backButton.isEnabled = false
            postDelayed({
                surface.catchBackExit { refreshBackAvailability() }
            }, BACK_CATCH_DELAY_MS)
        }
    }

    private fun refreshBackAvailability() {
        val query = ++backQueryGeneration
        surface.queryBackAvailability { available ->
            if (query != backQueryGeneration) return@queryBackAvailability
            backButton.isEnabled = available
            backButton.alpha = if (available) 1f else .35f
            backButton.contentDescription = if (available) "Back" else "Back unavailable"
        }
    }

    private fun updateCaptureAvailability() {
        // The action menu remains usable even when capture is prohibited or unavailable.
        if (::windowMenuButton.isInitialized) windowMenuButton.isEnabled = true
    }

    private fun control(text: String, action: () -> Unit) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        typeface = Typeface.MONOSPACE
        textSize = 12f * fontScale
        setTextColor(InputSettings.theme.foreground)
        includeFontPadding = false
        setOnClickListener { controlDebouncer.submit(action) }
        installBeastHover { InputSettings.theme.foreground }
        layoutParams = LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.MATCH_PARENT)
    }.also {
        controls += it
        controlBaseWidths[it] = 30
    }

    private fun applyUiScale() {
        if (!::backButton.isInitialized || !::windowMenuButton.isInitialized) return
        decoratorScale = decoratorGeometryScale(uiScale, chrome.width, resources.displayMetrics.density)
        applyDecoratorGeometry()
        applyFontScale()
        requestLayout()
    }

    private fun applyDecoratorGeometry() {
        chrome.setPadding(decoratorDp(4), 0, decoratorDp(4), 0)
        applyWindowBorder()
        chrome.layoutParams?.let {
            it.height = decoratorDp(32)
            chrome.layoutParams = it
        }
        controls.forEach { control ->
            control.layoutParams?.let {
                it.width = decoratorDp(controlBaseWidths[control] ?: 30)
                control.layoutParams = it
            }
        }
        modeButton.layoutParams?.let {
            it.width = decoratorDp(30)
            modeButton.layoutParams = it
        }
        (noteNavigation.layoutParams as? FrameLayout.LayoutParams)?.let {
            val inset = decoratorDp(RIGHT_CONTROLS_WIDTH_DP)
            it.marginStart = inset
            it.marginEnd = inset
            noteNavigation.layoutParams = it
        }
        updateNavigationVisibility(force = true)
        fullscreenExit.layoutParams?.let {
            it.width = decoratorDp(96)
            it.height = decoratorDp(36)
            fullscreenExit.layoutParams = it
        }
    }

    /** Keep the identity centered and ellipsized, even at large accessibility scales. */
    private fun updateNavigationVisibility(force: Boolean = false) {
        if (chrome.width <= 0) return
        val titleSlotWidth = chrome.width - chrome.paddingLeft - chrome.paddingRight -
            decoratorDp(RIGHT_CONTROLS_WIDTH_DP * 2)
        val collapseTitle = titleSlotWidth < decoratorDp(16)
        val currentlyCollapsed = appLabel.visibility == View.GONE
        if (collapseTitle == currentlyCollapsed && !force) return

        appLabel.visibility = if (collapseTitle) View.GONE else View.VISIBLE
        val params = noteNavigation.layoutParams as? FrameLayout.LayoutParams ?: return
        if (collapseTitle) {
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT
            params.marginStart = 0
            params.marginEnd = 0
            params.gravity = Gravity.CENTER
            noteNavigation.gravity = Gravity.CENTER
        } else {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.marginStart = decoratorDp(RIGHT_CONTROLS_WIDTH_DP)
            params.marginEnd = decoratorDp(RIGHT_CONTROLS_WIDTH_DP)
            params.gravity = Gravity.CENTER
            noteNavigation.gravity = Gravity.CENTER_VERTICAL
        }
        noteNavigation.layoutParams = params
    }

    private fun applyFontScale() {
        val textScale = decoratorTextScale()
        appLabel.textSize = 11f * textScale
        modeButton.textSize = 12f * textScale
        fullscreenExit.textSize = 12f * textScale
        controls.forEach { it.textSize = 12f * textScale }
    }

    private companion object {
        const val BACK_CATCH_DELAY_MS = 500L
        // Four controls total 126dp; reserve a gap plus independent pixel rounding.
        const val RIGHT_CONTROLS_WIDTH_DP = 130
        const val DECORATOR_TEXT_SCALE_MAX = 1.50f
        const val MIN_HOSTED_APP_DPI = 72
        const val MAX_HOSTED_APP_DPI = HostedAppDensity.MAX_RENDER_DPI
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun decoratorDp(value: Int): Int =
        (dp(value) * decoratorScale).roundToInt()

    private fun decoratorTextScale(): Float = fontScale.coerceIn(.85f, DECORATOR_TEXT_SCALE_MAX)

}
