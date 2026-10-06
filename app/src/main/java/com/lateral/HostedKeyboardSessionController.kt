package com.lateral

import android.content.ComponentName
import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.lateral.beast.HostedTextInputSession
import com.lateral.privileged.PrivilegedService
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.embedded.EmbeddedImeRuntime

/** Owns the temporary, exact-editor FlorisBoard IME lease. Contains no entered text. */
class HostedKeyboardSessionController(
    context: Context,
    private val onStateChanged: (State, String?) -> Unit,
) {
    enum class State { IDLE, ACTIVATING, ACTIVE, RESTORING, UNAVAILABLE }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val ownerToken = Binder()
    private val imeId = ComponentName(appContext, FlorisImeService::class.java).flattenToString()
    private var generation = 0L
    private var target: HostedTextInputSession.Target? = null
    private var previousImeId = ""
    private var deadline = 0L
    private var state = State.IDLE

    private val embeddedListener: () -> Unit = {
        handler.post { if (state == State.ACTIVATING) verifyTarget() }
        Unit
    }

    init {
        EmbeddedImeRuntime.addListener(embeddedListener)
    }

    fun recoverStaleSession(complete: (() -> Unit)? = null) {
        val stalePrevious = prefs.getString(KEY_PREVIOUS_IME, "").orEmpty()
        val expected = generation
        if (stalePrevious.isBlank()) { complete?.invoke(); return }
        PrivilegedService.restoreSessionInputMethod(imeId, stalePrevious) { result ->
            if (expected != generation) return@restoreSessionInputMethod
            confirmRestoration(result, expected) { complete?.invoke() }
        }
    }

    private fun confirmRestoration(result: PrivilegedService.SessionImeResult, expected: Long,
        complete: (Boolean) -> Unit) {
        if (expected != generation) return
        if (com.lateral.privileged.ImeRecoveryPolicy.confirmed(result.code, null, imeId)) {
            clearRecoveryRecord()
            previousImeId = ""
            complete(true)
            return
        }
        // A failed fallback is not proof of restoration. Verify the user's selected IME.
        PrivilegedService.getImeClientSnapshot { snapshot ->
            if (expected != generation) return@getImeClientSnapshot
            val restored = com.lateral.privileged.ImeRecoveryPolicy.confirmed(result.code, snapshot?.selectedImeId, imeId)
            if (restored) { clearRecoveryRecord(); previousImeId = "" }
            complete(restored)
        }
    }

    fun begin(next: HostedTextInputSession.Target) {
        generation++
        target = next
        updateState(State.ACTIVATING, "Connecting keyboard…")
        val requestGeneration = generation
        recoverStaleSession {
            if (requestGeneration != generation || target != next) return@recoverStaleSession
            if (prefs.getString(KEY_PREVIOUS_IME, "").orEmpty().isNotBlank()) {
                fail("Previous keyboard session still needs recovery", restore = false)
            } else activate(next, requestGeneration)
        }
    }

    private fun activate(next: HostedTextInputSession.Target, requestGeneration: Long) {
        PrivilegedService.beginSessionInputMethod(imeId, ownerToken) { activation ->
            if (requestGeneration != generation || target != next) return@beginSessionInputMethod
            if (activation.result != PrivilegedService.SessionImeResult.SUCCESS) {
                fail("Keyboard unavailable (${activation.result.name.lowercase()})")
                return@beginSessionInputMethod
            }
            previousImeId = activation.previousImeId
            if (previousImeId.isNotBlank()) {
                prefs.edit().putString(KEY_PREVIOUS_IME, previousImeId).apply()
            }
            // IME selection may include a cold FlorisBoard process/theme startup. Give
            // editor verification its own window instead of consuming it while the
            // privileged helper is still enabling and selecting the IME.
            deadline = SystemClock.uptimeMillis() + ACTIVATION_TIMEOUT_MS
            verifyTarget()
        }
    }

    fun end(reason: String = "session ended") {
        generation++
        val expected = generation
        target = null
        handler.removeCallbacksAndMessages(null)
        val restoreId = previousImeId.ifBlank { prefs.getString(KEY_PREVIOUS_IME, "").orEmpty() }
        if (state == State.IDLE && restoreId.isBlank()) return
        updateState(State.RESTORING, null)
        if (restoreId.isBlank()) {
            val switched = EmbeddedImeRuntime.switchToPreviousInputMethod()
            Log.i(TAG, "session end without recovery record reason=$reason fallback=$switched")
            updateState(State.IDLE, null)
            return
        }
        PrivilegedService.restoreSessionInputMethod(imeId, restoreId) { result ->
            if (expected != generation) return@restoreSessionInputMethod
            if (result == PrivilegedService.SessionImeResult.HELPER_UNAVAILABLE) EmbeddedImeRuntime.switchToPreviousInputMethod()
            confirmRestoration(result, expected) { restored ->
                updateState(if (restored) State.IDLE else State.UNAVAILABLE,
                    if (restored) null else "Keyboard restoration will retry after helper recovery")
            }
        }
    }

    fun onHelperUnavailable() {
        generation++
        target = null
        handler.removeCallbacksAndMessages(null)
        if (state == State.IDLE && prefs.getString(KEY_PREVIOUS_IME, "").orEmpty().isBlank()) return
        EmbeddedImeRuntime.switchToPreviousInputMethod()
        updateState(State.UNAVAILABLE, "Keyboard helper unavailable")
    }

    fun destroy() {
        end("controller destroyed")
        EmbeddedImeRuntime.removeListener(embeddedListener)
    }

    private fun verifyTarget() {
        val expected = target ?: return
        val requestGeneration = generation
        val embedded = EmbeddedImeRuntime.editorSession.value
        if (embedded == null || embedded.packageName != expected.packageName) {
            retryVerification(requestGeneration)
            return
        }
        PrivilegedService.getImeClientSnapshot { snapshot ->
            if (requestGeneration != generation || target != expected) return@getImeClientSnapshot
            val valid = snapshot != null && snapshot.selectedImeId == imeId &&
                snapshot.displayId == expected.displayId &&
                snapshot.taskId == expected.androidTaskId &&
                snapshot.packageName == expected.packageName
            if (valid) {
                updateState(State.ACTIVE, null)
            } else {
                retryVerification(requestGeneration)
            }
        }
    }

    private fun retryVerification(requestGeneration: Long) {
        if (requestGeneration != generation) return
        if (SystemClock.uptimeMillis() >= deadline) {
            fail("Keyboard could not verify the active Beast editor")
        } else {
            handler.postDelayed({
                if (requestGeneration == generation && state == State.ACTIVATING) verifyTarget()
            }, VERIFY_INTERVAL_MS)
        }
    }

    private fun fail(message: String, restore: Boolean = true) {
        Log.w(TAG, "$message targetTask=${target?.androidTaskId} display=${target?.displayId}")
        generation++
        val expected = generation
        target = null
        handler.removeCallbacksAndMessages(null)
        updateState(State.UNAVAILABLE, message)
        val restoreId = previousImeId.ifBlank { prefs.getString(KEY_PREVIOUS_IME, "").orEmpty() }
        if (restore && restoreId.isNotBlank()) {
            PrivilegedService.restoreSessionInputMethod(imeId, restoreId) {
                confirmRestoration(it, expected) { }
            }
        }
    }

    private fun clearRecoveryRecord() {
        prefs.edit().remove(KEY_PREVIOUS_IME).apply()
    }

    private fun updateState(next: State, detail: String?) {
        state = next
        onStateChanged(next, detail)
    }

    companion object {
        private const val TAG = "Lateral/HostedKeyboard"
        private const val PREFS = "hosted_keyboard_session"
        private const val KEY_PREVIOUS_IME = "previous_ime"
        private const val ACTIVATION_TIMEOUT_MS = 5_000L
        // The IME service is pre-warmed, so a short retry interval makes the keyboard
        // appear on the first valid onStartInput rather than waiting an extra frame.
        private const val VERIFY_INTERVAL_MS = 40L
    }
}
