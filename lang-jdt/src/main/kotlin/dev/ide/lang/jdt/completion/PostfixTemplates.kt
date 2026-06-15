package dev.ide.lang.jdt.completion

import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.TextEdit
import dev.ide.lang.dom.TextRange
import dev.ide.lang.template.SnippetExpansion
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding
import org.eclipse.jdt.internal.compiler.lookup.TypeIds

/**
 * Java postfix completion templates — typing `expr.key` rewrites the whole expression. They surface in the
 * ordinary completion popup at a `MEMBER_ACCESS` position: each produces a `CompletionItem(kind = SNIPPET)`
 * whose insert text IS the rewrite, whose [CompletionItem.additionalEdits] delete the original `expr.` text,
 * and whose [CompletionItem.caret] is a [CaretAction.ExpandSnippet] carrying tab stops (so `for`'s loop
 * variable is a linked/mirrored stop, `cast`'s type a placeholder, etc.). No new editor path — it reuses the
 * completion + snippet machinery.
 *
 * Built-ins operate on ecj's internal [TypeBinding] for accuracy (the same bindings completion already
 * resolved); third-party templates can still contribute through the neutral `platform.postfixTemplate` EP.
 */
internal object PostfixTemplates {

    /**
     * Build postfix items for `receiver.prefix` at the caret. [text] is the live buffer, [keyStart]..[keyEnd]
     * the typed key span (the completion replacement range), [qualifierType] the receiver's resolved type and
     * [staticQualifier] whether the receiver is a type name (`Foo.new`). Returns empty when there's no `.`
     * receiver to rewrite.
     */
    fun itemsFor(
        text: String,
        keyStart: Int,
        keyEnd: Int,
        prefix: String,
        qualifierType: TypeBinding?,
        staticQualifier: Boolean,
        importOffset: Int,
    ): List<CompletionItem> {
        val dot = dotBefore(text, keyStart) ?: return emptyList()
        val recvStart = receiverStart(text, dot)
        if (recvStart >= dot) return emptyList()
        val receiver = text.substring(recvStart, dot).trim()
        if (receiver.isEmpty()) return emptyList()

        val ctx = Ctx(receiver, qualifierType, staticQualifier)
        // additionalEdits delete `receiver.` (the key itself is the main replacement range). The snippet
        // offsets are relative to the inserted text, which lands where `receiver` started.
        val deleteReceiver = TextEdit(TextRange(recvStart, keyStart), "")

        val out = ArrayList<CompletionItem>()
        for (t in TEMPLATES) {
            if (!matches(t.key, prefix)) continue
            if (!t.applicable(ctx)) continue
            val spec = t.build(ctx) ?: continue
            val edits = buildList {
                add(deleteReceiver)
                spec.importFqn?.let { add(TextEdit(TextRange(importOffset, importOffset), "import $it;\n")) }
            }
            out.add(
                CompletionItem(
                    label = t.key,
                    insertText = spec.expansion.text,
                    kind = CompletionItemKind.SNIPPET,
                    detail = t.example,
                    documentation = t.description,
                    // Exact key match floats to the top; otherwise rank just below real members.
                    sortPriority = if (t.key == prefix) -50 else 60,
                    additionalEdits = edits,
                    caret = CaretAction.ExpandSnippet(spec.expansion),
                ),
            )
        }
        return out
    }

    /** Loose prefix match: the typed [prefix] is a case-insensitive prefix of the template [key]. */
    private fun matches(key: String, prefix: String): Boolean =
        prefix.isEmpty() || key.startsWith(prefix, ignoreCase = true)

    // ---- receiver scanning (over the original buffer) ----

    /** The `.` immediately before [keyStart] (skipping spaces), or null if the key isn't a member access. */
    private fun dotBefore(text: String, keyStart: Int): Int? {
        var i = keyStart - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t')) i--
        return if (i >= 0 && text[i] == '.') i else null
    }

