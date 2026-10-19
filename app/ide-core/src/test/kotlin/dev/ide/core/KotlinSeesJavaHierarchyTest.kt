package dev.ide.core

import dev.ide.index.ClassNameIndex
import dev.ide.index.ClassNameValue
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What a Kotlin file sees of project Java SOURCE beyond one class's own public members: members inherited from a
 * Java superclass, constructors, package-private members from the same package, a generic class's type
 * parameters, assignability between Java source types, and nested classes. All through the real engine, with
 * the Java files saved and indexed (the path a closed Java file takes).
 */
class KotlinSeesJavaHierarchyTest {

    private val root = createTempDirectory("kotlin-sees-java-hierarchy")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    private val pkgDir: Path get() = root.resolve("core/src/main/java/com/example/core")
    private val probe: Path get() = pkgDir.resolve("Probe.kt")

    private val javaFiles = mapOf(
        "Base.java" to """
            package com.example.core;
            public class Base {
                public Base() {}
                public String baseMethod() { return ""; }
                String packageMethod() { return ""; }
            }
        """.trimIndent(),
        "Derived.java" to """
            package com.example.core;
            public class Derived extends Base implements Named {
                public Derived(String name) {}
                public int derivedMethod() { return 0; }
                public String name() { return ""; }
            }
        """.trimIndent(),
        "Named.java" to """
            package com.example.core;
            public interface Named { String name(); }
        """.trimIndent(),
        "Box.java" to """
            package com.example.core;
            public class Box<T> {
                public T get() { return null; }
                public void put(T value) {}
            }
        """.trimIndent(),
        "Outer.java" to """
            package com.example.core;
            public class Outer {
                public static class Inner { public int innerMethod() { return 0; } }
            }
        """.trimIndent(),
    )

    private fun bootstrap(): IdeServices {
        val s = IdeServices.bootstrapJavaDemo(root).also { services = it }
        awaitIndexReady(s)
        for ((name, text) in javaFiles) s.save(pkgDir.resolve(name), text)
        for (name in javaFiles.keys) s.documentClosed(pkgDir.resolve(name))
        val deadline = System.currentTimeMillis() + 60_000
        for (name in javaFiles.keys) {
            val simple = name.removeSuffix(".java")
            while (System.currentTimeMillis() < deadline &&
                s.indexService.exact<ClassNameValue>(ClassNameIndex.JAVA, simple)
                    .none { it.fqn == "com.example.core.$simple" }
            ) Thread.sleep(50)
        }
        return s
    }

    private fun awaitIndexReady(s: IdeServices) {
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline && !s.indexService.status.ready) Thread.sleep(50)
    }

    private fun labels(s: IdeServices, text: String, anchor: String): List<String> {
        val offset = text.indexOf(anchor) + anchor.length
        return runBlocking { s.complete(probe, text, offset) }.items.map { it.insertText.substringBefore('(') }
    }

    private fun errors(s: IdeServices, body: String): List<String> =
        runBlocking { s.analyzeDiagnostics(probe, "package com.example.core\n$body") }
            .filter { it.severity.name == "ERROR" }.map { it.message }

    @Test
    fun inheritedMembersComplete() {
        val s = bootstrap()
        val items = labels(s, "package com.example.core\nfun f(d: Derived) { d. }", "d.")
        assertTrue("derivedMethod" in items, "own member: $items")
        assertTrue("baseMethod" in items, "inherited from Java superclass Base: $items")
        assertTrue("name" in items, "from Java interface Named: $items")
    }

    @Test
    fun inheritedMemberCallIsNotFlagged() {
        val s = bootstrap()
        val errs = errors(s, "fun f(d: Derived) { val x: String = d.baseMethod() }")
        assertTrue(errs.isEmpty(), "Derived inherits baseMethod(): $errs")
    }

    @Test
    fun javaSubclassIsAssignableToItsJavaBase() {
        val s = bootstrap()
        val errs = errors(s, "fun take(b: Base) {}\nfun take2(n: Named) {}\nfun f(d: Derived) { take(d); take2(d) }")
        assertTrue(errs.isEmpty(), "Derived is a Base and a Named: $errs")
    }

    @Test
    fun constructorArityIsChecked() {
        val s = bootstrap()
        assertTrue(errors(s, "fun f() { Derived(\"x\") }").isEmpty(), "Derived(String) exists")
        val wrong = errors(s, "fun f() { Derived(1, 2, 3) }")
        assertTrue(wrong.isNotEmpty(), "no Derived constructor takes three arguments: $wrong")
    }

    @Test
    fun packagePrivateMemberResolvesFromTheSamePackage() {
        val s = bootstrap()
        val errs = errors(s, "fun f(b: Base) { val x: String = b.packageMethod() }")
        assertTrue(errs.isEmpty(), "a package-private Java member is visible to Kotlin in the same package: $errs")
    }

    @Test
    fun genericJavaClassBindsItsTypeArgument() {
        val s = bootstrap()
        val items = labels(s, "package com.example.core\nfun f(b: Box<String>) { b.get(). }", "b.get().")
        assertTrue("length" in items, "Box<String>.get() is a String: $items")
        val errs = errors(s, "fun f(b: Box<String>) { val n: Int = b.get().length }")
        assertTrue(errs.isEmpty(), "$errs")
    }

    @Test
    fun nestedJavaClassMembersResolve() {
        val s = bootstrap()
        val items = labels(s, "package com.example.core\nfun f(i: Outer.Inner) { i. }", "i.")
        assertTrue("innerMethod" in items, "$items")
    }

    /** An UNSAVED edit to a Java superclass reaches a Kotlin use of its Java subclass: the inherited member walk
     *  and every cache under it follow the Java source stamp. */
    @Test
    fun unsavedSuperclassEditReachesTheSubclass() {
        val s = bootstrap()
        val body = "fun f(d: Derived) { val x: Int = d.addedLater() }"
        assertTrue(errors(s, body).isNotEmpty(), "sanity: addedLater() doesn't exist yet")
        s.updateDocument(
            pkgDir.resolve("Base.java"),
            javaFiles.getValue("Base.java").replace(
                "public String baseMethod()", "public int addedLater() { return 0; }\n    public String baseMethod()",
            ),
        )
        val errs = errors(s, body)
        assertTrue(errs.isEmpty(), "Base now declares addedLater(), and Derived inherits it: $errs")
    }
}
