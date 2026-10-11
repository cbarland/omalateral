package com.lateral

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.text.Spannable
import com.lateral.beast.DesktopTextInputSession
import com.lateral.beast.LauncherSearchSession

/** A phone IME endpoint, not a duplicate EditText: the external field owns all editing state. */
class DesktopInputProxyView(context: Context) : View(context) {
    private var connection: InputConnection? = null
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 15 * resources.displayMetrics.scaledDensity
    }
    private val selectionUpdate = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !isShown || !DesktopTextInputSession.active) return
            connection?.getExtractedText(ExtractedTextRequest(), 0)?.let { state ->
                val spans = state.text as? Spannable
                (context.getSystemService(InputMethodManager::class.java)).updateSelection(
                    this@DesktopInputProxyView, state.startOffset + state.selectionStart,
                    state.startOffset + state.selectionEnd,
                    spans?.let(BaseInputConnection::getComposingSpanStart) ?: -1,
                    spans?.let(BaseInputConnection::getComposingSpanEnd) ?: -1,
                )
            }
            postDelayed(this, 100)
        }
    }

    init { isFocusable = true; isFocusableInTouchMode = true }
    override fun onDraw(canvas: Canvas) {
        label.color = InputSettings.theme.brightForeground
        canvas.drawText("Typing in Omalauncher", 8 * resources.displayMetrics.density,
            height / 2f - (label.ascent() + label.descent()) / 2f, label)
    }
    override fun onCheckIsTextEditor() = DesktopTextInputSession.active
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        removeCallbacks(selectionUpdate)
        connection = DesktopTextInputSession.connect(outAttrs)
        post(selectionUpdate)
        return connection
    }
    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (DesktopTextInputSession.active && keyCode in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)) {
            if (event.action == KeyEvent.ACTION_UP) {
                DesktopTextInputSession.close()
                LauncherSearchSession.close()
            }
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        connection?.sendKeyEvent(event) == true || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        connection?.sendKeyEvent(event) == true || super.onKeyUp(keyCode, event)
    fun stop() { removeCallbacks(selectionUpdate); connection = null }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
}
