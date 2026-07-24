package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    @Test
    fun selectingAPackageAppendsADotToContinue() {
        val srcDir = tempProject(
            mapOf(
                "widgets/Widget.kt" to "package demo.widgets\nclass Widget",
                "Use.kt" to "package other\n",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
        val items = runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", "package other\nimport de|") }.items
        val demo = items.firstOrNull { it.label == "demo" }
        assertNotNull(demo, "the demo package should be offered; got ${items.map { it.label }}")
        assertEquals("demo.", demo.insertText, "selecting a package must append '.' so the next segment continues")
    }

    @Test
    fun topLevelDeclarationsAreImportableAfterAPackageDot() {
        // The reported case: `fun Test()` (a top-level function) in `com.tyron.test` was NOT offered at
        // `import com.tyron.test.<caret>` — package-member completion listed only types. Top-level functions AND
        // properties declared in the package are importable by name, so both must appear.
        val srcDir = tempProject(
            mapOf(
                "test/Api.kt" to "package com.tyron.test\nfun Test() {}\nval flag = true",
                "Use.kt" to "package other\n",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
        val items = runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", "package other\nimport com.tyron.test.|") }.items
        assertTrue(items.any { it.symbol?.name == "Test" }, "a top-level function must be importable; got ${items.map { it.label }}")
        assertTrue(items.any { it.symbol?.name == "flag" }, "a top-level property must be importable; got ${items.map { it.label }}")
    }
}
