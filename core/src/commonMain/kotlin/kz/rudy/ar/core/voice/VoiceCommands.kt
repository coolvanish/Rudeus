package kz.rudy.ar.core.voice

/** What a push-to-talk phrase asks the character to do (T24). Nothing else is voice-controlled. */
enum class VoiceCommand { JUMP, WALK }

/**
 * Keyword match on a `MetaModels.transcribe` transcript. English is the demo language (T01, Oct 28); the ru/kk
 * keywords count when the model happens to return them.
 *
 * Normalisation: lowercase, `ё` -> `е`, every character that is not a letter or digit becomes a space, then the text
 * is split into words. A keyword matches a whole word only ("good" is not "go", "барлық" is not "бар"). The FIRST
 * word that is a keyword decides ("go and then jump" -> WALK). Pure.
 */
object VoiceCommands {
    private val JUMP_WORDS = setOf("jump", "jumped", "hop", "прыгай", "прыг", "прыжок", "секір")
    private val WALK_WORDS = setOf("go", "walk", "run", "forward", "иди", "вперед", "жүр", "бар")

    /** The command of the first keyword in [transcript], or null when nothing matches. */
    fun parse(transcript: String): VoiceCommand? {
        for (word in words(transcript)) {
            if (word in JUMP_WORDS) return VoiceCommand.JUMP
            if (word in WALK_WORDS) return VoiceCommand.WALK
        }
        return null
    }

    /** Lowercase words with punctuation stripped; `ё` folded to `е` so "вперёд" and "вперед" are one keyword. */
    internal fun words(transcript: String): List<String> {
        val cleaned = buildString(transcript.length) {
            for (c in transcript.lowercase()) {
                append(
                    when {
                        c == 'ё' -> 'е'
                        c.isLetterOrDigit() -> c
                        else -> ' '
                    },
                )
            }
        }
        return cleaned.split(' ').filter { it.isNotEmpty() }
    }
}
