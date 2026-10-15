package com.lateral

import android.graphics.Color

enum class OmarchyThemeMode { DARK, LIGHT }

/** The semantic palette shared by Omarchy's built-in themes. */
data class OmarchyTheme(
    val id: String,
    val name: String,
    val mode: OmarchyThemeMode,
    private val accentHex: String,
    private val selectionHex: String,
    private val mutedHex: String,
    private val backgroundHex: String,
    private val darkBackgroundHex: String,
    private val darkerBackgroundHex: String,
    private val lighterBackgroundHex: String,
    private val foregroundHex: String,
    private val darkForegroundHex: String,
    private val lightForegroundHex: String,
    private val brightForegroundHex: String,
    private val redHex: String,
) {
    val accent: Int get() = color(accentHex)
    val selection: Int get() = color(selectionHex)
    val muted: Int get() = color(mutedHex)
    val background: Int get() = color(backgroundHex)
    val darkBackground: Int get() = color(darkBackgroundHex)
    val darkerBackground: Int get() = color(darkerBackgroundHex)
    val lighterBackground: Int get() = color(lighterBackgroundHex)
    val foreground: Int get() = color(foregroundHex)
    val darkForeground: Int get() = color(darkForegroundHex)
    val lightForeground: Int get() = color(lightForegroundHex)
    val brightForeground: Int get() = color(brightForegroundHex)
    val danger: Int get() = color(redHex)

    private fun color(hex: String): Int = Color.parseColor(hex)

    /** Semantic colors, also used to persist installed community themes from Omalauncher. */
    fun palette(): Map<String, String> = mapOf(
        "mode" to mode.name.lowercase(), "accent" to accentHex, "selection" to selectionHex,
        "muted" to mutedHex, "background" to backgroundHex, "dark_background" to darkBackgroundHex,
        "darker_background" to darkerBackgroundHex, "lighter_background" to lighterBackgroundHex,
        "foreground" to foregroundHex, "dark_foreground" to darkForegroundHex,
        "light_foreground" to lightForegroundHex, "bright_foreground" to brightForegroundHex, "red" to redHex,
    )

    companion object {
        fun fromPalette(id: String, name: String, colors: Map<String, String>): OmarchyTheme? {
            val hex = Regex("#[0-9a-fA-F]{6}")
            fun valid(key: String) = colors[key]?.takeIf(hex::matches)?.uppercase(java.util.Locale.ROOT)
            val background = valid("background") ?: return null
            val foreground = valid("foreground") ?: return null
            val surface = valid("lighter_background") ?: background
            val border = valid("muted") ?: surface
            return OmarchyTheme(id, name, if (colors["mode"] == "light") OmarchyThemeMode.LIGHT else OmarchyThemeMode.DARK,
                valid("accent") ?: foreground, valid("selection") ?: surface, border, background,
                valid("dark_background") ?: background, valid("darker_background") ?: background, surface,
                foreground, valid("dark_foreground") ?: foreground, valid("light_foreground") ?: foreground,
                valid("bright_foreground") ?: foreground, valid("red") ?: foreground)
        }
    }
}

/** Official built-in Omarchy themes, kept in the same semantic palette shape as colors.toml. */
object OmarchyThemes {
    const val DEFAULT_ID = "tokyo-night"

