package dev.ide.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class BreadcrumbTest {

    private fun crumbAt(ide: IdeServices, code: String): List<String> {
        val app = ide.modules().first { it.name == "app" }
        val probe = ide.sourceRoots(app).first().resolve("com/example/app/Probe.java")
        val offset = code.indexOf("|CARET|")
        return ide.breadcrumbAt(probe, code.replace("|CARET|", ""), offset)
    }

    @Test
    fun breadcrumbReflectsEnclosingTypeAndMethod() {
        val dir = Files.createTempDirectory("ide-crumb")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            // caret inside a method → [type, method]
            assertEquals(
                listOf("Outer", "greet"),
                crumbAt(ide, "package com.example.app; class Outer { void greet() { int x = 0; |CARET| } }"),
            )
            // caret inside a nested type's method → [outer, inner, method]
            assertEquals(
                listOf("Outer", "Inner", "run"),
                crumbAt(ide, "package com.example.app; class Outer { class Inner { void run() { |CARET| } } }"),
            )
            // caret in the class body but outside any method → [type]
            assertEquals(
                listOf("Outer"),
                crumbAt(ide, "package com.example.app; class Outer { |CARET| void greet() {} }"),
            )
        }
        dir.toFile().deleteRecursively()
    }
}
