package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The "Import …" fix on a call-argument mismatch exists for one shape: the call binds an in-scope overload and
 * mismatches, but an UNIMPORTED overload of the same name would fit (Compose's `items(list)` binding
 * `LazyListScope.items(Int, …)` when `androidx.compose.foundation.lazy.items` is what the code wants). It used
 * to offer every importable declaration with that name and check nothing — so the mismatch on `out.write(bytes)`
 * offered an unrelated `write` extension: an import that leaves the error in place and adds a dead line.
 *
 * These are the candidates that are now filtered out, each one demonstrably useless. The POSITIVE control —
 * that a candidate which really does fit is still offered — is [KotlinRealFoundationItemsTest]'s
 * `itemsListArgOffersTheExtensionImport`, against the real foundation jar; keep both green together, since
 * "offer nothing, ever" would satisfy this class alone.
 *
 * Note what cannot be tested here: a PROJECT-SOURCE candidate is import-blind to resolution, so one that fits
 * is already a resolution candidate and no mismatch is reported at all. The gate is aligned with the mismatch
 * check by construction — whatever it cannot reject, the check cannot either — so every fixture below has to
 * be a candidate the check already refused.
 */
class KotlinMismatchImportFixTest {

    private fun titlesAt(code: String, needle: String): List<String> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking {
            analyzer.incrementalParser.parseFull(doc)
            analyzer.analyze(doc.file)
            analyzer.importFixesAt(doc.file, code.indexOf(needle) + 1).map { it.title }
        }
    }

    private fun diagnose(code: String) = runBlocking {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics
    }

    // --- a bare call: no receiver to constrain the candidate, so the ARGUMENTS decide ------------------------

    /** `show("x")` mismatches the in-scope `demo.show(Int)`; `wrong.show(Boolean)` cannot hold a String either,
     *  so importing it is not a fix. */
    @Test
    fun aCandidateThatCannotTakeTheArgumentIsNotOffered() {
        val code = "package demo\nfun f() { show(\"x\") }\n"
        assertTrue(
            diagnose(code).any { it.code == KotlinDiagnosticCodes.TYPE_MISMATCH },
            "fixture check: the call must mismatch, else the fix list is trivially empty",
        )
        assertEquals(emptyList(), titlesAt(code, "\"x\""), "no `show` can take a String; nothing to offer")
    }

    /** The argument COUNT is the same kind of proof: `tag("x", 1)` mismatches `demo.tag(Int, Int)` on its first
     *  argument, and the one-parameter `wrong.tag(String)` could never hold two. */
    @Test
    fun aCandidateWithTooFewParametersIsNotOffered() {
        val code = "package demo\nfun f() { tag(\"x\", 1) }\n"
        assertTrue(
            diagnose(code).any { it.code == KotlinDiagnosticCodes.TYPE_MISMATCH },
            "fixture check: the call must mismatch, else the fix list is trivially empty",
        )
        assertTrue("Import wrong.tag" !in titlesAt(code, "\"x\""), "a 1-param `tag` can't take 2 arguments")
    }

    // --- an explicit receiver: only an extension ON that receiver is reachable ------------------------------

    /** `s.put("x")` mismatches `Sink.put(int)`. A same-named TOP-LEVEL function can never be called through a
     *  receiver, so importing it changes nothing about this call. */
    @Test
    fun aTopLevelCandidateIsNotOfferedForAQualifiedCall() {
        val titles = titlesAt("package demo\nfun f(s: Sink) { s.put(\"x\") }\n", "\"x\"")
        assertTrue("Import fits.put" !in titles, "a top-level function is unreachable via `s.`; got $titles")
    }

    /** An extension on an unrelated receiver is just as unreachable — a `String.put` says nothing about a Sink. */
    @Test
    fun anExtensionOnAnUnrelatedReceiverIsNotOffered() {
        val titles = titlesAt("package demo\nfun f(s: Sink) { s.put(\"x\") }\n", "\"x\"")
        assertTrue("Import wrong.put" !in titles, "a `String.put` extension can't apply to a Sink; got $titles")
    }

    /** Both halves at once, and the reason the list is empty: no candidate fits — not that the check stopped
     *  running. The mismatch is still reported. */
    @Test
    fun theQualifiedMismatchIsStillReportedWithNoFixOffered() {
        val code = "package demo\nfun f(s: Sink) { s.put(\"x\") }\n"
        assertTrue(
            diagnose(code).any { it.code == KotlinDiagnosticCodes.TYPE_MISMATCH },
            "the mismatch itself must still be reported",
        )
        assertEquals(emptyList(), titlesAt(code, "\"x\""), "no candidate fits, so nothing should be offered")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                // The in-scope overloads the bare calls bind and mismatch against.
                "Local.kt" to "package demo\nfun show(n: Int) {}\nfun tag(a: Int, b: Int) {}\n",
                // Importable, same names, none of them able to take the call.
                "Wrong.kt" to
                    "package wrong\nfun show(flag: Boolean) {}\nfun tag(text: String) {}\nfun String.put(text: String) {}\n",
                // A top-level `put`: it would fit the ARGUMENT, but not a call written as `s.put(…)`.
                "Fits.kt" to "package fits\nfun put(text: String) {}\n",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath(), sinkJar())))

        /** `public class demo.Sink { void put(int) }` — one overload, so a String argument mismatches it. */
        private fun sinkJar(): Path {
            val cw = ClassWriter(0)
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "demo/Sink", null, "java/lang/Object", null)
            listOf("<init>" to "()V", "put" to "(I)V").forEach { (name, descriptor) ->
                cw.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null).apply {
                    visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 2); visitEnd()
                }
            }
            cw.visitEnd()
            val jar = Files.createTempFile("sink-put", ".jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
                zos.putNextEntry(ZipEntry("demo/Sink.class")); zos.write(cw.toByteArray()); zos.closeEntry()
            }
            return jar
        }
    }
}
