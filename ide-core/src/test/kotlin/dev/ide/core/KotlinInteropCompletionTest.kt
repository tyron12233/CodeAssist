package dev.ide.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Kotlin → Java interop through the real engine: a module's top-level `fun`/`val` compile into a `<File>Kt`
 * file-facade class and its `class` declarations into normal classes, but none of that is on the editor
 * classpath before a build. The [dev.ide.lang.kotlin.synthetic.KotlinSyntheticClassProvider] reconstructs
 * those Java-visible shapes from the parsed Kotlin source so a Java file resolves `GreetingsKt.helloWorld()`
 * and `new KtGreeter()` — the EP → provider → name-environment overlay → completion path, no build needed.
 */
class KotlinInteropCompletionTest {

    private val root = createTempDirectory("kotlin-interop")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    private fun bootstrapWithKotlin(): IdeServices {
        val s = IdeServices.bootstrapJavaDemo(root).also { services = it }
        // Drop a Kotlin file under the `core` module's source root (com.example.core).
        write(
            "core/src/main/java/com/example/core/Greetings.kt",
            """
            package com.example.core
            fun helloWorld() { println("Hello World") }
            val greeting: String = "hi"
            class KtGreeter { fun hello(): String = "hi" }
            """.trimIndent(),
        )
        s.invalidateSyntheticClasses() // the .kt was written after bootstrap; rebuild the facade overlay
        awaitIndexReady(s)
        return s
    }

    /** Block until the background index finishes its first build. The Kotlin unresolved-symbol diagnostics are
     *  suppressed in "dumb mode" (while the classpath index isn't ready) so they never false-flag a library
     *  symbol, so a diagnostic assertion is only meaningful once the index is ready. */
    private fun awaitIndexReady(s: IdeServices) {
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline && !s.indexService.status.ready) Thread.sleep(50)
    }

    private fun write(rel: String, content: String) {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content)
    }

    private fun labels(s: IdeServices, probe: Path, text: String, anchor: String): List<String> {
        val offset = text.indexOf(anchor) + anchor.length
        return s.complete(probe, text, offset).items.map { it.insertText.substringBefore('(') }
    }

    @Test
    fun facadeStaticMembersCompleteFromJava() {
        val s = bootstrapWithKotlin()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.java")
        val text = "package com.example.core;\nclass Probe { void m() { GreetingsKt.; } }"
        val items = labels(s, probe, text, "GreetingsKt.")
        assertTrue("helloWorld" in items, "top-level fun helloWorld() should be a static facade member: $items")
        assertTrue("getGreeting" in items, "top-level val greeting should expose getGreeting(): $items")
    }

    @Test
    fun kotlinClassMembersCompleteFromJava() {
        val s = bootstrapWithKotlin()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.java")
        val text = "package com.example.core;\nclass Probe { void m() { new KtGreeter().; } }"
        val items = labels(s, probe, text, "new KtGreeter().")
        assertTrue("hello" in items, "Kotlin class instance method 'hello' should complete: $items")
    }

    @Test
    fun unresolvedReferenceIsFlaggedInKotlin() {
        val s = bootstrapWithKotlin()
        val ktFile = root.resolve("core/src/main/java/com/example/core/Bad.kt")
        val text = "package com.example.core\nfun f() { val x = undefinedThing }"
        val msgs = s.analyzeDiagnostics(ktFile, text).map { it.message }
        assertTrue(msgs.any { it.contains("undefinedThing") }, "unresolved reference should be flagged: $msgs")
    }

    @Test
    fun validKotlinHasNoUnresolvedDiagnostics() {
        val s = bootstrapWithKotlin()
        val ktFile = root.resolve("core/src/main/java/com/example/core/Good.kt")
        val text = "package com.example.core\nfun f() { println(\"hi\") }"
        val msgs = s.analyzeDiagnostics(ktFile, text).map { it.message }
        assertTrue(msgs.none { it.contains("Unresolved") }, "valid Kotlin must not be flagged: $msgs")
    }

    @Test
    fun builtinMembersResolveThroughTheIndex() {
        // Kotlin built-ins (List/Int/String) come from the `kotlin.builtins` index (decoded from the stdlib's
        // `.kotlin_builtins`), NOT a live jar read. A read-only `List` must show its REAL Kotlin shape: `size`
        // is present, but the mutating `add` of java.util.List is NOT.
        val s = bootstrapWithKotlin()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.kt")
        val withSize = labels(s, probe, "package com.example.core\nfun m(xs: List<Int>) { xs.si }", "xs.si")
        assertTrue("size" in withSize, "List built-in member 'size' via the kotlin.builtins index: $withSize")
        val withAdd = labels(s, probe, "package com.example.core\nfun m(xs: List<Int>) { xs.ad }", "xs.ad")
        assertTrue(withAdd.none { it == "add" }, "read-only List must NOT expose java.util.List's add: $withAdd")
    }

    @Test
    fun typeMismatchIsFlaggedInKotlin() {
        val s = bootstrapWithKotlin()
        val ktFile = root.resolve("core/src/main/java/com/example/core/Mismatch.kt")
        val text = "package com.example.core\nval a: Int = \"\""
        val msgs = s.analyzeDiagnostics(ktFile, text).map { it.message }
        assertTrue(msgs.any { it.contains("Type mismatch") }, "val a: Int = \"\" should be flagged: $msgs")
    }
}
