package dev.ide.core

import dev.ide.core.actions.EditorTextActions
import dev.ide.platform.impl.ExtensionRegistryImpl
import dev.ide.plugin.action.ActionContext
import dev.ide.plugin.action.ActionEffect
import dev.ide.plugin.action.ActionPlace
import dev.ide.plugin.action.ActionPlaces
import dev.ide.plugin.action.CaretContext
import dev.ide.plugin.action.TextEdit
import dev.ide.plugin.impl.ActionManager
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The line-level editor actions, driven through [ActionManager] the way the editor drives them: resolve for
 * a caret context, invoke by id, apply the returned edits. Pure text, so no project is needed.
 */
class EditorTextActionsTest {

    private val manager = ActionManager(ExtensionRegistryImpl().also { EditorTextActions.register(it) })

    private fun context(
        text: String,
        selStart: Int,
        selEnd: Int = selStart,
        language: String = "kotlin",
    ) = object : ActionContext {
        override val place = ActionPlaces.EDITOR
        override val projectRoot: String? = "/tmp/project"
        override val activeFilePath = "/tmp/project/Sample.kt"
        override val selectionStart = selStart
        override val selectionEnd = selEnd
        override val contextPath: String? = null
        override val caret = CaretContext(offset = selStart, languageId = language)
        override val documentText = text
    }

    /** Apply edits the way the host does: descending by offset, so earlier edits cannot shift later ones. */
    private fun apply(text: String, edits: List<TextEdit>): String {
        val sb = StringBuilder(text)
        for (e in edits.sortedByDescending { it.offset }) sb.replace(e.offset, e.offset + e.length, e.newText)
        return sb.toString()
    }

    private fun run(id: String, ctx: ActionContext): Pair<String?, List<ActionEffect>> = runBlocking {
        val result = manager.invoke(id, ctx)
        result.message to result.effects
    }

    private fun editsOf(effects: List<ActionEffect>): List<TextEdit> =
        effects.filterIsInstance<ActionEffect.ApplyEdits>().flatMap { it.edits }

    @Test
    fun commentsASelectionAtTheShallowestIndent() {
        val text = "fun f() {\n    val a = 1\n        val b = 2\n}\n"
        val from = text.indexOf("val a")
        val to = text.indexOf("val b") + 5
        val (_, effects) = run("editor.toggleComment", context(text, from, to))
        assertEquals(
            "fun f() {\n    // val a = 1\n    //     val b = 2\n}\n",
            apply(text, editsOf(effects)),
        )
    }

    @Test
    fun uncommentsWhenEveryLineIsAlreadyCommented() {
        val text = "fun f() {\n    // val a = 1\n    // val b = 2\n}\n"
        val from = text.indexOf("// val a")
        val to = text.indexOf("// val b") + 8
        val (_, effects) = run("editor.toggleComment", context(text, from, to))
        assertEquals("fun f() {\n    val a = 1\n    val b = 2\n}\n", apply(text, editsOf(effects)))
    }

    @Test
    fun aBlankLineInTheSelectionDoesNotFlipTheDirection() {
        // The blank line is neither commented nor counted, so a commented block still uncomments.
        val text = "// a\n\n// b\n"
        val (_, effects) = run("editor.toggleComment", context(text, 0, text.length))
        assertEquals("a\n\nb\n", apply(text, editsOf(effects)))
    }

    @Test
    fun toggleCommentIsHiddenForALanguageWithNoLineComment() {
        val text = "<a/>\n"
        val ids = manager.actionsFor(context(text, 0, language = "xml")).map { it.id }
        assertTrue("editor.toggleComment" !in ids, "should not offer line comments for XML, got $ids")
    }

    @Test
    fun movesALineUpAndSelectsIt() {
        val text = "one\ntwo\nthree\n"
        val caret = text.indexOf("three")
        val (_, effects) = run("editor.moveStatementUp", context(text, caret))
        assertEquals("one\nthree\ntwo\n", apply(text, editsOf(effects)))
        val select = effects.filterIsInstance<ActionEffect.Select>().single()
        assertEquals("three", "one\nthree\ntwo\n".substring(select.start, select.end))
    }

