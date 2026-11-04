package kz.rudy.ar.core.emote

/** Samples a choreography at any millisecond; pure, so the host only has to keep the start time. */
object EmotePlayer {
    /**
     * REST before 0 and from durationMs on. Between two keys scale, tilt and hop blend with smoothstep(t); yaw blends
     * linearly in time and in degrees (no angle wrapping), so 0 -> 360 turns the quad once at an even speed, never the
     * short way round. Before the first key the blend starts from REST at 0; after the last key its pose holds.
     */
    fun sample(c: Choreography, tMs: Long): EmotePose {
        if (tMs < 0 || tMs >= c.durationMs || c.keys.isEmpty()) return EmotePose.REST
        val next = c.keys.indexOfFirst { it.atMs > tMs }
        if (next < 0) return c.keys.last().pose
        val a = if (next == 0) EmoteKey(0L, EmotePose.REST) else c.keys[next - 1]
        val b = c.keys[next]
        val span = (b.atMs - a.atMs).coerceAtLeast(1L)
        val u = ((tMs - a.atMs).toFloat() / span).coerceIn(0f, 1f)
        val s = u * u * (3f - 2f * u)
        return EmotePose(
            scaleX = lerp(a.pose.scaleX, b.pose.scaleX, s),
            scaleY = lerp(a.pose.scaleY, b.pose.scaleY, s),
            yawDeg = lerp(a.pose.yawDeg, b.pose.yawDeg, u),
            tiltDeg = lerp(a.pose.tiltDeg, b.pose.tiltDeg, s),
            hop = lerp(a.pose.hop, b.pose.hop, s),
        )
    }

    fun finished(c: Choreography, tMs: Long): Boolean = tMs >= c.durationMs

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
