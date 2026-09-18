package dev.ide.deps.impl

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `java.util.zip`, which both the JVM and ART have.
 *
 * Alongside the `classes.jar`, the AAR's `res/`, `assets/`, `jni/` and `AndroidManifest.xml` are unpacked
 * into the same exploded dir — so a consumer (the IDE's resource model / the Android build) finds a
 * library's resources as a `res/` sibling of its `classes.jar`, and its package name in the sibling
 * manifest, with no further unzip at use time.
 */
internal actual fun explodeAar(aar: String, dir: String): String? =
    explode(Paths.get(aar), Paths.get(dir)).toString()

private fun explode(aar: Path, dir: Path): Path {
    Files.createDirectories(dir)
    val classesJar = dir.resolve("classes.jar")
    val marker = dir.resolve(".extracted")
    // Reuse the exploded dir only when a PRIOR extraction ran with the CURRENT explosion logic — the marker
    // records its version ([AAR_EXPLODE_VERSION]). Any older marker (an earlier build wrote an empty one, or a
    // pre-res/pre-manifest format) is treated as stale and re-extracted: such a dir can hold `classes.jar` +
    // `AndroidManifest.xml` but NO `res/` (the res/assets/jni unpacking was added later), yet still passes
    // `AndroidLibraries.isExplodedAar` with no res sibling — so a library's `res/` (its `attr`/
    // `declare-styleable`s) silently never reaches aapt2 and the app fails to link ("attribute … not found",
    // e.g. AndroidX Navigation's `navGraph`/`startDestination` from the transitive navigation-runtime/-common).
    if (runCatching { marker.readText().trim() }.getOrNull() == AAR_EXPLODE_VERSION) {
        // Heal a classes.jar an older build left as a zero-entry zip (resource-only AAR): unusable on ART,
        // where ZipFile rejects an empty archive. Cheap (central-directory read) and only rewrites the bad ones.
        if (Files.isRegularFile(classesJar) && !isUsableJar(classesJar)) writeManifestOnlyJar(classesJar)
        return classesJar
    }

    var foundClasses = false
    // Read via ZipFile (random access through the central directory), NOT ZipInputStream. A nested
    // `classes.jar` is almost always STORED (a jar is already compressed) and an AAR may write STORED
    // entries with a trailing data descriptor (sizes 0 in the local header) — a shape ZipInputStream, which
    // trusts local-header sizes, cannot find the boundary of, so it truncates/over-reads the copy. The
    // resulting `classes.jar` has a central directory that no longer aligns, and the Kotlin compiler's
    // memory-mapped FastJarFileSystem then reads a local-header signature where the central directory should
    // be ("IllegalArgumentException: 0: 67324752"). ZipFile reads each entry by its central-directory
    // size/offset, so the copy is byte-exact regardless of data descriptors or ZIP64.
    ZipFile(aar.toFile()).use { zf ->
        val entries = zf.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) { continue }
            val name = entry.name
            when {
                name == "classes.jar" -> {
                    zf.getInputStream(entry).use { Files.copy(it, classesJar, StandardCopyOption.REPLACE_EXISTING) }
                    foundClasses = true
                }
                name.startsWith("res/") || name.startsWith("assets/") ||
                    name.startsWith("jni/") || name == "AndroidManifest.xml" -> {
                    val target = dir.resolve(name).normalize()
                    if (target.startsWith(dir)) { // zip-slip guard
                        Files.createDirectories(target.parent)
                        zf.getInputStream(entry).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
                    }
                }
            }
        }
    }
    // Code-free AAR → a classes.jar with a single manifest entry (see [writeManifestOnlyJar]). Two shapes
    // land here: no `classes.jar` entry at all (a resource-only AAR), and the one that used to slip
    // through, an AAR that SHIPS a zero-entry `classes.jar`, as `com.onesignal:OneSignal` does for its
    // dependency-only umbrella module. Copying that verbatim puts a 22-byte empty archive on the classpath,
    // which ART's ZipFile rejects outright ("No entries"), so validate what was copied, not just that it existed.
    if (!foundClasses || !isUsableJar(classesJar)) writeManifestOnlyJar(classesJar)
    marker.writeText(AAR_EXPLODE_VERSION)
    return classesJar
}

/** Whether [jar] opens as a zip with at least one entry. ART's ZipFile throws `ZipException: No entries`
 *  on a zero-entry archive, so this also returns false for the empty jars older builds produced. */
private fun isUsableJar(jar: Path): Boolean =
    runCatching { ZipFile(jar.toFile()).use { it.entries().hasMoreElements() } }.getOrDefault(false)

/** Write a NON-EMPTY but class-free `classes.jar` (a single `META-INF/MANIFEST.MF`). A zero-entry archive
 *  is unusable on ART — both `ZipOutputStream.close` and `ZipFile.<init>` throw `ZipException: No entries`,
 *  and the dexer/editor open this jar — so a resource-only AAR needs one benign entry. Dexes to no classes. */
private fun writeManifestOnlyJar(jar: Path) = ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
    zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
    zos.write("Manifest-Version: 1.0\r\n\r\n".toByteArray(Charsets.UTF_8))
    zos.closeEntry()
}
