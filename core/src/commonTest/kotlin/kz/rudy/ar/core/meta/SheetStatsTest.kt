package kz.rudy.ar.core.meta

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kz.rudy.ar.core.bundle.Bundle
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.SpriteView
import kz.rudy.ar.core.domain.Stat
import kz.rudy.ar.core.domain.defaultFor
import kz.rudy.ar.core.domain.fighter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T40: the sheet's duel stats, reasons and view, from the model reply to `sheet.json` and back. */
class SheetStatsTest {
    private val root = "docs/api-samples"
    private val repo = DiskFixtureSource.repoRoot()
    private val api = FixtureApi(repo, root)
    private val newKeys = listOf("stats", "statReasons", "baseView", "facesRight")

    /** Every `spark-sheet/<ref>.fixture.json` ref. */
    private fun sheetRefs(): List<String> =
        repo.list("$root/${Jobs.SPARK_SHEET}").filter { it.endsWith(".fixture.json") }.map { it.removeSuffix(".fixture.json") }

    /** The bundled characters of both bundle folders, as `<dir>/<ref>/sheet.json` paths. */
    private fun bundledSheets(): List<String> = listOf("androidApp/src/main/assets/bundled", "docs/bundled").flatMap { dir ->
        repo.list(dir).map { "$dir/$it/sheet.json" }.filter { repo.exists(it) }
    }

    @Test
    fun sheetWithStatsParses() = runTest {
        val cat = api.characterSheet("sample-01", "")
        assertTrue(api.load(Jobs.SPARK_SHEET, "sample-01").synthetic, "hand-written, not a recorded reply")
        assertEquals(FighterStats(5, 5, 3, 7), cat.stats, "raw 6/5/3/8 (22 points) rebalanced to 20")
        assertEquals(cat.stats, cat.fighter)
        assertEquals(setOf("kk", "ru", "en"), cat.statReasons.keys)
        for ((lang, reasons) in cat.statReasons) assertEquals(Stat.entries.toSet(), reasons.keys, lang)
        assertEquals("sharp claws", cat.statReasons.getValue("en").getValue(Stat.POWER))
        assertEquals("өткір тырнақтар", cat.statReasons.getValue("kk").getValue(Stat.POWER))
        assertEquals(SpriteView.FRONT, cat.baseView)
        assertEquals(true, cat.facesRight)

        val bird = api.characterSheet("sample-02", "")
        assertEquals(FighterStats(2, 9, 2, 7), bird.stats, "already on budget: kept")
        assertEquals(Ability.FLY, bird.ability)
        assertEquals("большие крылья", bird.statReasons.getValue("ru").getValue(Stat.SPEED))
        assertEquals(SpriteView.SIDE, bird.baseView)
        assertEquals(true, bird.facesRight)
    }

    @Test
    fun parserReadsLooseStatsAndReasons() {
        val long = "a".repeat(60)
        val sheet = SheetParser.parse(
            """{"name": "Х", "personality": "", "ability": "hide", "lines": {},
               "stats": {"Power": "7", "speed": " 3 ", "guard": 80, "agility": "lots", "luck": 9},
               "why": {"KK": {"power": "  үлкен  ", "speed": "   ", "GUARD": "$long", "luck": "x", "agility": 5},
                       "de": {"power": "Krallen"}, "ru": "not an object", "en": {"agility": "tiny"}},
               "view": " Side ", "faces": "RIGHT"}""",
        )
        assertEquals(FighterStats(6, 3, 10, 1), sheet.stats, "strings read, 80 clamped and re-spread, agility missing")
        assertEquals(
            mapOf("kk" to mapOf(Stat.POWER to "үлкен", Stat.GUARD to "a".repeat(SheetParser.REASON_MAX_CHARS)), "en" to mapOf(Stat.AGILITY to "tiny")),
            sheet.statReasons,
        )
        assertEquals(SpriteView.SIDE, sheet.baseView)
        assertEquals(true, sheet.facesRight)

        val emoji = SheetParser.parseReasons(SheetParser.extractObject("""{"en": {"power": "a${"😀".repeat(25)}"}}"""))
            .getValue("en").getValue(Stat.POWER)
        assertTrue(emoji.length <= SheetParser.REASON_MAX_CHARS && !emoji.last().isHighSurrogate(), emoji)

        val odd = SheetParser.parse(
            """{"name": "Х", "personality": "", "ability": "roll", "lines": {}, "stats": {"power": "many", "speed": null},
               "why": [], "view": "top", "faces": "up"}""",
        )
        assertNull(odd.stats, "no usable score")
        assertEquals(emptyMap(), odd.statReasons)
        assertNull(odd.baseView)
        assertNull(odd.facesRight)
        assertEquals(FighterStats.defaultFor(Ability.ROLL), odd.fighter)
        assertNull(SheetParser.parse("""{"name": "Х", "ability": "roll", "stats": [7, 3, 8, 2]}""").stats, "not an object")
        assertEquals(false, SheetParser.parseFaces("left"))
        assertNull(SheetParser.fallback("cat-01").stats)
        assertEquals(FighterStats.defaultFor(Ability.JUMP), SheetParser.fallback("cat-01").fighter)
    }

