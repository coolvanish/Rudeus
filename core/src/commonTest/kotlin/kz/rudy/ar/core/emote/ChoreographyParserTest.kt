package kz.rudy.ar.core.emote

import kz.rudy.ar.core.meta.MetaError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ChoreographyParserTest {
    private fun c(duration: Long, keys: String, line: Int = 0, sound: String = "none") =
        """{"durationMs": $duration, "keys": [$keys], "line": $line, "sound": "$sound"}"""

    private val full = """
        Here you go:
        ```json
        {"hello": ${c(900, """{"atMs":0},{"atMs":300,"tiltDeg":10,"hop":0.05},{"atMs":900}""", 0, "hello")},
         "bye": ${c(1000, """{"atMs":0},{"atMs":500,"tiltDeg":20},{"atMs":1000}""", 1)},
         "dance": ${c(2000, """{"atMs":0},{"atMs":250,"hop":0.2,"tiltDeg":-10},{"atMs":2000,"yawDeg":360}""", 3, "dance")},
         "spin": ${c(800, """{"atMs":0,"scaleX":1,"scaleY":1,"yawDeg":0,"tiltDeg":0,"hop":0},{"atMs":800,"yawDeg":360}""", 4)},
         "roar": ${c(1100, """{"atMs":0},{"atMs":200,"scaleX":1.4,"scaleY":1.2,"extra":true},{"atMs":1100}""", 2, "ROAR")},
         "comment": "unknown keys are ignored"}
        ```
    """.trimIndent()

    @Test
    fun parsesAFullReply() {
        val set = ChoreographyParser.parse(full)
        assertEquals(EmoteSet.SOURCE_SPARK, set.source)
        assertEquals(Emote.entries.toSet(), set.choreographies.keys)
        val hello = set[Emote.HELLO]
        assertEquals(900L, hello.durationMs)
        assertEquals(EmoteSound.HELLO, hello.sound)
        assertEquals(EmotePose(tiltDeg = 10f, hop = 0.05f), hello.keys[1].pose)
        assertEquals(EmoteSound.ROAR, set[Emote.ROAR].sound)
        assertEquals(1.4f, set[Emote.ROAR].keys[1].pose.scaleX)
        assertEquals(2, set[Emote.ROAR].line)
        assertEquals(360f, set[Emote.SPIN].keys.last().pose.yawDeg)
        for (e in Emote.entries) assertNotEquals(Choreographies.DEFAULT[e], set[e], "$e came from the reply")
    }

    @Test
    fun missingEmoteFallsBackToDefault() {
        val set = ChoreographyParser.parse("""{"hello": ${c(900, """{"atMs":0},{"atMs":900}""")}}""")
        assertEquals(EmoteSet.SOURCE_SPARK, set.source)
        assertEquals(900L, set[Emote.HELLO].durationMs)
        for (e in Emote.entries - Emote.HELLO) assertEquals(Choreographies.DEFAULT[e], set.choreographies[e])
        assertEquals(EmoteSet.SOURCE_DEFAULT, ChoreographyParser.parse("""{"unrelated": 1}""").source)
    }

    @Test
    fun brokenEmoteFallsBackAndSourceStaysSpark() {
        val raw = """{"hello": ${c(9_000, """{"atMs":0}""")}, "bye": {"durationMs": "soon", "keys": 5}, "dance": [], "spin": ${c(800, "")},
            "roar": ${c(1100, """{"atMs":0},{"atMs":200,"scaleX":1.4}""", 2, "roar")}}"""
        val set = ChoreographyParser.parse(raw)
        assertEquals(EmoteSet.SOURCE_SPARK, set.source, "roar came from the reply")
        for (e in listOf(Emote.HELLO, Emote.BYE, Emote.DANCE, Emote.SPIN)) assertEquals(Choreographies.DEFAULT[e], set.choreographies[e], "$e")
        assertEquals(1100L, set[Emote.ROAR].durationMs)
    }

    @Test
    fun noJsonObjectThrowsParse() {
        assertFailsWith<MetaError.Parse> { ChoreographyParser.parse("I cannot dance, sorry.") }
        assertFailsWith<MetaError.Parse> { ChoreographyParser.parse("{not json at all") }
    }

    @Test
    fun jsonRoundTripKeepsEverySet() {
        for (set in listOf(EmoteSet.defaults(), ChoreographyParser.parse(full), ChoreographyParser.parse("""{"spin": ${c(800, """{"atMs":400,"yawDeg":200}""")}}"""))) {
            val text = ChoreographyParser.toJson(set)
            assertEquals(set, ChoreographyParser.fromJson(text))
            assertEquals(set.copy(source = EmoteSet.SOURCE_SPARK), ChoreographyParser.parse(text), "choreo.json is also a valid reply")
        }
    }

    @Test
    fun unreadableJsonYieldsDefaults() {
        for (text in listOf("", "garbage", "[1,2]", "{\"hello\": 3}", "{\"source\": \"spark\"}", "{\"source\": \"spark\", \"hello\": {\"durationMs\": 1}}")) {
            assertEquals(EmoteSet.defaults(), ChoreographyParser.fromJson(text), text)
        }
    }
}
