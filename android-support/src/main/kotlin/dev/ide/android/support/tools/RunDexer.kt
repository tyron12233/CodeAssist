package dev.ide.android.support.tools

import dev.ide.android.support.tasks.DEX_CACHE_FORMAT
import dev.ide.android.support.tasks.DexArchives
import dev.ide.build.engine.DexResult
import dev.ide.build.engine.RunDexBackend
import dev.ide.build.engine.RunDexRequest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.stream.Collectors

/**
 * The console-run [RunDexBackend], on device (D8 in-process). It replaces the old monolithic "dex the whole
 * runtime classpath every edit" path — whose cost was re-dexing `kotlin-stdlib` + every dependency on every
 * `.kt`/`.java` change — with the same content-hash dex caching the Android APK pipeline uses
 * (`DexArchiveBuilderTask`):
 *
 *  - **Library jars** (immutable across edits) are instrumented + dexed ONCE, keyed by content hash, and
 *    reused from a per-project staging cache and an optional shared cross-project cache. An edit reuses them.
 *  - **User class output** (changes every build) is instrumented + dexed each build — the only per-edit work.
 *
 * Both scopes are first run through the engine's run-sandbox [RunDexRequest.instrument] (ExitGuard +
 * SandboxGuard) and have `@kotlin.Metadata` stripped (the bundled D8 drops dex it can't rewrite — see
 * [DexArchives.strippedKotlinMetadata]). The library cache is namespaced by [RunDexRequest.guardVersion] +
 * minApi + the dex tool format, so a guard or D8 bump invalidates it and the run's INSTRUMENTED dex never
 * aliases the APK path's uninstrumented dex.
 *
 * Output is a flat `classes*.dex` set in `outDex`; [dev.ide.android.DexClassLoaderRunner] joins every `.dex`
 * under it onto the `DexClassLoader` path (multidex), so no D8 merge step is needed. Library dex is laid down
 * first in a stable order, so an unchanged library keeps the same file + bytes across runs and ART reuses its
 * compiled oat.
 *
 * @param cacheRoot shared cross-project dex cache root (e.g. `<home>/caches/dex-run`); null disables sharing
 *   (the per-project staging cache still gives intra-project reuse).
 */
