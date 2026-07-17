package dev.ide.lang.java

import dev.ide.lang.dom.TextRange
import dev.ide.lang.folding.FoldKind
import dev.ide.lang.highlight.HighlightKind
import dev.ide.lang.highlight.HighlightModifier
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.lang.signature.SignatureHelpRequest
import dev.ide.lang.signature.SignatureHelpTrigger
import dev.ide.vfs.VirtualFile
import dev.ide.vfs.local.LocalFileSystem
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Step-5 verification: folding, semantic highlight, signature help, inlay hints. */
class JavaServicesTest {
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
            public class Greeter { public String greet(String who) { return who; } }
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

    private fun writeFile(content: String): VirtualFile {
        val f = File(srcRoot, "com/foo/Use.java")
        f.writeText(content)
        return fs.fileFor(f.toPath())
    }

    private class Snap(override val file: VirtualFile, override val text: CharSequence) : DocumentSnapshot {
        override val version: Long = 1
        override fun length(): Int = text.length
    }

    @Test
    fun foldsImportsAndMethodBodies() {
        val vf = writeFile(
            """
            package com.foo;
            import java.util.List;
            import java.util.Map;
            class Use {
                void run() {
                    int x = 1;
                }
            }
            """.trimIndent()
        )
        val kinds = runBlocking { analyzer.folding!!.folds(vf) }.map { it.kind }
        assertTrue(FoldKind.IMPORTS in kinds, "should fold the import group; got $kinds")
        assertTrue(FoldKind.FUNCTION_BODY in kinds, "should fold the method body; got $kinds")
    }

    @Test
    fun highlightsFieldsMethodsAndLocals() {
        val vf = writeFile(
            """
            package com.foo;
            class Use {
                int counter;
                void run() { int local = counter; }
            }
            """.trimIndent()
        )
        val kinds = runBlocking { analyzer.semanticHighlighter!!.highlight(vf) }.map { it.kind }.toSet()
        assertTrue(HighlightKind.FIELD in kinds, "field `counter` should classify as FIELD; got $kinds")
        assertTrue(HighlightKind.METHOD in kinds, "method `run` should classify as METHOD; got $kinds")
        assertTrue(HighlightKind.LOCAL_VARIABLE in kinds, "`local` should classify as LOCAL_VARIABLE; got $kinds")
    }

    @Test
    fun highlightUsesTheLiveBufferNotDisk() {
        // On disk: one form. Live editor buffer: the same code shifted down by blank lines. The highlighter
        // must classify against the LIVE parse the host pushed through the incremental parser, so the token
        // sits at the live offset (regression: it used to read the on-disk content-hashed parse → stale colors).
        val vf = writeFile("package com.foo;\nclass Use { int counter; }")
        val live = "package com.foo;\n\n\n\nclass Use { int counter; }"
        analyzer.incrementalParser.parseFull(Snap(vf, live))
        val tokens = runBlocking { analyzer.semanticHighlighter!!.highlight(vf) }
        val expected = live.indexOf("counter")
        assertTrue(
            tokens.any { it.range.start == expected && it.kind == HighlightKind.FIELD },
            "the FIELD token must sit at the LIVE offset $expected; got ${tokens.map { it.kind.id to it.range.start }}",
        )
    }

    @Test
    fun mutableAndReadonlyVariablesAreMarked() {
        val vf = writeFile("package com.foo;\nclass Use { void run() { int m = 1; final int r = 2; } }")
        val localMods = runBlocking { analyzer.semanticHighlighter!!.highlight(vf) }
            .filter { it.kind == HighlightKind.LOCAL_VARIABLE }.map { it.modifiers }
        assertTrue(localMods.any { HighlightModifier.MUTABLE in it }, "a non-final local should carry MUTABLE; got $localMods")
        assertTrue(localMods.any { HighlightModifier.READONLY in it }, "a final local should carry READONLY; got $localMods")
    }

    @Test
    fun signatureHelpResolvesTheCall() {
        val src = "package com.foo; class Use { void run() { new Greeter().greet(|); } }"
        val offset = src.indexOf('|')
        val vf = writeFile("com/foo/Use.java placeholder") // name only; signature is text-driven
        val help = runBlocking {
            analyzer.signatureHelp!!.signatureHelp(
                SignatureHelpRequest(Snap(vf, src.removeRange(offset, offset + 1)), offset, SignatureHelpTrigger.Explicit)
            )
        }
        assertTrue(help != null && help.signatures.isNotEmpty(), "greet() should resolve to a signature")
        assertTrue(
            help!!.signatures[0].parameters.any { it.label.contains("who") },
            "the signature should expose parameter `who`; got ${help.signatures.map { it.label }}",
        )
    }

    @Test
    fun inlayParameterHintOnLiteralArg() {
        val vf = writeFile(
            """
            package com.foo;
            class Use { void run() { new Greeter().greet("hi"); } }
            """.trimIndent()
        )
        val hints = runBlocking { analyzer.inlayHints!!.hints(vf, TextRange(0, Int.MAX_VALUE)) }
        assertTrue(
            hints.any { h -> h.parts.any { it.text.startsWith("who") } },
            "the String literal arg should get a `who:` parameter hint; got ${hints.map { it.parts.map { p -> p.text } }}",
        )
    }
}
