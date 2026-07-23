package dev.ide.lang.java

import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Step-4 verification: member / name / type completion through the [JavaCompletion] contributor. */
class JavaCompletionTest {
    private lateinit var env: JavaEnvironment
    private lateinit var srcRoot: File
    private lateinit var analyzer: JavaSourceAnalyzer
    private lateinit var fs: LocalFileSystem

    @BeforeTest
    fun setUp() {
        srcRoot = Files.createTempDirectory("java-src").toFile()
        File(srcRoot, "com/foo").mkdirs()
        File(srcRoot, "com/foo/Greeter.java").writeText(
            """
            package com.foo;
            public class Greeter {
                public String greet(String who) { return who; }
                public int count() { return 0; }
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

    private class Snap(
        override val file: VirtualFile,
        override val text: CharSequence,
        override val version: Long = 1,
    ) : DocumentSnapshot {
        override fun length(): Int = text.length
    }

    /** The full completion items at the `|` marker in [source] (the marker char is stripped before parsing). */
    private fun itemsAt(source: String): List<CompletionItem> = runBlocking {
        val offset = source.indexOf('|')
        require(offset >= 0) { "source must contain a | caret marker" }
        val text = source.removeRange(offset, offset + 1)
        val vf = fs.fileFor(File(srcRoot, "com/foo/Use.java").toPath())
        val req = CompletionRequest(Snap(vf, text), offset, CompletionTrigger.Explicit)
        analyzer.complete(req, JavaLanguageBackend.LANGUAGE_ID).items
    }

    /** Labels at the `|` marker in [source]. */
    private fun labelsAt(source: String): List<String> = itemsAt(source).map { it.label }

    @Test
    fun memberAccessEnumeratesReceiverMembers() {
        val labels = labelsAt(
            """
            package com.foo;
            class Use { void run() { new Greeter().gr| } }
            """.trimIndent()
        )
        assertTrue("greet" in labels, "member access should offer greet(); got $labels")
    }

    @Test
    fun nameReferenceSeesLocalsAndMembers() {
        val labels = labelsAt(
            """
            package com.foo;
            class Use { void run() { int myLocal = 1; my| } }
            """.trimIndent()
        )
        assertTrue("myLocal" in labels, "name reference should offer the in-scope local; got $labels")
    }

    @Test
    fun typePositionOffersVisibleTypes() {
        val labels = labelsAt(
            """
            package com.foo;
            class Use { void run() { Str| } }
            """.trimIndent()
        )
        assertTrue("String" in labels, "a type position should offer java.lang.String; got $labels")
    }

    @Test
    fun bareNamePrefixOffersJavaLangType() {
        val labels = labelsAt(
            """
            package com.foo;
            class Use { void run() { Sys| } }
            """.trimIndent()
        )
        assertTrue("System" in labels, "`Sys` should offer java.lang.System; got ${labels.take(25)}")
    }

    @Test
    fun arrayMemberAccessOffersLengthCloneAndObjectMethods() {
        val labels = labelsAt(
            """
            package com.foo;
            class Use { void run(String[] args) { args.| } }
            """.trimIndent()
        )
        assertTrue("length" in labels, "array should offer the `length` field; got ${labels.take(25)}")
        assertTrue("clone" in labels, "array should offer clone()")
        assertTrue("equals" in labels, "array should offer inherited Object.equals")
    }

    @Test
    fun keywordsAndPrimitivesAreOffered() {
        assertTrue(
            "return" in labelsAt("package com.foo;\nclass Use { void run() { re| } }"),
            "`re` should offer the `return` keyword",
        )
        assertTrue(
            "boolean" in labelsAt("package com.foo;\nclass Use { void run() { bool| } }"),
            "`bool` should offer the `boolean` primitive",
        )
    }

    // --- smart completion: expected-type ranking + name suggestions --------------------------------------

    @Test
    fun expectedTypeBoostsAssignableNameCandidate() {
        val items = itemsAt(
            "package com.foo;\nclass Use { void run() { String name = \"x\"; int cnt = 0; String s = | } }"
        )
        val name = items.firstOrNull { it.label == "name" }
        val cnt = items.firstOrNull { it.label == "cnt" }
        assertTrue(name?.relevance?.fitsExpectedType == true, "String local should fit expected String; got ${name?.relevance}")
        assertTrue(cnt == null || cnt.relevance?.fitsExpectedType != true, "int local must not fit expected String; got ${cnt?.relevance}")
    }

    @Test
    fun expectedTypeBoostsAssignableMember() {
        val items = itemsAt("package com.foo;\nclass Use { void run() { String s = new Greeter().| } }")
        val greet = items.firstOrNull { it.label == "greet" }   // returns String
        val count = items.firstOrNull { it.label == "count" }   // returns int
        assertTrue(greet?.relevance?.fitsExpectedType == true, "String-returning member should fit; got ${greet?.relevance}")
        assertTrue(count == null || count.relevance?.fitsExpectedType != true, "int-returning member must not fit; got ${count?.relevance}")
    }

    @Test
    fun variableNamePositionSuggestsNamesFromType() {
        val labels = labelsAt("package com.foo;\nclass Use { void run() { Greeter | } }")
        assertTrue("greeter" in labels, "a declaration position should suggest a name from the type; got $labels")
    }

    // --- live + postfix templates ------------------------------------------------------------------------

    @Test
    fun liveTemplateOfferedAtStatementPosition() {
        val items = itemsAt("package com.foo;\nclass Use { void run() { sou| } }")
        val sout = items.firstOrNull { it.label == "sout" }
        assertTrue(sout != null && sout.kind == CompletionItemKind.SNIPPET, "`sou` should offer the `sout` live template; got ${items.map { it.label }}")
    }

    @Test
    fun postfixTemplateOfferedOnExpression() {
        val items = itemsAt("package com.foo;\nclass Use { void run() { String s = \"\"; s.| } }")
        val labels = items.filter { it.kind == CompletionItemKind.SNIPPET }.map { it.label }.toSet()
        assertTrue("sout" in labels, "`s.` should offer the `sout` postfix template; got $labels")
        assertTrue("nn" in labels, "a reference receiver should offer the `nn` (not-null) postfix template; got $labels")
    }

    // --- context-aware type completion (extends / implements / new / throws) + override -------------------

    @Test
    fun implementsOffersInterfacesOnly() {
        assertTrue("Runnable" in labelsAt("package com.foo;\nclass Use implements Ru| { }"), "implements should offer the Runnable interface")
        assertFalse("Thread" in labelsAt("package com.foo;\nclass Use implements Thre| { }"), "implements must not offer the Thread class")
    }

    @Test
    fun extendsOffersNonFinalClassesOnly() {
        assertTrue("Thread" in labelsAt("package com.foo;\nclass Use extends Thre| { }"), "extends should offer the Thread class")
        assertFalse("Runnable" in labelsAt("package com.foo;\nclass Use extends Ru| { }"), "extends must not offer the Runnable interface")
        assertFalse("String" in labelsAt("package com.foo;\nclass Use extends Stri| { }"), "extends must not offer the final class String")
    }

    @Test
    fun newOffersInstantiableTypesOnly() {
        assertTrue("Thread" in labelsAt("package com.foo;\nclass Use { Object o = new Thre|(); }"), "new should offer the instantiable Thread")
        assertFalse("Runnable" in labelsAt("package com.foo;\nclass Use { Object o = new Ru|(); }"), "new must not offer the abstract Runnable")
    }

    @Test
    fun throwsOffersThrowablesOnly() {
        assertTrue("RuntimeException" in labelsAt("package com.foo;\nclass Use { void m() throws Runtime| { } }"), "throws should offer a Throwable")
        assertFalse("String" in labelsAt("package com.foo;\nclass Use { void m() throws Stri| { } }"), "throws must not offer a non-Throwable")
    }

    @Test
    fun overrideCompletionAtMemberLevel() {
        val labels = labelsAt("package com.foo;\nclass Use { toStr| }")
        assertTrue("toString" in labels, "typing at a member position should offer an override of Object.toString; got $labels")
    }

    @Test
    fun newPositionOffersSubtypesFromIndex() {
        // `Shape s = new Sp<caret>` — the subtype index reports com.foo.Special (a Shape impl NOT declared in
        // this file, so only the subtype path can surface it). A stub index stands in for the host wiring.
        val src = "package com.foo;\ninterface Shape {}\nclass Use { void m() { Shape s = new Sp|(); } }"
        val offset = src.indexOf('|')
        val text = src.removeRange(offset, offset + 1)
        val vf = fs.fileFor(File(srcRoot, "com/foo/Use.java").toPath())
        val comp = dev.ide.lang.java.completion.JavaCompletion(
            env,
            subtypeSearch = { fqn ->
                if (fqn == "com.foo.Shape") listOf(dev.ide.lang.java.completion.JavaCompletion.IndexedType("com.foo.Special", "class"))
                else emptyList()
            },
        )
        val res = runBlocking {
            comp.complete(CompletionRequest(Snap(vf, text), offset, CompletionTrigger.Explicit), JavaLanguageBackend.LANGUAGE_ID)
        }
        assertTrue(res.items.any { it.label == "Special" }, "new-position should offer the indexed Shape impl 'Special'; got ${res.items.map { it.label }}")
    }

    @Test
    fun typeCompletionIsIncompleteForReQuery() {
        // Type/name completion consults the prefix-dependent, truncated index, so it must report incomplete —
        // else the editor narrows a stale list client-side and unimported types (`List`) never surface while
        // typing fast (they only appeared after a cursor move forced a re-query).
        val src = "package com.foo;\nclass Use { void m() { Lis| } }"
        val offset = src.indexOf('|')
        val text = src.removeRange(offset, offset + 1)
        val vf = fs.fileFor(File(srcRoot, "com/foo/Use.java").toPath())
        val res = runBlocking {
            analyzer.complete(CompletionRequest(Snap(vf, text), offset, CompletionTrigger.Explicit), JavaLanguageBackend.LANGUAGE_ID)
        }
        assertTrue(res.isIncomplete, "a type/name-position result must be incomplete so the editor re-queries the index per keystroke")
    }

    @Test
    fun indexBackedUnimportedTypeOffersAutoImport() {
        val src = "package com.foo;\nclass Use { void run() { Arr| } }"
        val offset = src.indexOf('|')
        val text = src.removeRange(offset, offset + 1)
        val vf = fs.fileFor(File(srcRoot, "com/foo/Use.java").toPath())
        // A JavaCompletion wired to a stub index that knows java.util.ArrayList.
        val comp = dev.ide.lang.java.completion.JavaCompletion(
            env,
            typeSearch = { listOf(dev.ide.lang.java.completion.JavaCompletion.IndexedType("java.util.ArrayList", "class")) },
        )
        val res = runBlocking {
            comp.complete(
                dev.ide.lang.completion.CompletionRequest(Snap(vf, text), offset, dev.ide.lang.completion.CompletionTrigger.Explicit),
                JavaLanguageBackend.LANGUAGE_ID,
            )
        }
        val item = res.items.firstOrNull { it.label == "ArrayList" }
        assertTrue(item != null, "index-backed ArrayList should be offered; got ${res.items.map { it.label }}")
        assertTrue(
            item.additionalEdits.any { it.newText.contains("import java.util.ArrayList;") },
            "an unimported type should carry an auto-import edit; got ${item.additionalEdits}",
        )
    }

    @Test
    fun annotationPositionOffersOnlyIndexedAnnotationTypes() {
        // `@Mark|` — an annotation NAME position. The index-backed coarse filter (TypeCtx.ANNOTATION) keeps
        // only candidates whose kind is "annotation", so a library annotation surfaces while an equally-
        // prefixed library class does not. This relies on `java.classNames` labeling a binary annotation
        // "annotation" (not the old blanket "class"); with the wrong kind the popup was empty.
        val src = "package com.foo;\n@Mark| class Use {}"
        val offset = src.indexOf('|')
        val text = src.removeRange(offset, offset + 1)
        val vf = fs.fileFor(File(srcRoot, "com/foo/Use.java").toPath())
        val comp = dev.ide.lang.java.completion.JavaCompletion(
            env,
            typeSearch = {
                listOf(
                    dev.ide.lang.java.completion.JavaCompletion.IndexedType("com.lib.Marker", "annotation"),
                    dev.ide.lang.java.completion.JavaCompletion.IndexedType("com.lib.MarkerBase", "class"),
                )
            },
        )
        val res = runBlocking {
            comp.complete(
                CompletionRequest(Snap(vf, text), offset, CompletionTrigger.Explicit),
                JavaLanguageBackend.LANGUAGE_ID,
            )
        }
        val labels = res.items.map { it.label }
        assertTrue("Marker" in labels, "a library annotation must be offered at `@…`; got $labels")
        assertFalse("MarkerBase" in labels, "a non-annotation library class must NOT be offered at `@…`; got $labels")
    }
}
