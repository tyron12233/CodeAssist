package dev.ide.core.plugins

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Finds the programs a plugin APK packages among its native libraries.
 *
 * A plugin ships a toolchain binary the way an Android app does, as `lib/<abi>/lib<name>.so`, and runs it from
 * the directory the installer extracts it to: since Android 10 nowhere in the app's own storage may be `exec`ed.
 * A JNI library is different: it is only ever mapped (`System.load`), which the app's storage still allows. So
 * whether a build needs an installed home for its libraries depends on whether it carries a program, and the
 * file name says nothing about that. The ELF header does.
 */
object PluginExecutables {

    /** The [abi] the plugin packages for this device, its `lib/<abi>/` entries, and a fingerprint of them. */
    data class Scan(val abi: String, val entries: List<String>, val executables: List<String>, val fingerprint: String)

    /**
     * Scans [apk]'s native libraries for the first of [abis] (the device's, in preference order) it packages.
     * Null when it packages none for this device, or packages only libraries, which need no installed home.
     */
    fun scan(apk: Path, abis: List<String>): Scan? = ZipFile(apk.toFile()).use { zip ->
        val all = zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("lib/") }.toList()
        val abi = abis.firstOrNull { abi -> all.any { it.name.startsWith("lib/$abi/") } } ?: return null
        val mine = all.filter { it.name.startsWith("lib/$abi/") && it.name.indexOf('/', "lib/$abi/".length) < 0 }
            .sortedBy { it.name }
        val executables = mine.filter { e ->
            zip.getInputStream(e).use { input -> isExecutable(input.readNBytes(HEADER_BYTES)) }
        }.map { it.name }
        if (executables.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        for (e in mine) digest.update("${e.name}:${e.crc}:${e.size}\n".toByteArray())
        Scan(abi, mine.map { it.name }, executables, digest.digest().joinToString("") { "%02x".format(it) })
    }

    /**
     * Whether [head], the first bytes of an ELF file, describes a program to run rather than a library to load:
     * an `ET_EXEC`, or an `ET_DYN` that names a program interpreter (a PIE executable, which is what the NDK
     * builds) or has an entry point (a static PIE; a linker leaves a shared library's entry at 0). Anything that
     * is not ELF is not a program.
     */
    fun isExecutable(head: ByteArray): Boolean {
        if (head.size < 52 || head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte() ||
            head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
        ) return false
        val is64 = head[4].toInt() == 2
        if (is64 && head.size < 64) return false
        val b = ByteBuffer.wrap(head).order(if (head[5].toInt() == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        when (b.getShort(16).toInt() and 0xffff) {
            ET_EXEC -> return true
            ET_DYN -> {}
            else -> return false
        }
        val entry = if (is64) b.getLong(24) else (b.getInt(24).toLong() and 0xffffffffL)
        val phoff = if (is64) b.getLong(32) else (b.getInt(28).toLong() and 0xffffffffL)
        val phentsize = b.getShort(if (is64) 54 else 42).toInt() and 0xffff
        val phnum = b.getShort(if (is64) 56 else 44).toInt() and 0xffff
        for (i in 0 until phnum) {
            val at = phoff + i.toLong() * phentsize
            if (phentsize < 4 || at < 0 || at + 4 > head.size) break
            if (b.getInt(at.toInt()) == PT_INTERP) return true
        }
        return entry != 0L
    }

    private const val ET_EXEC = 2
    private const val ET_DYN = 3
    private const val PT_INTERP = 3

    /** Enough of a file for its ELF header and, as linkers lay them out, its program headers. */
    private const val HEADER_BYTES = 4096
}
