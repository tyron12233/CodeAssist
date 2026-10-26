package dev.ide.lang.jdt.completion

import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.TextEdit
import dev.ide.lang.dom.TextRange
import dev.ide.lang.template.SnippetExpansion
import dev.ide.lang.template.indented
import dev.ide.lang.template.lineIndentAt

/**
 * Java **live templates**: abbreviations that expand on their own, without a receiver. Type `sout` and accept
 * to get `System.out.println();` with the caret inside. The IntelliJ and Android Studio set, each offered only
 * where it fits ([JavaPlace]): `psvm` among a class's members, `sout` at the start of a statement, `St` in a
 * type or expression, and the Android ones (`logd`, `Toast`, …) only in an Android module. Some read the code
 * around them: `logt` names the enclosing class, `soutp` and `logm` print the enclosing method's parameters.
 * The counterpart to the postfix templates ([PostfixTemplates]); both share the snippet machinery, so accept
 * steps through any tab stops.
 */
internal object LiveTemplates {

    fun itemsFor(text: CharSequence, tokenStart: Int, prefix: String, android: Boolean): List<CompletionItem> {
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
                // Sign convention (see ItemTierWeigher): positive keeps a not-yet-typed template below real
                // symbols; a fully-typed key opts back into the symbol tier and tops.
                sortPriority = if (t.key == prefix) -40 else 65,
                additionalEdits = t.imports.mapNotNull { importEdit(text, it) },
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

    /** An `import fqn;` edit, or null when the file already imports it (singly or by its package). */
    private fun importEdit(text: CharSequence, fqn: String): TextEdit? {
        val pkg = fqn.substringBeforeLast('.')
        val s = text.toString()
        if ("import $fqn;" in s || "import $pkg.*;" in s) return null
        val lastImport = s.lastIndexOf("\nimport ").let { if (it < 0 && s.startsWith("import ")) 0 else it }
        if (lastImport >= 0) {
            val end = s.indexOf('\n', lastImport + 1).let { if (it < 0) s.length else it + 1 }
            return TextEdit(TextRange(end, end), "import $fqn;\n")
        }
        val pkgAt = if (s.startsWith("package ")) 0 else s.indexOf("\npackage ").let { if (it < 0) -1 else it + 1 }
        val pkgEnd = if (pkgAt < 0) -1 else s.indexOf(';', pkgAt)
        if (pkgEnd >= 0) return TextEdit(TextRange(pkgEnd + 1, pkgEnd + 1), "\n\nimport $fqn;")
        return TextEdit(TextRange(0, 0), "import $fqn;\n\n")
    }
}
