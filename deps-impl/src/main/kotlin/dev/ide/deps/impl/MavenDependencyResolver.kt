package dev.ide.deps.impl

import dev.ide.deps.ArtifactKind
import dev.ide.deps.ConflictPolicy
import dev.ide.deps.DependencyResolver
import dev.ide.deps.Repository
import dev.ide.deps.ResolutionResult
import dev.ide.deps.ResolvedArtifact
import dev.ide.deps.VersionConflict
import dev.ide.model.Coordinate
import dev.ide.platform.ProgressReporter
import dev.ide.platform.log.Log
import dev.ide.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeText

/** A search hit from a repository's index: a coordinate plus its declared packaging (`jar`/`aar`/`pom`). */
data class ArtifactCandidate(val coordinate: Coordinate, val packaging: String)

/**
 * The Maven resolver behind deps-api. Fetches/parses POMs (merging the parent chain + imported BOMs for
 * properties and dependencyManagement), walks the transitive graph with Maven scope-narrowing and
 * exclusions, resolves version conflicts per [ConflictPolicy], extracts `classes.jar` from `.aar`s, and
 * caches everything on disk (so the same coordinate resolves offline next time). All I/O is through the
 * injected [fetcher]; [fileFor] turns a cached [Path] into a [VirtualFile] for the result (the caller
 * owns the concrete VFS, so the engine stays free of any filesystem impl).
 */
