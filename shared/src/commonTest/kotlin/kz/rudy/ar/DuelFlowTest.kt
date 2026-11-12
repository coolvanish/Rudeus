package kz.rudy.ar

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.duel.BotLevel
import kz.rudy.ar.core.duel.Contestant
import kz.rudy.ar.core.duel.DuelArenas
import kz.rudy.ar.core.duel.DuelInput
import kz.rudy.ar.core.duel.DuelMatch
import kz.rudy.ar.core.duel.DuelMatchState
import kz.rudy.ar.core.duel.DuelParams
import kz.rudy.ar.core.duel.DuelPhysics
import kz.rudy.ar.core.duel.DuelRules
import kz.rudy.ar.core.duel.MatchPhase
import kz.rudy.ar.core.emote.Emote
import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.emote.PoseFrames
import kz.rudy.ar.core.physics.Cast
import kz.rudy.ar.core.session.DuelRefusal
import kz.rudy.ar.core.session.DuelReport
import kz.rudy.ar.ui.duel.DuelDifficulty
import kz.rudy.ar.ui.duel.DuelFlow
import kz.rudy.ar.ui.duel.DuelStep
import kz.rudy.ar.ui.duel.DuelUi
import kz.rudy.ar.ui.duel.duelHud
import kz.rudy.ar.ui.screens.BundledCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// T59: the pure rules of the duel flow. Reports are built here with DuelMatch.start / step on DuelArenas.EMPTY (60 x 40
// cm), as the engine (T58) will build them; nothing here needs the engine.

private val ARENA = DuelArenas.EMPTY
private val MARKS = Vec2(-0.08f, 0f) to Vec2(0.08f, 0f)
private val MINE = Contestant("mine", FighterStats(power = 6, speed = 5, guard = 5, agility = 4), Ability.JUMP)
private val FOE = Contestant("foe", FighterStats(power = 4, speed = 6, guard = 4, agility = 6), Ability.FLY)
private val NAMES = mapOf("mine" to "Мұрат", "foe" to "Айгүл")
private fun nameOf(id: String) = NAMES[id] ?: id

private fun sheet(name: String, ability: Ability = Ability.JUMP) =
    CharacterSheet(name = name, personality = "", ability = ability, lines = emptyMap())

private fun card(id: String) = BundledCharacter(id, sheet("kk-$id"))

private fun member(id: String) = ArCharacter(id, id, spritePng = null, ability = Ability.JUMP, emotes = EmoteSet.defaults())

/** The match run on [ARENA] with [rules] and [params] until FIGHT starts, then [ms] more with [first] on its first frame. */
private fun fight(
    rules: DuelRules = DuelRules(),
    params: DuelParams = DuelParams.DEFAULT,
    first: Map<String, DuelInput> = emptyMap(),
    ms: Long = 0L,
): DuelMatchState {
    var s = DuelMatch.start(MINE, FOE, ARENA, MARKS, rules, params)
    while (s.phase == MatchPhase.COUNTDOWN) s = DuelMatch.step(s, ARENA, 24L, rules = rules, params = params).state
    assertEquals(MatchPhase.FIGHT, s.phase)
    var inputs = first
    var left = ms
    while (left > 0L) {
        val dt = minOf(left, 24L)
        s = DuelMatch.step(s, ARENA, dt, inputs, rules = rules, params = params).state
        inputs = emptyMap()
        left -= dt
    }
    return s
}

private fun running(
    state: DuelMatchState,
    rules: DuelRules = DuelRules(),
    params: DuelParams = DuelParams.DEFAULT,
    arena: Level = ARENA,
    serial: Int = 1,
) = DuelReport.Running(serial, state, arena, rules, params)

class DuelFlowTest {

    @Test
    fun difficultyMapsToTheBotLevelOfTheSameName() {
        assertEquals(DuelDifficulty.entries.map { it.name }, DuelDifficulty.entries.map { DuelFlow.botLevel(it).name })
        assertEquals(BotLevel.NORMAL, DuelFlow.botLevel(DuelUi().difficulty)) // NORMAL is preselected
    }

