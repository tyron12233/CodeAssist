package dev.ide.lang.kotlin

import dev.ide.lang.resolve.DocFormat
import dev.ide.lang.resolve.SourceDocProvider
import dev.ide.lang.signature.SignatureHelpRequest
import dev.ide.lang.signature.SignatureHelpTrigger
import dev.ide.testkit.DiskVirtualFile
import dev.ide.testkit.caret
import kotlinx.coroutines.runBlocking
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * When SOURCES are attached, completing a Java/Android API from a `.kt` file shows REAL parameter names and
 * javadoc instead of the `p0`/`p1` placeholders bytecode leaves behind. A synthetic `com.example.Widget` with
 * a parameterized method on the classpath (no debug names → `p0`/`p1`) is enriched by a stub [SourceDocProvider]
 * standing in for the source-doc index.
 */
class KotlinSourceDocEnrichmentTest {

    private fun complete(code: String) =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items

    @Test
    fun realParameterNamesReplaceBytecodePlaceholders() {
        val item = complete("import com.example.Widget\nfun f(w: Widget) { w.set| }")
            .first { it.symbol?.name == "setText" }
        assertTrue("message" in item.label, "real param name in label; got '${item.label}'")
        assertTrue("flags" in item.label, "real param name in label; got '${item.label}'")
        assertTrue("p0" !in item.label && "p1" !in item.label, "no bytecode placeholders; got '${item.label}'")
    }

    @Test
    fun javadocReachesTheCompletionItem() {
        val item = complete("import com.example.Widget\nfun f(w: Widget) { w.set| }")
            .first { it.symbol?.name == "setText" }
        assertEquals("Sets the widget text.", item.documentation)
    }

    @Test
    fun withoutSourcesNoNamesNoDocs() {
        // A method the provider knows nothing about keeps the bytecode placeholder display and no docs.
        val item = complete("import com.example.Widget\nfun f(w: Widget) { w.un| }")
            .first { it.symbol?.name == "untouched" }
        assertNotNull(item)
        assertTrue("p0" in item.label, "unenriched method keeps the placeholder; got '${item.label}'")
        assertEquals(null, item.documentation)
    }

    // ---- signature help: the parameter-info popup inside a call's parentheses ----

    private fun signatureLabel(code: String): String? = caret(code).let { (clean, offset) ->
        val doc = SnippetDoc(clean, DiskFile(srcDir.resolve("Use.kt")))
        runBlocking {
            analyzer.signatureHelp!!.signatureHelp(SignatureHelpRequest(doc, offset, SignatureHelpTrigger.Explicit))
        }?.let { it.signatures[it.activeSignature].label }
    }

    @Test
    fun aLibraryConstructorShowsItsSourceNames() {
        // Constructors came straight off the type shape, never enriched, so `NotificationChannel(` read
        // `(String, CharSequence, Int)` while methods of the same class had their names.
        val label = signatureLabel("import com.example.Widget\nfun f() { Widget(|) }")
        assertEquals("Widget(id: String, importance: Int)", label)
    }

    @Test
    fun aLibraryMethodShowsItsDebugInfoNamesWithoutSources() {
        // javac's default `-g` keeps names in the LocalVariableTable only; `ContextCompat.getSystemService(`
        // read `(Context, Class<T>)` because only `MethodParameters` was consulted.
        val label = signatureLabel("import com.example.Widget\nfun f() { Widget.make(|) }")
        assertEquals("make(label: String, size: Int): Widget", label)
    }

    @Test
    fun aJavaCallOffersNoNamedArguments() {
        // Kotlin rejects `make(label = …)` for a Java method, so knowing the names must not turn them into
        // `label =` completions, for a method or a constructor.
        val inMethod = complete("import com.example.Widget\nfun f() { Widget.make(|) }").map { it.label }
        assertTrue(inMethod.none { it.endsWith(" =") }, "no named-argument items; got $inMethod")
        val inCtor = complete("import com.example.Widget\nfun f() { Widget(|) }").map { it.label }
        assertTrue(inCtor.none { it.endsWith(" =") }, "no named-argument items; got $inCtor")
    }

