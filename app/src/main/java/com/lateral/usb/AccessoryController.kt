package com.lateral.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.FileOutputStream
import java.util.UUID

/** Activity-owned USB connection, retaining transport and helper lease until detach/close. */
class AccessoryController(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val usb = this.context.getSystemService(UsbManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val probes = UsbBootstrapCoordinator(this.context.applicationInfo.sourceDir)
    private val permissionAction = "${this.context.packageName}.USB_PROBE.${UUID.randomUUID()}"
    private var registered = false
    private var closed = false
    private var session: Session? = null

    private class Session(val accessory: UsbAccessory) {
        val callbackUri: Uri = Uri.parse("lateral-usb://permission/${UUID.randomUUID()}")
        @Volatile var cancelled = false
        var permission: PendingIntent? = null
        var opening = false
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed) return
            val current = session ?: return
            val accessory = accessoryFrom(intent) ?: return
            if (accessory != current.accessory) return
            when (intent.action) {
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    cancelSession()
                    Log.i(TAG, "Accessory detached; probe cancelled")
                }
                permissionAction -> {
                    if (intent.data != current.callbackUri || current.opening) return
                    current.permission?.cancel()
                    current.permission = null
                    try {
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                            usb.hasPermission(accessory) && isAttached(accessory)) {
                            open(current)
                        } else {
                            cancelSession()
                            Log.w(TAG, "Accessory permission denied or accessory no longer attached")
                        }
                    } catch (e: Exception) {
                        cancelSession()
                        Log.w(TAG, "Accessory permission validation failed", e)
                    }
                }
            }
        }
    }

    fun start(intent: Intent?) {
        if (closed || registered) return
        try {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(permissionAction).apply {
                addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            if (!handleIntent(intent)) discover()
        } catch (e: Exception) {
            cancelSession()
            Log.w(TAG, "Accessory probe initialization failed", e)
        }
    }

    /** Only attachment intents consume MainActivity's existing intent routing. */
    fun handleIntent(intent: Intent?): Boolean {
        if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return false
        if (!closed && registered) {
            // An exported activity intent is only a hint: enumerate the actual USB service.
            discover()
        }
        return true
    }

    private fun discover() {
        try {
            val accessory = usb.accessoryList?.firstOrNull {
                AccessoryProtocol.matches(it.manufacturer, it.model, it.version)
            } ?: return
            if (session?.accessory == accessory) return
            cancelSession()
            val next = Session(accessory)
            session = next
            if (usb.hasPermission(accessory)) {
                open(next)
            } else {
                // Unique URI ties the callback to this attachment attempt, including reattachments.
                val callback = Intent(permissionAction).setPackage(context.packageName)
                    .setData(next.callbackUri)
                val flags = PendingIntent.FLAG_CANCEL_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                next.permission = PendingIntent.getBroadcast(context, 0, callback, flags)
                usb.requestPermission(accessory, next.permission)
                Log.i(TAG, "Accessory permission requested")
            }
        } catch (e: Exception) {
            cancelSession()
            Log.w(TAG, "Accessory discovery/permission failed", e)
        }
    }

    private fun isAttached(accessory: UsbAccessory): Boolean =
        usb.accessoryList?.any { it == accessory } == true

    private fun open(current: Session) {
        if (current.opening || current.cancelled) return
        current.opening = true
        probes.submit(open = {
            if (!isAttached(current.accessory) || !usb.hasPermission(current.accessory)) {
                throw IOException("Accessory detached or permission revoked")
            }
            val descriptor = usb.openAccessory(current.accessory)
                ?: throw IOException("openAccessory returned null")
            try {
                val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                val output = FileOutputStream(descriptor.fileDescriptor)
                object : ProbeTransport {
                    override val input = input
                    override val output = output
                    override fun close() {
                        // Input owns the PFD. Its close signals blocked Android I/O.
                        // Output borrows the same FileDescriptor and must never own a dup.
                        try { input.close() } finally { output.close() }
                    }
                }
            } catch (failure: Throwable) {
                descriptor.close()
                throw failure
            }
        }, result = { result ->
            main.post {
                if (!closed && session === current && !current.cancelled) {
                    result.fold(
                        { Log.i(TAG, "USB bootstrap completed with a live correlated Binder") },
                        { Log.w(TAG, "USB bootstrap failed or was not admitted", it) },
                    )
                    // Retain attachment identity to suppress duplicate attach intents.
                }
            }
        })
    }

    private fun cancelSession() {
        probes.cancel()
        session?.let {
            it.cancelled = true
            it.permission?.cancel()
            it.permission = null
        }
        session = null
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelSession()
        if (registered) {
            context.unregisterReceiver(receiver)
            registered = false
        }
        probes.close()
        main.removeCallbacksAndMessages(null)
    }

    @Suppress("DEPRECATION")
    private fun accessoryFrom(intent: Intent): UsbAccessory? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        else intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)

    private companion object {
        const val TAG = "AccessoryProbe"
    }
}
