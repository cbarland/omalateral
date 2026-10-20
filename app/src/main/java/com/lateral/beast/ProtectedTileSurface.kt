package com.lateral.beast

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.os.Binder
import android.os.Build
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewTreeObserver
import com.lateral.privileged.IProtectedDisplayListener
import com.lateral.privileged.PrivilegedService
import kotlin.math.ceil
import kotlin.math.floor

/** Native-only secure presentation below normal window chrome, never a pixel consumer. */
internal class ProtectedTileSurface(context: Context, private val changed: (Boolean) -> Unit) :
    SurfaceView(context), SurfaceHolder.Callback, ViewTreeObserver.OnPreDrawListener {
    private var owner: Binder? = null
    private var generation = -1L
    private var source = -1
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var epoch = 0L
    private var sequence = 0L
    private var ready = false
    private var attempted = false
    private var attempts = 0
    private var nextAttemptAt = 0L
    private var surfaceReady = false
    private var lastGeometry: List<Float>? = null
    var protectedContent = false
        private set

    init {
        // Remain BELOW the activity window. Decorators, launcher panels and cursor
        // continue to draw over the hole instead of disappearing under native layers.
        setSecure(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
        isClickable = false
        isFocusable = false
        visibility = GONE
    }

    fun bind(id: Int?, bufferWidth: Int, bufferHeight: Int, phone: Boolean) {
        val output = display
        val supported = Build.VERSION.SDK_INT >= 35 && !phone && output != null &&
            output.displayId != Display.DEFAULT_DISPLAY && output.flags and Display.FLAG_SECURE != 0
        val next = if (supported) id ?: -1 else -1
        if (source != next || generation != PrivilegedService.helperGeneration) {
            disconnect()
            source = next
            generation = PrivilegedService.helperGeneration
        }
        sourceWidth = bufferWidth; sourceHeight = bufferHeight
        visibility = if (next >= 0) VISIBLE else GONE
        connect()
        submit()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(this)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(this)
        disconnect()
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; connect() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // A transparent anchor buffer lets SurfaceFlinger present child native layers.
        val canvas = holder.lockCanvas()
        if (canvas != null) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            holder.unlockCanvasAndPost(canvas)
        }
        lastGeometry = null; connect(); submit()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; disconnect() }
    override fun onPreDraw(): Boolean {
        connect(); submit()
        ProtectedTileInputRegions.update(this, ready && protectedContent && isShown)
        return true
    }

    private fun connect() {
        if (source < 0 || attempted || attempts >= 4 || SystemClock.uptimeMillis() < nextAttemptAt || !surfaceReady || !isAttachedToWindow ||
            !PrivilegedService.isBridgeReady() || Build.VERSION.SDK_INT < 35 || !surfaceControl.isValid) return
        attempted = true
        attempts++
        val token = Binder(); owner = token
        val expected = ++epoch
        val session = generation
        val sourceId = source
        // Transfer only this activity's own root. No global hidden-API exemption:
        // unsupported ROMs keep ordinary rendering and secure content stays black.
        val hostWindow = runCatching {
            val root = checkNotNull(rootSurfaceControl)
            copySurface(checkNotNull(ProtectedHostSurface.read(root)))
        }.getOrElse {
            Log.w("LateralProtected", "Own window surface unavailable", it)
            owner = null
            scheduleRetry(expected)
            return
        }
        val parent = copySurface(surfaceControl)
        val listener = object : IProtectedDisplayListener.Stub() {
            override fun onProtectedDisplays(displayIds: IntArray) {
                post {
                    if (epoch != expected || session != PrivilegedService.helperGeneration) return@post
                    protectedContent = sourceId in displayIds
                    changed(protectedContent)
                    lastGeometry = null; submit()
                }
            }
        }
        PrivilegedService.openProtectedOutputAsync(checkNotNull(display).displayId, sourceId, parent, hostWindow, token, listener) { root ->
            parent.release(); hostWindow.release()
            if (epoch != expected || session != PrivilegedService.helperGeneration) {
                root?.release(); PrivilegedService.closeProtectedOutput(token, session)
                return@openProtectedOutputAsync
            }
            ready = root != null
            root?.release() // The helper retains its own wrapper and owns all child layers.
            if (!ready) {
                protectedContent = false; changed(false)
                owner = null
                scheduleRetry(expected)
            }
            lastGeometry = null; submit()
        }
    }

    /** Transient surface/helper startup failures get bounded retries. */
    private fun scheduleRetry(expected: Long) {
        attempted = false
        nextAttemptAt = SystemClock.uptimeMillis() + 250
        postDelayed({ if (epoch == expected) { connect(); invalidate() } }, 260)
    }

    private fun copySurface(surface: SurfaceControl): SurfaceControl {
        val parcel = Parcel.obtain()
        return try {
            surface.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            SurfaceControl.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    /** Only the fitted image, not its letterbox bars, passes touch to the native clone. */
    fun contentBoundsOnScreen(): Rect? {
        val fit = ProtectedTileGeometry.fit(sourceWidth, sourceHeight, width, height) ?: return null
        val content = RectF(fit.left, fit.top, fit.right, fit.bottom)
        val transform = Matrix()
        transformMatrixToGlobal(transform)
        transform.mapRect(content)
        val bounds = Rect(floor(content.left).toInt(), floor(content.top).toInt(),
            ceil(content.right).toInt(), ceil(content.bottom).toInt())
        val visible = Rect()
        return bounds.takeIf { getGlobalVisibleRect(visible) && it.intersect(visible) }
    }

    private fun submit() {
        val token = owner ?: return
        if (!ready || generation != PrivilegedService.helperGeneration) return
        val geometry = ProtectedTileGeometry.slices(sourceWidth, sourceHeight, width, height) ?: return
        val visible = isShown && protectedContent
        val signature = geometry.toList() + if (visible) 1f else 0f
        if (signature == lastGeometry) return
        lastGeometry = signature
        PrivilegedService.updateProtectedOutput(token, generation, ++sequence, width, height,
            if (visible) intArrayOf(source) else intArrayOf(), if (visible) geometry else floatArrayOf())
    }

    fun disconnect() {
        ++epoch
        owner?.let { PrivilegedService.closeProtectedOutput(it, generation) }
        owner = null; ready = false; attempted = false; attempts = 0; nextAttemptAt = 0; lastGeometry = null
        if (protectedContent) { protectedContent = false; changed(false) }
        ProtectedTileInputRegions.update(this, false)
    }
}
