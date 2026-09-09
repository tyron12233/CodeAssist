package dev.ide.android.support.aidl

import org.objectweb.asm.ClassReader
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Answers "is this class a Parcelable or an AIDL interface?" by reading bytecode off the compile classpath.
 *
 * The Android SDK ships that answer for framework types in `platforms/android-NN/framework.aidl`, a
 * preprocessed list of `parcelable android.os.Bundle;` lines. On-device there is no such file, since
 * `android.jar` arrives as a bundled asset on its own, so the same question is answered from the jar: a class
 * that reaches `android.os.Parcelable` through its supertypes is a parcelable, one that reaches
 * `android.os.IInterface` is an AIDL interface. Only names an actual `.aidl` file mentions are ever probed,
 * and every answer is memoised by [AidlTypeTable], so this stays a handful of central-directory lookups.
 *
 * Entries are opened lazily and held until [close]; a build task wraps its use in `use { }`.
 */
class AidlClasspathProbe private constructor(private val entries: List<Path>) : Closeable {

    private val zips = HashMap<Path, ZipFile?>()

    /** [AidlTypeKind] of [fqName], or null when the class is absent or neither parcelable nor AIDL interface. */
    fun classify(fqName: String): AidlTypeKind? {
        if (entries.isEmpty()) return null
        return classify(fqName.replace('.', '/'), HashSet())
    }

    private fun classify(internalName: String, seen: MutableSet<String>): AidlTypeKind? {
        if (!seen.add(internalName)) return null
        when (internalName) {
            PARCELABLE -> return AidlTypeKind.PARCELABLE
            IINTERFACE -> return AidlTypeKind.INTERFACE
        }
        val reader = read(internalName) ?: return null
        // Interfaces first: an AIDL interface extends IInterface, and nothing extends both.
        for (itf in reader.interfaces) classify(itf, seen)?.let { return it }
        return reader.superName?.let { classify(it, seen) }
    }

    private fun read(internalName: String): ClassReader? {
        val relative = "$internalName.class"
        for (entry in entries) {
            val bytes = when {
                Files.isDirectory(entry) -> entry.resolve(relative).takeIf { Files.isRegularFile(it) }?.let(Files::readAllBytes)
                else -> zipOf(entry)?.let { zip ->
                    zip.getEntry(relative)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
                }
            } ?: continue
            return runCatching { ClassReader(bytes) }.getOrNull()
        }
        return null
    }

    /** The opened [ZipFile] for [path], or null when it is missing or not a zip (cached either way). */
    private fun zipOf(path: Path): ZipFile? = zips.getOrPut(path) {
        if (!Files.isRegularFile(path)) null else runCatching { ZipFile(path.toFile()) }.getOrNull()
    }

    override fun close() {
        for (zip in zips.values) runCatching { zip?.close() }
        zips.clear()
    }

    companion object {
        private const val PARCELABLE = "android/os/Parcelable"
        private const val IINTERFACE = "android/os/IInterface"

        /** A probe that answers nothing, for callers with no classpath (tests, pure-syntax paths). */
        val NONE = AidlClasspathProbe(emptyList())

        /** Probe over [classpath] (jars and class directories), in order. */
        fun over(classpath: List<Path>): AidlClasspathProbe =
            if (classpath.isEmpty()) NONE else AidlClasspathProbe(classpath)
    }
}
