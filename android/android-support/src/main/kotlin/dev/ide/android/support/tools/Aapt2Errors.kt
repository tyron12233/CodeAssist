package dev.ide.android.support.tools

/** One `error:` line aapt2 printed: the [path] it named (null when none), its 1-based [line] (null when it gave
 *  none, or `0`, which it prints for some XML parse errors) and the [message]. */
internal data class Aapt2Error(val path: String?, val line: Int?, val message: String)

/** Reads the errors out of an aapt2 `compile` / `link` log. */
internal object Aapt2Errors {
    private val LOCATED = Regex("""^(.+?)(?::(\d+))?: error: (.+)$""")
    private val BARE = Regex("""^error: (.+)$""")

    /** The closing lines aapt2 adds after the real errors, which only say that the step failed. */
    private val SUMMARIES = setOf(
        "file failed to compile.",
        "failed linking references.",
        "failed linking file resources.",
        "failed processing manifest.",
        "failed parsing overlays.",
        "failed to merge resources.",
    )

    /** The errors in [log], without aapt2's closing summaries unless they are all it printed. */
    fun parse(log: List<String>): List<Aapt2Error> {
        val all = log.mapNotNull { raw ->
            val s = raw.trim()
            BARE.matchEntire(s)?.let { return@mapNotNull Aapt2Error(null, null, it.groupValues[1]) }
            LOCATED.matchEntire(s)?.let { m ->
                Aapt2Error(m.groupValues[1], m.groupValues[2].toIntOrNull()?.takeIf { it > 0 }, m.groupValues[3])
            }
        }.distinct()
        return all.filter { it.message !in SUMMARIES }.ifEmpty { all }
    }
}
