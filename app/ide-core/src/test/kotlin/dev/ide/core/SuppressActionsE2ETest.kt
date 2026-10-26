package dev.ide.core

import dev.ide.android.support.tools.AndroidSdk
import dev.ide.lang.incremental.DocumentEdit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor's "Suppress" actions end to end: a warning offers them after its fixes, applying one writes the
 * comment or annotation, and the warning is gone on the next analysis. Over the demo Android app, so a lint
 * check's suppression is `@SuppressLint` with its import.
 */
class SuppressActionsE2ETest {

    private val root = createTempDirectory("suppress-actions-e2e")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    private fun ide(): IdeServices {
        assumeTrue(AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }?.isComplete() == true, "Android SDK not installed; skipping")
        val s = IdeServices.bootstrapDemo(root).also { services = it }
        s.modules()
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline && !s.indexService.status.ready) Thread.sleep(50)
        return s
    }

    private fun write(name: String, text: String): Path {
        val file = root.resolve("app/src/main/java/com/example/app/$name")
        Files.createDirectories(file.parent)
        Files.write(file, text.toByteArray())
        return file
    }

    private fun applyEdits(text: String, edits: List<DocumentEdit>): String {
        val sb = StringBuilder(text)
        for (e in edits.sortedByDescending { it.offset }) sb.replace(e.offset, e.offset + e.oldLength, e.newText.toString())
        return sb.toString()
    }

    /** The warnings [file] has with [text] whose message starts with [prefix]. */
    private fun warnings(s: IdeServices, file: Path, text: String, prefix: String): List<String> = runBlocking {
        IdeServicesBackend(s).editor.analyze(file.toString(), text)
    }.map { it.message }.filter { it.startsWith(prefix) }

    /** Apply the action titled [title] at [caret] and return the new text. */
    private fun suppress(s: IdeServices, file: Path, text: String, caret: Int, title: String): String {
        val actions = s.editorActions(file, text, caret, caret)
        val index = actions.indexOfFirst { it.title == title }
        assertTrue(index >= 0, "no '$title' among ${actions.map { it.title }}")
        return applyEdits(text, s.run { applyEditorAction(file, text, caret, caret, index).editsFor(file) })
    }

    @Test
    fun kotlinWarningIsSuppressedForTheLineTheFunctionTheClassAndTheFile() {
        val s = ide()
        val text = "package com.example.app\n\nclass Holder {\n    fun m() {\n        val unused = 1\n    }\n}\n"
        val file = write("Holder.kt", text)
        val all = warnings(s, file, text, "")
        val message = all.firstOrNull { "unused" in it.lowercase() }
        assertTrue(message != null, "expected an unused-variable warning in $all")
        val caret = text.indexOf("unused")
        val titles = s.editorActions(file, text, caret, caret).map { it.title }.filter { it.startsWith("Suppress") }
        assertEquals(
            listOf("line", "variable", "function", "class", "file").map { "Suppress \"kt.unusedLocal\" for $it" },
            titles,
        )

        val line = suppress(s, file, text, caret, "Suppress \"kt.unusedLocal\" for line")
        assertTrue("        //noinspection kt.unusedLocal\n        val unused = 1" in line, line)
        val function = suppress(s, file, text, caret, "Suppress \"kt.unusedLocal\" for function")
        assertTrue("    @Suppress(\"kt.unusedLocal\")\n    fun m() {" in function, function)
        val file2 = suppress(s, file, text, caret, "Suppress \"kt.unusedLocal\" for file")
        assertTrue(file2.startsWith("@file:Suppress(\"kt.unusedLocal\")\n\npackage com.example.app"), file2)
        for (suppressed in listOf(line, function, file2)) {
            assertEquals(emptyList(), warnings(s, file, suppressed, message!!), suppressed)
        }

        // An annotation already on the function takes the id instead of a second one being added.
        val annotated = text.replace("    fun m() {", "    @Suppress(\"SomethingElse\")\n    fun m() {")
        assertEquals(1, warnings(s, file, annotated, message!!).size)
        val merged = suppress(s, file, annotated, annotated.indexOf("unused"), "Suppress \"kt.unusedLocal\" for function")
        assertTrue("    @Suppress(\"SomethingElse\", \"kt.unusedLocal\")\n    fun m() {" in merged, merged)
        assertEquals(emptyList(), warnings(s, file, merged, message))
    }

    @Test
    fun aLintWarningInJavaIsSuppressedWithSuppressLint() {
        val s = ide()
        val text = "package com.example.app;\n\nimport android.location.LocationManager;\n\nclass Where {\n" +
            "    Object where(LocationManager lm) {\n        return lm.getLastKnownLocation(\"gps\");\n    }\n}\n"
        val file = write("Where.java", text)
        assertEquals(1, warnings(s, file, text, "Missing permissions").size)
        val caret = text.indexOf("getLastKnownLocation")
        val fixed = suppress(s, file, text, caret, "Suppress \"MissingPermission\" for function")
        assertTrue("    @SuppressLint(\"MissingPermission\")\n    Object where(" in fixed, fixed)
        assertTrue("import android.location.LocationManager;\nimport android.annotation.SuppressLint;" in fixed, fixed)
        assertEquals(emptyList(), warnings(s, file, fixed, "Missing permissions"))
        // The line comment uses lint's id too. (The menu lists the last analysis's warnings: analyze the original.)
        assertEquals(1, warnings(s, file, text, "Missing permissions").size)
        val line = suppress(s, file, text, caret, "Suppress \"MissingPermission\" for line")
        assertTrue("        //noinspection MissingPermission\n        return lm" in line, line)
        assertEquals(emptyList(), warnings(s, file, line, "Missing permissions"))

        // Java's single value becomes an array when a second id joins it.
        val annotated = text.replace("    Object where(", "    @android.annotation.SuppressLint(\"NewApi\")\n    Object where(")
        assertEquals(1, warnings(s, file, annotated, "Missing permissions").size)
        val merged = suppress(s, file, annotated, annotated.indexOf("getLastKnownLocation"), "Suppress \"MissingPermission\" for function")
        assertTrue("@android.annotation.SuppressLint({\"NewApi\", \"MissingPermission\"})\n    Object where(" in merged, merged)
        assertEquals(emptyList(), warnings(s, file, merged, "Missing permissions"))
    }
}
