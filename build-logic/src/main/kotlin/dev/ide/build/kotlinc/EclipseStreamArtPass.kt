package dev.ide.build.kotlinc

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Backports the `java.io` stream methods that Android only added in API level 33, for the Eclipse jars dexed
 * into the app: `InputStream.readAllBytes()`, `readNBytes(int)`, `readNBytes(byte[], int, int)`,
 * `transferTo(OutputStream)`, `InputStream.nullInputStream()` and `OutputStream.nullOutputStream()`.
 *
 * All six are Java 9 or Java 11 methods. CodeAssist's `minSdk` is 26 and core-library desugaring does not
 * cover `java.io`, so on an API 26 to 32 device the calls throw [NoSuchMethodError]. Two bundled libraries
 * reach them:
 *
 *  * ecj hits `readAllBytes()` immediately: its `Parser.<clinit>` loads the parser tables via
 *    `Util.getInputStreamAsByteArray(InputStream)` (one `input.readAllBytes()` call), and the error surfaces
 *    as an [ExceptionInInitializerError] that disables all Java parsing/indexing/analysis on device.
 *  * JGit hits `readNBytes` on every repository open: `FileBasedConfig.load()` reads the config through
 *    `IO.readFully(File)`, and the wire protocol reads every packet line through
 *    `IO.readFully(InputStream, byte[], int, int)`, so `Git.init().call()` and `CloneCommand.call()` both die.
 *
 * The methods cannot be relocated by name like ecj's `Runtime$Version` (see buildSrc `RelocateTypesInJar`):
 * they are calls on `java.io.InputStream`, and a `java.*` type cannot be stubbed on ART. Instead this pass
 * rewrites the call sites: each `INVOKEVIRTUAL java/io/InputStream.readAllBytes ()[B` becomes
 * `INVOKESTATIC dev/ide/lang/jdt/compat/InputStreamCompat.readAllBytes (Ljava/io/InputStream;)[B`, and so on
 * for the rest, targeting a shim shipped in `:lang-jdt`. The receiver becomes the leading argument, so the
 * stack effect is identical and no other bytecode changes.
 *
 * The same members also reach the runtime as method references, which carry a `MethodHandle` in an
 * `invokedynamic`'s bootstrap arguments rather than an invoke instruction. JGit's `PatchApplier` passes
 * `InputStream::nullInputStream` that way, and D8 turns it back into a real static call when it desugars the
 * lambda, so the handles are remapped too. A handle for an instance member becomes an `H_INVOKESTATIC` handle
 * on the shim: the receiver moves from the handle's receiver slot into its leading parameter, which leaves the
 * instantiated method type the metafactory sees unchanged.
 *
 * [RECEIVERS] lists the receiver types whose calls are rewritten. `java/io/InputStream` covers the vast
 * majority; the two JGit subclasses are there because javac emits the static receiver type as the call's
 * owner, so `IO.readFully(File, int)` calling `readNBytes` on a `SilentFileInputStream` local does not name
 * `java/io/InputStream` at all. The list is deliberately opt-in rather than "any receiver": rewriting a call
 * whose receiver is not an `InputStream` would produce bytecode that fails verification, and
 * `ByteBufferInputStream` and `PackObjectSizeIndexV1$IndexInputStreamReader` both declare their own
 * same-named methods that must keep being dispatched to. To extend it after a dependency upgrade, look for
 * call sites in the new jar whose owner is neither `java/io/InputStream` nor a class declaring its own
 * override.
 *
 * Scoped to `org.eclipse.` so only the relocated ecj / jdt.core / Eclipse-runtime / JGit jars are visited;
 * the rest of the app (which is compiled against the project's own API floor) is left untouched. Like the
 * Kotlin-compiler passes this rides AGP instrumentation (scope = ALL), so it reaches the dexed dependency
 * jars during the Android build.
 */
class EclipseStreamArtPass : ArtPatchPass {

    override val name: String = "eclipse-stream-backport"

    override fun handles(classFqn: String): Boolean = classFqn.startsWith("org.eclipse.")

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
                    val shim = shimFor(opcode == Opcodes.INVOKESTATIC, owner, methodName, methodDescriptor)
                    if (shim != null) {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM, shim.first, shim.second, false)
                        return
                    }
                    super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface)
                }

                override fun visitInvokeDynamicInsn(
                    callSiteName: String,
                    callSiteDescriptor: String,
                    bootstrapMethodHandle: Handle,
                    vararg bootstrapMethodArguments: Any?,
                ) {
                    val remapped = Array(bootstrapMethodArguments.size) { i ->
                        val argument = bootstrapMethodArguments[i]
                        if (argument is Handle) shimHandle(argument) else argument
                    }
                    super.visitInvokeDynamicInsn(callSiteName, callSiteDescriptor, bootstrapMethodHandle, *remapped)
                }
            }
        }

        /** The shim a method-reference handle should target, or [handle] itself when it is not one of ours. */
        private fun shimHandle(handle: Handle): Handle {
            val shim = shimFor(handle.tag == Opcodes.H_INVOKESTATIC, handle.owner, handle.name, handle.desc)
                ?: return handle
            return Handle(Opcodes.H_INVOKESTATIC, SHIM, shim.first, shim.second, false)
        }

        /** The shim's name and descriptor for one call target, or null when the pass does not rewrite it. */
        private fun shimFor(isStatic: Boolean, owner: String, name: String, descriptor: String): Pair<String, String>? =
            if (isStatic) STATIC["$owner.$name$descriptor"]
            else if (owner in RECEIVERS) INSTANCE["$name$descriptor"]
            else null
    }

    private companion object {
        const val INPUT_STREAM = "java/io/InputStream"
        const val OUTPUT_STREAM = "java/io/OutputStream"
        const val SHIM = "dev/ide/lang/jdt/compat/InputStreamCompat"

        /** Call owners rewritten by this pass: every one of these is an `InputStream` that inherits the method. */
        val RECEIVERS = setOf(
            INPUT_STREAM,
            // JGit's IO.readFully(File, int) / readSome(File, int) read through this subclass, and it
            // inherits readNBytes rather than overriding it.
            "org/eclipse/jgit/util/io/SilentFileInputStream",
            // JGit's PatchApplier hashes a blob through this subclass, which likewise inherits transferTo.
            "org/eclipse/jgit/patch/PatchApplier\$SHA1InputStream",
        )

        /** Instance calls, keyed by name + descriptor, mapped to the shim's name + descriptor. */
        val INSTANCE = mapOf(
            "readAllBytes()[B" to ("readAllBytes" to "(L$INPUT_STREAM;)[B"),
            "readNBytes(I)[B" to ("readNBytes" to "(L$INPUT_STREAM;I)[B"),
            "readNBytes([BII)I" to ("readNBytes" to "(L$INPUT_STREAM;[BII)I"),
            "transferTo(L$OUTPUT_STREAM;)J" to ("transferTo" to "(L$INPUT_STREAM;L$OUTPUT_STREAM;)J"),
        )

        /** Static factories, keyed by owner + name + descriptor. The shim mirrors both descriptors exactly. */
        val STATIC = mapOf(
            "$INPUT_STREAM.nullInputStream()L$INPUT_STREAM;" to ("nullInputStream" to "()L$INPUT_STREAM;"),
            "$OUTPUT_STREAM.nullOutputStream()L$OUTPUT_STREAM;" to ("nullOutputStream" to "()L$OUTPUT_STREAM;"),
        )
    }
}