    @Test
    fun aDuelNeedsALevelWithABoundary() {
        assertFalse(DuelFlow.canDuel(null)) // nothing scanned yet
        assertFalse(DuelFlow.canDuel(Level(emptyList()))) // the floor: no edge to push anyone over
        assertTrue(DuelFlow.canDuel(Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.6f, 0.4f))))
    }

    @Test
    fun opponentsAreSavedThenBundledOnceEachWithoutMine() {
        val saved = listOf(card("saved-2"), card("saved-1"))
        val bundled = listOf(card("murat"), card("koshka"), card("saved-1"))
        assertEquals(listOf("saved-2", "saved-1", "koshka"), DuelFlow.opponents(saved, bundled, mineId = "murat").map { it.id })
        // A photo played as "photo-<ref>" whose save landed as "saved-2": that card is mine too.
        assertEquals(
            listOf("saved-1", "murat", "koshka"),
            DuelFlow.opponents(saved, bundled, mineId = "photo-r1", alsoMine = setOf("saved-2")).map { it.id },
        )
        assertTrue(DuelFlow.opponents(emptyList(), listOf(card("murat")), mineId = "murat").isEmpty())
    }

    @Test
    fun aCardOfAMemberCarriesItsIdSheetSpriteEmotesAndFrames() {
        val sprite = byteArrayOf(1, 2, 3)
        val emotes = EmoteSet(EmoteSet.SOURCE_SPARK, emptyMap())
        val frames = PoseFrames.of(mapOf(Emote.HELLO to listOf(byteArrayOf(4))))
        val m = ArCharacter("photo-r1", "Мұрат", sprite, Ability.HIDE, emotes, frames)
        val s = sheet("Мұрат", Ability.HIDE)
        val c = DuelFlow.card(m, s)
        assertEquals("photo-r1", c.id)
        assertSame(s, c.sheet)
        assertSame(sprite, c.spritePng)
        assertSame(emotes, c.emotes)
        assertSame(frames, c.frames)
    }

    @Test
    fun theFoeJoinsTheCastWhenThereIsRoom() {
        val cast = listOf(member("mine"), member("friend"))
        val foe = member("foe")
        val next = DuelFlow.castFor(cast, "mine", foe)
        assertEquals(listOf("mine", "friend", "foe"), next.map { it.id })
        assertSame(foe, next.last())
    }

    @Test
    fun aFoeAlreadyOnTheTableChangesNothing() {
        val cast = listOf(member("mine"), member("foe"))
        assertSame(cast, DuelFlow.castFor(cast, "mine", member("foe")))
    }

    @Test
    fun inAFullCastTheFoeTakesTheLastSeatThatIsNotMine() {
        val ids = listOf("mine", "b", "c", "d")
        assertEquals(Cast.MAX, ids.size)
        val full = ids.map(::member)
        assertEquals(listOf("mine", "b", "c", "foe"), DuelFlow.castFor(full, "mine", member("foe")).map { it.id })
        // Mine sits last: the seat before it goes.
        val mineLast = listOf("b", "c", "d", "mine").map(::member)
        val next = DuelFlow.castFor(mineLast, "mine", member("foe"))
        assertEquals(listOf("b", "c", "foe", "mine"), next.map { it.id })
        assertSame(mineLast[3], next[3])
        assertEquals(Cast.MAX, next.size)
    }

    @Test
    fun aSetupExistsOnlyInFightWithAFoeAndAFighter() {
        val ready = DuelUi(step = DuelStep.FIGHT, foeId = "foe")
        assertNotNull(DuelFlow.setup(ready, 1, "mine", 7L))
        assertNull(DuelFlow.setup(null, 1, "mine", 7L)) // free play
        assertNull(DuelFlow.setup(ready.copy(step = DuelStep.PICK), 1, "mine", 7L))
        assertNull(DuelFlow.setup(ready.copy(step = DuelStep.VERSUS), 1, "mine", 7L))
        assertNull(DuelFlow.setup(ready.copy(foeId = null), 1, "mine", 7L))
        assertNull(DuelFlow.setup(ready, 1, null, 7L))
        assertNull(DuelFlow.setup(ready, 1, "foe", 7L)) // nobody duels themselves
    }

    @Test
    fun aSetupCarriesSerialSeedLevelHoldAndWatch() {
        val ui = DuelUi(step = DuelStep.FIGHT, foeId = "foe", difficulty = DuelDifficulty.HARD)
        val s = assertNotNull(DuelFlow.setup(ui, serial = 4, mineId = "mine", seed = 1234L, hold = true))
        assertEquals(4, s.serial)
        assertEquals("mine", s.mineId)
        assertEquals("foe", s.foeId)
        assertEquals(BotLevel.HARD, s.foeLevel)
        assertEquals(1234L, s.seed)
        assertTrue(s.hold)
        assertNull(s.mineBot) // the child plays
        assertFalse(assertNotNull(DuelFlow.setup(ui, 4, "mine", 1234L)).hold)
        // Watch: mine is a bot at the same level.
        val watched = assertNotNull(DuelFlow.setup(ui.copy(watch = true, difficulty = DuelDifficulty.EASY), 5, "mine", 9L))
        assertEquals(BotLevel.EASY, watched.foeLevel)
        assertEquals(BotLevel.EASY, watched.mineBot)
    }

    @Test
    fun hudIsNullWithoutARunningReportOfMine() {
        assertNull(DuelFlow.hud(null, "mine", watch = false, nameOf = ::nameOf))
        assertNull(DuelFlow.hud(DuelReport.Refused(1, DuelRefusal.NO_ROOM), "mine", watch = false, nameOf = ::nameOf))
        // A match "someone" is not in: null, where duelHud would throw.
        val report = running(DuelMatch.start(MINE, FOE, ARENA, MARKS))
        assertNull(DuelFlow.hud(report, "someone", watch = false, nameOf = ::nameOf))
        assertNotNull(DuelFlow.hud(report, "mine", watch = false, nameOf = ::nameOf))
        assertNotNull(DuelFlow.hud(report, "foe", watch = false, nameOf = ::nameOf))
    }

    @Test
    fun hudIsDuelHudWithTheReportsArenaRulesAndParams() {
        val rules = DuelRules(pointsToWin = 3)
        val params = DuelParams.DEFAULT.copy(dashCooldownMs = 5000)
        // Mine dashed 200 ms ago: its dash button is recharging on the report's cooldown, not the default one.
        val state = fight(rules, params, first = mapOf("mine" to DuelInput.Dash()), ms = 200L)
        val model = assertNotNull(DuelFlow.hud(running(state, rules, params), "mine", watch = false, nameOf = ::nameOf))
        assertEquals(duelHud(state, "mine", ARENA, ::nameOf, rules, params), model)
        assertEquals(3, model.pointsToWin) // three pips
        val mine = state.duel.a
        assertEquals(DuelPhysics.dashCooldownLeft(mine, params), model.dash.cooldown)
        assertTrue(model.dash.cooldown > 0f)
        assertNotEquals(DuelPhysics.dashCooldownLeft(mine, DuelParams.DEFAULT), model.dash.cooldown)
        assertEquals("Мұрат", model.me.name)
        assertEquals("Айгүл", model.foe.name)
    }

    @Test
    fun aWatchedHudDisablesTheThreeButtonsAndKeepsTheRest() {
        val report = running(fight())
        val played = assertNotNull(DuelFlow.hud(report, "mine", watch = false, nameOf = ::nameOf))
        assertTrue(played.dash.enabled && played.jump.enabled && assertNotNull(played.special).enabled)
        val watched = assertNotNull(DuelFlow.hud(report, "mine", watch = true, nameOf = ::nameOf))
        assertFalse(watched.dash.enabled)
        assertFalse(watched.jump.enabled)
        assertFalse(assertNotNull(watched.special).enabled)
        assertEquals(
            played.copy(dash = played.dash.copy(enabled = false), jump = played.jump.copy(enabled = false), special = played.special.copy(enabled = false)),
            watched,
        )
    }
}
