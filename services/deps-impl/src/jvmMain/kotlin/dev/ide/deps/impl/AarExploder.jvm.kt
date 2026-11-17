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
 * Alongside the `classes.jar`, the AAR's `res/`, `assets/`, `jni/`, `AndroidManifest.xml`, `R.txt`,
 * `proguard.txt` and `baseline-prof.txt` are unpacked into the same exploded dir — so a consumer (the IDE's resource model /
 * the Android build) finds a library's resources as a `res/` sibling of its `classes.jar`, and its package
 * name in the sibling manifest, with no further unzip at use time.
 *
 * Any `libs/<name>.jar` the AAR carries is folded INTO that `classes.jar` ([mergeLibJars]). An AAR's code is
 * `classes.jar` plus those jars, and a library is free to keep whole packages in them: Unity Ads ships its
 * `com.unity3d.scar.adapter.*` classes and the Open Measurement SDK that way. The class root a resolved
 * library has is one file, which the editor, the compiler and the dexer all read, so merging at this point
 * is what gets the bundled classes to all three. Left out, the app compiled (nothing in the project names
 * those classes) and then died on launch with `NoClassDefFoundError` from inside the library.
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
    // `libs/<name>.jar` entries, copied out to scratch files so each can be opened with ZipFile (the same
    // reason the AAR itself is: a nested jar may hold STORED entries with a data descriptor).
    val scratch = dir.resolve(".libs-merging")
    val libJars = ArrayList<Pair<String, Path>>()
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
                isBundledLibJar(name) -> {
                    Files.createDirectories(scratch)
                    val copy = scratch.resolve("${libJars.size}.jar")
                    zf.getInputStream(entry).use { Files.copy(it, copy, StandardCopyOption.REPLACE_EXISTING) }
                    libJars.add(name to copy)
                }
                name.startsWith("res/") || name.startsWith("assets/") ||
                    name.startsWith("jni/") || name == "AndroidManifest.xml" ||
                    // Baseline Profile rules: a release build compiles them into the app's profile.
                    name == "baseline-prof.txt" ||
                    // The library's symbol table: the R class a consumer generates for it holds exactly these.
                    name == "R.txt" ||
                    // The library's keep rules for whoever shrinks an app that uses it. Left behind, a minified
                    // build ran R8 without them and could strip what the library reaches by reflection.
                    name == "proguard.txt" -> {
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
    if (libJars.isNotEmpty()) {
        // By entry name, so the merged jar does not depend on the order the AAR happened to list them in.
        mergeLibJars(classesJar, libJars.sortedBy { it.first }.map { it.second })
        libJars.forEach { Files.deleteIfExists(it.second) }
        Files.deleteIfExists(scratch)
    }
    marker.writeText(AAR_EXPLODE_VERSION)
    return classesJar
}

/** A jar directly under the AAR's `libs/`: the only place besides `classes.jar` an AAR keeps code. */
private fun isBundledLibJar(name: String): Boolean =
    name.startsWith("libs/") && name.endsWith(".jar", ignoreCase = true) && name.indexOf('/', "libs/".length) < 0

/**
 * Rewrite [classesJar] as itself plus every entry of [libs], in that order.
 *
 * The first copy of a name wins, so `classes.jar` keeps precedence over a bundled jar and a second
 * `META-INF/MANIFEST.MF` is simply dropped. Two kinds of entry are not first-wins:
 *
 *  - `META-INF/services/` files are concatenated. Each jar lists its own providers under the same name, and
 *    keeping one file would silently unregister the others.
 *  - Signature files are left out. They sign the jar they came from, entry by entry, and that jar no longer
 *    exists as such; a verifier would read the result as a tampered archive.
 *
 * A bundled jar that will not open (ART rejects a zero-entry one) contributes nothing, which is what it
 * held. Written beside the target and moved over it, so a crash mid-merge leaves the plain `classes.jar`
 * and no marker, and the next resolve starts again.
 */
private fun mergeLibJars(classesJar: Path, libs: List<Path>) {
    val merged = classesJar.resolveSibling("classes.jar.merging")
    val seen = HashSet<String>()
    val services = LinkedHashMap<String, StringBuilder>()
    ZipOutputStream(Files.newOutputStream(merged)).use { out ->
        for (jar in listOf(classesJar) + libs) {
            val zf = runCatching { ZipFile(jar.toFile()) }.getOrNull() ?: continue
            zf.use {
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (entry.isDirectory || isSignatureFile(name)) continue
                    if (name.startsWith("META-INF/services/")) {
                        val text = zf.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                        services.getOrPut(name) { StringBuilder() }.append(text.trimEnd()).append('\n')
                        continue
                    }
                    if (!seen.add(name)) continue
                    // The source entry's own time, so the same AAR merges to the same entries everywhere.
                    out.putNextEntry(ZipEntry(name).apply { if (entry.time != -1L) time = entry.time })
                    zf.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
        for ((name, text) in services) {
            if (!seen.add(name)) continue
            out.putNextEntry(ZipEntry(name).apply { time = 0L })
            out.write(text.toString().toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }
    }
    Files.move(merged, classesJar, StandardCopyOption.REPLACE_EXISTING)
}

private fun isSignatureFile(name: String): Boolean {
    if (!name.startsWith("META-INF/") || name.indexOf('/', "META-INF/".length) >= 0) return false
    val upper = name.uppercase()
    return upper.endsWith(".SF") || upper.endsWith(".DSA") || upper.endsWith(".RSA") || upper.endsWith(".EC")
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
