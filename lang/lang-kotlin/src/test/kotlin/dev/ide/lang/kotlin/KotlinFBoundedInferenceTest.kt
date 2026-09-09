package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.resolve.KotlinConstraintSystem
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F-BOUNDED type parameters (`<T : Cmp<T>>`) in [KotlinConstraintSystem]: a bound that mentions the variable
 * it bounds, the shape every Java `<T extends Comparable<T>>` or self-returning-builder API has.
 *
 * Such a bound made the solver non-terminating: a lower bound on `T` was checked against the bound, projecting
 * the receiver onto the bound's classifier decomposed its arguments, and the argument WAS `T`, so the
 * decomposition added another bound to `T`, which re-ran the check, which decomposed again. Each turn added a
 * (duplicate) bound and one more `addSubtypeConstraint`/`check`/`checkConcrete`/`decompose` stack frame, so a
 * single member call exhausted the resolver thread's stack and crashed the process on device.
 *
 * Every case here must terminate AND still solve, and a genuine mismatch must still be a contradiction: the
 * termination guards must not blunt the applicability signal overload resolution reads.
 */
class KotlinFBoundedInferenceTest {

    /** Run [body] on a small-stack thread so a non-terminating solve fails as a clear overflow, not a hang. */
    private fun terminates(label: String, body: () -> Unit) {
        var failure: Throwable? = null
        val t = Thread(null, { runCatching(body).onFailure { failure = it } }, label, 1L * 1024 * 1024)
        t.start()
        t.join(60_000)
        assertFalse(t.isAlive, "$label did not finish in 60s")
        failure?.let { throw AssertionError("$label failed: $it", it) }
    }

    // ---- the solver directly ----

