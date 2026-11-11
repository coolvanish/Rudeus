package kz.rudy.ar.core.meta

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.SpriteView
import kz.rudy.ar.core.domain.Stat
import kz.rudy.ar.core.domain.StatBudget

/**
 * Turns the Spark character-sheet reply into a [CharacterSheet] and enforces the contract from
 * `.claude/skills/meta-fixtures/SKILL.md`: unknown ability -> JUMP, exactly [LINES_PER_LANGUAGE] lines
 * in each of "kk", "ru" and "en" (padded with [FALLBACK_LINES], truncated when longer).
 *
 * Expected model JSON (fences and surrounding prose are tolerated):
 * `{"names": {"kk": "...", "ru": "...", "en": "..."}, "personality": "...", "ability": "jump|fly|roll|hide", "lines": {"kk": [...], "ru": [...], "en": [...]}}`
 * Replies recorded before `names` carry one `"name"` instead; it is kept as the Kazakh name.
 *
 * Since T40 the reply also carries the duel stats (all optional here, required in the strict schema):
 * `"stats": {"power": 7, "speed": 3, "guard": 8, "agility": 2}` (numbers or numeric strings, any range: they go
 * through [StatBudget.normalise]), `"why": {"kk": {"power": "...", ...}, "ru": {...}, "en": {...}}` (a few words per
 * stat), `"view": "front|side|back"`, `"faces": "left|right"`. A reply without them (every sheet recorded before
 * T40) gets `stats == null`, no reasons, no view: the duel then uses the archetype of its ability. A saved `sheet.json`
 * (Bundle.json shape: `statReasons`, `baseView`, `facesRight`) reads the same way.
 */
object SheetParser {
    const val LINES_PER_LANGUAGE = 5

    /** A stat reason longer than this is cut (the fighter card has room for a few words). */
    const val REASON_MAX_CHARS = 40
    val LANGUAGES = listOf("kk", "ru", "en")

