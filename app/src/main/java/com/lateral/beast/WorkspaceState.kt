package com.lateral.beast

import android.content.Context
import android.os.SystemClock
import com.lateral.privileged.PrivilegedService
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

enum class PresentationMode(val marker: String) {
    PHONE("P"), TABLET("T"), FULLSCREEN("F");
    fun nextFramed(): PresentationMode = if (this == PHONE) TABLET else PHONE
}

enum class TaskPlacement { PHONE, BEAST_VISIBLE, BEAST_MINIMIZED, PENDING_BEAST }

data class BeastTask(
    val id: Long,
    var packageName: String,
    var activityName: String,
    var label: String,
    var shortLabel: String,
    var androidTaskId: Int? = null,
    var instanceTitle: String = "",
    var mode: PresentationMode = PresentationMode.PHONE,
    /** The framed mode to restore after leaving fullscreen. */
    var fullscreenReturnMode: PresentationMode? = null,
    var placement: TaskPlacement = TaskPlacement.PHONE,
    var lastInteractionSequence: Long = 0L,
    var androidDisplayId: Int = 0,
    var overviewRank: Int = Int.MAX_VALUE,
    var supportsMultiWindow: Boolean = true,
    var closeError: String? = null,
    var beastRequestUntil: Long = 0L,
    /** Trusted display assigned to a provisional launcher request before task-id binding. */
    var pendingDisplayId: Int? = null,
    var workspace: Int = 1,
    var floating: Boolean = false,
    var pinned: Boolean = false,
    var floatingX: Float = .2f,
    var floatingY: Float = .15f,
    var floatingWidth: Float = .6f,
    var floatingHeight: Float = .7f,
    var splitAnchor: Long? = null,
    var group: Long? = null,
    var maximized: Boolean = false,
    var pseudo: Boolean = false,
) {
    var minimized: Boolean
        get() = placement != TaskPlacement.BEAST_VISIBLE
        set(value) {
            placement = if (!value) TaskPlacement.BEAST_VISIBLE else when (placement) {
                TaskPlacement.PHONE -> TaskPlacement.PHONE
                else -> TaskPlacement.BEAST_MINIMIZED
            }
        }
}

/** Shared Android-task registry and Beast presentation state. Main-thread mutations only. */
object WorkspaceState {
    private val pendingIds = AtomicLong(-1L)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val mutableTasks = mutableListOf<BeastTask>()
    private val missingCounts = mutableMapOf<Int, Int>()
    private var interactionSequence = 0L
    private var displayModeRestoreTaskId: Long? = null
    private var displayModeRestoreExpiresAt = 0L
    private var displayModeExpectedUltrawide: Boolean? = null
    private var prefs: android.content.SharedPreferences? = null
    private var restored = emptyMap<Int, Persisted>()
    private val lastFreeformSizes = mutableMapOf<String, Pair<Float, Float>>()
    private val dwindleLayouts = mutableMapOf<Int, DwindleLayout>()
    private val workspaceFocus = mutableMapOf<Int, Long?>()
    var activeWorkspace = 1
        private set
    var previousWorkspace = 1
        private set
    var scratchpadVisible = false
        private set

    fun belongsToActiveWorkspace(task: BeastTask): Boolean = task.pinned || task.workspace == activeWorkspace ||
        (task.workspace == 0 && scratchpadVisible)

    fun visibleTasks(): List<BeastTask> {
        val candidates = tasks.filter { belongsToActiveWorkspace(it) &&
            (it.placement == TaskPlacement.BEAST_VISIBLE || it.placement == TaskPlacement.PENDING_BEAST) }
        val selected = candidates.filter { it.group != null }.groupBy { it.group }.mapValues { (_, members) ->
            members.firstOrNull { it.id == focusedTaskId } ?: members.maxBy { it.lastInteractionSequence }
        }
        return candidates.filter { it.group == null || selected[it.group]?.id == it.id }
    }

