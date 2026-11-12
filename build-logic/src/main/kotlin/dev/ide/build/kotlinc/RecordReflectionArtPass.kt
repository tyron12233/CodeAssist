package dev.ide.build.kotlinc

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Answers `Class.isRecord()`, `getRecordComponents()`, `isSealed()` and `getPermittedSubclasses()` (all Android
 * API 34) at the call site, for Jackson 3 and the MCP SDK.
 *
 * Jackson asks `isRecord()` of every type it is about to deserialize. Below API 34 the method is not there, so
 * the first request an MCP client sent threw [NoSuchMethodError] on the server's connection thread, where
 * nothing catches an `Error`, and the process ended.
 *
 * The answer the call would give is known without making it. The app's `minSdk` is 26, so D8 desugars every
 * record in the dex into an ordinary final class, and on a device that does have the method (Android 14 and
 * later) it returns false for those. Constant false here is therefore the same answer on every device, not a
 * degraded one: it is how the MCP server has been reading its (desugared) record types all along. `isSealed`
 * follows the same reasoning for the `PermittedSubclasses` attribute, which a dex file does not carry.
 *
 * Each call is replaced by popping the receiver and pushing the constant, which leaves the stack as the call
 * would have.
 */
class RecordReflectionArtPass : ArtPatchPass {

    override val name: String = "record-reflection-constant"

    override fun handles(classFqn: String): Boolean = PREFIXES.any { classFqn.startsWith(it) }

    override fun visitor(classFqn: String, next: ClassVisitor): ClassVisitor = Rewriter(next)

    private class Rewriter(next: ClassVisitor) : ClassVisitor(Opcodes.ASM9, next) {
        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor {
            val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
            return object : MethodVisitor(Opcodes.ASM9, mv) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    methodName: String,
                    methodDescriptor: String,
                    isInterface: Boolean,
                ) {
                    val constant = if (opcode == Opcodes.INVOKEVIRTUAL && owner == CLASS) ANSWERS[methodName + methodDescriptor] else null
                    if (constant != null) {
                        super.visitInsn(Opcodes.POP)
                        super.visitInsn(constant)
                        return
                    }
                    super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface)
                }
            }
        }
    }

    private companion object {
        const val CLASS = "java/lang/Class"

        /** What each call is replaced with: false, or the null the platform returns for a type that is neither. */
        val ANSWERS = mapOf(
            "isRecord()Z" to Opcodes.ICONST_0,
            "isSealed()Z" to Opcodes.ICONST_0,
            "getRecordComponents()[Ljava/lang/reflect/RecordComponent;" to Opcodes.ACONST_NULL,
            "getPermittedSubclasses()[Ljava/lang/Class;" to Opcodes.ACONST_NULL,
        )

        val PREFIXES = listOf("tools.jackson.", "io.modelcontextprotocol.")
    }
}
