package dev.ide.core

import dev.ide.testkit.withTempDir
import kotlin.test.Test
import kotlin.test.assertTrue

class ExpandSelectionTest {

    @Test
    fun expandSelectionWalksUpTheTree() {
        withTempDir("ide-expand") { dir ->
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                val app = ide.modules().first { it.name == "app" }
                val file = ide.sourceRoots(app).first().resolve("com/example/app/Probe.java")
                val code = "package com.example.app; class Outer { void greet() { int value = compute(); } }"
                val caret = code.indexOf("value") // caret inside the local-variable name

                // Repeatedly expand, feeding each result back in — the same chained walk the editor gesture drives.
                val levels = ArrayList<String>()
                var start = caret
                var end = caret
                var guard = 0
                while (guard++ < 20) {
                    val r = ide.expandSelection(file, code, start, end) ?: break
                    // Each step must STRICTLY enclose the previous selection (grows on at least one side).
                    assertTrue(
                        r.start <= start && r.end >= end && (r.start < start || r.end > end),
                        "step ${levels.size}: $r must strictly enclose [$start,$end)",
                    )
                    start = r.start
                    end = r.end
                    levels.add(code.substring(start, end))
                }

                assertTrue(levels.size >= 2, "expected several expansion levels, got $levels")
                // An intermediate level stays inside the method (a sub-declaration node between word and class).
                assertTrue(
                    levels.any { it.contains("compute()") && !it.contains("class") },
                    "expected an expression/statement-level selection, got $levels",
                )
                // The widest level reaches the enclosing type declaration.
                assertTrue(levels.last().contains("class Outer"), "widest level should be the class, got ${levels.last()}")
            }
        }
    }

    @Test
    fun expandSelectionReturnsNullOutsideProject() {
        withTempDir("ide-expand-null") { dir ->
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                val outside = dir.resolve("NotInProject.java")
                assertTrue(ide.expandSelection(outside, "class X {}", 0, 0) == null)
            }
        }
    }
}
