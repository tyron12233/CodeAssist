package dev.ide.android.support.aidl

/**
 * A minimal indenting source writer for the generated Java.
 *
 * Generated AIDL code is read by people: it turns up in stack traces, in "go to definition", and in
 * debugging sessions, so the emitter tracks indentation rather than producing one long line. It knows
 * nothing about Java beyond braces; correctness of what it is handed is the generator's business.
 */
internal class JavaWriter(private val indentUnit: String = "  ") {
    private val sb = StringBuilder()
    private var depth = 0

    /** One statement or declaration, at the current indentation. A blank [text] emits an empty line. */
    fun line(text: String = "") {
        if (text.isEmpty()) sb.append('\n') else sb.append(indentUnit.repeat(depth)).append(text).append('\n')
    }

    /** Every line of [text] at the current indentation, for pre-formatted fragments such as doc comments. */
    fun lines(text: String) = text.lineSequence().forEach { line(it) }

    /** `header {` … `}` with [body] emitted one level deeper. An empty [header] emits a bare block. */
    fun block(header: String, body: () -> Unit) {
        line(if (header.isEmpty()) "{" else "$header {")
        indent(body)
        line("}")
    }

    /** [body] one level deeper, with no braces of its own, for constructs that brace themselves. */
    fun indent(body: () -> Unit) {
        depth++
        body()
        depth--
    }

    /** `else {` … `}`, following a [block] that emitted the matching `if`. */
    fun blockElse(body: () -> Unit) = block("else", body)

    /** A doc comment wrapping [doc], or nothing when it is null or blank. */
    fun doc(doc: String?) {
        if (doc.isNullOrBlank()) return
        line("/**")
        doc.lineSequence().forEach { line(" * $it") }
        line(" */")
    }

    override fun toString(): String = sb.toString()
}