    private fun dwindle() = dwindleLayouts.getOrPut(activeWorkspace) { DwindleLayout(prefs?.getString("dwindle_$activeWorkspace", null)) }

    internal fun windowBounds(width: Int, height: Int, outer: Int, gap: Int): Map<Long, WindowBounds> {
        val visible = visibleTasks()
        val tiled = visible.filter { !it.floating && !it.pinned && it.workspace != 0 }
        dwindle().sync(tiled.map { it.group ?: it.id }, tasks.firstOrNull { it.id == focusedTaskId }?.let { it.group ?: it.id },
            width, height, tiled.associate { (it.group ?: it.id) to it.splitAnchor })
        val cells = dwindle().bounds(width, height, outer, gap)
        val result = tiled.mapNotNull { task -> cells[task.group ?: task.id]?.let { task.id to it } }.toMap().toMutableMap()
        visible.filter { it.floating || it.pinned || it.workspace == 0 }.forEach { task ->
            val w = (width * task.floatingWidth).toInt().coerceIn(1, width.coerceAtLeast(1))
            val h = (height * task.floatingHeight).toInt().coerceIn(1, height.coerceAtLeast(1))
            result[task.id] = WindowBounds((width * task.floatingX).toInt().coerceIn(0, (width - w).coerceAtLeast(0)),
                (height * task.floatingY).toInt().coerceIn(0, (height - h).coerceAtLeast(0)), w, h)
        }
        visible.forEach { task ->
            val box = result[task.id] ?: return@forEach
            if (task.maximized) result[task.id] = WindowBounds(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
            else if (task.pseudo && !task.floating) {
                val w = minOf(box.width, (width * task.floatingWidth).toInt().coerceAtLeast(1))
                val h = minOf(box.height, (height * task.floatingHeight).toInt().coerceAtLeast(1))
                result[task.id] = WindowBounds(box.x + (box.width - w) / 2, box.y + (box.height - h) / 2, w, h)
            }
        }
        return result
    }

    fun switchWorkspace(workspace: Int) {
        require(workspace in 1..10)
        if (workspace == activeWorkspace) return
        workspaceFocus[activeWorkspace] = focusedTaskId
        previousWorkspace = activeWorkspace
        activeWorkspace = workspace
        scratchpadVisible = false
        focusedTaskId = workspaceFocus[workspace]?.takeIf { id -> visibleTasks().any { it.id == id } }
            ?: visibleTasks().lastOrNull()?.id
        notifyChanged()
    }

    fun moveToWorkspace(id: Long, workspace: Int, follow: Boolean = true) {
        require(workspace in 0..10)
        val task = mutableTasks.firstOrNull { it.id == id } ?: return
        val members = if (task.group == null) listOf(task) else tasks.filter { it.group == task.group }
        members.forEach { it.workspace = workspace; it.pinned = false; if (workspace == 0) it.floating = true }
        if (follow && workspace != 0) switchWorkspace(workspace)
        focusedTaskId = if (follow && workspace != 0) id else visibleTasks().lastOrNull()?.id
        notifyChanged()
    }

    fun toggleFloating(id: Long, pin: Boolean = false) {
        val task = mutableTasks.firstOrNull { it.id == id } ?: return
        val members = if (task.group == null) listOf(task) else tasks.filter { it.group == task.group }
        val next = !task.floating; val pinned = !task.pinned
        members.forEach {
            it.maximized = false
            if (pin) { it.pinned = pinned; it.floating = true }
            else { it.floating = next; it.pinned = false }
        }
        notifyChanged()
    }

    fun toggleScratchpad() {
        scratchpadVisible = !scratchpadVisible
        if (scratchpadVisible) focusedTaskId = visibleTasks().lastOrNull { it.workspace == 0 }?.id ?: focusedTaskId
        else if (tasks.firstOrNull { it.id == focusedTaskId }?.workspace == 0) focusedTaskId = visibleTasks().lastOrNull()?.id
        notifyChanged()
    }

    internal fun focusDirectional(direction: WindowDirection, bounds: Map<Long, WindowBounds>, swap: Boolean = false): Boolean {
        val target = directionalWindow(bounds, focusedTaskId, direction) ?: return false
        val current = focusedTaskId
        if (swap && current != null) {
            dwindle().swap(tasks.first { it.id == current }.let { it.group ?: it.id }, tasks.first { it.id == target }.let { it.group ?: it.id })
            val from = mutableTasks.indexOfFirst { it.id == current }; val to = mutableTasks.indexOfFirst { it.id == target }
            if (from >= 0 && to >= 0) { val item = mutableTasks[from]; mutableTasks[from] = mutableTasks[to]; mutableTasks[to] = item }
            notifyChanged()
        } else focus(target)
        return true
    }

    fun toggleSplit() { tasks.firstOrNull { it.id == focusedTaskId }?.let { dwindle().toggleSplit(it.group ?: it.id); notifyChanged() } }

    fun resizeFocused(horizontal: Boolean, delta: Float, persist: Boolean = true) {
        val task = mutableTasks.firstOrNull { it.id == focusedTaskId } ?: return
        if (task.floating) {
            floatingBounds(task.id, task.floatingX, task.floatingY,
                if (horizontal) task.floatingWidth + delta else task.floatingWidth,
                if (horizontal) task.floatingHeight else task.floatingHeight, persist)
            return
        } else dwindle().resize(task.group ?: task.id, horizontal, delta)
        notifyChanged(persist)
    }

    fun moveFloating(id: Long, dx: Float, dy: Float) {
        val task = mutableTasks.firstOrNull { it.id == id } ?: return
        floatingBounds(id, task.floatingX + dx, task.floatingY + dy, task.floatingWidth, task.floatingHeight)
    }

    fun floatingBounds(id: Long, x: Float, y: Float, width: Float, height: Float, persist: Boolean = true) {
        val task = mutableTasks.firstOrNull { it.id == id } ?: return
        val sizeChanged = task.floatingWidth != width.coerceIn(.15f, 1f) ||
            task.floatingHeight != height.coerceIn(.15f, 1f)
        val members = if (task.group == null) listOf(task) else tasks.filter { it.group == task.group }
        members.forEach {
            it.floating = true; it.maximized = false
            it.floatingWidth = width.coerceIn(.15f, 1f); it.floatingHeight = height.coerceIn(.15f, 1f)
            it.floatingX = x.coerceIn(0f, 1f - it.floatingWidth); it.floatingY = y.coerceIn(0f, 1f - it.floatingHeight)
        }
        if (persist && sizeChanged) rememberFreeformSize(task)
        notifyChanged(persist)
    }

    private fun rememberFreeformSize(task: BeastTask) {
        lastFreeformSizes[task.packageName] = task.floatingWidth to task.floatingHeight
    }

    internal fun savedTileLayout(): String = dwindle().save()

    internal fun finishTileDrag(id: Long, target: Long?, saved: String?) {
        val task = tasks.firstOrNull { it.id == id } ?: return
        (if (task.group == null) listOf(task) else tasks.filter { it.group == task.group }).forEach { it.floating = false }
        if (saved != null) dwindleLayouts[activeWorkspace] = DwindleLayout(saved)
        val other = tasks.firstOrNull { it.id == target && it.id != id }
        if (other != null) {
            dwindle().swap(task.group ?: task.id, other.group ?: other.id)
        }
        notifyChanged()
    }

    fun toggleMaximized(id: Long) { tasks.firstOrNull { it.id == id }?.let { it.maximized = !it.maximized; notifyChanged() } }
    fun togglePseudo(id: Long) { tasks.firstOrNull { it.id == id }?.let { it.pseudo = !it.pseudo; notifyChanged() } }

    fun toggleGroup(id: Long, detach: Boolean = false) {
        val task = tasks.firstOrNull { it.id == id } ?: return
        val group = task.group
        if (group == null && !detach) task.group = task.id
        else if (detach) task.group = null
        else tasks.filter { it.group == group }.forEach { it.group = null }
        notifyChanged()
    }

    fun focusGroup(delta: Int, index: Int? = null) {
        val current = tasks.firstOrNull { it.id == focusedTaskId } ?: return
        val members = tasks.filter { it.group != null && it.group == current.group }
        if (members.isEmpty()) return
        val target = index?.minus(1)?.takeIf { it in members.indices }
            ?: ((members.indexOfFirst { it.id == current.id } + delta + members.size) % members.size)
        focus(members[target].id)
    }

    internal fun joinDirectionalGroup(direction: WindowDirection, bounds: Map<Long, WindowBounds>) {
        val current = tasks.firstOrNull { it.id == focusedTaskId } ?: return
        val target = directionalWindow(bounds, current.id, direction)?.let { id -> tasks.firstOrNull { it.id == id } } ?: return
        val group = target.group ?: target.id
        target.group = group; current.group = group
        current.floating = target.floating; current.workspace = target.workspace
        notifyChanged()
    }

    var onImmediateSyncRequested: (() -> Unit)? = null

    val tasks: List<BeastTask> get() = mutableTasks.toList()
    var focusedTaskId: Long? = null
        private set

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("workspace_tasks", Context.MODE_PRIVATE)
        restored = readPersisted()
        lastFreeformSizes.putAll(readFreeformSizes())
        activeWorkspace = prefs?.getInt("active_workspace", 1)?.coerceIn(1, 10) ?: 1
        pendingIds.set(minOf(-1L, (restored.values.flatMap { listOfNotNull(it.desktopId, it.group) }.minOrNull() ?: 0L) - 1L))
    }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    fun beginDisplayModeSwitch(ultrawide: Boolean) {
        displayModeRestoreTaskId = focusedTaskId
        displayModeExpectedUltrawide = ultrawide
        displayModeRestoreExpiresAt = SystemClock.uptimeMillis() + DISPLAY_MODE_HANDOFF_TIMEOUT_MS
    }

