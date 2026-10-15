package com.lateral.privileged

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.Region
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.util.Log
import android.view.InputWindowHandle
import android.view.SurfaceControl
import android.view.WindowManager
import android.window.WindowInfosListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Shell-only native composition. Secure app buffers never enter LATERAL's GL context. */
internal class ProtectedDisplayOutput(
    val displayId: Int,
    private val parent: SurfaceControl,
    private val hostWindow: SurfaceControl,
    private val owner: IBinder,
    private val hostUid: Int,
    private val listener: IProtectedDisplayListener,
    private val ownsSource: (Int) -> Boolean,
    private val windowManager: Any,
    private val onClosed: (ProtectedDisplayOutput) -> Unit,
) : AutoCloseable {
    data class Frame(val sequence: Long, val width: Int, val height: Int, val eyeCount: Int,
        val sources: IntArray, val eyes: IntArray, val slices: FloatArray)
    data class Route(val displayId: Int, val x: Float, val y: Float, val uid: Int)
    private data class Window(val token: IBinder?, val name: String, val display: Int,
        val config: Int, val secure: Boolean, val transform: Matrix, val region: Region, val uid: Int,
        val physicalToLogical: Matrix, val logicalToPhysical: Matrix)
    private data class Slice(val source: Int, val eye: Int, val surface: SurfaceControl)
    private data class Cell(val source: Int, val eye: Int, val index: Int)
    private val closed = AtomicBoolean(false)
    private val pending = AtomicReference<Frame?>()
    private val scheduled = AtomicBoolean(false)
    private val windows = AtomicReference<List<Window>>(emptyList())
    private data class Gesture(val token: IBinder, val x: Float, val y: Float, val eye: Int)
    private val gestures = java.util.concurrent.ConcurrentHashMap<Pair<Int, Int>, Gesture>()
    private val published = AtomicReference<Set<Int>>(emptySet())
    private val visibleSources = AtomicReference<Set<Int>>(emptySet())
    private val focusedClone = AtomicReference<IBinder?>()
    private val focusLock = Any()
    private var sequence = -1L
    @Volatile private var eyeCount = 1
    @Volatile private var outputWidth = 0
    private var root: SurfaceControl? = null
    private var hostTrusted = false // Accessed only by the composition worker.
    private val hostSurfaceName = hostWindow.toString().substringAfter("Surface(name=", "").substringBefore(")")
    private val eyeRoots = ArrayList<SurfaceControl>()
    private val slices = LinkedHashMap<Cell, Slice>()
    private val cloneFlag = inputFlag("CLONE")
    private val sensitiveFlag = inputFlag("SENSITIVE_FOR_PRIVACY")
    private val dropFlag = inputFlag("DROP_INPUT")
    private val invisibleFlag = inputFlag("NOT_VISIBLE")
    private val notFocusableFlag = inputFlag("NOT_FOCUSABLE")
    private val notTouchableFlag = inputFlag("NOT_TOUCHABLE")
    private val focusMethod = SurfaceControl.Transaction::class.java.getMethod("setFocusedWindow",
        IBinder::class.java, String::class.java, Int::class.javaPrimitiveType)
    private val matrixMethod = SurfaceControl.Transaction::class.java.getMethod("setMatrix",
        SurfaceControl::class.java, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
        Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
    private val removeMethod = SurfaceControl.Transaction::class.java.getMethod("remove", SurfaceControl::class.java)
    private val mirrorMethod = Class.forName("android.view.IWindowManager").getMethod("mirrorDisplay",
        Int::class.javaPrimitiveType, SurfaceControl::class.java)
    private val death = IBinder.DeathRecipient { close() }
    private val windowListener = object : WindowInfosListener() {
        override fun onWindowInfosChanged(handles: Array<InputWindowHandle>, displays: Array<DisplayInfo>) {
            if (closed.get()) return
            displays.firstOrNull { it.mDisplayId == displayId }?.let { outputWidth = it.mLogicalSize.width }
            val coordinates = displays.associate { display ->
                val toLogical = Matrix(display.mTransform ?: Matrix())
                val toPhysical = Matrix()
                check(toLogical.invert(toPhysical)) { "Invalid display input transform ${display.mDisplayId}" }
                display.mDisplayId to (toLogical to toPhysical)
            }
            val identity = Matrix() to Matrix()
            windows.set(handles.map {
                val coordinate = coordinates[it.displayId] ?: identity
                Window(it.token, it.name.orEmpty(), it.displayId, it.inputConfig,
                it.layoutParamsFlags and WindowManager.LayoutParams.FLAG_SECURE != 0 ||
                    it.inputConfig and sensitiveFlag != 0,
                Matrix(it.transform ?: Matrix()), Region(it.touchableRegion), it.ownerUid,
                    coordinate.first, coordinate.second)
            })
            // Metadata only. Never change policy flags, input handles, or application surfaces.
            handler.post {
                runCatching { publishProtection() }.onFailure {
                    Log.e("LateralProtected", "Protected host validation failed", it)
                    close()
                }
            }
        }
    }
    // Resolve platform APIs before allocating a worker; unsupported ROMs must not leak threads.
    private val thread = HandlerThread("lateral-protected-layers", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    init {
        try {
            val app = com.lateral.BuildConfig.APPLICATION_ID
            require(hostSurfaceName == "$app/com.lateral.beast.BeastActivity" ||
                (com.lateral.BuildConfig.DEBUG && hostSurfaceName == "$app/com.lateral.beast.ProtectedTileFixtureActivity")) {
                "Protected host must be this app's external workspace window"
            }
            root = container("LATERAL protected workspace", parent)
            SurfaceControl.Transaction().use { it.setLayer(root!!, 1).setVisibility(root!!, true).apply() }
            owner.linkToDeath(death, 0)
            val initial = windowListener.register()
            // WindowInfos may not contain the first host frame yet. Publication
            // remains empty until system metadata verifies its UID and display.
            windowListener.onWindowInfosChanged(initial.first, initial.second)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }
    // AIDL writes return values with PARCELABLE_WRITE_RETURN_VALUE, which releases
    // the sent wrapper. Keep our original handle alive for parenting later cells.
    val surfaceRoot: SurfaceControl get() = SurfaceControl::class.java.getConstructor(
        SurfaceControl::class.java, String::class.java).newInstance(checkNotNull(root), "LATERAL root transfer")

    private fun publishProtection() {
        if (closed.get()) return
        val protected = if (hasValidatedHost()) windows.get()
            .filter { it.config and cloneFlag == 0 && it.secure && ownsSource(it.display) }
            .map { it.display }.toSet() else emptySet()
        updateHostTrust(protected.any { it in visibleSources.get() })
        val previous = published.getAndSet(protected)
        if (previous != protected) {
            runCatching { listener.onProtectedDisplays(protected.toIntArray()) }
                .onFailure { close() }
            // Drop obsolete clones immediately, independently of the app's next GL frame.
            SurfaceControl.Transaction().use { transaction ->
                slices.values.filter { it.source !in protected }.forEach { transaction.setVisibility(it.surface, false) }
                transaction.apply()
            }
            if (visibleSources.get().none { it in protected }) restoreHostFocus()
        }
    }

    fun submit(frame: Frame) {
        if (closed.get() || frame.sources.size > 1536 || frame.eyes.size != frame.sources.size ||
            frame.slices.size != frame.sources.size * 10 || frame.width !in 1..16384 || frame.height !in 1..16384 ||
            frame.eyeCount !in 1..2 || frame.slices.any { !it.isFinite() } ||
            frame.eyes.any { it !in 0 until frame.eyeCount } || frame.sources.any { !ownsSource(it) }) return
        pending.getAndUpdate { old -> if (old == null || old.sequence < frame.sequence) frame else old }
        schedule()
    }

    private fun schedule() {
        if (scheduled.compareAndSet(false, true)) handler.post {
            try {
                pending.getAndSet(null)?.let { if (!closed.get() && it.sequence > sequence) apply(it) }
            } catch (failure: Throwable) {
                Log.e("LateralProtected", "Native protected composition failed", failure)
                close()
            } finally {
                scheduled.set(false)
                if (!closed.get() && pending.get() != null) schedule()
            }
        }
    }

    private fun container(name: String, parent: SurfaceControl): SurfaceControl {
        val builder = SurfaceControl.Builder().setName(name).setParent(parent)
        builder.javaClass.getMethod("setContainerLayer").invoke(builder)
        return builder.build()
    }

    private fun apply(frame: Frame) {
        if (!parent.isValid || !owner.isBinderAlive) { close(); return }
        sequence = frame.sequence
        eyeCount = frame.eyeCount
        val protected = published.get()
        SurfaceControl.Transaction().use { transaction ->
            if (root == null) {
                root = container("LATERAL protected workspace", parent)
                transaction.setLayer(root!!, 0).setVisibility(root!!, true)
            }
            while (eyeRoots.size < frame.eyeCount) {
                val eye = container("LATERAL protected eye ${eyeRoots.size}", root!!)
                eyeRoots += eye
                transaction.setVisibility(eye, true)
            }
            eyeRoots.forEachIndexed { index, eye ->
                transaction.setVisibility(eye, index < frame.eyeCount)
                transaction.setCrop(eye, Rect(index * frame.width / frame.eyeCount, 0,
                    (index + 1) * frame.width / frame.eyeCount, frame.height))
            }
            // Reuse mirrors at every pose. Only structural layout changes allocate native layers.
            val used = HashSet<Cell>()
            val counters = HashMap<Pair<Int,Int>, Int>()
            for (i in frame.sources.indices) {
                val source = frame.sources[i]
                val eye = frame.eyes[i]
                val group = source to eye
                val index = counters[group] ?: 0
                counters[group] = index+1
                val key = Cell(source,eye,index)
                used += key
                val existing = slices[key]
                if (source !in protected) { existing?.let { transaction.setVisibility(it.surface, false) }; continue }
                val n = i * 10
                val crop = Rect(frame.slices[n].toInt(), frame.slices[n+1].toInt(),
                    frame.slices[n+2].toInt(), frame.slices[n+3].toInt())
                if (crop.isEmpty) { existing?.let { transaction.setVisibility(it.surface, false) }; continue }
                val slice = existing ?: run {
                    val surface = SurfaceControl::class.java.getConstructor().newInstance()
                    if (mirrorMethod.invoke(windowManager, source, surface) != true) {
                        surface.release(); error("WindowManager rejected owned display mirror $source")
                    }
                    transaction.reparent(surface, eyeRoots[eye])
                    Slice(source, eye, surface).also { slices[key] = it }
                }
                transaction.setLayer(slice.surface, i)
                transaction.setVisibility(slice.surface, !crop.isEmpty)
                if (!crop.isEmpty) {
                    transaction.setCrop(slice.surface, crop)
                    // SurfaceControl stores its off-diagonal terms in column order.
                    matrixMethod.invoke(transaction, slice.surface, frame.slices[n+4], frame.slices[n+6],
                        frame.slices[n+5], frame.slices[n+7])
                    transaction.setPosition(slice.surface, frame.slices[n+8], frame.slices[n+9])
                }
            }
            val inactive = slices.keys.filter { it !in used }
            for (key in inactive) {
                val slice = slices.getValue(key)
                transaction.setVisibility(slice.surface, false)
                // Keep neighboring/minimized tiles warm, with bounded native allocation.
                if (!ownsSource(key.source) || slices.size > 1536) {
                    removeMethod.invoke(transaction, slice.surface); slice.surface.release(); slices.remove(key)
                }
            }
            transaction.apply()
        }
        visibleSources.set(frame.sources.indices.filter { frame.sources[it] in protected &&
            frame.slices[it*10+2] > frame.slices[it*10] }.map { frame.sources[it] }.toSet())
        updateHostTrust(visibleSources.get().isNotEmpty())
        if (visibleSources.get().isEmpty()) restoreHostFocus()
    }

    private fun updateHostTrust(active: Boolean) {
        if (active == hostTrusted) return
        if (active) {
            check(hasValidatedHost()) { "Protected host no longer belongs to the caller" }
            ProtectedHostOverlay.acquire(hostWindow)
        } else ProtectedHostOverlay.release(hostWindow)
        hostTrusted = active
    }

    private fun hasValidatedHost() = hostWindow.isValid && windows.get().any {
        it.display == displayId && it.uid == hostUid && it.config and (cloneFlag or invisibleFlag) == 0 &&
            it.name.endsWith(" $hostSurfaceName")
    }

    /** Uses the committed native input transforms, including crop and stereo eye geometry. */
    fun route(sourceId: Int, x: Float, y: Float, focus: Boolean = true, pointer: Int = 0): Route? {
        if (closed.get() || sourceId !in visibleSources.get()) return null
        val current = windows.get()
        val captured = if (focus) null else gestures[sourceId to pointer]
        for (source in current) {
            if (source.display != sourceId || source.config and cloneFlag != 0 || source.token == null ||
                (captured != null && source.token != captured.token)) continue
            val local = floatArrayOf(x, y)
            // WindowInfos regions/transforms use physical display coordinates.
            // Android's injected events use logical coordinates, even when the
            // panel and retained logical canvas have different dimensions.
            source.logicalToPhysical.mapPoints(local)
            if (captured == null && !source.region.contains(local[0].toInt(),local[1].toInt())) continue
            source.transform.mapPoints(local)
            val clones = current.filter { it.display == displayId && it.token == source.token && it.config and cloneFlag != 0 }
            for (clone in clones) {
                if (clone.config and (dropFlag or invisibleFlag or notTouchableFlag) != 0) continue
                val inverse = Matrix()
                if (!clone.transform.invert(inverse)) continue
                val center = floatArrayOf(clone.region.bounds.centerX().toFloat(),clone.region.bounds.centerY().toFloat())
                clone.physicalToLogical.mapPoints(center)
                val eye = if (eyeCount == 2 && outputWidth > 0 && center[0] >= outputWidth/2) 1 else 0
                if (captured != null) {
                    if (captured.eye != eye) continue
                    // The source crop stays stable when new visible cells change Z order.
                    // Re-find the clone containing DOWN, then map MOVE outside that crop.
                    val anchor = floatArrayOf(captured.x, captured.y)
                    source.logicalToPhysical.mapPoints(anchor)
                    source.transform.mapPoints(anchor)
                    inverse.mapPoints(anchor)
                    if (!clone.region.contains(anchor[0].toInt(), anchor[1].toInt())) continue
                }
                val point = local.copyOf()
                inverse.mapPoints(point)
                if (!point.all { it.isFinite() } || (captured == null && !clone.region.contains(point[0].toInt(), point[1].toInt()))) continue
                if (focus) { gestures[sourceId to pointer] = Gesture(source.token, x, y, eye); focus(clone) }
                clone.physicalToLogical.mapPoints(point)
                return Route(displayId, point[0], point[1], source.uid)
            }
        }
        return null
    }

    fun endGesture() { gestures.clear() }

    fun keyTarget(sourceId: Int): Route? {
        if (closed.get() || sourceId !in visibleSources.get()) return null
        val sourceTokens = windows.get().filter { it.display == sourceId }.mapNotNull { it.token }.toSet()
        val clone = windows.get().firstOrNull { it.display == displayId && it.token in sourceTokens &&
            it.config and cloneFlag != 0 && it.config and (dropFlag or invisibleFlag or notFocusableFlag) == 0 } ?: return null
        focus(clone, true)
        return Route(displayId, 0f, 0f, clone.uid)
    }

    fun protects(sourceId: Int) = !closed.get() && sourceId in published.get()

    fun hostWindowName(): String? = windows.get().firstOrNull { it.display == displayId && it.uid == hostUid &&
        it.token != null && it.config and (cloneFlag or invisibleFlag) == 0 }?.name

    fun hostReadyForKeyboard(): Boolean = !closed.get() && windows.get().any {
        it.display == displayId && it.uid == hostUid && it.token != null &&
            it.config and (cloneFlag or invisibleFlag or notFocusableFlag) == 0
    }

    private fun focus(window: Window, waitForInput: Boolean = false) {
        val reported = if (waitForInput) java.util.concurrent.CountDownLatch(1) else null
        synchronized(focusLock) {
            if (closed.get()) return
            focusedClone.set(window.token)
            SurfaceControl.Transaction().use {
                focusMethod.invoke(it, window.token, window.name, displayId)
                // Focus-only transactions need not publish changed WindowInfos.
                if (reported != null) it.addTransactionCommittedListener({ runnable -> runnable.run() }) { reported.countDown() }
                it.apply()
            }
        }
        reported?.await(150, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun restoreHostFocus() = synchronized(focusLock) {
        if (focusedClone.getAndSet(null) == null || !owner.isBinderAlive) return
        val host = windows.get().firstOrNull { it.display == displayId && it.uid == hostUid && it.token != null &&
            it.config and (cloneFlag or invisibleFlag or notFocusableFlag) == 0 } ?: return
        SurfaceControl.Transaction().use {
            focusMethod.invoke(it, host.token, host.name, displayId)
            it.apply()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { restoreHostFocus() }
        endGesture()
        published.set(emptySet())
        runCatching { listener.onProtectedDisplays(intArrayOf()) }
        onClosed(this)
        visibleSources.set(emptySet()); pending.set(null)
        runCatching { owner.unlinkToDeath(death, 0) }
        runCatching { windowListener.unregister() }
        handler.post {
            runCatching { updateHostTrust(false) }
                .onFailure { Log.e("LateralProtected", "Could not release own host trust", it) }
            runCatching {
                SurfaceControl.Transaction().use { transaction ->
                    root?.let { removeMethod.invoke(transaction, it) }
                    transaction.apply()
                }
            }
            try {
                slices.values.forEach { it.surface.release() }; slices.clear()
                eyeRoots.forEach { it.release() }; eyeRoots.clear()
                root?.release(); root = null; parent.release(); hostWindow.release()
            } finally { thread.quitSafely() }
        }
    }

    /** Helper shutdown must flush host-trust removal before exiting the shell process. */
    fun awaitClosed() {
        if (Thread.currentThread() !== thread) thread.join(1500)
    }

    companion object {
        private fun inputFlag(name: String) = Class.forName("android.os.InputConfig").getField(name).getInt(null)
    }
}