    // ---- quick documentation (hover / Ctrl-Q) on a library symbol, which has no PSI to read a doc from ----

    private fun quickDoc(code: String) = caret(code).let { (clean, offset) ->
        analyzer.quickDoc(DiskVirtualFile(srcDir.resolve("Use.kt")), clean, offset)
    }

    @Test
    fun hoveringALibraryClassShowsItsSourceDoc() {
        // Used to come back empty: only members were enriched, so a class's doc never left the index.
        val info = assertNotNull(quickDoc("import com.example.Widget\nfun f(w: Wid|get) {}"))
        assertEquals("/** A widget that shows text. */", info.doc)
        assertEquals(DocFormat.JAVADOC, info.docFormat)
    }

    @Test
    fun hoveringALibraryMethodPrefersTheRawDoc() {
        val info = assertNotNull(quickDoc("import com.example.Widget\nfun f(w: Widget) { w.setT|ext(\"\", 0) }"))
        assertEquals("/** Sets the widget text. @param message the text */", info.doc)
        assertEquals(DocFormat.JAVADOC, info.docFormat)
    }

    companion object {
        val srcDir: Path = tempProject(emptyMap())

        private val provider = object : SourceDocProvider {
            override fun method(declaringFqn: String, methodName: String, arity: Int): SourceDocProvider.MethodDoc? =
                if (declaringFqn != "com.example.Widget" || arity != 2) null
                else when (methodName) {
                    "setText" -> SourceDocProvider.MethodDoc(listOf("message", "flags"), "Sets the widget text.")
                    "Widget" -> SourceDocProvider.MethodDoc(listOf("id", "importance"), null)
                    else -> null
                }

            override fun methodRaw(declaringFqn: String, methodName: String, arity: Int): String? =
                if (declaringFqn == "com.example.Widget" && methodName == "setText" && arity == 2)
                    "/** Sets the widget text. @param message the text */"
                else null

            override fun classDocRaw(fqn: String): String? =
                if (fqn == "com.example.Widget") "/** A widget that shows text. */" else null
        }

        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath(), widgetJar())))
            .also { it.sourceDocProvider = provider }

        /**
         * A Java class with a 2-arg `setText(String, int)`, a 1-arg `untouched(String)` and a `(String, int)`
         * constructor, all bytecode with no names, plus a static `make(String, int)` whose names are only in
         * its LocalVariableTable (javac's default `-g`).
         */
        private fun widgetJar(): Path {
            val cw = ClassWriter(0)
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/Widget", null, "java/lang/Object", null)
            cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;I)V", null, null).apply {
                visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 3); visitEnd()
            }
            cw.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "make", "(Ljava/lang/String;I)Lcom/example/Widget;", null, null,
            ).apply {
                val start = Label()
                val end = Label()
                visitCode(); visitLabel(start)
                visitInsn(Opcodes.ACONST_NULL); visitInsn(Opcodes.ARETURN)
                visitLabel(end)
                visitLocalVariable("label", "Ljava/lang/String;", null, start, end, 0)
                visitLocalVariable("size", "I", null, start, end, 1)
                visitMaxs(1, 2); visitEnd()
            }
            cw.visitMethod(Opcodes.ACC_PUBLIC, "setText", "(Ljava/lang/String;I)V", null, null).apply {
                visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
            }
            cw.visitMethod(Opcodes.ACC_PUBLIC, "untouched", "(Ljava/lang/String;)V", null, null).apply {
                visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
            }
            cw.visitEnd()
            val jar = Files.createTempFile("widget", ".jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
                zos.putNextEntry(ZipEntry("com/example/Widget.class")); zos.write(cw.toByteArray()); zos.closeEntry()
            }
            return jar
        }
    }
}