    fun cancelDisplayModeSwitch() {
        displayModeRestoreTaskId = null
        displayModeExpectedUltrawide = null
        displayModeRestoreExpiresAt = 0L
    }

    fun hasPendingDisplayModeFocusRestore(): Boolean {
        if (displayModeRestoreTaskId == null) return false
        if (SystemClock.uptimeMillis() <= displayModeRestoreExpiresAt) return true
        cancelDisplayModeSwitch()
        return false
    }

    fun restoreFocusAfterDisplayModeSwitch(physicalWidth: Int? = null): Boolean {
        if (!hasPendingDisplayModeFocusRestore()) return false
        val expected = displayModeExpectedUltrawide
        if (expected != null && physicalWidth != null && (physicalWidth >= 3000) != expected) {
            return false
        }
        val id = displayModeRestoreTaskId ?: return false
        cancelDisplayModeSwitch()
        if (mutableTasks.none { it.id == id }) return false
        if (focusedTaskId != id) {
            focusedTaskId = id
            notifyChanged()
        }
        return true
    }

    fun reconcile(
        snapshots: List<PrivilegedService.TaskSnapshot>,
        initialImportMinimized: Boolean,
        initial: Boolean,
    ) {
        val incomingIds = snapshots.mapTo(hashSetOf()) { it.taskId }
        var changed = false

        snapshots.forEach { snapshot ->
            missingCounts.remove(snapshot.taskId)
            var task = mutableTasks.firstOrNull { it.androidTaskId == snapshot.taskId }
            if (task == null) {
                task = mutableTasks.firstOrNull {
                    it.androidTaskId == null &&
                        it.placement == TaskPlacement.PENDING_BEAST &&
                        it.pendingDisplayId == snapshot.displayId &&
                        it.packageName == snapshot.packageName
                }
                if (task != null) {
                    task.androidTaskId = snapshot.taskId
                    task.pendingDisplayId = null
                    if (task.placement == TaskPlacement.PENDING_BEAST) {
                        task.placement = TaskPlacement.BEAST_VISIBLE
                    }
                } else {
                    val saved = restored[snapshot.taskId]
                        ?.takeIf { it.packageName == snapshot.packageName }
                    val freeformSize = lastFreeformSizes[snapshot.packageName]
                    val placement = saved?.placement ?: when {
                        initial && !initialImportMinimized -> TaskPlacement.BEAST_VISIBLE
                        else -> TaskPlacement.PHONE
                    }
                    val fallbackLabel = snapshot.packageName.substringAfterLast('.')
                    task = BeastTask(
                        id = saved?.desktopId ?: snapshot.taskId.toLong(),
                        packageName = snapshot.packageName,
                        activityName = snapshot.activityName,
                        label = fallbackLabel,
                        shortLabel = shortLabel(fallbackLabel),
                        androidTaskId = snapshot.taskId,
                        instanceTitle = snapshot.title,
                        mode = saved?.mode ?: PresentationMode.PHONE,
                        fullscreenReturnMode = saved?.fullscreenReturnMode,
                        placement = placement,
                        lastInteractionSequence = nextInteractionSequence(),
                        workspace = saved?.workspace ?: activeWorkspace,
                        floating = saved?.floating ?: false,
                        pinned = saved?.pinned ?: false,
                        floatingX = saved?.x ?: .2f, floatingY = saved?.y ?: .15f,
                        floatingWidth = saved?.width ?: freeformSize?.first ?: .6f,
                        floatingHeight = saved?.height ?: freeformSize?.second ?: .7f,
                        group = saved?.group, maximized = saved?.maximized ?: false, pseudo = saved?.pseudo ?: false,
                    )
                    val restoredOrder = saved?.beastOrder
                    if (restoredOrder == null) {
                        mutableTasks += task
                    } else {
                        mutableTasks.add(restoredOrder.coerceIn(0, mutableTasks.size), task)
                    }
                }
                changed = true
            }
            if (task.packageName != snapshot.packageName || task.activityName != snapshot.activityName ||
                task.instanceTitle != snapshot.title || task.androidDisplayId != snapshot.displayId ||
                task.overviewRank != snapshot.overviewRank ||
                task.supportsMultiWindow != snapshot.supportsMultiWindow
            ) {
                task.packageName = snapshot.packageName
                task.activityName = snapshot.activityName
                task.instanceTitle = snapshot.title
                task.androidDisplayId = snapshot.displayId
                task.overviewRank = snapshot.overviewRank
                task.supportsMultiWindow = snapshot.supportsMultiWindow
                changed = true
            }
            val now = SystemClock.uptimeMillis()
            if (snapshot.displayId == android.view.Display.DEFAULT_DISPLAY && snapshot.isVisible &&
                now >= task.beastRequestUntil && task.placement != TaskPlacement.PHONE
            ) {
                task.placement = TaskPlacement.PHONE
                if (focusedTaskId == task.id) focusedTaskId = null
                changed = true
            } else if (snapshot.displayId != android.view.Display.DEFAULT_DISPLAY &&
                snapshot.displayId >= 0 && task.placement == TaskPlacement.PHONE
            ) {
                task.placement = TaskPlacement.BEAST_VISIBLE
                changed = true
            }
        }

        mutableTasks.filter { it.androidTaskId != null && it.androidTaskId !in incomingIds }
            .toList().forEach { task ->
                val taskId = task.androidTaskId ?: return@forEach
                val misses = (missingCounts[taskId] ?: 0) + 1
                missingCounts[taskId] = misses
                if (misses >= MISSING_GRACE_POLLS) {
                    mutableTasks.remove(task)
                    missingCounts.remove(taskId)
                    if (focusedTaskId == task.id) focusedTaskId = null
                    changed = true
                }
            }

        if (focusedTaskId == null) {
            focusedTaskId = visibleTasks().firstOrNull()?.id
        }
        if (changed) notifyChanged()
    }

