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
import dev.ide.vfs.VirtualFile
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
) : DependencyResolver {

    override suspend fun resolve(
        coordinates: List<Coordinate>,
        repositories: List<Repository>,
        conflict: ConflictPolicy,
        progress: ProgressReporter,
    ): ResolutionResult {
        val repos = repositories.ifEmpty { DEFAULT_REPOSITORIES }
        val pomMemo = HashMap<Coordinate, EffPom?>()

        val seenVersions = LinkedHashMap<GA, MutableSet<String>>()  // every requested version, per artifact
        val directVersions = HashMap<GA, String>()                  // the version the user declared (for PINNED)
        val chosen = LinkedHashMap<GA, String>()                    // winning version, per artifact
        val edges = LinkedHashMap<GA, LinkedHashSet<GA>>()          // dependsOn graph (by artifact)
        val packagingByGa = HashMap<GA, String>()
        val walked = HashSet<Coordinate>()
        val unresolved = LinkedHashSet<Coordinate>()
        val directGAs = LinkedHashSet<GA>()

        val queue = ArrayDeque<Req>()
        for (c in coordinates) {
            directVersions[c.ga] = c.version
            directGAs += c.ga
            queue.add(Req(c, emptySet(), direct = true))
        }

        fun pick(ga: GA): String {
            val versions = seenVersions[ga]!!
            return when (conflict) {
                ConflictPolicy.PINNED -> directVersions[ga] ?: MavenVersion.newest(versions)!!
                ConflictPolicy.NEWEST, ConflictPolicy.FAIL_ON_CONFLICT -> MavenVersion.newest(versions)!!
            }
        }

        var processed = 0
        while (queue.isNotEmpty() && chosen.size < MAX_NODES) {
            progress.checkCanceled()
            val req = queue.removeFirst()
            val ga = req.coord.ga
            seenVersions.getOrPut(ga) { linkedSetOf() }.add(req.coord.version)
            edges.getOrPut(ga) { linkedSetOf() }

            val chosenCoord = Coordinate(ga.group, ga.name, pick(ga))
            chosen[ga] = chosenCoord.version
            if (chosenCoord in walked) continue
            walked += chosenCoord

            val eff = pomMemo.getOrPut(chosenCoord) { loadEffective(chosenCoord, repos, HashSet()) }
            if (eff == null) {
                if (req.direct) unresolved += req.coord
                continue
            }
            packagingByGa[ga] = eff.packaging
            for (d in eff.dependencies) {
                if (d.version.isNullOrBlank()) continue            // unmanaged version → can't place it
                if (!d.isTransitivelyIncluded()) continue          // drop test/provided/system/optional
                val childGa = GA(d.groupId, d.artifactId)
                if (childGa.excludedBy(req.exclusions)) continue
                edges.getOrPut(ga) { linkedSetOf() }.add(childGa)
                if (d.type.equals("aar", ignoreCase = true)) packagingByGa.putIfAbsent(childGa, "aar")
                queue.add(Req(Coordinate(d.groupId, d.artifactId, d.version), req.exclusions + d.exclusions, direct = false))
            }
            if (++processed % 4 == 0) progress.report(-1.0, "Resolving ${req.coord.name}…")
        }

        // --- download + assemble results -------------------------------------------------------
        val resolved = ArrayList<ResolvedArtifact>()
        val total = chosen.size.coerceAtLeast(1)
        chosen.entries.forEachIndexed { i, (ga, ver) ->
            progress.checkCanceled()
            val coord = Coordinate(ga.group, ga.name, ver)
            val packaging = packagingByGa[ga] ?: "jar"
            if (packaging.equals("pom", ignoreCase = true)) return@forEachIndexed   // BOM/metadata-only
            val kind = if (packaging.equals("aar", ignoreCase = true)) ArtifactKind.AAR else ArtifactKind.JAR
            val ext = if (kind == ArtifactKind.AAR) "aar" else "jar"

            val artifact = fetchArtifact(coord, ext, repos)
            if (artifact == null) {
                if (ga in directGAs) unresolved += coord
                return@forEachIndexed
            }
            val classesRoot = if (kind == ArtifactKind.AAR) fileFor(extractClassesJar(coord, artifact)) else fileFor(artifact)
            val sources = fetchArtifact(coord, "jar", repos, classifier = "sources")?.let { fileFor(it) }
            val dependsOn = edges[ga].orEmpty().mapNotNull { c -> chosen[c]?.let { Coordinate(c.group, c.name, it) } }
            resolved += ResolvedArtifact(coord, kind, classesRoot, sources, dependsOn)
            progress.report((i + 1).toDouble() / total, "Downloaded ${coord.name}")
        }

        val conflicts = seenVersions
            .filterValues { it.size > 1 }
            .map { (ga, versions) -> VersionConflict(ga.toString(), versions.sortedWith(MavenVersion), chosen[ga] ?: versions.first()) }

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
        if (!visiting.add(coord)) return null   // guard against parent/import cycles
        val raw = parseRawPom(coord, repos) ?: return null.also { visiting.remove(coord) }

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
            val v = resolveProperties(m.version, props, coord)
            if (m.scope.equals("import", ignoreCase = true) && v != null) {
                loadEffective(Coordinate(g, a, v), repos, visiting)?.managed?.forEach { (ga, ver) -> managed.putIfAbsent(ga, ver) }
            } else if (v != null) {
                managed[GA(g, a)] = v   // local management overrides inherited
            }
        }

        val deps = raw.dependencies.mapNotNull { d ->
            val g = resolveProperties(d.groupId, props, coord) ?: return@mapNotNull null
            val a = resolveProperties(d.artifactId, props, coord) ?: return@mapNotNull null
            val v = resolveProperties(d.version, props, coord) ?: managed[GA(g, a)]
            d.copy(
                groupId = g,
                artifactId = a,
                version = v,
                type = resolveProperties(d.type, props, coord) ?: "jar",
            )
        }
        visiting.remove(coord)
        return EffPom(coord, raw.packaging, props, managed, deps)
    }

    private fun parseRawPom(coord: Coordinate, repos: List<Repository>): RawPom? {
        val bytes = fetchBytes(cache.relativePath(coord, "pom"), repos) ?: return null
        return runCatching { PomParser.parse(bytes) }.getOrNull()
    }

    // --- download helpers ----------------------------------------------------------------------

    /** Returns the cached path for an artifact, fetching it if absent. Null when no repo carries it. */
    private fun fetchArtifact(coord: Coordinate, ext: String, repos: List<Repository>, classifier: String? = null): Path? {
        val rel = cache.relativePath(coord, ext, classifier)
        if (cache.exists(rel)) return cache.fileFor(rel)
        val bytes = fetchBytes(rel, repos) ?: return null
        return cache.write(rel, bytes)
    }

    /** Cache-first byte fetch of a Maven-layout [rel]ative path: try the disk store, then each repo. */
    private fun fetchBytes(rel: String, repos: List<Repository>): ByteArray? {
        cache.read(rel)?.let { return it }
        for (repo in repos) {
            val url = repo.url.removeSuffix("/") + "/" + rel
            val bytes = runCatching { fetcher.fetch(url) }.getOrNull()
            if (bytes != null) { cache.write(rel, bytes); return bytes }
        }
        return null
    }

    /**
     * Explode an `.aar` into the cache, returning its `classes.jar`. Alongside it, the AAR's `res/` and
     * `assets/` are unpacked into the same exploded dir — so a consumer (the IDE's resource model) can find
     * a library's resources as a `res/` sibling of its `classes.jar`, with no further unzip at use time.
     */
    private fun extractClassesJar(coord: Coordinate, aar: Path): Path {
        val dir = cache.explodedDir(coord)
        Files.createDirectories(dir)
        val classesJar = dir.resolve("classes.jar")
        val marker = dir.resolve(".extracted")
        if (Files.isRegularFile(marker)) return classesJar // already fully exploded (classes + res + assets)

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
                    !entry.isDirectory && (name.startsWith("res/") || name.startsWith("assets/")) -> {
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
        if (!foundClasses) ZipOutputStream(Files.newOutputStream(classesJar)).use { } // resource-only AAR → empty jar
        Files.writeString(marker, "")
        return classesJar
    }

    private data class Req(val coord: Coordinate, val exclusions: Set<GA>, val direct: Boolean)

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
