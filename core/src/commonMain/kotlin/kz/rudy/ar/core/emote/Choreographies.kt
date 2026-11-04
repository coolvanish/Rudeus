package kz.rudy.ar.core.emote

/**
 * Hand-authored defaults, shipped offline, on a bad Spark reply and for characters saved before emotes existed;
 * every one satisfies `ChoreographyRules.sanitize(x) == x` (EmoteTest checks it).
 */
object Choreographies {
    private fun k(atMs: Long, scaleX: Float = 1f, scaleY: Float = 1f, yawDeg: Float = 0f, tiltDeg: Float = 0f, hop: Float = 0f) =
        EmoteKey(atMs, EmotePose(scaleX, scaleY, yawDeg, tiltDeg, hop))

    /** 1200 ms: a wave of the whole body (tilt +15 / -15 / +15 / 0), a small hop on the first beat. */
    val HELLO = Choreography(
        Emote.HELLO, 1200,
        listOf(k(0), k(250, tiltDeg = 15f, hop = 0.08f), k(550, tiltDeg = -15f), k(850, tiltDeg = 15f), k(1200)),
        line = 0, sound = EmoteSound.HELLO,
    )

    /** 1400 ms: a bow (tilt 25 at 300 ms, held to 700, up at 1000), then two small +-8 tilts. */
    val BYE = Choreography(
        Emote.BYE, 1400,
        listOf(k(0), k(300, tiltDeg = 25f), k(700, tiltDeg = 25f), k(1000), k(1150, tiltDeg = 8f), k(1300, tiltDeg = -8f), k(1400)),
        line = 1, sound = EmoteSound.NONE,
    )

    /** 2400 ms: four hops of 0.15 every 400 ms, tilt alternating +-12, squash on each landing, one turn over the last 800 ms. */
    val DANCE = Choreography(
        Emote.DANCE, 2400,
        listOf(
            k(0),
            k(200, tiltDeg = 12f, hop = 0.15f), k(400, scaleX = 1.15f, scaleY = 0.85f),
            k(600, tiltDeg = -12f, hop = 0.15f), k(800, scaleX = 1.15f, scaleY = 0.85f),
            k(1000, tiltDeg = 12f, hop = 0.15f), k(1200, scaleX = 1.15f, scaleY = 0.85f),
            k(1400, tiltDeg = -12f, hop = 0.15f), k(1600, scaleX = 1.15f, scaleY = 0.85f),
            k(2400, yawDeg = 360f),
        ),
        line = 3, sound = EmoteSound.DANCE,
    )

    /** 1200 ms: two full turns, a little thinner at the middle. */
    val SPIN = Choreography(
        Emote.SPIN, 1200,
        listOf(k(0), k(600, scaleX = 0.9f, yawDeg = 360f), k(1200, yawDeg = 720f)),
        line = 4, sound = EmoteSound.NONE,
    )

    /** 1300 ms: puffs up to (1.3, 1.15) in 150 ms, shakes +-6 at 10 Hz for 600 ms (keys every 50 ms), back to rest. */
    val ROAR = Choreography(
        Emote.ROAR, 1300,
        buildList {
            add(k(0))
            add(k(150, scaleX = 1.3f, scaleY = 1.15f))
            for (i in 0 until 12) add(k(200L + 50L * i, scaleX = 1.3f, scaleY = 1.15f, tiltDeg = if (i % 2 == 0) 6f else -6f))
            add(k(1300))
        },
        line = 2, sound = EmoteSound.ROAR,
    )

    val DEFAULT: Map<Emote, Choreography> = linkedMapOf(
        Emote.HELLO to HELLO,
        Emote.BYE to BYE,
        Emote.DANCE to DANCE,
        Emote.SPIN to SPIN,
        Emote.ROAR to ROAR,
    )
}
