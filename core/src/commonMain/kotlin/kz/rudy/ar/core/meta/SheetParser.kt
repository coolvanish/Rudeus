package kz.rudy.ar.core.meta

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterSheet

/**
 * Turns the Spark character-sheet reply into a [CharacterSheet] and enforces the contract from
 * `.claude/skills/meta-fixtures/SKILL.md`: unknown ability -> JUMP, exactly [LINES_PER_LANGUAGE] lines
 * in each of "kk" and "ru" (padded with [FALLBACK_LINES], truncated when longer).
 *
 * Expected model JSON (fences and surrounding prose are tolerated):
 * `{"name": "...", "personality": "...", "ability": "jump|fly|roll|hide", "lines": {"kk": [...], "ru": [...]}}`
 */
object SheetParser {
    const val LINES_PER_LANGUAGE = 5
    val LANGUAGES = listOf("kk", "ru")

    val FALLBACK_LINES: Map<String, List<String>> = mapOf(
        "kk" to listOf("Сәлем!", "Кеттік!", "Мен мұндамын!", "Қарап тұр!", "Жарайсың!"),
        "ru" to listOf("Привет!", "Пошли!", "Я здесь!", "Смотри!", "Молодец!"),
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
        val name = obj.string("name")?.takeIf { it.isNotBlank() } ?: "Dos"
        val personality = obj.string("personality") ?: ""
        val ability = parseAbility(obj.string("ability"))
        val linesEl = obj["lines"]
        val lines = LinkedHashMap<String, List<String>>()
        if (linesEl is JsonObject) {
            for ((lang, arr) in linesEl) {
                if (arr is JsonArray) lines[lang.lowercase()] = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            }
        }
        return normalise(CharacterSheet(name, personality, ability, lines))
    }

    /** "fly" / "FLY" / " Fly " -> FLY; anything else -> JUMP. */
    fun parseAbility(raw: String?): Ability {
        val key = raw?.trim()?.uppercase() ?: return Ability.JUMP
        return Ability.entries.firstOrNull { it.name == key } ?: Ability.JUMP
    }

    /** Ensures 5 non-blank lines for each of kk and ru; keeps any extra languages as they are. */
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
