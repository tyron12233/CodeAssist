package dev.ide.lang.jdt.completion

import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.template.SnippetExpansion

/**
 * Statement-position **live templates** — abbreviations that expand on their own, without a receiver:
 * type `sout` and accept to get `System.out.println();` with the caret inside. The IntelliJ-style
 * counterpart to the postfix templates ([PostfixTemplates]); both share the snippet machinery, so accept
 * steps through any tab stops. Offered at a name/statement position ([CompletionKind.NAME_REFERENCE]).
 */
internal object LiveTemplates {

    fun itemsFor(prefix: String): List<CompletionItem> {
        if (prefix.isEmpty()) return emptyList() // don't flood the bare-caret popup; needs at least one char
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
