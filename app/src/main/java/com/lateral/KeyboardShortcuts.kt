package com.lateral

import android.content.Context
import android.view.KeyEvent
import com.lateral.privileged.PrivilegedHotkeys
import com.lateral.privileged.PrivilegedService

data class KeyChord(val modifiers: Int, val keyCode: Int) {
    fun encoded(): String = "$modifiers:$keyCode"

    fun label(): String = buildList {
        if (modifiers and MOD_CTRL != 0) add("Ctrl")
        if (modifiers and MOD_ALT != 0) add("Alt")
        if (modifiers and MOD_SHIFT != 0) add("Shift")
        if (modifiers and MOD_META != 0) add("Meta")
        add(keyLabel(keyCode))
    }.joinToString("+")

    companion object {
        const val MOD_CTRL = 1
        const val MOD_ALT = 2
        const val MOD_SHIFT = 4
        const val MOD_META = 8

        fun fromEvent(event: KeyEvent): KeyChord = KeyChord(
            (if (event.isCtrlPressed) MOD_CTRL else 0) or
                (if (event.isAltPressed) MOD_ALT else 0) or
                (if (event.isShiftPressed) MOD_SHIFT else 0) or
                (if (event.isMetaPressed) MOD_META else 0),
            event.keyCode,
        )

        fun decode(raw: String?): KeyChord? {
            val parts = raw?.split(':') ?: return null
            if (parts.size != 2) return null
            return KeyChord(parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: return null)
        }

        private fun keyLabel(keyCode: Int): String = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
            KeyEvent.KEYCODE_DPAD_UP -> "Up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
            KeyEvent.KEYCODE_SPACE -> "Space"
            KeyEvent.KEYCODE_TAB -> "Tab"
            KeyEvent.KEYCODE_COMMA -> ","
            KeyEvent.KEYCODE_EQUALS -> "+"
            KeyEvent.KEYCODE_MINUS -> "-"
            else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
                .replace('_', ' ')
                .lowercase().replaceFirstChar { it.uppercase() }
        }
    }
}

enum class ShortcutAction(val code: Int, val title: String, val default: KeyChord) {
    APPS(PrivilegedHotkeys.HK_APPS, "Apps launcher", KeyChord(KeyChord.MOD_ALT, KeyEvent.KEYCODE_A)),
    FOCUS_LEFT(PrivilegedHotkeys.HK_FOCUS_LEFT, "Previous workspace app", KeyChord(KeyChord.MOD_CTRL, KeyEvent.KEYCODE_DPAD_LEFT)),
    FOCUS_RIGHT(PrivilegedHotkeys.HK_FOCUS_RIGHT, "Next workspace app", KeyChord(KeyChord.MOD_CTRL, KeyEvent.KEYCODE_DPAD_RIGHT)),
    FULLSCREEN(PrivilegedHotkeys.HK_FULLSCREEN, "Toggle focused app fullscreen", KeyChord(KeyChord.MOD_CTRL, KeyEvent.KEYCODE_DPAD_UP)),
    MINIMIZE(PrivilegedHotkeys.HK_MINIMIZE, "Minimize focused app", KeyChord(KeyChord.MOD_CTRL, KeyEvent.KEYCODE_DPAD_DOWN)),
    FRAME(PrivilegedHotkeys.HK_FRAME, "Toggle phone / tablet frame", KeyChord(KeyChord.MOD_ALT, KeyEvent.KEYCODE_T)),
    SHOW_ALL(PrivilegedHotkeys.HK_SHOW_ALL, "Show all apps", KeyChord(KeyChord.MOD_ALT, KeyEvent.KEYCODE_S)),
    MINIMIZE_ALL(PrivilegedHotkeys.HK_MINIMIZE_ALL, "Minimize all apps", KeyChord(KeyChord.MOD_ALT, KeyEvent.KEYCODE_M)),
    CLOSE(PrivilegedHotkeys.HK_CLOSE, "Close focused app", KeyChord(KeyChord.MOD_ALT, KeyEvent.KEYCODE_W)),
    CLOSE_ALL(PrivilegedHotkeys.HK_CLOSE_ALL, "Close all apps", KeyChord(KeyChord.MOD_CTRL or KeyChord.MOD_ALT or KeyChord.MOD_SHIFT, KeyEvent.KEYCODE_W)),
    CAPTURE(PrivilegedHotkeys.HK_CAPTURE, "Capture workspace", KeyChord(KeyChord.MOD_ALT, KeyEvent.KEYCODE_P)),
    DISPLAY_SETTINGS(PrivilegedHotkeys.HK_DISPLAY_SETTINGS, "Open display settings", KeyChord(KeyChord.MOD_ALT, KeyEvent.KEYCODE_D)),
    GENERAL_SETTINGS(PrivilegedHotkeys.HK_GENERAL_SETTINGS, "Open general settings", KeyChord(KeyChord.MOD_ALT, KeyEvent.KEYCODE_O)),
}

