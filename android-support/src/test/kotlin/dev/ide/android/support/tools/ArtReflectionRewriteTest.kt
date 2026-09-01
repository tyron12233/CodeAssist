package dev.ide.android.support.tools

import dev.ide.testkit.withTempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.lang.reflect.Constructor
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [ArtReflectionRewrite] — patches out `AccessibleObject.trySetAccessible()` (JDK-9, absent on ART) from
 * processor/plugin jars before they are dexed for a device `DexClassLoader`, replacing it with the ART-present
 * `setAccessible(true)`. A jar that never calls it must be returned untouched (never re-jarred).
 */
class ArtReflectionRewriteTest {

    /** A class in the UNNAMED module whose PRIVATE constructor `setAccessible(true)` can legally open (so the
     *  rewritten call succeeds on the desktop JVM, unlike a JDK-module class it would be blocked from). */
    private class Target private constructor()

    /** `public static boolean call(java.lang.reflect.Constructor c) { return c.trySetAccessible(); }` — the
     *  exact shape XProcessing's `KSTypeJavaPoetExt` uses on a private JavaPoet constructor. */
    private fun probeClassBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, PROBE, null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "call", "(Ljava/lang/reflect/Constructor;)Z", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Constructor", "trySetAccessible", "()Z", false)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** `public static int noop() { return 1; }` — never calls trySetAccessible. */
    private fun plainClassBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Plain", null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "noop", "()I", null, null)
        mv.visitCode()
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(1, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun jarOf(dir: Path, name: String, entries: Map<String, ByteArray>): Path {
        val jar = dir.resolve(name)
        JarOutputStream(Files.newOutputStream(jar)).use { jos ->
            for ((entry, bytes) in entries) {
                jos.putNextEntry(JarEntry(entry)); jos.write(bytes); jos.closeEntry()
            }
        }
        return jar
    }

    private fun classEntry(jar: Path, name: String): ByteArray =
        ZipFile(jar.toFile()).use { zf -> zf.getInputStream(zf.getEntry(name)).use { it.readBytes() } }

    /** Names of the reflection methods this class invokes via INVOKEVIRTUAL. */
    private fun invokedReflectionMethods(bytes: ByteArray): List<Pair<String, String>> {
        val calls = mutableListOf<Pair<String, String>>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(a: Int, n: String?, d: String?, s: String?, e: Array<out String>?): MethodVisitor =
                object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(op: Int, owner: String?, mn: String?, md: String?, itf: Boolean) {
                        if (op == Opcodes.INVOKEVIRTUAL && owner?.startsWith("java/lang/reflect/") == true) calls += (mn!! to md!!)
                    }
                }
        }, 0)
        return calls
    }

    @Test
    fun rewritesTrySetAccessibleToSetAccessibleAndTheRewrittenClassRunsOnAJvmWithoutTrySetAccessible() {
        withTempDir("art-reflect") { tmp ->
            val src = jarOf(tmp, "xprocessing.jar", mapOf("$PROBE.class" to probeClassBytes()))
            val outDir = tmp.resolve("art-safe")

            val patched = ArtReflectionRewrite.patch(listOf(src), outDir)
            assertEquals(1, patched.size)
            assertNotEquals(src, patched[0], "a jar that calls trySetAccessible is rewritten to a new path")
            assertEquals(outDir.resolve("0-xprocessing.jar"), patched[0], "rewritten under its classpath index")

            val rewritten = classEntry(patched[0], "$PROBE.class")
            val calls = invokedReflectionMethods(rewritten)
            assertFalse(calls.any { it.first == "trySetAccessible" }, "trySetAccessible must be gone: $calls")
            assertTrue(calls.contains("setAccessible" to "(Z)V"), "must call setAccessible(boolean): $calls")

            // The rewrite must produce verifiable bytecode that runs on a JVM that lacks trySetAccessible: load
            // the class and drive it through a PRIVATE constructor it should open (proving setAccessible(true) ran).
            val cls = object : ClassLoader(javaClass.classLoader) {
                fun define(b: ByteArray): Class<*> = defineClass(PROBE, b, 0, b.size)
            }.define(rewritten)
            val ctor: Constructor<*> = Target::class.java.declaredConstructors.first()
            assertFalse(ctor.isAccessible, "fixture ctor starts inaccessible")
            val result = cls.getMethod("call", Constructor::class.java).invoke(null, ctor) as Boolean
            assertTrue(result, "the rewritten call returns true (trySetAccessible's success value)")
            assertTrue(ctor.isAccessible, "setAccessible(true) actually ran against the constructor")
        }
    }

    @Test
    fun aJarThatNeverCallsTrySetAccessibleIsReturnedUnchanged() {
        withTempDir("art-reflect-plain") { tmp ->
            val src = jarOf(tmp, "plain.jar", mapOf("Plain.class" to plainClassBytes(), "META-INF/x.txt" to "hi".toByteArray()))
            val outDir = tmp.resolve("art-safe")

            val patched = ArtReflectionRewrite.patch(listOf(src), outDir)
            assertSame(src, patched[0], "a jar with no trySetAccessible call is passed through as its original path")
            assertFalse(Files.exists(outDir), "no rewritten copy is written for a jar that needs no patch")
        }
    }

    /**
     * A tool classpath can hold two SAME-NAMED jars: a module activating two bundled KSP processors gets each
     * closure's own `annotations-13.0.jar`, `jsr305-3.0.2.jar`, and so on. Both rewritten to `outDir/<name>`,
     * one would silently overwrite the other and the classpath would carry its classes twice under the second
     * entry's identity, so the destination is keyed by classpath index.
     */
    @Test
    fun twoSameNamedJarsDoNotOverwriteEachOthersRewrite() {
        withTempDir("art-reflect-collide") { tmp ->
            val a = jarOf(Files.createDirectories(tmp.resolve("a")), "shared.jar", mapOf("$PROBE.class" to probeClassBytes()))
            val b = jarOf(Files.createDirectories(tmp.resolve("b")), "shared.jar", mapOf("Other.class" to probeClassBytes()))
            val outDir = tmp.resolve("art-safe")

            val patched = ArtReflectionRewrite.patch(listOf(a, b), outDir)

            assertEquals(2, patched.size)
            assertNotEquals(patched[0], patched[1], "same-named jars must rewrite to distinct paths")
            // Each rewritten jar still carries ITS OWN class, not the other's.
            assertNotNull(zipEntryOrNull(patched[0], "$PROBE.class"), "first jar keeps its own entry")
            assertNotNull(zipEntryOrNull(patched[1], "Other.class"), "second jar keeps its own entry")
            assertNull(zipEntryOrNull(patched[0], "Other.class"), "no cross-contamination")
        }
    }

    private fun zipEntryOrNull(jar: Path, name: String): ByteArray? =
        ZipFile(jar.toFile()).use { zf -> zf.getEntry(name)?.let { e -> zf.getInputStream(e).use { it.readBytes() } } }

    private companion object {
        const val PROBE = "Probe"
    }
}
