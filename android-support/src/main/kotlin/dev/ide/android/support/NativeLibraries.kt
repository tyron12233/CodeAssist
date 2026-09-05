package dev.ide.android.support

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.writeText

/**
 * Unpacks a `natives`-scoped dependency into the `<abi>/lib*.so` layout the packager expects.
 *
 * A native library published for Android arrives as one jar per ABI, addressed by a Maven CLASSIFIER:
 * `com.badlogicgames.gdx:gdx-platform:1.14.2:natives-arm64-v8a` holds a single `libgdx.so` at the archive
 * root, with no `lib/` prefix and no ABI directory anywhere in it. So the ABI is carried by the classifier
 * and nothing else, which is why such a jar is useless on the compile or dex classpath: it defines no
 * classes, and [dev.ide.android.support.tasks.NativeLibsMerger] (like AGP) only lifts `.so` files that are
 * already under `lib/<abi>/`. The ABI has to be recovered from the file name and the layout rebuilt.
 *
 * The output is a directory per jar, laid out exactly like a project's own `src/main/jniLibs`, so it joins
 * [ResolvedLibraries.jniLibDirs] and flows through the existing merge/packaging path unchanged.
 */
object NativeLibraries {

    /** The Android ABIs an APK can carry, longest name first so `x86_64` is never read as `x86`. */
    val ABIS: List<String> = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /**
     * Version of the unpacked layout, written into each output dir's marker. Bump it whenever what this
     * lays down changes, so a directory left by an older build is re-unpacked instead of reused.
     */
    private const val LAYOUT_VERSION = "1"

    /** [dirs] are `<abi>/`-laid-out roots to package; [warnings] name jars that contributed nothing. */
    data class Unpacked(val dirs: List<Path>, val warnings: List<String>)

    /**
     * Unpack every `.so` in [jars] under [outRoot], one directory per jar. A jar that yields no Android
     * native library at all (a `natives-desktop` / `natives-ios` classifier declared by mistake, or an
     * ordinary jar with no `.so` in it) contributes no directory and one [Unpacked.warnings] entry.
     */
    fun unpack(jars: List<Path>, outRoot: Path): Unpacked {
        if (jars.isEmpty()) return Unpacked(emptyList(), emptyList())
        val dirs = ArrayList<Path>()
        val warnings = ArrayList<String>()
        for (jar in jars.distinct()) {
            if (!Files.isRegularFile(jar)) continue
            val name = jar.fileName.toString()
            val target = outRoot.resolve(name.substringBeforeLast('.'))
            val marker = target.resolve(".unpacked")
            if (runCatching { Files.readString(marker).trim() }.getOrNull() == LAYOUT_VERSION) {
                if (hasNativeLibrary(target)) dirs.add(target) else warnings.add(noNativesWarning(name))
                continue
            }
            val count = try {
                extract(jar, target)
            } catch (failure: Exception) {
                warnings.add("Couldn't unpack the native libraries in '$name': ${failure.message}")
                continue
            }
            marker.writeText(LAYOUT_VERSION)
            if (count > 0) dirs.add(target) else warnings.add(noNativesWarning(name))
        }
        return Unpacked(dirs, warnings)
    }

    private fun noNativesWarning(jarName: String): String =
        "'$jarName' carries no Android native library, so nothing was packaged from it. A per-ABI native " +
            "artifact is named by its classifier: declare ${ABIS.joinToString(" / ") { "natives-$it" }} " +
            "rather than a desktop or iOS one."

    /** Copy each `.so` in [jar] to its `<abi>/` place under [target]; returns how many were written. */
    private fun extract(jar: Path, target: Path): Int {
        deleteTree(target)
        Files.createDirectories(target)
        val fallbackAbi = abiFromFileName(jar.fileName.toString())
        var written = 0
        // ZipFile (central-directory reads), not ZipInputStream: a `.so` is stored uncompressed often enough
        // that a trailing data descriptor would make a streaming read miss the entry boundary.
        ZipFile(jar.toFile()).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(".so", ignoreCase = true)) continue
                val rel = placeFor(entry.name, fallbackAbi) ?: continue
                val dest = target.resolve(rel).normalize()
                if (!dest.startsWith(target)) continue   // zip-slip guard
                Files.createDirectories(dest.parent)
                zf.getInputStream(entry).use { Files.copy(it, dest, StandardCopyOption.REPLACE_EXISTING) }
                written++
            }
        }
        return written
    }

    /**
     * Where a `.so` at [entryPath] belongs, relative to the unpacked root, or null when it can't be placed.
     *
     * Three shapes reach here. A jar that is already in packaged form (`lib/<abi>/libfoo.so`) keeps its path
     * below `lib/`, matching what the merger lifts out of classpath jars, and so keeps working for a legacy
     * ABI this build never names. A jar laid out `<abi>/libfoo.so` is taken verbatim. Anything else is a
     * bare `.so` whose ABI only the classifier knows ([fallbackAbi]), and with no classifier ABI there is
     * nowhere to put it, which is exactly the `natives-desktop` case.
     */
    internal fun placeFor(entryPath: String, fallbackAbi: String?): String? {
        val segments = entryPath.trimStart('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        if (segments[0] == "lib" && segments.size >= 3) return segments.drop(1).joinToString("/")
        if (segments[0] in ABIS && segments.size >= 2) return entryPath.trimStart('/')
        return fallbackAbi?.let { "$it/${segments.last()}" }
    }

    /** The ABI a natives jar's file name encodes, e.g. `gdx-platform-1.14.2-natives-arm64-v8a.jar`. The
     *  classifier is the only place it is written down, so the file name is where it has to be read from. */
    internal fun abiFromFileName(fileName: String): String? {
        val stem = fileName.substringBeforeLast('.')
        return ABIS.firstOrNull { stem.endsWith(it, ignoreCase = true) }
    }

    private fun hasNativeLibrary(dir: Path): Boolean = runCatching {
        Files.walk(dir).use { s -> s.anyMatch { Files.isRegularFile(it) && it.toString().endsWith(".so", true) } }
    }.getOrDefault(false)

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        runCatching {
            Files.walk(path).use { s ->
                s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }
}
