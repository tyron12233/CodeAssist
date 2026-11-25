package dev.ide.build.split

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A toolchain call into the program names the method's signature types as well as its owner, and R8 never
 * rewrites the toolchain's side. Keeping only the member let R8 rename its RETURN type: kotlinc's
 * `PersistentSet.builder()` call (`()Lkotlinx/collections/immutable/PersistentSet$Builder;`) met a kept
 * `builder()` whose descriptor had become `()Lq09;`, so the on-device compile died with NoSuchMethodError.
 */
class ToolchainKeepRulesTest {

    private val set = "kotlinx/collections/immutable/PersistentSet"
    private val builder = "$set\$Builder"
    private val holder = "kotlinx/collections/immutable/Holder"
    private val elem = "kotlinx/collections/immutable/Elem"

    private val program = mapOf(
        set to ToolchainKeepRules.ProgramClass(null, emptyList(), emptySet(), setOf("builder()L$builder;")),
        builder to ToolchainKeepRules.ProgramClass(null, emptyList(), emptySet(), emptySet()),
        holder to ToolchainKeepRules.ProgramClass(null, emptyList(), setOf("value:L$elem;"), emptySet()),
        elem to ToolchainKeepRules.ProgramClass(null, emptyList(), emptySet(), emptySet()),
    )

    /** A toolchain class whose one method calls `PersistentSet.builder()` and reads `Holder.value`. */
    private fun toolchainClass(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "org/jetbrains/kotlin/fir/LogicSystemKt", null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "toMutable", "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitTypeInsn(Opcodes.CHECKCAST, set)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, set, "builder", "()L$builder;", true)
        mv.visitInsn(Opcodes.POP)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitTypeInsn(Opcodes.CHECKCAST, holder)
        mv.visitFieldInsn(Opcodes.GETFIELD, holder, "value", "L$elem;")
        mv.visitInsn(Opcodes.POP)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun memberSignatureTypesAreKeptByName() {
        val rules = ToolchainKeepRules(program).apply { scan(toolchainClass()) }.render()
        assertTrue("-keep class kotlinx.collections.immutable.PersistentSet {" in rules, rules)
        assertTrue("kotlinx.collections.immutable.PersistentSet\$Builder builder();" in rules, rules)
        assertTrue("-keep class kotlinx.collections.immutable.PersistentSet\$Builder\n" in rules, "the return type must keep its name\n$rules")
        assertTrue("-keep class kotlinx.collections.immutable.Elem\n" in rules, "the field type must keep its name\n$rules")
    }
}
