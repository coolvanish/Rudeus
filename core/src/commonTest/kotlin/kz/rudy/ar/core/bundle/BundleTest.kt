package kz.rudy.ar.core.bundle

import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.meta.FakeModels
import kz.rudy.ar.core.pipeline.CharacterResult
import kotlin.test.Test
import kotlin.test.assertEquals

class BundleTest {
    private fun entry(ref: String, name: String = ref, runId: String = "2026-10-28T12-00-00Z") = BundleEntry(
        ref = ref, name = name, ability = Ability.JUMP, gateScore = 0.82f, gatePassed = true, attempts = 1,
        sprite = Bundle.spritePath(ref), sheet = Bundle.sheetPath(ref), provider = "meta", runId = runId,
    )

    @Test
    fun bundleIndexUpsertReplacesSameRefAndSorts() {
        val index = BundleIndex()
            .upsert(entry("toy-car-01"))
            .upsert(entry("cat-01", name = "old"))
            .upsert(entry("dog-01"))
            .upsert(entry("cat-01", name = "new", runId = "2026-10-29T08-00-00Z"))

        assertEquals(listOf("cat-01", "dog-01", "toy-car-01"), index.characters.map { it.ref })
        val cat = index.characters.first()
        assertEquals("new", cat.name)
        assertEquals("2026-10-29T08-00-00Z", cat.runId)
        assertEquals(1, index.schema)

        // Upsert survives a write/read cycle of the file.
        val reread = Bundle.decodeIndex(Bundle.encodeIndex(index)).upsert(entry("ant-01"))
        assertEquals(listOf("ant-01", "cat-01", "dog-01", "toy-car-01"), reread.characters.map { it.ref })
        assertEquals(BundleIndex(), Bundle.decodeIndex(null))
    }

    @Test
    fun bundleEntryJsonRoundTrip() {
        val result = CharacterResult(
            ref = "cat-01", sprite = byteArrayOf(1), sheet = FakeModels.DEFAULT_SHEET.copy(ability = Ability.FLY),
            gateScore = 0.64f, gatePassed = false, attempts = 3, cutoutMask = null,
        )
        val e = Bundle.entry(result, provider = "meta", runId = "2026-10-28T12-00-00Z")
        assertEquals("cat-01/sprite.png", e.sprite)
        assertEquals("cat-01/sheet.json", e.sheet)
        assertEquals(Ability.FLY, e.ability)
        assertEquals(3, e.attempts)

        val text = Bundle.json.encodeToString(BundleEntry.serializer(), e)
        assertEquals(e, Bundle.json.decodeFromString(BundleEntry.serializer(), text))
        for (key in listOf("ref", "name", "ability", "gateScore", "gatePassed", "attempts", "sprite", "sheet", "provider", "runId")) {
            assertEquals(true, text.contains("\"$key\""), "field $key in $text")
        }
        assertEquals(result.sheet, Bundle.decodeSheet(Bundle.encodeSheet(result.sheet)))
    }


    @Test
    fun bundleEntryCarriesTheChoreoPath() {
        val result = CharacterResult("cat-01", byteArrayOf(1), FakeModels.DEFAULT_SHEET, 0.8f, true, 1, null)
        val e = Bundle.entry(result, "meta", "2026-11-04T12-00-00Z")
        assertEquals("cat-01/choreo.json", e.choreo)
        assertEquals(Bundle.choreoPath("cat-01"), e.choreo)
        assertEquals(e, Bundle.decodeIndex(Bundle.encodeIndex(BundleIndex().upsert(e))).characters.single())
        assertEquals(EmoteSet.defaults(), result.emotes, "CharacterResult defaults its emotes")
        assertEquals(EmoteSet.defaults(), Bundle.decodeEmotes(Bundle.encodeEmotes(EmoteSet.defaults())))
    }

    @Test
    fun anOldIndexWithoutChoreoStillDecodes() {
        val old = """{"schema":1,"characters":[{"ref":"cat-01","name":"Мысық","ability":"JUMP","gateScore":0.8,"gatePassed":true,
            "attempts":1,"sprite":"cat-01/sprite.png","sheet":"cat-01/sheet.json","provider":"meta","runId":"2026-10-28T12-00-00Z"}]}"""
        val entry = Bundle.decodeIndex(old).characters.single()
        assertEquals("cat-01", entry.ref)
        assertEquals(null, entry.choreo)
    }
}
