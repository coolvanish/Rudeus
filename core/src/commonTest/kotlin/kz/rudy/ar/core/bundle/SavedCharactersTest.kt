package kz.rudy.ar.core.bundle

import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.emote.Emote
import kz.rudy.ar.core.emote.Choreographies
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.domain.SpriteView
import kz.rudy.ar.core.emote.PoseFrames
import kz.rudy.ar.core.emote.SpriteViews
import kz.rudy.ar.core.io.MemoryCharacterFolder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SavedCharactersTest {
    private fun sheet(name: String) = CharacterSheet(
        name = name,
        personality = "батыл",
        ability = Ability.FLY,
        lines = mapOf("kk" to listOf("Сәлем!"), "ru" to listOf("Привет!")),
    )

    private fun sprite(n: Int) = byteArrayOf(n.toByte(), 1, 2)

    @Test
    fun savedCharacterComesBackWhenTheAppStartsAgain() {
        val folder = MemoryCharacterFolder()
        val id = SavedCharacters(folder, clock = { 1000L }).save(sprite(7), sheet("Мұрат")).id

        val loaded = SavedCharacters(folder).load().single()

        assertEquals(id, loaded.id)
        assertEquals(sheet("Мұрат"), loaded.sheet)
        assertContentEquals(sprite(7), loaded.sprite)
    }

    @Test
    fun newestComesFirstWhenTheClockStandsStillOrGoesBack() {
        var now = 5000L
        val saved = SavedCharacters(MemoryCharacterFolder(), clock = { now })
        saved.save(sprite(1), sheet("first"))
        saved.save(sprite(2), sheet("same ms"))
        now = 10L
        saved.save(sprite(3), sheet("clock went back"))

        assertEquals(listOf("clock went back", "same ms", "first"), saved.load().map { it.sheet.name })
    }

    @Test
    fun oldestGoesPastTheCapAndItsFilesWithIt() {
        val folder = MemoryCharacterFolder()
        val saved = SavedCharacters(folder, maxKept = 2)
        saved.save(sprite(1), sheet("a"))
        saved.save(sprite(2), sheet("b"))
        saved.save(sprite(3), sheet("c"))

        assertEquals(listOf("c", "b"), saved.load().map { it.sheet.name })
        assertEquals(2, folder.sprites.size)
        assertEquals(2, folder.sheets.size)
    }

    @Test
    fun unreadableEntriesAreSkippedAndTheRestStillLoad() {
        val folder = MemoryCharacterFolder()
        val saved = SavedCharacters(folder)
        saved.save(sprite(1), sheet("good"))
        folder.sheets["saved-1"] = "{ not json"
        folder.sprites["saved-1"] = sprite(2)
        folder.sheets["saved-2"] = Bundle.encodeSheet(sheet("no sprite"))
        folder.sprites["saved-3"] = sprite(3) // no sheet
        folder.write("saved-4", ByteArray(0), Bundle.encodeSheet(sheet("empty sprite")))

        assertEquals(listOf("good"), saved.load().map { it.sheet.name })
    }

    @Test
    fun foldersThatAreNotCharactersAreLeftAlone() {
        val folder = MemoryCharacterFolder()
        folder.write(".writing", sprite(1), Bundle.encodeSheet(sheet("half")))
        folder.write("saved-abc", sprite(1), Bundle.encodeSheet(sheet("odd")))

        val saved = SavedCharacters(folder, maxKept = 1)
        saved.save(sprite(2), sheet("real"))
        saved.save(sprite(3), sheet("newer"))

        assertEquals(listOf("newer"), saved.load().map { it.sheet.name })
        assertEquals(setOf(".writing", "saved-abc"), folder.ids().toSet() - saved.load().map { it.id }.toSet())
    }


    @Test
    fun savedCharacterRoundTripsItsEmotes() {
        val folder = MemoryCharacterFolder()
        val emotes = EmoteSet(EmoteSet.SOURCE_SPARK, Choreographies.DEFAULT + (Emote.ROAR to Choreographies.ROAR.copy(line = 0)))
        val saved = SavedCharacters(folder, clock = { 1000L }).save(sprite(1), sheet("Арыстан"), emotes)
        assertEquals(emotes, saved.emotes)
        assertEquals(emotes, Bundle.decodeEmotes(folder.choreos.getValue(saved.id)))

        val loaded = SavedCharacters(folder).load().single()
        assertEquals(emotes, loaded.emotes)
        assertEquals(EmoteSet.SOURCE_SPARK, loaded.emotes.source)
    }

    @Test
    fun aSavedCharacterWithoutChoreoLoadsDefaults() {
        val folder = MemoryCharacterFolder()
        folder.write("saved-1000", sprite(1), Bundle.encodeSheet(sheet("old"))) // saved before T29: no choreo.json
        folder.write("saved-2000", sprite(2), Bundle.encodeSheet(sheet("broken")), "{not json")

        val loaded = SavedCharacters(folder).load()
        assertEquals(listOf("broken", "old"), loaded.map { it.sheet.name }, "neither character is dropped")
        assertEquals(listOf(EmoteSet.defaults(), EmoteSet.defaults()), loaded.map { it.emotes })
        assertEquals(EmoteSet.defaults(), SavedCharacters(MemoryCharacterFolder()).save(sprite(3), sheet("new")).emotes)
    }
}

