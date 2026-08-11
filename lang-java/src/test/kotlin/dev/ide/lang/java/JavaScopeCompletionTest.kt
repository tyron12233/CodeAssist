package dev.ide.lang.java

import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.completion.complete
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.testkit.TestDocument
import dev.ide.vfs.VirtualFile
import dev.ide.vfs.local.LocalFileSystem
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Bare-name completion has to see every declaration form Java lets you write, not just parameters and
 * top-of-block locals: inherited members (the `findViewById`-inside-an-Activity case), `for` / `foreach` /
 * `catch` / try-with-resources variables, lambda parameters, `instanceof` pattern variables, type parameters
 * and static imports all used to be missing from [dev.ide.lang.java.resolve.JavaScope].
 */
class JavaScopeCompletionTest {
    private lateinit var env: JavaEnvironment
    private lateinit var srcRoot: File
    private lateinit var analyzer: JavaSourceAnalyzer
    private lateinit var fs: LocalFileSystem

    @BeforeTest
    fun setUp() {
        srcRoot = Files.createTempDirectory("java-scope").toFile()
        File(srcRoot, "com/foo").mkdirs()
        File(srcRoot, "com/foo/Base.java").writeText(
            """
            package com.foo;
            public class Base {
                protected int baseField = 1;
                private int hiddenField = 2;
                public void baseMethod() {}
                public static void baseStatic() {}
            }
            """.trimIndent()
        )
        env = JavaEnvironment.create(emptyList(), listOf(srcRoot), File(System.getProperty("java.home")))
        analyzer = JavaSourceAnalyzer(env)
        fs = LocalFileSystem(srcRoot.toPath())
    }

    @AfterTest
    fun tearDown() {
        env.close()
        srcRoot.deleteRecursively()
    }

    private class Snap(file: VirtualFile, text: CharSequence, version: Long = 1) :
        DocumentSnapshot by TestDocument(text, file, version)

    private fun labelsAt(source: String): List<String> = runBlocking {
        val offset = source.indexOf('|')
        require(offset >= 0) { "source must contain a | caret marker" }
        val text = source.removeRange(offset, offset + 1)
        val vf = fs.fileFor(File(srcRoot, "com/foo/Use.java").toPath())
        analyzer.complete(CompletionRequest(Snap(vf, text), offset, CompletionTrigger.Explicit), JavaLanguageBackend.LANGUAGE_ID)
            .items.map { it.label }
    }

    private fun assertOffers(name: String, source: String) {
        val labels = labelsAt(source)
        assertTrue(name in labels, "bare-name completion should offer `$name`; got $labels")
    }

    @Test
    fun offersInheritedMembers() {
        assertOffers("baseMethod", "package com.foo;\nclass Use extends Base { void run() { base| } }")
        assertOffers("baseField", "package com.foo;\nclass Use extends Base { void run() { base| } }")
        assertOffers("baseStatic", "package com.foo;\nclass Use extends Base { void run() { base| } }")
    }

    @Test
    fun hidesInaccessibleSupertypeMembers() {
        val labels = labelsAt("package com.foo;\nclass Use extends Base { void run() { hidden| } }")
        assertFalse("hiddenField" in labels, "a supertype's private field is not visible here; got $labels")
    }

    @Test
    fun staticContextHidesInstanceMembers() {
        val labels = labelsAt("package com.foo;\nclass Use extends Base { static void run() { base| } }")
        assertTrue("baseStatic" in labels, "a static method should still offer static members; got $labels")
        assertFalse("baseMethod" in labels, "an instance method is not nameable from a static context; got $labels")
        assertFalse("baseField" in labels, "an instance field is not nameable from a static context; got $labels")
    }

    @Test
    fun offersLoopAndCatchAndResourceVariables() {
        assertOffers("idx", "package com.foo;\nclass Use { void run() { for (int idx = 0; idx < 3; idx++) { id| } } }")
        assertOffers("item", "package com.foo;\nclass Use { void run(String[] a) { for (String item : a) { ite| } } }")
        assertOffers("ex", "package com.foo;\nclass Use { void run() { try { } catch (Exception ex) { e| } } }")
        assertOffers(
            "res",
            "package com.foo;\nclass Use { void run() throws Exception { try (java.io.Reader res = null) { re| } } }",
        )
    }

    @Test
    fun offersLambdaParameter() {
        assertOffers(
            "value",
            "package com.foo;\nclass Use { void run() { java.util.function.Consumer<String> c = value -> { val| }; } }",
        )
    }

    @Test
    fun offersInstanceofPatternVariable() {
        assertOffers("str", "package com.foo;\nclass Use { void run(Object o) { if (o instanceof String str) { st| } } }")
    }

    @Test
    fun patternVariableIsNotOfferedOutsideItsBranch() {
        val labels = labelsAt(
            "package com.foo;\nclass Use { void run(Object o) { if (o instanceof String str) { } else { st| } } }"
        )
        assertFalse("str" in labels, "a pattern variable is not in scope in the else-branch; got $labels")
    }

    @Test
    fun offersStaticImportedMember() {
        assertOffers("asList", "package com.foo;\nimport static java.util.Arrays.asList;\nclass Use { void run() { asL| } }")
    }

    @Test
    fun offersTypeParameter() {
        assertOffers("T", "package com.foo;\nclass Use<T> { void run() { T| } }")
        assertOffers("R", "package com.foo;\nclass Use { <R> R run() { R| } }")
    }

    @Test
    fun inheritedMemberIsOfferedOnce() {
        // `allMethods`/`allFields` report one row per level of the hierarchy for an override; only the
        // most-derived declaration is reachable, so completion must collapse them.
        File(srcRoot, "com/foo/Mid.java").writeText(
            "package com.foo;\npublic class Mid extends Base { public void baseMethod() {} }"
        )
        val labels = labelsAt("package com.foo;\nclass Use extends Mid { void run() { baseMe| } }")
        assertEquals(1, labels.count { it == "baseMethod" }, "an override must not appear once per hierarchy level; got $labels")
    }
}