    @Test
    fun movesALineDownAndSelectsIt() {
        val text = "one\ntwo\nthree\n"
        val caret = text.indexOf("one")
        val (_, effects) = run("editor.moveStatementDown", context(text, caret))
        val moved = apply(text, editsOf(effects))
        assertEquals("two\none\nthree\n", moved)
        val select = effects.filterIsInstance<ActionEffect.Select>().single()
        assertEquals("one", moved.substring(select.start, select.end))
    }

    @Test
    fun movingPastTheFileEdgeIsANoOp() {
        val text = "one\ntwo\n"
        assertTrue(editsOf(run("editor.moveStatementUp", context(text, 0)).second).isEmpty())
        val lastLine = text.indexOf("two")
        assertTrue(editsOf(run("editor.moveStatementDown", context(text, lastLine)).second).isEmpty())
    }

    @Test
    fun sortsSelectedLinesIgnoringCaseAndIndent() {
        val text = "import c\nimport A\nimport b\n"
        val (_, effects) = run("editor.sortLines", context(text, 0, text.lastIndex))
        assertEquals("import A\nimport b\nimport c\n", apply(text, editsOf(effects)))
    }

    @Test
    fun sortIsOfferedOnlyForAMultiLineSelection() {
        val text = "b\na\n"
        assertTrue("editor.sortLines" !in manager.actionsFor(context(text, 0)).map { it.id })
        assertTrue("editor.sortLines" in manager.actionsFor(context(text, 0, text.length)).map { it.id })
    }

    @Test
    fun alreadySortedLinesReportRatherThanEdit() {
        val text = "a\nb\n"
        val (message, effects) = run("editor.sortLines", context(text, 0, text.length))
        assertTrue(editsOf(effects).isEmpty())
        assertEquals("Lines are already sorted", message)
    }

    @Test
    fun theLinesGroupNestsEveryActionAsASubmenu() {
        val text = "a\nb\n"
        val menu = manager.menuFor(context(text, 0, text.length))
        val submenu = menu.filterIsInstance<dev.ide.plugin.impl.ResolvedMenuItem.Submenu>().single()
        assertEquals("Lines", submenu.text)
        val ids = submenu.items.filterIsInstance<dev.ide.plugin.impl.ResolvedMenuItem.Action>().map { it.action.id }
        assertEquals(
            listOf(
                "editor.toggleComment",
                "editor.moveStatementUp",
                "editor.moveStatementDown",
                "editor.sortLines",
            ),
            ids,
        )
    }

    @Test
    fun theActionsAreAbsentFromAPlaceTheyDoNotTarget() {
        val ctx = object : ActionContext {
            override val place = ActionPlace("fileContext")
            override val projectRoot: String? = null
            override val activeFilePath: String? = null
            override val selectionStart: Int? = null
            override val selectionEnd: Int? = null
            override val contextPath: String? = "/tmp/project/Sample.kt"
        }
        assertTrue(manager.actionsFor(ctx).isEmpty())
    }
}

/**
 * Moving a top-level declaration into its own file: the plugin-tier action that spans files, so it is
 * where the create-plus-edit effect pair is exercised.
 */
class ExtractFileActionTest {

    private val manager = ActionManager(
        dev.ide.platform.impl.ExtensionRegistryImpl().also { dev.ide.core.actions.ExtractFileAction.register(it) },
    )

    private fun context(
        text: String,
        caret: CaretContext,
        path: String = "/p/app/src/main/kotlin/com/example/Main.kt",
    ) = object : ActionContext {
        override val place = ActionPlaces.EDITOR
        override val projectRoot: String? = "/p"
        override val activeFilePath = path
        override val selectionStart = caret.offset
        override val selectionEnd = caret.offset
        override val contextPath: String? = null
        override val caret = caret
        override val documentText = text
    }

    /** The caret sitting ON a top-level declaration spanning `[start, end)`. */
    private fun onDeclaration(offset: Int, start: Int, end: Int, kind: String = "class_decl") = CaretContext(
        offset = offset,
        languageId = "kotlin",
        nodeKind = kind,
        nodeStart = start,
        nodeEnd = end,
        ancestors = listOf(dev.ide.plugin.action.CaretAncestor("compilation_unit", 0, end)),
    )

    private fun effects(ctx: ActionContext) = runBlocking {
        manager.invoke("editor.moveToNewFile", ctx).effects
    }

