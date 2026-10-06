package com.lateral.privileged

import com.lateral.BuildConfig

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Receives the [PrivilegedServer]'s Binder back from the shell-uid process and hands it to
 * the manager in the UxSpace app process.
 *
 * The server (started via `app_process`, runs as shell) cannot easily reach back into the
 * app — Binder hand-offs over a `ContentProvider.call` is the standard mechanism, since a
 * Binder survives a `Bundle` across the process boundary. Shizuku's own server uses the
 * same trick to publish itself to apps that use it.
 *
 * The provider is `exported` so that the shell-uid server can call it; we restrict callers
 * to the shell and the app's own uid.
 */
class BinderReceiverProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == METHOD_CAN_SWEEP_ORPHANS) {
            return Bundle().apply { putBoolean(EXTRA_CAN_SWEEP,
                Binder.getCallingUid() == Process.SHELL_UID && PrivilegedService.maySweepOrphanHelpers()) }
        }
        if (method == METHOD_ADMIT_ADB_ONLY) {
            val token = extras?.getString(EXTRA_USB_TOKEN)
            if (!UsbLifeline.allowsAdmission(Binder.getCallingUid(), token)) return null
            val helper = extras?.getBinder(EXTRA_BINDER) ?: return null
            val attempt = PrivilegedService.admitAdbOnly(checkNotNull(token), helper)
            return try {
                if (attempt.admitted.get(10, TimeUnit.SECONDS) && attempt.valid()) {
                    Bundle().apply { putBoolean(EXTRA_ACCEPTED, true) }
                } else {
                    PrivilegedService.cancelUsb(attempt)
                    Bundle().apply { putString(EXTRA_ERROR, attempt.refusalReason ?: "USB admission was refused") }
                }
            } catch (failure: Exception) {
                PrivilegedService.cancelUsb(attempt)
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                Bundle().apply { putString(EXTRA_ERROR, failure.message ?: "USB admission failed") }
            }
        }
        if (method != METHOD_SET_BINDER) return null
        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.SHELL_UID && callingUid != Process.myUid()) {
            Log.w(TAG, "rejecting setBinder call from uid $callingUid")
            return null
        }
        val binder = extras?.getBinder(EXTRA_BINDER)
        if (binder == null) {
            Log.w(TAG, "setBinder called with no binder")
            return null
        }
        Log.i(TAG, "received privileged binder from uid $callingUid")
        val usbToken = if (extras.containsKey(EXTRA_USB_TOKEN))
            extras.getString(EXTRA_USB_TOKEN) ?: "" else null
        if (usbToken == null) {
            val attemptId = extras?.getLong(EXTRA_STARTUP_ATTEMPT, 0L) ?: 0L
            if (attemptId == 0L) {
                Log.w(TAG, "rejecting ADB Binder without a startup attempt id")
                return null
            }
            PrivilegedService.offerAdbBinder(binder, attemptId)
            return null
        }
        val reply = PrivilegedService.offerUsbBinder(binder, usbToken)
        return try { reply.get(10, TimeUnit.SECONDS) } catch (failure: Exception) {
            reply.cancel(false)
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            Log.w(TAG, "USB ownership acknowledgement failed", failure)
            null
        }
    }

    // ContentProvider's other entry points are unused — this provider is a Binder hand-off
    // channel, not a data store.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** Variant-specific provider authority — `content://<applicationId>.privileged`. */
        val AUTHORITY: String = "${BuildConfig.APPLICATION_ID}.privileged"

        /** `ContentResolver.call` method name the server uses to hand its Binder over. */
        const val METHOD_SET_BINDER = "setBinder"
        const val METHOD_ADMIT_ADB_ONLY = "admitAdbOnly"
        const val METHOD_CAN_SWEEP_ORPHANS = "canSweepOrphans"

        /** Bundle key under which the Binder travels. */
        const val EXTRA_BINDER = "binder"
        const val EXTRA_USB_TOKEN = "usb_token"
        const val EXTRA_STARTUP_ATTEMPT = "startup_attempt"
        const val EXTRA_ACCEPTED = "usb_accepted"
        const val EXTRA_ERROR = "usb_error"
        const val EXTRA_CAN_SWEEP = "can_sweep_orphans"
        const val EXTRA_OWNER = "usb_owner"

        private const val TAG = "Lateral/Privileged"
    }
}
