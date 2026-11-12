package dev.ide.build.kotlinc

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Backports `String.formatted(Object...)` (Java 15, Android API 34) for the MCP SDK and Jackson 3, both built
 * for a desktop JVM and dexed into the app.
 *
 * Neither D8 nor core-library desugaring covers the method, so below API 34 the call throws
 * [NoSuchMethodError]. Every site builds an exception message, which turns a recoverable failure (a closed MCP
 * session, a JSON mapping problem) into an `Error` nothing upstream expects to catch.
 *
 * `receiver.formatted(args)` is `String.format(receiver, args)` by definition, and the static form takes the
 * receiver as its leading argument, so swapping the instruction leaves the stack untouched.
 */
class StringFormattedArtPass : ArtPatchPass {

    override val name: String = "string-formatted-backport"

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
                    if (opcode == Opcodes.INVOKEVIRTUAL && owner == STRING && methodName == "formatted" &&
                        methodDescriptor == "([Ljava/lang/Object;)Ljava/lang/String;"
                    ) {
                        super.visitMethodInsn(
                            Opcodes.INVOKESTATIC, STRING, "format",
                            "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;", false,
                        )
                        return
                    }
                    super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface)
                }
            }
        }
    }

    private companion object {
        const val STRING = "java/lang/String"

        /** The dexed libraries found calling it (scan of the release dex's method table, 2026-11-12). */
        val PREFIXES = listOf("io.modelcontextprotocol.", "tools.jackson.")
    }
}
