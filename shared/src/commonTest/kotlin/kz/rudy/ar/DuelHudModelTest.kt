package kz.rudy.ar

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.duel.Contestant
import kz.rudy.ar.core.duel.DuelFighter
import kz.rudy.ar.core.duel.DuelInput
import kz.rudy.ar.core.duel.DuelMatch
import kz.rudy.ar.core.duel.DuelMatchState
import kz.rudy.ar.core.duel.DuelParams
import kz.rudy.ar.core.duel.DuelRules
import kz.rudy.ar.core.duel.MatchPhase
import kz.rudy.ar.core.duel.PointReason
import kz.rudy.ar.core.duel.PointResult
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.duel.DuelBanner
import kz.rudy.ar.ui.duel.DuelHudModel
import kz.rudy.ar.ui.duel.GO_MS
import kz.rudy.ar.ui.duel.duelHud
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 28 x 40 cm table (edges at u = +-0.14): a POWER 10 dash knocks a GUARD 1 fighter off it from its mark. */
private val TABLE = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.28f, 0.4f))
private val MARKS = Vec2(-0.06f, 0f) to Vec2(0.06f, 0f)

private val RED = Contestant("red", FighterStats(power = 10, speed = 4, guard = 3, agility = 3), Ability.JUMP)
private val BLUE = Contestant("blue", FighterStats(power = 4, speed = 6, guard = 1, agility = 9), Ability.HIDE)

private val NAMES = mapOf("red" to "Мұрат", "blue" to "Айгүл")
private fun nameOf(id: String) = NAMES.getValue(id)

private fun start(a: Contestant = RED, b: Contestant = BLUE): DuelMatchState = DuelMatch.start(a, b, TABLE, MARKS)

private fun hud(s: DuelMatchState, me: String = "red", params: DuelParams = DuelParams.DEFAULT, rules: DuelRules = DuelRules()): DuelHudModel =
    duelHud(s, me, TABLE, ::nameOf, rules, params)

/** [ms] of tracked time, in small frames (any split gives the same states); [first] is the first frame's inputs. */
private fun advance(
    s0: DuelMatchState,
    ms: Long,
    first: Map<String, DuelInput> = emptyMap(),
    rules: DuelRules = DuelRules(),
): DuelMatchState {
    var s = s0
    var left = ms
    var inputs = first
    while (left > 0L) {
        val dt = minOf(left, 24L)
        s = DuelMatch.step(s, TABLE, dt, inputs, rules = rules).state
        inputs = emptyMap()
        left -= dt
    }
    return s
}

/** The countdown run out: FIGHT with the whole round clock left. */
private fun fightOf(s: DuelMatchState): DuelMatchState = advance(s, s.phaseLeftMs).also { assertEquals(MatchPhase.FIGHT, it.phase) }

/** FIGHT until the round ends: red dashes at blue the moment it starts. */
private fun redWinsTheRound(s: DuelMatchState): DuelMatchState {
    var cur = advance(fightOf(s), 8L, mapOf("red" to DuelInput.Dash()))
    var guard = 0
    while (cur.phase == MatchPhase.FIGHT && guard++ < 2000) cur = advance(cur, 24L)
    assertEquals(MatchPhase.POINT, cur.phase)
    return cur
}

private fun DuelMatchState.withA(f: (DuelFighter) -> DuelFighter) = copy(duel = duel.copy(a = f(duel.a)))
private fun DuelMatchState.inFight() = copy(phase = MatchPhase.FIGHT, phaseLeftMs = 60_000L)
private fun DuelFighter.inMode(mode: Mode) = copy(body = body.copy(state = body.state.copy(mode = mode)))

class DuelHudModelTest {

    @Test
    fun countdownShowsThreeTwoOne() {
        var s = start()
        assertEquals(DuelBanner.Count(3), hud(s).banner)
        s = advance(s, 400L) // 2600 left
        assertEquals(DuelBanner.Count(3), hud(s).banner)
        s = advance(s, 600L) // 2000 left: still two full seconds
        assertEquals(DuelBanner.Count(2), hud(s).banner)
        s = advance(s, 1000L) // 1000 left
        assertEquals(DuelBanner.Count(1), hud(s).banner)
        s = advance(s, 984L) // 16 ms left: never 0
        assertEquals(MatchPhase.COUNTDOWN, s.phase)
        assertEquals(DuelBanner.Count(1), hud(s).banner)
        // Clamped to 1..3 whatever the rules' countdown (a longer one starts at 3, never 5).
        assertEquals(DuelBanner.Count(3), hud(start().copy(phaseLeftMs = 5000L)).banner)
        assertEquals(DuelBanner.Count(1), hud(start().copy(phaseLeftMs = 0L)).banner)
    }

