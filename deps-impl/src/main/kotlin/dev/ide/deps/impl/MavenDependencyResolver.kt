package dev.ide.deps.impl

import dev.ide.deps.ArtifactKind
import dev.ide.deps.ConflictPolicy
import dev.ide.deps.DependencyResolver
import dev.ide.deps.Repository
import dev.ide.deps.ResolutionResult
import dev.ide.deps.ResolvedArtifact
import dev.ide.deps.VersionConflict
import dev.ide.model.Coordinate
import dev.ide.model.Exclusion
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
    /**
     * Consumer attributes for Gradle Module Metadata variant selection (which artifact variant of a
     * KMP/multi-variant library to pick). Defaults to an Android compile classpath, so a KMP library
     * resolves to its `-android` variant. A pure-JVM consumer can pass a `jvm` request.
     */
    private val variantRequest: VariantRequest = VariantRequest(),
) : DependencyResolver {

    private val log = Log.logger("deps.resolver")


    /**
     * Effective-POM cache, keyed by coordinate and shared across `resolve` calls (released POMs are
     * immutable, so this is safe). A single Compose graph re-fetches the same AndroidX parent POMs and
     * the Compose BOM hundreds of times; memoizing the *effective* POM collapses that to one fetch+parse
     * each. `Optional.empty()` records a genuinely-absent POM so we don't keep re-probing the network.
     */
    private val effCache = ConcurrentHashMap<Coordinate, Optional<EffPom>>()

    /** Gradle Module Metadata (`*.module`) cache, sibling to [effCache] — also immutable once released.
     *  `Optional.empty()` records a coordinate with no `.module` so we don't re-probe (it took the POM path). */
    private val moduleCache = ConcurrentHashMap<Coordinate, Optional<GradleModule>>()

    override suspend fun resolve(
        coordinates: List<Coordinate>,
        repositories: List<Repository>,
        conflict: ConflictPolicy,
        progress: ProgressReporter,
        platforms: List<Coordinate>,
        exclusions: Map<Coordinate, List<Exclusion>>,
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
        // GMM variant selection can redirect an artifact to a different file/coordinate (a KMP root's
        // `-android` platform module) and publish more than one file. Keyed by the ORIGINAL ga (so
        // version/edges/alignment stay coherent); the download pass fetches these instead of the default path.
        val gmmArtifacts = HashMap<GA, List<ArtifactRef>>()
        val gmmSources = HashMap<GA, ArtifactRef>()
        // The version each ga's metadata (artifacts/packaging/edges) was captured at during the walk. When a
        // post-walk alignment/constraint bumps `chosen[ga]` to a version NOT equal to this, the captured
        // gmmArtifacts point at the OLD version's files; the download re-loads the chosen version's metadata.
        val metaVersion = HashMap<GA, String>()
        // The capabilities each chosen module provides — for cross-module capability conflict resolution
        // (two modules providing the same capability → evict the loser, e.g. google-collections vs guava).
        val capabilitiesByGa = HashMap<GA, List<GmmCapability>>()
        // GMM `dependencyConstraints` (atomic-group / BOM-style alignment) and `strictly` pins, applied after
        // the walk: constraints align a GA *only if it is in the graph*; a strict version forces it.
        val constraints = LinkedHashMap<GA, LinkedHashSet<String>>()
        val strictVersions = HashMap<GA, String>()
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
            // Seed this direct dependency's subtree with the caller's declared exclusions (keyed by the
            // as-passed coordinate, so a versionless one matches its blank-version form). They merge with the
            // POM-declared exclusions accumulated below, exactly like Gradle/Maven per-declaration excludes.
            val seedExclusions = exclusions[c0].orEmpty().mapTo(HashSet()) { GA(it.group, it.name) }
            queue.add(Req(c, seedExclusions, direct = true))
        }

        fun pick(ga: GA): String {
            // A `strictly` pin forces the version (unless the user explicitly PINNED it themselves).
            strictVersions[ga]?.let { if (!(conflict == ConflictPolicy.PINNED && ga in directVersions)) return it }
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

                // Fetch + parse this wave's metadata in parallel (the actual bottleneck). [loadNode] reads
                // Gradle Module Metadata when present (variant selection), falling back to the POM otherwise.
                val loaded = toWalk.entries.map { (coord, req) ->
                    async(Dispatchers.IO) { sem.withPermit { Triple(coord, req, loadNode(coord, repos)) } }
                }.awaitAll()

                // Fold the results back into the graph single-threaded, in frontier order.
                for ((coord, req, node) in loaded) {
                    val ga = coord.ga
                    if (node == null) {
                        if (req.direct) unresolved += req.coord
                        continue
                    }
                    packagingByGa[ga] = node.packaging
                    metaVersion[ga] = coord.version
                    node.artifacts.takeIf { it.isNotEmpty() }?.let { gmmArtifacts[ga] = it }
                    node.sources?.let { gmmSources[ga] = it }
                    capabilitiesByGa[ga] = node.capabilities
                    // Constraints align a GA only if it ends up in the graph — collect them, don't enqueue.
                    for (c in node.constraints) {
                        val cga = GA(c.group, c.name)
                        c.version?.let { constraints.getOrPut(cga) { linkedSetOf() }.add(it) }
                        c.strictly?.let { strictVersions.putIfAbsent(cga, it) }
                    }
                    for (d in node.transitives) {
                        val childGa = GA(d.group, d.name)
                        // A `strictly` pin forces the version; else a versionless transitive (one whose own
                        // metadata left the version to a BOM it didn't carry) is placeable iff a BOM manages it.
                        val version = d.strictly?.ifBlank { null } ?: d.version?.ifBlank { null } ?: platformManaged[childGa] ?: continue
                        if (childGa.excludedBy(req.exclusions)) continue
                        d.strictly?.ifBlank { null }?.let { strictVersions.putIfAbsent(childGa, it) }
                        edges.getOrPut(ga) { linkedSetOf() }.add(childGa)
                        if (d.type.equals("aar", ignoreCase = true)) packagingByGa.putIfAbsent(childGa, "aar")
                        queue.add(Req(Coordinate(d.group, d.name, version), req.exclusions + d.exclusions, direct = false))
                    }
                    if (processed.incrementAndGet() % 4 == 0) progress.report(-1.0, "Resolving ${coord.name}…")
                }
            }
        }

        log.info("graph walk done: ${chosen.size} node(s) chosen, ${unresolved.size} POM(s) unresolved so far")

        // --- version alignment -------------------------------------------------------------------
        // Members of an alignment group that ended up in the graph all snap to the newest version any of
        // them requested. Done AFTER the full walk so it sees every requested version regardless of the
        // order members were discovered in (a sibling's newer version may surface waves later than the one
        // that pulled in the member being aligned). Rewriting `chosen` is enough: the download pass below
        // and the `dependsOn` edges both read the per-`ga` version from `chosen`, so the bumped member is
        // fetched at the aligned version and its edges point at the aligned closure. See [ALIGNMENT_GROUPS].
        for (group in ALIGNMENT_GROUPS) {
            val present = group.filter { it in chosen }
            if (present.size < 2) continue   // a single member can't collide with a sibling — nothing to align
            val aligned = MavenVersion.newest(present.flatMap { seenVersions[it].orEmpty() }) ?: continue
            for (ga in present) {
                // Under PINNED the user's explicit declaration wins over alignment (a transitively-pulled
                // sibling still aligns, which is what removes the duplicate class).
                if (conflict == ConflictPolicy.PINNED && ga in directVersions) continue
                val cur = chosen[ga]
                if (cur != aligned) {
                    log.info("align $ga: $cur -> $aligned (alignment group)")
                    chosen[ga] = aligned
                }
            }
        }

        // GMM `dependencyConstraints`: align a GA that ended up in the graph to the newest of its requested
        // and constrained versions — the atomic-group / BOM-style alignment Gradle applies (e.g. all
        // `androidx.datastore:*` snap together), so two versions of one artifact can't collide at dex.
        for ((ga, cvs) in constraints) {
            if (ga !in chosen || ga in strictVersions) continue
            if (conflict == ConflictPolicy.PINNED && ga in directVersions) continue
            val aligned = MavenVersion.newest(seenVersions[ga].orEmpty() + cvs) ?: continue
            if (chosen[ga] != aligned) { log.info("constrain $ga: ${chosen[ga]} -> $aligned (dependencyConstraint)"); chosen[ga] = aligned }
        }
        // `strictly` pins force the version (unless the user PINNED it). Done last so a strict wins over alignment.
        for ((ga, v) in strictVersions) {
            if (ga !in chosen || (conflict == ConflictPolicy.PINNED && ga in directVersions)) continue
            if (chosen[ga] != v) { log.info("strict $ga: ${chosen[ga]} -> $v (strictly)"); chosen[ga] = v }
        }

        // --- cross-module capability conflict resolution -----------------------------------------
        // Two modules can provide the SAME Gradle capability — a module that absorbed/relocated another (guava
        // declares `com.google.collections:google-collections`; an old `-ktx` companion folded into its main
        // artifact). Gradle picks ONE (the highest capability version, like `selectHighestVersion`) and evicts
        // the loser entirely; the loser is substituted by the winner everywhere it was depended on. We record
        // the substitution so an evicted *direct* dependency still resolves to its winner during the prune below.
        val substitutions = HashMap<GA, GA>()
        run {
            fun caps(ga: GA): List<GmmCapability> =
                capabilitiesByGa[ga] ?: listOf(GmmCapability(ga.group, ga.name, chosen[ga]))
            val providers = LinkedHashMap<GA, LinkedHashMap<GA, String?>>()   // capability ga -> (provider ga -> cap version)
            for (ga in chosen.keys) for (c in caps(ga)) providers.getOrPut(GA(c.group, c.name)) { LinkedHashMap() }.putIfAbsent(ga, c.version)
            val evicted = LinkedHashSet<GA>()
            for ((capGa, provMap) in providers) {
                if (provMap.size < 2) continue   // only one provider — no conflict
                fun capVersion(p: GA) = provMap[p] ?: chosen[p] ?: "0"
                val winner = provMap.keys.reduce { a, b ->
                    val cmp = MavenVersion.compare(capVersion(a), capVersion(b))
                    when {
                        cmp != 0 -> if (cmp > 0) a else b                        // newest capability version wins
                        (a in directVersions) != (b in directVersions) -> if (a in directVersions) a else b  // prefer a declared dep
                        else -> if (a.toString() <= b.toString()) a else b       // deterministic
                    }
                }
                for (loser in provMap.keys) if (loser != winner) {
                    evicted += loser
                    substitutions[loser] = winner
                    log.info("capability $capGa: $winner supersedes $loser (evicted)")
                }
            }
            if (evicted.isNotEmpty()) {
                chosen.keys.removeAll(evicted)
                evicted.forEach { gmmArtifacts.remove(it); gmmSources.remove(it) }
            }
        }

        // --- prune to the reachable graph --------------------------------------------------------
        // Gradle's resolved graph is the closure REACHABLE from the roots through the chosen edges: when a module
        // is evicted (capability conflict) or superseded, its now-unreachable transitives drop out. Roots are the
        // directly-declared coordinates, redirected through any capability substitution (an evicted direct dep
        // resolves to its winner). (Edges accumulate across the versions a `ga` was walked at, so this prunes
        // conservatively — an evicted module's exclusive subtree goes, while a stale transitive of a
        // version-superseded module may linger; it never drops a still-reachable node.)
        run {
            val roots = directVersions.keys.map { substitutions[it] ?: it }.filter { it in chosen }
            val reachable = LinkedHashSet<GA>()
            val stack = ArrayDeque(roots)
            while (stack.isNotEmpty()) {
                val ga = stack.removeLast()
                if (!reachable.add(ga)) continue
                for (child in edges[ga].orEmpty()) {
                    val s = substitutions[child] ?: child
                    if (s in chosen) stack.addLast(s)
                }
            }
            val pruned = chosen.keys.filter { it !in reachable }
            if (pruned.isNotEmpty()) {
                chosen.keys.removeAll(pruned.toSet())
                pruned.forEach { gmmArtifacts.remove(it); gmmSources.remove(it) }
                log.info("pruned ${pruned.size} unreachable node(s): ${pruned.joinToString()}")
            }
        }

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
                        // A post-walk alignment/constraint can bump `chosen[ga]` to a version we never walked, so
                        // the captured gmmArtifacts/packaging point at the OLD version's files (e.g. lifecycle-
                        // runtime-ktx chosen at 2.10.0 but captured at 2.6.1 → downloading the 2.6.1 aar under a
                        // 2.10.0 label, duplicating FlowExtKt against lifecycle-runtime-android). Re-load the
                        // chosen version's metadata so we fetch ITS file and follow ITS `available-at`.
                        val bumpedNode = if (metaVersion[ga]?.let { it != ver } == true) loadNode(coord, repos) else null
                        // GMM variant selection may have redirected this artifact (a KMP `-android` module) and
                        // may publish more than one file; otherwise fetch by the default packaging URL (POM path).
                        val gmm = (bumpedNode?.artifacts ?: gmmArtifacts[ga]).orEmpty()
                        val packaging = (bumpedNode?.packaging ?: packagingByGa[ga]) ?: "jar"
                        if (gmm.isEmpty() && packaging.equals("pom", ignoreCase = true)) return@withPermit null  // BOM/metadata-only
                        val primary = gmm.firstOrNull()
                        val kind = primary?.kind
                            ?: if (packaging.equals("aar", ignoreCase = true)) ArtifactKind.AAR else ArtifactKind.JAR

                        val fetch = if (primary != null) fetchRel(primary.coord, primary.rel, repos)
                            else fetchArtifact(coord, if (kind == ArtifactKind.AAR) "aar" else "jar", repos)
                        // A failed fetch of ANY chosen artifact — direct OR transitive — is recorded as
                        // unresolved. Previously only direct failures were surfaced, so a flaky-network drop of
                        // a transitive (e.g. `androidx.activity:activity`, ComponentActivity's home, pulled by
                        // `activity-compose`) silently vanished from the closure and got persisted as if the
                        // library were complete — invisible until completion couldn't resolve the type.
                        if (fetch == null) return@withPermit DownloadOutcome(coord, null, failed = true)
                        // A corrupt/unreadable artifact (e.g. a truncated download, or a non-zip body from a
                        // captive portal) must not abort the WHOLE resolve — treat just this one as unresolved.
                        val classesRoot = runCatching {
                            classRootOf(kind, primary?.coord ?: coord, fetch.path)
                        }.getOrElse {
                            log.warn("artifact unusable: $coord (${it.javaClass.simpleName}: ${it.message})")
                            return@withPermit DownloadOutcome(coord, null, failed = true)
                        }
                        // Extra files of a multi-file GMM variant join the classpath (rare; usually none).
                        val extraRoots = gmm.drop(1).mapNotNull { a ->
                            fetchRel(a.coord, a.rel, repos)?.let { f -> runCatching { classRootOf(a.kind, a.coord, f.path) }.getOrNull() }
                        }
                        val dependsOn = edges[ga].orEmpty().mapNotNull { c -> chosen[c]?.let { Coordinate(c.group, c.name, it) } }
                        // Honest progress: only announce a real network download. A cache hit advances the count
                        // silently (null message), so a fully-cached re-resolve reports no "downloading" activity.
                        val frac = downloaded.incrementAndGet().toDouble() / total
                        progress.report(frac, if (fetch.fromNetwork) "Downloading ${coord.name}…" else null)
                        DownloadOutcome(coord, ResolvedArtifact(coord, kind, classesRoot, sourcesRoot = null, dependsOn, extraClassesRoots = extraRoots), failed = false)
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
        // effort) and never affect `unresolved`. A GMM-derived sources file (the KMP case, where the sources
        // jar lives under the `-android` coordinate / a non-default name) is preferred over the classifier guess.
        if (resolved.isNotEmpty()) coroutineScope {
            val sem = Semaphore(downloadConcurrency)
            val withSources = resolved.map { art ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        progress.checkCanceled()
                        val src = gmmSources[art.coordinate.ga]
                        val sources = runCatching {
                            if (src != null) fetchRel(src.coord, src.rel, repos) else fetchArtifact(art.coordinate, "jar", repos, classifier = "sources")
                        }.getOrNull()?.let { fileFor(it.path) } ?: return@withPermit art
                        art.copy(sourcesRoot = sources)
                    }
                }
            }.awaitAll()
            resolved.clear()
            resolved.addAll(withSources)
        }

        val conflicts = seenVersions
            .filterKeys { it in chosen }   // don't report a conflict for an evicted/pruned module
            .filterValues { it.size > 1 }
            .map { (ga, versions) -> VersionConflict(ga.toString(), versions.sortedWith(MavenVersion), chosen[ga] ?: versions.first()) }

        // A coordinate is RETRIABLE when it's unresolved yet NOT recorded as a hard 404 (in the negative
        // cache) — i.e. it failed transiently (network/timeout), so a later online resolve may succeed.
        // When every failure is a known 404, re-walking won't help (the caller can stop retrying every open).
        val retriable = unresolved.any { c ->
            !cache.isKnownMissing(cache.relativePath(c, "pom")) &&
                !cache.isKnownMissing(cache.relativePath(c, "jar")) &&
                !cache.isKnownMissing(cache.relativePath(c, "aar"))
        }
        if (unresolved.isEmpty()) log.info("resolve done: ${resolved.size} artifact(s) resolved, none unresolved")
        else log.warn("resolve done: ${resolved.size} resolved, ${unresolved.size} UNRESOLVED${if (retriable) " (retriable)" else " (all hard 404)"}: ${unresolved.joinToString { it.toString() }}")
        return ResolutionResult(resolved, unresolved.toList(), conflicts, retriable = retriable)
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

    /**
     * List the published versions of [group]:[name] across [repos], newest-first — powers the Dependencies
     * screen's version picker / update flow. Reads each repo's `maven-metadata.xml` (the Maven version index)
     * directly via the [fetcher], NOT through the artifact cache: metadata is mutable (a new release rewrites
     * it), so a cached copy would hide newer versions. Versions are merged across repos and ordered by
     * [MavenVersion]. Empty when no repo carries the artifact or the network is unreachable.
     */
    suspend fun availableVersions(group: String, name: String, repos: List<Repository>): List<String> {
        if (group.isBlank() || name.isBlank()) return emptyList()
        val rel = "${group.replace('.', '/')}/$name/maven-metadata.xml"
        val merged = LinkedHashSet<String>()
        for (repo in reposFor(Coordinate(group, name, ""), repos)) {
            val url = repo.url.removeSuffix("/") + "/" + rel
            val bytes = runCatching { fetcher.fetch(url) }.getOrNull() ?: continue
            merged += parseMetadataVersions(bytes)
        }
        return merged.sortedWith(MavenVersion.reversed())
    }

    /** Extract the `<version>`s inside `maven-metadata.xml`'s `<versions>` block (falling back to the whole
     *  document if the block markers are absent). Regex-only so it works the same on the JVM and ART. */
    private fun parseMetadataVersions(bytes: ByteArray): List<String> {
        val text = String(bytes, Charsets.UTF_8)
        val block = text.substringAfter("<versions>", "").substringBefore("</versions>")
        val src = block.ifBlank { text }
        return METADATA_VERSION.findAll(src).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
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

    // --- Gradle Module Metadata (variant selection) -------------------------------------------

    /** A resolved per-coordinate metadata node: its transitives + version constraints, its packaging, the
     *  explicit GMM artifact file(s) (empty = the POM path → fetch by the default packaging URL), and an
     *  optional GMM-derived sources jar (so KMP libs attach sources instead of a root-coordinate guess). */
    private data class ResolvedNode(
        val packaging: String,
        val transitives: List<ChildDep>,
        val constraints: List<ChildDep>,
        val artifacts: List<ArtifactRef>,
        val sources: ArtifactRef?,
        /** The Gradle capabilities this module provides — its implicit `group:name` plus any declared extras
         *  (e.g. guava also provides `com.google.collections:google-collections`). Drives conflict eviction. */
        val capabilities: List<GmmCapability>,
    )

    /** A transitive edge / constraint in resolver-neutral form. [strictly] (a Gradle `strictly` pin) forces
     *  the version — conflict resolution must not bump it. */
    private data class ChildDep(
        val group: String, val name: String, val version: String?, val strictly: String?,
        val exclusions: Set<GA>, val type: String,
    )

    /** A GMM-selected artifact to download in place of the requesting coordinate's default file. */
    private data class ArtifactRef(val coord: Coordinate, val rel: String, val kind: ArtifactKind)

    /** GMM-first metadata load: read the `.module` and select a variant, else fall back to the effective POM
     *  (so libraries without Gradle Module Metadata resolve exactly as before). */
    private fun loadNode(coord: Coordinate, repos: List<Repository>): ResolvedNode? {
        loadModule(coord, repos)?.let { gmm ->
            materializeModule(coord, coord, gmm, repos, hashSetOf(coord))?.let { return it }
        }
        return loadEffective(coord, repos, HashSet())?.toResolvedNode()
    }

    private fun loadModule(coord: Coordinate, repos: List<Repository>): GradleModule? {
        moduleCache[coord]?.let { return it.orElse(null) }
        val bytes = fetchBytes(cache.relativePath(coord, "module"), reposFor(coord, repos))
        val module = bytes?.let { GradleModuleParser.parse(it) }
        moduleCache[coord] = Optional.ofNullable(module)
        return module
    }

    /**
     * Turn a parsed [gmm] into a [ResolvedNode] for [requestCoord]: pick the variant, follow an
     * `available-at` redirect to the platform module that carries the files ([finalCoord]; cycle-guarded by
     * [visited]), and read that variant's dependencies + primary artifact. An override is emitted whenever the
     * chosen file isn't the default the download pass would build for [requestCoord] (the KMP `-android`
     * case). Null when no variant matches → the caller falls back to the POM.
     */
    private fun materializeModule(
        requestCoord: Coordinate, finalCoord: Coordinate, gmm: GradleModule,
        repos: List<Repository>, visited: MutableSet<Coordinate>,
    ): ResolvedNode? {
        val variant = GmmVariantSelector.select(gmm, variantRequest) ?: return null
        variant.availableAt?.let { at ->
            val target = Coordinate(at.group, at.name, at.version)
            if (!visited.add(target)) return null
            val targetGmm = loadModule(target, repos) ?: return null
            val node = materializeModule(requestCoord, target, targetGmm, repos, visited) ?: return null
            // `available-at` redirects only WHERE THE FILES live; the redirecting variant keeps its own identity,
            // so ITS capabilities belong to this module. AndroidX declares the relocation capability on exactly
            // this variant (e.g. `lifecycle-runtime`'s published variant declares it provides
            // `lifecycle-runtime-ktx`, the merged `-ktx` artifact); the target (`-android`) GMM doesn't re-declare
            // it. Union them in, else cross-module capability conflict resolution never sees the substitution and
            // the superseded standalone `-ktx` artifact isn't evicted — its classes then dex twice.
            if (variant.capabilities.isEmpty()) return node
            return node.copy(capabilities = (variant.capabilities + node.capabilities).distinctBy { GA(it.group, it.name) })
        }
        // Follow the api variant's deps AND the runtime variant's runtime-ONLY deps. GMM has no scope: a
        // runtime-only transitive (e.g. AppCompat's `emoji2-views-helper`, which auto-enables EmojiCompat in
        // AppCompatTextView) lives solely in the runtime variant's `dependencies`, and a packaged/dexed app
        // needs it — resolving only the api variant drops it, so both the built APK and the preview crash with
        // `NoClassDefFoundError`. Deduped by group:name (the api entry wins when both list it). A runtime
        // variant that itself redirects via `available-at` (rare) carries no direct deps, so it adds nothing.
        val apiDeps = variant.dependencies
        val runtimeOnly = GmmVariantSelector.runtimeVariant(gmm, variantRequest)
            ?.takeIf { it !== variant }
            ?.dependencies
            ?.filter { rd -> apiDeps.none { it.group == rd.group && it.name == rd.name } }
            ?: emptyList()
        val transitives = (apiDeps + runtimeOnly).map {
            ChildDep(it.group, it.name, it.version?.ifBlank { null }, it.strictly?.ifBlank { null }, it.excludes, "jar")
        }
        val constraints = variant.dependencyConstraints.map {
            ChildDep(it.group, it.name, it.version?.ifBlank { null }, it.strictly?.ifBlank { null }, emptySet(), "jar")
        }
        // The artifact's real path is the file's `url` (relative to the module dir), NOT its `name`: AGP
        // publishes an AAR with a logical name like `datastore-preferences-release.aar` but a url of
        // `datastore-preferences-android-1.1.1.aar`. All primary files are kept (a variant rarely ships more
        // than one jar/aar; the extras join the classpath).
        val artifacts = variant.files.filter { isPrimaryArtifact(it.url) }.map { f ->
            val kind = if (f.url.substringAfterLast('.', "jar").equals("aar", ignoreCase = true)) ArtifactKind.AAR else ArtifactKind.JAR
            ArtifactRef(finalCoord, relForFile(finalCoord, f.url), kind)
        }
        val packaging = if (artifacts.firstOrNull()?.kind == ArtifactKind.AAR) "aar" else "jar"
        val sources = findSourcesFile(gmm)?.let { ArtifactRef(finalCoord, relForFile(finalCoord, it), ArtifactKind.JAR) }
        // A variant with no declared capabilities provides only the module's implicit `group:name` (keyed by the
        // logical request coordinate, not the redirected platform artifact).
        val capabilities = variant.capabilities.ifEmpty {
            listOf(GmmCapability(requestCoord.group, requestCoord.name, requestCoord.version))
        }
        return ResolvedNode(packaging, transitives, constraints, artifacts, sources, capabilities)
    }

    private fun EffPom.toResolvedNode(): ResolvedNode = ResolvedNode(
        packaging = packaging,
        transitives = dependencies.filter { it.isTransitivelyIncluded() }
            .map { ChildDep(it.groupId, it.artifactId, it.version?.ifBlank { null }, null, it.exclusions, it.type) },
        constraints = emptyList(),
        artifacts = emptyList(),   // no GMM → fetch by the default packaging-based URL
        sources = null,
        capabilities = listOf(GmmCapability(coordinate.group, coordinate.name, coordinate.version)),  // implicit only
    )

    /** Whether [name] is a variant's primary jar/aar (not a `-sources`/`-javadoc` companion). */
    private fun isPrimaryArtifact(name: String): Boolean {
        val lower = name.lowercase()
        if (!lower.endsWith(".jar") && !lower.endsWith(".aar")) return false
        return !lower.endsWith("-sources.jar") && !lower.endsWith("-javadoc.jar")
    }

    /** The url of a sources jar from a GMM sources variant (docstype=sources, JVM/android, real not fake),
     *  preferring the android variant. Null when the module publishes none → fall back to the classifier guess. */
    private fun findSourcesFile(gmm: GradleModule): String? {
        val v = gmm.variants.filter {
            val a = it.attributes
            a["org.gradle.docstype"] == "sources" &&
                a["org.jetbrains.kotlin.platform.type"].let { p -> p == null || p == "androidJvm" || p == "jvm" }
        }.maxByOrNull { v -> if (v.attributes["org.jetbrains.kotlin.platform.type"] == "androidJvm") 2 else 1 }
        return v?.files?.firstOrNull { it.url.endsWith(".jar", ignoreCase = true) }?.url
    }

    /** Maven-layout relative path for an explicit file name under [coord]'s directory. */
    private fun relForFile(coord: Coordinate, fileName: String): String =
        "${coord.group.replace('.', '/')}/${coord.name}/${coord.version}/$fileName"

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
    /** The outcome of [fetchArtifact]: the cached path plus whether it was just downloaded ([fromNetwork]) or
     *  already on disk — so the resolver only reports "Downloading…" for real network work (honest progress). */
    private data class ArtifactFetch(val path: Path, val fromNetwork: Boolean)

    private fun fetchArtifact(coord: Coordinate, ext: String, repos: List<Repository>, classifier: String? = null): ArtifactFetch? =
        fetchRel(coord, cache.relativePath(coord, ext, classifier), repos)

    /**
     * Fetch the artifact at an explicit Maven-layout [rel]ative path (cache-first, then each repo), streaming
     * socket → disk. [coord] only orders the repos ([reposFor]) — the path is taken verbatim, so a GMM
     * variant override can fetch a file whose name/coordinate differs from the requesting coordinate.
     */
    private fun fetchRel(coord: Coordinate, rel: String, repos: List<Repository>): ArtifactFetch? {
        if (cache.exists(rel)) return ArtifactFetch(cache.fileFor(rel), fromNetwork = false)
        // A recent confirmed 404 (e.g. a library with no -sources.jar) — don't re-probe the network for it.
        if (cache.isKnownMissing(rel)) return null
        val ordered = reposFor(coord, repos)
        var transient = false
        val path = cache.writeStreaming(rel) { tmp ->
            ordered.any { repo ->
                val url = repo.url.removeSuffix("/") + "/" + rel
                log.debug("GET jar: $url")
                val outcome = runCatching { fetcher.fetchTo(url, tmp) }
                // A thrown error (not a 404, which returns false) is the real "why it failed": host
                // unreachable, TLS, timeout, a 5xx. Log it so an on-device resolution failure is diagnosable.
                outcome.exceptionOrNull()?.let { transient = true; log.warn("download failed: $url (${it.javaClass.simpleName}: ${it.message})") }
                outcome.getOrDefault(false)
            }
        }
        if (path != null) return ArtifactFetch(path, fromNetwork = true)
        // No repo carried it. A clean miss (404 everywhere, no transient error) is genuinely absent → remember
        // it so the next open skips the network; a transient failure is NOT recorded (it may succeed online).
        if (!transient) cache.recordMissing(rel)
        return null
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
        if (cache.isKnownMissing(rel)) return null   // a recent 404 (e.g. an absent parent POM) — don't re-probe
        var transient = false
        for (repo in repos) {
            val url = repo.url.removeSuffix("/") + "/" + rel
            log.debug("GET pom: $url")
            val outcome = runCatching { fetcher.fetch(url) }
            // A thrown error (not a 404, which returns null) is the real cause of an unresolvable POM:
            // host unreachable, TLS, timeout, a 5xx. Log it so an on-device failure is diagnosable.
            outcome.exceptionOrNull()?.let { transient = true; log.warn("POM fetch failed: $url (${it.javaClass.simpleName}: ${it.message})") }
            val bytes = outcome.getOrNull()
            if (bytes != null) { cache.write(rel, bytes); return bytes }
        }
        // Confirmed absent across every repo (and no transient error) → remember the miss; a transient failure
        // is left unrecorded so it's retried online.
        if (!transient) cache.recordMissing(rel)
        return null
    }

    /**
     * Explode an `.aar` into the cache, returning its `classes.jar`. Alongside it, the AAR's `res/`,
     * `assets/`, `jni/` and `AndroidManifest.xml` are unpacked into the same exploded dir — so a consumer
     * (the IDE's resource model / Android build) can find a library's resources as a `res/` sibling of its
     * `classes.jar` and its package name in the sibling manifest, with no further unzip at use time.
     */
    /** The class root for a downloaded [artifact]: an AAR is exploded to its `classes.jar`; a jar is itself. */
    private fun classRootOf(kind: ArtifactKind, coord: Coordinate, artifact: Path): VirtualFile =
        if (kind == ArtifactKind.AAR) fileFor(extractClassesJar(coord, artifact)) else fileFor(artifact)

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

        /** A `<version>X</version>` entry in `maven-metadata.xml`. */
        val METADATA_VERSION = Regex("<version>([^<]+)</version>")

        /**
         * Version-alignment groups (Gradle BOM / `kotlin-bom`-style alignment). Every member of a group
         * present in the graph resolves to ONE shared version: the newest requested across the group. This
         * is the POM-only equivalent of the Gradle Module Metadata *capability* the published `.module`
         * files carry (which a pure-POM resolver never sees).
         *
         * The load-bearing case is the Kotlin stdlib family. As of Kotlin 1.8.0 the contents of
         * `kotlin-stdlib-jdk7`/`-jdk8` were folded into the main `kotlin-stdlib` artifact (the jdk7/jdk8
         * modules became near-empty shims). So a graph that mixes `kotlin-stdlib:1.8.x` (which now carries
         * `kotlin.collections.jdk8.CollectionsJDK8Kt`) with an older `kotlin-stdlib-jdk8:1.6.x` (which still
         * carries it) defines that class twice — harmless on the JVM (first-on-classpath wins) but FATAL to
         * D8/R8, which abort on a type defined more than once. These are DISTINCT artifacts (different
         * `group:name`), so plain newest-wins per-coordinate dedup can't collapse them. Aligning the family
         * to a single version does: at any one version exactly one member carries the class.
         */
        val ALIGNMENT_GROUPS: List<Set<GA>> = listOf(
            setOf(
                GA("org.jetbrains.kotlin", "kotlin-stdlib"),
                GA("org.jetbrains.kotlin", "kotlin-stdlib-jdk7"),
                GA("org.jetbrains.kotlin", "kotlin-stdlib-jdk8"),
                GA("org.jetbrains.kotlin", "kotlin-stdlib-common"),
            ),
        )
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
