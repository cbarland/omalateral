package com.lateral

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lateral.beast.AndroidTaskSynchronizer
import com.lateral.beast.PresentationMode
import com.lateral.beast.TaskPlacement
import com.lateral.beast.WorkspaceLayout
import com.lateral.beast.WorkspaceState

/** Phone-owned window controls. Every operation is available without a keyboard or long press. */
object PhoneWindowsPanel {
    fun show(activity: Activity, onDismiss: () -> Unit = {}): AlertDialog? {
        if (activity.isFinishing || activity.isDestroyed) { onDismiss(); return null }
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        var dialog: AlertDialog? = null
        var moving = false
        var swapping = false
        var selected = WorkspaceState.focusedTaskId
        val lease = TransientPanelCoordinator.claim(activity.display?.displayId ?: 0,
            TransientPanelCoordinator.Kind.CONTROLLER) { dialog?.dismiss() }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(12))
            setBackgroundColor(InputSettings.theme.background)
        }
        val scroll = ScrollView(activity).apply { addView(content) }
        fun label(value: String) {
            content.addView(TextView(activity).apply {
                text = value
                textSize = 16f
                setTextColor(InputSettings.theme.brightForeground)
                setPadding(dp(4), dp(12), dp(4), dp(8))
            })
        }
        fun row(items: List<Pair<String, () -> Unit>>) {
            val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            items.forEach { (title, action) ->
                row.addView(Button(activity).apply {
                    text = title
                    isAllCaps = false
                    textSize = 13f
                    minHeight = dp(48)
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            content.addView(row)
        }
        lateinit var render: () -> Unit
        render = {
            content.removeAllViews()
            val task = WorkspaceState.tasks.firstOrNull { it.id == selected }
            if (task == null) { moving = false; swapping = false }
            label(if (moving) "Move ${task?.label} to…" else "Workspace ${WorkspaceState.activeWorkspace}")
            (1..10).chunked(5).forEach { numbers ->
                row(numbers.map { number ->
                    val count = WorkspaceState.tasks.count { it.workspace == number && it.placement != TaskPlacement.PHONE }
                    val title = "${if (number == WorkspaceState.activeWorkspace) "• " else ""}$number\n($count)"
                    title to {
                        if (moving && task != null) {
                            moving = false
                            WorkspaceState.moveToWorkspace(task.id, number, follow = true)
                        } else {
                            selected = null
                            WorkspaceState.switchWorkspace(number)
                        }
                        render()
                    }
                })
            }
            if (moving) {
                label("Grouped windows move together. You will follow the window.")
                row(listOf("Cancel move" to { moving = false; render() }))
            } else {
                row(listOf(
                    (if (WorkspaceState.layout() == WorkspaceLayout.DWINDLE) "Layout: tiles" else "Layout: columns") to { WorkspaceState.toggleLayout() },
                    (if (WorkspaceState.scratchpadVisible) "Hide shelf" else "Shelf (${WorkspaceState.tasks.count { it.workspace == 0 }})") to { WorkspaceState.toggleScratchpad() },
                ))
                val tasks = WorkspaceState.tasks.filter {
                    WorkspaceState.belongsToActiveWorkspace(it) && it.placement != TaskPlacement.PHONE
                }
                label(if (swapping) "Tap a tiled window to swap with ${task?.label}"
                    else if (tasks.isEmpty()) "No windows here. Open an app from Apps." else "Tap a window to select and show it")
                tasks.forEach { window ->
                    if (swapping && (window.id == selected || window.floating || window.pinned ||
                        window.workspace == 0 || window.minimized || (task?.group != null && window.group == task.group))) return@forEach
                    val state = when {
                        window.placement == TaskPlacement.BEAST_MINIMIZED -> "hidden"
                        window.workspace == 0 -> "shelf"
                        window.pinned -> "all workspaces"
                        window.group != null -> "grouped"
                        window.floating -> "floating"
                        else -> "tiled"
                    }
                    row(listOf("${if (window.id == selected) "✓ " else ""}${window.label} · $state" to {
                        if (swapping && task != null) {
                            swapping = false
                            WorkspaceState.finishTileDrag(task.id, window.id, WorkspaceState.savedTileLayout())
                            render()
                        } else {
                            selected = window.id
                            WorkspaceState.focus(window.id, reveal = true)
                        }
                    }))
                }
                if (swapping) row(listOf("Cancel swap" to { swapping = false; render() }))
                if (!swapping && task != null && WorkspaceState.belongsToActiveWorkspace(task) && task.placement != TaskPlacement.PHONE) {
                    label("Arrange ${task.label}")
                    row(listOf(
                        (if (task.floating) "Tile" else "Float") to { WorkspaceState.toggleFloating(task.id) },
                        (if (task.mode == PresentationMode.FULLSCREEN) "Exit fullscreen" else "Fullscreen") to {
                            WorkspaceState.setMode(task.id, if (task.mode == PresentationMode.FULLSCREEN) PresentationMode.TABLET else PresentationMode.FULLSCREEN)
                        },
                    ))
                    row(listOf("Move to…" to { moving = true; render() },
                        (if (task.pinned) "Unpin" else "All workspaces") to { WorkspaceState.toggleFloating(task.id, pin = true) }))
                    if (task.mode != PresentationMode.FULLSCREEN) {
                        if (!task.floating && !task.minimized && tasks.any { it.id != task.id && !it.floating && !it.minimized &&
                                (task.group == null || it.group != task.group) }) {
                            row(listOf("Swap position…" to { swapping = true; render() }))
                        }
                        if (task.floating) {
                            row(listOf("← Move" to { WorkspaceState.moveFloating(task.id, -.05f, 0f) },
                                "Move →" to { WorkspaceState.moveFloating(task.id, .05f, 0f) }))
                            row(listOf("↑ Move" to { WorkspaceState.moveFloating(task.id, 0f, -.05f) },
                                "Move ↓" to { WorkspaceState.moveFloating(task.id, 0f, .05f) }))
                        } else if (WorkspaceState.layout() == WorkspaceLayout.DWINDLE) {
                            row(listOf("Rotate split" to { WorkspaceState.focus(task.id); WorkspaceState.toggleSplit() }))
                        }
                        if (task.floating || WorkspaceState.layout() == WorkspaceLayout.DWINDLE) {
                            row(listOf("Narrower" to { WorkspaceState.focus(task.id); WorkspaceState.resizeFocused(true, -.05f) },
                                "Wider" to { WorkspaceState.focus(task.id); WorkspaceState.resizeFocused(true, .05f) }))
                            row(listOf("Shorter" to { WorkspaceState.focus(task.id); WorkspaceState.resizeFocused(false, -.05f) },
                                "Taller" to { WorkspaceState.focus(task.id); WorkspaceState.resizeFocused(false, .05f) }))
                        }
                    }
                    row(listOf("Hide window" to { WorkspaceState.minimize(task.id) },
                        (if (task.workspace == 0) "Return from shelf" else "Put on shelf") to {
                            WorkspaceState.moveToWorkspace(task.id, if (task.workspace == 0) WorkspaceState.activeWorkspace else 0)
                        }))
                    if (task.group != null) row(listOf("Ungroup window" to { WorkspaceState.toggleGroup(task.id, detach = true) }))
                    task.closeError?.let { label(it) }
                    row(listOf("Close window…" to {
                        AlertDialog.Builder(activity).setTitle("Close ${task.label}?")
                            .setMessage("Unsaved work may be lost. Hide keeps the window running.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Close window") { _, _ ->
                                AndroidTaskSynchronizer.closeTask(task) { removed ->
                                    if (!removed) Toast.makeText(activity, "Could not close ${task.label}", Toast.LENGTH_SHORT).show()
                                }
                            }.show()
                    }))
                }
            }
        }
        // Reconcile callbacks may arrive repeatedly. Rebuild only for actual presentation changes,
        // preserving the scroll position while arranging several windows.
        var snapshot = ""
        val listener: () -> Unit = {
            val windows = WorkspaceState.tasks.map { listOf(it.id, it.label, it.placement, it.workspace,
                it.floating, it.pinned, it.mode, it.group, it.closeError) }
            val next = "${WorkspaceState.activeWorkspace}/${WorkspaceState.layout()}/${WorkspaceState.scratchpadVisible}/$selected/$windows"
            if (next != snapshot) { snapshot = next; render() }
        }
        WorkspaceState.addListener(listener)
        try {
            render()
            dialog = AlertDialog.Builder(activity).setTitle("Windows")
                .setView(scroll).setPositiveButton("Done", null).create()
            dialog.setOnDismissListener {
                WorkspaceState.removeListener(listener)
                lease.release()
                onDismiss()
            }
            dialog.show()
        } catch (error: RuntimeException) {
            WorkspaceState.removeListener(listener)
            lease.release()
            onDismiss()
            throw error
        }
        return dialog
    }
}
