package dev.ide.build.kotlinc

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [EclipseStreamArtPass] must route every API 33 `java.io` stream call in the bundled Eclipse jars through the
 * [InputStreamCompat] shim, receiver first, while leaving same-named calls on unrelated owners alone.
 * Verified purely in bytecode against synthetic classes shaped like the real ecj and JGit call sites.
 */
class EclipseStreamArtPassTest {

    private val pass = EclipseStreamArtPass()
    private val caller = "org.eclipse.jgit.util.IO"
    private val callerInternal = "org/eclipse/jgit/util/IO"
    private val shim = "dev/ide/lang/jdt/compat/InputStreamCompat"
    private val inputStream = "java/io/InputStream"
    private val outputStream = "java/io/OutputStream"
    private val silent = "org/eclipse/jgit/util/io/SilentFileInputStream"
    private val sha1 = "org/eclipse/jgit/patch/PatchApplier\$SHA1InputStream"

    @Test
    fun handlesTheEclipseJarsOnly() {
        assertTrue(pass.handles("org.eclipse.jgit.util.IO"))
        assertTrue(pass.handles("org.eclipse.jdt.internal.compiler.util.Util"))
        assertFalse(pass.handles("dev.ide.core.backend.VcsBackend"))
        assertFalse(pass.handles("java.io.InputStream"))
    }

    @Test
    fun rewritesEveryApi33StreamCallOnAnInputStreamReceiver() {
        val out = runPass(
            synthetic(
                Call(Opcodes.INVOKEVIRTUAL, inputStream, "readAllBytes", "()[B"),
                Call(Opcodes.INVOKEVIRTUAL, inputStream, "readNBytes", "(I)[B"),
                Call(Opcodes.INVOKEVIRTUAL, inputStream, "readNBytes", "([BII)I"),
                Call(Opcodes.INVOKEVIRTUAL, inputStream, "transferTo", "(L$outputStream;)J"),
                Call(Opcodes.INVOKESTATIC, inputStream, "nullInputStream", "()L$inputStream;"),
                Call(Opcodes.INVOKESTATIC, outputStream, "nullOutputStream", "()L$outputStream;"),
            ),
        )

        assertEquals(
            listOf(
                Call(Opcodes.INVOKESTATIC, shim, "readAllBytes", "(L$inputStream;)[B"),
                Call(Opcodes.INVOKESTATIC, shim, "readNBytes", "(L$inputStream;I)[B"),
                Call(Opcodes.INVOKESTATIC, shim, "readNBytes", "(L$inputStream;[BII)I"),
                Call(Opcodes.INVOKESTATIC, shim, "transferTo", "(L$inputStream;L$outputStream;)J"),
                Call(Opcodes.INVOKESTATIC, shim, "nullInputStream", "()L$inputStream;"),
                Call(Opcodes.INVOKESTATIC, shim, "nullOutputStream", "()L$outputStream;"),
            ),
            callsIn(out),
        )
    }

    @Test
    fun rewritesTheJGitSubclassReceiversJavacEmits() {
        // IO.readFully(File, int) and readSome(File, int) call readNBytes on a SilentFileInputStream local,
        // and PatchApplier calls transferTo on a SHA1InputStream: the owner is the subclass, not InputStream.
        val out = runPass(
            synthetic(
                Call(Opcodes.INVOKEVIRTUAL, silent, "readNBytes", "(I)[B"),
                Call(Opcodes.INVOKEVIRTUAL, sha1, "transferTo", "(L$outputStream;)J"),
            ),
        )

        assertEquals(
            listOf(
                Call(Opcodes.INVOKESTATIC, shim, "readNBytes", "(L$inputStream;I)[B"),
                Call(Opcodes.INVOKESTATIC, shim, "transferTo", "(L$inputStream;L$outputStream;)J"),
            ),
            callsIn(out),
        )
    }

    @Test
    fun leavesSameNamedCallsOnOtherOwnersAlone() {
        // ByteBufferInputStream declares its own readNBytes overrides, IndexInputStreamReader is not an
        // InputStream at all, and IFile.readAllBytes is unrelated Eclipse API: all must dispatch as written.
        val untouched = listOf(
            Call(Opcodes.INVOKEVIRTUAL, "org/eclipse/jgit/util/io/ByteBufferInputStream", "readNBytes", "(I)[B"),
            Call(
                Opcodes.INVOKEVIRTUAL,
                "org/eclipse/jgit/internal/storage/file/PackObjectSizeIndexV1\$IndexInputStreamReader",
                "readNBytes",
                "(I)[B",
            ),
            Call(Opcodes.INVOKEINTERFACE, "org/eclipse/core/resources/IFile", "readAllBytes", "()[B"),
            Call(Opcodes.INVOKEVIRTUAL, inputStream, "read", "([B)I"),
        )

        assertEquals(untouched, callsIn(runPass(synthetic(*untouched.toTypedArray()))))
    }

