package com.lateral

import android.content.Context
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Shared, compact input/settings menu used by PhoneUI and the external workspace. */
object InputSettingsPanel {
    fun show(
        context: Context,
        onModalVisibilityChanged: (Boolean) -> Unit = {},
        onDialogWindowCreated: (android.app.Dialog) -> Unit = {},
        onDialogWindowDismissed: (android.app.Dialog) -> Unit = {},
    ) {
        val activity = context as? Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        var dialog: android.app.AlertDialog? = null
        val lease = TransientPanelCoordinator.claim(
            activity.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY,
            TransientPanelCoordinator.Kind.SETTINGS,
        ) { dialog?.dismiss() }
        val density = context.resources.displayMetrics.density
        val padding = (20 * density).toInt()
        fun dp(value: Int) = (value * density).toInt()
        fun colorHex(color: Int) = "#%06X".format(color and 0xFFFFFF)
        fun showTrackedDialog(builder: android.app.AlertDialog.Builder) {
            val childDialog = builder.create()
            childDialog.setOnShowListener { onDialogWindowCreated(childDialog) }
            childDialog.setOnDismissListener { onDialogWindowDismissed(childDialog) }
            childDialog.show()
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val sensitivityLabel = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(210, 220, 218))
        }
        fun updateSensitivityLabel(value: Float) {
            sensitivityLabel.text = "Cursor sensitivity  ${"%.1f".format(value)}×"
        }
        updateSensitivityLabel(InputSettings.cursorSensitivity)
        val sensitivity = SeekBar(context).apply {
            max = 21
            progress = ((InputSettings.cursorSensitivity - .4f) * 10).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    val value = .4f + progress / 10f
                    InputSettings.setCursorSensitivity(context, value)
                    updateSensitivityLabel(value)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        fun gestureSensitivityControl(
            title: String,
            current: () -> Float,
            set: (Float) -> Unit,
        ): LinearLayout {
            val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val valueLabel = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                setTextColor(Color.rgb(210, 220, 218))
            }
            fun update(value: Float) {
                valueLabel.text = "$title  ${"%.1f".format(value)}×"
            }
            update(current())
            row.addView(valueLabel)
            row.addView(SeekBar(context).apply {
                max = 21
                progress = ((current() - .4f) * 10).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        val value = .4f + progress / 10f
                        set(value)
                        update(value)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                })
            })
            return row
        }
        val scrollSensitivity = gestureSensitivityControl(
            "Scroll sensitivity",
            { InputSettings.scrollSensitivity },
            { InputSettings.setScrollSensitivity(context, it) },
        )
        val pinchZoomSensitivity = gestureSensitivityControl(
            "Pinch zoom sensitivity",
            { InputSettings.pinchZoomSensitivity },
            { InputSettings.setPinchZoomSensitivity(context, it) },
        )
        val invertTwoFingerScroll = Switch(context).apply {
            text = "Invert two-finger scroll"
            isChecked = InputSettings.invertTwoFingerScroll
            setOnCheckedChangeListener { _, checked ->
                InputSettings.setInvertTwoFingerScroll(context, checked)
            }
        }
        val invertScrollbarScroll = Switch(context).apply {
            text = "Invert scrollbar scroll"
            isChecked = InputSettings.invertScrollbarScroll
            setOnCheckedChangeListener { _, checked ->
                InputSettings.setInvertScrollbarScroll(context, checked)
            }
        }
        val momentum = Switch(context).apply {
            text = "Scroll momentum"
            isChecked = InputSettings.momentumEnabled
            setOnCheckedChangeListener { _, checked -> InputSettings.setMomentumEnabled(context, checked) }
        }
        val startMinimized = Switch(context).apply {
            text = "Startup: open apps minimized"
            isChecked = InputSettings.startOpenAppsMinimized
            setOnCheckedChangeListener { _, checked ->
                InputSettings.setStartOpenAppsMinimized(context, checked)
            }
        }
        val showPhoneTaskbar = Switch(context).apply {
            text = "Show PhoneUI taskbar"
            isChecked = InputSettings.showPhoneTaskbar
            setOnCheckedChangeListener { _, checked ->
                InputSettings.setShowPhoneTaskbar(context, checked)
            }
        }
        fun alignmentControl(title: String, current: () -> BarAlignment, set: (BarAlignment) -> Unit) =
            TextView(context).apply {
                typeface = Typeface.MONOSPACE
                setTextColor(Color.rgb(210, 220, 218))
                setPadding(0, dp(8), 0, dp(8))
                fun update() { text = "$title  ${current().name.lowercase()}" }
                update()
                setOnClickListener {
                    val choices = BarAlignment.entries.map { it.name.lowercase() }.toTypedArray()
                    showTrackedDialog(android.app.AlertDialog.Builder(context)
                        .setTitle(title)
                        .setSingleChoiceItems(choices, current().ordinal) { dialog, which ->
                            set(BarAlignment.entries[which])
                            update()
                            dialog.dismiss()
                        })
                }
            }
        val taskbarAlignment = alignmentControl(
            "Taskbar alignment",
            { InputSettings.taskbarAlignment },
            { InputSettings.setTaskbarAlignment(context, it) },
        )
        val personalizationTitle = TextView(context).apply {
            text = "Personalization / Omarchy theme"
            typeface = Typeface.MONOSPACE
            setTextColor(InputSettings.accentColor)
            setPadding(0, dp(16), 0, dp(4))
        }
        val themeValueLabel = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(InputSettings.theme.foreground)
            setPadding(0, 0, 0, dp(4))
        }
        val themeButtons = linkedMapOf<String, TextView>()
        lateinit var refreshThemeWidgets: () -> Unit
        fun themeButton(theme: OmarchyTheme) = TextView(context).apply {
            text = theme.name.uppercase()
            gravity = android.view.Gravity.CENTER
            typeface = Typeface.MONOSPACE
            textSize = 11f
            isAllCaps = true
            isClickable = true
            setPadding(dp(6), dp(10), dp(6), dp(10))
            setOnClickListener {
                InputSettings.setTheme(context, theme)
                refreshThemeWidgets()
            }
            themeButtons[theme.id] = this
        }
        val themeGrid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        OmarchyThemes.all.chunked(2).forEach { rowThemes ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(2), 0, dp(2))
            }
            rowThemes.forEach { theme ->
                row.addView(themeButton(theme), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    setMargins(0, 0, dp(4), 0)
                })
            }
            if (rowThemes.size == 1) row.addView(View(context), LinearLayout.LayoutParams(0, dp(42), 1f))
            themeGrid.addView(row)
        }
        val resetTheme = TextView(context).apply {
            text = "reset to Tokyo Night"
            typeface = Typeface.MONOSPACE
            setTextColor(InputSettings.accentColor)
            setPadding(0, dp(6), 0, dp(8))
            setOnClickListener {
                InputSettings.resetTheme(context)
                refreshThemeWidgets()
            }
        }
        val note = TextView(context).apply {
            text = "Omarchy themes apply their semantic palette to PhoneUI, BeastUI, controls, and the embedded keyboard. The selected theme is saved with LATERAL_."
            textSize = 12f
            setTextColor(InputSettings.theme.foreground)
            setPadding(0, dp(10), 0, 0)
        }
        val about = TextView(context).apply {
            text = "About / open-source licenses"
            typeface = Typeface.MONOSPACE
            setTextColor(InputSettings.accentColor)
            setPadding(0, dp(18), 0, dp(18))
            setOnClickListener {
                showTrackedDialog(android.app.AlertDialog.Builder(context)
                    .setTitle("LATERAL_ / licenses")
                    .setMessage(
                        "Embedded keyboard: FlorisBoard v0.5.2\n" +
                            "Copyright © FlorisBoard contributors\n\n" +
                            "FlorisBoard and LATERAL_'s integration changes are licensed " +
                            "under the Apache License 2.0. The complete license and " +
                            "attribution are distributed in third_party/florisboard/LICENSE " +
                            "and NOTICE.\n\nhttps://www.apache.org/licenses/LICENSE-2.0",
                    )
                    .setPositiveButton("Done", null)
                )
            }
        }
        refreshThemeWidgets = {
            val selected = InputSettings.theme
            themeValueLabel.text = "${selected.name}  ·  ${selected.mode.name.lowercase()}  ·  accent ${colorHex(selected.accent)}"
            themeValueLabel.setTextColor(selected.foreground)
            personalizationTitle.setTextColor(selected.accent)
            resetTheme.setTextColor(selected.accent)
            about.setTextColor(selected.accent)
            themeButtons.forEach { (id, button) ->
                val theme = OmarchyThemes.byId(id)
                val chosen = theme.id == selected.id
                button.setTextColor(if (chosen) theme.brightForeground else theme.foreground)
                button.background = GradientDrawable().apply {
                    setColor(theme.lighterBackground)
                    setStroke(dp(if (chosen) 2 else 1), if (chosen) theme.accent else theme.selection)
                    cornerRadius = dp(3).toFloat()
                }
            }
        }
        refreshThemeWidgets()
        panel.addView(Switch(context).apply {
            text = "Auto-start Omalateral desktop"
            isChecked = DesktopShellSettings.autoStart(context)
            setOnCheckedChangeListener { _, checked -> DesktopShellSettings.setAutoStart(context, checked) }
        })
        panel.addView(TextView(context).apply {
            text = "Turn this off to leave display startup to Android's desktop mode. Return Home on the monitor or reconnect it after changing this setting."
            setTextColor(InputSettings.theme.foreground)
            setPadding(0, dp(4), 0, dp(12))
        })
        panel.addView(sensitivityLabel)
        panel.addView(sensitivity)
        panel.addView(scrollSensitivity)
        panel.addView(pinchZoomSensitivity)
        panel.addView(invertTwoFingerScroll)
        panel.addView(invertScrollbarScroll)
        panel.addView(momentum)
        panel.addView(startMinimized)
        panel.addView(showPhoneTaskbar)
        panel.addView(taskbarAlignment)
        panel.addView(personalizationTitle)
        panel.addView(themeValueLabel)
        panel.addView(themeGrid)
        panel.addView(resetTheme)
        panel.addView(note)
        panel.addView(about)
        val scrollPanel = ScrollView(context).apply { addView(panel) }
        try {
            val settingsDialog = android.app.AlertDialog.Builder(context)
                .setTitle("LATERAL_ / settings")
                .setView(scrollPanel)
                .setPositiveButton("Done", null)
                .create()
            dialog = settingsDialog
            settingsDialog.setOnShowListener { onDialogWindowCreated(settingsDialog) }
            settingsDialog.setOnDismissListener {
                onDialogWindowDismissed(settingsDialog)
                lease.release()
                onModalVisibilityChanged(false)
            }
            settingsDialog.show()
            onModalVisibilityChanged(true)
        } catch (error: RuntimeException) {
            lease.release()
            onModalVisibilityChanged(false)
            Toast.makeText(context, error.message ?: "Could not open settings", Toast.LENGTH_SHORT).show()
        }
    }
}
