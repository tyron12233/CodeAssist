package dev.ide.ios

import dev.ide.deps.ConflictPolicy
import dev.ide.deps.impl.ArtifactFetcher
import dev.ide.deps.impl.GradleModuleParser
import dev.ide.deps.impl.HttpArtifactFetcher
import dev.ide.deps.impl.MavenDependencyResolver
import dev.ide.deps.impl.MavenVersion
import dev.ide.deps.impl.ResolverCache
import dev.ide.deps.impl.VariantRequest
import dev.ide.model.Coordinate
import dev.ide.model.Exclusion
import dev.ide.platform.ProgressReporter
import dev.ide.platform.log.Log
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * The project's libraries as a preview runs them: the same declarations the editor resolves, but each as
 * its JVM DESKTOP variant, because the preview interprets JVM bytecode and draws with skia (see
 * [IosPreviewRenderer]). Nothing about the libraries is bundled with the app: whatever the project declares
 * is what the preview runs.
 *
 * Most of that is a variant choice the resolver already makes ([VariantRequest.JVM]): a multiplatform
 * library publishes a desktop JVM artifact beside its Android one. The exception is androidx Compose
 * itself, whose `ui`/`foundation`/`material3` publish only Android code and JVM STUBS that throw; their
 * desktop implementation is Compose Multiplatform's, under `org.jetbrains.compose.*`. So an androidx
 * Compose coordinate is replaced by the Compose Multiplatform coordinate whose Android variant IS it, found
 * by reading that module's metadata rather than from a table of known pairs.
 */