    @Test
    fun goShowsForTheFirstMomentOfTheFight() {
        var s = fightOf(start())
        assertEquals(60_000L, s.phaseLeftMs)
        assertEquals(DuelBanner.Go, hud(s).banner)
        s = advance(s, GO_MS - 8L)
        assertEquals(DuelBanner.Go, hud(s).banner)
        s = advance(s, 8L) // GO_MS of FIGHT have run
        assertNull(hud(s).banner)
        assertNull(hud(advance(s, 5_000L)).banner)
        // A shorter round (rules passed in) still counts the time that has run, not the time that is left.
        val rules = DuelRules(roundMs = 20_000L)
        val short = advance(DuelMatch.start(RED, BLUE, TABLE, MARKS, rules), 3000L, rules = rules)
        assertEquals(20_000L, short.phaseLeftMs)
        assertEquals(DuelBanner.Go, hud(short, rules = rules).banner)
        assertNull(hud(advance(short, GO_MS, rules = rules), rules = rules).banner)
    }

    @Test
    fun clockIsTheRoundsSecondsLeftRoundedUpAndOnlyInFight() {
        val s = fightOf(start())
        assertEquals(60, hud(s).clockSeconds)
        assertEquals(60, hud(advance(s, 8L)).clockSeconds) // 59.992 s left rounds up
        assertEquals(59, hud(advance(s, 1_000L)).clockSeconds)
        assertEquals(59, hud(advance(s, 1_008L)).clockSeconds)
        assertEquals(1, hud(s.copy(phaseLeftMs = 1L)).clockSeconds)
        assertEquals(0, hud(s.copy(phaseLeftMs = 0L)).clockSeconds)
        // Not FIGHT: no clock (COUNTDOWN's phaseLeftMs is the countdown, POINT's the pause).
        assertNull(hud(start()).clockSeconds)
        assertNull(hud(redWinsTheRound(start())).clockSeconds)
        assertNull(hud(s.copy(phase = MatchPhase.RESULT, phaseLeftMs = 0L)).clockSeconds)
    }

    @Test
    fun buttonsAreEnabledOnlyInFightAndOnlyWhenPhysicsWouldAct() {
        fun enabled(m: DuelHudModel) = Triple(m.dash.enabled, m.jump.enabled, m.special!!.enabled)

        // Ready on the ground in FIGHT: everything. Never outside FIGHT.
        val ready = start().inFight()
        assertEquals(Triple(true, true, true), enabled(hud(ready)))
        for (phase in listOf(MatchPhase.COUNTDOWN, MatchPhase.POINT, MatchPhase.RESULT)) {
            assertEquals(Triple(false, false, false), enabled(hud(ready.copy(phase = phase))), "$phase")
        }

        // On cooldown: that button only.
        val dashed = ready.withA { it.copy(dashCooldownMs = 900L) }
        assertEquals(Triple(false, true, true), enabled(hud(dashed)))
        val specialUsed = ready.withA { it.copy(specialCooldownMs = 900L) }
        assertEquals(Triple(true, true, false), enabled(hud(specialUsed)))

        // Stunned or dashing: input is ignored, so all three are off.
        assertEquals(Triple(false, false, false), enabled(hud(ready.withA { it.copy(stunMs = 200L) })))
        assertEquals(Triple(false, false, false), enabled(hud(ready.withA { it.copy(dashMs = 100L) })))
        assertEquals(Triple(false, false, false), enabled(hud(ready.withA { it.copy(knock = Vec2(1f, 0f)) })))

        // In the air: no second jump, no dash, no special.
        assertEquals(Triple(false, false, false), enabled(hud(ready.withA { it.inMode(Mode.JUMPING) })))

        // Out (over the edge): nothing, and the model says so.
        val out = ready.withA { it.copy(body = it.body.copy(state = it.body.state.copy(pos = Vec2(1f, 0f)))) }
        assertEquals(Triple(false, false, false), enabled(hud(out)))
        assertTrue(hud(out).me.out)
        assertFalse(hud(out).foe.out)

        // ROLL cannot jump (Physics.jump ignores it) but its special, the ram, works.
        val roller = start(a = Contestant("red", RED.stats, Ability.ROLL)).inFight()
        assertEquals(Triple(true, false, true), enabled(hud(roller)))

        // A paused (tracking lost) fighter takes nothing.
        assertEquals(Triple(false, false, false), enabled(hud(ready.withA { it.inMode(Mode.PAUSED) })))
    }

