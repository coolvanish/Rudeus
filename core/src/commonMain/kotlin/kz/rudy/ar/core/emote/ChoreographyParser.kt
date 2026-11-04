package kz.rudy.ar.core.emote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kz.rudy.ar.core.meta.MetaError
import kotlin.math.round

/**
 * Spark's choreography reply and choreo.json, both in one shape:
 * `{"hello": C, "bye": C, "dance": C, "spin": C, "roar": C}` (choreo.json adds `"source"`), with
 * `C = {"durationMs": n, "keys": [{"atMs": n, "scaleX": f, "scaleY": f, "yawDeg": f, "tiltDeg": f, "hop": f}], "line": n, "sound": "none|hello|roar|dance"}`.
 * Every field is optional (a missing pose field is its rest value), unknown keys are ignored, emote names are
 * case-insensitive. An emote that is missing or fails [ChoreographyRules.sanitize] gets `Choreographies.DEFAULT[emote]`.
 */
object ChoreographyParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pretty = Json { prettyPrint = true }

    /**
     * Spark's reply, fences and prose tolerated like SheetParser. source = SOURCE_SPARK when at least one emote came
     * from the reply, else SOURCE_DEFAULT. Throws [MetaError.Parse] only when the text holds no JSON object.
     */
    fun parse(raw: String): EmoteSet {
        val obj = extractObject(raw) ?: throw MetaError.Parse("choreography reply holds no JSON object: ${raw.take(120)}")
        val (set, fromReply) = fromObject(obj)
        return set.copy(source = if (fromReply > 0) EmoteSet.SOURCE_SPARK else EmoteSet.SOURCE_DEFAULT)
    }

    /** choreo.json: the parse shape plus "source". */
    fun toJson(set: EmoteSet): String {
        val fields = LinkedHashMap<String, JsonElement>()
        fields["source"] = JsonPrimitive(set.source)
        for (e in Emote.entries) fields[e.name.lowercase()] = choreographyJson(set[e])
        return pretty.encodeToString(JsonObject.serializer(), JsonObject(fields)) + "\n"
    }

    /**
     * Reads choreo.json, re-sanitising every choreography. The stored "source" is kept when at least one emote was
     * read; a missing or unknown "source", or nothing read, is SOURCE_DEFAULT; unreadable text -> [EmoteSet.defaults] (never throws).
     */
    fun fromJson(text: String): EmoteSet = try {
        val obj = json.parseToJsonElement(text) as? JsonObject
        if (obj == null) EmoteSet.defaults() else {
            val (set, read) = fromObject(obj)
            val stored = (obj["source"] as? JsonPrimitive)?.contentOrNull?.takeIf { it == EmoteSet.SOURCE_SPARK || it == EmoteSet.SOURCE_DEFAULT }
            set.copy(source = stored?.takeIf { read > 0 } ?: EmoteSet.SOURCE_DEFAULT)
        }
    } catch (e: Exception) {
        EmoteSet.defaults()
    }

    /** All five emotes (defaults filling the gaps) and how many came from [obj]. */
    private fun fromObject(obj: JsonObject): Pair<EmoteSet, Int> {
        val byName = obj.entries.associate { (k, v) -> k.trim().lowercase() to v }
        var read = 0
        val map = LinkedHashMap<Emote, Choreography>()
        for (e in Emote.entries) {
            val c = (byName[e.name.lowercase()] as? JsonObject)?.let { runCatching { choreography(e, it) }.getOrNull() }
                ?.let { ChoreographyRules.sanitize(it) }
            if (c != null) read++
            map[e] = c ?: Choreographies.DEFAULT.getValue(e)
        }
        return EmoteSet(EmoteSet.SOURCE_SPARK, map) to read
    }

    private fun choreography(emote: Emote, o: JsonObject): Choreography? {
        val duration = o.long("durationMs") ?: return null
        val keys = (o["keys"] as? JsonArray).orEmpty().mapNotNull { el ->
            val k = el as? JsonObject ?: return@mapNotNull null
            EmoteKey(
                atMs = k.long("atMs") ?: 0L,
                pose = EmotePose(
                    scaleX = k.float("scaleX") ?: 1f,
                    scaleY = k.float("scaleY") ?: 1f,
                    yawDeg = k.float("yawDeg") ?: 0f,
                    tiltDeg = k.float("tiltDeg") ?: 0f,
                    hop = k.float("hop") ?: 0f,
                ),
            )
        }
        val line = o.long("line")?.coerceIn(0L, ChoreographyRules.MAX_LINE.toLong())?.toInt() ?: 0
        val soundName = (o["sound"] as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase()
        val sound = EmoteSound.entries.firstOrNull { it.name == soundName } ?: EmoteSound.NONE
        return Choreography(emote, duration, keys, line, sound)
    }

    private fun choreographyJson(c: Choreography): JsonObject = JsonObject(
        linkedMapOf(
            "durationMs" to JsonPrimitive(c.durationMs),
            "keys" to JsonArray(
                c.keys.map { k ->
                    JsonObject(
                        linkedMapOf(
                            "atMs" to JsonPrimitive(k.atMs),
                            "scaleX" to JsonPrimitive(k.pose.scaleX),
                            "scaleY" to JsonPrimitive(k.pose.scaleY),
                            "yawDeg" to JsonPrimitive(k.pose.yawDeg),
                            "tiltDeg" to JsonPrimitive(k.pose.tiltDeg),
                            "hop" to JsonPrimitive(k.pose.hop),
                        ),
                    )
                },
            ),
            "line" to JsonPrimitive(c.line),
            "sound" to JsonPrimitive(c.sound.name.lowercase()),
        ),
    )

    /** First `{...}` block of [raw] (```json fences and chatty prefixes tolerated), or null. */
    private fun extractObject(raw: String): JsonObject? {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    private fun JsonObject.number(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? {
        val s = number(key)?.trim() ?: return null
        return s.toLongOrNull() ?: s.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { round(it).toLong() }
    }

    /** Parsed from the literal text (not via Double), so a float written by [toJson] reads back bit-exact. */
    private fun JsonObject.float(key: String): Float? = number(key)?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }
}