    fun updateLabel(androidTaskId: Int, label: String) {
        val task = mutableTasks.firstOrNull { it.androidTaskId == androidTaskId } ?: return
        if (task.label == label) return
        task.label = label
        task.shortLabel = shortLabel(label)
        notifyChanged()
    }

    fun launchNew(packageName: String, activityName: String, label: String): BeastTask {
        val freeformSize = lastFreeformSizes[packageName]
        val task = BeastTask(
            id = pendingIds.getAndDecrement(), packageName = packageName,
            activityName = activityName, label = label, shortLabel = shortLabel(label),
            placement = TaskPlacement.PENDING_BEAST,
            lastInteractionSequence = nextInteractionSequence(),
            workspace = activeWorkspace,
            mode = PresentationMode.TABLET,
            floatingWidth = freeformSize?.first ?: .6f,
            floatingHeight = freeformSize?.second ?: .7f,
            splitAnchor = focusedTaskId,
            group = tasks.firstOrNull { it.id == focusedTaskId }?.group,
        )
        // Beast order is intentionally independent of Android Overview. New instances
        // append without disturbing the user's existing spatial layout.
        mutableTasks.add(task)
        focusedTaskId = task.id
        notifyChanged()
        onImmediateSyncRequested?.invoke()
        return task
    }

