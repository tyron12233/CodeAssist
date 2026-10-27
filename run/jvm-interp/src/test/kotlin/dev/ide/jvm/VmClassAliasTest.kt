package dev.ide.jvm

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * [ClassAlias] and [Vm.forgetClasses], as the real-view preview uses them for Android `R` classes: every
 * library's `R$id` shares the app's, and regenerated `R` bytes are picked up without a new [Vm].
 */
class VmClassAliasTest {

    /** `app/R$id` with `public static final int x = [value]` (a ConstantValue, as an app's final R). */
    private fun rId(value: Int): ByteArray = ClassWriter(0).run {
        visit(V1_8, ACC_PUBLIC or ACC_FINAL or ACC_SUPER, "app/R\$id", null, "java/lang/Object", null)
        visitField(ACC_PUBLIC or ACC_STATIC or ACC_FINAL, "x", "I", null, value).visitEnd()
        visitEnd()
        toByteArray()
    }

    /** `lib/Reader.get()` returns `lib.R.id.x`, as library code reads its own (aliased) `R`. */
    private val reader: ByteArray = ClassWriter(ClassWriter.COMPUTE_MAXS).run {
        visit(V1_8, ACC_PUBLIC or ACC_SUPER, "lib/Reader", null, "java/lang/Object", null)
        visitMethod(ACC_PUBLIC or ACC_STATIC, "get", "()I", null, null).apply {
            visitCode()
            visitFieldInsn(GETSTATIC, "lib/R\$id", "x", "I")
            visitInsn(IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        visitEnd()
        toByteArray()
    }

    @Test
    fun aliasedNameSharesTheTargetClassAndForgettingReloadsIt() {
        var value = 1
        val vm = Vm(
            source = ClassBytesSource { name ->
                when (name) {
                    "app/R\$id" -> rId(value)
                    "lib/Reader" -> reader
                    else -> null   // lib/R$id has no bytes of its own
                }
            },
            classAlias = ClassAlias { name -> if (name == "lib/R\$id") "app/R\$id" else null },
        )
        fun read() = vm.interpretedMethods("lib.Reader").single { it.name == "get" }.invoke(null, emptyList())

        assertEquals(1, read())
        assertSame(vm.resolve("app/R\$id"), vm.resolve("lib/R\$id"), "the alias must share the target's class")

        value = 2
        assertEquals(1, read(), "a cached class keeps its statics until forgotten")
        vm.forgetClasses { it.endsWith("/R\$id") }
        assertEquals(2, read())
    }
}