    val FALLBACK_LINES: Map<String, List<String>> = mapOf(
        "kk" to listOf("Сәлем!", "Кеттік!", "Мен мұндамын!", "Қарап тұр!", "Жарайсың!"),
        "ru" to listOf("Привет!", "Пошли!", "Я здесь!", "Смотри!", "Молодец!"),
        "en" to listOf("Hi!", "Let's go!", "I'm here!", "Look!", "Well done!"),
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parses raw model text. Throws [MetaError.Parse] when no JSON object can be found. */
    fun parse(raw: String): CharacterSheet = fromJson(extractObject(raw))

    /**
     * The model's JSON object exactly as returned (fences and prose stripped, no normalisation).
     * Used by [parse] and by [FixtureConverter] for the `sheet` field. Throws [MetaError.Parse] when none is found.
     */
    fun extractObject(raw: String): JsonObject =
        extractJson(raw) ?: throw MetaError.Parse("character sheet reply holds no JSON object: ${raw.take(120)}")

    fun fromJson(element: JsonElement): CharacterSheet {
        val obj = element as? JsonObject ?: throw MetaError.Parse("character sheet is not a JSON object")
        val names = LinkedHashMap<String, String>()
        (obj["names"] as? JsonObject)?.forEach { (lang, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { names[lang.lowercase()] = it }
        }
        val name = names["kk"] ?: obj.string("name")?.takeIf { it.isNotBlank() } ?: names.values.firstOrNull() ?: "Dos"
        val personality = obj.string("personality") ?: ""
        val ability = parseAbility(obj.string("ability"))
        val linesEl = obj["lines"]
        val lines = LinkedHashMap<String, List<String>>()
        if (linesEl is JsonObject) {
            for ((lang, arr) in linesEl) {
                if (arr is JsonArray) lines[lang.lowercase()] = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            }
        }
        return normalise(
            CharacterSheet(
                name, personality, ability, lines, names,
                stats = parseStats(obj["stats"]),
                // The second key of each pair is the saved `sheet.json` shape (Bundle.json), which the Android host
                // also reads through this parser for the bundled characters.
                statReasons = parseReasons(obj["why"] ?: obj["statReasons"]),
                baseView = parseView(obj.string("view") ?: obj.string("baseView")),
                facesRight = parseFaces(obj.string("faces")) ?: (obj["facesRight"] as? JsonPrimitive)?.booleanOrNull,
            ),
        )
    }

    /**
     * `{"power": 7, "speed": "3", ...}` -> the balanced stats; keys are matched case-insensitively, values may be
     * numbers or numeric strings, anything else counts as missing. Null when there is no object or no usable score.
     */
    fun parseStats(element: JsonElement?): FighterStats? {
        val obj = element as? JsonObject ?: return null
        val raw = LinkedHashMap<Stat, Float>()
        for ((key, value) in obj) {
            val stat = statOf(key) ?: continue
            val number = (value as? JsonPrimitive)?.contentOrNull?.trim()?.toFloatOrNull() ?: continue
            raw[stat] = number
        }
        return StatBudget.normalise(raw)
    }

    /**
     * `{"kk": {"power": "big claws", ...}, ...}` -> language -> stat -> reason, for [LANGUAGES] only. Each reason is
     * trimmed and cut at [REASON_MAX_CHARS]; blank ones, unknown stats and languages left with none are dropped.
     */
    fun parseReasons(element: JsonElement?): Map<String, Map<Stat, String>> {
        val obj = element as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, Map<Stat, String>>()
        for ((lang, perStat) in obj) {
            val code = lang.trim().lowercase()
            if (code !in LANGUAGES || code in out || perStat !is JsonObject) continue
            val reasons = LinkedHashMap<Stat, String>()
            for ((key, value) in perStat) {
                val stat = statOf(key) ?: continue
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::cutReason) ?: continue
                if (text.isNotEmpty() && stat !in reasons) reasons[stat] = text
            }
            if (reasons.isNotEmpty()) out[code] = reasons
        }
        return out
    }

    /** "front" / "Side" / " BACK " -> the view; anything else (or nothing) -> null. */
    fun parseView(raw: String?): SpriteView? {
        val key = raw?.trim()?.uppercase() ?: return null
        return SpriteView.entries.firstOrNull { it.name == key }
    }

    /** "right" -> true, "left" -> false, anything else (or nothing) -> null. */
    fun parseFaces(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
        "right" -> true
        "left" -> false
        else -> null
    }

    private fun statOf(key: String): Stat? {
        val k = key.trim().uppercase()
        return Stat.entries.firstOrNull { it.name == k }
    }

    /** Trimmed, at most [REASON_MAX_CHARS] characters, never ending in half a surrogate pair. */
    private fun cutReason(raw: String): String {
        val t = raw.trim()
        if (t.length <= REASON_MAX_CHARS) return t
        var cut = t.take(REASON_MAX_CHARS)
        if (cut.last().isHighSurrogate()) cut = cut.dropLast(1)
        return cut.trimEnd()
    }

    /**
     * The sheet a character ships with when the sheet call fails: a name derived from [ref] (its first word with
     * letters, capitalised: "cat-01" -> "Cat"; "Dos" when there is none), ability JUMP, the [FALLBACK_LINES].
     * No stats: the duel uses the JUMP archetype.
     */
    fun fallback(ref: String): CharacterSheet {
        val word = ref.split('-', '_', ' ', '.').map { w -> w.filter { it.isLetter() } }.firstOrNull { it.isNotEmpty() }
        val name = word?.lowercase()?.replaceFirstChar { it.uppercaseChar() } ?: "Dos"
        return normalise(CharacterSheet(name, "", Ability.JUMP, FALLBACK_LINES))
    }

    /** "fly" / "FLY" / " Fly " -> FLY; anything else -> JUMP. */
    fun parseAbility(raw: String?): Ability {
        val key = raw?.trim()?.uppercase() ?: return Ability.JUMP
        return Ability.entries.firstOrNull { it.name == key } ?: Ability.JUMP
    }

    /** Ensures 5 non-blank lines for each of [LANGUAGES]; keeps any extra languages as they are. */
    fun normalise(sheet: CharacterSheet): CharacterSheet {
        val out = LinkedHashMap<String, List<String>>()
        for (lang in LANGUAGES) {
            val given = sheet.lines[lang].orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
            val fallback = FALLBACK_LINES.getValue(lang)
            val padded = ArrayList(given.take(LINES_PER_LANGUAGE))
            var i = 0
            while (padded.size < LINES_PER_LANGUAGE) { padded += fallback[i % fallback.size]; i++ }
            out[lang] = padded
        }
        for ((lang, l) in sheet.lines) if (lang !in out) out[lang] = l
        return sheet.copy(name = sheet.name.ifBlank { "Dos" }, lines = out)
    }

    /** Finds the first `{...}` block (handles ```json fences and chatty prefixes). */
    private fun extractJson(raw: String): JsonObject? {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
