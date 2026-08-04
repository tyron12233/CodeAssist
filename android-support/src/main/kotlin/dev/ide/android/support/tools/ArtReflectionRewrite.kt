package dev.ide.android.support.tools

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Rewrites classpath jars to drop calls to JDK reflection methods that ART lacks, so processor/plugin code
 * that is D8-dexed and run through a `DexClassLoader` on device (see `ArtKotlinPluginLoader`) doesn't die with
 * a `NoSuchMethodError`. This is the runtime-dex counterpart to the build-time `ArtPatchPass` framework: those
 * passes only reach classes dexed INTO the app at build time, whereas a KSP processor's jars are extracted +
 * dexed at runtime, so they need their own ART fix-up here.
 *
 * The only method handled today is `java.lang.reflect.AccessibleObject.trySetAccessible()` — a JDK-9 API that
 * is absent on many ART versions. androidx.room's XProcessing (`KSTypeJavaPoetExt`) calls it on a private
 * JavaPoet constructor, which crashes Room's KSP processor on device with:
 * `NoSuchMethodError: No virtual method trySetAccessible()Z in class Ljava/lang/reflect/Constructor;`.
 *
 * Each `INVOKEVIRTUAL <reflectType>.trySetAccessible ()Z` is replaced INLINE with the ART-present
 * `setAccessible(true)` followed by a pushed `true` (the boolean result `trySetAccessible` would have
 * returned). The rewrite references only methods that resolve against `android.jar`, so no shim class has to
 * ride on the dexed classpath. (`trySetAccessible` swallows an inaccessible-object failure and returns false;
 * on ART there is no module system to make a non-platform constructor inaccessible, so an unconditional
 * `setAccessible(true)` is the faithful equivalent.)
 *
 * Only the ART loaders route through this. The desktop loaders leave jars untouched — a real JVM has
 * `trySetAccessible`, so `DefaultKspProcessorLoader` / `DefaultKotlinPluginLoader` load the original bytes.
 */
object ArtReflectionRewrite {

    private const val METHOD = "trySetAccessible"
    private val METHOD_BYTES = METHOD.toByteArray(Charsets.UTF_8)

    // `setAccessible(boolean)` is declared on AccessibleObject but callable via any subclass owner, so we emit
    // the replacement with the SAME owner the original `trySetAccessible` call used — a valid inherited-method
    // invokevirtual on all of these.
    private val REFLECT_OWNERS = setOf(
        "java/lang/reflect/AccessibleObject",
        "java/lang/reflect/Constructor",
        "java/lang/reflect/Method",
        "java/lang/reflect/Field",
    )

    /**
     * Return an ART-safe classpath equivalent to [jars]: a jar that references [METHOD] is rewritten into
     * [outDir] (same file name) with the call patched out; a jar that doesn't reference it is passed through
     * as its ORIGINAL path (no copy). [outDir] is created on demand and only ever holds rewritten jars.
     */
    fun patch(jars: List<Path>, outDir: Path): List<Path> =
        jars.map { jar -> if (references(jar)) rewriteInto(jar, outDir) else jar }

    /** True if any `.class` entry of [jar] mentions [METHOD] in its (decompressed) constant pool. */
    private fun references(jar: Path): Boolean {
        ZipInputStream(BufferedInputStream(Files.newInputStream(jar))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".class") && containsBytes(zis.readBytes(), METHOD_BYTES)) {
                    return true
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return false
    }

    /** Stream [jar] into [outDir]/<name>, rewriting each `.class` entry that references [METHOD]; everything
     *  else is copied byte-for-byte. */
    private fun rewriteInto(jar: Path, outDir: Path): Path {
        Files.createDirectories(outDir)
        val out = outDir.resolve(jar.fileName.toString())
        ZipInputStream(BufferedInputStream(Files.newInputStream(jar))).use { zis ->
            ZipOutputStream(BufferedOutputStream(Files.newOutputStream(out))).use { zos ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val content = zis.readBytes()
                        val bytes =
                            if (entry.name.endsWith(".class") && containsBytes(content, METHOD_BYTES))
                                rewriteClass(content)
                            else content
                        zos.putNextEntry(ZipEntry(entry.name)) // fresh entry: CRC/size/compression recomputed
                        zos.write(bytes)
                        zos.closeEntry()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return out
    }

    private fun rewriteClass(bytes: ByteArray): ByteArray {
        // COMPUTE_MAXS only (not COMPUTE_FRAMES): the inlined `iconst_1` transiently deepens the stack by one,
        // so maxStack may need to grow, but the rewrite adds no branches/labels — existing stack-map frames stay
        // valid — and COMPUTE_FRAMES would need a class hierarchy we can't resolve for arbitrary processor jars.
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        ClassReader(bytes).accept(Rewriter(writer), 0)
        return writer.toByteArray()
    }

    private class Rewriter(next: ClassVisitor) : ClassVisitor(Opcodes.ASM9, next) {
        override fun visitMethod(
            access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?,
        ): MethodVisitor? {
            val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
            return object : MethodVisitor(Opcodes.ASM9, mv) {
                override fun visitMethodInsn(
                    opcode: Int, owner: String?, mName: String?, mDesc: String?, isInterface: Boolean,
                ) {
                    if (opcode == Opcodes.INVOKEVIRTUAL && mName == METHOD && mDesc == "()Z" && owner in REFLECT_OWNERS) {
                        // The receiver (an AccessibleObject subtype) is already on the stack. Replace the call
                        // with `setAccessible(true)` and leave `true` as its result — same net stack effect.
                        super.visitInsn(Opcodes.ICONST_1)
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "setAccessible", "(Z)V", false)
                        super.visitInsn(Opcodes.ICONST_1)
                        return
                    }
                    super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface)
                }
            }
        }
    }

    /** Naive substring scan — fine for a one-shot per-class gate on a short [needle]. */
    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
