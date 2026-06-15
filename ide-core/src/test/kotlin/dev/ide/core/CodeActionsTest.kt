package dev.ide.core

import dev.ide.lang.incremental.DocumentEdit
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The unified editor-actions API end-to-end through [IdeServices.editorActions] / [applyEditorAction]:
 * a caret intention ([IntroduceVariableActionProvider], [SurroundWithTryCatchActionProvider]) and a
 * diagnostic-keyed quick-fix ([RemoveUnusedImportQuickFixProvider]). Uses the pure-Java demo (no SDK
 * needed) — the syntactic DOM the intentions walk is always available, and the unused-import warning is a
 * syntactic analyzer. ("Add import" depends on the live class-name index, so it is exercised in the app.)
 */
class CodeActionsTest {

    private fun demo(block: (IdeServices, Path) -> Unit) {
        val dir = Files.createTempDirectory("ide-actions")
        IdeServices.bootstrapJavaDemo(dir).use { block(it, dir.resolve("app/src/main/java/com/example/app/Main.java")) }
        dir.toFile().deleteRecursively()
    }

    /** Apply DocumentEdits descending-by-offset (the editor's contract), so multi-edit actions don't drift. */
    private fun applyEdits(text: String, edits: List<DocumentEdit>): String {
        val sb = StringBuilder(text)
        for (e in edits.sortedByDescending { it.offset }) sb.replace(e.offset, e.offset + e.oldLength, e.newText.toString())
        return sb.toString()
    }

    @Test
    fun introduceLocalVariableUsesTheResolvedType() = demo { ide, main ->
        val text = Files.readString(main)
        val caret = text.indexOf("formatter.format(") + "formatter.".length + 1 // inside the `format` call
        val actions = ide.editorActions(main, text, caret, caret)
        val idx = actions.indexOfFirst { it.title.startsWith("Introduce local variable") }
        assertTrue(idx >= 0, "expected an introduce-variable action, got ${actions.map { it.title }}")
        val result = applyEdits(text, ide.applyEditorAction(main, text, caret, caret, idx))
        // `format` returns String — the declaration must name that type (never `var`, which is invalid pre-Java 10).
        assertFalse("var " in result, "should use the resolved type, not `var`:\n$result")
        assertTrue(
            Regex("""String \w+ = formatter\.format\("World"\);""").containsMatchIn(result),
            "expected a `String <name> = formatter.format(\"World\");` declaration:\n$result",
        )
    }

    @Test
    fun introduceLocalVariableImportsTheResolvedType() = demo { ide, main ->
        // An expression whose type lives in another package + carries generics: `java.util.Arrays.asList(..)`
        // → `List<String>`. The intention must render `List<String>` and add `import java.util.List;`.
        val text = Files.readString(main).replace(
            "System.out.println(formatter.banner(\"CodeAssist\"));",
            "System.out.println(java.util.Arrays.asList(\"a\"));",
        )
        val caret = text.indexOf("asList(") + 1
        val actions = ide.editorActions(main, text, caret, caret)
        val idx = actions.indexOfFirst { it.title.startsWith("Introduce local variable") }
        assertTrue(idx >= 0, "expected an introduce-variable action, got ${actions.map { it.title }}")
        val result = applyEdits(text, ide.applyEditorAction(main, text, caret, caret, idx))
        assertFalse("var " in result, "should use the resolved type, not `var`:\n$result")
        assertTrue(
            Regex("""List<String> \w+ = java\.util\.Arrays\.asList\("a"\);""").containsMatchIn(result),
            "expected a typed generic declaration:\n$result",
        )
        assertTrue("import java.util.List;" in result, "the List import was not added:\n$result")
    }

    @Test
    fun surroundWithTryCatchWrapsTheStatement() = demo { ide, main ->
        val text = Files.readString(main)
        val caret = text.indexOf("System.out.println(formatter.format")
        val actions = ide.editorActions(main, text, caret, caret)
        val idx = actions.indexOfFirst { it.title == "Surround with try/catch" }
        assertTrue(idx >= 0, "expected surround-with-try/catch, got ${actions.map { it.title }}")
        val result = applyEdits(text, ide.applyEditorAction(main, text, caret, caret, idx))
        assertTrue("try {" in result && "catch (Exception e)" in result, "the statement was not wrapped:\n$result")
    }

    @Test
    fun removeUnusedImportDeletesTheLine() = demo { ide, main ->
        val text = Files.readString(main).replace(
            "import com.example.util.Formatter;",
            "import com.example.util.Formatter;\nimport java.util.List;",
        )
        ide.analyzeDiagnostics(main, text) // publish the unused-import warning so the quick-fix attaches
        val at = text.indexOf("import java.util.List;")
        val actions = ide.editorActions(main, text, at, at)
        val idx = actions.indexOfFirst { it.title == "Remove unused import" }
        assertTrue(idx >= 0, "expected remove-unused-import, got ${actions.map { it.title }}")
        val result = applyEdits(text, ide.applyEditorAction(main, text, at, at, idx))
        assertFalse("java.util.List" in result, "the unused import was not removed:\n$result")
    }
}
