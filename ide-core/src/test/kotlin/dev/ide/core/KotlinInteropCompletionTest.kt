package dev.ide.core

import kotlinx.coroutines.runBlocking

import dev.ide.lang.completion.CompletionItemKind
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
            class Box { var value: Int = 0 }
            class Repo { fun all(): List<String> = emptyList() }
            class Person(val id: Int, var label: String) { fun other(p: Person): Person = p }
            class Factory { companion object { @JvmStatic fun create(): Factory = Factory() } }
            object Consts { @JvmField val MAX: Int = 10 }
            class Greeter2 @JvmOverloads constructor(val tag: String = "world") { @JvmName("greetJava") fun greet(): String = "hi" }
            class Overloaded { @JvmOverloads fun box(a: Int, b: Int = 1): Int = a + b }
            class Multi { fun describe(): String = "x"; constructor(a: Int) {}; constructor(a: Int, b: Int) {} }
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
        return runBlocking { s.complete(probe, text, offset) }.items.map { it.insertText.substringBefore('(') }
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

    /** A Kotlin `var` property is visible to Java as BOTH `get<Name>()` and `set<Name>(…)` (the crude facade
     *  only emitted the getter). */
    @Test
    fun varPropertyExposesGetterAndSetterToJava() {
        val s = bootstrapWithKotlin()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.java")
        val items = labels(s, probe, "package com.example.core;\nclass Probe { void m() { new Box().; } }", "new Box().")
        assertTrue("getValue" in items, "var → getter: $items")
        assertTrue("setValue" in items, "var → setter (was missing before faithful mapping): $items")
    }

    /** A Kotlin method's return type maps to a real Java type, so chaining through it resolves: `Repo.all()`
     *  returns `List<String>` → `java.util.List`, whose `size`/`isEmpty` complete (the crude facade returned
     *  `Object`, so nothing chained). */
    @Test
    fun mappedReturnTypeChainsFromJava() {
        val s = bootstrapWithKotlin()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.java")
        val items = labels(s, probe, "package com.example.core;\nclass Probe { void m() { new Repo().all().; } }", "new Repo().all().")
        assertTrue("size" in items, "List<String> return must map to java.util.List (size completes): $items")
    }

    /** A Kotlin primary constructor is visible to Java (`new Person(int, String)`), and its mapped parameter
     *  types resolve — so members complete off a constructed instance. */
    @Test
    fun primaryConstructorResolvesFromJava() {
        val s = bootstrapWithKotlin()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.java")
        val text = "package com.example.core;\nclass Probe { void m() { new Person(1, \"x\").; } }"
        val items = labels(s, probe, text, "new Person(1, \"x\").")
        assertTrue("getId" in items, "constructed Person should expose getId(): $items")
        assertTrue("setLabel" in items, "Person.label is a var → setLabel: $items")
        assertTrue("other" in items, "Person.other(Person) should complete (param type resolved): $items")
    }

    /** `@JvmStatic` on a companion member surfaces it as a `static` on the OWNER class (`Factory.create()`). */
    @Test
    fun jvmStaticCompanionMemberIsStaticOnOwner() {
        val s = bootstrapWithKotlin()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.java")
        val items = labels(s, probe, "package com.example.core;\nclass Probe { void m() { Factory.; } }", "Factory.")
        assertTrue("create" in items, "@JvmStatic companion fun should be static on Factory: $items")
    }

    /** `@JvmField` exposes a property as a public field, NOT a getter. */
    @Test
    fun jvmFieldIsAFieldNotAGetter() {
        val s = bootstrapWithKotlin()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.java")
        val items = labels(s, probe, "package com.example.core;\nclass Probe { void m() { Consts.; } }", "Consts.")
        assertTrue("MAX" in items, "@JvmField MAX should be a field: $items")
        assertTrue("getMAX" !in items, "@JvmField must not also expose a getter: $items")
    }

    /** `@JvmName` renames the Java-visible method, and the original Kotlin name is not exposed. */
    @Test
    fun jvmNameRenamesTheMethod() {
        val s = bootstrapWithKotlin()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.java")
        val items = labels(s, probe, "package com.example.core;\nclass Probe { void m() { new Greeter2().; } }", "new Greeter2().")
        assertTrue("greetJava" in items, "@JvmName(\"greetJava\") should rename greet: $items")
        assertTrue("greet" !in items, "the original Kotlin name must not be exposed: $items")
    }

    /** `@JvmOverloads` generates the shorter-arity overloads, so a defaulted argument can be omitted from Java. */
    @Test
    fun jvmOverloadsGeneratesShorterArity() {
        val s = bootstrapWithKotlin()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.java")
        // box(a, b=1) → box(int) overload exists; the 1-arg call must NOT be flagged unresolved/inapplicable.
        val text = "package com.example.core;\nclass Probe { int m() { return new Overloaded().box(5); } }"
        val msgs = runBlocking { s.analyzeDiagnostics(probe, text) }.map { it.message }
        assertTrue(msgs.none { it.contains("box", ignoreCase = true) }, "1-arg box(5) must resolve via @JvmOverloads: $msgs")
    }

    /** Secondary constructors are visible to Java (`new Multi(int, int)`). */
    @Test
    fun secondaryConstructorResolvesFromJava() {
        val s = bootstrapWithKotlin()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.java")
        val items = labels(s, probe, "package com.example.core;\nclass Probe { void m() { new Multi(1, 2).; } }", "new Multi(1, 2).")
        assertTrue("describe" in items, "2-arg secondary constructor should resolve: $items")
    }

    @Test
    fun unresolvedReferenceIsFlaggedInKotlin() {
        val s = bootstrapWithKotlin()
        val ktFile = root.resolve("core/src/main/java/com/example/core/Bad.kt")
        val text = "package com.example.core\nfun f() { val x = undefinedThing }"
        val msgs = runBlocking { s.analyzeDiagnostics(ktFile, text) }.map { it.message }
        assertTrue(msgs.any { it.contains("undefinedThing") }, "unresolved reference should be flagged: $msgs")
    }

    @Test
    fun validKotlinHasNoUnresolvedDiagnostics() {
        val s = bootstrapWithKotlin()
        val ktFile = root.resolve("core/src/main/java/com/example/core/Good.kt")
        val text = "package com.example.core\nfun f() { println(\"hi\") }"
        val msgs = runBlocking { s.analyzeDiagnostics(ktFile, text) }.map { it.message }
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
    fun kotlinPostfixTemplatesSurfaceThroughTheEngine() {
        // End-to-end: live snapshot is parsed fresh → the generic PostfixContributor reconstructs the receiver,
        // resolves its type via analyzer.resolveType, and emits the Kotlin POSTFIX_TEMPLATE_EP rewrites.
        val s = bootstrapWithKotlin()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.kt")

        fun snippetLabels(text: String, anchor: String): List<String> {
            val offset = text.indexOf(anchor) + anchor.length
            return runBlocking { s.complete(probe, text, offset) }.items.filter { it.kind == CompletionItemKind.SNIPPET }.map { it.label }
        }

        // `.val` applies to any receiver and rewrites to `val name = x`.
        val valText = "package com.example.core\nfun g() { val x = 5\n x.va }"
        val valOffset = valText.indexOf("x.va") + "x.va".length
        val valItems = runBlocking { s.complete(probe, valText, valOffset) }.items
        assertTrue(
            valItems.any { it.kind == CompletionItemKind.SNIPPET && it.label == "val" && it.insertText == "val name = x" },
            "expected `.val` postfix: ${valItems.filter { it.kind == CompletionItemKind.SNIPPET }.map { it.label to it.insertText }}",
        )

        // `.if` applies on a Boolean receiver but not on an Int one (type gating through resolveType).
        assertTrue("if" in snippetLabels("package com.example.core\nfun g() { val b = true\n b.i }", "b.i"), "`.if` on Boolean")
        assertTrue("if" !in snippetLabels("package com.example.core\nfun g() { val n = 5\n n.i }", "n.i"), "no `.if` on Int")
    }

    @Test
    fun typeMismatchIsFlaggedInKotlin() {
        val s = bootstrapWithKotlin()
        val ktFile = root.resolve("core/src/main/java/com/example/core/Mismatch.kt")
        val text = "package com.example.core\nval a: Int = \"\""
        val msgs = runBlocking { s.analyzeDiagnostics(ktFile, text) }.map { it.message }
        assertTrue(msgs.any { it.contains("Type mismatch") }, "val a: Int = \"\" should be flagged: $msgs")
    }
}
