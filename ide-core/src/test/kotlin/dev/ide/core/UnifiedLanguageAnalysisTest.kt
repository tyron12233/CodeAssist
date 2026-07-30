package dev.ide.core

import kotlinx.coroutines.runBlocking

import dev.ide.testkit.writeSource
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves Kotlin and XML diagnostics + code-actions now flow through the ONE generic analysis engine (the
 * `*AnalysisSupport` providers contributed by lang-kotlin / lang-xml), not the old ide-core special cases.
 * The public `IdeServices.analyzeDiagnostics`/`editorActions`/`applyEditorAction` are the editor's entry
 * points; here they exercise the language-aware `targetFor` + the language-gated provider dispatch.
 */
class UnifiedLanguageAnalysisTest {

    private val root = createTempDirectory("unified-analysis")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    @Test
    fun kotlinSyntaxDiagnosticFlowsThroughTheEngine() {
        val s = IdeServices.bootstrapJavaDemo(root).also { services = it }
        // A blatant syntax error — the tolerant Kotlin parser flags it `kt.syntax`, which is NOT gated by
        // the index "dumb mode" (that only suppresses unresolved-symbol *semantic* findings), so this is
        // deterministic without awaiting the index.
        val kt = root.writeSource("core/src/main/java/com/example/core/Broken.kt", "package com.example.core\nfun broken( { }\n", trim = false)
        val text = Files.readString(kt)
        val diags = runBlocking { s.analyzeDiagnostics(kt, text) }
        assertTrue(diags.any { it.code == "kt.syntax" }, "Kotlin syntax diagnostic should reach the unified engine: ${diags.map { it.code to it.message }}")
    }

    @Test
    fun xmlMissingNamespaceDiagnosticAndFixFlowThroughTheEngine() {
        val s = IdeServices.bootstrapDemo(root).also { services = it }
        // Uses `android:` attributes with no `xmlns:android` declaration → the missing-namespace lint rule.
        // Pure detection (no resource index), so it's deterministic on a fresh workspace.
        val layout = root.writeSource("app/src/main/res/layout/unified_test.xml", "<LinearLayout android:orientation=\"vertical\"></LinearLayout>", trim = false)
        val text = Files.readString(layout)

        val diags = runBlocking { s.analyzeDiagnostics(layout, text) }
        assertTrue(diags.any { it.code == "android.missingNamespace" }, "XML lint diagnostic should reach the unified engine: ${diags.map { it.code }}")

        // The quick-fix is offered at the tag and inserts the namespace declaration.
        val caret = text.indexOf("LinearLayout")
        val actions = s.editorActions(layout, text, caret, caret)
        val idx = actions.indexOfFirst { it.title.contains("xmlns:android") }
        assertTrue(idx >= 0, "an 'Add xmlns:android' fix should be offered: ${actions.map { it.title }}")
        val edits = s.applyEditorAction(layout, text, caret, caret, idx)
        assertTrue(edits.any { it.newText.contains("xmlns:android") }, "applying the fix should insert the namespace: $edits")
    }
}