    val all: List<OmarchyTheme> = listOf(
        OmarchyTheme("catppuccin", "Catppuccin", OmarchyThemeMode.DARK, "#89b4fa", "#45475a", "#585b70", "#1e1e2e", "#161622", "#101019", "#313244", "#cdd6f4", "#6c7086", "#bac2de", "#cdd6f4", "#f38ba8"),
        OmarchyTheme("catppuccin-latte", "Catppuccin Latte", OmarchyThemeMode.LIGHT, "#1e66f5", "#ccd0da", "#acb0be", "#eff1f5", "#e3e4e8", "#d7d8dc", "#dce0e8", "#4c4f69", "#9ca0b0", "#5c5f77", "#4c4f69", "#d20f39"),
        OmarchyTheme("ethereal", "Ethereal", OmarchyThemeMode.DARK, "#7d82d9", "#252e56", "#6d7db6", "#060B1E", "#040816", "#030610", "#131a3a", "#ffcead", "#6d7db6", "#c9b8a6", "#ffcead", "#ED5B5A"),
        OmarchyTheme("everforest", "Everforest", OmarchyThemeMode.DARK, "#7fbbb3", "#3d484d", "#475258", "#2d353b", "#21272c", "#181d20", "#343f44", "#d3c6aa", "#4f585e", "#9da9a0", "#d3c6aa", "#e67e80"),
        OmarchyTheme("flexoki-light", "Flexoki Light", OmarchyThemeMode.LIGHT, "#205EA6", "#CECDC3", "#B7B5AC", "#FFFCF0", "#f2efe4", "#e5e2d8", "#E6E4D9", "#100F0F", "#878580", "#403E3C", "#100F0F", "#D14D41"),
        OmarchyTheme("gruvbox", "Gruvbox", OmarchyThemeMode.DARK, "#7daea3", "#504945", "#665c54", "#282828", "#1e1e1e", "#161616", "#3c3836", "#d4be98", "#7c6f64", "#bdae93", "#d4be98", "#ea6962"),
        OmarchyTheme("hackerman", "Hackerman", OmarchyThemeMode.DARK, "#82FB9C", "#1f253a", "#2d3450", "#0B0C16", "#080910", "#06060c", "#151828", "#ddf7ff", "#6a6e95", "#b5c5db", "#ddf7ff", "#50f872"),
        OmarchyTheme("kanagawa", "Kanagawa", OmarchyThemeMode.DARK, "#dcd7ba", "#363646", "#54546D", "#1f1f28", "#17171e", "#111116", "#223249", "#dcd7ba", "#727169", "#c8c093", "#dcd7ba", "#c34043"),
        OmarchyTheme("last-horizon", "Last Horizon", OmarchyThemeMode.DARK, "#b59790", "#584e51", "#584e51", "#0c0b0c", "#090809", "#060606", "#0c0b0c", "#FAFCFB", "#584e51", "#cfd3cd", "#e2dddc", "#c38b7b"),
        OmarchyTheme("lumon", "Lumon", OmarchyThemeMode.DARK, "#8bc9eb", "#243d56", "#304860", "#16242d", "#101b21", "#0b1216", "#1b2d40", "#d6e2ee", "#4d86b0", "#d6e2ee", "#f2fcff", "#4d86b0"),
        OmarchyTheme("lupine", "Lupine", OmarchyThemeMode.LIGHT, "#3264eb", "#d0d0d0", "#9e9e9e", "#fafafa", "#ececec", "#dedede", "#f5f5f5", "#212121", "#757575", "#424242", "#000000", "#c900c4"),
        OmarchyTheme("matte-black", "Matte Black", OmarchyThemeMode.DARK, "#e68e0d", "#2a2a2a", "#333333", "#121212", "#0d0d0d", "#090909", "#1e1e1e", "#bebebe", "#555555", "#8a8a8d", "#bebebe", "#D35F5F"),
        OmarchyTheme("miasma", "Miasma", OmarchyThemeMode.DARK, "#78824b", "#383838", "#666666", "#222222", "#191919", "#121212", "#2c2c2c", "#c2c2b0", "#555555", "#8a8a7e", "#c2c2b0", "#685742"),
        OmarchyTheme("nord", "Nord", OmarchyThemeMode.DARK, "#81a1c1", "#434c5e", "#4c566a", "#2e3440", "#222730", "#191c23", "#3b4252", "#d8dee9", "#667080", "#adb5c4", "#d8dee9", "#bf616a"),
        OmarchyTheme("osaka-jade", "Osaka Jade", OmarchyThemeMode.DARK, "#509475", "#32473B", "#53685B", "#111c18", "#0c1512", "#090f0d", "#23372B", "#C1C497", "#81B8A8", "#D6D5BC", "#F7E8B2", "#FF5345"),
        OmarchyTheme("retro-82", "Retro 82", OmarchyThemeMode.DARK, "#faa968", "#134e5a", "#2a6b78", "#05182e", "#031222", "#020c17", "#0a2540", "#f6dcac", "#3f8f8a", "#a7c9c6", "#f6dcac", "#f85525"),
        OmarchyTheme("ristretto", "Ristretto", OmarchyThemeMode.DARK, "#f38d70", "#403e41", "#72696a", "#2c2525", "#211b1b", "#181414", "#3d2f2a", "#e6d9db", "#72696a", "#c3b7b8", "#e6d9db", "#fd6883"),
        OmarchyTheme("rose-pine", "Rose Pine", OmarchyThemeMode.LIGHT, "#56949f", "#dfdad9", "#cecacd", "#faf4ed", "#ede7e1", "#e1dbd5", "#f2e9e1", "#575279", "#9893a5", "#6e6a86", "#575279", "#b4637a"),
        OmarchyTheme("solitude", "Solitude", OmarchyThemeMode.DARK, "#798186", "#343d41", "#4b4e55", "#101315", "#0c0e10", "#080a0b", "#101315", "#cacccc", "#4b4e55", "#cbc2be", "#a5aeb4", "#565d60"),
        OmarchyTheme("starship", "Starship", OmarchyThemeMode.DARK, "#ff9f0a", "#3a3a3c", "#8e8e93", "#1c1c1e", "#161618", "#0e0e10", "#2c2c2e", "#f5f5f7", "#98989d", "#fbfbfd", "#ffffff", "#ff5f4a"),
        OmarchyTheme("tokyo-night", "Tokyo Night", OmarchyThemeMode.DARK, "#7aa2f7", "#292e42", "#414868", "#1a1b26", "#13141c", "#0e0e14", "#24283b", "#a9b1d6", "#565f89", "#b4bee6", "#c0caf5", "#f7768e"),
        OmarchyTheme("vantablack", "Vantablack", OmarchyThemeMode.DARK, "#8d8d8d", "#1a1a1a", "#7a7a7a", "#000000", "#090909", "#070707", "#1a1a1a", "#ffffff", "#505050", "#ececec", "#ffffff", "#a4a4a4"),
        OmarchyTheme("white", "White", OmarchyThemeMode.LIGHT, "#6e6e6e", "#c0c0c0", "#808080", "#ffffff", "#f5f5f5", "#e8e8e8", "#c0c0c0", "#000000", "#c0c0c0", "#000000", "#000000", "#2a2a2a"),
    )

    val default: OmarchyTheme get() = byId(DEFAULT_ID)

    fun byId(id: String): OmarchyTheme = all.firstOrNull { it.id == id } ?: defaultOrFirst()

    private fun defaultOrFirst(): OmarchyTheme = all.first { it.id == DEFAULT_ID }
}
