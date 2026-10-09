package dev.ide.interp.compose

import androidx.compose.ui.text.PlatformParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle
import dev.ide.jvm.ClassBytesSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Library bytecode compiled against Android Compose, run on the desktop host: `material3-android`'s
 * `DefaultPlatformTextStyle_androidKt` builds `PlatformTextStyle(includeFontPadding = false)`, a constructor
 * Compose for Desktop lacks. Its failed static initializer used to leave Material3's typography unbuilt, so the
 * preview died later on `getColor` of a null text style. The bridge now substitutes the host's neutral style.
 */
class PlatformStyleCompatTest {

    private val owner = "fixture/AndroidTextStyleKt"

    /** `static Object make()` running `new <type>(<descriptor>)` with [pushArgs] supplying the arguments. */
    private fun fixture(type: String, descriptor: String, pushArgs: (org.objectweb.asm.MethodVisitor) -> Unit): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, owner, null, "java/lang/Object", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "make", "()Ljava/lang/Object;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, type)
            visitInsn(Opcodes.DUP)
            pushArgs(this)
            visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", descriptor, false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun run(bytes: ByteArray): Any? {
        val executor = VmLibraryExecutor(
            hostLoadable = { it != owner.replace('/', '.') },
            source = ClassBytesSource { if (it == owner) bytes else null },
        )
        return executor.invokeStatic(owner.replace('/', '.'), "make", emptyList())
    }

    @Test fun includeFontPaddingConstructorBuildsTheNeutralTextStyle() {
        val bytes = fixture("androidx/compose/ui/text/PlatformTextStyle", "(Z)V") { it.visitInsn(Opcodes.ICONST_0) }
        val style = run(bytes) as PlatformTextStyle
        assertNull(style.spanStyle)
        assertNull(style.paragraphStyle)
    }

    @Test fun includeFontPaddingParagraphStyleIsTheDefault() {
        val bytes = fixture("androidx/compose/ui/text/PlatformParagraphStyle", "(Z)V") { it.visitInsn(Opcodes.ICONST_1) }
        assertEquals(PlatformParagraphStyle.Default, run(bytes))
    }
}
