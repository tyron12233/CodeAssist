package dev.ide.lang.java

import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.completion.complete
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.java.env.JavaEnvironment
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
 * Context-awareness of Java completion: scope-gated keywords (return/break/continue only where legal, modifiers
 * only at declarations), annotation-only candidates after `@`, enum-constant candidates in a `case` label, and
 * the declaration-proximity ranking signal (locals nearer than library types) that Java previously left unset.
 */
class JavaCompletionContextTest {
    private lateinit var env: JavaEnvironment
    private lateinit var srcRoot: File
    private lateinit var analyzer: JavaSourceAnalyzer
    private lateinit var fs: LocalFileSystem

    @BeforeTest fun setUp() {
        srcRoot = Files.createTempDirectory("java-src").toFile()
        File(srcRoot, "com/foo").mkdirs()
        File(srcRoot, "com/foo/Color.java").writeText("package com.foo;\npublic enum Color { RED, GREEN, BLUE }")
        File(srcRoot, "com/foo/Box.java").writeText(
            "package com.foo;\npublic class Box { public Inner inner() { return null; } }\nclass Inner { public String value() { return \"\"; } }"
        )
        env = JavaEnvironment.create(emptyList(), listOf(srcRoot), File(System.getProperty("java.home")))
        analyzer = JavaSourceAnalyzer(env)
        fs = LocalFileSystem(srcRoot.toPath())
    }

    @AfterTest fun tearDown() { env.close(); srcRoot.deleteRecursively() }

    private class Snap(override val file: VirtualFile, override val text: CharSequence, override val version: Long = 1) : DocumentSnapshot {
        override fun length(): Int = text.length
    }

    private fun itemsAt(source: String): List<CompletionItem> = runBlocking {
        val offset = source.indexOf('|')
        require(offset >= 0) { "source must contain a | caret marker" }
        val text = source.removeRange(offset, offset + 1)
        val vf = fs.fileFor(File(srcRoot, "com/foo/Use.java").toPath())
        analyzer.complete(CompletionRequest(Snap(vf, text), offset, CompletionTrigger.Explicit), JavaLanguageBackend.LANGUAGE_ID).items
    }

    private fun labelsAt(source: String) = itemsAt(source).map { it.label }

    // ---- keyword scope-gating ----

    @Test fun returnOfferedInMethodBodyNotAtMemberPosition() {
        assertTrue("return" in labelsAt("package com.foo;\nclass U { void m() { re| } }"), "return in a method body")
        assertFalse("return" in labelsAt("package com.foo;\nclass U { re| }"), "return must not be offered at a class-member position")
    }

    @Test fun breakOnlyInLoop() {
        assertTrue("break" in labelsAt("package com.foo;\nclass U { void m() { for (int i=0;i<1;i++) { bre| } } }"), "break in a loop")
        assertFalse("break" in labelsAt("package com.foo;\nclass U { void m() { bre| } }"), "break must not be offered outside a loop/switch")
    }

    @Test fun modifiersAtMemberPositionNotInMethodBody() {
        assertTrue("static" in labelsAt("package com.foo;\nclass U { stati| }"), "modifiers at a member position")
        assertFalse("static" in labelsAt("package com.foo;\nclass U { void m() { stati| } }"), "modifiers must not be offered inside a method body")
    }

    // ---- annotation position ----

    @Test fun annotationPositionOffersOnlyAnnotationTypes() {
        val labels = labelsAt("package com.foo;\nclass U { @Dep| void m() {} }")
        assertTrue("Deprecated" in labels, "an annotation type should be offered after @; got $labels")
        // A non-annotation type must not appear at an annotation position.
        assertFalse("Color" in labels, "a non-annotation type must not appear at an annotation position")
    }

    // ---- case label ----

    @Test fun caseLabelOffersEnumConstants() {
        val src = "package com.foo;\nclass U { void m(Color c) { switch (c) { case R| } } }"
        val items = itemsAt(src)
        assertTrue("RED" in items.map { it.label }, "case on an enum should offer its constants; got ${items.map { it.label }}")
        assertTrue(items.first { it.label == "RED" }.relevance?.fitsExpectedType == true, "the enum constant should be boosted")
    }

    // ---- ranking parity (proximity signal is populated) ----

    @Test fun localCarriesNearerProximityThanType() {
        val items = itemsAt("package com.foo;\nclass U { void m() { int colorValue = 1; Color| } }")
        val local = items.firstOrNull { it.label == "colorValue" }?.relevance?.proximity
        val type = items.firstOrNull { it.label == "Color" }?.relevance?.proximity
        assertTrue(local != null && type != null, "both a local and a type should be offered")
        assertTrue(local!! < type!!, "a local ($local) must rank nearer than a type ($type)")
    }

    @Test fun memberAccessCarriesCallableWeight() {
        val items = itemsAt("package com.foo;\nclass U { void m() { \"x\".to| } }")
        // String.toString is inherited-from-Object-ish vs its own methods; own members weigh 0, Object methods 4.
        val toUpper = items.firstOrNull { it.label == "toUpperCase" }?.relevance?.callableWeight
        assertEquals(0, toUpper, "an own member of the receiver should weigh 0")
    }

    // ---- one-hop expected-type chains ----

    @Test fun offersOneHopChainToExpectedType() {
        val items = itemsAt("package com.foo;\nclass U { void m() { String s = new Box().inn| } }")
        assertTrue("inner().value()" in items.map { it.label },
            "a one-hop chain producing the expected String should be offered; got ${items.map { it.label }}")
    }

    // ---- postfix polish ----

    @Test fun throwPostfixGatedToThrowables() {
        val onThrowable = itemsAt("package com.foo;\nclass U { void m() { new RuntimeException().throw| } }").map { it.label }
        assertTrue("throw" in onThrowable, "`.throw` should be offered on a Throwable; got $onThrowable")
        val onString = itemsAt("package com.foo;\nclass U { void m() { \"x\".throw| } }").map { it.label }
        assertFalse("throw" in onString, "`.throw` must not be offered on a non-Throwable")
    }
}