object KeyboardShortcuts {
    private const val PREFS = "keyboard_shortcuts"
    private const val PREFIX = "binding_"

    enum class Validation { OK, NO_MODIFIER, MODIFIER_ONLY, SYSTEM_RESERVED, DUPLICATE }

    val reservedLabels = listOf(
        "Super / Command shortcuts are handled by the Omarchy shell",
        "Alt+Tab, Alt+Space, Alt+Left, Alt+Right",
        "Ctrl+Space, Ctrl+, and Ctrl+Alt+Z",
        "Unmodified keys and Android Home, Back, volume, power, and app-switch keys",
    )

    fun binding(context: Context, action: ShortcutAction): KeyChord {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREFIX + action.name, null)
        return KeyChord.decode(raw) ?: action.default
    }

    fun bindings(context: Context): Map<ShortcutAction, KeyChord> =
        ShortcutAction.entries.associateWith { binding(context, it) }

    fun match(context: Context, event: KeyEvent): ShortcutAction? {
        if (event.action != KeyEvent.ACTION_DOWN) return null
        val chord = KeyChord.fromEvent(event)
        return bindings(context).entries.firstOrNull { it.value == chord }?.key
    }

    fun validate(context: Context, action: ShortcutAction, chord: KeyChord): Validation {
        if (chord.modifiers == 0) return Validation.NO_MODIFIER
        if (isModifierKey(chord.keyCode)) return Validation.MODIFIER_ONLY
        if (isReserved(chord)) return Validation.SYSTEM_RESERVED
        if (bindings(context).any { (other, value) -> other != action && value == chord }) return Validation.DUPLICATE
        return Validation.OK
    }

    fun set(context: Context, action: ShortcutAction, chord: KeyChord): Validation {
        val validation = validate(context, action, chord)
        if (validation != Validation.OK) return validation
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREFIX + action.name, chord.encoded()).apply()
        PrivilegedService.syncHotkeyBindings(context)
        return Validation.OK
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        PrivilegedService.syncHotkeyBindings(context)
    }

    fun payload(context: Context): Array<String> = bindings(context).map { (action, chord) ->
        "${action.code}|${chord.modifiers}|${chord.keyCode}"
    }.toTypedArray()

    fun isReserved(chord: KeyChord): Boolean {
        if (chord.modifiers and KeyChord.MOD_META != 0) return true
        if (chord.keyCode in SYSTEM_KEYS) return true
        return chord in RESERVED_CHORDS
    }

    private fun isModifierKey(keyCode: Int) = keyCode in setOf(
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
    )

    private val SYSTEM_KEYS = setOf(
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_APP_SWITCH,
    )
    private val RESERVED_CHORDS = setOf(
        KeyChord(KeyChord.MOD_ALT, KeyEvent.KEYCODE_TAB),
        KeyChord(KeyChord.MOD_ALT, KeyEvent.KEYCODE_SPACE),
        KeyChord(KeyChord.MOD_ALT, KeyEvent.KEYCODE_DPAD_LEFT),
        KeyChord(KeyChord.MOD_ALT, KeyEvent.KEYCODE_DPAD_RIGHT),
        KeyChord(KeyChord.MOD_CTRL, KeyEvent.KEYCODE_SPACE),
        KeyChord(KeyChord.MOD_CTRL, KeyEvent.KEYCODE_COMMA),
        KeyChord(KeyChord.MOD_CTRL or KeyChord.MOD_ALT, KeyEvent.KEYCODE_Z),
    )
}

object WorkspaceShortcutDispatcher {
    @Volatile var handler: ((ShortcutAction) -> Unit)? = null
    fun dispatch(code: Int) {
        ShortcutAction.entries.firstOrNull { it.code == code }?.let { handler?.invoke(it) }
    }
}
