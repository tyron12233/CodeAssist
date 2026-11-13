package dev.ide.build.kotlinc

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Backports the two Java 11 methods R8 calls that Android only added in API 33, for the R8 a forked VM runs.
 *
 * That R8 is dexed on its own into an asset, with no core-library desugaring, so on Android 12 and older the
 * calls resolve to nothing. `Collection.toArray(IntFunction)` sits in the shrinker's set-up, so EVERY minified
 * build whose R8 ran in the forked VM failed there with `NoSuchMethodError`; a phone with too little RAM to
 * fork runs the app's own (desugared) copy of R8 and never saw it, which is how it went unnoticed.
 *
 *  - `c.toArray(generator)` becomes `c.toArray((Object[]) generator.apply(0))`, the method's own default body.
 *    That holds one more value on the stack than the call it replaces, so the method's max stack is raised.
 *  - `out.toString(charset)` becomes `charset.decode(ByteBuffer.wrap(out.toByteArray())).toString()`, which
 *    decodes exactly as `new String(bytes, charset)` does (malformed input replaced, not thrown).
 *
 * Both sites are plain invoke instructions here. A method reference to either would sit in an
 * `invokedynamic`'s bootstrap arguments and be missed; the scan of the built asset's method table is what
 * says there is none (see `R8CollectionArtPassTest` for the jar-level check).
 */
class R8CollectionArtPass : ArtPatchPass {

    override val name: String = "r8-java11-collection-backport"

    override fun handles(classFqn: String): Boolean = classFqn.startsWith("com.android.tools.r8.")

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
                private var deeper = false

                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    methodName: String,
                    methodDescriptor: String,
                    isInterface: Boolean,
                ) {
                    val instance = opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEVIRTUAL
                    if (instance && methodName == "toArray" && methodDescriptor == TO_ARRAY_GENERATOR && isCollection(owner)) {
                        // [collection, generator] -> [collection, Object[]] -> [Object[]]
                        super.visitInsn(Opcodes.ICONST_0)
                        super.visitMethodInsn(Opcodes.INVOKEINTERFACE, INT_FUNCTION, "apply", "(I)Ljava/lang/Object;", true)
                        super.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;")
                        super.visitMethodInsn(Opcodes.INVOKEINTERFACE, COLLECTION, "toArray", "([Ljava/lang/Object;)[Ljava/lang/Object;", true)
                        deeper = true
                        return
                    }
                    if (opcode == Opcodes.INVOKEVIRTUAL && owner == BYTE_STREAM && methodName == "toString" &&
                        methodDescriptor == "(L$CHARSET;)Ljava/lang/String;"
                    ) {
                        // [stream, charset] -> [charset, bytes] -> [charset, buffer] -> [chars] -> [string]
                        super.visitInsn(Opcodes.SWAP)
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BYTE_STREAM, "toByteArray", "()[B", false)
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, BYTE_BUFFER, "wrap", "([B)L$BYTE_BUFFER;", false)
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CHARSET, "decode", "(L$BYTE_BUFFER;)Ljava/nio/CharBuffer;", false)
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/nio/CharBuffer", "toString", "()Ljava/lang/String;", false)
                        return
                    }
                    super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface)
                }

                override fun visitMaxs(maxStack: Int, maxLocals: Int) {
                    super.visitMaxs(if (deeper) maxStack + 1 else maxStack, maxLocals)
                }
            }
        }
    }

    private companion object {
        /**
         * `Stream.toArray(IntFunction)` has the same name and erased descriptor, exists since API 24 and must be
         * left alone: rewritten, it fails with `IncompatibleClassChangeError` (a stream is no `Collection`). R8
         * calls the method on `java.util.List` and on `java.util.stream.Stream` only (method table of the asset).
         */
        fun isCollection(owner: String): Boolean = owner.startsWith("java/util/") && !owner.startsWith("java/util/stream/")

        const val TO_ARRAY_GENERATOR = "(Ljava/util/function/IntFunction;)[Ljava/lang/Object;"
        const val INT_FUNCTION = "java/util/function/IntFunction"
        const val COLLECTION = "java/util/Collection"
        const val BYTE_STREAM = "java/io/ByteArrayOutputStream"
        const val CHARSET = "java/nio/charset/Charset"
        const val BYTE_BUFFER = "java/nio/ByteBuffer"
    }
}
