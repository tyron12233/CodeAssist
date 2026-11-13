package dev.ide.build.kotlinc

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [R8CollectionArtPass] removes R8's two API 33 calls, and the rewritten methods still give the same results. */
class R8CollectionArtPassTest {

    private val pass = R8CollectionArtPass()

    @Test
    fun handlesR8Only() {
        assertTrue(pass.handles("com.android.tools.r8.shaking.Enqueuer"))
        assertFalse(pass.handles("com.example.Other"))
    }

    @Test
    fun neitherApi33CallSurvives() {
        val calls = callsIn(runPass(synthetic()))
        assertTrue(calls.none { "toArray(Ljava/util/function/IntFunction;)" in it }, calls.toString())
        assertTrue(calls.none { "ByteArrayOutputStream.toString(Ljava/nio/charset/Charset;)" in it }, calls.toString())
    }

    @Test
    fun theRewrittenMethodsGiveTheSameResults() {
        val out = runPass(synthetic())
        val loaded = object : ClassLoader(javaClass.classLoader) {
            fun define(): Class<*> = defineClass("com.android.tools.r8.Synthetic", out, 0, out.size)
        }.define()

        val array = loaded.getMethod("array", java.util.List::class.java, java.util.function.IntFunction::class.java)
            .invoke(null, listOf("a", "b"), java.util.function.IntFunction { n -> arrayOfNulls<String>(n) })
        assertTrue(array is Array<*> && array.isArrayOf<String>(), "the generator's array type is kept")
        assertEquals(listOf("a", "b"), (array as Array<*>).toList())

        val stream = java.io.ByteArrayOutputStream().apply { write("héllo".toByteArray(Charsets.UTF_8)) }
        assertEquals(
            "héllo",
            loaded.getMethod("text", java.io.ByteArrayOutputStream::class.java, java.nio.charset.Charset::class.java)
                .invoke(null, stream, Charsets.UTF_8),
        )
    }

    /** `Stream.toArray(IntFunction)` shares the name and descriptor, is on every supported device, and is not a
     *  `Collection`: rewriting it made R8 fail with `IncompatibleClassChangeError`. */
    @Test
    fun aStreamsToArrayIsLeftAlone() {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "com/android/tools/r8/Streams", null, "java/lang/Object", null)
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "array",
            "(Ljava/util/stream/Stream;Ljava/util/function/IntFunction;)[Ljava/lang/Object;", null, null,
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/stream/Stream", "toArray", "(Ljava/util/function/IntFunction;)[Ljava/lang/Object;", true)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        cw.visitEnd()
        assertEquals(
            listOf("java/util/stream/Stream.toArray(Ljava/util/function/IntFunction;)[Ljava/lang/Object;"),
            callsIn(runPass(cw.toByteArray())),
        )
    }

    private fun runPass(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(pass.visitor("com.android.tools.r8.Synthetic", writer), 0)
        return writer.toByteArray()
    }

    /**
     * `static Object[] array(List l, IntFunction g) { return l.toArray(g); }` and
     * `static String text(ByteArrayOutputStream o, Charset c) { return o.toString(c); }`, each with the exact
     * max stack javac gives it, so the pass has to raise the first one itself.
     */
    private fun synthetic(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "com/android/tools/r8/Synthetic", null, "java/lang/Object", null)
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "array",
            "(Ljava/util/List;Ljava/util/function/IntFunction;)[Ljava/lang/Object;", null, null,
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "toArray", "(Ljava/util/function/IntFunction;)[Ljava/lang/Object;", true)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "text",
            "(Ljava/io/ByteArrayOutputStream;Ljava/nio/charset/Charset;)Ljava/lang/String;", null, null,
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/ByteArrayOutputStream", "toString", "(Ljava/nio/charset/Charset;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun callsIn(bytes: ByteArray): List<String> {
        val calls = ArrayList<String>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        calls.add("$owner.$name$descriptor")
                    }
                }
        }, 0)
        return calls
    }
}