    /** Correlate a provisional launch with its dedicated trusted display. */
    fun markPendingDisplayReady(id: Long, displayId: Int) {
        val task = mutableTasks.firstOrNull { it.id == id } ?: return
        if (task.placement != TaskPlacement.PENDING_BEAST || task.pendingDisplayId == displayId) return
        task.pendingDisplayId = displayId
        task.beastRequestUntil = SystemClock.uptimeMillis() + BEAST_MOVE_GRACE_MS
        notifyChanged()
        onImmediateSyncRequested?.invoke()
    }

    /** Keep the provisional card visible while Android creates and reports its task. */
    fun markPendingHosted(id: Long) {
        val task = mutableTasks.firstOrNull { it.id == id } ?: return
        if (task.placement != TaskPlacement.PENDING_BEAST) return
        task.beastRequestUntil = SystemClock.uptimeMillis() + BEAST_MOVE_GRACE_MS
        if (focusedTaskId == null && belongsToActiveWorkspace(task)) focusedTaskId = id
        notifyChanged()
        onImmediateSyncRequested?.invoke()
    }

    fun focus(id: Long) {
        val task = mutableTasks.firstOrNull { it.id == id } ?: return
        if (task.workspace in 1..10 && !task.pinned && task.workspace != activeWorkspace) switchWorkspace(task.workspace)
        if (task.workspace == 0) scratchpadVisible = true
        if (!task.supportsMultiWindow) {
            task.closeError = "This app cannot move to a secondary display"
            notifyChanged()
            return
        }
        task.lastInteractionSequence = nextInteractionSequence()
        task.placement = TaskPlacement.BEAST_VISIBLE
        task.beastRequestUntil = SystemClock.uptimeMillis() + BEAST_MOVE_GRACE_MS
        task.closeError = null
        focusedTaskId = id
        notifyChanged()
        onImmediateSyncRequested?.invoke()
    }

