package dev.ide.android.support.tasks

import dev.ide.android.support.AndroidPackaging
import dev.ide.android.support.JniLibsPackaging
import dev.ide.android.support.ResourcePackaging
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * AGP-faithful merge rules for the packaging step: when the same archive path is contributed by more than
 * one input (the module's own files, a sub-module, an AAR, an external jar) the packager either drops it
 * ([Action.EXCLUDE]), keeps the first provider ([Action.PICK_FIRST]), concatenates every provider
 * ([Action.MERGE]), or — the default — keeps the first with a warning (AGP errors here; the on-device IDE
 * is lenient so an un-configured duplicate doesn't fail the whole build).
 *
 * Patterns are AGP-style globs relative to the APK root: a double-star crosses a slash, a single star
 * stays within a path segment, `?` is one non-separator char, and a leading slash is optional (stripped
 * before matching). A pattern must match the WHOLE relative path (a double-star-slash prefix matches
 * zero-or-more leading segments, like Ant), e.g. a `kotlin_metadata` pattern prefixed that way matches
 * both a root-level file and a nested one.
 */
internal object PackagingRules {

    enum class Action { EXCLUDE, PICK_FIRST, MERGE, DEFAULT }

    /** The effective Java-resource filter: module config layered over the AGP defaults (see [AndroidPackaging]). */
    fun resourceFilter(cfg: ResourcePackaging): Filter = Filter(
        excludes = AndroidPackaging.DEFAULT_RESOURCE_EXCLUDES + cfg.excludes,
        pickFirsts = cfg.pickFirsts.toList(),
        merges = AndroidPackaging.DEFAULT_RESOURCE_MERGES + cfg.merges,
    )

    /** The effective native-library filter. AGP ships no default `.so` excludes; `.so` are never merged. */
    fun jniLibsFilter(cfg: JniLibsPackaging): Filter = Filter(
        excludes = cfg.excludes.toList(),
        pickFirsts = cfg.pickFirsts.toList(),
        merges = emptyList(),
    )

    /** Resolves the [Action] for an archive path against the compiled pattern sets. */
    class Filter(excludes: List<String>, pickFirsts: List<String>, merges: List<String>) {
        private val excludeM = Matcher(excludes)
        private val pickFirstM = Matcher(pickFirsts)
        private val mergeM = Matcher(merges)

        /** A stable string of the effective patterns — the build-cache key for the merge tasks. */
        val fingerprint: String = "x=$excludes;p=$pickFirsts;m=$merges"

        fun actionFor(path: String): Action = when {
            excludeM.matches(path) -> Action.EXCLUDE   // exclude wins over everything
            mergeM.matches(path) -> Action.MERGE
            pickFirstM.matches(path) -> Action.PICK_FIRST
            else -> Action.DEFAULT
        }
    }

    class Matcher(patterns: Collection<String>) {
        private val regexes = patterns.map { globToRegex(it) }
        fun matches(path: String): Boolean = regexes.any { it.matches(path) }
    }

    /** Compile one AGP glob into a regex matching the whole (leading-slash-stripped) relative path. */
    fun globToRegex(pattern: String): Regex {
        val p = pattern.removePrefix("/")
        val sb = StringBuilder()
        var i = 0
        while (i < p.length) {
            val c = p[i]
            when {
                c == '*' && i + 1 < p.length && p[i + 1] == '*' -> {
                    if (i + 2 < p.length && p[i + 2] == '/') {   // '**/' — zero-or-more leading segments
                        sb.append("(?:[^/]+/)*"); i += 3
                    } else {                                      // trailing/standalone '**'
                        sb.append(".*"); i += 2
                    }
                }
                c == '*' -> { sb.append("[^/]*"); i++ }
                c == '?' -> { sb.append("[^/]"); i++ }
                c in REGEX_META -> { sb.append('\\').append(c); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        return Regex(sb.toString())
    }

    private const val REGEX_META = ".()[]{}+^$|\\"
}

/**
 * Merges Java resources (non-code files) from the module's `src/<set>/resources`, sub-module jars, and
 * external library jars into one `merged-java-res.jar` whose entries the packager copies to the APK root.
 * `.class` entries are skipped (they are dexed, not packaged as resources). Inputs are offered project-first
 * so a `pickFirst`/first-wins duplicate keeps the module's own copy.
 */
internal object JavaResMerger {

    /** @param dirs project + dependency-library `resources` roots. @param jars sub-module + external jars. */
    fun merge(dirs: List<Path>, jars: List<Path>, filter: PackagingRules.Filter, outJar: Path, warn: (String) -> Unit = {}): Int {
        val merger = Merger(filter, warn)
        for (dir in dirs.filter { Files.isDirectory(it) }) {
            filesIn(dir).forEach { f ->
                val rel = dir.relativize(f).toString().replace('\\', '/')
                merger.offer(rel) { Files.newInputStream(f) }
            }
        }
        for (jar in jars.filter { Files.isRegularFile(it) }) {
            ZipFile(jar.toFile()).use { zf ->
                zf.entries().asSequence()
                    .filter { !it.isDirectory && !it.name.endsWith(".class") }
                    .sortedBy { it.name }
                    .forEach { e -> merger.offer(e.name) { zf.getInputStream(e) } }
            }
        }
        outJar.parent?.let { Files.createDirectories(it) }
        if (merger.size == 0) {
            // A ZipOutputStream closed with zero entries throws "No entries" on ART; write the canonical
            // 22-byte empty archive instead so the task's declared output exists and packages nothing.
            Files.write(outJar, EMPTY_ZIP)
        } else {
            ZipOutputStream(Files.newOutputStream(outJar)).use { zos ->
                zos.setLevel(Deflater.BEST_COMPRESSION)
                merger.forEach { name, bytes ->
                    zos.putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED })
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }
        return merger.size
    }

    /** The 22-byte End-Of-Central-Directory record = a valid archive with no entries (ART-readable). */
    private val EMPTY_ZIP = byteArrayOf(0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    /** Accumulates offered entries, applying the [PackagingRules.Filter]. Emits in first-seen order. */
    private class Merger(private val filter: PackagingRules.Filter, private val warn: (String) -> Unit) {
        private val chosen = LinkedHashMap<String, ByteArray>()   // pick-first / default: first content wins
        private val merged = LinkedHashMap<String, ByteArrayOutputStream>() // merge: concatenated content

        val size: Int get() = chosen.size + merged.size

        fun offer(path: String, open: () -> InputStream) {
            val name = path.trimStart('/')
            if (name.isEmpty()) return
            when (filter.actionFor(name)) {
                PackagingRules.Action.EXCLUDE -> return
                PackagingRules.Action.MERGE -> {
                    val acc = merged.getOrPut(name) { ByteArrayOutputStream() }
                    if (acc.size() > 0) acc.write('\n'.code)   // AGP separates concatenated service files by newline
                    open().use { it.copyTo(acc) }
                }
                PackagingRules.Action.PICK_FIRST -> if (name !in chosen) chosen[name] = open().use { it.readBytes() }
                PackagingRules.Action.DEFAULT ->
                    if (name !in chosen) chosen[name] = open().use { it.readBytes() }
                    else warn("More than one file found for '$name'; keeping the first (add it to packaging.resources.pickFirsts or merges)")
            }
        }

        inline fun forEach(action: (String, ByteArray) -> Unit) {
            chosen.forEach { (n, b) -> action(n, b) }
            merged.forEach { (n, acc) -> action(n, acc.toByteArray()) }
        }
    }

    private fun filesIn(root: Path): List<Path> =
        Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.sorted().collect(Collectors.toList()) }
}

/**
 * Merges native libraries into an `<abi>` directory of `.so` files the packager maps under `lib`. Sources:
 * the module's `src/<set>/jniLibs` (laid out `<abi>` then the file), dependency-library jniLibs + an AAR's
 * `jni` dir (same layout), and the `.so` entries under `lib` inside dependency jars. Filtering runs on the
 * APK path (the `lib/<abi>` form) so both a name-only pattern and a `lib`-anchored one match. First-wins is
 * keyed by `<abi>` + name (project first). There is no stripping: that needs the NDK, absent on device, so
 * libraries are packaged as-is (AGP does the same without an NDK).
 */
internal object NativeLibsMerger {

    /** @param dirs `<abi>`-laid-out native roots (project jniLibs, dep jniLibs, AAR jni). @param jars may hold `.so` under `lib`. */
    fun merge(dirs: List<Path>, jars: List<Path>, filter: PackagingRules.Filter, outDir: Path, warn: (String) -> Unit = {}): Int {
        // Clean so a removed library doesn't linger from a previous run.
        if (Files.exists(outDir)) Files.walk(outDir).use { s ->
            s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
        Files.createDirectories(outDir)
        val seen = HashSet<String>()   // <abi>/name relative keys already written

        fun accept(relInLib: String, open: () -> InputStream) {
            val rel = relInLib.trimStart('/')            // <abi>/name.so
            if (rel.isEmpty()) return
            when (filter.actionFor("lib/$rel")) {
                PackagingRules.Action.EXCLUDE -> return
                PackagingRules.Action.MERGE, PackagingRules.Action.PICK_FIRST ->
                    if (!seen.add(rel)) return           // native libs can't merge; both fall to first-wins
                PackagingRules.Action.DEFAULT ->
                    if (!seen.add(rel)) { warn("Duplicate native library '$rel'; keeping the first (add it to packaging.jniLibs.pickFirsts)"); return }
            }
            val target = outDir.resolve(rel)
            target.parent?.let { Files.createDirectories(it) }
            open().use { Files.copy(it, target) }
        }

        // A jniLibs / AAR `jni` dir packages ALL its files under `lib/` (AGP's behaviour, not just `.so` — e.g.
        // versioned `libfoo.so.1` or gdbserver), so we don't regress the AAR-jni packaging the old packager did.
        for (dir in dirs.filter { Files.isDirectory(it) }) {
            filesIn(dir).forEach { f ->
                val rel = dir.relativize(f).toString().replace('\\', '/')
                accept(rel) { Files.newInputStream(f) }
            }
        }
        // From jars we extract only the `.so` under `lib/` (AGP's native-lib extraction), not arbitrary content.
        for (jar in jars.filter { Files.isRegularFile(it) }) {
            ZipFile(jar.toFile()).use { zf ->
                zf.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("lib/") && it.name.endsWith(".so") }
                    .sortedBy { it.name }
                    .forEach { e -> accept(e.name.removePrefix("lib/")) { zf.getInputStream(e) } }
            }
        }
        return seen.size
    }

    private fun filesIn(root: Path): List<Path> =
        Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.sorted().collect(Collectors.toList()) }
}
