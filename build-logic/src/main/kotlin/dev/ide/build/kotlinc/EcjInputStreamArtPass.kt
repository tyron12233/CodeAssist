package dev.ide.build.kotlinc

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Backports [java.io.InputStream.readAllBytes] / [java.io.InputStream.readNBytes] for the Eclipse classes
 * dexed into the app.
 *
 * Both are Java 9 `InputStream` methods that on Android only exist from API 33. CodeAssist's `minSdk` is 26
 * with core-library desugaring off, so on an API 26 to 32 device the calls throw [NoSuchMethodError]. ecj
 * hits `readAllBytes()` immediately: its `Parser.<clinit>` loads the parser tables via
 * `Util.getInputStreamAsByteArray(InputStream)` (one `input.readAllBytes()` call), and the error surfaces as
 * an [ExceptionInInitializerError] that disables all Java parsing/indexing/analysis on device.
 *
 * The two methods cannot be relocated by name like ecj's `Runtime$Version` (see buildSrc `RelocateTypesInJar`):
 * they are instance calls on `java.io.InputStream`, and a `java.*` type cannot be stubbed on ART. Instead this
 * pass rewrites the call sites: each `INVOKEVIRTUAL java/io/InputStream.readAllBytes ()[B` becomes
 * `INVOKESTATIC dev/ide/lang/jdt/compat/InputStreamCompat.readAllBytes (Ljava/io/InputStream;)[B` (and the
 * matching `readNBytes (I)[B`), targeting a shim shipped in `:lang-jdt`. The receiver becomes the leading
 * argument, so the stack effect is identical and no other bytecode changes. The shim reads the stream with
 * the API-1 `read(byte[])` primitives.
 *
 * Scoped to `org.eclipse.` so only the relocated ecj / jdt.core / Eclipse-runtime jars are visited; the rest
 * of the app (which is compiled against the project's own API floor) is left untouched. Like the
 * Kotlin-compiler passes this rides AGP instrumentation (scope = ALL), so it reaches the dexed dependency
 * jars during the Android build.
 */
class EcjInputStreamArtPass : ArtPatchPass {

    override val name: String = "ecj-inputstream-read-bytes"

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
                    if (opcode == Opcodes.INVOKEVIRTUAL && owner == INPUT_STREAM) {
                        if (methodName == "readAllBytes" && methodDescriptor == "()[B") {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM, "readAllBytes", "(L$INPUT_STREAM;)[B", false)
                            return
                        }
                        if (methodName == "readNBytes" && methodDescriptor == "(I)[B") {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM, "readNBytes", "(L$INPUT_STREAM;I)[B", false)
                            return
                        }
                    }
                    super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface)
                }
            }
        }
    }

    private companion object {
        const val INPUT_STREAM = "java/io/InputStream"
        const val SHIM = "dev/ide/lang/jdt/compat/InputStreamCompat"
    }
}