class SavedFramesTest {
    private fun sheet(name: String) = CharacterSheet(name = name, personality = "", ability = Ability.JUMP, lines = emptyMap())
    private fun png(n: Int) = byteArrayOf(n.toByte(), 0x50, 0x4E, 0x47)

    @Test
    fun savedCharacterRoundTripsItsFramesAndKeyedEmotes() {
        val folder = MemoryCharacterFolder()
        val frames = kz.rudy.ar.core.emote.PoseFrames.of(mapOf(Emote.HELLO to listOf(png(1), png(2)), Emote.ROAR to listOf(png(3))))
        val emotes = EmoteSet(EmoteSet.SOURCE_SPARK, Choreographies.DEFAULT).withFrames(frames.counts())
        val saved = SavedCharacters(folder, clock = { 1000L }).save(png(0), sheet("Арыстан"), emotes, frames)
        assertEquals(frames, saved.frames)
        assertEquals(setOf("hello-1.png", "hello-2.png", "roar-1.png"), folder.frames.getValue(saved.id).keys)

        val loaded = SavedCharacters(folder).load().single()
        assertEquals(frames, loaded.frames)
        assertEquals(emotes, loaded.emotes)
        assertTrue(loaded.emotes[Emote.HELLO].keys.any { it.frame == 1 })
    }

    @Test
    fun aSavedCharacterWithoutFramesLoadsEmpty() {
        val folder = MemoryCharacterFolder()
        folder.write("saved-1000", png(1), Bundle.encodeSheet(sheet("old")), Bundle.encodeEmotes(EmoteSet.defaults()))
        folder.write("saved-2000", png(2), Bundle.encodeSheet(sheet("odd files")), null, mapOf("hello-2.png" to png(5), "notes.txt" to png(6)))

        val loaded = SavedCharacters(folder).load()
        assertEquals(listOf("odd files", "old"), loaded.map { it.sheet.name })
        assertEquals(listOf(kz.rudy.ar.core.emote.PoseFrames.EMPTY, kz.rudy.ar.core.emote.PoseFrames.EMPTY), loaded.map { it.frames })
        assertEquals(kz.rudy.ar.core.emote.PoseFrames.EMPTY, SavedCharacters(MemoryCharacterFolder()).save(png(3), sheet("new")).frames)
    }
}

class SavedViewsTest {
    private fun png(n: Int) = byteArrayOf(n.toByte(), 0x50, 0x4E, 0x47)

    @Test
    fun savedCharacterKeepsViewsNextToFrames() {
        val folder = MemoryCharacterFolder()
        // A FRONT drawing looking right (what T40's sheet will say); the pipeline drew BACK and SIDE.
        val sheet = CharacterSheet(
            name = "Түлкі", personality = "", ability = Ability.ROLL, lines = emptyMap(),
            baseView = SpriteView.FRONT, facesRight = true,
        )
        val frames = PoseFrames.of(mapOf(Emote.HELLO to listOf(png(1))))
        val views = SpriteViews.of(SpriteView.FRONT, true, mapOf(SpriteView.BACK to png(7), SpriteView.SIDE to png(8)))
        val saved = SavedCharacters(folder, clock = { 1000L }).save(png(0), sheet, EmoteSet.defaults(), frames, views)
        assertEquals(views, saved.views)
        assertEquals(setOf("hello-1.png", "view-back.png", "view-side.png"), folder.frames.getValue(saved.id).keys, "one folder for both")

        val loaded = SavedCharacters(folder).load().single()
        assertEquals(views, loaded.views)
        assertEquals(frames, loaded.frames, "the views are not frames")
        assertEquals(sheet, loaded.sheet)

        // Views without frames travel in the frames map all the same; a sheet without baseView reads a SIDE base.
        val plain = CharacterSheet(name = "Мысық", personality = "", ability = Ability.JUMP, lines = emptyMap())
        val backOnly = SpriteViews.of(SpriteView.SIDE, false, mapOf(SpriteView.BACK to png(9)))
        val folder2 = MemoryCharacterFolder()
        SavedCharacters(folder2, clock = { 2000L }).save(png(0), plain, views = backOnly)
        val back = SavedCharacters(folder2).load().single()
        assertEquals(backOnly, back.views)
        assertEquals(PoseFrames.EMPTY, back.frames)
        assertEquals(SpriteViews.NONE, SavedCharacters(MemoryCharacterFolder()).save(png(3), plain).views)
    }
}