    /**
     * Start of the receiver expression ending just before [dot]. A backward scan over a balanced suffix:
     * identifiers, dots, and bracket groups (`)`/`]` ... `(`/`[`), plus string/char literals, stopping at the
     * first operator / separator / opening bracket at depth 0. Good enough for the common receivers
     * (`a.b`, `foo()`, `arr[i]`, `"x"`, `a.b().c`); a leading operator like `!x.` keeps just `x`.
     */
    private fun receiverStart(text: String, dot: Int): Int {
        var i = dot - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        var depth = 0
        while (i >= 0) {
            val c = text[i]
            when {
                c == ')' || c == ']' -> { depth++; i-- }
                c == '(' || c == '[' -> { if (depth == 0) return i + 1; depth--; i-- }
                depth > 0 -> i--
                c == '"' || c == '\'' -> { // skip back over a literal
                    val q = c; i--
                    while (i >= 0 && !(text[i] == q && (i == 0 || text[i - 1] != '\\'))) i--
                    i--
                }
                c.isLetterOrDigit() || c == '_' || c == '$' || c == '.' -> i--
                else -> return i + 1
            }
        }
        return i + 1
    }

    // ---- type predicates over ecj bindings ----

    private class Ctx(val receiver: String, val type: TypeBinding?, val staticQualifier: Boolean) {
        val isBoolean: Boolean get() = type?.let { it.id == TypeIds.T_boolean || it.id == TypeIds.T_JavaLangBoolean } ?: false
        val isPrimitive: Boolean get() = type?.isBaseType ?: false
        val isVoid: Boolean get() = type?.id == TypeIds.T_void
        val isArray: Boolean get() = type?.isArrayType ?: false
        val isInt: Boolean get() = type?.let { it.id == TypeIds.T_int || it.id == TypeIds.T_short || it.id == TypeIds.T_byte || it.id == TypeIds.T_long } ?: false
        val isReference: Boolean get() = type != null && !type.isBaseType
        val isIterable: Boolean get() = isArray || (type as? ReferenceBinding)?.let { superMatches(it, "java.lang.Iterable") } ?: false
        /** Short display of the receiver's type for a `var`/`cast` rewrite. */
        val typeName: String get() = InternalMembers.display(type)
    }

    private fun superMatches(t: ReferenceBinding, fqn: String): Boolean {
        val seen = HashSet<String>()
        fun walk(r: ReferenceBinding?): Boolean {
            if (r == null || !seen.add(String(r.readableName()))) return false
            if (String(r.readableName()) == fqn) return true
            if (walk(r.superclass())) return true
            r.superInterfaces()?.forEach { if (walk(it)) return true }
            return false
        }
        return walk(t)
    }

    /** Suggest a variable name from a type: first letter lowercased (`String` → `string`, `List` → `list`). */
    private fun nameFor(ctx: Ctx): String {
        val n = (ctx.type?.let { InternalMembers.simpleName(it) } ?: "value").substringBefore('<').trimEnd('[', ']')
        return if (n.isEmpty() || !n[0].isLetter()) "value" else n[0].lowercaseChar() + n.substring(1)
    }

    // ---- the template table ----

    private class Spec(val expansion: SnippetExpansion, val importFqn: String? = null)

    private class Template(
        val key: String,
        val example: String,
        val description: String,
        val applicable: (Ctx) -> Boolean = { true },
        val build: (Ctx) -> Spec?,
    )

    private val anyValue: (Ctx) -> Boolean = { !it.isVoid }

