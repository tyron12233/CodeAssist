package dev.ide.core

import kotlinx.coroutines.runBlocking

import dev.ide.lang.completion.CompletionItemKind
import dev.ide.testkit.writeSource
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame
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
        root.writeSource(
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
            open class KtBase { fun baseCall(): Int = 1 }
            interface KtNamed { fun label(): String }
            class KtChild : KtBase(), KtNamed { override fun label(): String = "c" }
            abstract class KtShape { abstract fun area(): Double }
            class Names { fun all(): List<String> = emptyList(); fun byId(): Map<Int, String> = emptyMap() }
            class Clicker { fun onClick(listener: (String) -> Unit) {}; fun transform(f: (Int, Int) -> String): String = f(1, 2) }
            class Inferred {
                val title = "hi"
                val count = 3
                var enabled = true
                val ratio = 0.5
                val child = KtChild()
                fun describe() = "x"
                fun total() = 42
            }
            """.trimIndent(),
            trim = false,
        )
        // The .kt was written after bootstrap: give the module the stdlib it would have had at open (the light
        // classes name `kotlin.Unit` / `kotlin.jvm.functions.*`), and rebuild the facade overlay.
        val before = s.indexService.status.stats
        s.ensureKotlinStdlib()
        s.invalidateSyntheticClasses()
        // Adding the stdlib commits the model (more than once), and each commit re-syncs the index in the
        // background; `ready` can still read true from before a pass starts. Wait for a pass that finished after
        // this point, then for the index to stay ready and idle for a full second.
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline && s.indexService.status.stats === before) Thread.sleep(50)
        var quietSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline && System.currentTimeMillis() - quietSince < 1_000) {
            val st = s.indexService.status
            if (!st.ready || st.building) quietSince = System.currentTimeMillis()
            Thread.sleep(50)
        }
        return s
    }

    /** Block until the background index finishes its first build. The Kotlin unresolved-symbol diagnostics are
     *  suppressed in "dumb mode" (while the classpath index isn't ready) so they never false-flag a library
     *  symbol, so a diagnostic assertion is only meaningful once the index is ready. */
    private fun awaitIndexReady(s: IdeServices) {
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline && !s.indexService.status.ready) Thread.sleep(50)
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

    /** An UNSAVED Kotlin edit reaches Java code: the light classes are read from the open buffer, and the Java
     *  environment, which already resolved `KtGreeter` once, drops its cached copy when the shape changes. */
    @Test
    fun unsavedKotlinEditIsVisibleFromJava() {
        val s = bootstrapWithKotlin()
        val kt = root.resolve("core/src/main/java/com/example/core/Greetings.kt")
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.java")
        val text = "package com.example.core;\nclass Probe { void m() { new KtGreeter().; } }"
        assertTrue("hello" in labels(s, probe, text, "new KtGreeter()."))
        val edited = kt.toFile().readText()
            .replace("class KtGreeter { fun hello(): String = \"hi\" }", "class KtGreeter { fun hello(): String = \"hi\"; fun added(n: Int) {} }")
        s.updateDocument(kt, edited)
        val items = labels(s, probe, text, "new KtGreeter().")
        assertTrue("added" in items, "a method added in an unsaved Kotlin buffer should complete from Java: $items")
    }

    /** An edit inside a Kotlin function body changes no light class, so the overlay the Java environment sees is
     *  the very same map and none of its caches are dropped. A new member does change it. */
    @Test
    fun aKotlinBodyEditLeavesTheJavaOverlayAlone() {
        val s = bootstrapWithKotlin()
        val kt = root.resolve("core/src/main/java/com/example/core/Greetings.kt")
        val original = kt.toFile().readText()
        val before = s.syntheticOverlay()
        val bodyEdit = original.replace("println(\"Hello World\")", "println(\"Hello there\")")
        assertTrue(bodyEdit != original, "sanity: the body edit applies")
        s.updateDocument(kt, bodyEdit)
        assertSame(before, s.syntheticOverlay(), "a body-only edit must hand back the same overlay")
        s.updateDocument(kt, original + "\nfun another() {}\n")
        assertNotSame(before, s.syntheticOverlay(), "a new top-level fun changes the facade")
    }

    /** A saved Kotlin edit stays visible once its tab is closed (the light classes go back to reading disk). */
    @Test
    fun savedKotlinEditIsVisibleFromJavaAfterTheTabCloses() {
        val s = bootstrapWithKotlin()
        val kt = root.resolve("core/src/main/java/com/example/core/Greetings.kt")
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.java")
        val text = "package com.example.core;\nclass Probe { void m() { GreetingsKt.; } }"
        assertTrue("helloWorld" in labels(s, probe, text, "GreetingsKt."))
        s.save(kt, kt.toFile().readText() + "\nfun savedTopLevel(): Int = 1\n")
        s.documentClosed(kt)
        val items = labels(s, probe, text, "GreetingsKt.")
        assertTrue("savedTopLevel" in items, "a saved top-level fun should be on the facade: $items")
    }

    /** A Kotlin class's supertypes reach Java: members inherited from a Kotlin superclass complete on it. */
    @Test
    fun inheritedKotlinMembersCompleteFromJava() {
        val s = bootstrapWithKotlin()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.java")
        val items = labels(s, probe, "package com.example.core;\nclass Probe { void m() { new KtChild().; } }", "new KtChild().")
        assertTrue("baseCall" in items, "inherited from KtBase: $items")
        assertTrue("label" in items, "its own override: $items")
    }

    /** A Kotlin subclass is assignable to its Kotlin superclass and interface in Java, with no false error. */
    @Test
    fun kotlinSubclassIsAssignableInJava() {
        val s = bootstrapWithKotlin()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.java")
        val text = "package com.example.core;\nclass Probe { void m() { KtBase b = new KtChild(); KtNamed n = new KtChild(); } }"
        val errs = runBlocking { s.analyzeDiagnostics(probe, text) }.filter { it.severity.name == "ERROR" }.map { it.message }
        assertTrue(errs.isEmpty(), "KtChild is a KtBase and a KtNamed: $errs")
    }

    /** An abstract Kotlin class can't be instantiated from Java either. */
    @Test
    fun abstractKotlinClassIsAbstractInJava() {
        val s = bootstrapWithKotlin()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.java")
        val text = "package com.example.core;\nclass Probe { void m() { new KtShape(); } }"
        val errs = runBlocking { s.analyzeDiagnostics(probe, text) }.filter { it.severity.name == "ERROR" }.map { it.message }
        assertTrue(errs.isNotEmpty(), "KtShape is abstract: $errs")
    }

    /** Generic Kotlin signatures keep their type arguments, so Java sees the element type. */
    @Test
    fun genericKotlinReturnTypesKeepTheirArguments() {
        val s = bootstrapWithKotlin()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.java")
        val items = labels(s, probe, "package com.example.core;\nclass Probe { void m() { new Names().all().get(0).; } }", "get(0).")
        assertTrue("length" in items, "List<String>.get(0) is a String: $items")
        val text = "package com.example.core;\nclass Probe { void m() { String s = new Names().byId().get(1); for (String n : new Names().all()) {} } }"
        val errs = runBlocking { s.analyzeDiagnostics(probe, text) }.filter { it.severity.name == "ERROR" }.map { it.message }
        assertTrue(errs.isEmpty(), "Map<Integer, String>.get is a String, List<String> iterates Strings: $errs")
    }

    /** A Kotlin function-type parameter takes a Java lambda: it is a `kotlin.jvm.functions.FunctionN` to Java. */
    @Test
    fun javaLambdaFitsAKotlinFunctionTypeParameter() {
        val s = bootstrapWithKotlin()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.java")
        val text = "package com.example.core;\nclass Probe { void m() { new Clicker().onClick(s -> { s.length(); return null; }); String r = new Clicker().transform((a, b) -> \"\" + (a + b)); } }"
        val errs = runBlocking { s.analyzeDiagnostics(probe, text) }.filter { it.severity.name == "ERROR" }.map { it.message }
        assertTrue(errs.isEmpty(), "a lambda fits Function1/Function2: $errs")
    }

    /** A declaration whose type Kotlin infers from a literal or a constructor call has that type in Java, not
     *  Object: assigning it to the real type is no error. */
    @Test
    fun inferredKotlinTypesReachJava() {
        val s = bootstrapWithKotlin()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.java")
        val text = "package com.example.core;\nclass Probe { void m() { Inferred i = new Inferred(); " +
            "String t = i.getTitle(); int c = i.getCount(); boolean e = i.getEnabled(); double r = i.getRatio(); " +
            "KtChild k = i.getChild(); String d = i.describe(); int n = i.total(); } }"
        val errs = runBlocking { s.analyzeDiagnostics(probe, text) }.filter { it.severity.name == "ERROR" }.map { it.message }
        assertTrue(errs.isEmpty(), "inferred types: $errs")
    }

    /** `const val` and `lateinit var` are FIELDS to Java, `@file:JvmName` renames the file facade, and an
     *  `object`'s plain members are reached through `INSTANCE`. */
    @Test
    fun kotlinFieldShapesAndRenamedFacadeReachJava() {
        val s = bootstrapWithKotlin()
        root.writeSource(
            "core/src/main/java/com/example/core/Settings.kt",
            """
            @file:JvmName("SettingsUtil")
            package com.example.core
            const val MAX_ITEMS = 10
            fun settingsHelper(): Int = 1
            object Config { const val VERSION = "1"; fun load(): Int = 0 }
            class Holder { lateinit var name: String; companion object { const val TAG = "h" } }
            """.trimIndent(),
            trim = false,
        )
        s.invalidateSyntheticClasses()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.java")
        val text = "package com.example.core;\nclass Probe { void m() { " +
            "int a = SettingsUtil.MAX_ITEMS; int b = SettingsUtil.settingsHelper(); String v = Config.VERSION; " +
            "int c = Config.INSTANCE.load(); String t = Holder.TAG; String n = new Holder().name; } }"
        val errs = runBlocking { s.analyzeDiagnostics(probe, text) }.filter { it.severity.name == "ERROR" }.map { it.message }
        assertTrue(errs.isEmpty(), "$errs")
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

    /**
     * `map.forEach { entry -> entry.<caret> }` — the lambda parameter must be a `Map.Entry`, not the map's key
     * type. Through the index, `MutableMap` resolves its members via the JVM `java.util.Map` shape, so
     * `forEach(BiConsumer<K,V>)` is an inherited MEMBER whose SAM's first parameter is the KEY; it used to win
     * over the Kotlin `Map.forEach((Map.Entry<K,V>) -> Unit)` extension on receiver specificity (member beats
     * extension), typing `entry` as the key (String). Overload resolution now prefers the function-type
     * parameter over SAM conversion, so `entry` is a `Map.Entry` and `entry.key`/`entry.value` complete.
     */
    @Test
    fun mapForEachEntryIsMapEntryThroughIndex() {
        val s = bootstrapWithKotlin()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.kt")
        val text = "package com.example.core\nfun f() {\n  val toolbox = mutableMapOf(\"a\" to \"b\")\n  toolbox.forEach { entry -> entry. }\n}"
        val items = labels(s, probe, text, "entry.")
        assertTrue("key" in items && "value" in items, "forEach lambda param must be Map.Entry (key/value): $items")
        assertTrue("length" !in items, "entry must NOT be the String key type (no `length`): $items")
    }

    /** `map.<caret> { entry -> }` — a member access whose selector is a call with a trailing lambda must still
     *  complete the receiver's members, not fall through to type/scope names. */
    @Test
    fun mapMemberAccessWithTrailingLambdaThroughIndex() {
        val s = bootstrapWithKotlin()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.kt")
        val text = "package com.example.core\nfun f() {\n  val toolbox = mutableMapOf(\"a\" to \"b\")\n  toolbox. { entry ->\n  }\n}"
        val items = labels(s, probe, text, "toolbox.")
        assertTrue("forEach" in items, "map members must complete before a trailing lambda: ${items.take(30)}")
        assertTrue("String" !in items, "type names must not leak into member access: ${items.take(30)}")
    }

    /**
     * `StringBuilder` is a Kotlin typealias to `java.lang.StringBuilder` (`kotlin/text/TypeAliases.kt`) — no
     * `.kotlin_builtins`, no `.class` — so it used to resolve to a shapeless FQN and complete nothing. Mapped in
     * `Builtins` so its members (which chain fluently: `append(...)` returns `StringBuilder`) resolve through
     * the aliased java.lang type.
     */
    @Test
    fun stringBuilderMembersAndFluentChainResolve() {
        val s = bootstrapWithKotlin()
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.kt")
        val direct = labels(s, probe, "package com.example.core\nfun f() { StringBuilder().app }", "StringBuilder().app")
        assertTrue(direct.any { it.startsWith("append") }, "StringBuilder().append should complete: ${direct.take(20)}")
        // Fluent: append(...) returns StringBuilder, so a second .append chains.
        val chained = labels(s, probe, "package com.example.core\nfun f() { StringBuilder().append(\"x\").app }", "append(\"x\").app")
        assertTrue(chained.any { it.startsWith("append") }, "append() must return StringBuilder so it chains: ${chained.take(20)}")
        // `with(StringBuilder()) { <caret> }` — the receiver value's members are in scope.
        val withScope = labels(s, probe, "package com.example.core\nfun f() { with(StringBuilder()) { app } }", "{ app")
        assertTrue(withScope.any { it.startsWith("append") }, "with(StringBuilder()) block should see append: ${withScope.take(20)}")
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
