package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.KotlinTreeResolver
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.walk
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import org.objectweb.asm.ClassWriter
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
 * A binary (library) method overload set whose parameter types are JVM FQNs (`java.lang.String` /
 * `java.lang.CharSequence` / `java.io.Serializable`) the static resolver can't line up with a Kotlin argument
 * type (`kotlin.String`) — the `android.content.Intent.putExtra(...)` shape. Because `argsBindable` rejects every
 * overload, `chooseCallee` used to tie out to `unsupported("unresolved/ambiguous call putExtra")` and fail the
 * whole preview. It must instead DEFER the homogeneous same-owner overload set to the runtime dispatcher (which
 * re-resolves the overload from the actual argument values), lowering cleanly as a Library MEMBER call.
 */
class KotlinBinaryOverloadDeferralTest {

    @Test
    fun overloadedBinaryMemberDefersInsteadOfReportingAmbiguous() {
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = listOf(bagJar()))
        val code = """
            import com.example.Bag
            fun use(bag: Bag) { bag.put("key", "value") }
        """.trimIndent()
        val kt = KotlinParserHost.parse("Use.kt", code)
        val parsed = KotlinParsedFile(kt, FakeFile("Use.kt"), 0)
        val fn = assertNotNull(KotlinTreeResolver(kt, parsed, service).lowerFirstFunction())
        assertTrue(
            fn.isComplete,
            "an overloaded binary member call must lower, not tie out to ambiguous; diags=${fn.diagnostics.map { it.reason }}",
        )

        var put: RNode.Call? = null
        fn.body.walk { if (it is RNode.Call && it.callee.displayName == "put") put = it }
        val call = assertNotNull(put, "the `put` call must lower to a Call")
        assertEquals(DispatchKind.MEMBER, call.dispatch, "an instance overload call dispatches MEMBER (reflected at runtime)")
        val callee = assertNotNull(call.callee as? ResolvedCallable.Library, "it must be a library callee re-resolved at runtime")
        assertEquals("com.example.Bag", callee.ownerFqn)
        assertEquals("put", callee.methodName)
    }

    /** A synthetic Java class with an overloaded `put(String, X)` (String / CharSequence / Serializable) — the
     *  `Intent.putExtra` shape, whose overloads are pairwise incomparable given a `kotlin.String` argument. */
    private fun bagJar(): Path {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/Bag", null, "java/lang/Object", null)
        fun put(secondParamDesc: String) = cw.visitMethod(
            Opcodes.ACC_PUBLIC, "put", "(Ljava/lang/String;$secondParamDesc)V", null, null,
        ).apply { visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd() }
        put("Ljava/lang/String;")
        put("Ljava/lang/CharSequence;")
        put("Ljava/io/Serializable;")
        cw.visitEnd()
        val jar = Files.createTempFile("bag", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            zos.putNextEntry(ZipEntry("com/example/Bag.class")); zos.write(cw.toByteArray()); zos.closeEntry()
        }
        return jar
    }
}
