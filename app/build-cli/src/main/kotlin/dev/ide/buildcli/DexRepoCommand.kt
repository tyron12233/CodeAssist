package dev.ide.buildcli

import dev.ide.android.support.pack.DexRepository
import dev.ide.android.support.pack.LibraryPack
import dev.ide.core.headless.HeadlessEngine
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/**
 * `codeassist dexrepo` — add libraries to the public dex repository ([DexRepository]): Maven Central's layout,
 * holding dex instead of jars, which a phone downloads instead of dexing a project's libraries itself.
 *
 *     codeassist dexrepo --repo ../codeassist-dex --key ~/.codeassist/dexpack-signing-key.pk8 --libraries libraries.txt
 *
 * Each line of the list is a coordinate (`androidx.compose.material3:material3:1.3.1`), an artifact without a version
 * (`androidx.compose.material3:material3`, meaning its [LATEST_STABLE] newest stable releases), or a project template
 * (`template:compose-app`). Each is resolved in a fresh minSdk-26 project of its own, so a line yields exactly its
 * own dependency closure. A line whose whole closure is already published (for this CLI's D8 and indexers) is
 * skipped without dexing or indexing; otherwise, and every library version in that closure not yet in the repository is dexed with the
 * D8 the app bundles and indexed with the IDE's own indexers, signed and written there (`dex/…` and `index/…`).
 * Versions already published are never rewritten.
 */
internal object DexRepoCommand {
    const val USAGE = "codeassist dexrepo --repo <dir> --key <pkcs8 file> --libraries <file> [--cache <dir>]"

    /** How many of an artifact's newest stable releases a version-less line publishes. */
    const val LATEST_STABLE = 3

    fun run(args: List<String>): Int {
        var repo: Path? = null
        var key: Path? = null
        var list: Path? = null
        var cache: Path? = null
        var i = 0
        fun value(flag: String) = args.getOrNull(++i) ?: error("$flag needs a value\n$USAGE")
        while (i < args.size) {
            when (val a = args[i]) {
                "--repo" -> repo = Path.of(value(a)).toAbsolutePath().normalize()
                "--key" -> key = Path.of(value(a)).toAbsolutePath().normalize()
                "--libraries" -> list = Path.of(value(a)).toAbsolutePath().normalize()
                "--cache" -> cache = Path.of(value(a)).toAbsolutePath().normalize()
                else -> error("unknown option '$a'\n$USAGE")
            }
            i++
        }
        val repoRoot = repo ?: error("--repo is required\n$USAGE")
        val signingKey = LibraryPack.privateKey(Files.readAllBytes(key ?: error("--key is required\n$USAGE")))
        val lines = Files.readAllLines(list ?: error("--libraries is required\n$USAGE"))
            .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
            .flatMap { line ->
                val parts = line.split(':')
                if (line.startsWith("template:") || parts.size != 2) listOf(line)
                else MavenVersions.latestStable(parts[0], parts[1], LATEST_STABLE).map { "$line:$it" }
                    .ifEmpty { println("  no stable release found for $line"); emptyList() }
            }
        val work = Files.createTempDirectory("dexrepo")
        // One caches root for every line: downloads and dexed libraries are shared, as on a phone with many projects.
        val caches = cache ?: work.resolve("caches")
        val dexCache = caches.resolve("dex-repo")
        var written = 0
        val failed = ArrayList<String>()
        for ((n, line) in lines.withIndex()) {
            println("== $line")
            val outcome = runCatching { publishLine(line, work.resolve("p$n"), caches, dexCache, repoRoot, signingKey) }
            outcome.onSuccess { written += it }.onFailure { failed += "$line: ${it.message}"; println("  failed: ${it.message}") }
        }
        println("Published $written new library version(s) into $repoRoot" + if (failed.isEmpty()) "" else "; ${failed.size} line(s) failed")
        failed.forEach { System.err.println("  $it") }
        return if (failed.isEmpty()) 0 else 1
    }

    private fun publishLine(line: String, root: Path, caches: Path, dexCache: Path, repoRoot: Path, key: java.security.PrivateKey): Int {
        val template = line.removePrefix("template:").takeIf { line.startsWith("template:") }
        val id = template?.substringBefore(':') ?: "android-app"
        val extra = (template?.substringAfter(':', "") ?: "language=kotlin").split(',').filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val args = mapOf("name" to "Repo", "packageName" to "com.example.repo", "minSdk" to "${DexRepository.MIN_API}") + extra
        return HeadlessEngine.create(root, id, args, caches).use { engine ->
            if (template == null) runBlocking { engine.addDependency("app", line) }?.let { error(it) }
            val unresolved = runBlocking { engine.resolveDependencies() }
            if (unresolved.isNotEmpty()) error("unresolved: $unresolved")
            if (engine.alreadyPublished(repoRoot)) { println("  already published"); return@use 0 }
            val predexed = runBlocking { engine.predexLibraries(dexCache) { println("  $it") } }
                ?: error("no Android app module at minSdk ${DexRepository.MIN_API}+")
            val androidJar = dev.ide.core.sdk.BundledAndroidToolchain.androidJar() ?: error("no android.jar (install an Android SDK)")
            val d8 = dev.ide.android.support.tools.D8InProcessDexer()
            val coordinates = DexRepository.publish(predexed.jars, predexed.tagDir, repoRoot, key) { dexes, out ->
                d8.dex(dexes, androidJar, DexRepository.MIN_API, false, out, 0, null).success
            }
            coordinates.forEach { println("  + $it") }
            val indexed = runBlocking { engine.publishIndexes(dexCache.resolveSibling("index-repo"), repoRoot, key) }
            indexed.forEach { println("  + $it (index)") }
            coordinates.size + indexed.size
        }
    }
}
