package dev.ide.deps.impl

import dev.ide.kotlin.classfile.ZipArchive
import dev.ide.platform.createDirectories
import dev.ide.platform.fileInfo
import dev.ide.platform.openFile
import dev.ide.platform.resolvePath
import dev.ide.platform.writeFileAtomically

/**
 * An AAR's `classes.jar`, and nothing else.
 *
 * The JVM actual unpacks the whole artifact: `classes.jar` plus `res/`, `assets/`, `jni/` and the manifest,
 * because aapt2 and the Android build read a library's resources out of the exploded directory. None of that
 * runs here, so none of it is written here.
 *
 * What DOES matter here is the class root. An `.aar` is where every `androidx.*` and Material artifact ships
 * its code, so a host that cannot open one resolves nothing from the Android ecosystem: an Android project
 * opened on a phone had a classpath of plain jars and a sea of unresolved library types. Reading one entry
 * out of a zip is all the editor ever needed, and `:kotlin-classfile`'s archive reader (which is how every
 * jar on this platform is already read) does it with no JVM.
 *
 * **This is a classpath, not a build.** The Android FRAMEWORK is still absent (there is no `android.jar` on
 * iOS and no SDK to get one from), so a library type resolves while the `android.*` types it extends do not.
 * That is a smaller gap than it sounds: a library's own API surface is what completion and navigation are
 * asked about.
 *
 * A resource-only AAR (no `classes.jar` entry at all) returns null, which the caller already treats as that
 * one coordinate contributing no classes. The JVM writes a manifest-only jar there instead, because the
 * dexer and the build open every classpath entry and ART rejects a zero-entry archive; nothing here opens an
 * empty jar, so there is nothing to synthesize.
 */
internal actual fun explodeAar(aar: String, dir: String): String? {
    val classesJar = resolvePath(dir, CLASSES_JAR)
    // Extracted already. No marker file: unlike the JVM's exploded directory, there is one possible shape
    // here, so the file being present is the whole of the question.
    if (fileInfo(classesJar) != null) return classesJar
    val source = openFile(aar) ?: return null
    val bytes = try {
        val archive = ZipArchive.open(source) ?: return null
        archive.entry(CLASSES_JAR)?.let { archive.read(it) } ?: return null
    } finally {
        source.close()
    }
    if (!createDirectories(dir)) return null
    return if (writeFileAtomically(classesJar, bytes)) classesJar else null
}

private const val CLASSES_JAR = "classes.jar"
