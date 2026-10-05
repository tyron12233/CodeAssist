package dev.codeassist.ndk.jni

/** A `Java_…` function defined in a C or C++ file: its [name] and where the name sits. */
data class JniFunction(val name: String, val nameOffset: Int)

/** What one C or C++ file contributes to JNI binding. */
data class CppJniFile(
    val functions: List<JniFunction>,
    /**
     * Whether the file registers natives itself (`RegisterNatives`), usually from `JNI_OnLoad`. Such a module
     * binds methods to functions of any name, so "no function for this native method" cannot be decided from
     * names and is not reported.
     */
    val registersNatives: Boolean,
)

/**
 * Finds the JNI functions a C or C++ file defines, from the text.
 *
 * A definition is a `Java_` identifier followed by a parameter list and a body; a declaration (`;` after the
 * list) does not implement anything and is skipped, so a header that only declares does not satisfy a
 * native method that nothing defines.
 */
object CppJniScanner {

    fun scan(text: String): CppJniFile {
        val clean = SourceText.clean(text, kotlin = false)
        val toks = SourceText.tokens(clean)
        val functions = ArrayList<JniFunction>()
        var registers = false
        for ((i, tok) in toks.withIndex()) {
            if (tok.text == "RegisterNatives") registers = true
            if (!tok.text.startsWith("Java_") || toks.getOrNull(i + 1)?.text != "(") continue
            val close = toks.matching(i + 1)
            // Skip trailing qualifiers between the parameter list and the body (`noexcept`, attributes).
            var k = close + 1
            while (k < toks.size && toks[k].text.firstOrNull()?.isLetter() == true) k++
            if (toks.getOrNull(k)?.text == "{") functions.add(JniFunction(tok.text, tok.start))
        }
        return CppJniFile(functions, registers)
    }
}
