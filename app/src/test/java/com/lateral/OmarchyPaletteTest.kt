package com.lateral

import org.junit.Assert.*
import org.junit.Test

class OmarchyPaletteTest {
    @Test fun everyBundledPaletteRoundTripsWithoutAndroidColorParsing() {
        OmarchyThemes.all.forEach { theme ->
            val restored = checkNotNull(OmarchyTheme.fromPalette(theme.id, theme.name, theme.palette()))
            assertEquals(theme.id, restored.id)
            assertEquals(theme.mode, restored.mode)
            theme.palette().forEach { (key, value) -> assertEquals(value.uppercase(), restored.palette().getValue(key).uppercase()) }
        }
    }
    @Test fun communityThemeKeepsIdentityAndSuppliesMissingRoles() {
        val theme = checkNotNull(OmarchyTheme.fromPalette("custom", "My theme", mapOf(
            "background" to "#101010", "foreground" to "#dedede", "accent" to "#bbaaee", "mode" to "light")))
        assertEquals("custom", theme.id)
        assertEquals(OmarchyThemeMode.LIGHT, theme.mode)
        assertEquals("#BBAAEE", theme.palette()["accent"])
        assertEquals("#DEDEDE", theme.palette()["bright_foreground"])
    }
    @Test fun malformedRequiredColorsAreRejectedAndOptionalValuesFallBack() {
        assertNull(OmarchyTheme.fromPalette("x", "x", mapOf("background" to "red", "foreground" to "#123456")))
        assertNull(OmarchyTheme.fromPalette("x", "x", emptyMap()))
        val theme = checkNotNull(OmarchyTheme.fromPalette("x", "x", mapOf(
            "background" to "#101010", "foreground" to "#eeeeee", "accent" to "not a color")))
        assertEquals("#EEEEEE", theme.palette()["accent"])
    }
}
