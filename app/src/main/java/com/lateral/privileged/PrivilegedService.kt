package com.lateral.privileged

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Shared privileged operations and validated ownership for ADB, USB, and Shizuku. */
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
        STOPPING,
        STOPPED,
        SHIZUKU_NOT_INSTALLED,
        SHIZUKU_NOT_RUNNING,
        AUTHORIZATION_REQUIRED,
        AUTHORIZATION_DENIED,
        CONNECTION_LOST,
        START_TIMEOUT,

        /** Helper bound — privileged calls go through. */
        READY,
    }

    @Volatile private var lifecycleState: State = State.NEEDS_PAIRING
    val state: State
        get() = if (lifecycleState == State.READY && currentSession?.value?.binder?.isBinderAlive != true)
            State.CONNECTION_LOST else lifecycleState

    @Volatile
    var imeRoutingState: ImeRoutingState = ImeRoutingState.CHECKING
        private set

    @Volatile
    var imeRoutingError: String = ""
        private set

    private data class HelperSession(
        val backend: PrivilegeBackendId,
        val transport: BridgeTransport,
        val binder: IBinder,
        val service: IPrivilegedService,
        val deathRecipient: IBinder.DeathRecipient,
        val owner: IBinder = Binder(),
    )
    private val sessions = SessionFence<HelperSession>()
    private val currentSession get() = sessions.current
    private val service get() = currentSession?.value?.service
    val helperGeneration: Long get() = sessions.generation
    val activeBackend: PrivilegeBackendId? get() = currentSession?.value?.backend

    private var appContext: Context? = null
    @Volatile private var adbBackend: AdbPrivilegeBackend? = null
    @Volatile private var shizukuBackend: ShizukuPrivilegeBackend? = null
    private val activation = BackendAttemptGate(PrivilegeBackendId.WIRELESS_ADB)
    val selectedBackend: PrivilegeBackendId get() = activation.selected
    @Volatile var unavailableReason: UnavailableReason? = null
        private set
    @Volatile private var intentionallyStopped = false
    private fun selectedActivationBackend(): PrivilegeBackend? = when (selectedBackend) {
        PrivilegeBackendId.SHIZUKU -> shizukuBackend
        PrivilegeBackendId.WIRELESS_ADB -> adbBackend
    }
    private val activeBackendAttempt get() = activation.attemptId
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val control = Executors.newSingleThreadExecutor { Thread(it, "lateral-helper-control") }
    private val initializer = Executors.newCachedThreadPool { Thread(it, "lateral-helper-init").apply { isDaemon = true } }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "lateral-privileged") }
    /** Pinch pacing must never occupy the lane used by clicks, keys, and task actions. */
    private val pinchWorker = Executors.newSingleThreadExecutor { Thread(it, "lateral-pinch") }
    private val pendingPinch = AtomicReference<PinchRequest?>(null)
    private val pinchDrainScheduled = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "lateral-privileged-sched")
    }

    private data class PinchRequest(
        val session: SessionFence.Session<HelperSession>,
        val displayId: Int,
        val centerX: Int,
        val centerY: Int,
        val fromSpan: Int,
        val toSpan: Int,
        val durationMs: Int,
    )

    /** Call once, from the Application. Computes the initial state from persisted identity. */
    fun init(context: Context) {
        if (appContext != null) { control.execute { notifyListeners() }; return }
        appContext = context.applicationContext
        activation.selectAfterStop(PrivilegePreferences.selected(context), Result.success(Unit))
        adbBackend = AdbPrivilegeBackend(context.applicationContext)
        shizukuBackend = ShizukuPrivilegeBackend(context.applicationContext)
        // USB provider admission and READY publication are serialized on this worker too.
        // Queue initialization behind any admission already accepted before Application.onCreate.
        control.execute {
            val usbInProgress = usbAttempt?.valid() == true
            val usbReady = usbLease?.let {
                it.binder == service?.asBinder() && !it.attempt.cancelled &&
                    it.attempt.ready.getNow(false) == true && it.binder.pingBinder()
            } == true
            if (!isBridgeReady() && !usbInProgress && !usbReady) lifecycleState = State.NEEDS_PAIRING
            notifyListeners()
        }
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    private fun backendCallback(backend: PrivilegeBackendId) = object : PrivilegeBackend.Callback {
        override fun onConnecting(attemptId: Long) {
            control.execute {
                if (activation.accepts(backend, attemptId)) setState(State.DISCOVERING)
            }
        }

        override fun onConnected(attemptId: Long, binder: IBinder) {
            control.execute {
                if (!activation.accepts(backend, attemptId) || intentionallyStopped) {
                    return@execute
                }
                activation.finished(attemptId)
                if (service?.asBinder() != binder) setState(State.STARTING)
                onBinderOnWorker(binder, null)
                completeAdbHandoff(attemptId, binder)
            }
        }

        override fun onUnavailable(attemptId: Long, reason: UnavailableReason) {
            control.execute {
                if (!activation.accepts(backend, attemptId) || intentionallyStopped) return@execute
                activation.finished(attemptId)
                unavailableReason = reason
                currentSession?.takeIf { it.value.backend == backend && it.value.transport != BridgeTransport.USB }?.let {
                    reportSessionLost(it, reason)
                }
                setState(when (reason) {
                    UnavailableReason.DEVELOPER_OPTIONS_REQUIRED -> State.NEEDS_DEVELOPER_OPTIONS
                    UnavailableReason.PAIRING_REQUIRED -> State.NEEDS_PAIRING
                    UnavailableReason.INCOMPATIBLE_RUNTIME -> State.UNSUPPORTED
                    UnavailableReason.SHIZUKU_NOT_INSTALLED -> State.SHIZUKU_NOT_INSTALLED
                    UnavailableReason.SHIZUKU_NOT_RUNNING -> State.SHIZUKU_NOT_RUNNING
                    UnavailableReason.AUTHORIZATION_REQUIRED -> State.AUTHORIZATION_REQUIRED
                    UnavailableReason.AUTHORIZATION_DENIED -> State.AUTHORIZATION_DENIED
                    UnavailableReason.CONNECTION_LOST -> State.CONNECTION_LOST
                    UnavailableReason.START_TIMEOUT -> State.START_TIMEOUT
                    else -> State.NEEDS_WIRELESS_DEBUGGING
                })
            }
        }
    }
    @Volatile private var navigationObserverReady = false
    fun setPhoneNavigationObserverReady(ready: Boolean) {
        navigationObserverReady = ready
        call(Unit) { it.setPhoneNavigationObserverReady(ready) }
    }
    fun cancelPhoneFocusRecovery() {
        // Direct Binder call: never queue Home behind a slow hosted-app launch.
        call(Unit) { it.clearPhoneTouchGuard() }
    }
    fun allowPhoneFocusRecovery(taskId: Int) {
        call(Unit) { it.allowPhoneFocusRecovery(taskId) }
    }
    /** Validate the live helper, not just a potentially stale UI state. */
    fun isBridgeReady(): Boolean = state == State.READY &&
        runCatching { service?.asBinder()?.pingBinder() == true }.getOrDefault(false)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun setState(next: State) {
        if (next == lifecycleState) return
        Log.i(TAG, "state: $lifecycleState -> $next")
        lifecycleState = next
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
        control.execute {
            if (intentionallyStopped || state == State.STOPPING) return@execute
            if (usbAttempt?.valid() == true) return@execute
            if (state == State.READY && service != null) return@execute
            if (state == State.DISCOVERING || state == State.CONNECTING || state == State.STARTING) return@execute
            val backend = selectedActivationBackend() ?: return@execute
            val attemptId = activation.begin() ?: return@execute
            setState(State.DISCOVERING)
            backend.start(attemptId, backendCallback(backend.id))
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
        if (selectedBackend != PrivilegeBackendId.WIRELESS_ADB) { done(false); return }
        val backend = adbBackend
        if (backend == null) { done(false); return }
        backend.activate(pairingCode) { paired ->
            val accepted = paired && selectedBackend == PrivilegeBackendId.WIRELESS_ADB
            done(accepted)
            if (accepted) ensureRunning()
        }
    }

    fun requestShizukuAuthorization() {
        if (selectedBackend == PrivilegeBackendId.SHIZUKU) shizukuBackend?.requestAuthorization()
    }

    fun retryHelper() {
        control.execute { intentionallyStopped = false; ensureRunning() }
    }

    fun selectBackend(next: PrivilegeBackendId, done: (Result<Unit>) -> Unit = {}) =
        stopOrSwitch(next, restart = true, done)

    fun restartHelper(done: (Result<Unit>) -> Unit = {}) = stopOrSwitch(selectedBackend, true, done)
    fun stopHelper(done: (Result<Unit>) -> Unit = {}) = stopOrSwitch(selectedBackend, false, done)

    private fun stopOrSwitch(next: PrivilegeBackendId, restart: Boolean, done: (Result<Unit>) -> Unit) {
        control.execute {
            if (state == State.STOPPING) { mainHandler.post { done(Result.failure(IllegalStateException("Helper is stopping"))) }; return@execute }
            intentionallyStopped = true
            usbAttempt?.cancel()
            usbCandidate?.let(::rejectOffer)
            usbCandidate = null
            activation.invalidate()
            setState(State.STOPPING)
            val current = service
            val finish: (Result<Unit>) -> Unit = { outcome -> control.execute {
                if (outcome.isSuccess) {
                    current?.let { helper -> serviceDeath?.let { runCatching { helper.asBinder().unlinkToDeath(it, 0) } } }
                    sessions.invalidate(currentSession)
                    pendingPinch.set(null)
                    usbLease?.attempt?.cancel()
                    usbLease = null
                    acceptedTransport = BridgeTransport.NONE
                    pendingPinch.set(null)
                    activation.selectAfterStop(next, outcome)
                    appContext?.let { PrivilegePreferences.select(it, next) }
                    unavailableReason = null
                    setState(State.STOPPED)
                    intentionallyStopped = !restart
                    if (restart) ensureRunning()
                } else {
                    intentionallyStopped = false
                    setState(if (currentSession?.value?.binder?.pingBinder() == true) State.READY else State.CONNECTION_LOST)
                }
                mainHandler.post { done(outcome) }
            } }
            val backend = selectedActivationBackend()
            if (acceptedTransport == BridgeTransport.USB) {
                val result = runCatching { HelperShutdown.awaitDeath(current?.asBinder()) { current?.exit() } }
                if (result.isFailure) finish(result) else backend?.stop(finish) ?: finish(Result.success(Unit))
            } else backend?.stop(finish) ?: finish(Result.success(Unit))
        }
    }

    // Owned by the serial service worker; attempt cancellation is immediately visible.
    private var usbAttempt: UsbBootstrapAttempt? = null
    @Volatile private var visibleUsbAttempt: UsbBootstrapAttempt? = null
    private var usbCandidate: IBinder? = null
    private var usbShellAccepted = false
    private val serviceGeneration get() = sessions.generation
    private val serviceDeath get() = currentSession?.value?.deathRecipient
    private class UsbLease(val attempt: UsbBootstrapAttempt, val binder: IBinder,
        val generation: Long, val owner: IBinder = Binder())
    @Volatile private var usbLease: UsbLease? = null
    enum class BridgeTransport { NONE, USB, WIRELESS, SHIZUKU }
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

    internal fun maySweepOrphanHelpers(): Boolean = selectedBackend == PrivilegeBackendId.WIRELESS_ADB && visibleUsbAttempt?.valid() != true && usbLease?.let {
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
        control.execute {
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
                control.execute { if (usbAttempt === attempt) cancelUsb(attempt) }
            }.also { mainHandler.postDelayed(it, delay) }
        }
    }

    internal fun cancelUsb(attempt: UsbBootstrapAttempt) {
        attempt.cancel()
        control.execute {
            val lease = usbLease
            if (lease?.attempt === attempt) {
                usbLease = null
                if (BinderOfferPolicy.currentDeath(service?.asBinder(), lease.binder,
                        serviceGeneration, lease.generation)) {
                    serviceDeath?.let { runCatching { lease.binder.unlinkToDeath(it, 0) } }
                    sessions.invalidate(currentSession)
                    pendingPinch.set(null)
                    acceptedTransport = BridgeTransport.NONE
                    runCatching { IPrivilegedService.Stub.asInterface(lease.binder).exit() }
                    setImeRoutingState(ImeRoutingState.UNAVAILABLE)
                    setState(State.CONNECTION_LOST)
                    if (!intentionallyStopped && selectedBackend == PrivilegeBackendId.WIRELESS_ADB) ensureRunning()
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
        control.execute {
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

    private val adbHandoffs = java.util.concurrent.ConcurrentHashMap<Long, CompletableFuture<Bundle?>>()
    internal fun offerAdbBinder(binder: IBinder, attemptId: Long): CompletableFuture<Bundle?> {
        val reply = CompletableFuture<Bundle?>()
        if (selectedBackend != PrivilegeBackendId.WIRELESS_ADB || attemptId != activeBackendAttempt || intentionallyStopped) {
            adbBackend?.offerBinder(attemptId, binder)
            reply.complete(null)
            return reply
        }
        val pending = adbHandoffs.putIfAbsent(attemptId, reply)
        if (pending != null) return pending
        reply.whenComplete { _, _ ->
            adbHandoffs.remove(attemptId, reply)
            if (reply.isCancelled) control.execute {
                currentSession?.takeIf { it.value.binder == binder && activeBackendAttempt == attemptId }?.let {
                    reportSessionLost(it)
                    adbBackend?.stop { }
                }
            }
        }
        adbBackend?.offerBinder(attemptId, binder) ?: reply.complete(null)
        return reply
    }
    private fun completeAdbHandoff(attemptId: Long, binder: IBinder) {
        val owned = currentSession?.takeIf { it.value.binder == binder && it.value.transport == BridgeTransport.WIRELESS && isBridgeReady() }
        adbHandoffs[attemptId]?.complete(owned?.let { session -> Bundle().apply {
            putBoolean(BinderReceiverProvider.EXTRA_ACCEPTED, true)
            putBinder(BinderReceiverProvider.EXTRA_OWNER, session.value.owner)
        } })
    }


    /** Never waits on the serial worker; the provider bounds its own Binder-thread wait. */
    internal fun offerUsbBinder(binder: IBinder, token: String): CompletableFuture<Bundle?> {
        val reply = CompletableFuture<Bundle?>()
        control.execute {
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
                attempt.ready.whenComplete { _, _ -> control.execute { acknowledge() } }
            } else acknowledge()
            reply.whenComplete { _, _ ->
                if (reply.isCancelled) control.execute {
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
        val candidate = AtomicReference<SessionFence.Session<HelperSession>?>(null)
        val death = IBinder.DeathRecipient { candidate.get()?.let { reportSessionLost(it) } }
        val helper = IPrivilegedService.Stub.asInterface(binder)
        try {
            check(binder.pingBinder())
            binder.linkToDeath(death, 0)
            val initialization = initializer.submit {
                check(helper.protocolVersion == PrivilegedProtocol.VERSION) { "Incompatible helper protocol" }
                helper.setPhoneNavigationObserverReady(navigationObserverReady)
                helper.setBeastMediaRoutingEnabled(false)
                if (hotkeyMonitoringRequested) helper.setHotkeyListener(hotkeyListenerFor(binder))
                check(binder.pingBinder())
            }
            try { initialization.get(8, TimeUnit.SECONDS) } finally { initialization.cancel(true) }
            if (usbToken != null) check(attempt?.matches(usbToken) == true)
        } catch (failure: Exception) {
            Log.w(TAG, "Helper validation failed", failure)
            runCatching { binder.unlinkToDeath(death, 0) }
            rejectOffer(binder)
            if (usbToken != null && attempt != null) cancelUsb(attempt)
            else {
                if (selectedBackend == PrivilegeBackendId.SHIZUKU) shizukuBackend?.helperLost(binder)
                else adbBackend?.helperLost(binder)
                val timedOut = failure is java.util.concurrent.TimeoutException
                unavailableReason = if (timedOut) UnavailableReason.START_TIMEOUT else UnavailableReason.INCOMPATIBLE_RUNTIME
                setState(if (timedOut) State.START_TIMEOUT else State.UNSUPPORTED)
            }
            return
        }
        val previous = currentSession
        val transport = if (usbToken != null) BridgeTransport.USB else
            if (selectedBackend == PrivilegeBackendId.SHIZUKU) BridgeTransport.SHIZUKU else BridgeTransport.WIRELESS
        val owner = HelperSession(if (usbToken != null) PrivilegeBackendId.WIRELESS_ADB else selectedBackend,
            transport, binder, helper, death)
        val publish: () -> Unit = {
            val accepted = sessions.install(owner)
            candidate.set(accepted)
            acceptedTransport = transport
            pendingPinch.set(null)
            if (usbToken != null) {
                usbLease = UsbLease(checkNotNull(attempt), binder, accepted.generation)
                usbDeadline?.let { mainHandler.removeCallbacks(it) }
                usbDeadline = null
                usbAttempt = null
                usbCandidate = null
                usbShellAccepted = false
            } else usbLease = null
        }
        if (usbToken != null && attempt?.commit(publish) != true) {
            runCatching { binder.unlinkToDeath(death, 0) }
            rejectOffer(binder)
            attempt?.let(::cancelUsb)
            return
        }
        if (usbToken == null) publish()
        previous?.let { runCatching { it.value.binder.unlinkToDeath(it.value.deathRecipient, 0) } }
        setImeRoutingState(ImeRoutingState.CHECKING)
        unavailableReason = null
        if (!binder.pingBinder()) {
            attempt?.ready?.complete(false)
            candidate.get()?.let { reportSessionLost(it) }
            return
        }
        if (usbToken != null && attempt != null) {
            if (!attempt.commit { setState(State.READY); attempt.ready.complete(true) }) cancelUsb(attempt)
            else if (previous != null && previous.value.binder != binder) runCatching { previous.value.service.exit() }
        } else setState(State.READY)
        notifyListeners()
    }

    /** Clear ownership immediately, even while the lifecycle lane is waiting on a remote call. */
    private fun reportSessionLost(expected: SessionFence.Session<HelperSession>,
        reason: UnavailableReason = UnavailableReason.CONNECTION_LOST) {
        if (!sessions.invalidate(expected)) return
        pendingPinch.set(null)
        control.execute {
            val lost = expected.value
            runCatching { lost.binder.unlinkToDeath(lost.deathRecipient, 0) }
            usbLease?.takeIf { it.binder == lost.binder && it.generation == expected.generation }?.let {
                it.attempt.cancel(); usbLease = null
            }
            if (lost.backend == PrivilegeBackendId.SHIZUKU) shizukuBackend?.helperLost(lost.binder)
            else adbBackend?.helperLost(lost.binder)
            if (currentSession != null) return@execute
            acceptedTransport = BridgeTransport.NONE
            setImeRoutingState(ImeRoutingState.UNAVAILABLE)
            if (intentionallyStopped || state == State.STOPPING) return@execute
            unavailableReason = reason
            setState(when (reason) {
                UnavailableReason.AUTHORIZATION_DENIED -> State.AUTHORIZATION_DENIED
                UnavailableReason.AUTHORIZATION_REQUIRED -> State.AUTHORIZATION_REQUIRED
                else -> State.CONNECTION_LOST
            })
            if (selectedBackend == PrivilegeBackendId.WIRELESS_ADB) ensureRunning()
        }
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
    private fun hotkeyListenerFor(binder: IBinder) = object : IPrivilegedHotkeyListener.Stub() {
        private fun dispatch(action: () -> Unit) {
            val expected = currentSession?.takeIf { it.value.binder == binder } ?: return
            mainHandler.post { if (sessions.matches(expected)) action() }
        }
        override fun onHotkey(code: Int) = dispatch { hotkeyHandler?.invoke(code) }
        override fun onMouseDelta(dx: Int, dy: Int, wheel: Int) = dispatch { mouseDeltaHandler?.invoke(dx, dy, wheel) }
        override fun onMouseButton(code: Int, pressed: Boolean) = dispatch { mouseButtonHandler?.invoke(code, pressed) }
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
        call(Unit) { it.setHotkeyListener(if (enabled) hotkeyListenerFor(it.asBinder()) else null) }
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
    fun rescanGlassesUsb() = onWorker { it.rescanGlassesUsb() }

    fun launchApp(displayId: Int, packageName: String, activityName: String) =
        onWorker { it.launchOnDisplay(displayId, packageName, activityName) }

    fun createHostedVirtualDisplay(name: String, width: Int, height: Int, densityDpi: Int,
        surface: Surface, destroyContentOnRemoval: Boolean = true): HostedDisplayResult {
        val expected = currentSession
        val result = invoke(expected, -1) {
            it.createVirtualDisplayWithPolicy(name, width, height, densityDpi, surface, destroyContentOnRemoval)
        }
        if (!sessions.matches(expected)) return HostedDisplayResult.HelperUnavailable
        return when {
            result >= 0 -> {
                imeRoutingError = ""
                setImeRoutingState(ImeRoutingState.AVAILABLE)
                HostedDisplayResult.Ready(result)
            }
            result == IME_ROUTING_UNAVAILABLE -> {
                imeRoutingError = invoke(expected, "") { it.imeRoutingError }
                setImeRoutingState(ImeRoutingState.UNAVAILABLE)
                HostedDisplayResult.KeyboardRoutingUnavailable
            }
            else -> HostedDisplayResult.DisplayCreationFailed
        }
    }

    fun ensureHostedDisplayImeRouting(displayId: Int): Boolean {
        val expected = currentSession
        val available = invoke(expected, false) {
            it.applyDefaultDisplayImePolicy(displayId) && it.getDisplayImePolicy(displayId) == DISPLAY_IME_POLICY_FALLBACK_DISPLAY
        }
        if (!sessions.matches(expected)) return false
        imeRoutingError = if (available) "" else invoke(expected, "") { it.imeRoutingError }
        setImeRoutingState(if (available) ImeRoutingState.AVAILABLE else ImeRoutingState.UNAVAILABLE)
        return available
    }

    fun recentTasks(): List<RecentTask> = call(emptyList()) { helper ->
        helper.recentTasks.orEmpty().mapNotNull { encoded ->
            val fields = encoded.split('\t')
            if (fields.size !in 5..6) return@mapNotNull null
            RecentTask(fields[0].toInt(), fields[1].toLong(), fields[2].toBooleanStrictOrNull() ?: false,
                fields[3], fields[4], fields.getOrElse(5) { "" })
        }
    }

    private fun taskSnapshotResponse(helper: IPrivilegedService): Pair<Boolean, List<TaskSnapshot>> {
            val encodedSnapshots = helper.taskSnapshots.orEmpty()
            if (encodedSnapshots.any { it.startsWith("!error\t") }) return false to emptyList()
            return true to encodedSnapshots.mapNotNull { encoded ->
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
    }

    fun taskSnapshots(): List<TaskSnapshot> = call(emptyList()) { taskSnapshotResponse(it).second }
    fun queryTaskSnapshots(callback: (List<TaskSnapshot>?) -> Unit) = query(null, callback) {
        val (success, tasks) = taskSnapshotResponse(it)
        tasks.takeIf { success }
    }

    fun rewriteRecentTaskOrder(taskIdsNewestFirst: IntArray, displayIds: IntArray, callback: (Boolean) -> Unit) {
        val tasks = taskIdsNewestFirst.copyOf()
        val displays = displayIds.copyOf()
        query(false, callback) { it.rewriteRecentTaskOrder(tasks, displays) }
    }
    fun startRecentTaskOnDisplay(taskId: Int, displayId: Int): Boolean = call(false) { it.startRecentTaskOnDisplay(taskId, displayId) }
    fun restorePhoneTaskIfStillHome(taskId: Int): Boolean = call(false) { it.restorePhoneTaskIfStillHome(taskId) }
    fun restorePhoneTaskIfStillHomeAsync(taskId: Int) = onWorker { it.restorePhoneTaskIfStillHome(taskId) }
    fun focusPhoneTaskAsync(taskId: Int, callback: (Boolean) -> Unit) = query(false, callback) {
        it.startRecentTaskOnDisplay(taskId, android.view.Display.DEFAULT_DISPLAY)
    }
    fun restoreTaskPreservingPhoneFocus(taskId: Int, beastDisplayId: Int, phoneTaskId: Int): FocusRestoreResult =
        call(FocusRestoreResult.HELPER_UNAVAILABLE) {
            FocusRestoreResult.fromCode(it.restoreTaskPreservingPhoneFocus(taskId, beastDisplayId, phoneTaskId))
        }
    fun restoreTaskPreservingPhoneFocusAsync(taskId: Int, beastDisplayId: Int, phoneTaskId: Int,
        callback: (FocusRestoreResult) -> Unit) = query(FocusRestoreResult.HELPER_UNAVAILABLE, callback) {
        FocusRestoreResult.fromCode(it.restoreTaskPreservingPhoneFocus(taskId, beastDisplayId, phoneTaskId))
    }
    fun removeTask(taskId: Int): Boolean = call(false) { it.removeTask(taskId) }
    fun removeTaskAsync(taskId: Int, callback: (Boolean) -> Unit) = query(false, callback) { it.removeTask(taskId) }
    fun releaseVirtualDisplay(displayId: Int, helperGeneration: Long = this.helperGeneration) {
        val expected = currentSession?.takeIf { it.generation == helperGeneration } ?: return
        worker.execute { invoke(expected, Unit) { it.releaseVirtualDisplay(displayId) } }
    }
    fun resizeVirtualDisplay(displayId: Int, width: Int, height: Int, densityDpi: Int) = onWorker {
        it.resizeVirtualDisplay(displayId, width, height, densityDpi)
    }
    fun clearDisplayOverrideSize(displayId: Int) = onWorker { it.clearDisplayOverrideSize(displayId) }
    fun setUserPreferredDisplayMode(displayId: Int, width: Int, height: Int, refreshRate: Float,
        callback: (Boolean) -> Unit) = query(false, callback) { it.setUserPreferredDisplayMode(displayId, width, height, refreshRate) }
    fun setBeastMediaRoutingEnabled(enabled: Boolean) = onWorker { it.setBeastMediaRoutingEnabled(enabled) }
    fun setVirtualDisplaySurface(displayId: Int, surface: Surface) = onWorker { it.setVirtualDisplaySurface(displayId, surface) }
    fun scrollOnDisplay(displayId: Int, x: Int, y: Int, vScroll: Float) = onWorker { it.injectScroll(displayId, x, y, vScroll) }
    fun scrollOnDisplayImmediate(displayId: Int, x: Int, y: Int, vScroll: Float) { call(Unit) { it.injectScroll(displayId, x, y, vScroll) } }

    fun pinchOnDisplay(displayId: Int, centerX: Int, centerY: Int, fromSpan: Int, toSpan: Int, durationMs: Int) {
        val expected = currentSession ?: return
        pendingPinch.set(PinchRequest(expected, displayId, centerX, centerY, fromSpan, toSpan, durationMs))
        schedulePinchDrain()
    }
    private fun schedulePinchDrain() {
        if (!pinchDrainScheduled.compareAndSet(false, true)) return
        pinchWorker.execute {
            try {
                while (true) {
                    val request = pendingPinch.getAndSet(null) ?: break
                    invoke(request.session, Unit) {
                        it.pinchOnDisplay(request.displayId, request.centerX, request.centerY,
                            request.fromSpan, request.toSpan, request.durationMs)
                    }
                }
            } finally {
                pinchDrainScheduled.set(false)
                if (pendingPinch.get() != null) schedulePinchDrain()
            }
        }
    }
    fun injectMouse(displayId: Int, x: Int, y: Int, action: Int, buttonState: Int = 0) =
        onWorker { it.injectMouse(displayId, x, y, action, buttonState) }
    fun clickMouse(displayId: Int, x: Int, y: Int, button: Int) = onWorker { it.clickMouse(displayId, x, y, button) }
    fun clickTouch(displayId: Int, x: Int, y: Int) = onWorker { it.clickTouch(displayId, x, y) }
    fun moveNativePointer(displayId: Int, dx: Int, dy: Int, wheel: Int = 0) = onWorker { it.moveNativePointer(displayId, dx, dy, wheel) }
    fun nativePointerButton(displayId: Int, code: Int, pressed: Boolean) = onWorker { it.nativePointerButton(displayId, code, pressed) }
    fun injectTouch(displayId: Int, x: Int, y: Int, action: Int) = onWorker { it.injectTouch(displayId, x, y, TouchActionProtocol.encode(action)) }
    fun key(displayId: Int, keyCode: Int) = onWorker { it.key(displayId, keyCode) }
    fun nativeKey(displayId: Int, event: KeyEvent) {
        val copy = KeyEvent(event)
        onWorker { it.nativeKey(displayId, copy.action, copy.keyCode, copy.repeatCount, copy.metaState, copy.downTime, copy.eventTime) }
    }
    fun text(displayId: Int, value: String) = onWorker { it.text(displayId, value) }
    fun queryFocusedEditor(displayId: Int, callback: (FocusedEditorInfo?) -> Unit) = query(null, callback) {
        val raw = it.getFocusedEditorInfo(displayId)
        if (raw != null && raw.size >= 3 && raw[0] == 1) FocusedEditorInfo(raw[1], raw[2]) else null
    }
    fun beginSessionInputMethod(imeId: String, ownerToken: IBinder, callback: (SessionImeActivation) -> Unit) =
        query(SessionImeActivation(SessionImeResult.HELPER_UNAVAILABLE, "", ""), callback) {
            val raw = it.beginSessionInputMethod(imeId, ownerToken)
            if (raw != null && raw.size >= 3) SessionImeActivation(SessionImeResult.fromCode(raw[0].toIntOrNull() ?: -2), raw[1], raw[2])
            else SessionImeActivation(SessionImeResult.HELPER_UNAVAILABLE, "", "")
        }
    fun restoreSessionInputMethod(sessionImeId: String, previousImeId: String, callback: ((SessionImeResult) -> Unit)? = null) =
        query(SessionImeResult.HELPER_UNAVAILABLE, { callback?.invoke(it) }) {
            SessionImeResult.fromCode(it.restoreSessionInputMethod(sessionImeId, previousImeId))
        }
    fun getImeClientSnapshot(callback: (ImeClientSnapshot?) -> Unit) = query(null, callback) {
        val fields = it.imeClientSnapshot?.split('|')
        if (fields != null && fields.size >= 7 && fields[0] == "v1") ImeClientSnapshot(fields[1],
            fields[2].toIntOrNull() ?: -1, fields[3].toIntOrNull() ?: -1, fields[4],
            fields[5].toIntOrNull() ?: 0, fields[6].toIntOrNull() ?: 0) else null
    }
    fun forceStop(packageName: String) = onWorker { it.forceStop(packageName) }
    fun displayHasActivity(displayId: Int): Boolean = call(false) { it.displayHasActivity(displayId) }
    fun queryDisplayHasBackStack(displayId: Int, callback: (Boolean) -> Unit) = query(false, callback) { it.displayHasBackStack(displayId) }
    fun queryDisplayHasPackage(displayId: Int, packageName: String, callback: (Boolean) -> Unit) =
        query(true, callback) { it.displayHasPackage(displayId, packageName) }
    fun restoreRecentTaskOnDisplay(taskId: Int, displayId: Int, callback: (Boolean) -> Unit) =
        query(false, callback) { it.restoreRecentTaskOnDisplay(taskId, displayId) }
    fun sendMediaPause(displayId: Int) = onWorker { it.key(displayId, KeyEvent.KEYCODE_MEDIA_PAUSE) }
    fun sendBack(displayId: Int, onEmptied: () -> Unit) {
        val expected = currentSession ?: return
        worker.execute { invoke(expected, Unit) { it.key(displayId, KeyEvent.KEYCODE_BACK) } }
        scheduler.schedule({
            val empty = invoke(expected, false) { !it.displayHasActivity(displayId) }
            mainHandler.post { if (empty && sessions.matches(expected)) onEmptied() }
        }, BACK_SETTLE_MS, TimeUnit.MILLISECONDS)
    }

    private fun <T> invoke(expected: SessionFence.Session<HelperSession>?, unavailable: T,
        operation: (IPrivilegedService) -> T): T {
        if (!sessions.matches(expected) || state != State.READY) return unavailable
        return try {
            val result = operation(checkNotNull(expected).value.service)
            if (sessions.matches(expected) && state == State.READY) result else unavailable
        } catch (failure: Exception) {
            if (expected != null) {
                if (failure is DeadObjectException || !expected.value.binder.pingBinder()) reportSessionLost(expected)
                else control.execute { Log.w(TAG, "Privileged operation failed in generation ${expected.generation}", failure) }
            }
            unavailable
        }
    }
    private fun <T> call(unavailable: T, operation: (IPrivilegedService) -> T): T = invoke(currentSession, unavailable, operation)
    private fun onWorker(operation: (IPrivilegedService) -> Unit) {
        val expected = currentSession ?: return
        worker.execute { invoke(expected, Unit, operation) }
    }
    private fun <T> query(unavailable: T, callback: (T) -> Unit, operation: (IPrivilegedService) -> T) {
        val expected = currentSession
        worker.execute {
            val result = invoke(expected, unavailable, operation)
            mainHandler.post { callback(if (sessions.matches(expected) && state == State.READY) result else unavailable) }
        }
    }

    private const val BACK_SETTLE_MS = 800L
    private const val DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1
    private const val IME_ROUTING_UNAVAILABLE = -2
}
