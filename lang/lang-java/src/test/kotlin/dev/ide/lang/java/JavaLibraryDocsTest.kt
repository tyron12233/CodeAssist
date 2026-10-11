package dev.ide.lang.java

import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.lang.resolve.SourceDocProvider
import dev.ide.lang.signature.SignatureHelpRequest
import dev.ide.lang.signature.SignatureHelpTrigger
import dev.ide.testkit.TestDocument
import dev.ide.vfs.VirtualFile
import dev.ide.vfs.local.LocalFileSystem
import dev.ide.vfs.local.fileFor
import kotlinx.coroutines.runBlocking
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A compiled library method has no javadoc and, when abstract, no parameter names: the PSI makes them up. The
 * analyzer's [JavaSourceAnalyzer.sourceDocProvider] supplies both to signature help, quick doc and inlay hints,
 * and is never consulted for a source method, whose own names and comment stand.
 */
class JavaLibraryDocsTest {
    private val libDir = Files.createTempDirectory("java-lib").toFile()
    private val srcRoot = Files.createTempDirectory("java-src").toFile()
    private val fs = LocalFileSystem(srcRoot.toPath())
    private val env: JavaEnvironment
    private val analyzer: JavaSourceAnalyzer

    init {
        File(libDir, "lib").mkdirs()
        // public interface lib.Listener { void onEvent(int, String); }  (abstract: no parameter names kept)
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT, "lib/Listener", null, "java/lang/Object", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "onEvent", "(ILjava/lang/String;)V", null, null).visitEnd()
        cw.visitEnd()
        File(libDir, "lib/Listener.class").writeBytes(cw.toByteArray())
        File(srcRoot, "com/foo").mkdirs()
        File(srcRoot, "com/foo/Greeter.java").writeText(
            "package com.foo;\npublic class Greeter {\n    /** Greets. */\n    public String greet(String who) { return who; }\n}\n",
        )
        env = JavaEnvironment.create(listOf(libDir), listOf(srcRoot), File(System.getProperty("java.home")))
        analyzer = JavaSourceAnalyzer(env).also { it.sourceDocProvider = FakeDocs }
    }

    private object FakeDocs : SourceDocProvider {
        override fun method(declaringFqn: String, methodName: String, arity: Int) = when ("$declaringFqn#$methodName/$arity") {
            "lib.Listener#onEvent/2" -> SourceDocProvider.MethodDoc(listOf("code", "message"), "Called on an event.")
            "com.foo.Greeter#greet/1" -> SourceDocProvider.MethodDoc(listOf("wrong"), "Wrong doc.")
            else -> null
        }

        override fun methodRaw(declaringFqn: String, methodName: String, arity: Int) =
            if (declaringFqn == "lib.Listener" && methodName == "onEvent") "/** Called on an event. */" else null
    }

    @AfterTest
    fun tearDown() {
        env.close()
        libDir.deleteRecursively(); srcRoot.deleteRecursively()
    }

    private class Snap(file: VirtualFile, text: CharSequence) : DocumentSnapshot by TestDocument(text, file)

    private fun file(text: String): VirtualFile {
        val f = File(srcRoot, "com/foo/Use.java").apply { writeText(text) }
        return fs.fileFor(f.toPath())
    }

    private fun signature(call: String): Pair<String, String?> {
        val src = "package com.foo; class Use { void run(lib.Listener l) { $call } }"
        val offset = src.indexOf('|')
        val text = src.removeRange(offset, offset + 1)
        val help = runBlocking {
            analyzer.signatureHelp!!.signatureHelp(SignatureHelpRequest(Snap(file(text), text), offset, SignatureHelpTrigger.Explicit))
        }!!
        return help.signatures[0].label to help.signatures[0].documentation
    }

    @Test
    fun signatureHelpGetsRealNamesAndDocForACompiledMethod() {
        val (label, doc) = signature("l.onEvent(|);")
        assertEquals("onEvent(int code, String message)", label.substringBefore(":").trim())
        assertEquals("Called on an event.", doc)
    }

    @Test
    fun aSourceMethodKeepsItsOwnNamesAndDoc() {
        val (label, doc) = signature("new Greeter().greet(|);")
        assertTrue("String who" in label, label)
        assertEquals("Greets.", doc)
    }

    @Test
    fun quickDocShowsTheCompiledMethodsDoc() {
        val text = "package com.foo; class Use { void run(lib.Listener l) { l.onEvent(1, \"x\"); } }"
        val info = analyzer.quickDoc(file(text), text, text.indexOf("onEvent") + 2)!!
        assertEquals("/** Called on an event. */", info.doc)
        assertTrue("int code, String message" in info.signature, info.signature)
    }

    @Test
    fun inlayHintsUseTheRealNames() {
        val text = "package com.foo; class Use { void run(lib.Listener l) { l.onEvent(1, \"x\"); } }"
        val vf = file(text)
        val hints = runBlocking { analyzer.inlayHints!!.hints(vf, TextRange(0, Int.MAX_VALUE)) }
        val labels = hints.map { h -> h.parts.joinToString("") { it.text } }
        assertTrue("code:" in labels && "message:" in labels, labels.toString())
    }
}
