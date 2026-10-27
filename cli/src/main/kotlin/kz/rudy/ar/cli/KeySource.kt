package kz.rudy.ar.cli

import java.io.File
import java.util.Properties

/**
 * Finds the Meta API key for `--live` and `refs generate` without anyone seeing it: the `META_API_KEY` environment
 * variable, else `META_API_KEY=...` in `local.properties` under [workDir] (the repo root: `:cli:run` sets the working
 * dir there). Surrounding quotes are stripped, as androidApp does. The value is never printed, logged or put into an
 * exception message; errors name the variable and the file only.
 */
internal object KeySource {
    const val VARIABLE = "META_API_KEY"
    const val FILE = "local.properties"

    fun read(workDir: File = File(".")): String {
        System.getenv(VARIABLE)?.let(::clean)?.takeIf { it.isNotEmpty() }?.let { return it }
        val file = File(workDir, FILE)
        if (file.isFile) {
            val props = Properties()
            try {
                file.reader().use(props::load)
            } catch (e: Exception) {
                // Never e.message: a parse error could quote a line of the file.
                throw CliError("cannot read ${file.absolutePath} (${e::class.simpleName})")
            }
            props.getProperty(VARIABLE)?.let(::clean)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        throw CliError("no $VARIABLE: not set in the environment and not found in ${file.absolutePath}")
    }

    /**
     * `"abc" # note` / `'abc'` -> the text between the quotes; `abc # note` -> `abc` (an unquoted ` #...` is a
     * trailing comment; keys never contain whitespace).
     */
    private fun clean(raw: String): String {
        val v = raw.trim()
        val quote = v.firstOrNull()?.takeIf { it == '"' || it == '\'' }
        if (quote != null) {
            val end = v.indexOf(quote, startIndex = 1)
            return (if (end > 0) v.substring(1, end) else v.substring(1)).trim()
        }
        return v.replace(TRAILING_COMMENT, "").trim()
    }

    private val TRAILING_COMMENT = Regex("\\s+#.*$")
}
