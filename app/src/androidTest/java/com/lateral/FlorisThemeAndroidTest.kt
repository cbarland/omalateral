package com.lateral

import androidx.compose.ui.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.ime.input.InputShiftState
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import org.florisboard.lib.snygg.SnyggAnnotationRule
import org.florisboard.lib.snygg.SnyggElementRule
import org.florisboard.lib.snygg.SnyggSinglePropertySet
import org.florisboard.lib.snygg.value.SnyggStaticColorValue
import org.junit.Assert.assertEquals
import org.junit.Test

class FlorisThemeAndroidTest {
    @Test fun embeddedKeyboardFollowsLauncherPaletteChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InputSettings.load(context)
        val previous = InputSettings.theme
        try {
            for (theme in listOf(OmarchyThemes.byId("tokyo-night"),
                OmarchyThemes.byId("catppuccin-latte"))) {
                InputSettings.setTheme(context, theme)
                val rules = lateralKeyboardTheme().stylesheet.rules
                val defines = rules[SnyggAnnotationRule.Defines] as SnyggSinglePropertySet
                assertEquals(Color(theme.background),
                    (defines.properties.getValue("--background") as SnyggStaticColorValue).color)
                assertEquals(Color(theme.accent),
                    (defines.properties.getValue("--primary") as SnyggStaticColorValue).color)
                val popup = rules[SnyggElementRule(FlorisImeUi.KeyPopupBox.elementName)]
                    as SnyggSinglePropertySet
                assertEquals(Color(theme.selection),
                    (popup.background as SnyggStaticColorValue).color)
                val capsLock = rules.entries.first { (rule, _) ->
                    rule is SnyggElementRule && rule.elementName == FlorisImeUi.Key.elementName &&
                        KeyCode.SHIFT.toString() in rule.attributes[FlorisImeUi.Attr.Code].orEmpty() &&
                        InputShiftState.CAPS_LOCK.toString() in rule.attributes[FlorisImeUi.Attr.ShiftState].orEmpty()
                }.value as SnyggSinglePropertySet
                assertEquals(Color(theme.accent),
                    (capsLock.foreground as SnyggStaticColorValue).color)
            }
        } finally {
            InputSettings.setTheme(context, previous)
        }
    }
}
