package dev.ide.lang.resolve

/**
 * Reads the API-level guards Java and Kotlin share, from source text: `Build.VERSION.SDK_INT >= 26`,
 * `SDK_INT >= Build.VERSION_CODES.O`, `@RequiresApi(api = 26)`. A language backend finds the guarding
 * condition or annotation in its own tree and hands the text here, so both editors agree on what counts as
 * a guard.
 */
object ApiGuards {

    /** The diagnostic code both editors report the minSdk check under, so quick fixes can key off it. */
    const val NEW_API = "android.newApi"

    /** The code both editors report a JDK API newer than a JVM module's Java release under. */
    const val NEW_JDK_API = "java.newJdkApi"

    /** The [ApiLevelContext.platform]-specific code an API-level diagnostic is reported under. */
    fun codeFor(platform: ApiPlatform): String = if (platform == ApiPlatform.JDK) NEW_JDK_API else NEW_API

    /**
     * The API-level diagnostic's message: `Call requires API level 26 (current min is 21): `label``, or for
     * the JDK `Call requires Java 11 (module language level is 8): `label``.
     */
    fun message(what: String, label: String, since: Int, api: ApiLevelContext): String =
        if (api.platform == ApiPlatform.JDK) "$what requires Java $since (module language level is ${api.minLevel}): `$label`"
        else "$what requires API level $since (current min is ${api.minLevel}): `$label`"

    /** `android.os.Build.VERSION_CODES` constant names to their level. */
    val VERSION_CODES: Map<String, Int> = mapOf(
        "BASE" to 1, "BASE_1_1" to 2, "CUPCAKE" to 3, "DONUT" to 4, "ECLAIR" to 5, "ECLAIR_0_1" to 6,
        "ECLAIR_MR1" to 7, "FROYO" to 8, "GINGERBREAD" to 9, "GINGERBREAD_MR1" to 10, "HONEYCOMB" to 11,
        "HONEYCOMB_MR1" to 12, "HONEYCOMB_MR2" to 13, "ICE_CREAM_SANDWICH" to 14, "ICE_CREAM_SANDWICH_MR1" to 15,
        "JELLY_BEAN" to 16, "JELLY_BEAN_MR1" to 17, "JELLY_BEAN_MR2" to 18, "KITKAT" to 19, "KITKAT_WATCH" to 20,
        "LOLLIPOP" to 21, "LOLLIPOP_MR1" to 22, "M" to 23, "N" to 24, "N_MR1" to 25, "O" to 26, "O_MR1" to 27,
        "P" to 28, "Q" to 29, "R" to 30, "S" to 31, "S_V2" to 32, "TIRAMISU" to 33, "UPSIDE_DOWN_CAKE" to 34,
        "VANILLA_ICE_CREAM" to 35, "BAKLAVA" to 36,
    )

    private val SDK_INT = """(?:android\.os\.)?(?:Build\.)?(?:VERSION\.)?SDK_INT"""
    private val LEVEL = """(\d+|(?:android\.os\.)?(?:Build\.)?(?:VERSION_CODES\.)?[A-Z][A-Z0-9_]*)"""
    private val SDK_FIRST = Regex("""^$SDK_INT\s*(>=|>|<=|<|==)\s*$LEVEL$""")
    private val LEVEL_FIRST = Regex("""^$LEVEL\s*(>=|>|<=|<|==)\s*$SDK_INT$""")

    /** A level literal or `VERSION_CODES` reference (`26`, `O`, `Build.VERSION_CODES.O`), or null. */
    fun levelOf(text: String): Int? {
        val t = text.trim()
        t.toIntOrNull()?.let { return it }
        return VERSION_CODES[t.substringAfterLast('.')]
    }

    /**
     * The API level known to hold where [condition] evaluated to [outcome]: `SDK_INT >= 26` guarantees 26
     * when true and nothing when false, `SDK_INT < 26` guarantees 26 when false. A top-level `&&` combines
     * its operands when true, a top-level `||` when false; `!` flips. 0 when the text guarantees nothing.
     */
    fun levelGuaranteed(condition: String, outcome: Boolean): Int {
        val c = stripParens(condition.replace(Regex("\\s+"), " ").trim())
        if (c.startsWith("!") && !c.startsWith("!=")) {
            val inner = c.substring(1).trim()
            if (inner.startsWith("(") && stripParens(inner) != inner) return levelGuaranteed(inner, !outcome)
        }
        splitTopLevel(c, "&&")?.let { parts ->
            return if (outcome) parts.maxOf { levelGuaranteed(it, true) } else 0
        }
        splitTopLevel(c, "||")?.let { parts ->
            return if (!outcome) parts.maxOf { levelGuaranteed(it, false) } else 0
        }
        SDK_FIRST.matchEntire(c)?.let { m ->
            val n = levelOf(m.groupValues[2]) ?: return 0
            return comparison(m.groupValues[1], n, outcome)
        }
        LEVEL_FIRST.matchEntire(c)?.let { m ->
            val n = levelOf(m.groupValues[1]) ?: return 0
            val flipped = when (m.groupValues[2]) { ">=" -> "<="; ">" -> "<"; "<=" -> ">="; "<" -> ">"; else -> "==" }
            return comparison(flipped, n, outcome)
        }
        return 0
    }

    /** `SDK_INT <op> n` evaluated to [outcome]: the level that guarantees. */
    private fun comparison(op: String, n: Int, outcome: Boolean): Int = when (op) {
        ">=" -> if (outcome) n else 0
        ">" -> if (outcome) n + 1 else 0
        "<" -> if (outcome) 0 else n
        "<=" -> if (outcome) 0 else n + 1
        "==" -> if (outcome) n else 0
        else -> 0
    }

    /**
     * The level an annotation guarantees for the code it annotates: `@RequiresApi(26)`,
     * `@RequiresApi(api = Build.VERSION_CODES.O)`, `@TargetApi(26)`. [name] is the annotation's written
     * name and [argument] the text of its value argument; 0 for any other annotation.
     */
    fun levelFromAnnotation(name: String, argument: String?): Int {
        val simple = name.substringAfterLast('.')
        if (simple != "RequiresApi" && simple != "TargetApi") return 0
        val value = argument?.substringAfter('=')?.trim() ?: return 0
        return levelOf(value) ?: 0
    }

    private fun stripParens(text: String): String {
        var t = text
        while (t.startsWith("(") && t.endsWith(")") && closes(t)) t = t.substring(1, t.length - 1).trim()
        return t
    }

    /** Whether the `(` at index 0 is closed by the final `)`. */
    private fun closes(t: String): Boolean {
        var depth = 0
        for (i in t.indices) {
            when (t[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0 && i != t.length - 1) return false }
            }
        }
        return depth == 0
    }

    /** [text] split on [op] outside parentheses, or null when it has no top-level [op]. */
    private fun splitTopLevel(text: String, op: String): List<String>? {
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '(') depth++ else if (ch == ')') depth--
            else if (depth == 0 && text.startsWith(op, i)) {
                parts += text.substring(start, i).trim()
                i += op.length
                start = i
                continue
            }
            i++
        }
        if (parts.isEmpty()) return null
        parts += text.substring(start).trim()
        return parts
    }
}
