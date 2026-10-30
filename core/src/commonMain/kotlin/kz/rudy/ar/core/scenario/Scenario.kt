package kz.rudy.ar.core.scenario

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Semantics
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2

/** One scripted tap. [atMs] is the time since the scenario started. */
@Serializable
sealed class ScenarioStep {
    abstract val atMs: Long

    /** A walk tap at [target] (plane coordinates, metres): `Physics.walkTo`. */
    @Serializable
    @SerialName("walk")
    data class WalkTo(override val atMs: Long, val target: Vec2) : ScenarioStep()

    /** A jump tap: `Physics.jump` (a no-op while airborne, see Physics). */
    @Serializable
    @SerialName("jump")
    data class Jump(override val atMs: Long) : ScenarioStep()
}

/**
 * A timed list of taps that plays the same demo every time (Day 5 gate: the scripted scenario runs 3x in a row).
 * Pure data: [ScenarioRunner] replays it on the JVM, [ScenarioPlayer] feeds it to the AR host's frame loop.
 */
@Serializable
data class Scenario(
    val id: String,
    /** The runner stops here. */
    val durationMs: Long,
    /** Sorted by [ScenarioStep.atMs]; every `atMs` in `0..durationMs`. Equal times keep list order. */
    val steps: List<ScenarioStep>,
) {
    init {
        require(durationMs >= 0) { "durationMs must be >= 0, got $durationMs" }
        require(steps.all { it.atMs in 0..durationMs }) { "every step must be in 0..$durationMs ms: $steps" }
        require(steps.zipWithNext().all { (a, b) -> a.atMs <= b.atMs }) { "steps must be sorted by atMs: $steps" }
    }

    fun toJson(): String = Scenarios.json.encodeToString(serializer(), this)

    /**
     * The same script for a level scaled by [k] (e.g. [SurfaceProfile.scale] for the floor): positions × k, times
     * unchanged ([kz.rudy.ar.core.physics.PhysicsParams.forProfile] scales the speeds by the same factor).
     */
    fun scaled(k: Float): Scenario = if (k == 1f) this else copy(
        steps = steps.map { s ->
            when (s) {
                is ScenarioStep.WalkTo -> s.copy(target = s.target * k)
                is ScenarioStep.Jump -> s
            }
        },
    )

    companion object {
        fun fromJson(text: String): Scenario = Scenarios.json.decodeFromString(serializer(), text)
    }
}

object Scenarios {
    /** Polymorphic config for [ScenarioStep]: `{"type":"walk",...}` / `{"type":"jump",...}`. */
    val json: Json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /**
     * The Day 5 beat sheet, for [day5Level] on the table (FLOOR: [Scenario.scaled] by the profile scale).
     * Speeds are `PhysicsParams.DEFAULT` (walk 5 cm/s, a jump carries 10 cm/s).
     *
     * - 0 s: walk to (0.30, 0). Head-on into the cup: pinned at u = 0.08 from 1.6 s (BLOCKED).
     * - 4 s: walk to the book centre (0, 0.15): steps up onto the 2 cm book at 6.3 s (STEPPED_UP), arrives 7.3 s.
     * - 9 s: walk toward (-0.30, 0) (beyond the gap).
     * - 10.4 s: hop off the book 7 cm along the walk (the book edge is at 8.4 cm). Walking off would be a FELL; the
     *   hop is a JUMPED / LANDED instead. JUMP: lands on the table at 11.1 s, 2.7 cm (path) short of the gap. FLY
     *   (slow arc, 2.2 s airborne, ~22 cm): this hop already crosses the whole gap, landing at u = -0.264.
     *   Window: 10.2 s (earlier, FLY comes down in the gap) .. 10.68 s (later, walked off the book).
     * - 11.4 s: jump 1 cm before the gap edge. JUMP: comes down over the gap at 11.5 s, falls, respawns 12.2 s. FLY:
     *   still in the air from the hop, the tap is a no-op. Window: 11.1 s (JUMP landing) .. 11.64 s (at the edge).
     */
    val DAY5: Scenario = Scenario(
        id = "day5",
        durationMs = 25_000,
        steps = listOf(
            ScenarioStep.WalkTo(0, Vec2(0.30f, 0f)),
            ScenarioStep.WalkTo(4_000, Vec2(0f, 0.15f)),
            ScenarioStep.WalkTo(9_000, Vec2(-0.30f, 0f)),
            ScenarioStep.Jump(10_400),
            ScenarioStep.Jump(11_400),
        ),
    )

    /**
     * The level [DAY5] assumes, scaled by [SurfaceProfile.scale]: spawn (0, 0); cup r 0.04 at (0.15, 0) h 0.09;
     * book 0.15 x 0.10 at (0, 0.15) h 0.02; gap = CLIFF strip 0.10 (u) x 0.30 (v) centred at (-0.20, 0);
     * boundary ±0.35 square. Cup and book kinds come from [Semantics.kindFor] at [profile]'s scale.
     */
    fun day5Level(profile: SurfaceProfile = SurfaceProfile.TABLE): Level {
        val k = profile.scale
        fun obj(id: String, label: String, footprint: Polygon, heightM: Float) =
            LevelObject(id, label, Semantics.kindFor(label, heightM, profile), footprint, heightM)
        val cup = obj("cup-1", "cup", Polygon.circle(Vec2(0.15f, 0f) * k, 0.04f * k), 0.09f * k)
        val book = obj("book-1", "book", Polygon.rect(Vec2(0f, 0.15f) * k, 0.15f * k, 0.10f * k), 0.02f * k)
        val gap = LevelObject("gap-1", "gap", ObjectKind.CLIFF, Polygon.rect(Vec2(-0.20f, 0f) * k, 0.10f * k, 0.30f * k), 0f)
        return Level(
            objects = listOf(cup, book, gap),
            boundary = Polygon.rect(Vec2.ZERO, 0.70f * k, 0.70f * k),
            spawn = Vec2.ZERO,
        )
    }
}

/**
 * Incremental player for the AR host: call [due] once per frame with the host clock; each step is returned exactly
 * once, in list order, as soon as `startedAtMs + atMs <= nowMs`. A clock that jumps past several steps gets them all.
 */
class ScenarioPlayer(val scenario: Scenario, private val startedAtMs: Long) {
    /** Number of steps returned so far. */
    var issued: Int = 0
        private set

    /** Every step issued and the last [due] call was at or past `startedAtMs + durationMs`. */
    var finished: Boolean = false
        private set

    fun due(nowMs: Long): List<ScenarioStep> {
        val elapsed = nowMs - startedAtMs
        val steps = scenario.steps
        var out: MutableList<ScenarioStep>? = null
        while (issued < steps.size && steps[issued].atMs <= elapsed) {
            (out ?: ArrayList<ScenarioStep>().also { out = it }) += steps[issued]
            issued++
        }
        if (issued == steps.size && elapsed >= scenario.durationMs) finished = true
        return out ?: emptyList()
    }
}
