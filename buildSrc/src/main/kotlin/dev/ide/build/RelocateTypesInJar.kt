package dev.ide.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Rewrites a jar, relocating one or more types (named by JVM internal name, e.g.
 * `java/lang/Runtime$Version`) to different internal names. The rewrite is surgical and
 * dependency-free: it happens entirely at the constant-pool level. For every `.class` entry, each
 * `CONSTANT_Utf8` constant — which is where class names, field/method descriptors and generic
 * signatures live — has the old internal name replaced with the new one. Every other constant-pool
 * entry, the code, the `StackMapTable`, attributes and the rest of the class file are copied
 * **verbatim**: they reference the pool by *index*, and indices are preserved (same count, same
 * order), so nothing else needs touching. In particular no stack-map regeneration — and therefore no
 * class-hierarchy resolution of the relocated target — is required, which a general ASM/Class-File
 * remap would otherwise force.
 *
 * The motivating use: Eclipse ecj's `org.eclipse.jdt.internal.compiler.parser.Parser` references
 * `java.lang.Runtime$Version` (to warn when the requested `-source` is newer than the compiler
 * supports). That class exists on the JVM but **not** on Android's ART (it is absent from android.jar
 * and the runtime) and cannot be stubbed because app classes may not live in `java.*`. On ART the
 * reference surfaces as an uncatchable [LinkageError] that disables editor analysis. Relocating ecj's
 * reference to a shim we ship (`dev.ide.lang.jdt.compat.RuntimeVersion`) removes the blocker.
 */
@CacheableTask
abstract class RelocateTypesInJar : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputJar: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    /**
     * Old internal name -> new internal name, e.g.
     * `"java/lang/Runtime$Version"` -> `"dev/ide/lang/jdt/compat/RuntimeVersion"`.
     * Names use `/` separators and `$` for nested types (JVM internal form), not dotted names.
     */
    @get:Input
    abstract val renames: MapProperty<String, String>

    @TaskAction
    fun relocate() {
        val mapping = renames.get().entries.map { (k, v) ->
            require(k.isNotEmpty()) { "rename key must not be empty" }
            k.toByteArray(Charsets.US_ASCII) to v.toByteArray(Charsets.US_ASCII)
        }
        require(mapping.isNotEmpty()) { "no renames configured for ${'$'}{this.path}" }

        val outFile = outputJar.get().asFile
        outFile.parentFile?.mkdirs()
        var patched = 0
        ZipFile(inputJar.get().asFile).use { zip ->
            ZipOutputStream(BufferedOutputStream(outFile.outputStream())).use { out ->
                // Stable, alphabetical entry order so the output is reproducible (cacheable).
                for (e in Collections.list(zip.entries()).sortedBy { it.name }) {
                    val name = e.name
                    // Jar signatures would be invalidated by any class rewrite, so drop them.
                    if (name.startsWith("META-INF/") &&
                        (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))
                    ) continue

                    val data = zip.getInputStream(e).use { it.readBytes() }
                    val bytes =
                        if (name.endsWith(".class")) relocateClass(data, mapping)?.also { patched++ } ?: data
                        else data

                    out.putNextEntry(ZipEntry(name).apply { time = FIXED_TIME })
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        logger.lifecycle("RelocateTypesInJar: patched $patched class(es) in ${outFile.name} ${renames.get()}")
        if (patched == 0) throw GradleException(
            "RelocateTypesInJar matched no classes for ${renames.get().keys} in ${inputJar.get().asFile.name}. " +
                "The dependency version may have changed — re-check which classes reference the relocated types."
        )
    }

    /** Returns rewritten class bytes, or null if no rename touched this class. */
    private fun relocateClass(orig: ByteArray, mapping: List<Pair<ByteArray, ByteArray>>): ByteArray? {
        fun u2(off: Int) = ((orig[off].toInt() and 0xff) shl 8) or (orig[off + 1].toInt() and 0xff)
        if (orig.size < 10 || u2(0) != 0xCAFE || u2(2) != 0xBABE) return null // not a class file

        val cpCount = u2(8)
        val out = ByteArrayOutputStream(orig.size + 64)
        out.write(orig, 0, 10) // magic + minor + major + constant_pool_count (all unchanged)
        var i = 10
        var idx = 1
        var changed = false
        while (idx < cpCount) {
            when (val tag = orig[i].toInt() and 0xff) {
                CONSTANT_Utf8 -> {
                    val len = u2(i + 1)
                    val start = i + 3
                    var payload = orig.copyOfRange(start, start + len)
                    for ((old, new) in mapping) {
                        val rep = replaceBytes(payload, old, new)
                        if (rep !== payload) { payload = rep; changed = true }
                    }
                    if (payload.size > 0xFFFF) throw GradleException("Relocated UTF-8 constant exceeds 65535 bytes")
                    out.write(CONSTANT_Utf8)
                    out.write((payload.size ushr 8) and 0xff)
                    out.write(payload.size and 0xff)
                    out.write(payload)
                    i = start + len
                    idx += 1
                }
                // u2 payload: Class, String, MethodType, Module, Package
                7, 8, 16, 19, 20 -> { out.write(orig, i, 3); i += 3; idx += 1 }
                // u1+u2 payload: MethodHandle
                15 -> { out.write(orig, i, 4); i += 4; idx += 1 }
                // u4 payload: Integer, Float, Fieldref, Methodref, InterfaceMethodref, NameAndType, Dynamic, InvokeDynamic
                3, 4, 9, 10, 11, 12, 17, 18 -> { out.write(orig, i, 5); i += 5; idx += 1 }
                // u8 payload, occupies two pool slots: Long, Double
                5, 6 -> { out.write(orig, i, 9); i += 9; idx += 2 }
                else -> throw GradleException("Unknown constant-pool tag $tag at offset $i")
            }
        }
        out.write(orig, i, orig.size - i) // everything after the constant pool, verbatim
        return if (changed) out.toByteArray() else null
    }

    /** Replace every non-overlapping occurrence of [old] bytes with [new]; returns [src] itself if none. */
    private fun replaceBytes(src: ByteArray, old: ByteArray, new: ByteArray): ByteArray {
        var i = indexOf(src, old, 0)
        if (i < 0) return src
        val out = ByteArrayOutputStream(src.size + 16)
        var pos = 0
        while (i >= 0) {
            out.write(src, pos, i - pos)
            out.write(new)
            pos = i + old.size
            i = indexOf(src, old, pos)
        }
        out.write(src, pos, src.size - pos)
        return out.toByteArray()
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return -1
        val last = hay.size - needle.size
        var i = from
        while (i <= last) {
            var j = 0
            while (j < needle.size && hay[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }

    private companion object {
        const val CONSTANT_Utf8 = 1
        // A fixed DOS timestamp (1980-01-01) keeps the output byte-for-byte reproducible.
        const val FIXED_TIME = 315532800000L
    }
}
