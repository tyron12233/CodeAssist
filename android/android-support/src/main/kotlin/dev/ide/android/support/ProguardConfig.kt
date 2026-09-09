package dev.ide.android.support

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The bundled default ProGuard/R8 keep-rule files, the counterpart to AGP's
 * `getDefaultProguardFile(name)`. A `BuildType.proguardFiles` entry equal to one of these bare names
 * is resolved from android-support's classpath assets (committed under `resources/proguard/`) rather
 * than the module directory; any other entry is treated as a module-relative path.
 */
object DefaultProguardFiles {
    /** No-optimization default (mirrors AGP's `proguard-android.txt`). */
    const val DEFAULT = "proguard-android.txt"
    /** Optimizing default (mirrors AGP's `proguard-android-optimize.txt`); the conventional choice. */
    const val OPTIMIZE = "proguard-android-optimize.txt"
    val NAMES: Set<String> = setOf(DEFAULT, OPTIMIZE)

    fun isDefault(entry: String): Boolean = entry in NAMES

    /**
     * Extract bundled default [name] into [dir] (rewritten only when the size differs, so it is not
     * re-touched every build and the task's input fingerprint stays stable), returning its path.
     * Null when [name] is not a known default or the asset is missing.
     */
    fun extract(name: String, dir: Path): Path? {
        if (name !in NAMES) return null
        val bytes = DefaultProguardFiles::class.java.getResourceAsStream("/proguard/$name")
            ?.use { it.readBytes() } ?: return null
        Files.createDirectories(dir)
        val out = dir.resolve(name)
        if (!Files.exists(out) || Files.size(out) != bytes.size.toLong()) Files.write(out, bytes)
        return out
    }
}

/**
 * Resolve a `proguardFiles`/`consumerProguardFiles` entry to a concrete file: a bundled default
 * (extracted into [defaultsDir]) when it names one, else a path relative to [moduleDir] (an absolute
 * entry is used as-is). Returns null when a module-relative file is absent, so the build can skip a
 * missing user-named file with a log line rather than failing (AGP errors, but a tolerant on-device
 * IDE prefers to keep building).
 */
fun resolveProguardFile(entry: String, moduleDir: Path, defaultsDir: Path): Path? {
    if (DefaultProguardFiles.isDefault(entry)) return DefaultProguardFiles.extract(entry, defaultsDir)
    val p = Paths.get(entry)
    val resolved = if (p.isAbsolute) p else moduleDir.resolve(entry)
    return resolved.takeIf { Files.isRegularFile(it) }
}

/** Resolve all [entries], preserving order and dropping absent module-relative files (see [resolveProguardFile]). */
fun resolveProguardFiles(entries: List<String>, moduleDir: Path, defaultsDir: Path): List<Path> =
    entries.mapNotNull { resolveProguardFile(it, moduleDir, defaultsDir) }
