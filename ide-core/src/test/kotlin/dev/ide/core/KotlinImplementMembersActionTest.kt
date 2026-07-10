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
 * The "missing abstract members" diagnostic + "Implement members" quick-fix end-to-end through the real
 * engine ([IdeServices.analyzeDiagnostics] → [editorActions] → [applyEditorAction]). A concrete Kotlin class
 * that leaves an inherited abstract member unimplemented is flagged `kt.abstractNotImplemented`, and the
 * lightbulb generates the `override` stub into the class body. Index-gated, so it awaits the first index build
 * (the inheritance checks resolve the supertype closure, suppressed in dumb mode like the unresolved checks).
 */
class KotlinImplementMembersActionTest {

    private val root = createTempDirectory("kotlin-implement")
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
    fun implementMembersFixInsertsOverrideStub() {
        val s = IdeServices.bootstrapJavaDemo(root).also { services = it }
        val rel = "core/src/main/java/com/example/core/Service.kt"
        val text = """
            package com.example.core
            interface Service { fun run(input: String): Int }
            class MyService : Service
        """.trimIndent()
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, text)
        s.invalidateSyntheticClasses()
        awaitIndexReady(s)

        val probe: Path = f
        val diags = runBlocking { s.analyzeDiagnostics(probe, text) }
        assertTrue(
            diags.any { it.code == "kt.abstractNotImplemented" && it.message.contains("run") },
            "expected a missing-abstract diagnostic naming `run`; got ${diags.map { it.code to it.message }}",
        )

        val caret = text.indexOf("MyService")
        val actions = s.editorActions(probe, text, caret, caret)
        val idx = actions.indexOfFirst { it.title == "Implement members" }
        assertTrue(idx >= 0, "expected an 'Implement members' action; got ${actions.map { it.title }}")

        val result = applyEdits(text, s.applyEditorAction(probe, text, caret, caret, idx))
        assertTrue(
            Regex("""override fun run\(input: String\): Int""").containsMatchIn(result),
            "the override stub for `run` was not generated:\n$result",
        )
        assertTrue("TODO(" in result, "the generated stub should have a TODO body:\n$result")
    }
}
