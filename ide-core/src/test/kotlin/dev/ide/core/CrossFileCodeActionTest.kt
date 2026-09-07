package dev.ide.core

import dev.ide.analysis.ACTION_PROVIDER_EP
import dev.ide.analysis.ActionProvider
import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.FixContext
import dev.ide.analysis.QuickFix
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.LanguageId
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.platform.PluginId
import dev.ide.testkit.withTempDir
import dev.ide.vfs.local.LocalFileSystem
import dev.ide.vfs.VirtualFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A code action is allowed to edit more than the file it was invoked in: [QuickFix.computeEdits] returns a
 * [WorkspaceEdit] keyed by file, so the API invites it.
 *
 * The editor round trip used to drop every entry that was not the focal file, with no error and no log, so
 * such a fix half-applied. None of the built-in fixes reach past the current file, which is why nothing
 * caught it; a fix that adds an import in one file while editing another is the ordinary case for a plugin's.
 */
class CrossFileCodeActionTest {

    /** An intention offered anywhere in a `.java` file that edits the focal file AND [other]. */
    private class TwoFileProvider(private val other: VirtualFile) : ActionProvider {
        override val languages = setOf(LanguageId("java"))

        override fun actions(target: AnalysisTarget, range: TextRange): List<QuickFix> =
            listOf(object : QuickFix {
                override val title = TITLE
                override val kind = CodeActionKind.INTENTION
                override suspend fun computeEdits(ctx: FixContext) = WorkspaceEdit(
                    mapOf(
                        ctx.target.file to listOf(DocumentEdit(0, 0, "// here\n")),
                        other to listOf(DocumentEdit(0, 0, "// there\n")),
                    ),
                )
            })

        companion object {
            const val TITLE = "Touch both files"
        }
    }

    @Test
    fun anActionThatEditsAnotherFileKeepsThatEdit() = withTempDir("cross-file-action") { dir ->
        val main = dir.resolve("app/src/main/java/com/example/app/Main.java")
        val other = main.resolveSibling("Other.java")
        // Registered BEFORE the engine is built: it snapshots the action providers at construction.
        val env = ApplicationEnvironment()
        env.platform.extensions.register(
            ACTION_PROVIDER_EP,
            TwoFileProvider(LocalFileSystem(dir).fileFor(other)),
            PluginId("test.crossfile"),
        )
        IdeServices.bootstrapJavaDemo(dir, env).use { ide ->
            Files.writeString(other, "package com.example.app;\nclass Other { }\n")
            ide.events.fileCreated(other)

            val text = Files.readString(main)
            val at = text.indexOf("class ")
            val actions = ide.editorActions(main, text, at, at)
            val idx = actions.indexOfFirst { it.title == TwoFileProvider.TITLE }
            assertTrue(
                idx >= 0,
                "the test provider's action should be offered, got ${actions.map { it.title }}",
            )

            val byFile = ide.applyEditorAction(main, text, at, at, idx)
            assertEquals(
                setOf(main.toAbsolutePath().normalize(), other.toAbsolutePath().normalize()),
                byFile.keys,
                "both files the fix edits must come back; the second used to be dropped silently",
            )
            assertEquals(
                "// here\n",
                byFile.getValue(main.toAbsolutePath().normalize()).single().newText,
            )
            assertEquals(
                "// there\n",
                byFile.getValue(other.toAbsolutePath().normalize()).single().newText,
            )
        }
    }
}
