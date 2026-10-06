package com.lateral.privileged

import android.content.Context
import android.os.Build
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Orchestrates UxSpace's own shell-uid helper: pairs with the device's wireless-debugging
 * dialog, discovers and connects to the ADB connect service, starts a [PrivilegedServer]
 * over `app_process`, and exposes the privileged-call surface UxSpace uses to launch apps,
 * inject input, and create trusted virtual displays.
 *
 * Built in stage 4 of [docs/PRIVILEGE.md]; not yet wired by UxSpaceApp — the app still goes
 * through [ShizukuManager]. Stage 5 swaps the wiring and removes the Shizuku path.
 *
 * Lifecycle:
 *
 *  - First run, after [activate] pairs with a 6-digit code, the ADB identity (key + cert)
 *    is persisted; the device trusts it permanently.
 *  - Every launch, [ensureRunning] discovers the connect port via mDNS, connects, and
 *    starts the server. The returned [AdbStream] is held open so the server stays alive;
 *    closing it (or the app dying) tears the server down.
 *  - The server sends its Binder back through [BinderReceiverProvider]; that arrives at
 *    [onPrivilegedBinder] and moves the state machine to [State.READY].
 */
object PrivilegedService {

    enum class ImeRoutingState { CHECKING, AVAILABLE, UNAVAILABLE }

    enum class FocusRestoreResult(val code: Int, val targetRestored: Boolean) {
        SUCCESS(0, true),
        TARGET_RESTORE_FAILED(1, false),
        PHONE_FOCUS_FAILED(2, true),
        VERIFICATION_FAILED(3, true),
        GUARD_SETUP_FAILED(4, true),
        HELPER_UNAVAILABLE(-1, false),
        UNKNOWN(-2, false);

        companion object {
            fun fromCode(code: Int): FocusRestoreResult = entries.firstOrNull { it.code == code }
                ?: UNKNOWN
        }
    }

    sealed interface HostedDisplayResult {
        data class Ready(val displayId: Int) : HostedDisplayResult
        data object HelperUnavailable : HostedDisplayResult
        data object KeyboardRoutingUnavailable : HostedDisplayResult
        data object DisplayCreationFailed : HostedDisplayResult
    }

    data class RecentTask(
        val taskId: Int,
        val lastActiveTime: Long,
        val isRunning: Boolean,
        val packageName: String,
        val activityName: String,
        val title: String,
    )

    data class TaskSnapshot(
        val taskId: Int,
        val overviewRank: Int,
        val lastActiveTime: Long,
        val isRunning: Boolean,
        val hasBeenVisible: Boolean,
        val displayId: Int,
        val isVisible: Boolean,
        val isFocused: Boolean,
        val activityType: Int,
        val excluded: Boolean,
        val supportsMultiWindow: Boolean,
        val packageName: String,
        val activityName: String,
        val title: String,
        val isAvailable: Boolean,
    )

    data class FocusedEditorInfo(val inputType: Int, val imeOptions: Int)

    enum class SessionImeResult(val code: Int) {
        SUCCESS(0), QUERY_FAILED(1), ENABLE_FAILED(2), SELECT_FAILED(3),
        VERIFY_FAILED(4), OWNER_DEAD(5), NOT_SELECTED(6), NO_PREVIOUS(7),
        RESTORE_FAILED(8), HELPER_UNAVAILABLE(-1), UNKNOWN(-2);

