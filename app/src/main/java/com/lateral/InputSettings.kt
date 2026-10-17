package com.lateral

import android.content.Context
import android.graphics.Color
import java.util.concurrent.CopyOnWriteArraySet


/** Persisted PhoneUI input preferences shared with the Beast workspace. */
object InputSettings {
    private const val PREFS = "lateral_input"
    private const val KEY_SENSITIVITY = "cursor_sensitivity"
    private const val KEY_SCROLL_SENSITIVITY = "scroll_sensitivity"
    private const val KEY_PINCH_ZOOM_SENSITIVITY = "pinch_zoom_sensitivity"
    /** Retained only to migrate installations created before scroll sources were split. */
    private const val KEY_INVERT_SCROLL = "invert_scroll"
    private const val KEY_INVERT_TWO_FINGER_SCROLL = "invert_two_finger_scroll"
    private const val KEY_INVERT_SCROLLBAR_SCROLL = "invert_scrollbar_scroll"
    private const val KEY_ULTRAWIDE = "beast_ultrawide"
    private const val KEY_BEAST_TIMING_SET = "beast_timing_set"
    private const val KEY_BEAST_TIMING_WIDTH = "beast_timing_width"
    private const val KEY_BEAST_TIMING_HEIGHT = "beast_timing_height"
    private const val KEY_BEAST_TIMING_REFRESH = "beast_timing_refresh"
    private const val KEY_STANDARD_UI_SCALE = "standard_ui_scale"
    private const val KEY_ULTRAWIDE_UI_SCALE = "ultrawide_ui_scale"
    private const val KEY_STANDARD_FONT_SCALE = "standard_font_scale"
    private const val KEY_ULTRAWIDE_FONT_SCALE = "ultrawide_font_scale"
    private const val KEY_HOSTED_APP_SCALE = "hosted_app_scale"
    private const val KEY_SUPERSAMPLING = "display_supersampling"
    private const val KEY_START_MINIMIZED = "start_open_apps_minimized"
    private const val KEY_SHOW_PHONE_TASKBAR = "show_phone_taskbar"
    private const val KEY_THEME = "omarchy_theme"
    private const val KEY_THEME_PALETTE = "omarchy_theme_palette"

