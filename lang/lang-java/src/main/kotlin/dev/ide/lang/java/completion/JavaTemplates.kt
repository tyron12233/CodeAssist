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
import dev.ide.lang.template.indented
import dev.ide.lang.template.lineIndentAt

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

// ---- live templates (`sout`, `fori`, `psvm`, `logd`, …) --------------------------------------------------

/**
 * Java **live templates**: abbreviations that expand on their own, without a receiver. The IntelliJ and Android
 * Studio set, each offered only where it fits ([JavaPlace]): `psvm` among a class's members, `sout` at the start
 * of a statement, `St` in a type or expression, and the Android ones (`logd`, `Toast`, …) only in an Android
 * module. Some read the code around them: `logt` names the enclosing class, `soutp` and `logm` print the
 * enclosing method's parameters. Mirrors lang-jdt's `LiveTemplates`.
 */
internal object JavaLiveTemplates {

    /**
     * The templates matching [prefix] at [tokenStart] in [text], re-indented to its line. [plannedImport] makes
     * the import edit for a class a template uses, or returns null when the file already imports it.
     */
    fun itemsFor(
        text: CharSequence,
        tokenStart: Int,
        prefix: String,
        android: Boolean,
        plannedImport: (String) -> TextEdit?,
    ): List<CompletionItem> {
        if (prefix.isEmpty()) return emptyList() // don't flood the bare-caret popup; needs at least one char
        val matching = TEMPLATES.filter { it.key.startsWith(prefix, ignoreCase = true) && (android || !it.android) }
        if (matching.isEmpty()) return emptyList()
        val ctx = JavaTemplateContexts.at(text, tokenStart)
        if (ctx.place == JavaPlace.NONE) return emptyList()
        val indent = lineIndentAt(text, tokenStart)
        return matching.mapNotNull { t ->
            if (ctx.place !in t.places) return@mapNotNull null
            val expansion = t.build(ctx)?.indented(indent) ?: return@mapNotNull null
            CompletionItem(
                label = t.key,
                insertText = expansion.text,
                kind = CompletionItemKind.SNIPPET,
                detail = t.preview,
                documentation = t.description,
                sortPriority = if (t.key == prefix) -40 else 65,
                additionalEdits = t.imports.mapNotNull(plannedImport),
                caret = CaretAction.ExpandSnippet(expansion),
            )
        }
    }

    /**
     * A template: [places] it fits, whether it needs an Android module, the classes its text uses ([imports],
     * added when not already imported), and [build], which may read the [JavaTemplateContext] and returns null
     * when the context lacks what it needs (`soutp` outside a method with parameters).
     */
    private class Template(
        val key: String,
        val preview: String,
        val description: String,
        val places: Set<JavaPlace>,
        val android: Boolean = false,
        val imports: List<String> = emptyList(),
        val build: (JavaTemplateContext) -> SnippetExpansion?,
    )

    private fun fixed(key: String, preview: String, description: String, places: Set<JavaPlace>, expansion: SnippetExpansion) =
        Template(key, preview, description, places) { expansion }

    private val MEMBER = setOf(JavaPlace.CLASS_BODY)
    private val STATEMENT = setOf(JavaPlace.STATEMENT)
    private val VALUE = setOf(JavaPlace.EXPRESSION, JavaPlace.STATEMENT)
    private val TYPE = setOf(JavaPlace.CLASS_BODY, JavaPlace.STATEMENT, JavaPlace.EXPRESSION)

    private const val LOG = "android.util.Log"
    private const val TOAST = "android.widget.Toast"

    /** Android's log tags are at most 23 characters, so `logt` cuts a longer class name, as Android Studio does. */
    private const val MAX_TAG = 23

    private fun mainMethod() = snippet { text("public static void main(String[] args) {\n    "); finalHere(); text("\n}") }

    /** `Log.x(TAG, "method: |");`, as Android Studio's `logd` family writes it. */
    private fun log(level: Char) = Template(
        "log$level", "Log.$level(TAG, \"method: \");", "Log.$level with the enclosing method's name",
        STATEMENT, android = true, imports = listOf(LOG),
    ) { ctx ->
        snippet {
            text("Log.$level(TAG, \"")
            ctx.methodName?.let { text("$it: ") }
            finalHere()
            text("\");")
        }
    }

