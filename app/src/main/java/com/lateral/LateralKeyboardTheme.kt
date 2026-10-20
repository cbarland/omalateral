package com.lateral

import android.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import dev.patrickgold.florisboard.embedded.EmbeddedKeyboardTheme
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.ime.theme.FlorisImeThemeBaseStyle
import dev.patrickgold.florisboard.ime.input.InputShiftState
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import org.florisboard.lib.snygg.SnyggElementRule
import org.florisboard.lib.snygg.SnyggSelector
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
    val danger = InputSettings.theme.danger

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
        // FlorisBoard's base style still has fixed colors outside its palette variables.
        // Replace those visible accents too, including the caps-lock and popup states.
        fun tint(rule: SnyggElementRule, textColor: Int? = null, fillColor: Int? = null) {
            (rules[rule] as? SnyggSinglePropertySetEditor)?.apply {
                textColor?.let { this.foreground = rgbaColor(Color.red(it), Color.green(it), Color.blue(it)) }
                fillColor?.let { this.background = rgbaColor(Color.red(it), Color.green(it), Color.blue(it)) }
            }
        }
        rules.keys.filterIsInstance<SnyggElementRule>().firstOrNull { rule ->
            rule.elementName == FlorisImeUi.Key.elementName &&
                KeyCode.SHIFT.toString() in rule.attributes[FlorisImeUi.Attr.Code].orEmpty() &&
                InputShiftState.CAPS_LOCK.toString() in rule.attributes[FlorisImeUi.Attr.ShiftState].orEmpty()
        }?.let { tint(it, textColor = accent) }
        tint(SnyggElementRule(FlorisImeUi.KeyPopupBox.elementName), fillColor = surfaceVariant)
        tint(SnyggElementRule(FlorisImeUi.KeyPopupElement.elementName, selector = SnyggSelector.FOCUS),
            fillColor = accent)
        tint(SnyggElementRule(FlorisImeUi.SmartbarExtendedActionsToggle.elementName),
            textColor = onSurfaceVariant)
        tint(SnyggElementRule(FlorisImeUi.SmartbarActionKey.elementName), textColor = onSurface)
        tint(SnyggElementRule(FlorisImeUi.SmartbarCandidateClip.elementName), textColor = onSurface)
        tint(SnyggElementRule(FlorisImeUi.OneHandedPanel.elementName),
            textColor = onBackground, fillColor = backgroundVariant)
        rules.keys.filterIsInstance<SnyggElementRule>().firstOrNull { rule ->
            rule.elementName == FlorisImeUi.SmartbarActionsEditorTile.elementName &&
                KeyCode.DRAG_MARKER.toString() in rule.attributes[FlorisImeUi.Attr.Code].orEmpty()
        }?.let { tint(it, textColor = danger) }
    }.build()

    return EmbeddedKeyboardTheme(stylesheet)
}
