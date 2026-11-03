package kz.rudy.ar.core.geometry

import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConvexHullTest {
    @Test
    fun interiorDuplicateAndEdgePointsAreDropped() {
        val corners = listOf(Vec2(0f, 0f), Vec2(1f, 0f), Vec2(1f, 1f), Vec2(0f, 1f))
        val hull = convexHull(corners + listOf(Vec2(0.5f, 0.5f), Vec2(0.5f, 0f), Vec2(1f, 1f)))
        assertEquals(corners.toSet(), hull.toSet())
        assertEquals(4, hull.size)
    }

    @Test
    fun hullIsCounterClockwiseAndHoldsEveryInteriorPoint() {
        val pts = listOf(Vec2(-1f, 2f), Vec2(3f, 0.5f), Vec2(0f, -2f), Vec2(0.2f, 0.1f), Vec2(1f, 1f), Vec2(-0.5f, 0f))
        val hull = Polygon(convexHull(pts))
        assertTrue(hull.signedArea() > 0f, "counter-clockwise")
        assertTrue(hull.contains(Vec2(0.2f, 0.1f)))
    }

    @Test
    fun degenerateInputGivesFewerThanThreePoints() {
        assertEquals(1, convexHull(listOf(Vec2(1f, 1f), Vec2(1f, 1f))).size)
        assertEquals(2, convexHull(listOf(Vec2(0f, 0f), Vec2(1f, 1f), Vec2(2f, 2f))).size)
    }
}