internal class IosPreviewDependencies(
    projectRoot: String,
    private val fetcher: ArtifactFetcher = HttpArtifactFetcher(),
) {
    private val cache = ResolverCache(projectRoot)
    private val resolver by lazy {
        MavenDependencyResolver(cache, ::IosVirtualFile, fetcher, variantRequest = VariantRequest.JVM)
    }

    /**
     * The equivalents found so far, kept on disk: a released module never changes what it aliases, so a match
     * found once is a match on every later open, and finding it costs a version list and several modules.
     * A miss is kept too, for a week ([MISS_TTL_SECONDS]), since a later release may alias it.
     */
    private val equivalentsFile = IosFiles.join(projectRoot, ".platform/caches/preview-equivalents.txt")
    private val equivalents: HashMap<Coordinate, Coordinate?> by lazy { loadEquivalents() }

    /**
     * androidx version -> the Compose Multiplatform version that aliased it. androidx Compose ships `ui`,
     * `foundation`, `runtime`, `animation` and their parts at ONE version, and a Compose Multiplatform release
     * aliases all of them, so after the first is found the rest are a single module read each.
     */
    private val hints = HashMap<String, String>()

    /**
     * The desktop jars [declared] resolves to, with the Kotlin standard library. Empty when offline.
     *
     * The androidx-to-Compose-Multiplatform swap applies to the WHOLE graph, not only to what the project
     * declares: a Compose Multiplatform library may itself depend on an androidx Compose artifact, and
     * androidx's desktop build of, say, the runtime is a stub that throws "Implemented only in JetBrains fork".
     * So after each resolve, every androidx Compose artifact that has an equivalent is excluded everywhere and
     * its equivalent added, until a resolve brings in nothing new to swap.
     */
    suspend fun classpath(declared: List<Coordinate>, progress: ProgressReporter = IosDependencies.SilentProgress): List<String> {
        val roots = (declared.map { desktopEquivalent(it) ?: it } + IosDependencies.STDLIB).distinct()
        val swapped = LinkedHashMap<String, Coordinate>()
        repeat(MAX_ROUNDS) { round ->
            val all = (roots + swapped.values).distinct()
            val exclusions = swapped.keys.map { Exclusion(it.substringBefore(':'), it.substringAfter(':')) }
            log.info("preview classpath: resolving ${all.size} root(s), ${swapped.size} androidx artifact(s) swapped (round ${round + 1})")
            val result = runCatching {
                resolver.resolve(
                    all, IosDependencies.REPOSITORIES, ConflictPolicy.NEWEST, progress,
                    emptyList(), all.associateWith { exclusions },
                )
            }.onFailure { log.warn("preview classpath resolve failed: ${it.message}") }.getOrNull() ?: return emptyList()
            if (result.unresolved.isNotEmpty()) log.warn("preview classpath incomplete: ${result.unresolved.joinToString()}")
            // A resolved artifact may carry its platform module's name (`runtime-desktop`); the swap and the
            // exclusion are of the root module (`runtime`), which is what every dependent names.
            val inGraph = result.resolved.map { it.coordinate }
                .associateBy({ "${it.group}:${rootName(it.name)}" }, { Coordinate(it.group, rootName(it.name), it.version) })
            val fresh = result.resolved.map { it.coordinate }
                .map { Coordinate(it.group, rootName(it.name), it.version) }
                .filter { it.group.startsWith(ANDROIDX_COMPOSE) && "${it.group}:${it.name}" !in swapped }
                .distinct()
                .mapNotNull { c ->
                    // Already in the graph as Compose Multiplatform (a root was swapped, and an older library
                    // still names the androidx one): that is its equivalent, whatever version it asked for.
                    // `androidx.compose.ui:ui:1.0.1` predates the alias, so no release would ever match it.
                    val present = inGraph[JETBRAINS_COMPOSE + c.group.removePrefix(ANDROIDX_COMPOSE) + ":" + c.name]
                    (present ?: desktopEquivalent(c))?.let { c to it }
                }
            if (fresh.isEmpty()) {
                val jars = result.resolved.flatMap { a -> listOf(a.classesRoot.path) + a.extraClassesRoots.map { it.path } }.distinct()
                log.info("preview classpath: ${jars.size} jar(s): ${result.resolved.joinToString { it.coordinate.toString() }}")
                return jars
            }
            for ((c, equivalent) in fresh) {
                log.info("preview classpath: $c runs as $equivalent")
                swapped["${c.group}:${c.name}"] = equivalent
            }
        }
        log.warn("preview classpath: still swapping after $MAX_ROUNDS rounds")
        return emptyList()
    }

    private fun rootName(name: String): String =
        PLATFORM_SUFFIXES.firstOrNull { name.endsWith(it) }?.let { name.removeSuffix(it) } ?: name

    /**
     * The Compose Multiplatform coordinate whose Android variant is [c], or null when [c] is not androidx
     * Compose or no release aliases it. The newest such release wins.
     */
    suspend fun desktopEquivalent(c: Coordinate): Coordinate? {
        if (!c.group.startsWith(ANDROIDX_COMPOSE)) return null
        equivalents[c]?.let { return it }
        if (equivalents.containsKey(c)) return null
        val group = JETBRAINS_COMPOSE + c.group.removePrefix(ANDROIDX_COMPOSE)
        log.info("preview classpath: looking for the Compose Multiplatform release that is $c")
        val found = findAliasing(group, c)?.let { Coordinate(group, c.name, it) }
        equivalents[c] = found
        if (found == null) log.warn("preview classpath: no Compose Multiplatform release aliases $c")
        else hints[c.version] = found.version
        saveEquivalents()
        return found
    }

    /**
     * The newest version of `group:c.name` whose Android variant is [c], reading as few modules as it can.
     *
     * Walking every release newest-first read one module per release, and a Compose artifact has hundreds
     * of releases: about 900 sequential downloads for one project, over a minute and a half. Instead: the
     * version a hint names, when it has one; otherwise a binary search, because the androidx version a
     * Compose Multiplatform release aliases rises with the release. A release that aliases nothing (one
     * published before the alias existed) is stepped over to its nearest neighbour that does.
     */
    private suspend fun findAliasing(group: String, c: Coordinate): String? {
        val seen = HashMap<String, String?>()
        fun aliasOf(version: String): String? = seen.getOrPut(version) {
            androidAliasOf(group, c.name, version)?.takeIf { it.first == c.group }?.second
        }
        hints[c.version]?.let { if (aliasOf(it) == c.version) return it }

        // Central only: Compose Multiplatform is not on Google's repository, and asking it is a wasted trip.
        val versions = resolver.availableVersions(group, c.name, listOf(IosDependencies.REPOSITORIES.first())).reversed()
        var lo = 0
        var hi = versions.lastIndex
        var best = -1
        while (lo <= hi) {
            val probe = nearestAliasing(versions, (lo + hi) / 2, lo, hi, ::aliasOf) ?: break
            if (MavenVersion.compare(aliasOf(versions[probe])!!, c.version) <= 0) {
                best = probe
                lo = probe + 1
            } else {
                hi = probe - 1
            }
        }
        if (best >= 0 && aliasOf(versions[best]) == c.version) return versions[best]
        // Not where the order says it would be. Look through what the search did not rule out, newest first,
        // rather than conclude there is none from an order a release broke.
        for (i in hi.coerceAtMost(versions.lastIndex) downTo lo.coerceAtLeast(0)) {
            if (aliasOf(versions[i]) == c.version) return versions[i]
        }
        return null
    }

    /** The index nearest [mid], within [lo]..[hi], of a version that aliases something; null if none near. */
    private fun nearestAliasing(versions: List<String>, mid: Int, lo: Int, hi: Int, aliasOf: (String) -> String?): Int? {
        for (step in 0..PROBE_REACH) {
            if (mid + step <= hi && aliasOf(versions[mid + step]) != null) return mid + step
            if (step > 0 && mid - step >= lo && aliasOf(versions[mid - step]) != null) return mid - step
        }
        return null
    }

    /** When each miss was recorded, in seconds since the epoch; a miss older than [MISS_TTL_SECONDS] is retried. */
    private val missedAt = HashMap<Coordinate, Long>()

    private fun loadEquivalents(): HashMap<Coordinate, Coordinate?> {
        val out = HashMap<Coordinate, Coordinate?>()
        if (!IosFiles.exists(equivalentsFile)) return out
        val now = nowSeconds()
        for (line in IosFiles.readText(equivalentsFile).lines()) {
            val from = parse(line.substringBefore('=', "")) ?: continue
            val value = line.substringAfter('=', "")
            if (value.startsWith(MISS)) {
                val at = value.removePrefix(MISS).toLongOrNull() ?: continue
                if (now - at > MISS_TTL_SECONDS) continue
                out[from] = null
                missedAt[from] = at
                continue
            }
            val to = parse(value) ?: continue
            out[from] = to
            hints[from.version] = to.version
        }
        return out
    }

    private fun saveEquivalents() {
        val text = equivalents.entries.map { (from, to) ->
            if (to != null) "$from=$to" else "$from=$MISS${missedAt.getOrPut(from) { nowSeconds() }}"
        }.sorted().joinToString("\n")
        IosFiles.parentOf(equivalentsFile)?.let { IosFiles.mkdirs(it) }
        IosFiles.writeText(equivalentsFile, text + "\n")
    }

    private fun nowSeconds(): Long = NSDate().timeIntervalSince1970.toLong()

    private fun parse(text: String): Coordinate? =
        text.split(':').takeIf { it.size == 3 && it.none(String::isBlank) }?.let { Coordinate(it[0], it[1], it[2]) }

    /**
     * The (group, version) of the androidx artifact a Compose Multiplatform module's Android variant is.
     *
     * Releases have said so two ways: older ones redirect the Android variant elsewhere (`available-at` an
     * androidx module), current ones publish the Android variant as a dependency on the androidx artifact of
     * the same name (`org.jetbrains.compose.ui:ui:1.11.1` on Android is `androidx.compose.ui:ui:1.11.2`,
     * `org.jetbrains.compose.material3:material3:1.9.0` is `androidx.compose.material3:material3:1.4.0`).
     */
    private fun androidAliasOf(group: String, name: String, version: String): Pair<String, String>? {
        val coordinate = Coordinate(group, name, version)
        val relative = cache.relativePath(coordinate, "module")
        val path = cache.fileFor(relative)
        val bytes = IosFiles.readBytes(path) ?: run {
            val url = IosDependencies.REPOSITORIES.first().url.removeSuffix("/") + "/" + relative
            fetcher.fetch(url)?.also { IosFiles.mkdirs(IosFiles.parentOf(path)!!); IosFiles.writeBytes(path, it) }
        } ?: return null
        val module = GradleModuleParser.parse(bytes) ?: return null
        val androidxGroup = ANDROIDX_COMPOSE + group.removePrefix(JETBRAINS_COMPOSE)
        // Whichever variant names the same-named androidx artifact is the Android one: the desktop variant IS
        // the implementation and never does. (Variant names vary by release: `android…`, `release…`.)
        for (variant in module.variants) {
            variant.availableAt?.let { at -> if (at.group == androidxGroup) return at.group to at.version }
            variant.dependencies.firstOrNull { it.group == androidxGroup && it.name == name }?.let { dep ->
                dep.version?.let { return dep.group to it }
            }
        }
        return null
    }

    private companion object {
        const val ANDROIDX_COMPOSE = "androidx.compose."
        const val MAX_ROUNDS = 5
        /** How far either side of a midpoint to look for a release that aliases anything. */
        const val PROBE_REACH = 8
        /** A miss is kept a week: a later Compose Multiplatform release may alias what none did before. */
        const val MISS_TTL_SECONDS = 7L * 24 * 60 * 60
        const val MISS = "-"
        val PLATFORM_SUFFIXES = listOf("-desktop", "-jvmstubs", "-jvm", "-android")
        const val JETBRAINS_COMPOSE = "org.jetbrains.compose."
        val log = Log.logger("ios-preview-deps")
    }
}