    /** Focus the adjacent visible window in the current workspace. */
    fun focusRelative(delta: Int): Boolean {
        if (delta == 0) return false
        val visibleIds = mutableTasks.asSequence()
            .filter { belongsToActiveWorkspace(it) && (
                it.placement == TaskPlacement.BEAST_VISIBLE ||
                    it.placement == TaskPlacement.PENDING_BEAST)
            }
            .map(BeastTask::id)
            .toList()
        val targetId = relativeFocusTarget(visibleIds, focusedTaskId, delta) ?: return false
        focus(targetId)
        return focusedTaskId == targetId
    }

    fun setMode(id: Long, mode: PresentationMode) {
        val task = mutableTasks.firstOrNull { it.id == id } ?: return
        val nextMode = when {
            mode == PresentationMode.FULLSCREEN && task.mode != PresentationMode.FULLSCREEN -> {
                if (task.mode == PresentationMode.PHONE || task.mode == PresentationMode.TABLET) {
                    task.fullscreenReturnMode = task.mode
                }
                mode
            }
            task.mode == PresentationMode.FULLSCREEN && mode != PresentationMode.FULLSCREEN -> {
                task.fullscreenReturnMode ?: mode
            }
            else -> mode
        }
        if (nextMode != PresentationMode.FULLSCREEN) task.fullscreenReturnMode = null
        task.mode = nextMode
        task.placement = TaskPlacement.BEAST_VISIBLE
        task.lastInteractionSequence = nextInteractionSequence()
        focusedTaskId = id
        notifyChanged()
    }

