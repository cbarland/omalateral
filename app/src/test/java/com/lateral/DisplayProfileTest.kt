package com.lateral

import org.junit.Assert.*
import org.junit.Test

class DisplayProfileTest {
    @Test fun ratioSyntaxAndValidation() {
        assertEquals(9f / 19.5f, DisplayProfile.parseAspect("9:19.5")!!, .0001f)
        assertEquals(4f / 3f, DisplayProfile.parseAspect(" 4 : 3 ")!!, .0001f)
        assertEquals(1.45f, DisplayProfile.parseAspect("1.45")!!, .0001f)
        for (bad in listOf("", "NaN", "Infinity", "1:0", "-1:-2", "1:2:3", "1:9", "4:1")) {
            assertNull(bad, DisplayProfile.parseAspect(bad))
        }
    }
    @Test fun displayResolutionAndPhoneProfilesStayIndependentAfterReload() {
        val data = mutableMapOf<String, Float>()
        fun store() = DisplayProfileStore({ k, d -> data[k] ?: d }, { data.putAll(it) })
        val phone = DisplayProfileKey("phoneview:built-in", 1080, 2424)
        val beast = DisplayProfileKey("external:beast", 1920, 1080)
        val wide = beast.copy(width = 3840, height = 1200)
        val other = beast.copy(identity = "external:other")
        val preferred = DisplayProfile(.5f, 1.6f, 2f, 1.8f, .7f)
        store().save(beast, preferred)
        store().save(phone, preferred.copy(uiScale = 1.2f))
        assertEquals(preferred, store().load(beast.copy(), DisplayProfile()))
        assertEquals(1.2f, store().load(phone, DisplayProfile()).uiScale, .0001f)
        assertEquals(DisplayProfile(), store().load(wide, DisplayProfile()))
        assertEquals(DisplayProfile(), store().load(other, DisplayProfile()))
    }
    @Test fun legacyDefaultsDoNotLeakEditedValuesAcrossProfiles() {
        val data = mutableMapOf<String, Float>()
        val store = DisplayProfileStore({ k, d -> data[k] ?: d }, { data.putAll(it) })
        val legacy = DisplayProfile(uiScale = 1.5f, supersampling = 1.5f)
        val key = DisplayProfileKey("a", 1920, 1080)
        assertEquals(legacy, store.load(key, legacy))
        store.save(key, legacy.copy(uiScale = 2f))
        assertEquals(legacy, store.load(key.copy(identity = "b"), legacy))
    }
    @Test fun corruptedValuesAreBounded() {
        val profile = DisplayProfile(Float.NaN, -2f, 100f, Float.POSITIVE_INFINITY, 0f).sanitized()
        assertEquals(9f / 19.5f, profile.phoneAspect, .0001f)
        assertEquals(.25f, profile.tabletAspect, .0001f)
        assertEquals(2f, profile.supersampling, .0001f)
        assertEquals(1.1f, profile.uiScale, .0001f)
        assertEquals(.6f, profile.hostedScale, .0001f)
    }
}
