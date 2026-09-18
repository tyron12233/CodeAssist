package dev.ide.lang.signature

/**
 * A tiny, language-neutral lexical scan that finds the call the caret sits inside and which argument it is on.
 * It tracks a bracket stack while skipping string/char literals and comments, so a `(` inside a string or a
 * `,` inside a nested `[...]` / `{...}` / `(...)` doesn't fool it. This is the reliable primitive both the JDT
 * and Kotlin signature-help backends use to locate the call site on a half-typed buffer (where the AST may not
 * yet have a clean call node): find the open paren + callee name range here, then resolve the callee with the
 * backend's own resolver.
 *
 * It is deliberately C/Java/Kotlin-family lexis (matched `()[]{}`, `"`/`'` literals, `//` and block comments,
 * Kotlin `"""` raw strings). It does not understand string templates' `${ }` as code, so a `(` inside a Kotlin
 * template is skipped — acceptable for a best-effort locator.
 */
object SignatureScan {

    /** A located call site: the offset of its open paren, the `[start, end)` range of its callee name, and the
     *  zero-based index of the argument the caret is in (top-level comma count since the open paren). */
    data class CallSite(
        val openParenOffset: Int,
        val calleeNameStart: Int,
        val calleeNameEnd: Int,
        val activeParameter: Int,
    )

    private data class Frame(val open: Char, val openOffset: Int, var commas: Int, val nameStart: Int, val nameEnd: Int)

    /** The innermost call whose argument list contains [offset], or null when the caret isn't inside a `(...)`
     *  that follows an identifier. Scans `[0, offset)`. */
    fun enclosingCall(text: CharSequence, offset: Int): CallSite? {
        val end = offset.coerceIn(0, text.length)
        val stack = ArrayList<Frame>()
        var i = 0
        while (i < end) {
            val c = text[i]
            when {
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    i += 2
                    while (i < end && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i < end && !(text[i] == '*' && i + 1 < text.length && text[i + 1] == '/')) i++
                    i += 2
                }
                c == '"' && i + 2 < text.length && text[i + 1] == '"' && text[i + 2] == '"' -> {
                    i += 3
                    while (i < end && !(text[i] == '"' && i + 1 < text.length && text[i + 1] == '"' && i + 2 < text.length && text[i + 2] == '"')) i++
                    i += 3
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    i++
                    while (i < end && text[i] != quote) { if (text[i] == '\\') i++; i++ }
                    i++
                }
                c == '(' -> {
                    val nameEnd = trimTrailingWhitespace(text, i)
                    var nameStart = nameEnd
                    while (nameStart > 0 && isNameChar(text[nameStart - 1])) nameStart--
                    stack.add(Frame('(', i, 0, nameStart, nameEnd))
                    i++
                }
                c == '[' -> { stack.add(Frame('[', i, 0, i, i)); i++ }
                c == '{' -> { stack.add(Frame('{', i, 0, i, i)); i++ }
                c == ')' || c == ']' || c == '}' -> {
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    i++
                }
                c == ',' -> {
                    stack.lastOrNull()?.let { it.commas++ }
                    i++
                }
                else -> i++
            }
        }
        // The active call is the topmost still-open `(` frame whose callee name is non-empty.
        for (idx in stack.indices.reversed()) {
            val f = stack[idx]
            if (f.open == '(' && f.nameEnd > f.nameStart) {
                return CallSite(f.openOffset, f.nameStart, f.nameEnd, f.commas)
            }
            // A `[`/`{` between the caret and the nearest call means the caret is in an index/lambda/array,
            // not in the call's own argument list — stop so we don't attribute it to an outer call.
            if (f.open != '(') return null
        }
        return null
    }

    private fun trimTrailingWhitespace(text: CharSequence, parenIndex: Int): Int {
        var j = parenIndex
        while (j > 0 && text[j - 1].isWhitespace()) j--
        return j
    }

    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
}
