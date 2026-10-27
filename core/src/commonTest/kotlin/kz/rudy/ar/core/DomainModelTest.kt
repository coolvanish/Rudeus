package kz.rudy.ar.core

import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Semantics
import kz.rudy.ar.core.domain.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DomainModelTest {
    @Test
    fun rectContainsAndArea() {
        val r = Polygon.rect(Vec2(0f, 0f), 0.2f, 0.1f)
        assertTrue(r.contains(Vec2(0.05f, 0.02f)))
        assertFalse(r.contains(Vec2(0.15f, 0f)))
        assertEquals(0.02f, r.area, 1e-5f)
        assertEquals(0f, r.centroid().u, 1e-5f)
    }

    @Test
    fun semanticsMapLabelsAndHeights() {
        assertEquals(ObjectKind.PLATFORM, Semantics.kindFor("book", 0.02f))
        assertEquals(ObjectKind.OBSTACLE, Semantics.kindFor("book", 0.08f))
        assertEquals(ObjectKind.OBSTACLE, Semantics.kindFor("cup", 0.09f))
        assertEquals(ObjectKind.DYNAMIC, Semantics.kindFor("hand", 0.03f))
    }
}