    @Test
    fun legacySheetHasNoStatsAndFightsWithItsArchetype() = runTest {
        var legacy = 0
        for (ref in sheetRefs()) {
            val sheet = api.characterSheet(ref, "")
            assertTrue(sheet.fighter.isBalanced, "$ref fights with ${sheet.fighter}")
            val reply = api.load(Jobs.SPARK_SHEET, ref).sheet!!
            if (listOf("stats", "why", "view", "faces").any { it in reply }) continue
            legacy++
            assertNull(sheet.stats, ref)
            assertEquals(emptyMap(), sheet.statReasons, ref)
            assertNull(sheet.baseView, ref)
            assertNull(sheet.facesRight, ref)
            assertEquals(FighterStats.defaultFor(sheet.ability), sheet.fighter, ref)
        }
        assertTrue(legacy >= 1, "the fixtures recorded before T40 are the back-compat proof")

        // G2 (ai/build-plan-v2.md): every bundled character fights with a balanced 20, read either way the app reads it.
        val bundled = bundledSheets()
        assertTrue(bundled.isNotEmpty())
        for (path in bundled) {
            val text = repo.readText(path)!!
            for (sheet in listOf(SheetParser.parse(text), Bundle.decodeSheet(text))) {
                assertNull(sheet.stats, path)
                assertEquals(FighterStats.defaultFor(sheet.ability), sheet.fighter, path)
                assertEquals(FighterStats.BUDGET, sheet.fighter.total, path)
            }
        }
    }

    /** The shape of `CharacterSheet` before T40, field for field. */
    @Serializable
    private data class SheetBeforeT40(
        val name: String,
        val personality: String,
        val ability: Ability,
        val lines: Map<String, List<String>>,
        val names: Map<String, String> = emptyMap(),
    )

    @Test
    fun sheetJsonWithoutNewFieldsIsUnchanged() {
        val sheets = listOf(
            SheetParser.fallback("cat-01"),
            CharacterSheet("Жылдам", "fast", Ability.ROLL, mapOf("kk" to listOf("Сәлем!")), mapOf("kk" to "Жылдам", "en" to "Speedy")),
        ) + bundledSheets().map { Bundle.decodeSheet(repo.readText(it)!!) }
        for (sheet in sheets) {
            val json = Bundle.json.encodeToString(CharacterSheet.serializer(), sheet)
            for (key in newKeys) assertFalse("\"$key\"" in json, "$key leaked into $json")
            val before = SheetBeforeT40(sheet.name, sheet.personality, sheet.ability, sheet.lines, sheet.names)
            assertEquals(Bundle.json.encodeToString(SheetBeforeT40.serializer(), before), json, "byte-identical to the v1.0 shape")
        }
    }

    @Test
    fun sheetWithStatsRoundTrips() = runTest {
        for (ref in listOf("sample-01", "sample-02")) {
            val sheet = api.characterSheet(ref, "")
            val saved = Bundle.encodeSheet(sheet)
            for (key in newKeys) assertTrue("\"$key\"" in saved, "$ref: $key is written")
            assertEquals(sheet, Bundle.decodeSheet(saved), "$ref through Bundle.json")
            // The Android host reads bundled sheets through SheetParser.parse: the saved shape survives that too.
            assertEquals(sheet, SheetParser.parse(saved), "$ref through SheetParser")
        }
    }
}