    fun move(id: Long, delta: Int) {
        val from = mutableTasks.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, mutableTasks.lastIndex)
        if (from == to) return
        val task = mutableTasks.removeAt(from)
        mutableTasks.add(to, task)
        focusedTaskId = id
        notifyChanged()
    }

    fun removeClosed(id: Long): BeastTask? {
        val index = mutableTasks.indexOfFirst { it.id == id }
        if (index < 0) return null
        val removed = mutableTasks.removeAt(index)
        if (focusedTaskId == id) {
            focusedTaskId = visibleTasks().firstOrNull()?.id
        }
        notifyChanged()
        return removed
    }

    fun markCloseFailed(id: Long, message: String) {
        mutableTasks.firstOrNull { it.id == id }?.let {
            it.closeError = message
            notifyChanged()
        }
    }

    fun minimize(id: Long) {
        val task = mutableTasks.firstOrNull { it.id == id } ?: return
        if (task.placement != TaskPlacement.BEAST_VISIBLE) return
        task.placement = TaskPlacement.BEAST_MINIMIZED
        if (focusedTaskId == id) {
            focusedTaskId = visibleTasks().firstOrNull()?.id
        }
        notifyChanged()
    }

    fun showAll() {
        var changed = false
        val requestUntil = SystemClock.uptimeMillis() + BEAST_MOVE_GRACE_MS
        mutableTasks.forEach {
            if (it.placement != TaskPlacement.BEAST_VISIBLE) {
                it.placement = TaskPlacement.BEAST_VISIBLE
                it.beastRequestUntil = requestUntil
                changed = true
            }
        }
        if (changed) {
            focusedTaskId = focusedTaskId ?: mutableTasks.firstOrNull()?.id
            notifyChanged()
        }
    }

    fun minimizeAll() {
        var changed = false
        mutableTasks.forEach {
            if (it.placement == TaskPlacement.BEAST_VISIBLE) {
                it.placement = TaskPlacement.BEAST_MINIMIZED
                changed = true
            }
        }
        if (changed) {
            focusedTaskId = null
            notifyChanged()
        }
    }

    fun closeAllCandidates(): List<BeastTask> = mutableTasks.toList()

    fun commitGeometry(resizedTaskId: Long? = null) {
        resizedTaskId?.let { id -> mutableTasks.firstOrNull { it.id == id }?.let(::rememberFreeformSize) }
        persist()
    }

    private fun notifyChanged(persist: Boolean = true) {
        if (persist) persist()
        listeners.forEach { it() }
    }

    private fun nextInteractionSequence(): Long = ++interactionSequence
    private fun shortLabel(label: String) =
        label.filter { it.isLetterOrDigit() }.take(12).lowercase().ifBlank { "app" }

    private data class Persisted(
        val packageName: String,
        val placement: TaskPlacement,
        val mode: PresentationMode,
        val fullscreenReturnMode: PresentationMode?,
        val beastOrder: Int,
        val desktopId: Long,
        val workspace: Int,
        val floating: Boolean,
        val pinned: Boolean,
        val x: Float, val y: Float, val width: Float, val height: Float,
        val group: Long?, val maximized: Boolean, val pseudo: Boolean,
    )

    private fun readPersisted(): Map<Int, Persisted> = runCatching {
        val array = JSONArray(prefs?.getString(KEY_STATE, "[]") ?: "[]")
        buildMap {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                put(item.getInt("taskId"), Persisted(
                    item.getString("package"),
                    TaskPlacement.valueOf(item.getString("placement")),
                    PresentationMode.valueOf(item.getString("mode")),
                    item.optString("fullscreenReturnMode", "")
                        .takeIf(String::isNotBlank)
                        ?.let(PresentationMode::valueOf),
                    item.optInt("beastOrder", index),
                    item.optLong("desktopId", item.getInt("taskId").toLong()),
                    item.optInt("workspace", 1).coerceIn(0, 10),
                    item.optBoolean("floating", false), item.optBoolean("pinned", false),
                    item.optDouble("x", .2).toFloat(), item.optDouble("y", .15).toFloat(),
                    item.optDouble("width", .6).toFloat().coerceIn(.15f, 1f),
                    item.optDouble("height", .7).toFloat().coerceIn(.15f, 1f),
                    if (item.has("group")) item.getLong("group") else null,
                    item.optBoolean("maximized", false), item.optBoolean("pseudo", false),
                ))
            }
        }
    }.getOrDefault(emptyMap())

    private fun readFreeformSizes(): Map<String, Pair<Float, Float>> = runCatching {
        val saved = JSONObject(prefs?.getString(KEY_FREEFORM_SIZES, "{}") ?: "{}")
        buildMap {
            saved.keys().forEach { packageName ->
                val size = saved.getJSONArray(packageName)
                put(packageName, size.getDouble(0).toFloat().coerceIn(.15f, 1f) to
                    size.getDouble(1).toFloat().coerceIn(.15f, 1f))
            }
        }
    }.getOrDefault(emptyMap())

    private fun persist() {
        val array = JSONArray()
        mutableTasks.forEachIndexed { order, task ->
            val taskId = task.androidTaskId ?: return@forEachIndexed
            array.put(JSONObject().apply {
                put("taskId", taskId)
                put("package", task.packageName)
                put("placement", task.placement.name)
                put("mode", task.mode.name)
                task.fullscreenReturnMode?.let { put("fullscreenReturnMode", it.name) }
                put("beastOrder", order)
                put("desktopId", task.id); put("workspace", task.workspace)
                put("floating", task.floating); put("pinned", task.pinned)
                put("x", task.floatingX); put("y", task.floatingY)
                put("width", task.floatingWidth); put("height", task.floatingHeight)
                task.group?.let { put("group", it) }; put("maximized", task.maximized); put("pseudo", task.pseudo)
            })
        }
        val freeformSizes = JSONObject().apply {
            lastFreeformSizes.forEach { (packageName, size) ->
                put(packageName, JSONArray().put(size.first.toDouble()).put(size.second.toDouble()))
            }
        }
        prefs?.edit()?.putString(KEY_STATE, array.toString())
            ?.putString(KEY_FREEFORM_SIZES, freeformSizes.toString())
            ?.putInt("active_workspace", activeWorkspace)?.apply()
        prefs?.edit()?.apply {
            dwindleLayouts.forEach { (workspace, tree) -> putString("dwindle_$workspace", tree.save()) }
        }?.apply()
    }

    private const val KEY_STATE = "state.v2"
    private const val KEY_FREEFORM_SIZES = "freeform_sizes.v1"
    private const val MISSING_GRACE_POLLS = 3
    private const val BEAST_MOVE_GRACE_MS = 2_500L
    private const val DISPLAY_MODE_HANDOFF_TIMEOUT_MS = 15_000L
}

/** Pure navigation policy: move one card in the requested direction and stop at the edge. */
internal fun relativeFocusTarget(ids: List<Long>, focusedId: Long?, delta: Int): Long? {
    if (ids.isEmpty() || delta == 0) return null
    val current = ids.indexOf(focusedId)
    val target = if (current < 0) {
        if (delta < 0) ids.lastIndex else 0
    } else {
        (current + if (delta < 0) -1 else 1).coerceIn(0, ids.lastIndex)
    }
    return ids[target].takeIf { it != focusedId }
}