    private val TEMPLATES: List<Template> = listOf(
        // --- printing ---
        fixed("sout", "System.out.println();", "Print to standard output", STATEMENT,
            snippet { text("System.out.println("); finalHere(); text(");") }),
        fixed("souf", "System.out.printf(\"\", );", "Formatted print", STATEMENT,
            snippet { text("System.out.printf(\""); stop(1); text("\", "); finalHere(); text(");") }),
        fixed("soutv", "System.out.println(\"value = \" + value);", "Print a value with its name", STATEMENT,
            snippet { text("System.out.println(\""); stop(1, "value"); text(" = \" + "); stop(1, "value"); text(");"); finalHere() }),
        fixed("serr", "System.err.println();", "Print to standard error", STATEMENT,
            snippet { text("System.err.println("); finalHere(); text(");") }),
        Template("soutm", "System.out.println(\"Class.method\");", "Print the class and method name", STATEMENT) { ctx ->
            val method = ctx.methodName ?: return@Template null
            snippet { text("System.out.println(\"${listOfNotNull(ctx.className, method).joinToString(".")}\");"); finalHere() }
        },
        Template("soutp", "System.out.println(\"a = \" + a);", "Print the method's parameters", STATEMENT) { ctx ->
            if (ctx.methodParams.isEmpty()) return@Template null
            val parts = ctx.methodParams.mapIndexed { i, p -> (if (i == 0) "\"" else " + \", ") + "$p = \" + $p" }
            snippet { text("System.out.println(${parts.joinToString("")});"); finalHere() }
        },
        // --- declarations ---
        Template("psvm", "public static void main(String[] args) { }", "main method", MEMBER) { mainMethod() },
        Template("main", "public static void main(String[] args) { }", "main method", MEMBER) { mainMethod() },
        fixed("psf", "public static final", "public static final", MEMBER,
            snippet { text("public static final "); finalHere() }),
        fixed("psfi", "public static final int NAME = ;", "public static final int", MEMBER,
            snippet { text("public static final int "); stop(1, "NAME"); text(" = "); finalHere(); text(";") }),
        fixed("psfs", "public static final String NAME = \"\";", "public static final String", MEMBER,
            snippet { text("public static final String "); stop(1, "NAME"); text(" = \""); finalHere(); text("\";") }),
        fixed("prsf", "private static final", "private static final", MEMBER,
            snippet { text("private static final "); finalHere() }),
        fixed("St", "String", "String", TYPE, snippet { text("String"); finalHere() }),
        // --- loops and checks ---
        fixed("fori", "for (int i = 0; i < n; i++) { }", "Indexed for loop", STATEMENT,
            snippet {
                text("for (int "); stop(1, "i"); text(" = 0; "); stop(1, "i"); text(" < "); stop(2, "n"); text("; ")
                stop(1, "i"); text("++) {\n    "); finalHere(); text("\n}")
            }),
        fixed("iter", "for (var item : iterable) { }", "for-each loop", STATEMENT,
            snippet { text("for (var "); stop(1, "item"); text(" : "); stop(2, "iterable"); text(") {\n    "); finalHere(); text("\n}") }),
        fixed("itar", "for (int i = 0; i < array.length; i++) { }", "Iterate over an array", STATEMENT,
            snippet {
                text("for (int "); stop(1, "i"); text(" = 0; "); stop(1, "i"); text(" < "); stop(2, "array")
                text(".length; "); stop(1, "i"); text("++) {\n    "); stop(3, "Object"); text(" "); stop(4, "element")
                text(" = "); stop(2, "array"); text("["); stop(1, "i"); text("];\n    "); finalHere(); text("\n}")
            }),
        fixed("ritar", "for (int i = array.length - 1; i >= 0; i--) { }", "Iterate over an array in reverse", STATEMENT,
            snippet {
                text("for (int "); stop(1, "i"); text(" = "); stop(2, "array"); text(".length - 1; "); stop(1, "i")
                text(" >= 0; "); stop(1, "i"); text("--) {\n    "); stop(3, "Object"); text(" "); stop(4, "element")
                text(" = "); stop(2, "array"); text("["); stop(1, "i"); text("];\n    "); finalHere(); text("\n}")
            }),
        fixed("itli", "for (int i = 0; i < list.size(); i++) { }", "Iterate over a list", STATEMENT,
            snippet {
                text("for (int "); stop(1, "i"); text(" = 0; "); stop(1, "i"); text(" < "); stop(2, "list")
                text(".size(); "); stop(1, "i"); text("++) {\n    "); stop(3, "Object"); text(" "); stop(4, "element")
                text(" = "); stop(2, "list"); text(".get("); stop(1, "i"); text(");\n    "); finalHere(); text("\n}")
            }),
        fixed("toar", "collection.toArray(new Object[0])", "Store a collection's elements in an array", VALUE,
            snippet { stop(1, "collection"); text(".toArray(new "); stop(2, "Object"); text("[0])"); finalHere() }),
        fixed("lazy", "if (instance == null) instance = new T();", "Lazy initialization", STATEMENT,
            snippet {
                text("if ("); stop(1, "instance"); text(" == null) {\n    "); stop(1, "instance"); text(" = new ")
                stop(2, "Object"); text("("); finalHere(); text(");\n}")
            }),
        fixed("ife", "if (cond) { }", "if statement", STATEMENT,
            snippet { text("if ("); stop(1, "cond"); text(") {\n    "); finalHere(); text("\n}") }),
        fixed("ifn", "if (o == null) { }", "if null check", STATEMENT,
            snippet { text("if ("); stop(1, "o"); text(" == null) {\n    "); finalHere(); text("\n}") }),
        fixed("inn", "if (o != null) { }", "if not-null check", STATEMENT,
            snippet { text("if ("); stop(1, "o"); text(" != null) {\n    "); finalHere(); text("\n}") }),
        fixed("whilet", "while (cond) { }", "while loop", STATEMENT,
            snippet { text("while ("); stop(1, "cond"); text(") {\n    "); finalHere(); text("\n}") }),
        fixed("thr", "throw new RuntimeException();", "throw an exception", STATEMENT,
            snippet { text("throw new "); stop(1, "RuntimeException"); text("("); finalHere(); text(");") }),
        fixed("try", "try { } catch (Exception e) { }", "try/catch", STATEMENT,
            snippet { text("try {\n    "); finalHere(); text("\n} catch ("); stop(1, "Exception"); text(" "); stop(2, "e"); text(") {\n}") }),
        // --- Android ---
        Template("logt", "private static final String TAG = \"Class\";", "A log tag named after the class", MEMBER, android = true) { ctx ->
            val tag = (ctx.className ?: "TAG").take(MAX_TAG)
            snippet { text("private static final String TAG = \""); stop(1, tag); text("\";"); finalHere() }
        },
        log('d'), log('i'), log('w'), log('e'),
        Template("logm", "Log.d(TAG, \"method() called with: a = [\" + a + \"]\");", "Log the method call with its parameters",
            STATEMENT, android = true, imports = listOf(LOG)) { ctx ->
            val method = ctx.methodName ?: return@Template null
            val args = ctx.methodParams.mapIndexed { i, p -> (if (i == 0) "" else " + \", ") + "$p = [\" + $p + \"]\"" }
            val message = if (args.isEmpty()) "\"$method() called\"" else "\"$method() called with: " + args.joinToString("")
            snippet { text("Log.d(TAG, $message);"); finalHere() }
        },
        Template("Toast", "Toast.makeText(context, \"\", Toast.LENGTH_SHORT).show();", "Show a toast", STATEMENT,
            android = true, imports = listOf(TOAST)) { ctx ->
            snippet {
                text("Toast.makeText("); stop(1, ctx.className?.let { "$it.this" } ?: "context"); text(", \"")
                stop(2); text("\", Toast.LENGTH_SHORT).show();"); finalHere()
            }
        },
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
            // The rewrite lands where the receiver started, so its inner lines take that line's indent.
            val expansion = spec.expansion.indented(lineIndentAt(text, recvStart))
            val edits = buildList {
                add(deleteReceiver)
                // The import (if any) is spliced in sorted position by the planner.
                spec.importFqn?.let { fqn -> plannedImport(fqn)?.let(::add) }
            }
            out.add(
                CompletionItem(
                    label = t.key,
                    insertText = expansion.text,
                    kind = CompletionItemKind.SNIPPET,
                    detail = t.example,
                    documentation = t.description,
                    sortPriority = if (t.key == prefix) -50 else 60,
                    additionalEdits = edits,
                    caret = CaretAction.ExpandSnippet(expansion),
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
