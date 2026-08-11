package dev.ide.core

import dev.ide.lang.incremental.DocumentEdit
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The "package does not match file location" inspection + its "Set package to '…'" quick-fix, end-to-end
 * through the real engine ([IdeServices.analyzeDiagnostics] → [editorActions] → [applyEditorAction]) for both
 * Java and Kotlin. A file whose `package` directive disagrees with its directory under the source root is
 * flagged `package.mismatch`, and the fix rewrites the directive to the expected package.
 */
class PackageMismatchInspectionTest {

    private val root = createTempDirectory("package-mismatch")
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

    private fun write(s: IdeServices, rel: String, text: String): Path {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, text)
        return f
    }

    @Test
    fun flagsAndFixesAMismatchedKotlinPackage() {
        val s = IdeServices.bootstrapJavaDemo(root).also { services = it }
        // Sits in .../com/example/core, so the directory expects `com.example.core`, not `com.example.wrong`.
        val text = "package com.example.wrong\n\nclass Widget\n"
        val f = write(s, "core/src/main/java/com/example/core/Widget.kt", text)

        val diags = runBlocking { s.analyzeDiagnostics(f, text) }
        val mismatch = diags.firstOrNull { it.code == "package.mismatch" }
        assertTrue(mismatch != null, "expected a package.mismatch diagnostic; got ${diags.map { it.code to it.message }}")
        assertTrue("com.example.core" in mismatch.message, "message should name the expected package: ${mismatch.message}")

        val caret = text.indexOf("com.example.wrong")
        val actions = s.editorActions(f, text, caret, caret)
        val idx = actions.indexOfFirst { it.title == "Set package to 'com.example.core'" }
        assertTrue(idx >= 0, "expected a 'Set package' action; got ${actions.map { it.title }}")

        val fixed = applyEdits(text, s.applyEditorAction(f, text, caret, caret, idx))
        assertTrue("package com.example.core" in fixed, "the package should be corrected:\n$fixed")
        assertFalse("com.example.wrong" in fixed, "the wrong package should be gone:\n$fixed")
        assertTrue("class Widget" in fixed, "the rest of the file is untouched:\n$fixed")
    }

    @Test
    fun flagsAndFixesAMismatchedJavaPackage() {
        val s = IdeServices.bootstrapJavaDemo(root).also { services = it }
        val text = "package com.example.wrong;\n\npublic class Gadget {}\n"
        val f = write(s, "core/src/main/java/com/example/core/Gadget.java", text)

        val diags = runBlocking { s.analyzeDiagnostics(f, text) }
        assertTrue(diags.any { it.code == "package.mismatch" }, "expected a package.mismatch diagnostic; got ${diags.map { it.code }}")

        val caret = text.indexOf("com.example.wrong")
        val actions = s.editorActions(f, text, caret, caret)
        val idx = actions.indexOfFirst { it.title == "Set package to 'com.example.core'" }
        assertTrue(idx >= 0, "expected a 'Set package' action; got ${actions.map { it.title }}")

        val fixed = applyEdits(text, s.applyEditorAction(f, text, caret, caret, idx))
        assertTrue("package com.example.core;" in fixed, "the package should be corrected (with semicolon):\n$fixed")
        assertFalse("com.example.wrong" in fixed, "the wrong package should be gone:\n$fixed")
    }

    @Test
    fun cleanWhenPackageMatchesDirectory() {
        val s = IdeServices.bootstrapJavaDemo(root).also { services = it }
        val ktText = "package com.example.core\n\nclass Ok\n"
        val kt = write(s, "core/src/main/java/com/example/core/Ok.kt", ktText)
        val javaText = "package com.example.core;\n\npublic class OkJava {}\n"
        val java = write(s, "core/src/main/java/com/example/core/OkJava.java", javaText)

        val ktDiags = runBlocking { s.analyzeDiagnostics(kt, ktText) }
        val javaDiags = runBlocking { s.analyzeDiagnostics(java, javaText) }
        assertFalse(ktDiags.any { it.code == "package.mismatch" }, "a matching Kotlin package must not be flagged: ${ktDiags.map { it.code }}")
        assertFalse(javaDiags.any { it.code == "package.mismatch" }, "a matching Java package must not be flagged: ${javaDiags.map { it.code }}")
    }

    @Test
    fun theInspectionIsRegisteredAndToggleable() {
        val s = IdeServices.bootstrapJavaDemo(root).also { services = it }
        val listed = s.registeredAnalyzers().firstOrNull { it.id.value == "packageMismatch" }
        assertTrue(listed != null, "the inspection should appear in the analyzer catalogue")
        assertEquals("Package does not match file location", listed.displayName)
    }
}