    @Test
    fun rewritesTheMethodHandleBehindAMethodReference() {
        // JGit's PatchApplier passes InputStream::nullInputStream as a supplier: the target lives in the
        // invokedynamic's bootstrap arguments, and D8 turns it back into a static call when it desugars.
        val out = runPass(syntheticMethodReference())

        assertEquals(
            listOf(Call(Opcodes.H_INVOKESTATIC, shim, "nullInputStream", "()L$inputStream;")),
            callsIn(out),
        )
    }

    @Test
    fun leavesNoApi33StreamCallSiteInTheJGitJar() {
        val jar = JarFile(jgitJar())
        val before = ArrayList<Call>()
        val after = ArrayList<Call>()
        jar.use {
            for (entry in it.entries()) {
                if (!entry.name.endsWith(".class")) continue
                val bytes = it.getInputStream(entry).use { stream -> stream.readBytes() }
                val fqn = entry.name.removeSuffix(".class").replace('/', '.')
                if (!pass.handles(fqn)) continue
                before += callsIn(bytes).filter(::isApi33StreamCall)
                after += callsIn(runPass(bytes)).filter(::isApi33StreamCall)
            }
        }

        assertTrue(before.isNotEmpty(), "the jar under test must contain the call sites the pass rewrites")
        assertEquals(emptyList(), after, "every API 33 stream call site in JGit must be routed through the shim")
    }

    /** A call ART cannot resolve below API 33: one of the six members, on a receiver that inherits it. */
    private fun isApi33StreamCall(call: Call): Boolean = when {
        call.owner == outputStream -> call.name == "nullOutputStream"
        call.owner != inputStream && call.owner != silent && call.owner != sha1 -> false
        else -> call.name == "readAllBytes" || call.name == "readNBytes" ||
            call.name == "transferTo" || call.name == "nullInputStream"
    }

    private fun jgitJar(): java.io.File {
        val source = Class.forName("org.eclipse.jgit.util.IO").protectionDomain.codeSource
        return java.io.File(source.location.toURI())
    }

    private fun runPass(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(pass.visitor(caller, writer), 0)
        return writer.toByteArray()
    }

    private data class Call(val opcode: Int, val owner: String, val name: String, val descriptor: String)

    /**
     * A class with one method holding [calls] back to back. Only structurally parseable, not verifiable:
     * the pass rewrites instructions without reading the operand stack, so that is enough.
     */
    private fun synthetic(vararg calls: Call): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, callerInternal, null, "java/lang/Object", null)
        cw.visitMethod(Opcodes.ACC_STATIC, "callSites", "()V", null, null).apply {
            visitCode()
            for (call in calls) {
                visitMethodInsn(
                    call.opcode,
                    call.owner,
                    call.name,
                    call.descriptor,
                    call.opcode == Opcodes.INVOKEINTERFACE,
                )
            }
            visitInsn(Opcodes.RETURN)
            visitMaxs(8, 8)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    /**
     * The call sites in [bytes], counting the method handles an `invokedynamic` carries. A method reference
     * such as `InputStream::nullInputStream` compiles to a handle in the bootstrap arguments rather than to an
     * invoke instruction, and reaches the runtime as a real call once the lambda is desugared.
     */
    /** A class whose single method captures `InputStream::nullInputStream` through `LambdaMetafactory`. */
    private fun syntheticMethodReference(): ByteArray {
        val metafactory = Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory",
            "metafactory",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                "Ljava/lang/invoke/CallSite;",
            false,
        )
        val target = Handle(Opcodes.H_INVOKESTATIC, inputStream, "nullInputStream", "()L$inputStream;", false)
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, callerInternal, null, "java/lang/Object", null)
        cw.visitMethod(Opcodes.ACC_STATIC, "supplier", "()Ljava/util/function/Supplier;", null, null).apply {
            visitCode()
            visitInvokeDynamicInsn(
                "get",
                "()Ljava/util/function/Supplier;",
                metafactory,
                Type.getType("()Ljava/lang/Object;"),
                target,
                Type.getType("()L$inputStream;"),
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun callsIn(bytes: ByteArray): List<Call> {
        val out = ArrayList<Call>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(a: Int, n: String?, d: String?, s: String?, e: Array<out String>?): MethodVisitor =
                object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(op: Int, owner: String, name: String, desc: String, itf: Boolean) {
                        out.add(Call(op, owner, name, desc))
                    }

                    override fun visitInvokeDynamicInsn(n: String, d: String, bsm: Handle, vararg args: Any?) {
                        for (arg in args) {
                            if (arg is Handle) out.add(Call(arg.tag, arg.owner, arg.name, arg.desc))
                        }
                    }
                }
        }, 0)
        return out
    }
}
