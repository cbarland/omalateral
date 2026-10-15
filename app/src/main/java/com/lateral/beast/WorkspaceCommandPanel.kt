package com.lateral.beast

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import com.lateral.InputSettings
import com.lateral.TransientPanelCoordinator

internal data class WorkspaceCommand(
    val title: String,
    val detail: String,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val run: () -> Unit,
)

/** One keyboard/pointer-friendly shell menu, using the same modal lease as settings. */
internal object WorkspaceCommandPanel {
    fun show(activity: Activity, title: String, subtitle: String, commands: List<WorkspaceCommand>,
        scale: Float, opened: (Dialog) -> Unit, closed: (Dialog) -> Unit): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val lease = TransientPanelCoordinator.claim(activity.display?.displayId ?: 0,
            TransientPanelCoordinator.Kind.SETTINGS) { dialog.dismiss() }
        fun dp(n: Int) = (n * activity.resources.displayMetrics.density * scale.coerceIn(.85f, 1.6f)).toInt()
        fun text(value: String, color: Int, size: Float) = TextView(activity).apply {
            text = value; typeface = Typeface.MONOSPACE; setTextColor(color)
            textSize = size * scale.coerceIn(.85f, 1.5f); includeFontPadding = false
        }
        val palette = InputSettings.theme
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
            background = GradientDrawable().apply { setColor(palette.background); setStroke(dp(1).coerceAtLeast(1), palette.accent) }
        }
        content.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(title, palette.accent, 13f), LinearLayout.LayoutParams(0, dp(36), 1f))
            addView(text("×", palette.foreground, 20f).apply {
                gravity = Gravity.CENTER; contentDescription = "Close menu"; isFocusable = true
                setOnClickListener { dialog.dismiss() }; installBeastHover()
            }, LinearLayout.LayoutParams(dp(40), dp(36)))
        })
        content.addView(text(subtitle, palette.lightForeground, 11f).apply { setPadding(0, dp(4), 0, dp(12)) })
        var editor: DesktopTextInputSession.Editor? = null
        val rows = mutableListOf<Pair<WorkspaceCommand, View>>()
        var selected = 0
        fun choices() = rows.filter { it.second.visibility == View.VISIBLE && it.first.enabled }
        fun runSelected() { choices().getOrNull(selected)?.first?.let { command -> dialog.dismiss(); command.run() } }
        val search = EditText(activity).apply {
            hint = "Type to filter…"; setSingleLine(true); typeface = Typeface.MONOSPACE
            textSize = 13f * scale; setTextColor(palette.foreground); setHintTextColor(palette.muted)
            setPadding(dp(10), dp(8), dp(10), dp(8)); setBackgroundColor(palette.darkerBackground)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnClickListener {
                requestFocus()
                editor?.let(DesktopTextInputSession::release)
                editor = DesktopTextInputSession.begin({ info -> checkNotNull(onCreateInputConnection(info)) }) { dialog.dismiss() }
            }
            setOnEditorActionListener { _, _, _ -> runSelected(); true }
            setOnKeyListener { _, code, event ->
                if (event.action == KeyEvent.ACTION_DOWN && code in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP)) {
                    val available = choices()
                    if (available.isNotEmpty()) { selected = (selected + if (code == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1).coerceIn(0, available.lastIndex); available[selected].second.requestFocus() }
                    true
                } else false
            }
        }
        content.addView(search, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(10) })
        commands.forEach { command ->
            val color = if (command.destructive) palette.danger else palette.foreground
            val name = text(command.title, color, 13f)
            val hint = text(command.detail, palette.lightForeground, 11f)
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(10), dp(9))
                addView(name); addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                minimumHeight = dp(52); isEnabled = command.enabled; isFocusable = command.enabled
                alpha = if (command.enabled) 1f else .4f
                contentDescription = "${command.title}. ${command.detail}"
                setOnClickListener { if (command.enabled) { dialog.dismiss(); command.run() } }
                installBeastHover(listOf(name to { color }, hint to { palette.lightForeground }))
            }
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
            rows += command to row
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim().orEmpty()
                rows.forEach { (command, view) -> view.visibility = if (query.isEmpty() || "${command.title} ${command.detail}".contains(query, ignoreCase = true)) View.VISIBLE else View.GONE }
                selected = 0
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        dialog.setContentView(ScrollView(activity).apply { addView(content) }, ViewGroup.LayoutParams(-1, -2))
        dialog.setOnShowListener { opened(dialog); search.requestFocus() }
        dialog.setOnKeyListener { _, _, event -> OmarchyShortcuts.dispatch(event) }
        dialog.setOnDismissListener { editor?.let(DesktopTextInputSession::release); lease.release(); closed(dialog) }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = .28f }
            val bounds = if (android.os.Build.VERSION.SDK_INT >= 30) activity.windowManager.currentWindowMetrics.bounds
                else android.graphics.Rect(0, 0, activity.resources.displayMetrics.widthPixels, activity.resources.displayMetrics.heightPixels)
            setLayout(minOf(dp(440), bounds.width() - dp(32)).coerceAtLeast(1),
                minOf(dp(640), bounds.height() - dp(40)).coerceAtLeast(1))
            val popup = this
            content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val height = minOf(content.measuredHeight, dp(640), bounds.height() - dp(40)).coerceAtLeast(1)
                if (popup.attributes.height != height) popup.setLayout(popup.attributes.width, height)
            }
            content.requestLayout()
        }
        return dialog
    }
}
