package dev.codeassist.csharp.project.csproj
/**
 * Evaluates the `Condition="..."` expressions used by hand-written or template-generated project files.
 *
 * Supports comparisons such as `'$(Configuration)' == 'Debug'` and
 * `'$(TargetFramework)' != 'net10.0'`, joined by `and` and `or`.
 * String comparisons are case-insensitive, as in MSBuild.
 *
 * Only these forms are evaluated.
 * Unsupported expressions, such as `Exists(...)`, `HasTrailingSlash(...)`,
 * `$(A) > 3`, or expressions with parentheses, are treated as true, preserving
 * the previous behavior. `and` has higher precedence than `or`.
 * `Not` is not supported. Properties missing from [vars] expand to empty strings,
 * as in MSBuild.
 */
internal object CsprojCondition {

    private val OR = Regex("""\s+or\s+""", RegexOption.IGNORE_CASE)
    private val AND = Regex("""\s+and\s+""", RegexOption.IGNORE_CASE)

    private val COMPARISON = Regex(
        """^\s*(?:'([^']*)'|"([^"]*)"|(\S+?))\s*(==|!=)\s*(?:'([^']*)'|"([^"]*)"|(\S+?))\s*$""",
    )

    private val PROPERTY = Regex("""\$\((\w+)\)""")

    /** Returns whether [condition] holds. Missing, blank, and unsupported conditions evaluate to true. */
    fun holds(condition: String?, vars: Map<String, String>): Boolean {
        if (condition.isNullOrBlank()) return true

        return OR.split(condition).any { alternative ->
            AND.split(alternative).all { clause -> clause(clause, vars) }
        }
    }

    private fun clause(text: String, vars: Map<String, String>): Boolean {
        val match = COMPARISON.matchEntire(text) ?: return true
        val g = match.groupValues

        val left = expand(g[1].ifEmpty { g[2] }.ifEmpty { g[3] }, vars)
        val right = expand(g[5].ifEmpty { g[6] }.ifEmpty { g[7] }, vars)

        val equal = left.equals(right, ignoreCase = true)
        return if (g[4] == "==") equal else !equal
    }

    private fun expand(text: String, vars: Map<String, String>): String =
        PROPERTY.replace(text) { vars[it.groupValues[1]].orEmpty() }
}