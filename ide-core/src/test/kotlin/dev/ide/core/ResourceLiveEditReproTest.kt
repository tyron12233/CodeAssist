package dev.ide.core

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A string added to `strings.xml` and SAVED must become resolvable in code (`R.string.<name>`) — for both a
 * SECOND added string as well as the first. Regression: the lang-java analyzer's IntelliJ facade cached the
 * synthetic `R` keyed on a PSI modification count that `dropPsiCaches` didn't bump, so once `R` had been
 * resolved, further saved resources were invisible in Java (the first string appeared only because it was `R`'s
 * first resolution). The Kotlin symbol service refreshes by list identity and was already correct; asserted too.
 */
class ResourceLiveEditReproTest {
    private val root = createTempDirectory("res-live")
    private var services: IdeServices? = null

    @AfterTest fun tearDown() { services?.close(); root.toFile().deleteRecursively() }

    private fun javaLabels(s: IdeServices): List<String> {
        val probe = root.resolve("app/src/main/java/com/example/app/JProbe.java")
        val text = "package com.example.app;\nclass JProbe { void m() { int x = R.string.; } }"
        val off = text.indexOf("R.string.") + "R.string.".length
        return runBlocking { s.complete(probe, text, off) }.items.map { it.insertText.substringBefore('(') }
    }

    private fun kotlinLabels(s: IdeServices): List<String> {
        val probe = root.resolve("app/src/main/kotlin/com/example/app/KProbe.kt")
        val text = "package com.example.app\nfun m() { val x = R.string. }"
        val off = text.indexOf("R.string.") + "R.string.".length
        return runBlocking { s.complete(probe, text, off) }.items.map { it.symbol?.name ?: it.label }
    }

    private fun poll(name: String, get: () -> List<String>, ms: Long = 30_000): Boolean {
        val d = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < d) { if (name in get()) return true; Thread.sleep(50) }
        return name in get()
    }

    @Test
    fun savedStringsBecomeVisibleInCodeIncludingTheSecond() {
        val s = IdeServices.bootstrapDemo(root).also { services = it }
        val strings = root.resolve("app/src/main/res/values/strings.xml")
        val original = Files.readString(strings)
        fun withExtra(vararg names: String) =
            original.replace("</resources>", names.joinToString("") { "    <string name=\"$it\">v</string>\n" } + "</resources>")

        s.save(strings, withExtra("first_added"))
        assertTrue(poll("first_added", { javaLabels(s) }), "Java: first added string should resolve in R.string")
        assertTrue(poll("first_added", { kotlinLabels(s) }), "Kotlin: first added string should resolve in R.string")

        s.save(strings, withExtra("first_added", "second_added"))
        assertTrue(poll("second_added", { javaLabels(s) }), "Java: SECOND added string should resolve (the regression)")
        assertTrue(poll("second_added", { kotlinLabels(s) }), "Kotlin: second added string should resolve")
        // …and the first must still be there.
        assertTrue("first_added" in javaLabels(s), "Java: first string must remain visible after the second save")
    }

    @Test
    fun unsavedBufferEditIsVisibleInCode() {
        // Live-in-buffer: a `<string>` typed into strings.xml but NOT yet saved must still resolve in code,
        // for both Java and Kotlin (the synthetic R reads the open editor buffer of res files).
        val s = IdeServices.bootstrapDemo(root).also { services = it }
        val strings = root.resolve("app/src/main/res/values/strings.xml")
        val original = Files.readString(strings)
        val edited = original.replace("</resources>", "    <string name=\"buffer_only\">v</string>\n</resources>")

        s.updateDocument(strings, edited) // buffer only — NO save / disk write
        assertTrue(poll("buffer_only", { javaLabels(s) }, 10_000), "Java: unsaved buffer string should resolve in R.string")
        assertTrue(poll("buffer_only", { kotlinLabels(s) }, 10_000), "Kotlin: unsaved buffer string should resolve in R.string")
    }
}