    private val TEMPLATES: List<Template> = listOf(
        Template("sout", "expr.sout → System.out.println(expr);", "Print to standard output", anyValue) {
            Spec(snippet { text("System.out.println(${it.receiver});"); finalHere() })
        },
        Template("serr", "expr.serr → System.err.println(expr);", "Print to standard error", anyValue) {
            Spec(snippet { text("System.err.println(${it.receiver});"); finalHere() })
        },
        Template("return", "expr.return → return expr;", "Return the expression", anyValue) {
            Spec(snippet { text("return ${it.receiver};"); finalHere() })
        },
        Template("var", "expr.var → Type name = expr;", "Introduce a local variable", anyValue) { c ->
            Spec(snippet { text("${c.typeName} "); stop(1, nameFor(c)); text(" = ${c.receiver};"); finalHere() })
        },
        Template("field", "expr.field → this.name = expr;", "Assign to a field") { c ->
            Spec(snippet { text("this."); stop(1, nameFor(c)); text(" = ${c.receiver};"); finalHere() })
        },
        Template("par", "expr.par → (expr)", "Wrap in parentheses", anyValue) {
            Spec(snippet { text("("); text(it.receiver); text(")"); finalHere() })
        },
        Template("not", "expr.not → !expr", "Negate a boolean", { it.isBoolean }) {
            Spec(snippet { text("!(${it.receiver})"); finalHere() })
        },
        Template("if", "expr.if → if (expr) { }", "Wrap in an if", { it.isBoolean }) {
            Spec(snippet { text("if (${it.receiver}) {\n    "); finalHere(); text("\n}") })
        },
        Template("else", "expr.else → if (!expr) { }", "Wrap in a negated if", { it.isBoolean }) {
            Spec(snippet { text("if (!(${it.receiver})) {\n    "); finalHere(); text("\n}") })
        },
        Template("while", "expr.while → while (expr) { }", "Wrap in a while loop", { it.isBoolean }) {
            Spec(snippet { text("while (${it.receiver}) {\n    "); finalHere(); text("\n}") })
        },
        Template("null", "expr.null → if (expr == null) { }", "Null check", { it.isReference }) {
            Spec(snippet { text("if (${it.receiver} == null) {\n    "); finalHere(); text("\n}") })
        },
        Template("nn", "expr.nn → if (expr != null) { }", "Not-null check", { it.isReference }) {
            Spec(snippet { text("if (${it.receiver} != null) {\n    "); finalHere(); text("\n}") })
        },
        Template("notnull", "expr.notnull → if (expr != null) { }", "Not-null check", { it.isReference }) {
            Spec(snippet { text("if (${it.receiver} != null) {\n    "); finalHere(); text("\n}") })
        },
        Template("for", "arr.for → for (int i = 0; i < arr.length; i++)", "Indexed loop", { it.isArray || it.isInt }) { c ->
            val bound = if (c.isArray) "${c.receiver}.length" else c.receiver
            Spec(snippet {
                text("for (int "); stop(1, "i"); text(" = 0; "); stop(1, "i"); text(" < $bound; "); stop(1, "i")
                text("++) {\n    "); finalHere(); text("\n}")
            })
        },
        Template("fori", "n.fori → for (int i = 0; i < n; i++)", "Indexed loop", { it.isArray || it.isInt }) { c ->
            val bound = if (c.isArray) "${c.receiver}.length" else c.receiver
            Spec(snippet {
                text("for (int "); stop(1, "i"); text(" = 0; "); stop(1, "i"); text(" < $bound; "); stop(1, "i")
                text("++) {\n    "); finalHere(); text("\n}")
            })
        },
        Template("iter", "coll.iter → for (var it : coll)", "Iterate (for-each)", { it.isIterable }) { c ->
            Spec(snippet { text("for (var "); stop(1, "item"); text(" : ${c.receiver}) {\n    "); finalHere(); text("\n}") })
        },
        Template("opt", "expr.opt → Optional.ofNullable(expr)", "Wrap in an Optional", { it.isReference }) {
            Spec(snippet { text("Optional.ofNullable(${it.receiver})"); finalHere() }, importFqn = "java.util.Optional")
        },
        Template("cast", "expr.cast → ((Type) expr)", "Cast the expression", anyValue) { c ->
            Spec(snippet { text("(("); stop(1, "Type"); text(") ${c.receiver})"); finalHere() })
        },
        Template("instanceof", "expr.instanceof → expr instanceof Type", "instanceof check", { it.isReference }) { c ->
            Spec(snippet { text("${c.receiver} instanceof "); stop(1, "Type"); finalHere() })
        },
        Template("new", "Type.new → new Type()", "Instantiate the type", { it.staticQualifier }) { c ->
            Spec(snippet { text("new ${c.receiver}("); finalHere(); text(")") })
        },
    )
}