    @Test
    fun writesTheDeclarationToItsOwnFileUnderTheSamePackage() {
        val text = "package com.example\n\nimport kotlin.math.PI\n\nclass Main\n\nclass Helper {\n    val x = PI\n}\n"
        val start = text.indexOf("class Helper")
        val ctx = context(text, onDeclaration(start + 6, start, text.indexOf("\n}\n") + 2))
        val created = effects(ctx).filterIsInstance<ActionEffect.CreateFile>().single()
        assertEquals("/p/app/src/main/kotlin/com/example/Helper.kt", created.path)
        assertEquals(
            "package com.example\nimport kotlin.math.PI\n\nclass Helper {\n    val x = PI\n}\n",
            created.text,
        )
    }

    @Test
    fun cutsTheDeclarationOutOfTheSourceFile() {
        val text = "package com.example\n\nclass Main\n\nclass Helper\n"
        val start = text.indexOf("class Helper")
        val ctx = context(text, onDeclaration(start + 6, start, start + "class Helper".length))
        val edit = effects(ctx).filterIsInstance<ActionEffect.ApplyEdits>().single().edits.single()
        val remaining = StringBuilder(text).replace(edit.offset, edit.offset + edit.length, edit.newText).toString()
        assertEquals("package com.example\n\nclass Main\n", remaining)
    }

    @Test
    fun opensTheNewFileAfterWritingIt() {
        val text = "package com.example\n\nclass Main\n\nclass Helper\n"
        val start = text.indexOf("class Helper")
        val ctx = context(text, onDeclaration(start + 6, start, start + "class Helper".length))
        val order = effects(ctx).map { it::class.simpleName }
        // Create before cut: a failed create must not leave the declaration deleted from both files.
        assertEquals(listOf("CreateFile", "ApplyEdits", "OpenFile"), order)
    }

    @Test
    fun theDeclarationTheFileIsNamedAfterIsNotOffered() {
        val text = "package com.example\n\nclass Main\n"
        val start = text.indexOf("class Main")
        val ctx = context(text, onDeclaration(start + 6, start, text.length - 1))
        assertTrue(manager.actionsFor(ctx).isEmpty(), "moving Main out of Main.kt would orphan the file")
    }

    @Test
    fun aMemberIsNotOfferedAsItsOwnFile() {
        val text = "package com.example\n\nclass Helper {\n    fun m() {}\n}\n"
        val fnStart = text.indexOf("fun m")
        val caret = CaretContext(
            offset = fnStart + 2,
            languageId = "kotlin",
            nodeKind = "method_decl",
            nodeStart = fnStart,
            nodeEnd = fnStart + "fun m() {}".length,
            ancestors = listOf(
                dev.ide.plugin.action.CaretAncestor("class_decl", text.indexOf("class Helper"), text.length - 1),
                dev.ide.plugin.action.CaretAncestor("compilation_unit", 0, text.length),
            ),
        )
        // The enclosing top-level class is what moves, not the method.
        val created = effects(context(text, caret)).filterIsInstance<ActionEffect.CreateFile>().single()
        assertEquals("/p/app/src/main/kotlin/com/example/Helper.kt", created.path)
        assertTrue("class Helper {" in created.text, created.text)
    }

    @Test
    fun onlyKotlinAndJavaFilesAreOffered() {
        val text = "<resources>\n    <string name=\"a\">b</string>\n</resources>\n"
        val ctx = context(text, onDeclaration(2, 0, text.length), path = "/p/app/src/main/res/values/strings.xml")
        assertTrue(manager.actionsFor(ctx).isEmpty())
    }

    @Test
    fun aJavaDeclarationKeepsSemicolonsOnItsHeader() {
        val text = "package com.example;\n\nimport java.util.List;\n\nclass Main {}\n\nclass Helper {}\n"
        val start = text.indexOf("class Helper")
        val ctx = context(
            text,
            onDeclaration(start + 6, start, start + "class Helper {}".length),
            path = "/p/app/src/main/java/com/example/Main.java",
        )
        val created = effects(ctx).filterIsInstance<ActionEffect.CreateFile>().single()
        assertEquals("/p/app/src/main/java/com/example/Helper.java", created.path)
        assertEquals(
            "package com.example;\nimport java.util.List;\n\nclass Helper {}\n",
            created.text,
        )
    }
}
