package com.lateral

import android.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import dev.patrickgold.florisboard.embedded.EmbeddedKeyboardTheme
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.ime.theme.FlorisImeThemeBaseStyle
import org.florisboard.lib.snygg.SnyggElementRule
import org.florisboard.lib.snygg.SnyggSinglePropertySetEditor

/** Builds the embedded FlorisBoard theme from LATERAL_'s current appearance settings. */
internal fun lateralKeyboardTheme(): EmbeddedKeyboardTheme {
    val accent = InputSettings.accentColor
    val accentVariant = InputSettings.accentOutlineColor()
    val background = InputSettings.theme.background
    val backgroundVariant = InputSettings.theme.darkBackground
    val surface = InputSettings.theme.lighterBackground
    val surfaceVariant = InputSettings.theme.selection
    val onBackground = InputSettings.theme.brightForeground
    val onSurface = InputSettings.theme.foreground
    val onSurfaceVariant = InputSettings.theme.darkForeground

    val stylesheet = FlorisImeThemeBaseStyle.edit().apply {
        // Keep FlorisBoard's complete rule set, replacing only its Material palette and
        // geometry variables so every keyboard surface stays consistent with LATERAL_.
        defines {
            "--primary" to rgbaColor(Color.red(accent), Color.green(accent), Color.blue(accent))
            "--primary-variant" to rgbaColor(
                Color.red(accentVariant), Color.green(accentVariant), Color.blue(accentVariant),
            )
            "--secondary" to rgbaColor(Color.red(accent), Color.green(accent), Color.blue(accent))
            "--secondary-variant" to rgbaColor(
                Color.red(accentVariant), Color.green(accentVariant), Color.blue(accentVariant),
            )
            "--background" to rgbaColor(
                Color.red(background), Color.green(background), Color.blue(background),
            )
            "--background-variant" to rgbaColor(
                Color.red(backgroundVariant), Color.green(backgroundVariant), Color.blue(backgroundVariant),
            )
            "--surface" to rgbaColor(Color.red(surface), Color.green(surface), Color.blue(surface))
            "--surface-variant" to rgbaColor(
                Color.red(surfaceVariant), Color.green(surfaceVariant), Color.blue(surfaceVariant),
            )
            "--on-primary" to rgbaColor(Color.red(background), Color.green(background), Color.blue(background))
            "--on-background" to rgbaColor(
                Color.red(onBackground), Color.green(onBackground), Color.blue(onBackground),
            )
            "--on-background-disabled" to rgbaColor(Color.red(accentVariant), Color.green(accentVariant), Color.blue(accentVariant))
            "--on-surface" to rgbaColor(Color.red(onSurface), Color.green(onSurface), Color.blue(onSurface))
            "--on-surface-variant" to rgbaColor(
                Color.red(onSurfaceVariant), Color.green(onSurfaceVariant), Color.blue(onSurfaceVariant),
            )
            "--shape" to roundedCornerShape(4.dp)
            "--shape-variant" to roundedCornerShape(6.dp)
        }
        listOf(FlorisImeUi.Key, FlorisImeUi.Smartbar).forEach { element ->
            (rules[SnyggElementRule(element.elementName)] as? SnyggSinglePropertySetEditor)?.let { rule ->
                rule.fontFamily = rule.genericFontFamily(FontFamily.Monospace)
            }
        }
    }.build()

    return EmbeddedKeyboardTheme(stylesheet)
}
