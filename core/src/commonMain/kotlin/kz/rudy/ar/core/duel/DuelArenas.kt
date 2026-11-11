package kz.rudy.ar.core.duel

import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.physics.PhysicsParams

/**
 * The arenas the simulator ([DuelSim]) and the CLI tune the duel on: plane coordinates, metres, the spawn at the
 * origin, every one mirror-symmetric across `u = 0` so that swapping the marks swaps nothing else.
 */
object DuelArenas {
    /** How far apart [marks] puts the two fighters, centre to centre: a 10 cm gap, just out of one dash's reach (13.5 cm). */
    const val SEPARATION_M = 0.16f

    /** An empty 60 x 40 cm table. */
    val EMPTY: Level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.6f, 0.4f))

    /**
     * [EMPTY] with a cup (OBSTACLE, 9 cm tall, 8 cm across) and a book (PLATFORM, 3 cm, 12 x 8 cm), one on each side of
     * the line between the marks: the cup stops knockback (wall slams) and has to be walked round, the book is high
     * ground (a hit from it is stronger).
     */
    val CUP_AND_BOOK: Level = EMPTY.copy(
        objects = listOf(
            LevelObject("cup", "cup", ObjectKind.OBSTACLE, Polygon.circle(Vec2(0f, 0.11f), 0.04f), 0.09f),
            LevelObject("book", "book", ObjectKind.PLATFORM, Polygon.rect(Vec2(0f, -0.11f), 0.12f, 0.08f), 0.03f),
        ),
    )

    /** A small 30 x 30 cm table: every edge is close. */
    val SMALL: Level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.3f, 0.3f))

    /** By name, in this order: "empty", "cup-and-book", "small". */
    val ALL: Map<String, Level> = linkedMapOf("empty" to EMPTY, "cup-and-book" to CUP_AND_BOOK, "small" to SMALL)

    /**
     * The marks a match on [arena] starts on: [DuelArena.marks] for fighters of [radiusM], [separationM] apart.
     * Throws when the arena has no room for two fighters (none of [ALL]).
     */
    fun marks(
        arena: Level,
        radiusM: Float = SurfaceProfile.TABLE.radiusM,
        separationM: Float = SEPARATION_M,
        physics: PhysicsParams = PhysicsParams.DEFAULT,
    ): Pair<Vec2, Vec2> = requireNotNull(DuelArena.marks(arena, radiusM, separationM, physics)) {
        "the arena has no room for two fighters of radius $radiusM m"
    }
}
