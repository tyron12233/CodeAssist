package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Completion inside an `import` directive. A bare `import <caret>` (before any dot) offers PACKAGE ROOTS, not
 * scope symbols / types, and accepting one adds NO auto-import edit — previously a type was offered here and,
 * on accept, injected a second `import` statement while you were literally editing an import.
 */
class KotlinImportCompletionTest {

    @Test
    fun bareImportOffersPackageRootsWithoutAnAutoImportEdit() {
        val srcDir = tempProject(
            mapOf(
                "widgets/Widget.kt" to "package demo.widgets\nclass Widget",
                "Use.kt" to "package other\n",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
        val items = runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", "package other\nimport de|") }.items

        assertTrue(items.any { it.label == "demo" }, "a source package root should be offered at a bare import; got ${items.map { it.label }}")
        assertTrue(
            items.all { it.additionalEdits.isEmpty() },
            "no candidate at an import may carry an auto-import edit; offending: ${items.filter { it.additionalEdits.isNotEmpty() }.map { it.label }}",
        )
        assertTrue(
            items.none { it.symbol?.name == "Widget" },
            "a type must not be offered at a bare import position; got ${items.map { it.label }}",
        )
    }
}
