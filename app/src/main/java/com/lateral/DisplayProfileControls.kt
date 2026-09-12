package com.lateral

import android.content.Context
import android.graphics.Color
import android.widget.*
import java.util.Locale
import kotlin.math.roundToInt

/** Embedded in Display settings; saves against the displayed profile, never a stale mode. */
internal class DisplayProfileControls(context: Context) : LinearLayout(context) {
    private var target: DisplayPreferences.Target? = null
    init { orientation = VERTICAL; refresh() }

    fun refresh() {
        val next = DisplayPreferences.workspaceTarget(context)
        if (next.key == target?.key) return
        target = next
        removeAllViews()
        val profile = DisplayPreferences.load(context, next)
        fun label(text: String) = TextView(context).apply {
            this.text = text; setTextColor(Color.rgb(210, 220, 218)); textSize = 14f
        }
        addView(label("WORKSPACE PREFERENCES\n${next.label}"))
        addView(label("Saved for this display and resolution. Ratios are width:height."))
        fun ratio(title: String, value: Float): EditText {
            addView(label(title))
            return EditText(context).apply {
                setSingleLine(true)
                setText(String.format(Locale.US, "%.4f:1", value))
                hint = "e.g. 9:19.5 or 4:3"
                addView(this)
            }
        }
        val phone = ratio("Phone-style aspect ratio", profile.phoneAspect)
        val tablet = ratio("Tablet-style aspect ratio", profile.tabletAspect)
        fun save(change: (DisplayProfile) -> DisplayProfile) {
            if (DisplayPreferences.workspaceTarget(context).key != next.key) { refresh(); return }
            DisplayPreferences.save(context, next, change(DisplayPreferences.load(context, next)))
        }
        addView(Button(context).apply {
            text = "Apply aspect ratios"
            setOnClickListener {
                val p = DisplayProfile.parseAspect(phone.text.toString())
                val t = DisplayProfile.parseAspect(tablet.text.toString())
                phone.error = if (p == null) "Use a ratio between 1:4 and 3:1" else null
                tablet.error = if (t == null) "Use a ratio between 1:4 and 3:1" else null
                if (p != null && t != null) save { it.copy(phoneAspect = p, tabletAspect = t) }
            }
        })
        fun slider(title: String, current: Float, min: Float, max: Float, step: Float, change: (DisplayProfile, Float) -> DisplayProfile) {
            val text = label("")
            fun update(value: Float) { text.text = "$title  ${String.format(Locale.US, "%.2f", value)}×" }
            update(current); addView(text)
            addView(SeekBar(context).apply {
                contentDescription = title
                this.max = ((max - min) / step).roundToInt()
                progress = ((current - min) / step).roundToInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        update(min + progress * step)
                        if (fromUser && !bar.isPressed) save { change(it, min + progress * step) }
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) { save { change(it, min + bar.progress * step) } }
                })
            })
        }
        slider("Supersampling", profile.supersampling, 1f, 2f, .05f) { p, v -> p.copy(supersampling = v) }
        slider("UI + text scale", profile.uiScale, .85f, if (next.key.width >= 3000) 5f else 2f, .05f) { p, v -> p.copy(uiScale = v) }
        slider("Hosted app scale", profile.hostedScale, .6f, 5f, .05f) { p, v -> p.copy(hostedScale = v) }
    }
}
