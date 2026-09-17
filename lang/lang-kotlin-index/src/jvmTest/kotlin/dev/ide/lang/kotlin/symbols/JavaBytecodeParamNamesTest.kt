package dev.ide.lang.kotlin.symbols

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Java bytecode carries real parameter names only in the `MethodParameters` attribute (emitted by `javac
 * -parameters`), and ASM gates that attribute on `SKIP_DEBUG`, which [JavaBytecode] used to pass, so every
 * Java method reached `p0`/`p1` regardless of how it was compiled.
 *
 * The absent-attribute case matters just as much as the present one: [KotlinSymbol.paramNames] must stay
 * EMPTY rather than be filled with `p0`/`p1`, because that emptiness is the signal
 * `KotlinSymbolService.enrich` keys off to splice names in from attached sources.
 */
class JavaBytecodeParamNamesTest {

    /** `public class p/Widget { public void setPadding(int, int) }`, with [names] as MethodParameters. */
    private fun widgetBytes(names: List<String>?): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "p/Widget", null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "setPadding", "(II)V", null, null)
        names?.forEach { mv.visitParameter(it, 0) }
        mv.visitCode()
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 2)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun setPadding(names: List<String>?): KotlinSymbol =
        JavaBytecode.read(widgetBytes(names), null)!!.members.single { it.name == "setPadding" }

    @Test
    fun readsRealParameterNamesFromMethodParameters() {
        val m = setPadding(listOf("left", "top"))
        assertEquals(listOf("left", "top"), m.paramNames)
        assertEquals("(left: int, top: int): void", m.signature)
    }

    @Test
    fun leavesNamesEmptyWhenTheClassWasNotCompiledWithParameters() {
        val m = setPadding(null)
        assertTrue(m.paramNames.isEmpty(), "empty is what tells enrich() to consult attached sources")
        assertEquals("(p0: int, p1: int): void", m.signature)
    }

    /** A rewritten/truncated attribute would otherwise shift every name onto the wrong parameter. */
    @Test
    fun ignoresMethodParametersThatDoNotCoverEveryParameter() {
        val m = setPadding(listOf("left"))
        assertTrue(m.paramNames.isEmpty())
        assertEquals("(p0: int, p1: int): void", m.signature)
    }
}
