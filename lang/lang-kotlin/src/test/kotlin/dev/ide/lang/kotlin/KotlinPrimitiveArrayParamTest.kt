package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Passing a `ByteArray` to a Java `write(byte[])` is correct Kotlin — `ByteArray` IS the JVM `byte[]` — but the
 * bytecode decode used to type every array as `Array<elem>`, so the argument check reported "inferred type is
 * ByteArray but Array<Byte> was expected" on `outputStream.write(bytes)`. The mismatch then drove the
 * lightbulb, which offered to import an unrelated same-named extension as the "fix".
 *
 * The fixture is a synthetic `demo.Sink` with `java.io.OutputStream`'s overload set (so the check must pick
 * between `write(int)` and `write(byte[])` exactly as it does for the real class) plus `int[]`/`char[]`/
 * `String[]` methods, and a project-source `other.write` extension standing in for the bogus import candidate
 * the mismatch used to surface.
 */
class KotlinPrimitiveArrayParamTest {

    private fun analyze(body: String): List<Diagnostic> {
        val code = "package demo\n$body\n"
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
            .filter { it.severity == dev.ide.lang.dom.Severity.ERROR }
    }

    @Test
    fun byteArrayPassedToAJavaByteArrayParamIsNotFlagged() {
        val ds = analyze("fun f(s: Sink, b: ByteArray) { s.write(b) }")
        assertTrue(ds.isEmpty(), "a ByteArray IS a `byte[]`; nothing to report. got $ds")
    }

    @Test
    fun theIntOverloadStillResolves() {
        val ds = analyze("fun f(s: Sink) { s.write(1) }")
        assertTrue(ds.isEmpty(), "`write(int)` accepts an Int literal; got $ds")
    }

    @Test
    fun theLongerByteArrayOverloadIsNotFlagged() {
        val ds = analyze("fun f(s: Sink, b: ByteArray) { s.write(b, 0, b.size) }")
        assertTrue(ds.isEmpty(), "`write(byte[], int, int)` takes a ByteArray; got $ds")
    }

    @Test
    fun otherPrimitiveArraysAreNotFlagged() {
        val ds = analyze("fun f(s: Sink, i: IntArray, c: CharArray) { s.ints(i); s.chars(c) }")
        assertTrue(ds.isEmpty(), "`int[]`/`char[]` params take IntArray/CharArray; got $ds")
    }

    /** The specialisation is per-element-kind: a REFERENCE array is still `Array<E>`, and an `Array<String>`
     *  must keep fitting it (the fix must not have turned every array into a specialised class). */
    @Test
    fun referenceArrayStillTakesAnArray() {
        val ds = analyze("fun f(s: Sink) { s.names(arrayOf(\"a\")) }")
        assertTrue(ds.isEmpty(), "`String[]` takes an Array<String>; got $ds")
    }

    /** The check must still catch a real mismatch — the fix widens nothing beyond the array mapping. */
    @Test
    fun agenuinelyWrongArgumentIsStillFlagged() {
        val ds = analyze("fun f(s: Sink) { s.ints(\"nope\") }")
        assertTrue(
            ds.any { it.code == KotlinDiagnosticCodes.TYPE_MISMATCH },
            "a String is not an `int[]`; that must still be reported. got $ds",
        )
    }

    /** The reported second half: with no mismatch there is no lightbulb, so the unrelated `other.write`
     *  extension is no longer offered as the "fix" for a perfectly valid call. */
    @Test
    fun noBogusImportIsOfferedOnTheValidCall() {
        val code = "package demo\nfun f(s: Sink, b: ByteArray) { s.write(b) }\n"
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        val titles = runBlocking {
            analyzer.incrementalParser.parseFull(doc)
            analyzer.importFixesAt(doc.file, code.indexOf("write") + 1).map { it.title }
        }
        assertTrue(titles.isEmpty(), "a valid call must offer no import; got $titles")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                // A same-named extension elsewhere in the project: what the mismatch quick-fix used to offer.
                "Ext.kt" to "package other\nimport demo.Sink\nfun Sink.write(text: String) {}\n",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath(), sinkJar())))

        /** `public class demo.Sink` with `java.io.OutputStream`'s `write` overloads plus array-shaped extras. */
        private fun sinkJar(): Path {
            val cw = ClassWriter(0)
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "demo/Sink", null, "java/lang/Object", null)
            fun method(name: String, descriptor: String, access: Int = Opcodes.ACC_PUBLIC) {
                cw.visitMethod(access, name, descriptor, null, null).apply {
                    visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 4); visitEnd()
                }
            }
            method("<init>", "()V")
            method("write", "(I)V")
            method("write", "([B)V")
            method("write", "([BII)V")
            method("ints", "([I)V")
            method("chars", "([C)V")
            method("names", "([Ljava/lang/String;)V")
            cw.visitEnd()
            val jar = Files.createTempFile("sink", ".jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
                zos.putNextEntry(ZipEntry("demo/Sink.class")); zos.write(cw.toByteArray()); zos.closeEntry()
            }
            return jar
        }
    }
}
