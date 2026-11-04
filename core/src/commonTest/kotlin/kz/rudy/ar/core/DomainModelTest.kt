package kz.rudy.ar.core

import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Semantics
import kz.rudy.ar.core.domain.SurfaceProfile
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

    /** Day 10 probe (docs/api-samples/sam-table/table3-day10): low flat things and small generic finds are walkable. */
    @Test
    fun lowRemotesKnivesAndSmallObjectsArePlatforms() {
        assertEquals(ObjectKind.PLATFORM, Semantics.kindFor("remote", 0.019f))
        assertEquals(ObjectKind.OBSTACLE, Semantics.kindFor("remote", 0.08f), "a remote standing up is a wall")
        assertEquals(ObjectKind.PLATFORM, Semantics.kindFor("small object", 0.005f))
        assertEquals(ObjectKind.PLATFORM, Semantics.kindFor("Small Object", 0.005f), "compared lower-case")
        assertEquals(ObjectKind.OBSTACLE, Semantics.kindFor("small object", 0.06f))
        for (label in listOf("plate", "keyboard", "knife", "item")) assertEquals(ObjectKind.PLATFORM, Semantics.kindFor(label, 0.02f), label)
        assertTrue(Semantics.isCompact("small object"))
        assertTrue(Semantics.isCompact("item"))
        assertFalse(Semantics.liesFlat("small object"), "a guess: compact, not flat")
        for (label in listOf("knife", "remote", "plate", "keyboard")) assertTrue(Semantics.liesFlat(label), label)
        assertEquals(ObjectKind.OBSTACLE, Semantics.kindFor("cup", 0.02f), "a cup is never a platform")
    }

    @Test
    fun kindForUsesTheSurfaceProfile() {
        // A 12 cm box (a shoebox) is a platform for the 24 cm floor character, an obstacle for the 6 cm table one.
        assertEquals(ObjectKind.PLATFORM, Semantics.kindFor("box", 0.12f, SurfaceProfile.FLOOR))
        assertEquals(ObjectKind.OBSTACLE, Semantics.kindFor("box", 0.12f, SurfaceProfile.TABLE))
        assertEquals(ObjectKind.OBSTACLE, Semantics.kindFor("box", 0.12f), "the default profile is the table")
        assertEquals(ObjectKind.PLATFORM, Semantics.kindFor("book", 0.12f, SurfaceProfile.FLOOR))
        assertEquals(ObjectKind.OBSTACLE, Semantics.kindFor("box", 0.20f, SurfaceProfile.FLOOR), "past the floor's 16 cm")
        assertEquals(ObjectKind.OBSTACLE, Semantics.kindFor("cup", 0.09f, SurfaceProfile.FLOOR), "not a platform label")
        assertEquals(ObjectKind.DYNAMIC, Semantics.kindFor("hand", 0.10f, SurfaceProfile.FLOOR))
        // The table default matches the pre-profile rule for every label the table scan prompts (and the other
        // platform labels): platform labels up to 4 cm are platforms, hands are dynamic, the rest obstacles.
        fun before(label: String, h: Float): ObjectKind = when {
            label == "hand" || label == "finger" -> ObjectKind.DYNAMIC
            label in setOf("book", "phone", "notebook", "card", "paper") && h <= 0.04f -> ObjectKind.PLATFORM
            else -> ObjectKind.OBSTACLE
        }
        for (label in listOf("cup", "book", "phone", "bottle", "hand", "object", "notebook", "card", "paper", "finger")) {
            for (h in listOf(0.01f, 0.04f, 0.041f, 0.09f, 0.12f)) {
                assertEquals(before(label, h), Semantics.kindFor(label, h), "$label $h")
                assertEquals(before(label, h), Semantics.kindFor(label, h, SurfaceProfile.TABLE), "$label $h")
            }
        }
    }
}