class MavenDependencyResolver(
    private val cache: ResolverCache,
    private val fileFor: (Path) -> VirtualFile,
    private val fetcher: ArtifactFetcher = HttpArtifactFetcher(),
    private val searchEndpoint: String = MAVEN_CENTRAL_SEARCH,
    /**
     * Concurrency is split because the two phases have opposite profiles. POM fetches are tiny and
     * latency-bound (the graph walk is a chain of small round-trips), so a high fan-out keeps the pipe full;
     * artifact downloads are bandwidth-bound, where too many parallel large transfers just thrash the link.
     */
    private val pomConcurrency: Int = 24,
    private val downloadConcurrency: Int = 8,
) : DependencyResolver {

    private val log = Log.logger("deps.resolver")


    /**
     * Effective-POM cache, keyed by coordinate and shared across `resolve` calls (released POMs are
     * immutable, so this is safe). A single Compose graph re-fetches the same AndroidX parent POMs and
     * the Compose BOM hundreds of times; memoizing the *effective* POM collapses that to one fetch+parse
     * each. `Optional.empty()` records a genuinely-absent POM so we don't keep re-probing the network.
     */
    private val effCache = ConcurrentHashMap<Coordinate, Optional<EffPom>>()

    override suspend fun resolve(
        coordinates: List<Coordinate>,
        repositories: List<Repository>,
        conflict: ConflictPolicy,
        progress: ProgressReporter,
        platforms: List<Coordinate>,
    ): ResolutionResult {
        val repos = repositories.ifEmpty { DEFAULT_REPOSITORIES }
        log.info(
            "resolve: ${coordinates.size} coordinate(s) ${coordinates.joinToString { it.toString() }} " +
                "over ${repos.size} repo(s) ${repos.joinToString { it.url }}" +
                if (platforms.isNotEmpty()) " · platforms ${platforms.joinToString { it.toString() }}" else "",
        )

        val seenVersions = LinkedHashMap<GA, MutableSet<String>>()  // every requested version, per artifact
        val directVersions = HashMap<GA, String>()                  // the version the user declared (for PINNED)
        val chosen = LinkedHashMap<GA, String>()                    // winning version, per artifact
        val edges = LinkedHashMap<GA, LinkedHashSet<GA>>()          // dependsOn graph (by artifact)
        val packagingByGa = HashMap<GA, String>()
        val walked = HashSet<Coordinate>()
        val unresolved = LinkedHashSet<Coordinate>()

        // Imported BOMs (Gradle `platform(...)`): merge their dependencyManagement into one version source
        // for versionless coordinates. Earlier platforms win on overlap (putIfAbsent); a BOM that can't be
        // fetched is surfaced as unresolved so the caller can flag it.
        val platformManaged = LinkedHashMap<GA, String>()
        for (p in platforms) {
            val eff = loadEffective(p, repos, HashSet())
            if (eff == null) { unresolved += p; continue }
            eff.managed.forEach { (ga, v) -> platformManaged.putIfAbsent(ga, v) }
        }

        val queue = ArrayDeque<Req>()
        for (c0 in coordinates) {
            // A blank version means "take it from a platform BOM" — fill it, or record it unresolved.
            val c = if (c0.version.isBlank()) {
                val v = platformManaged[c0.ga] ?: run { unresolved += c0; continue }
                c0.copy(version = v)
            } else c0
            directVersions[c.ga] = c.version
            queue.add(Req(c, emptySet(), direct = true))
        }

        fun pick(ga: GA): String {
            val versions = seenVersions[ga]!!
            return when (conflict) {
                ConflictPolicy.PINNED -> directVersions[ga] ?: MavenVersion.newest(versions)!!
                ConflictPolicy.NEWEST, ConflictPolicy.FAIL_ON_CONFLICT -> MavenVersion.newest(versions)!!
            }
        }

        // --- transitive walk ---------------------------------------------------------------------
        // BFS, but processed one *frontier wave* at a time: all graph bookkeeping (version pick, edges,
        // exclusions, the `walked` dedup) stays single-threaded — only the blocking POM fetch/parse of a
        // wave's coordinates runs in parallel. Folding the whole frontier's versions before picking is
        // equivalent to (and on diamonds, slightly tighter than) the old node-at-a-time walk: a GA still
        // resolves to the newest version requested, and the first req to reach a chosen (ga,version) owns
        // its exclusions — so resolution outcomes are unchanged, just far less wall-clock on big graphs.
        val processed = AtomicInteger(0)
        coroutineScope {
            val sem = Semaphore(pomConcurrency)
            while (queue.isNotEmpty() && chosen.size < MAX_NODES) {
                progress.checkCanceled()
                val frontier = ArrayList<Req>(queue.size)
                while (queue.isNotEmpty()) frontier += queue.removeFirst()

                // Record every version this frontier requests before picking, so a wave-mate's newer
                // version wins immediately (Maven newest-wins) rather than being walked redundantly.
                for (req in frontier) {
                    val ga = req.coord.ga
                    seenVersions.getOrPut(ga) { linkedSetOf() }.add(req.coord.version)
                    edges.getOrPut(ga) { linkedSetOf() }
                }

                // Pick winners; collect the not-yet-walked coordinates (first req to reach each owns it).
                val toWalk = LinkedHashMap<Coordinate, Req>()
                for (req in frontier) {
                    if (chosen.size >= MAX_NODES) break
                    val ga = req.coord.ga
                    val chosenCoord = Coordinate(ga.group, ga.name, pick(ga))
                    chosen[ga] = chosenCoord.version
                    if (chosenCoord in walked) continue
                    walked += chosenCoord
                    toWalk.putIfAbsent(chosenCoord, req)
                }
                if (toWalk.isEmpty()) continue

                // Fetch + parse this wave's effective POMs in parallel (the actual bottleneck).
                val loaded = toWalk.entries.map { (coord, req) ->
                    async(Dispatchers.IO) { sem.withPermit { Triple(coord, req, loadEffective(coord, repos, HashSet())) } }
                }.awaitAll()

                // Fold the results back into the graph single-threaded, in frontier order.
                for ((coord, req, eff) in loaded) {
                    val ga = coord.ga
                    if (eff == null) {
                        if (req.direct) unresolved += req.coord
                        continue
                    }
                    packagingByGa[ga] = eff.packaging
                    for (d in eff.dependencies) {
                        if (!d.isTransitivelyIncluded()) continue      // drop test/provided/system/optional
                        val childGa = GA(d.groupId, d.artifactId)
                        // A versionless transitive (one whose own POM left the version to dependencyManagement
                        // it didn't carry) is placeable iff a platform BOM manages it.
                        val version = d.version?.ifBlank { null } ?: platformManaged[childGa] ?: continue
                        if (childGa.excludedBy(req.exclusions)) continue
                        edges.getOrPut(ga) { linkedSetOf() }.add(childGa)
                        if (d.type.equals("aar", ignoreCase = true)) packagingByGa.putIfAbsent(childGa, "aar")
                        queue.add(Req(Coordinate(d.groupId, d.artifactId, version), req.exclusions + d.exclusions, direct = false))
                    }
                    if (processed.incrementAndGet() % 4 == 0) progress.report(-1.0, "Resolving ${coord.name}…")
                }
            }
        }

        log.info("graph walk done: ${chosen.size} node(s) chosen, ${unresolved.size} POM(s) unresolved so far")

        // --- download + assemble results ---------------------------------------------------------
        // Two passes, so the build-critical jars/aars all land first at full concurrency and editor-only
        // sources never share a download slot with (or queue ahead of) a jar something is waiting to build
        // against. Pass 1 fetches + AAR-explodes every chosen artifact in parallel; pass 2 fetches the
        // matching `-sources.jar`s (optional, often absent → a 404 we don't want on the critical path) and
        // grafts them onto the already-resolved artifacts.
        val total = chosen.size.coerceAtLeast(1)
        val downloaded = AtomicInteger(0)
        val outcomes = coroutineScope {
            val sem = Semaphore(downloadConcurrency)
            chosen.entries.map { (ga, ver) ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        progress.checkCanceled()
                        val coord = Coordinate(ga.group, ga.name, ver)
                        val packaging = packagingByGa[ga] ?: "jar"
                        if (packaging.equals("pom", ignoreCase = true)) return@withPermit null  // BOM/metadata-only
                        val kind = if (packaging.equals("aar", ignoreCase = true)) ArtifactKind.AAR else ArtifactKind.JAR
                        val ext = if (kind == ArtifactKind.AAR) "aar" else "jar"

                        val artifact = fetchArtifact(coord, ext, repos)
                        // A failed fetch of ANY chosen artifact — direct OR transitive — is recorded as
                        // unresolved. Previously only direct failures were surfaced, so a flaky-network drop of
                        // a transitive (e.g. `androidx.activity:activity`, ComponentActivity's home, pulled by
                        // `activity-compose`) silently vanished from the closure and got persisted as if the
                        // library were complete — invisible until completion couldn't resolve the type.
                        if (artifact == null) return@withPermit DownloadOutcome(coord, null, failed = true)
                        // A corrupt/unreadable artifact (e.g. a truncated download, or a non-zip body from a
                        // captive portal) must not abort the WHOLE resolve — treat just this one as unresolved.
                        val classesRoot = runCatching {
                            if (kind == ArtifactKind.AAR) fileFor(extractClassesJar(coord, artifact)) else fileFor(artifact)
                        }.getOrElse {
                            log.warn("artifact unusable: $coord (${it.javaClass.simpleName}: ${it.message})")
                            return@withPermit DownloadOutcome(coord, null, failed = true)
                        }
                        val dependsOn = edges[ga].orEmpty().mapNotNull { c -> chosen[c]?.let { Coordinate(c.group, c.name, it) } }
                        progress.report(downloaded.incrementAndGet().toDouble() / total, "Downloaded ${coord.name}")
                        DownloadOutcome(coord, ResolvedArtifact(coord, kind, classesRoot, sourcesRoot = null, dependsOn), failed = false)
                    }
                }
            }.awaitAll()
        }
        val resolved = ArrayList<ResolvedArtifact>()
        for (o in outcomes) {
            if (o == null) continue
            if (o.artifact != null) resolved += o.artifact else if (o.failed) unresolved += o.coord
        }
        log.info("downloads done: ${resolved.size} artifact(s) fetched")

        // Pass 2: editor-only sources, off the build-critical path. Failures are ignored (sources are best-
        // effort) and never affect `unresolved`.
        if (resolved.isNotEmpty()) coroutineScope {
            val sem = Semaphore(downloadConcurrency)
            val withSources = resolved.map { art ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        progress.checkCanceled()
                        val sources = runCatching { fetchArtifact(art.coordinate, "jar", repos, classifier = "sources") }
                            .getOrNull()?.let { fileFor(it) } ?: return@withPermit art
                        art.copy(sourcesRoot = sources)
                    }
                }
            }.awaitAll()
            resolved.clear()
            resolved.addAll(withSources)
        }

        val conflicts = seenVersions
            .filterValues { it.size > 1 }
            .map { (ga, versions) -> VersionConflict(ga.toString(), versions.sortedWith(MavenVersion), chosen[ga] ?: versions.first()) }

        if (unresolved.isEmpty()) log.info("resolve done: ${resolved.size} artifact(s) resolved, none unresolved")
        else log.warn("resolve done: ${resolved.size} resolved, ${unresolved.size} UNRESOLVED: ${unresolved.joinToString { it.toString() }}")
        return ResolutionResult(resolved, unresolved.toList(), conflicts)
    }

    /** Repository index search (Maven Central solr by default) — powers the "Add dependency" picker. */
    suspend fun search(query: String, limit: Int = 25): List<ArtifactCandidate> {
        if (query.isBlank()) return emptyList()
        val url = "$searchEndpoint?q=${URLEncoder.encode(query.trim(), "UTF-8")}&rows=$limit&wt=json"
        val bytes = runCatching { fetcher.fetch(url) }.getOrNull() ?: return emptyList()
        val root = runCatching { Json.parse(String(bytes, Charsets.UTF_8)) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val docs = (root["response"] as? Map<*, *>)?.get("docs") as? List<*> ?: return emptyList()
        return docs.mapNotNull { doc ->
            val d = doc as? Map<*, *> ?: return@mapNotNull null
            val g = d["g"] as? String ?: return@mapNotNull null
            val a = d["a"] as? String ?: return@mapNotNull null
            val v = (d["latestVersion"] ?: d["v"]) as? String ?: return@mapNotNull null
            ArtifactCandidate(Coordinate(g, a, v), (d["p"] as? String) ?: "jar")
        }
    }

    // --- effective POM (parent chain + imported BOM merge) -------------------------------------

    private data class EffPom(
        val coordinate: Coordinate,
        val packaging: String,
        val properties: Map<String, String>,
        val managed: Map<GA, String>,
        val dependencies: List<PomDependency>,
    )

    private fun loadEffective(coord: Coordinate, repos: List<Repository>, visiting: MutableSet<Coordinate>): EffPom? {
        effCache[coord]?.let { return it.orElse(null) }   // immutable released POMs → safe to memoize
        if (!visiting.add(coord)) return null   // parent/import cycle — return without caching the broken result
        try {
            val raw = parseRawPom(coord, repos) ?: run { effCache[coord] = Optional.empty(); return null }

            val props = LinkedHashMap<String, String>()
            val managed = LinkedHashMap<GA, String>()

            raw.parent?.let { parent ->
                loadEffective(parent, repos, visiting)?.let { p ->
                    props.putAll(p.properties)
                    managed.putAll(p.managed)
                }
            }
            props.putAll(raw.properties)

            for (m in raw.managed) {
                val g = resolveProperties(m.ga.group, props, coord) ?: continue
                val a = resolveProperties(m.ga.name, props, coord) ?: continue
                val v = normalizeVersion(resolveProperties(m.version, props, coord))
                if (m.scope.equals("import", ignoreCase = true) && v != null) {
                    loadEffective(Coordinate(g, a, v), repos, visiting)?.managed?.forEach { (ga, ver) -> managed.putIfAbsent(ga, ver) }
                } else if (v != null) {
                    managed[GA(g, a)] = v   // local management overrides inherited
                }
            }

            val deps = raw.dependencies.mapNotNull { d ->
                val g = resolveProperties(d.groupId, props, coord) ?: return@mapNotNull null
                val a = resolveProperties(d.artifactId, props, coord) ?: return@mapNotNull null
                val v = normalizeVersion(resolveProperties(d.version, props, coord)) ?: managed[GA(g, a)]
                d.copy(
                    groupId = g,
                    artifactId = a,
                    version = v,
                    type = resolveProperties(d.type, props, coord) ?: "jar",
                )
            }
            val eff = EffPom(coord, raw.packaging, props, managed, deps)
            effCache[coord] = Optional.of(eff)
            return eff
        } finally {
            visiting.remove(coord)
        }
    }

    /**
     * Reduce a Maven version *specification* to a single concrete version this resolver can fetch. A plain
     * version (`1.9.3`) passes through. A range or hard-pin — AndroidX pins every same-group dependency as
     * `[1.9.3]`, and others use `[a,b)` etc. — is collapsed: a single value (`[V]`) becomes `V`; a comma
     * range yields its lower bound (else upper), a real version that conflict resolution can still bump.
     * Without this, `[1.9.3]` is treated as a literal version, its POM URL 404s, and the whole subtree
     * (e.g. `androidx.activity:activity`, which carries `ComponentActivity`) is silently dropped.
     */
    private fun normalizeVersion(raw: String?): String? {
        val v = raw?.trim()?.ifBlank { null } ?: return null
        if (v.first() != '[' && v.first() != '(') return v
        val inner = v.trim('[', ']', '(', ')', ' ')
        if (inner.isEmpty()) return null
        if (!inner.contains(',')) return inner          // hard pin: [V]
        val (lower, upper) = inner.split(',', limit = 2).let { it[0].trim() to it.getOrElse(1) { "" }.trim() }
        return lower.ifBlank { upper.ifBlank { null } }
    }

    private fun parseRawPom(coord: Coordinate, repos: List<Repository>): RawPom? {
        val bytes = fetchBytes(cache.relativePath(coord, "pom"), reposFor(coord, repos)) ?: return null
        return runCatching { PomParser.parse(bytes) }.getOrNull()
    }

    // --- download helpers ----------------------------------------------------------------------

    /**
     * Returns the cached path for an artifact, fetching it if absent. Null when no repo carries it. Unlike
     * the POM path ([fetchBytes]), the bytes stream socket → disk (never resident in heap) — jars/aars are
     * large and many download in parallel.
     */
    private fun fetchArtifact(coord: Coordinate, ext: String, repos: List<Repository>, classifier: String? = null): Path? {
        val rel = cache.relativePath(coord, ext, classifier)
        if (cache.exists(rel)) return cache.fileFor(rel)
        val ordered = reposFor(coord, repos)
        return cache.writeStreaming(rel) { tmp ->
            ordered.any { repo ->
                val url = repo.url.removeSuffix("/") + "/" + rel
                log.debug("GET jar: $url")
                val outcome = runCatching { fetcher.fetchTo(url, tmp) }
                // A thrown error (not a 404, which returns false) is the real "why it failed": host
                // unreachable, TLS, timeout, a 5xx. Log it so an on-device resolution failure is diagnosable.
                outcome.exceptionOrNull()?.let { log.warn("download failed: $url (${it.javaClass.simpleName}: ${it.message})") }
                outcome.getOrDefault(false)
            }
        }
    }

    /**
     * Order [repos] by where [coord] most likely lives, so the first probe is usually a hit instead of a
     * wasted 404. AndroidX / Google / Firebase artifacts only exist on Google Maven — trying Maven Central
     * first for the whole Compose graph burns one round-trip per artifact. Stable: a non-Google coordinate
     * keeps the caller's order. The fallthrough still tries every repo, so nothing becomes unresolvable.
     */
    private fun reposFor(coord: Coordinate, repos: List<Repository>): List<Repository> {
        val g = coord.group
        val googleHosted = g.startsWith("androidx.") || g.startsWith("com.android") ||
            g.startsWith("com.google.android") || g.startsWith("com.google.firebase")
        if (!googleHosted) return repos
        return repos.sortedByDescending { it.url.contains("dl.google.com") }   // stable: Google repos move to front
    }

    /** Cache-first byte fetch of a Maven-layout [rel]ative path: try the disk store, then each repo. */
    private fun fetchBytes(rel: String, repos: List<Repository>): ByteArray? {
        cache.read(rel)?.let { return it }
        for (repo in repos) {
            val url = repo.url.removeSuffix("/") + "/" + rel
            log.debug("GET pom: $url")
            val outcome = runCatching { fetcher.fetch(url) }
            // A thrown error (not a 404, which returns null) is the real cause of an unresolvable POM:
            // host unreachable, TLS, timeout, a 5xx. Log it so an on-device failure is diagnosable.
            outcome.exceptionOrNull()?.let { log.warn("POM fetch failed: $url (${it.javaClass.simpleName}: ${it.message})") }
            val bytes = outcome.getOrNull()
            if (bytes != null) { cache.write(rel, bytes); return bytes }
        }
        return null
    }

    /**
     * Explode an `.aar` into the cache, returning its `classes.jar`. Alongside it, the AAR's `res/`,
     * `assets/`, `jni/` and `AndroidManifest.xml` are unpacked into the same exploded dir — so a consumer
     * (the IDE's resource model / Android build) can find a library's resources as a `res/` sibling of its
     * `classes.jar` and its package name in the sibling manifest, with no further unzip at use time.
     */
    private fun extractClassesJar(coord: Coordinate, aar: Path): Path {
        val dir = cache.explodedDir(coord)
        Files.createDirectories(dir)
        val classesJar = dir.resolve("classes.jar")
        val marker = dir.resolve(".extracted")
        // Re-extract if a prior (pre-manifest) explosion left no manifest, so the package name is available.
        if (Files.isRegularFile(marker) && Files.isRegularFile(dir.resolve("AndroidManifest.xml"))) {
            // Heal a classes.jar an older build left as a zero-entry zip (resource-only AAR): unusable on ART,
            // where ZipFile rejects an empty archive. Cheap (central-directory read) and only rewrites the bad ones.
            if (Files.isRegularFile(classesJar) && !isUsableJar(classesJar)) writeManifestOnlyJar(classesJar)
            return classesJar
        }

        var foundClasses = false
        ZipInputStream(Files.newInputStream(aar)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name == "classes.jar" -> {
                        Files.copy(zin, classesJar, StandardCopyOption.REPLACE_EXISTING)
                        foundClasses = true
                    }
                    !entry.isDirectory && (name.startsWith("res/") || name.startsWith("assets/") ||
                        name.startsWith("jni/") || name == "AndroidManifest.xml") -> {
                        val target = dir.resolve(name).normalize()
                        if (target.startsWith(dir)) { // zip-slip guard
                            Files.createDirectories(target.parent)
                            Files.copy(zin, target, StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        // resource-only AAR (no code) → a classes.jar with a single manifest entry (see [writeManifestOnlyJar]).
        if (!foundClasses) writeManifestOnlyJar(classesJar)
        marker.writeText("")
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

    private data class Req(val coord: Coordinate, val exclusions: Set<GA>, val direct: Boolean)

    /** Result of one parallel download slot: a resolved artifact, or a coord whose artifact [failed] to fetch
     *  (any such failure — direct or transitive — is surfaced as unresolved, never silently dropped). */
    private data class DownloadOutcome(val coord: Coordinate, val artifact: ResolvedArtifact?, val failed: Boolean)

    private companion object {
        const val MAX_NODES = 4000
    }
}

private val Coordinate.ga: GA get() = GA(group, name)

private fun PomDependency.isTransitivelyIncluded(): Boolean =
    !optional && (scope.equals("compile", true) || scope.equals("runtime", true))

private fun GA.excludedBy(exclusions: Set<GA>): Boolean = exclusions.any {
    (it.group == "*" || it.group == group) && (it.name == "*" || it.name == name)
}

const val MAVEN_CENTRAL_SEARCH = "https://search.maven.org/solrsearch/select"

/** The two repositories almost every Android/Java dependency lives in: Maven Central and Google Maven. */
val DEFAULT_REPOSITORIES = listOf(
    Repository("Maven Central", "https://repo1.maven.org/maven2"),
    Repository("Google", "https://dl.google.com/android/maven2"),
)
