package dev.ide.core

import kotlinx.coroutines.runBlocking

import dev.ide.lang.incremental.DocumentEdit
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The `kt.suspendOverride` diagnostic + its "Add 'suspend' modifier" quick-fix end-to-end through the real
 * engine ([IdeServices.analyzeDiagnostics] → [editorActions] → [applyEditorAction]). This is the reported
 * shape: an override generated (or written) without `suspend` over a `suspend` supertype member compiled
 * nowhere and was flagged nowhere — the editor now says so and the lightbulb repairs it.
 */
class KotlinSuspendOverrideFixTest {

    private val root = createTempDirectory("kotlin-suspend-override")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    private fun applyEdits(text: String, edits: List<DocumentEdit>): String {
        val sb = StringBuilder(text)
        for (e in edits.sortedByDescending { it.offset }) sb.replace(e.offset, e.offset + e.oldLength, e.newText.toString())
        return sb.toString()
    }

    private fun awaitIndexReady(s: IdeServices) {
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline && !s.indexService.status.ready) Thread.sleep(50)
    }

    @Test
    fun suspendOverrideFixAddsTheModifier() {
        val s = IdeServices.bootstrapJavaDemo(root).also { services = it }
        val rel = "core/src/main/java/com/example/core/Loader.kt"
        val text = """
            package com.example.core
            interface Loader { suspend fun load(id: String): String }
            class MyLoader : Loader { override fun load(id: String): String = id }
        """.trimIndent()
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, text)
        s.invalidateSyntheticClasses()
        awaitIndexReady(s)

        val probe: Path = f
        val diags = runBlocking { s.analyzeDiagnostics(probe, text) }
        assertTrue(
            diags.any { it.code == "kt.suspendOverride" && it.message.contains("Non-suspend function 'load'") },
            "expected the suspend-override diagnostic; got ${diags.map { it.code to it.message }}",
        )

        val caret = text.indexOf("load(id: String): String = id")
        val actions = s.editorActions(probe, text, caret, caret)
        val idx = actions.indexOfFirst { it.title == "Add 'suspend' modifier" }
        assertTrue(idx >= 0, "expected the modifier fix; got ${actions.map { it.title }}")

        val result = applyEdits(text, s.run { applyEditorAction(probe, text, caret, caret, idx).editsFor(probe) })
        assertTrue(
            "override suspend fun load(id: String): String = id" in result,
            "the fix must add `suspend` before `fun`:\n$result",
        )
        assertTrue(
            runBlocking { s.analyzeDiagnostics(probe, result) }.none { it.code == "kt.suspendOverride" },
            "the diagnostic must clear once the fix is applied",
        )
    }
}