    @Test
    fun cooldownFractionsFollowTheFighter() {
        val params = DuelParams.DEFAULT
        val dashTotal = params.cooldownMsFor(RED.stats)
        val specialTotal = params.specialCooldownMsFor(RED.stats)
        val s = start().inFight()
        assertEquals(0f, hud(s).dash.cooldown)
        assertEquals(0f, hud(s).special!!.cooldown)
        assertEquals(0f, hud(s).jump.cooldown)

        val used = s.withA { it.copy(dashCooldownMs = dashTotal, specialCooldownMs = specialTotal / 4) }
        assertEquals(1f, hud(used).dash.cooldown)
        assertEquals(0.25f, hud(used).special!!.cooldown, 0.001f)
        val half = s.withA { it.copy(dashCooldownMs = dashTotal / 2) }
        assertEquals(0.5f, hud(half).dash.cooldown, 0.01f)

        // Each side reads its own fighter: red's cooldown is blue's 0.
        assertEquals(0f, hud(used, me = "blue").dash.cooldown)
        assertEquals(1f, hud(used, me = "red").dash.cooldown)

        // The params in use set the length: the same time left is a bigger fraction of a shorter cooldown.
        val shorter = params.copy(dashCooldownMs = params.dashCooldownMs / 2)
        val left = dashTotal / 4
        val quarter = s.withA { it.copy(dashCooldownMs = left) }
        assertTrue(hud(quarter, params = shorter).dash.cooldown > hud(quarter, params = params).dash.cooldown * 1.5f)
    }

    @Test
    fun specialLabelFollowsTheAbilityAndIsAbsentWithoutOne() {
        val expected = mapOf(
            Ability.JUMP to StringId.special_stomp,
            Ability.FLY to StringId.special_lift,
            Ability.ROLL to StringId.special_ram,
            Ability.HIDE to StringId.special_dodge,
        )
        for ((ability, label) in expected) {
            val m = hud(start(a = Contestant("red", RED.stats, ability)))
            assertEquals(label, m.special?.label, "$ability")
        }
        // Dash and Jump labels do not depend on the ability.
        assertEquals(StringId.duel_dash, hud(start()).dash.label)
        assertEquals(StringId.play_jump, hud(start()).jump.label)
        // A fighter built without an ability (a bare physics fighter) has no special button.
        val bare = start().withA { it.copy(ability = null) }.inFight()
        assertNull(hud(bare).special)
        assertTrue(hud(bare).dash.enabled)
    }

    @Test
    fun pointBannerSaysWhoScoredOrReplay() {
        val point = redWinsTheRound(start())
        assertEquals(1, point.scoreA)
        assertEquals(DuelBanner.Point(mine = true), hud(point, me = "red").banner)
        assertEquals(DuelBanner.Point(mine = false), hud(point, me = "blue").banner)
        // Nobody scored (both out in one sub-step, or a tie at the clock).
        val replay = point.copy(lastPoint = PointResult(1, null, PointReason.RING_OUT))
        assertEquals(DuelBanner.Point(mine = null), hud(replay, me = "red").banner)
        assertEquals(DuelBanner.Point(mine = null), hud(replay, me = "blue").banner)
    }

    @Test
    fun resultBannerSaysWonLostOrDraw() {
        var s = start()
        // Red takes every round until the match ends: the first to two points.
        var rounds = 0
        while (s.phase != MatchPhase.RESULT && rounds++ < 6) {
            s = if (s.phase == MatchPhase.POINT) advance(s, s.phaseLeftMs) else redWinsTheRound(s)
        }
        assertEquals(MatchPhase.RESULT, s.phase)
        assertEquals("red", s.winner)
        assertEquals(DuelBanner.Result(won = true), hud(s, me = "red").banner)
        assertEquals(DuelBanner.Result(won = false), hud(s, me = "blue").banner)
        assertEquals(2, hud(s, me = "red").me.score)
        assertEquals(0, hud(s, me = "red").foe.score)
        val draw = s.copy(winner = null)
        assertEquals(DuelBanner.Result(won = null), hud(draw, me = "red").banner)
        assertEquals(DuelBanner.Result(won = null), hud(draw, me = "blue").banner)
        assertNull(hud(s).clockSeconds)
    }