    @Volatile var cursorSensitivity = 1.4f
        private set
    @Volatile var scrollSensitivity = 1.0f
        private set
    @Volatile var pinchZoomSensitivity = 1.0f
        private set
    @Volatile var invertTwoFingerScroll = false
        private set
    @Volatile var invertScrollbarScroll = false
        private set
    @Volatile var ultrawideEnabled = false
        private set
    @Volatile var preferredBeastTiming: BeastTiming? = null
        private set
    @Volatile var standardUiScale = 1.10f
        private set
    @Volatile var ultrawideUiScale = 1.50f
        private set
    @Volatile var standardFontScale = 1.10f
        private set
    @Volatile var ultrawideFontScale = 1.50f
        private set
    @Volatile var hostedAppScale = 1f
        private set
    @Volatile var displaySupersampling = 1.25f
        private set
    @Volatile var startOpenAppsMinimized = true
        private set
    /** PhoneUI defaults to a clean touchpad-only controller; the taskbar is opt-in. */
    @Volatile var showPhoneTaskbar = false
        private set
    @Volatile var theme = OmarchyThemes.default
        private set
    @Volatile var accentColor = OmarchyThemes.default.accent
        private set
    /** PhoneUI is authoritative for hosted-app resources, independent of Beast chrome. */
    @Volatile var phoneAppDensityDpi = DEFAULT_PHONE_APP_DENSITY_DPI
        private set
    @Volatile var phoneFontScale = 1f
        private set
    @Volatile var phoneAppWindowHeightPx = 1
        private set
    private val sensitivityListeners = CopyOnWriteArraySet<(Float) -> Unit>()
    private val appearanceListeners = CopyOnWriteArraySet<() -> Unit>()
    private val phoneConfigurationListeners = CopyOnWriteArraySet<(PhoneAppConfiguration) -> Unit>()

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cursorSensitivity = prefs.getFloat(KEY_SENSITIVITY, 1.4f)
        scrollSensitivity = prefs.getFloat(KEY_SCROLL_SENSITIVITY, 1.0f).coerceIn(.4f, 2.5f)
        pinchZoomSensitivity = prefs.getFloat(KEY_PINCH_ZOOM_SENSITIVITY, 1.0f).coerceIn(.4f, 2.5f)
        val legacyInvertScroll = prefs.getBoolean(KEY_INVERT_SCROLL, true)
        // Prior builds had hard-coded app directions and used the one toggle only
        // for workspace scrolling. Preserve that behavior on migration.
        invertTwoFingerScroll = prefs.getBoolean(KEY_INVERT_TWO_FINGER_SCROLL, false)
        invertScrollbarScroll = prefs.getBoolean(
            KEY_INVERT_SCROLLBAR_SCROLL,
            !legacyInvertScroll,
        )
        ultrawideEnabled = prefs.getBoolean(KEY_ULTRAWIDE, false)
        preferredBeastTiming = if (prefs.getBoolean(KEY_BEAST_TIMING_SET, false)) {
            BeastTiming(
                prefs.getInt(KEY_BEAST_TIMING_WIDTH, 0),
                prefs.getInt(KEY_BEAST_TIMING_HEIGHT, 0),
                prefs.getInt(KEY_BEAST_TIMING_REFRESH, 0),
            ).takeIf(BeastTiming::isValid)
        } else if (ultrawideEnabled) {
            // Migrate the older ultrawide-only preference to the first supported
            // ultrawide timing. Later timing changes are stored exactly.
            BeastTiming(3840, 1200, 60)
        } else {
            null
        }
        standardUiScale = prefs.getFloat(KEY_STANDARD_UI_SCALE, 1.10f).coerceIn(.85f, 2.00f)
        ultrawideUiScale = prefs.getFloat(KEY_ULTRAWIDE_UI_SCALE, 1.50f).coerceIn(.85f, 5.00f)
        // UI geometry is authoritative for the one consolidated appearance scale.
        // Ignore legacy divergent font values and lock text to the same value.
        standardFontScale = standardUiScale
        ultrawideFontScale = ultrawideUiScale
        hostedAppScale = prefs.getFloat(KEY_HOSTED_APP_SCALE, 1f).coerceIn(.6f, 1.4f)
        displaySupersampling = prefs.getFloat(KEY_SUPERSAMPLING, 1.25f).coerceIn(1f, 2f)
        startOpenAppsMinimized = prefs.getBoolean(KEY_START_MINIMIZED, true)
        showPhoneTaskbar = prefs.getBoolean(KEY_SHOW_PHONE_TASKBAR, false)
        val selectedTheme = prefs.getString(KEY_THEME, OmarchyThemes.DEFAULT_ID).orEmpty()
        val savedPalette = runCatching {
            val json = org.json.JSONObject(prefs.getString(KEY_THEME_PALETTE, null) ?: return@runCatching null)
            if (json.optString("id") != selectedTheme) return@runCatching null
            OmarchyTheme.fromPalette(selectedTheme, json.optString("name", selectedTheme),
                json.keys().asSequence().associateWith { json.optString(it) })
        }.getOrNull()
        theme = savedPalette ?: OmarchyThemes.byId(selectedTheme)
        accentColor = theme.accent
    }

    fun setCursorSensitivity(context: Context, value: Float) {
        cursorSensitivity = value.coerceIn(.4f, 2.5f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SENSITIVITY, cursorSensitivity).apply()
        sensitivityListeners.forEach { it(cursorSensitivity) }
    }

    fun setScrollSensitivity(context: Context, value: Float) {
        scrollSensitivity = value.coerceIn(.4f, 2.5f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SCROLL_SENSITIVITY, scrollSensitivity).apply()
    }

    fun setPinchZoomSensitivity(context: Context, value: Float) {
        pinchZoomSensitivity = value.coerceIn(.4f, 2.5f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_PINCH_ZOOM_SENSITIVITY, pinchZoomSensitivity).apply()
    }

    fun addSensitivityListener(listener: (Float) -> Unit) { sensitivityListeners += listener }
    fun removeSensitivityListener(listener: (Float) -> Unit) { sensitivityListeners -= listener }

    fun uiScale(ultrawide: Boolean) = if (ultrawide) ultrawideUiScale else standardUiScale
    fun fontScale(ultrawide: Boolean) = uiScale(ultrawide)

    fun setUiScale(context: Context, ultrawide: Boolean, value: Float) {
        val scale = value.coerceIn(.85f, if (ultrawide) 5.00f else 2.00f)
        if (ultrawide) {
            ultrawideUiScale = scale
            ultrawideFontScale = scale
        } else {
            standardUiScale = scale
            standardFontScale = scale
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(if (ultrawide) KEY_ULTRAWIDE_UI_SCALE else KEY_STANDARD_UI_SCALE, scale)
            .putFloat(if (ultrawide) KEY_ULTRAWIDE_FONT_SCALE else KEY_STANDARD_FONT_SCALE, scale)
            .apply()
        appearanceListeners.forEach { it() }
    }

    fun setFontScale(context: Context, ultrawide: Boolean, value: Float) {
        setUiScale(context, ultrawide, value)
    }

    fun addAppearanceListener(listener: () -> Unit) { appearanceListeners += listener }
    fun removeAppearanceListener(listener: () -> Unit) { appearanceListeners -= listener }

    fun updatePhoneAppConfiguration(densityDpi: Int, fontScale: Float, windowHeightPx: Int) {
        val next = PhoneAppConfiguration(
            densityDpi.coerceIn(MIN_PHONE_APP_DENSITY_DPI, MAX_PHONE_APP_DENSITY_DPI),
            fontScale.coerceIn(.5f, 2.0f),
            windowHeightPx.coerceAtLeast(1),
            hostedAppScale,
            displaySupersampling,
        )
        if (phoneAppDensityDpi == next.densityDpi && phoneFontScale == next.fontScale &&
            phoneAppWindowHeightPx == next.windowHeightPx
        ) return
        phoneAppDensityDpi = next.densityDpi
        phoneFontScale = next.fontScale
        phoneAppWindowHeightPx = next.windowHeightPx
        phoneConfigurationListeners.forEach { it(next) }
    }

    fun addPhoneAppConfigurationListener(listener: (PhoneAppConfiguration) -> Unit) {
        phoneConfigurationListeners += listener
    }

    fun removePhoneAppConfigurationListener(listener: (PhoneAppConfiguration) -> Unit) {
        phoneConfigurationListeners -= listener
    }

    fun setHostedAppScale(context: Context, value: Float) {
        val next = value.coerceIn(.6f, 1.4f)
        if (hostedAppScale == next) return
        hostedAppScale = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_HOSTED_APP_SCALE, next).apply()
        phoneConfigurationListeners.forEach {
            it(currentPhoneAppConfiguration())
        }
    }

    fun setDisplaySupersampling(context: Context, value: Float) {
        val next = value.coerceIn(1f, 2f)
        if (displaySupersampling == next) return
        displaySupersampling = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SUPERSAMPLING, next).apply()
        phoneConfigurationListeners.forEach { it(currentPhoneAppConfiguration()) }
    }

    private fun currentPhoneAppConfiguration() = PhoneAppConfiguration(
        phoneAppDensityDpi,
        phoneFontScale,
        phoneAppWindowHeightPx,
        hostedAppScale,
        displaySupersampling,
    )

    fun setTheme(context: Context, next: OmarchyTheme) {
        if (theme == next) return
        theme = next
        accentColor = next.accent
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME, next.id)
            .putString(KEY_THEME_PALETTE, org.json.JSONObject(next.palette()).put("id", next.id).put("name", next.name).toString())
            .apply()
        appearanceListeners.forEach { it() }
    }

    fun resetTheme(context: Context) {
        setTheme(context, OmarchyThemes.default)
    }

    fun accentOutlineColor(): Int = theme.selection

    fun setStartOpenAppsMinimized(context: Context, value: Boolean) {
        startOpenAppsMinimized = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_START_MINIMIZED, value).apply()
    }

    fun setShowPhoneTaskbar(context: Context, value: Boolean) {
        showPhoneTaskbar = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SHOW_PHONE_TASKBAR, value).apply()
        appearanceListeners.forEach { it() }
    }

    fun setInvertTwoFingerScroll(context: Context, value: Boolean) {
        invertTwoFingerScroll = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_INVERT_TWO_FINGER_SCROLL, value).apply()
    }

    fun setInvertScrollbarScroll(context: Context, value: Boolean) {
        invertScrollbarScroll = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_INVERT_SCROLLBAR_SCROLL, value).apply()
    }

    fun setUltrawideEnabled(context: Context, value: Boolean) {
        ultrawideEnabled = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ULTRAWIDE, value).apply()
        appearanceListeners.forEach { it() }
    }

    fun setPreferredBeastTiming(context: Context, timing: BeastTiming) {
        if (!timing.isValid()) return
        preferredBeastTiming = timing
        ultrawideEnabled = timing.isUltrawide
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BEAST_TIMING_SET, true)
            .putInt(KEY_BEAST_TIMING_WIDTH, timing.width)
            .putInt(KEY_BEAST_TIMING_HEIGHT, timing.height)
            .putInt(KEY_BEAST_TIMING_REFRESH, timing.refreshRate)
            .putBoolean(KEY_ULTRAWIDE, timing.isUltrawide)
            .apply()
        appearanceListeners.forEach { it() }
    }

    data class BeastTiming(val width: Int, val height: Int, val refreshRate: Int) {
        val isUltrawide: Boolean get() = width >= 3000
        fun isValid(): Boolean = width > 0 && height > 0 && refreshRate > 0
    }

    data class PhoneAppConfiguration(
        val densityDpi: Int,
        val fontScale: Float,
        val windowHeightPx: Int,
        val hostedAppScale: Float,
        val supersampling: Float,
    )

    private const val DEFAULT_PHONE_APP_DENSITY_DPI = 240
    private const val MIN_PHONE_APP_DENSITY_DPI = 120
    private const val MAX_PHONE_APP_DENSITY_DPI = 640
}
