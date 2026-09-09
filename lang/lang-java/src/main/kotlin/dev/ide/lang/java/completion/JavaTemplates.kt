package dev.ide.lang.java.completion

import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiType
import com.intellij.psi.util.InheritanceUtil
import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.TextEdit
import dev.ide.lang.dom.TextRange
import dev.ide.lang.template.ExpandedStop
import dev.ide.lang.template.SnippetExpansion

/**
 * Java postfix + statement-position live templates for the IntelliJ-PSI backend — `expr.sout` / `expr.nn` /
 * `sout` / `fori` / … expand through the ordinary completion popup as `SNIPPET` items carrying tab stops.
 * Reimplemented in-module (so `:lang-java` needn't depend on `:lang-jdt`; mirrors `JavaSyntheticSource`): the
 * template tables + receiver scanning + snippet builder are identical to the JDT version, only the type facts
 * ([Ctx]) are computed from a [PsiType] rather than an ecj binding.
 */

// ---- snippet builder (offsets relative to the inserted text) --------------------------------------------

internal fun snippet(block: SnippetBuilder.() -> Unit): SnippetExpansion = SnippetBuilder().apply(block).build()

internal class SnippetBuilder {
    private val sb = StringBuilder()
    private val stops = LinkedHashMap<Int, MutableList<TextRange>>()
    private var finalOffset = -1

    fun text(s: String) { sb.append(s) }

    /** A tab stop [index] with optional [default] placeholder; repeat an index for linked cursors. */
    fun stop(index: Int, default: String = "") {
        val start = sb.length
        sb.append(default)
        stops.getOrPut(index) { ArrayList() }.add(TextRange(start, sb.length))
    }

    /** Mark the final caret position (`$0`). */
    fun finalHere() { finalOffset = sb.length }

    fun build(): SnippetExpansion {
        val expanded = stops.toSortedMap().map { (i, ranges) -> ExpandedStop(i, ranges) }
        return SnippetExpansion(sb.toString(), expanded, if (finalOffset >= 0) finalOffset else sb.length)
    }
}

// ---- statement-position live templates (`sout`, `fori`, `psvm`, …) --------------------------------------

internal object JavaLiveTemplates {

    fun itemsFor(prefix: String): List<CompletionItem> {
        if (prefix.isEmpty()) return emptyList() // don't flood the bare-caret popup
        return TEMPLATES.filter { it.key.startsWith(prefix, ignoreCase = true) }.map { t ->
            CompletionItem(
                label = t.key,
                insertText = t.expansion.text,
                kind = CompletionItemKind.SNIPPET,
                detail = t.preview,
                documentation = t.description,
                sortPriority = if (t.key == prefix) -40 else 65,
                caret = CaretAction.ExpandSnippet(t.expansion),
            )
        }
    }

    private class Template(val key: String, val preview: String, val description: String, val expansion: SnippetExpansion)

    private val TEMPLATES: List<Template> = listOf(
        Template("sout", "System.out.println();", "Print to standard output",
            snippet { text("System.out.println("); finalHere(); text(");") }),
        Template("souf", "System.out.printf(\"\", );", "Formatted print",
            snippet { text("System.out.printf(\""); stop(1); text("\", "); finalHere(); text(");") }),
        Template("soutv", "System.out.println(value);", "Print a value",
            snippet { text("System.out.println("); stop(1, "value"); text(");"); finalHere() }),
        Template("serr", "System.err.println();", "Print to standard error",
            snippet { text("System.err.println("); finalHere(); text(");") }),
        Template("psvm", "public static void main(String[] args) { }", "main method",
            snippet { text("public static void main(String[] args) {\n    "); finalHere(); text("\n}") }),
        Template("psf", "private static final", "private static final",
            snippet { text("private static final "); finalHere() }),
        Template("psfi", "private static final int NAME = ;", "private static final int",
            snippet { text("private static final int "); stop(1, "NAME"); text(" = "); finalHere(); text(";") }),
        Template("psfs", "private static final String NAME = \"\";", "private static final String",
            snippet { text("private static final String "); stop(1, "NAME"); text(" = \""); finalHere(); text("\";") }),
        Template("fori", "for (int i = 0; i < n; i++) { }", "Indexed for loop",
            snippet {
                text("for (int "); stop(1, "i"); text(" = 0; "); stop(1, "i"); text(" < "); stop(2, "n"); text("; ")
                stop(1, "i"); text("++) {\n    "); finalHere(); text("\n}")
            }),
        Template("iter", "for (var item : iterable) { }", "for-each loop",
            snippet { text("for (var "); stop(1, "item"); text(" : "); stop(2, "iterable"); text(") {\n    "); finalHere(); text("\n}") }),
        Template("ife", "if (cond) { }", "if statement",
            snippet { text("if ("); stop(1, "cond"); text(") {\n    "); finalHere(); text("\n}") }),
        Template("ifn", "if (o == null) { }", "if null check",
            snippet { text("if ("); stop(1, "o"); text(" == null) {\n    "); finalHere(); text("\n}") }),
        Template("inn", "if (o != null) { }", "if not-null check",
            snippet { text("if ("); stop(1, "o"); text(" != null) {\n    "); finalHere(); text("\n}") }),
        Template("whilet", "while (cond) { }", "while loop",
            snippet { text("while ("); stop(1, "cond"); text(") {\n    "); finalHere(); text("\n}") }),
        Template("thr", "throw new RuntimeException();", "throw an exception",
            snippet { text("throw new "); stop(1, "RuntimeException"); text("("); finalHere(); text(");") }),
        Template("try", "try { } catch (Exception e) { }", "try/catch",
            snippet { text("try {\n    "); finalHere(); text("\n} catch ("); stop(1, "Exception"); text(" "); stop(2, "e"); text(") {\n}") }),
    )
}