    @Test
    fun theModelIsSeenFromMySide() {
        val s = redWinsTheRound(start()).withA { it.copy(specialMs = 400L, ability = Ability.FLY) }
        val red = hud(s, me = "red")
        val blue = hud(s, me = "blue")
        assertEquals("red", red.me.id)
        assertEquals("blue", red.foe.id)
        assertEquals("blue", blue.me.id)
        assertEquals("red", blue.foe.id)
        assertEquals(red.me, blue.foe)
        assertEquals(red.foe, blue.me)
        assertEquals("Мұрат", red.me.name)
        assertEquals("Айгүл", red.foe.name)
        assertEquals(1, red.me.score)
        assertEquals(0, red.foe.score)
        assertEquals(1, blue.foe.score)
        // The FLY fighter is hovering: immune, whoever looks.
        assertTrue(red.me.immune)
        assertTrue(blue.foe.immune)
        assertFalse(red.foe.immune)
        // Round, points to win and phase are the match's, the same from both sides.
        assertEquals(red.round, blue.round)
        assertEquals(2, red.pointsToWin)
        assertEquals(red.phase, blue.phase)
        // Slot order does not matter: b's player has b's buttons.
        assertEquals(StringId.special_dodge, hud(start(), me = "blue").special?.label)
        assertEquals(StringId.special_stomp, hud(start(), me = "red").special?.label)
        // An id that is not in the duel is a programming error.
        assertFailsWith<IllegalArgumentException> { hud(s, me = "green") }
        assertNotNull(hud(s, me = "blue"))
    }

    @Test
    fun hudModelShowsWobble() {
        // T48's wobble as a share of the most there is (wobbleMax 4 by default): steady at the start, half after two
        // hits, full at the cap and never past it; from both sides alike; 0 when the params have no wobble.
        val s = start()
        assertEquals(0f, hud(s).me.wobble)
        assertEquals(0f, hud(s).foe.wobble)
        val shaken = s.inFight().withA { it.copy(wobble = 2f) }
        assertEquals(0.5f, hud(shaken, me = "red").me.wobble, 1e-6f)
        assertEquals(0.5f, hud(shaken, me = "blue").foe.wobble, 1e-6f)
        assertEquals(0f, hud(shaken, me = "red").foe.wobble)
        assertEquals(1f, hud(s.inFight().withA { it.copy(wobble = DuelParams.DEFAULT.wobbleMax) }).me.wobble)
        assertEquals(1f, hud(s.inFight().withA { it.copy(wobble = 99f) }).me.wobble)
        assertEquals(0f, hud(shaken, params = DuelParams.DEFAULT.copy(wobblePerHit = 0f, wobbleMax = 0f)).me.wobble)
        // A real hit: red dashes blue, blue's wobble is one hit of four.
        val hit = advance(fightOf(s), 400L, mapOf("red" to DuelInput.Dash()))
        assertEquals(DuelParams.DEFAULT.wobblePerHit / DuelParams.DEFAULT.wobbleMax, hud(hit).foe.wobble, 1e-6f)
    }

    @Test
    fun everyDuelStringHasThreeLanguages() {
        val required = listOf(
            StringId.duel_title, StringId.duel_pick_opponent, StringId.duel_fight, StringId.duel_watch, StringId.duel_vs,
            StringId.duel_round, StringId.duel_go, StringId.duel_point_mine, StringId.duel_point_foe, StringId.duel_replay,
            StringId.duel_won, StringId.duel_lost, StringId.duel_draw, StringId.duel_again, StringId.duel_done, StringId.duel_dash,
            StringId.special_stomp, StringId.special_lift, StringId.special_ram, StringId.special_dodge,
            StringId.difficulty_easy, StringId.difficulty_normal, StringId.difficulty_hard,
        )
        val all = StringId.entries.filter { it.name.startsWith("duel_") || it.name.startsWith("special_") || it.name.startsWith("difficulty_") }
        assertTrue(all.containsAll(required), "every id of the brief exists")
        for (id in all) {
            for ((lang, text) in listOf("kk" to id.kk, "ru" to id.ru, "en" to id.en)) {
                assertTrue(text.isNotBlank(), "${id.name} has no $lang text")
                assertTrue(text.length <= 24, "${id.name} ($lang) is not short: '$text'")
                assertFalse('!' in text, "${id.name} ($lang): exclamation marks are only for the listed lines")
            }
        }
        // The Kazakh and Russian copy is its own, not the English left in place (VS is the one shared word).
        for (id in all.filter { it != StringId.duel_vs }) {
            assertTrue(id.kk != id.en && id.ru != id.en, "${id.name} is not translated")
        }
    }
}
