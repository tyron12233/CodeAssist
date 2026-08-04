package dev.ide.core

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Kotlin → Java SOURCE interop through the REAL engine (index-backed [dev.ide.lang.kotlin.symbols
 * .KotlinSymbolService], `bootstrapJavaDemo` fixture). A same-project Java class has no `.class` and no
 * `@Metadata`; its members reach a calling Kotlin file through the `java.membersByOwner` index, now carrying
 * each member's SHAPE (parameters + `static` flag + return type). This proves the whole pipeline and the
 * reported bugs:
 *  - a Java `static` method surfaces on `Type.` completion and its 1-arg call type-checks (`Test.main(...)`);
 *  - a Java edit reflects in a calling Kotlin file WHILE TYPING — before the Java file is saved/reindexed.
 *
 * The fixture's `com.example.core.StringUtils` has `static String repeat(String, int)` + `static boolean
 * isBlank(String)`; `com.example.core.Greeter` has an instance `String greet(String)`.
 */
class KotlinSeesJavaSourceTest {

    private val root = createTempDirectory("kotlin-sees-java")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    private fun bootstrap(): IdeServices {
        val s = IdeServices.bootstrapJavaDemo(root).also { services = it }
        awaitIndexReady(s)
        return s
    }

    private fun awaitIndexReady(s: IdeServices) {
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline && !s.indexService.status.ready) Thread.sleep(50)
    }

    private val probe: Path get() = root.resolve("core/src/main/java/com/example/core/Probe.kt")
    private val stringUtils: Path get() = root.resolve("core/src/main/java/com/example/core/StringUtils.java")

    private fun labels(s: IdeServices, text: String, anchor: String): List<String> {
        val offset = text.indexOf(anchor) + anchor.length
        return runBlocking { s.complete(probe, text, offset) }.items.map { it.insertText.substringBefore('(') }
    }

    @Test
    fun javaStaticMembersCompleteOnTheTypeReceiver() {
        val s = bootstrap()
        val items = labels(s, "package com.example.core\nfun f() { StringUtils. }", "StringUtils.")
        assertTrue("repeat" in items, "Java `static String repeat(String,int)` must surface on `StringUtils.`: $items")
        assertTrue("isBlank" in items, "Java `static boolean isBlank(String)` must surface on `StringUtils.`: $items")
    }

    @Test
    fun javaInstanceMemberStaysOffTheTypeReceiver() {
        val s = bootstrap()
        // `Greeter.` is a TYPE receiver: the instance method `greet` must NOT appear; on an instance it does.
        val onType = labels(s, "package com.example.core\nfun f() { Greeter. }", "Greeter.")
        assertTrue("greet" !in onType, "an instance method must not surface on the type receiver: $onType")
        val onInstance = labels(s, "package com.example.core\nfun f(g: Greeter) { g. }", "g.")
        assertTrue("greet" in onInstance, "instance method `greet` must surface on a Greeter instance: $onInstance")
    }

    @Test
    fun correctArityStaticCallIsNotFalselyFlagged() {
        val s = bootstrap()
        // The reported bug: a shapeless `repeat` (0 params) made this 2-arg call "too many arguments".
        val text = "package com.example.core\nfun f() { val r: String = StringUtils.repeat(\"x\", 3) }"
        val msgs = runBlocking { s.analyzeDiagnostics(probe, text) }.map { it.message }
        assertTrue(
            msgs.none { it.contains("Too many", ignoreCase = true) },
            "`StringUtils.repeat(\"x\", 3)` fits `repeat(String, int)` — no too-many-arguments error: $msgs",
        )
    }

    @Test
    fun tooManyArgumentsToAJavaStaticCallIsFlagged() {
        val s = bootstrap()
        val text = "package com.example.core\nfun f() { StringUtils.repeat(\"x\", 3, 4) }"
        val msgs = runBlocking { s.analyzeDiagnostics(probe, text) }.map { it.message }
        assertTrue(
            msgs.any { it.contains("Too many", ignoreCase = true) },
            "3 arguments to `repeat(String, int)` must be flagged too-many: $msgs",
        )
    }

    /** The "modifying Java reflects in a calling Kotlin file WHILE TYPING" requirement: an unsaved edit to the
     *  open `.java` buffer (a new static method) must be visible to Kotlin completion without a save/reindex. */
    @Test
    fun unsavedJavaEditIsVisibleToKotlinImmediately() {
        val s = bootstrap()
        // Before the edit, the new method does not exist.
        val before = labels(s, "package com.example.core\nfun f() { StringUtils. }", "StringUtils.")
        assertTrue("shout" !in before, "sanity: `shout` should not exist before the edit: $before")

        // Open the Java file as a live buffer and add a static method WITHOUT saving.
        val edited = """
            package com.example.core;
            public final class StringUtils {
                private StringUtils() {}
                public static String repeat(String s, int times) { return s; }
                public static boolean isBlank(String s) { return s == null; }
                public static String shout(String s) { return s; }
            }
        """.trimIndent()
        s.updateDocument(stringUtils, edited)

        val after = labels(s, "package com.example.core\nfun f() { StringUtils. }", "StringUtils.")
        assertTrue("shout" in after, "an UNSAVED Java edit must reflect in Kotlin completion at once: $after")
    }
}