        companion object {
            fun fromCode(code: Int): SessionImeResult =
                entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    data class SessionImeActivation(
        val result: SessionImeResult,
        val previousImeId: String,
        val selectedImeId: String,
    )

    data class ImeClientSnapshot(
        val selectedImeId: String,
        val displayId: Int,
        val taskId: Int,
        val packageName: String,
        val inputType: Int,
        val imeOptions: Int,
    )

    private const val TAG = "Lateral/Privileged"

    /** Steps the user (or UxSpace) must clear before privileged calls work. */
    enum class State {
        /** Android < 11 — the wireless-debugging path does not exist. */
        UNSUPPORTED,

        /**
         * Developer options is locked. Wireless Debugging can't be turned on until the user
         * unlocks Developer options (Settings → About → tap Build number 7×), so the wizard
         * walks them through that first.
         */
        NEEDS_DEVELOPER_OPTIONS,

        /** Paired before, but the connect service is not on mDNS (wireless debugging off). */
        NEEDS_WIRELESS_DEBUGGING,

        /**
         * Never paired (or the device forgot our key — connect failed with
         * AdbPairingRequiredException, which clears the paired marker). The wizard asks for
         * a pairing code.
         */
        NEEDS_PAIRING,

        /** mDNS / TCP work in progress. */
        DISCOVERING,
        CONNECTING,
        STARTING,

        /** Helper bound — privileged calls go through. */
        READY,
    }

    @Volatile
    var state: State = State.NEEDS_PAIRING
        private set

    @Volatile
    var imeRoutingState: ImeRoutingState = ImeRoutingState.CHECKING
        private set

    @Volatile
    var imeRoutingError: String = ""
        private set

    @Volatile
    private var service: IPrivilegedService? = null

    private var appContext: Context? = null
    @Volatile private var adbBackend: AdbPrivilegeBackend? = null
    private val backendAttemptIds = AtomicLong(0)
    @Volatile private var activeBackendAttempt = 0L
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "lateral-privileged") }
    /** Pinch pacing must never occupy the lane used by clicks, keys, and task actions. */
    private val pinchWorker = Executors.newSingleThreadExecutor { Thread(it, "lateral-pinch") }
    private val pendingPinch = AtomicReference<PinchRequest?>(null)
    private val pinchDrainScheduled = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "lateral-privileged-sched")
    }

    private data class PinchRequest(
        val displayId: Int,
        val centerX: Int,
        val centerY: Int,
        val fromSpan: Int,
        val toSpan: Int,
        val durationMs: Int,
    )

    /** Call once, from the Application. Computes the initial state from persisted identity. */
    fun init(context: Context) {
        appContext = context.applicationContext
        adbBackend = AdbPrivilegeBackend(context.applicationContext)
        // USB provider admission and READY publication are serialized on this worker too.
        // Queue initialization behind any admission already accepted before Application.onCreate.
        worker.execute {
            val usbInProgress = usbAttempt?.valid() == true
            val usbReady = usbLease?.let {
                it.binder == service?.asBinder() && !it.attempt.cancelled &&
                    it.attempt.ready.getNow(false) == true && it.binder.pingBinder()
            } == true
            if (!isBridgeReady() && !usbInProgress && !usbReady) state = State.NEEDS_PAIRING
            notifyListeners()
        }
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    private val adbBackendCallback = object : PrivilegeBackend.Callback {
        override fun onConnecting(attemptId: Long) {
            worker.execute {
                if (attemptId == activeBackendAttempt) setState(State.DISCOVERING)
            }
        }

        override fun onConnected(attemptId: Long, binder: IBinder) {
            worker.execute {
                if (attemptId != activeBackendAttempt) {
                    if (service?.asBinder() != binder) runCatching { IPrivilegedService.Stub.asInterface(binder).exit() }
                    return@execute
                }
                setState(State.STARTING)
                onBinderOnWorker(binder, null)
            }
        }

        override fun onUnavailable(attemptId: Long, reason: UnavailableReason) {
            worker.execute {
                if (attemptId != activeBackendAttempt) return@execute
                setState(when (reason) {
                    UnavailableReason.DEVELOPER_OPTIONS_REQUIRED -> State.NEEDS_DEVELOPER_OPTIONS
                    UnavailableReason.PAIRING_REQUIRED -> State.NEEDS_PAIRING
                    UnavailableReason.INCOMPATIBLE_RUNTIME -> State.UNSUPPORTED
                    else -> State.NEEDS_WIRELESS_DEBUGGING
                })
            }
        }
    }
    @Volatile private var navigationObserverReady = false
    fun setPhoneNavigationObserverReady(ready: Boolean) {
        navigationObserverReady = ready
        runCatching { service?.setPhoneNavigationObserverReady(ready) }
    }
    fun cancelPhoneFocusRecovery() {
        // Direct Binder call: never queue Home behind a slow hosted-app launch.
        runCatching { service?.clearPhoneTouchGuard() }
    }
    fun allowPhoneFocusRecovery(taskId: Int) {
        runCatching { service?.allowPhoneFocusRecovery(taskId) }
    }
    /** Validate the live helper, not just a potentially stale UI state. */
    fun isBridgeReady(): Boolean = state == State.READY &&
        runCatching { service?.asBinder()?.pingBinder() == true }.getOrDefault(false)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun setState(next: State) {
        if (next == state) return
        Log.i(TAG, "state: $state -> $next")
        state = next
        notifyListeners()
    }

    private fun setImeRoutingState(next: ImeRoutingState) {
        if (next == imeRoutingState) return
        Log.i(TAG, "IME routing: $imeRoutingState -> $next")
        imeRoutingState = next
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.forEach { runCatching { it() } }
    }

    /** Start the selected activation backend. */
    fun ensureRunning() {
        worker.execute {
            if (usbAttempt?.valid() == true) return@execute
            if (state == State.READY && service != null) return@execute
            if (state == State.DISCOVERING || state == State.CONNECTING || state == State.STARTING) return@execute
            val backend = adbBackend ?: return@execute
            val attemptId = backendAttemptIds.incrementAndGet()
            activeBackendAttempt = attemptId
            setState(State.DISCOVERING)
            backend.start(attemptId, adbBackendCallback)
        }
    }

    /**
     * Pair using the 6-digit [pairingCode] from Wireless Debugging's "Pair device with a
     * pairing code" dialog. The pairing port is discovered via mDNS — this only works
     * while the dialog is still in the foreground, which is why UxSpace reads the code
     * from a notification's `RemoteInput` (the shade overlays the dialog instead of
     * backgrounding it). [done] runs on the worker thread.
     */
    fun activate(pairingCode: String, done: (Boolean) -> Unit) {
        val backend = adbBackend
        if (backend == null) { done(false); return }
        backend.activate(pairingCode) { paired ->
            done(paired)
            if (paired) ensureRunning()
        }
    }

    // Owned by the serial service worker; attempt cancellation is immediately visible.
    private var usbAttempt: UsbBootstrapAttempt? = null
    @Volatile private var visibleUsbAttempt: UsbBootstrapAttempt? = null
    private var usbCandidate: IBinder? = null
    private var usbShellAccepted = false
    private var serviceGeneration = 0L
    private var serviceDeath: IBinder.DeathRecipient? = null
    private class UsbLease(val attempt: UsbBootstrapAttempt, val binder: IBinder,
        val generation: Long, val owner: IBinder = Binder())
    @Volatile private var usbLease: UsbLease? = null
    enum class BridgeTransport { NONE, USB, WIRELESS }
    @Volatile private var acceptedTransport: BridgeTransport = BridgeTransport.NONE
    fun bridgeTransport(): BridgeTransport {
        if (!isBridgeReady()) return BridgeTransport.NONE
        if (acceptedTransport != BridgeTransport.USB) return acceptedTransport
        val lease = usbLease ?: return BridgeTransport.NONE
        return if (lease.binder == service?.asBinder() && !lease.attempt.cancelled &&
            lease.attempt.ready.getNow(false) == true && lease.binder.pingBinder())
            BridgeTransport.USB else BridgeTransport.NONE
    }
    private var usbDeadline: Runnable? = null

    internal fun maySweepOrphanHelpers(): Boolean = visibleUsbAttempt?.valid() != true && usbLease?.let {
        it.binder == service?.asBinder() && !it.attempt.cancelled &&
            it.attempt.ready.getNow(false) == true && it.binder.pingBinder()
    } != true

    internal fun admitAdbOnly(token: String, helper: IBinder): UsbBootstrapAttempt {
        val attempt = UsbBootstrapAttempt(token, System.nanoTime() + 15_000_000_000L)
        val death = IBinder.DeathRecipient { cancelUsb(attempt) }
        try {
            helper.linkToDeath(death, 0)
            attempt.onEnded { runCatching { helper.unlinkToDeath(death, 0) } }
            check(helper.pingBinder())
            beginUsb(attempt, adbHelper = helper)
        } catch (_: Exception) {
            cancelUsb(attempt)
        }
        return attempt
    }

    internal fun beginUsb(attempt: UsbBootstrapAttempt, adbHelper: IBinder? = null) {
        worker.execute {
            // Stage one correlated USB candidate while retaining the current owner.
            if (!attempt.valid()) {
                attempt.refusalReason = "USB admission expired"
                attempt.cancel()
                return@execute
            }
            if (usbLease?.let { it.binder.pingBinder() && !it.attempt.cancelled } == true) {
                attempt.refusalReason = "An active USB helper already owns the service"
                attempt.cancel()
                return@execute
            }
            if (usbAttempt?.valid() == true) {
                attempt.refusalReason = "Another USB admission is already in progress"
                attempt.cancel()
                return@execute
            }
            usbAttempt?.cancel()
            usbCandidate?.let { rejectOffer(it) }
            usbAttempt = attempt
            visibleUsbAttempt = attempt
            usbShellAccepted = adbHelper != null
            usbCandidate = adbHelper
            attempt.admitted.complete(true)
            val delay = ((attempt.deadlineNanos - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
            usbDeadline?.let { mainHandler.removeCallbacks(it) }
            usbDeadline = Runnable {
                worker.execute { if (usbAttempt === attempt) cancelUsb(attempt) }
            }.also { mainHandler.postDelayed(it, delay) }
        }
    }

    internal fun cancelUsb(attempt: UsbBootstrapAttempt) {
        attempt.cancel()
        worker.execute {
            val lease = usbLease
            if (lease?.attempt === attempt) {
                usbLease = null
                if (BinderOfferPolicy.currentDeath(service?.asBinder(), lease.binder,
                        serviceGeneration, lease.generation)) {
                    serviceDeath?.let { runCatching { lease.binder.unlinkToDeath(it, 0) } }
                    service = null
                    acceptedTransport = BridgeTransport.NONE
                    serviceDeath = null
                    serviceGeneration++
                    runCatching { IPrivilegedService.Stub.asInterface(lease.binder).exit() }
                    setImeRoutingState(ImeRoutingState.UNAVAILABLE)
                    setState(State.NEEDS_WIRELESS_DEBUGGING)
                    ensureRunning()
                }
            }
            if (usbAttempt !== attempt) return@execute
            usbDeadline?.let { mainHandler.removeCallbacks(it) }
            usbDeadline = null
            usbAttempt = null
            visibleUsbAttempt = null
            usbCandidate?.let { rejectOffer(it) }
            usbCandidate = null
            usbShellAccepted = false
            if (service?.asBinder()?.pingBinder() != true) {
                setState(State.NEEDS_WIRELESS_DEBUGGING)
                ensureRunning()
            }
        }
    }

    internal fun usbShellAccepted(attempt: UsbBootstrapAttempt) {
        worker.execute {
            if (usbAttempt !== attempt || !attempt.valid()) return@execute
            usbShellAccepted = true
            usbCandidate?.let { onBinderOnWorker(it, attempt.token) }
        }
    }

    private fun rejectOffer(binder: IBinder) {
        // A repeated offer of our accepted Binder must never terminate that service.
        if (service?.asBinder() == binder) return
        runCatching { IPrivilegedService.Stub.asInterface(binder).exit() }
    }

    internal fun offerAdbBinder(binder: IBinder, attemptId: Long) {
        adbBackend?.offerBinder(attemptId, binder)
    }

    fun onPrivilegedBinder(binder: IBinder, usbToken: String? = null) {
        worker.execute { onBinderOnWorker(binder, usbToken) }
    }

    /** Never waits on the serial worker; the provider bounds its own Binder-thread wait. */
    internal fun offerUsbBinder(binder: IBinder, token: String): CompletableFuture<Bundle?> {
        val reply = CompletableFuture<Bundle?>()
        worker.execute {
            if (reply.isCancelled) { rejectOffer(binder); return@execute }
            val attempt = usbAttempt?.takeIf { it.matches(token) }
            fun acknowledge() {
                val lease = usbLease
                val owned = lease != null && lease.attempt.token == token &&
                    !lease.attempt.cancelled && lease.binder == binder &&
                    BinderOfferPolicy.currentDeath(service?.asBinder(), binder,
                        serviceGeneration, lease.generation) && binder.pingBinder()
                val bundle = if (owned) Bundle().apply {
                    putBoolean(BinderReceiverProvider.EXTRA_ACCEPTED, true)
                    putBinder(BinderReceiverProvider.EXTRA_OWNER, lease!!.owner)
                } else null
                if (!reply.complete(bundle) && owned) cancelUsb(lease!!.attempt)
            }
            onBinderOnWorker(binder, token)
            if (usbCandidate == binder && usbAttempt === attempt && attempt != null) {
                attempt.ready.whenComplete { _, _ -> worker.execute { acknowledge() } }
            } else acknowledge()
            reply.whenComplete { _, _ ->
                if (reply.isCancelled) worker.execute {
                    val lease = usbLease
                    if (lease?.binder == binder && lease.attempt.token == token) {
                        cancelUsb(lease.attempt)
                    } else if (usbCandidate == binder && usbAttempt === attempt && attempt != null) {
                        cancelUsb(attempt)
                    }
                }
            }
        }
        return reply
    }

    private fun onBinderOnWorker(binder: IBinder, usbToken: String?) {
        if (service?.asBinder() == binder && binder.pingBinder()) return
        val attempt = usbAttempt
        when (BinderOfferPolicy.decide(service?.asBinder(), service?.asBinder()?.pingBinder() == true,
            usbCandidate, binder, usbToken, attempt, usbShellAccepted)) {
            BinderOfferPolicy.Decision.IGNORE -> return
            BinderOfferPolicy.Decision.REJECT -> { rejectOffer(binder); return }
            BinderOfferPolicy.Decision.WAIT_FOR_SHELL -> { usbCandidate = binder; return }
            BinderOfferPolicy.Decision.ACCEPT -> Unit
        }
        if (!binder.pingBinder()) {
            Log.w(TAG, "onPrivilegedBinder: ping failed")
            if (usbToken != null && attempt != null) cancelUsb(attempt)
            return
        }
        val generation = serviceGeneration + 1
        val death = IBinder.DeathRecipient {
            worker.execute {
                if (BinderOfferPolicy.currentDeath(service?.asBinder(), binder, serviceGeneration, generation)) {
                    usbLease?.takeIf { it.binder == binder && it.generation == generation }?.let {
                        it.attempt.cancel()
                        usbLease = null
                    }
                    service = null
                    acceptedTransport = BridgeTransport.NONE
                    serviceDeath = null
                    serviceGeneration++
                    adbBackend?.helperLost(binder)
                    setImeRoutingState(ImeRoutingState.UNAVAILABLE)
                    setState(State.NEEDS_WIRELESS_DEBUGGING)
                    ensureRunning()
                }
            }
        }
        try {
            binder.linkToDeath(death, 0)
            check(binder.pingBinder())
            if (usbToken != null) check(attempt?.matches(usbToken) == true)
        } catch (failure: Exception) {
            runCatching { binder.unlinkToDeath(death, 0) }
            rejectOffer(binder)
            if (usbToken != null && attempt != null) cancelUsb(attempt)
            return
        }
        // The admission policy preserves every healthy accepted service.
        val previous = service
        val previousDeath = serviceDeath
        val publish: () -> Unit = {
            service = IPrivilegedService.Stub.asInterface(binder)
            acceptedTransport = if (usbToken != null) BridgeTransport.USB else BridgeTransport.WIRELESS
            serviceGeneration = generation
            serviceDeath = death
            if (usbToken != null) {
                usbLease = UsbLease(checkNotNull(attempt), binder, generation)
                usbDeadline?.let { mainHandler.removeCallbacks(it) }
                usbDeadline = null
                usbAttempt = null
                usbCandidate = null
                usbShellAccepted = false
            } else usbLease = null
        }
        if (usbToken != null) {
            if (attempt?.commit(publish) != true) {
                runCatching { binder.unlinkToDeath(death, 0) }
                rejectOffer(binder)
                if (attempt != null) cancelUsb(attempt)
                return
            }
        } else publish()
        if (previous != null && previousDeath != null) {
            runCatching { previous.asBinder().unlinkToDeath(previousDeath, 0) }
        }
        runCatching { service?.setPhoneNavigationObserverReady(navigationObserverReady) }
        // Clear a preferred external route written by older LATERAL_ builds. The phone's
        // system audio policy owns the active route (including the user's "This Phone"
        // selection); LATERAL_ must never overwrite it with USB/wired routing.
        runCatching { service?.setBeastMediaRoutingEnabled(false) }
            .onFailure { Log.e(TAG, "could not clear legacy Beast media routing", it) }
        setImeRoutingState(ImeRoutingState.CHECKING)
        Log.i(TAG, "privileged binder bound — READY")
        // Only install the hotkey listener if a keyboard is actually attached. With
        // no keyboard the helper opens no /dev/input/event* nodes at all — minimum
        // footprint, no contention against the glasses' USB IMU endpoint on OEMs
        // (Samsung) where BT input attach disturbs USB hosts.
        if (hotkeyMonitoringRequested) installHotkeyListener()
        if (!binder.pingBinder()) {
            attempt?.ready?.complete(false)
            death.binderDied()
            return
        }
        if (usbToken != null && attempt != null) {
            if (!attempt.commit {
                setState(State.READY)
                attempt.ready.complete(true)
            }) cancelUsb(attempt)
            else if (previous != null && previous.asBinder() != binder) {
                // The candidate is published and READY before retiring our previous owner.
                runCatching { previous.exit() }
            }
        } else setState(State.READY)
        if (previous != null && previous.asBinder() != binder) notifyListeners()
    }

    /**
     * App-level dispatcher for the Ctrl+Alt+X global hotkeys the shell-uid server captures
     * off `/dev/input/event*`. Set once by the app at startup (alongside the other
     * `WorkspaceController.*` lambdas in `UxSpaceApp.onCreate`). Invoked on the main thread.
     * Codes are [PrivilegedHotkeys].HK_*.
     */
    @Volatile
    var hotkeyHandler: ((code: Int) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hotkeyListenerStub = object : IPrivilegedHotkeyListener.Stub() {
        override fun onHotkey(code: Int) {
            // Binder thread → main thread; controller methods aren't always main-only,
            // but the actions (zoom presets, view mode, recenter) ultimately touch
            // the renderer and UI, so jumping once here is safest.
            mainHandler.post { hotkeyHandler?.invoke(code) }
        }
        override fun onMouseDelta(dx: Int, dy: Int, wheel: Int) {
            // Raw evdev mouse deltas — never quantised to the phone-screen edge.
            mainHandler.post { mouseDeltaHandler?.invoke(dx, dy, wheel) }
        }
        override fun onMouseButton(code: Int, pressed: Boolean) {
            // With the helper holding EVIOCGRAB on the mouse, button events never
            // reach `dispatchGenericMotionEvent` either — so we have to forward
            // them here and the app dispatches workspace clicks ourselves.
            mainHandler.post { mouseButtonHandler?.invoke(code, pressed) }
        }
    }

    /**
     * App-level handler for raw evdev mouse motion (REL_X, REL_Y, REL_WHEEL batched
     * per SYN_REPORT). Set once by the app at startup. dx/dy are kernel ticks;
     * the handler is responsible for any scaling to workspace NDC units.
     */
    @Volatile
    var mouseDeltaHandler: ((dx: Int, dy: Int, wheel: Int) -> Unit)? = null

    /**
     * App-level handler for mouse button transitions (BTN_LEFT/RIGHT/MIDDLE
     * press + release). [code] is the kernel BTN_* number, [pressed] true on
     * press / false on release.
     */
    @Volatile
    var mouseButtonHandler: ((code: Int, pressed: Boolean) -> Unit)? = null

    private fun installHotkeyListener() {
        val helper = service ?: return
        runCatching {
            helper.setHotkeyListener(hotkeyListenerStub)
        }.onFailure { Log.w(TAG, "setHotkeyListener failed", it) }
    }

    /**
     * Whether the app has currently asked the helper to monitor hotkeys. Controlled by
     * [setHotkeyMonitoringEnabled] from the keyboard-presence listener in MainActivity;
     * stays true once any keyboard appears so the next privileged-binder bind installs
     * the listener immediately.
     */
    @Volatile
    private var hotkeyMonitoringRequested: Boolean = false

    /**
     * Push the current rebindable-shortcut table to the helper.
     *
     * The global-shortcut feature is disabled for this build: the helper's HotkeyMonitor
     * still matches fixed Linux evdev EV_KEY codes and the Android-keycode→EV_KEY
     * translation was never implemented, so this is a no-op. [KeyboardShortcuts] keeps
     * persisting user bindings locally so they survive a re-enable.
     */
    fun syncHotkeyBindings(context: Context) {
        // No-op — rebindable global shortcuts are disabled in this build.
    }

    /**
     * Toggle the shell-uid HotkeyMonitor based on whether a physical keyboard is
     * attached. When `enabled = false` the listener Binder is nulled out, the helper
     * tears down its FileObserver + reader threads + every `/dev/input/event*` FD.
     * When `enabled = true` it re-opens them and resumes Ctrl+Alt+X dispatch.
     */
    fun setHotkeyMonitoringEnabled(enabled: Boolean) {
        if (hotkeyMonitoringRequested == enabled) return
        hotkeyMonitoringRequested = enabled
        Log.i(TAG, "hotkey monitoring requested -> $enabled")
        val helper = service ?: return
        runCatching {
            helper.setHotkeyListener(if (enabled) hotkeyListenerStub else null)
        }.onFailure { Log.w(TAG, "setHotkeyListener toggle failed", it) }
    }

    /**
     * Force-detach and re-attach the VITURE glasses' USB device via the helper, so the
     * kernel re-enumerates it. Triggered by the app's DOF-stall watchdog after the SDK
     * stops producing poses for an extended period — observed empirically that a stale
     * Carina USB endpoint sometimes only recovers from a bus reset, which the BT-pair
     * USB churn occasionally does on its own. Returns immediately; the actual rescan
     * runs on the helper's worker thread and the resulting USB_DEVICE_ATTACHED intent
     * re-triggers [com.uxspace.spatial.WorkspacePresentation.retryHeadTracking].
     */
    fun rescanGlassesUsb() {
        val helper = service ?: run {
            Log.w(TAG, "rescanGlassesUsb ignored — helper not READY (state=$state)")
            return
        }
        worker.execute {
            runCatching { helper.rescanGlassesUsb() }
                .onFailure { Log.w(TAG, "rescanGlassesUsb failed", it) }
        }
    }

    // region Privileged-call surface (mirrors ShizukuManager so UxSpaceApp swaps cleanly)

    fun launchApp(displayId: Int, packageName: String, activityName: String) {
        Log.i(
            "Lateral/Launch",
            "7) PrivilegedService.launchApp pkg=$packageName display=$displayId state=$state",
        )
        val helper = service
        if (helper == null) {
            Log.w(TAG, "launchApp ignored — not READY (state=$state)")
            return
        }
        worker.execute {
            runCatching {
                Log.i(
                    "Lateral/Launch",
                    "8) helper.launchOnDisplay AIDL pkg=$packageName display=$displayId",
                )
                val ok = helper.launchOnDisplay(displayId, packageName, activityName)
                Log.i(
                    "Lateral/Launch",
                    "9) helper returned ok=$ok pkg=$packageName display=$displayId",
                )
            }.onFailure { Log.e(TAG, "launchApp failed", it) }
        }
    }

    fun createHostedVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
        destroyContentOnRemoval: Boolean = true,
    ): HostedDisplayResult {
        val helper = service
        if (helper == null) {
            Log.w(TAG, "createHostedVirtualDisplay ignored — not READY (state=$state)")
            return HostedDisplayResult.HelperUnavailable
        }
        val result = runCatching {
            helper.createVirtualDisplayWithPolicy(
                name, width, height, densityDpi, surface, destroyContentOnRemoval,
            )
        }.onFailure { Log.e(TAG, "createHostedVirtualDisplay failed", it) }.getOrNull()
            ?: return HostedDisplayResult.DisplayCreationFailed
        return when {
            result >= 0 -> {
                imeRoutingError = ""
                setImeRoutingState(ImeRoutingState.AVAILABLE)
                HostedDisplayResult.Ready(result)
            }
            result == IME_ROUTING_UNAVAILABLE -> {
                imeRoutingError = runCatching { helper.imeRoutingError }.getOrDefault("")
                if (imeRoutingError.isNotBlank()) {
                    Log.e(TAG, "Keyboard routing unavailable: $imeRoutingError")
                }
                setImeRoutingState(ImeRoutingState.UNAVAILABLE)
                HostedDisplayResult.KeyboardRoutingUnavailable
            }
            else -> HostedDisplayResult.DisplayCreationFailed
        }
    }

    /** Reassert and verify native IME routing before a retained display is used. */
    fun ensureHostedDisplayImeRouting(displayId: Int): Boolean {
        val helper = service ?: return false
        val available = runCatching {
            helper.applyDefaultDisplayImePolicy(displayId) &&
                helper.getDisplayImePolicy(displayId) == DISPLAY_IME_POLICY_FALLBACK_DISPLAY
        }.onFailure { Log.e(TAG, "IME routing verification failed for display $displayId", it) }
            .getOrDefault(false)
        imeRoutingError = if (available) "" else runCatching {
            helper.imeRoutingError
        }.getOrDefault("")
        setImeRoutingState(
            if (available) ImeRoutingState.AVAILABLE else ImeRoutingState.UNAVAILABLE,
        )
        return available
    }

    fun recentTasks(): List<RecentTask> {
        val helper = service ?: return emptyList()
        return runCatching {
            helper.recentTasks.orEmpty().mapNotNull { encoded ->
                val fields = encoded.split('\t')
                if (fields.size !in 5..6) return@mapNotNull null
                RecentTask(
                    taskId = fields[0].toInt(),
                    lastActiveTime = fields[1].toLong(),
                    isRunning = fields[2].toBooleanStrictOrNull() ?: false,
                    packageName = fields[3],
                    activityName = fields[4],
                    title = fields.getOrElse(5) { "" },
                )
            }
        }.onFailure { Log.e(TAG, "recentTasks failed", it) }.getOrDefault(emptyList())
    }

    private fun taskSnapshotResponse(): Pair<Boolean, List<TaskSnapshot>> {
        val helper = service ?: return false to emptyList()
        return runCatching {
            val encodedSnapshots = helper.taskSnapshots.orEmpty()
            if (encodedSnapshots.any { it.startsWith("!error\t") }) return@runCatching false to emptyList()
            true to encodedSnapshots.mapNotNull { encoded ->
                val fields = encoded.split('\t')
                if (fields.size < 15 || fields[0] != "v1") return@mapNotNull null
                TaskSnapshot(
                    taskId = fields[1].toInt(),
                    overviewRank = fields[2].toInt(),
                    lastActiveTime = fields[3].toLong(),
                    isRunning = fields[4].toBooleanStrictOrNull() ?: false,
                    hasBeenVisible = fields[5].toBooleanStrictOrNull() ?: false,
                    displayId = fields[6].toInt(),
                    isVisible = fields[7].toBooleanStrictOrNull() ?: false,
                    isFocused = fields[8].toBooleanStrictOrNull() ?: false,
                    activityType = fields[9].toInt(),
                    excluded = fields[10].toBooleanStrictOrNull() ?: false,
                    supportsMultiWindow = fields[11].toBooleanStrictOrNull() ?: true,
                    packageName = fields[12],
                    activityName = fields[13],
                    title = fields[14],
                    // Appended compatibly to v1 after REDMAGIC exposed unavailable
                    // persistent records alongside legitimate cached Overview tasks.
                    isAvailable = fields.getOrNull(15)?.toBooleanStrictOrNull() ?: true,
                )
            }
        }.onFailure { Log.e(TAG, "taskSnapshots failed", it) }
            .getOrDefault(false to emptyList())
    }

    fun taskSnapshots(): List<TaskSnapshot> = taskSnapshotResponse().second

    fun queryTaskSnapshots(callback: (List<TaskSnapshot>?) -> Unit) {
        worker.execute {
            val (success, snapshots) = taskSnapshotResponse()
            mainHandler.post { callback(snapshots.takeIf { success }) }
        }
    }

    fun rewriteRecentTaskOrder(
        taskIdsNewestFirst: IntArray,
        displayIds: IntArray,
        callback: (Boolean) -> Unit,
    ) {
        worker.execute {
            val result = runCatching {
                service?.rewriteRecentTaskOrder(taskIdsNewestFirst, displayIds) == true
            }.getOrDefault(false)
            mainHandler.post { callback(result) }
        }
    }

    fun startRecentTaskOnDisplay(taskId: Int, displayId: Int): Boolean {
        val helper = service ?: return false
        return runCatching { helper.startRecentTaskOnDisplay(taskId, displayId) }
            .onFailure { Log.e(TAG, "startRecentTaskOnDisplay failed", it) }
            .getOrDefault(false)
    }

    /**
     * Repairs the phone display only when a task transition exposed Home. A different
     * top task is treated as an intentional user task switch and is never overridden.
     */
    fun restorePhoneTaskIfStillHome(taskId: Int): Boolean {
        val helper = service ?: return false
        return runCatching { helper.restorePhoneTaskIfStillHome(taskId) }
            .onFailure { Log.e(TAG, "restorePhoneTaskIfStillHome failed", it) }
            .getOrDefault(false)
    }

    fun restorePhoneTaskIfStillHomeAsync(taskId: Int) {
        worker.execute { restorePhoneTaskIfStillHome(taskId) }
    }

    fun focusPhoneTaskAsync(taskId: Int, callback: (Boolean) -> Unit) {
        worker.execute {
            val focused = startRecentTaskOnDisplay(taskId, android.view.Display.DEFAULT_DISPLAY)
            mainHandler.post { callback(focused) }
        }
    }

    /** Exact-task move + PhoneUI recovery, executed as one helper-side transaction. */
    fun restoreTaskPreservingPhoneFocus(
        taskId: Int,
        beastDisplayId: Int,
        phoneTaskId: Int,
    ): FocusRestoreResult {
        val helper = service ?: return FocusRestoreResult.HELPER_UNAVAILABLE
        return runCatching {
            FocusRestoreResult.fromCode(
                helper.restoreTaskPreservingPhoneFocus(taskId, beastDisplayId, phoneTaskId),
            )
        }.onFailure { Log.e(TAG, "focus-preserving restore failed for task $taskId", it) }
            .getOrDefault(FocusRestoreResult.UNKNOWN)
    }

    fun restoreTaskPreservingPhoneFocusAsync(
        taskId: Int,
        beastDisplayId: Int,
        phoneTaskId: Int,
        callback: (FocusRestoreResult) -> Unit,
    ) {
        worker.execute {
            val result = restoreTaskPreservingPhoneFocus(taskId, beastDisplayId, phoneTaskId)
            mainHandler.post { callback(result) }
        }
    }

    fun removeTask(taskId: Int): Boolean {
        val helper = service ?: return false
        return runCatching { helper.removeTask(taskId) }
            .onFailure { Log.e(TAG, "removeTask failed for $taskId", it) }
            .getOrDefault(false)
    }

    fun removeTaskAsync(taskId: Int, callback: (Boolean) -> Unit) {
        worker.execute {
            val removed = removeTask(taskId)
            mainHandler.post { callback(removed) }
        }
    }

    fun releaseVirtualDisplay(displayId: Int) =
        onWorker { service?.releaseVirtualDisplay(displayId) }

    fun resizeVirtualDisplay(displayId: Int, width: Int, height: Int, densityDpi: Int) =
        onWorker { service?.resizeVirtualDisplay(displayId, width, height, densityDpi) }

    fun clearDisplayOverrideSize(displayId: Int) =
        onWorker { service?.clearDisplayOverrideSize(displayId) }

    fun setUserPreferredDisplayMode(
        displayId: Int,
        width: Int,
        height: Int,
        refreshRate: Float,
        callback: (Boolean) -> Unit,
    ) {
        worker.execute {
            val applied = runCatching {
                service?.setUserPreferredDisplayMode(displayId, width, height, refreshRate) == true
            }.onFailure { Log.e(TAG, "setUserPreferredDisplayMode failed", it) }.getOrDefault(false)
            mainHandler.post { callback(applied) }
        }
    }

    /** Clears a legacy LATERAL_-owned external media preference, if present. */
    fun setBeastMediaRoutingEnabled(enabled: Boolean) =
        onWorker { service?.setBeastMediaRoutingEnabled(enabled) }

    fun setVirtualDisplaySurface(displayId: Int, surface: Surface) =
        onWorker { service?.setVirtualDisplaySurface(displayId, surface) }

    /**
     * Inject an ACTION_SCROLL motion event into the app on [displayId] at content
     * pixel (x, y). Identical path to `UiScreen.dispatchScroll` but routed through the
     * shell-uid helper so the event reaches an out-of-process VirtualDisplay; one
     * AIDL call per frame, no swipe synthesis.
     */
    fun scrollOnDisplay(displayId: Int, x: Int, y: Int, vScroll: Float) =
        onWorker { service?.injectScroll(displayId, x, y, vScroll) }

    /** Low-latency path used after the cursor has already been resolved to a hosted display. */
    fun scrollOnDisplayImmediate(displayId: Int, x: Int, y: Int, vScroll: Float) {
        val helper = service ?: return
        runCatching { helper.injectScroll(displayId, x, y, vScroll) }
            .onFailure { Log.e(TAG, "direct scroll failed for display $displayId", it) }
    }

    /**
     * Injects a coherent two-pointer scale gesture without blocking ordinary input.
     * If several zoom requests arrive while one is running, keep only the latest so
     * repeated pinches cannot create a seconds-long backlog.
     */
    fun pinchOnDisplay(
        displayId: Int,
        centerX: Int,
        centerY: Int,
        fromSpan: Int,
        toSpan: Int,
        durationMs: Int,
    ) {
        pendingPinch.set(PinchRequest(
            displayId, centerX, centerY, fromSpan, toSpan, durationMs,
        ))
        schedulePinchDrain()
    }

    private fun schedulePinchDrain() {
        if (!pinchDrainScheduled.compareAndSet(false, true)) return
        pinchWorker.execute {
            try {
                while (true) {
                    val request = pendingPinch.getAndSet(null) ?: break
                    runCatching {
                        service?.pinchOnDisplay(
                            request.displayId,
                            request.centerX,
                            request.centerY,
                            request.fromSpan,
                            request.toSpan,
                            request.durationMs,
                        )
                    }.onFailure { Log.e(TAG, "pinch failed for display ${request.displayId}", it) }
                }
            } finally {
                pinchDrainScheduled.set(false)
                if (pendingPinch.get() != null) schedulePinchDrain()
            }
        }
    }

    /** Inject an Android SOURCE_MOUSE frame onto the selected physical display. */
    fun injectMouse(displayId: Int, x: Int, y: Int, action: Int, buttonState: Int = 0) =
        onWorker { service?.injectMouse(displayId, x, y, action, buttonState) }

    fun clickMouse(displayId: Int, x: Int, y: Int, button: Int) =
        onWorker { service?.clickMouse(displayId, x, y, button) }

    fun clickTouch(displayId: Int, x: Int, y: Int) =
        onWorker { service?.clickTouch(displayId, x, y) }

    fun moveNativePointer(displayId: Int, dx: Int, dy: Int, wheel: Int = 0) =
        onWorker { service?.moveNativePointer(displayId, dx, dy, wheel) }

    fun nativePointerButton(displayId: Int, code: Int, pressed: Boolean) =
        onWorker { service?.nativePointerButton(displayId, code, pressed) }

    /**
     * Streamed touch injection: one frame at a time, Android action ∈ {DOWN=0, UP=1, MOVE=2,
     * CANCEL=3}. Lets the trackpad surface a touch-move-lift gesture into an app's
     * VirtualDisplay so taps and swipes work the same way they would on a real screen.
     */
    fun injectTouch(displayId: Int, x: Int, y: Int, action: Int) {
        val name = when (action) {
            0 -> "DOWN"; 1 -> "UP"; 2 -> "MOVE"; 3 -> "CANCEL"; else -> "?($action)"
        }
        Log.d(TAG, "injectTouch $name @ ($x,$y) display=$displayId")
        onWorker { service?.injectTouch(displayId, x, y, TouchActionProtocol.encode(action)) }
    }

    fun key(displayId: Int, keyCode: Int) = onWorker { service?.key(displayId, keyCode) }
    fun nativeKey(displayId: Int, event: android.view.KeyEvent) {
        val copy = android.view.KeyEvent(event)
        onWorker {
            service?.nativeKey(displayId, copy.action, copy.keyCode, copy.repeatCount, copy.metaState, copy.downTime, copy.eventTime)
        }
    }

    fun text(displayId: Int, value: String) = onWorker { service?.text(displayId, value) }

    fun queryFocusedEditor(displayId: Int, callback: (FocusedEditorInfo?) -> Unit) {
        worker.execute {
            val raw = runCatching { service?.getFocusedEditorInfo(displayId) }.getOrNull()
            val result = if (raw != null && raw.size >= 3 && raw[0] == 1) {
                FocusedEditorInfo(raw[1], raw[2])
            } else null
            mainHandler.post { callback(result) }
        }
    }

    fun beginSessionInputMethod(
        imeId: String,
        ownerToken: IBinder,
        callback: (SessionImeActivation) -> Unit,
    ) {
        worker.execute {
            val raw = runCatching { service?.beginSessionInputMethod(imeId, ownerToken) }
                .onFailure { Log.e(TAG, "session IME activation failed", it) }
                .getOrNull()
            val activation = if (raw != null && raw.size >= 3) {
                SessionImeActivation(
                    SessionImeResult.fromCode(raw[0].toIntOrNull() ?: -2),
                    raw[1], raw[2],
                )
            } else {
                SessionImeActivation(SessionImeResult.HELPER_UNAVAILABLE, "", "")
            }
            mainHandler.post { callback(activation) }
        }
    }

    fun restoreSessionInputMethod(
        sessionImeId: String,
        previousImeId: String,
        callback: ((SessionImeResult) -> Unit)? = null,
    ) {
        worker.execute {
            val result = runCatching {
                service?.restoreSessionInputMethod(sessionImeId, previousImeId)
                    ?.let(SessionImeResult::fromCode)
            }.onFailure { Log.e(TAG, "session IME restore failed", it) }
                .getOrNull() ?: SessionImeResult.HELPER_UNAVAILABLE
            callback?.let { mainHandler.post { it(result) } }
        }
    }

    fun getImeClientSnapshot(callback: (ImeClientSnapshot?) -> Unit) {
        worker.execute {
            val raw = runCatching { service?.imeClientSnapshot }.getOrNull()
            val fields = raw?.split('|')
            val snapshot = if (fields != null && fields.size >= 7 && fields[0] == "v1") {
                ImeClientSnapshot(
                    selectedImeId = fields[1],
                    displayId = fields[2].toIntOrNull() ?: -1,
                    taskId = fields[3].toIntOrNull() ?: -1,
                    packageName = fields[4],
                    inputType = fields[5].toIntOrNull() ?: 0,
                    imeOptions = fields[6].toIntOrNull() ?: 0,
                )
            } else null
            mainHandler.post { callback(snapshot) }
        }
    }

    fun forceStop(packageName: String) = onWorker { service?.forceStop(packageName) }

    /** Whether the given VirtualDisplay currently has any activity stacked on it. */
    fun displayHasActivity(displayId: Int): Boolean =
        runCatching { service?.displayHasActivity(displayId) == true }.getOrDefault(false)

    /** Queries the activity stack off the UI thread; false is the safe fallback. */
    fun queryDisplayHasBackStack(displayId: Int, callback: (Boolean) -> Unit) {
        worker.execute {
            val result = runCatching { service?.displayHasBackStack(displayId) == true }
                .getOrDefault(false)
            mainHandler.post { callback(result) }
        }
    }

    /** Queries package presence off the UI thread; true is the safe fallback when unknown. */
    fun queryDisplayHasPackage(displayId: Int, packageName: String, callback: (Boolean) -> Unit) {
        worker.execute {
            val result = runCatching { service?.displayHasPackage(displayId, packageName) ?: true }
                .getOrDefault(true)
            mainHandler.post { callback(result) }
        }
    }

    /** Reattaches a tracked Android task to its Beast virtual display. */
    fun restoreRecentTaskOnDisplay(taskId: Int, displayId: Int, callback: (Boolean) -> Unit) {
        worker.execute {
            val result = runCatching {
                service?.restoreRecentTaskOnDisplay(taskId, displayId) == true
            }.getOrDefault(false)
            mainHandler.post { callback(result) }
        }
    }

    /**
     * Injects KEYCODE_MEDIA_PAUSE at the given display so any media playback on
     * the activity (YouTube audio, etc.) stops when the window is minimised.
     */
    fun sendMediaPause(displayId: Int) {
        onWorker { service?.key(displayId, KeyEvent.KEYCODE_MEDIA_PAUSE) }
    }

    fun sendBack(displayId: Int, onEmptied: () -> Unit) {
        onWorker { service?.key(displayId, KeyEvent.KEYCODE_BACK) }
        scheduler.schedule(
            {
                runCatching {
                    if (service?.displayHasActivity(displayId) == false) onEmptied()
                }.onFailure { Log.e(TAG, "back-empty check failed", it) }
            },
            BACK_SETTLE_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    // endregion

    private fun onWorker(block: () -> Unit) {
        worker.execute { runCatching { block() } }
    }

    /** How long to wait for an mDNS hit before giving up. */
    private const val DISCOVERY_TIMEOUT_MS = 5_000L

    /** How long to wait after a Back press before checking whether it closed the app. */
    private const val BACK_SETTLE_MS = 800L

    /** Slightly beyond the helper's immutable two-second hard cap. */

    private const val DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1
    private const val IME_ROUTING_UNAVAILABLE = -2

    /** Path of the marker file created on a successful [activate] (relative to filesDir). */
    // Versioned because builds copied from UXspace used an UXspace-labelled ADB key.
    // Re-pairing with a fresh LATERAL_ identity is required once after migration.
    private const val PAIRED_MARKER = "adb/lateral_paired_v2.flag"
}
