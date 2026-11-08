package dev.ide.core.plugins

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Finds the programs a plugin APK packages among its native libraries.
 *
 * A plugin ships a toolchain binary the way an Android app does, as `lib/<abi>/lib<name>.so`, and runs it from
 * the directory the installer extracts it to: since Android 10 nowhere in the app's own storage may be `exec`ed.
 * A JNI library is different: it is only ever mapped (`System.load`), which the app's storage still allows. So
 * whether a never-installed build (the plugin sandbox's) can run what it packages depends on whether it carries
 * a program, and of which kind, and the file name says nothing about that. The ELF header does: a
 * position-independent program can be loaded by the system linker from anywhere ([isLinkerLoadable]), a static
 * one at a fixed address cannot.
 */
object PluginExecutables {

    /**
     * The [abi] the plugin packages for this device, its `lib/<abi>/` entries, the programs among them, and those
     * of the programs the system linker cannot load ([isLinkerLoadable]).
     */
    data class Scan(val abi: String, val entries: List<String>, val executables: List<String>, val static: List<String>)

    /**
     * Scans [apk]'s native libraries for the first of [abis] (the device's, in preference order) it packages.
     * Null when it packages none for this device, or packages only libraries, which load from anywhere.
     */
    fun scan(apk: Path, abis: List<String>): Scan? = ZipFile(apk.toFile()).use { zip ->
        val all = zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("lib/") }.toList()
        val abi = abis.firstOrNull { abi -> all.any { it.name.startsWith("lib/$abi/") } } ?: return null
        val mine = all.filter { it.name.startsWith("lib/$abi/") && it.name.indexOf('/', "lib/$abi/".length) < 0 }
            .sortedBy { it.name }
        val heads = mine.associate { e -> e.name to zip.getInputStream(e).use { readUpTo(it, HEADER_BYTES) } }
        val executables = mine.map { it.name }.filter { isExecutable(heads.getValue(it)) }
        if (executables.isEmpty()) return null
        Scan(abi, mine.map { it.name }, executables, executables.filter { !isLinkerLoadable(heads.getValue(it)) })
    }

    /** The start of a file, as much of it as [isExecutable] and [isLinkerLoadable] read. */
    fun head(input: java.io.InputStream): ByteArray = readUpTo(input, HEADER_BYTES)

    /** Up to [limit] bytes from the start of [input]. (`InputStream.readNBytes` is API 33; the app's floor is 26.) */
    private fun readUpTo(input: java.io.InputStream, limit: Int): ByteArray {
        val buf = ByteArray(limit)
        var n = 0
        while (n < limit) {
            val r = input.read(buf, n, limit - n)
            if (r < 0) break
            n += r
        }
        return if (n == limit) buf else buf.copyOf(n)
    }

    /**
     * Whether [head], the first bytes of an ELF file, describes a program to run rather than a library to load:
     * an `ET_EXEC`, or an `ET_DYN` that names a program interpreter (a PIE executable, which is what the NDK
     * builds) or has an entry point (a static PIE; a linker leaves a shared library's entry at 0). Anything that
     * is not ELF is not a program.
     */
    fun isExecutable(head: ByteArray): Boolean = kind(head).let { it == EXEC || it == PIE || it == STATIC_PIE }

    /**
     * Whether [head] is a program the system linker can load (`/system/bin/linker64 <file>`, which is how a file
     * in the app's own storage runs on Android 10+): an `ET_DYN` that names a program interpreter, which is what
     * the NDK builds. A static program (`ET_EXEC`, or a static PIE, which relocates itself and has no
     * `PT_INTERP`) runs only when the kernel execs it, so only from an installed app.
     */
    fun isLinkerLoadable(head: ByteArray): Boolean = kind(head) == PIE

    private fun kind(head: ByteArray): Int {
        if (head.size < 52 || head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte() ||
            head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
        ) return NONE
        val is64 = head[4].toInt() == 2
        if (is64 && head.size < 64) return NONE
        val b = ByteBuffer.wrap(head).order(if (head[5].toInt() == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        when (b.getShort(16).toInt() and 0xffff) {
            ET_EXEC -> return EXEC
            ET_DYN -> {}
            else -> return NONE
        }
        val entry = if (is64) b.getLong(24) else (b.getInt(24).toLong() and 0xffffffffL)
        val phoff = if (is64) b.getLong(32) else (b.getInt(28).toLong() and 0xffffffffL)
        val phentsize = b.getShort(if (is64) 54 else 42).toInt() and 0xffff
        val phnum = b.getShort(if (is64) 56 else 44).toInt() and 0xffff
        for (i in 0 until phnum) {
            val at = phoff + i.toLong() * phentsize
            if (phentsize < 4 || at < 0 || at + 4 > head.size) break
            if (b.getInt(at.toInt()) == PT_INTERP) return PIE
        }
        return if (entry != 0L) STATIC_PIE else NONE
    }

    private const val NONE = 0
    private const val EXEC = 1
    private const val PIE = 2
    private const val STATIC_PIE = 3

    private const val ET_EXEC = 2
    private const val ET_DYN = 3
    private const val PT_INTERP = 3

    /** Enough of a file for its ELF header and, as linkers lay them out, its program headers. */
    private const val HEADER_BYTES = 4096
}
