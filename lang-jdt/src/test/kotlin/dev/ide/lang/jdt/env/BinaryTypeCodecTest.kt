package dev.ide.lang.jdt.env

import org.eclipse.jdt.internal.compiler.Compiler
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies
import org.eclipse.jdt.internal.compiler.ICompilerRequestor
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader
import org.eclipse.jdt.internal.compiler.env.IBinaryType
import org.eclipse.jdt.internal.compiler.env.INameEnvironment
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory
import java.nio.file.Paths
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spike validation for [BinaryTypeCodec]: (1) the round-trip preserves the structural surface ecj reads, and
 * (2) ecj produces the SAME diagnostics whether every library type is served as a real [ClassFileReader] or
 * round-tripped through the codec. The second is the real proof: it exercises overload resolution, generics
 * inference, assignability, the supertype chain, and unhandled exceptions, all built on our [IBinaryType].
 */
class BinaryTypeCodecTest {

    private val jrt = JrtImage.forHome(Paths.get(System.getProperty("java.home")))
    private fun reader(fqcn: String): ClassFileReader =
        ClassFileReader.read(jrt.bytes(fqcn)!!, fqcn.replace('.', '/') + ".class")

    @Test
    fun roundTripPreservesStructure() {
        for (fqcn in listOf("java.util.ArrayList", "java.lang.String", "java.util.HashMap", "java.lang.Thread")) {
            val src = reader(fqcn)
            val rt = BinaryTypeCodec.decode(BinaryTypeCodec.encode(src))
            assertEquals(str(src.name), str(rt.name), "name")
            assertEquals(str(src.superclassName), str(rt.superclassName), "superclass of $fqcn")
            assertEquals(src.interfaceNames?.map { String(it) }?.toSet(), rt.interfaceNames?.map { String(it) }?.toSet(), "interfaces of $fqcn")
            assertEquals(src.modifiers, rt.modifiers, "modifiers of $fqcn")
            assertEquals(src.tagBits, rt.tagBits, "tagBits of $fqcn")
            assertEquals(methodSigs(src), methodSigs(rt), "methods of $fqcn")
            assertEquals(fieldSigs(src), fieldSigs(rt), "fields of $fqcn")
        }
    }

    @Test
    fun ecjResolvesIdenticallyThroughTheCodec() {
        val samples = listOf(
            "clean-generics" to "package app; import java.util.*; public class T { void m() { List<String> l = new ArrayList<>(); l.add(\"x\"); int n = l.size(); String s = l.get(0); } }",
            "overload-error" to "package app; import java.util.*; public class T { void m() { List<String> l = new ArrayList<>(); l.add(1, 2, 3); } }",
            "type-mismatch" to "package app; import java.util.*; public class T { void m() { List<String> l = new ArrayList<>(); String s = l.size(); } }",
            "generic-arg-error" to "package app; import java.util.*; public class T { void m() { Map<String,Integer> x = new HashMap<>(); x.put(\"k\", \"not-an-int\"); } }",
            "inheritance" to "package app; import java.util.*; public class T { Object m() { ArrayList<String> a = new ArrayList<>(); return a.toString(); } }",
            "unhandled-exception" to "package app; public class T { void m() { Thread.sleep(10); } }",
            "deprecated" to "package app; public class T { @SuppressWarnings(\"deprecation\") void m() { Runtime.getRuntime(); } }",
        )
        for ((name, src) in samples) {
            val baseline = problems(roundTrip = false, src = src)
            val viaCodec = problems(roundTrip = true, src = src)
            assertEquals(baseline, viaCodec, "diagnostics diverged for [$name] when types come from the codec")
        }
    }

    @Test
    fun reportSerializedSize() {
        println("=== IBinaryType codec size vs class bytes ===")
        for (fqcn in listOf("java.lang.String", "java.util.ArrayList", "java.util.HashMap", "java.lang.Thread")) {
            val classBytes = jrt.bytes(fqcn)!!.size
            val codec = BinaryTypeCodec.encode(reader(fqcn)).size
            println("  $fqcn: class=${classBytes}B codec=${codec}B (${100 * codec / classBytes}%)")
        }
    }

    // ---- harness ----

    /** ecj problems for [src] (focal `app.T`), serving every library type either as a real ClassFileReader
     *  or round-tripped through the codec. java.* comes from the jrt image. */
    private fun problems(roundTrip: Boolean, src: String): List<String> {
        val env = object : INameEnvironment {
            override fun findType(compoundTypeName: Array<CharArray>) = resolve(compoundTypeName.joinToString(".") { String(it) })
            override fun findType(typeName: CharArray, packageName: Array<CharArray>): NameEnvironmentAnswer? {
                val pkg = packageName.joinToString(".") { String(it) }
                return resolve(if (pkg.isEmpty()) String(typeName) else "$pkg.${String(typeName)}")
            }
            override fun isPackage(parentPackageName: Array<CharArray>?, packageName: CharArray): Boolean {
                val parent = parentPackageName?.joinToString(".") { String(it) } ?: ""
                return jrt.isPackage(if (parent.isEmpty()) String(packageName) else "$parent.${String(packageName)}")
            }
            override fun cleanup() {}
            private fun resolve(fqcn: String): NameEnvironmentAnswer? {
                if (fqcn == "app.T") return null // the focal unit being compiled
                val bytes = jrt.bytes(fqcn) ?: return null
                val reader = runCatching { ClassFileReader.read(bytes, fqcn.replace('.', '/') + ".class") }.getOrNull() ?: return null
                val bt: IBinaryType = if (roundTrip) BinaryTypeCodec.decode(BinaryTypeCodec.encode(reader)) else reader
                return NameEnvironmentAnswer(bt, null)
            }
        }
        val opts = CompilerOptions().apply {
            complianceLevel = ClassFileConstants.JDK17; sourceLevel = ClassFileConstants.JDK17; targetJDK = ClassFileConstants.JDK17
            performMethodsFullRecovery = true; performStatementsRecovery = true; storeAnnotations = false
        }
        val compiler = Compiler(env, DefaultErrorHandlingPolicies.proceedWithAllProblems(), opts, ICompilerRequestor { }, DefaultProblemFactory(Locale.getDefault()))
        val cud = compiler.resolve(JdtSourceUnit("app.T", src.toCharArray()), true, true, true)
        return (cud?.compilationResult?.allProblems ?: emptyArray()).map { "${if (it.isError) "E" else "W"} ${it.message}" }.sorted()
    }

    private fun str(c: CharArray?): String? = c?.let { String(it) }
    private fun methodSigs(t: IBinaryType): Set<String> =
        (t.methods ?: emptyArray()).map { "${String(it.selector)}${String(it.methodDescriptor)}:${it.modifiers}:${it.genericSignature?.let(::String) ?: ""}" }.toSet()
    private fun fieldSigs(t: IBinaryType): Set<String> =
        (t.fields ?: emptyArray()).map { "${String(it.name)}:${String(it.typeName)}:${it.modifiers}" }.toSet()
}