class RunDexer(
    private val dexer: Dexer,
    private val androidJar: Path,
    private val cacheRoot: Path?,
) : RunDexBackend {

    override fun dexForRun(request: RunDexRequest): DexResult {
        val log = ArrayList<String>()
        DexArchives.clearDir(request.outDex)
        Files.createDirectories(request.outDex)
        Files.createDirectories(request.stagingDir)
        val minApi = request.minApi
        val transform = request.instrument

        // Namespace the cache by guard + minApi + dex tool format: an instrumented run dex must never reuse
        // the APK path's uninstrumented dex, and a guard/D8 bump must invalidate it.
        val ns = "run-${request.guardVersion}-minApi$minApi-$DEX_CACHE_FORMAT"
        val sharedNs = cacheRoot?.resolve(ns)
        val libRoot = request.stagingDir.resolve("lib")

        val dexDirs = ArrayList<Path>()   // per-scope dex dirs in stable order (libraries first, user last)

        // 1) Libraries — instrument + dex once, content-hash cached.
        val hashCache = DexArchives.HashCache(request.stagingDir)
        val libs = request.libJars.filter { Files.exists(it) }.sortedBy { it.toString() }
        val liveHashes = HashSet<String>()
        for (lib in libs) {
            val hash = hashCache.hashOf(lib)
            if (!liveHashes.add(hash)) continue          // two paths, identical content → dex once
            val localBucket = libRoot.resolve(hash)
            val shared = sharedNs?.resolve(hash)
            when {
                DexArchives.hasDex(localBucket) -> dexDirs.add(localBucket)
                shared != null && DexArchives.hasDex(shared) -> {
                    DexArchives.copyDir(shared, localBucket)
                    dexDirs.add(localBucket)
                    log.add("dexRun: reuse ${lib.fileName} (shared cache)")
                }
                else -> {
                    val staged = request.stagingDir.resolve("staged-$hash.jar")
                    val classes = writeInstrumentedJar(lib, staged, transform)
                    if (classes == 0) { runCatching { Files.deleteIfExists(staged) }; continue }
                    DexArchives.clearDir(localBucket); Files.createDirectories(localBucket)
                    val r = dexer.dex(listOf(staged), androidJar, minApi, release = false, outDir = localBucket)
                    log.addAll(r.log)
                    runCatching { Files.deleteIfExists(staged) }
                    if (!r.success) return DexResult(false, log + "dexRun: failed dexing ${lib.fileName}")
                    if (shared != null) DexArchives.publishToCache(localBucket, shared)
                    dexDirs.add(localBucket)
                    log.add("dexRun: dexed ${lib.fileName}")
                }
            }
        }
        hashCache.flush()
        DexArchives.prune(libRoot, liveHashes)            // drop buckets for libraries no longer on the path

        // 2) User class output — re-dexed every build (the only per-edit dex work).
        val userDirs = request.userClassDirs.filter { Files.isDirectory(it) }
        if (userDirs.isNotEmpty()) {
            val userJar = request.stagingDir.resolve("user.jar")
            val classes = writeInstrumentedDirs(userDirs, userJar, transform)
            if (classes > 0) {
                val userOut = request.stagingDir.resolve("user-dex")
                DexArchives.clearDir(userOut); Files.createDirectories(userOut)
                val r = dexer.dex(listOf(userJar), androidJar, minApi, release = false, outDir = userOut)
                log.addAll(r.log)
                if (!r.success) return DexResult(false, log + "dexRun: failed dexing user classes")
                dexDirs.add(userOut)
            }
            runCatching { Files.deleteIfExists(userJar) }
        }

        // 3) Assemble — renumber every scope's .dex into one classes*.dex set the runner can load.
        var idx = 0
        for (dir in dexDirs) {
            for (dex in dexesIn(dir)) {
                val name = if (idx == 0) "classes.dex" else "classes${idx + 1}.dex"
                Files.copy(dex, request.outDex.resolve(name), StandardCopyOption.REPLACE_EXISTING)
                idx++
            }
        }
        if (idx == 0) return DexResult(false, log + "dexRun: nothing to dex for run")
        return DexResult(true, log)
    }

    private fun dexesIn(dir: Path): List<Path> =
        if (!Files.isDirectory(dir)) emptyList()
        else Files.walk(dir).use { s ->
            s.filter { it.toString().endsWith(".dex") }.sorted().collect(Collectors.toList())
        }

    /** Instrument + strip-metadata every `.class` in [src] into [dest]. Returns the class count (0 → caller
     *  skips dexing; an empty jar both wastes a D8 run and trips ART's zero-entry `ZipException`). */
    private fun writeInstrumentedJar(src: Path, dest: Path, transform: (String, ByteArray) -> ByteArray): Int {
        dest.parent?.let { Files.createDirectories(it) }
        var count = 0
        JarOutputStream(Files.newOutputStream(dest)).use { jos ->
            JarFile(src.toFile()).use { jf ->
                val entries = jf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory || !e.name.endsWith(".class")) continue
                    val bytes = jf.getInputStream(e).use { it.readBytes() }
                    jos.putNextEntry(JarEntry(e.name))
                    jos.write(DexArchives.strippedKotlinMetadata(transform(e.name, bytes)))
                    jos.closeEntry()
                    count++
                }
            }
        }
        return count
    }

    /** Instrument + strip-metadata every `.class` under [dirs] into [dest] (first-wins on duplicate paths).
     *  Returns the class count. */
    private fun writeInstrumentedDirs(dirs: List<Path>, dest: Path, transform: (String, ByteArray) -> ByteArray): Int {
        dest.parent?.let { Files.createDirectories(it) }
        val seen = HashSet<String>()
        var count = 0
        JarOutputStream(Files.newOutputStream(dest)).use { jos ->
            for (dir in dirs) {
                val files = Files.walk(dir).use { s ->
                    s.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
                        .collect(Collectors.toList())
                }
                for (f in files) {
                    val rel = dir.relativize(f).toString().replace('\\', '/')
                    if (!seen.add(rel)) continue
                    val bytes = Files.readAllBytes(f)
                    jos.putNextEntry(JarEntry(rel))
                    jos.write(DexArchives.strippedKotlinMetadata(transform(rel, bytes)))
                    jos.closeEntry()
                    count++
                }
            }
        }
        return count
    }
}