    private val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = listOf(stdlibJarPath()))

    private fun type(fqn: String, vararg args: KotlinType) = KotlinType(fqn, args.toList(), context = service)
    private fun param(name: String) = KotlinType(name, isTypeParameter = true, context = service)

    @Test
    fun selfReferentialUpperBoundStillSolves() {
        // `<T : Comparable<T>> maxOf(a: T, b: T)` called with an Int: Comparable is declared `in T`, so
        // projecting Int onto it decomposes CONTRAVARIANTLY and pushes Int back as an UPPER bound of T.
        terminates("comparable") {
            val cs = KotlinConstraintSystem(service)
            cs.registerVariable("T", type("kotlin.Comparable", param("T")))
            cs.addSubtypeConstraint(type("kotlin.Int"), param("T"))
            assertEquals("Int", cs.solve()["T"]?.toString(), "T fixes to the argument type")
            assertFalse(cs.hasContradiction, "Int satisfies Comparable<Int>")
        }
        terminates("comparable-string") {
            val cs = KotlinConstraintSystem(service)
            cs.registerVariable("T", type("kotlin.Comparable", param("T")))
            cs.addSubtypeConstraint(type("kotlin.String"), param("T"))
            assertEquals("String", cs.solve()["T"]?.toString())
            assertFalse(cs.hasContradiction)
        }
    }

    @Test
    fun selfReferentialCovariantAndInvariantBoundsTerminate() {
        // The same shape through a COVARIANT bound (`<T : List<T>>`) and an INVARIANT one
        // (`<T : MutableList<T>>`), whose decompositions push bounds back onto T the other way round.
        for ((label, bound) in listOf(
            "list" to "kotlin.collections.List",
            "mutable-list" to "kotlin.collections.MutableList",
        )) terminates(label) {
            val cs = KotlinConstraintSystem(service)
            cs.registerVariable("T", type(bound, param("T")))
            cs.addSubtypeConstraint(type(bound, type("kotlin.Int")), param("T"))
            cs.solve()
        }
    }

    @Test
    fun concreteMismatchIsStillAContradiction() {
        // The guards must not cost the applicability signal: a floor that is not a subtype of the ceiling.
        val cs = KotlinConstraintSystem(service)
        cs.registerVariable("T", type("kotlin.Int"))
        cs.addSubtypeConstraint(type("kotlin.String"), param("T"))
        assertTrue(cs.hasContradiction, "String is not an Int")
    }

    @Test
    fun repeatedBoundKeepsTheSolution() {
        // The same constraint added twice (two arguments of the same type, `maxOf(1, 2)`) is deduplicated;
        // the second must not be lost, and a WIDER second argument must still widen the fixation.
        val cs = KotlinConstraintSystem(service)
        cs.registerVariable("T")
        cs.addSubtypeConstraint(type("kotlin.Int"), param("T"))
        cs.addSubtypeConstraint(type("kotlin.Int"), param("T"))
        assertEquals("Int", cs.solve()["T"]?.toString())

        val widened = KotlinConstraintSystem(service)
        widened.registerVariable("T")
        widened.addSubtypeConstraint(type("kotlin.Int"), param("T"))
        widened.addSubtypeConstraint(type("kotlin.String"), param("T"))
        assertEquals("Comparable", widened.solve()["T"]?.toString(), "Int + String fix to their common supertype")
    }

    // ---- end to end: the library shape that crashed the device ----

    /** `interface Cmp<T>`, `class Node implements Cmp<Node>`, `class Sorter { <T extends Cmp<T>> T best(T, T) }`. */
    private fun fBoundedJar(): Path = TestJars.buildJar {
        clazz(
            "demo/Cmp",
            classBytes(
                "demo/Cmp", "<T:Ljava/lang/Object;>Ljava/lang/Object;",
                access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            ),
        )
        clazz(
            "demo/Node",
            classBytes("demo/Node", "Ljava/lang/Object;Ldemo/Cmp<Ldemo/Node;>;", interfaces = arrayOf("demo/Cmp")) {
                defaultConstructor()
            },
        )
        // class Expansive<T> implements Cmp<Expansive<Expansive<T>>>, EXPANSIVE inheritance: projecting it
        // onto Cmp yields a strictly deeper type, so each decomposition can produce a brand-new bound.
        clazz(
            "demo/Expansive",
            classBytes(
                "demo/Expansive",
                "<T:Ljava/lang/Object;>Ljava/lang/Object;Ldemo/Cmp<Ldemo/Expansive<Ldemo/Expansive<TT;>;>;>;",
                interfaces = arrayOf("demo/Cmp"),
            ) { defaultConstructor() },
        )
        clazz(
            "demo/Sorter",
            classBytes("demo/Sorter", null) {
                visitMethod(
                    Opcodes.ACC_PUBLIC, "best", "(Ldemo/Cmp;Ldemo/Cmp;)Ldemo/Cmp;",
                    "<T::Ldemo/Cmp<TT;>;>(TT;TT;)TT;", null,
                ).apply {
                    visitCode(); visitInsn(Opcodes.ACONST_NULL); visitInsn(Opcodes.ARETURN)
                    visitMaxs(1, 3); visitEnd()
                }
                visitMethod(
                    Opcodes.ACC_PUBLIC, "pick", "(Ldemo/Cmp;)Ldemo/Cmp;",
                    "<T::Ldemo/Cmp<TT;>;>(TT;)TT;", null,
                ).apply {
                    visitCode(); visitInsn(Opcodes.ACONST_NULL); visitInsn(Opcodes.ARETURN)
                    visitMaxs(1, 2); visitEnd()
                }
                defaultConstructor()
            },
        )
    }

    private fun classBytes(
        internalName: String,
        signature: String?,
        access: Int = Opcodes.ACC_PUBLIC,
        interfaces: Array<String>? = null,
        body: ClassWriter.() -> Unit = {},
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V1_8, access, internalName, signature, "java/lang/Object", interfaces)
        cw.body()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun ClassWriter.defaultConstructor() {
        visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1); visitEnd()
        }
    }

    @Test
    fun analyzingACallToAnFBoundedLibraryMethodTerminates() {
        val dir = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(dir, listOf(stdlibJarPath(), fBoundedJar())))
        val code = "package demo\nfun f(s: Sorter, n: Node) { val x = s.best(n, n)\n  println(x) }\n"
        val doc = SnippetDoc(code, DiskFile(dir.resolve("FB.kt")))
        terminates("f-bounded-call") {
            val d = runBlocking {
                analyzer.incrementalParser.parseFull(doc)
                analyzer.analyze(doc.file).diagnostics
            }
            assertTrue(d.isEmpty(), "the call is valid; got ${d.map { it.code }}")
        }
    }

    @Test
    fun analyzingACallWhoseBoundWidensForeverTerminates() {
        // `Expansive<T> : Cmp<Expansive<Expansive<T>>>` against `<T : Cmp<T>>`: every decomposition projects a
        // STRICTLY DEEPER type, so deduplication alone can't converge; the bound count is what stops it.
        val dir = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(dir, listOf(stdlibJarPath(), fBoundedJar())))
        val code = "package demo\nfun f(s: Sorter, e: Expansive<Int>) { val x = s.pick(e)\n  println(x) }\n"
        val doc = SnippetDoc(code, DiskFile(dir.resolve("FBX.kt")))
        terminates("expansive-call") {
            runBlocking {
                analyzer.incrementalParser.parseFull(doc)
                analyzer.analyze(doc.file).diagnostics
            }
        }
    }
}