// ---- postfix templates (`expr.key` rewrites the whole expression) ---------------------------------------

internal object JavaPostfixTemplates {

    /** Build postfix items for `receiver.prefix`; [keyStart]..[keyEnd] is the typed key span (the replacement
     *  range), [qualifierType] the receiver's resolved type, [staticQualifier] whether it's a type name. */
    fun itemsFor(
        text: String,
        keyStart: Int,
        prefix: String,
        qualifierType: PsiType?,
        staticQualifier: Boolean,
        /** Plans a sorted-position `import <fqn>;` edit for a template that needs one, or null when already
         *  imported / not applicable. */
        plannedImport: (String) -> TextEdit?,
    ): List<CompletionItem> {
        val dot = dotBefore(text, keyStart) ?: return emptyList()
        val recvStart = receiverStart(text, dot)
        if (recvStart >= dot) return emptyList()
        val receiver = text.substring(recvStart, dot).trim()
        if (receiver.isEmpty()) return emptyList()

        val ctx = Ctx(receiver, qualifierType, staticQualifier)
        val deleteReceiver = TextEdit(TextRange(recvStart, keyStart), "") // the key is the main replacement range
        val out = ArrayList<CompletionItem>()
        for (t in TEMPLATES) {
            if (!matches(t.key, prefix)) continue
            if (!t.applicable(ctx)) continue
            val spec = t.build(ctx) ?: continue
            val edits = buildList {
                add(deleteReceiver)
                // The import (if any) is spliced in sorted position by the planner.
                spec.importFqn?.let { fqn -> plannedImport(fqn)?.let(::add) }
            }
            out.add(
                CompletionItem(
                    label = t.key,
                    insertText = spec.expansion.text,
                    kind = CompletionItemKind.SNIPPET,
                    detail = t.example,
                    documentation = t.description,
                    sortPriority = if (t.key == prefix) -50 else 60,
                    additionalEdits = edits,
                    caret = CaretAction.ExpandSnippet(spec.expansion),
                ),
            )
        }
        return out
    }

    private fun matches(key: String, prefix: String): Boolean =
        prefix.isEmpty() || key.startsWith(prefix, ignoreCase = true)

    /** The `.` immediately before [keyStart] (skipping spaces), or null if the key isn't a member access. */
    private fun dotBefore(text: String, keyStart: Int): Int? {
        var i = keyStart - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t')) i--
        return if (i >= 0 && text[i] == '.') i else null
    }

    /** Start of the receiver expression ending just before [dot] — a backward scan over a balanced suffix. */
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
                c == '"' || c == '\'' -> {
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

    private class Ctx(val receiver: String, val type: PsiType?, val staticQualifier: Boolean) {
        val isBoolean: Boolean get() = type != null && (type.equalsToText("boolean") || type.equalsToText("java.lang.Boolean"))
        val isVoid: Boolean get() = type?.equalsToText("void") ?: false
        val isArray: Boolean get() = type is PsiArrayType
        val isInt: Boolean get() = type != null &&
            (type.equalsToText("int") || type.equalsToText("short") || type.equalsToText("byte") || type.equalsToText("long"))
        val isReference: Boolean get() = type is PsiClassType || type is PsiArrayType
        val isIterable: Boolean get() = isArray || (type is PsiClassType && InheritanceUtil.isInheritor(type, "java.lang.Iterable"))
        /** A `java.util.Collection` (has `.stream()` / `.forEach(...)` — unlike a bare array or `Iterable`). */
        val isCollection: Boolean get() = type is PsiClassType && InheritanceUtil.isInheritor(type, "java.util.Collection")
        val isThrowable: Boolean get() = type is PsiClassType && InheritanceUtil.isInheritor(type, "java.lang.Throwable")
        val typeName: String get() = type?.presentableText?.takeIf { it.isNotBlank() } ?: "var"
    }

    private fun nameFor(ctx: Ctx): String {
        val n = (ctx.type?.presentableText ?: "value").substringBefore('<').trimEnd('[', ']', ' ').substringAfterLast('.')
        return if (n.isEmpty() || !n[0].isLetter()) "value" else n[0].lowercaseChar() + n.substring(1)
    }

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
        Template("throw", "expr.throw → throw expr;", "Throw the exception", { it.isThrowable }) { c ->
            Spec(snippet { text("throw ${c.receiver};"); finalHere() })
        },
        Template("stream", "coll.stream → coll.stream()", "Open a stream over the collection", { it.isCollection }) { c ->
            Spec(snippet { text("${c.receiver}.stream()"); finalHere() })
        },
        Template("forEach", "coll.forEach → coll.forEach(item -> )", "for-each with a lambda", { it.isIterable && !it.isArray }) { c ->
            Spec(snippet { text("${c.receiver}.forEach("); stop(1, "item"); text(" -> "); finalHere(); text(")") })
        },
    )
}
