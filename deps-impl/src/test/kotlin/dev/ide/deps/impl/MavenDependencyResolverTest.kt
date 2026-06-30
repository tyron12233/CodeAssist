package dev.ide.deps.impl

import dev.ide.deps.ArtifactKind
import dev.ide.deps.ConflictPolicy
import dev.ide.deps.Repository
import dev.ide.model.Coordinate
import dev.ide.model.Exclusion
import dev.ide.platform.ProgressReporter
import dev.ide.vfs.local.LocalFileSystem
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MavenDependencyResolverTest {

    private val noProgress = object : ProgressReporter {
        override fun report(fraction: Double, message: String?) {}
        override fun checkCanceled() {}
        override val isCanceled: Boolean = false
    }

    private val repo = Repository("fixture", BASE)

    @Test
    fun resolvesTransitivesAndPicksNewestOnConflict() {
        // a → common:1.0 ; b → common:2.0  (diamond). NEWEST must win for common.
        val files = FakeRepo()
        files.put("a", "1.0", deps = listOf(Dep("g", "common", "1.0")))
        files.put("b", "1.0", deps = listOf(Dep("g", "common", "2.0")))
        files.put("common", "1.0")
        files.put("common", "2.0")

        val (resolver, _) = newResolver(files)
        val result = runBlocking {
            resolver.resolve(listOf(coord("a", "1.0"), coord("b", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress)
        }

        val byName = result.resolved.associateBy { it.coordinate.name }
        assertEquals(setOf("a", "b", "common"), byName.keys)
        assertEquals("2.0", byName.getValue("common").coordinate.version)
        assertTrue(result.unresolved.isEmpty(), "unexpected unresolved: ${result.unresolved}")

        val conflict = result.conflicts.single()
        assertEquals("g:common", conflict.coordinate)
        assertEquals(listOf("1.0", "2.0"), conflict.requested)
        assertEquals("2.0", conflict.chosen)

        // The dependsOn edges expose the diamond for the UI graph.
        assertEquals(listOf(coord("common", "2.0")), byName.getValue("a").dependsOn)
        assertEquals(listOf(coord("common", "2.0")), byName.getValue("b").dependsOn)
    }

    @Test
    fun normalizesHardPinAndRangeVersionsOnTransitives() {
        // AndroidX pins same-group deps as `[1.0]` (a Maven hard-pin range). The literal brackets must not
        // leak into the fetch URL, else the dep's POM 404s and its whole subtree is silently dropped — which
        // is how `androidx.activity:activity` (ComponentActivity) went missing from the Compose classpath.
        val files = FakeRepo()
        files.put("a", "1.0", deps = listOf(Dep("g", "mid", "[1.0]")))   // hard pin
        files.put("mid", "1.0", deps = listOf(Dep("g", "leaf", "[1.0,2.0)")))   // range → lower bound
        files.put("leaf", "1.0")
        val (resolver, _) = newResolver(files)

        val result = runBlocking {
            resolver.resolve(listOf(coord("a", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress)
        }
        val byName = result.resolved.associateBy { it.coordinate.name }
        assertEquals(setOf("a", "mid", "leaf"), byName.keys, "range-pinned transitives must resolve: ${result.unresolved}")
        assertEquals("1.0", byName.getValue("mid").coordinate.version)
        assertEquals("1.0", byName.getValue("leaf").coordinate.version)
        assertTrue(result.unresolved.isEmpty(), "unexpected unresolved: ${result.unresolved}")
    }

    @Test
    fun pinnedPolicyKeepsTheDirectlyDeclaredVersion() {
        val files = FakeRepo()
        files.put("a", "1.0", deps = listOf(Dep("g", "common", "2.0")))
        files.put("common", "1.0")
        files.put("common", "2.0")
        val (resolver, _) = newResolver(files)

        // common is declared directly at 1.0 but a pulls 2.0 transitively → PINNED keeps 1.0.
        val result = runBlocking {
            resolver.resolve(listOf(coord("a", "1.0"), coord("common", "1.0")), listOf(repo), ConflictPolicy.PINNED, noProgress)
        }
        assertEquals("1.0", result.resolved.single { it.coordinate.name == "common" }.coordinate.version)
    }

    @Test
    fun extractsClassesJarAndResFromAar() {
        val files = FakeRepo()
        files.put("widget", "1.0", packaging = "aar", jarBytes = aarWithRes())
        val (resolver, _) = newResolver(files)

        val result = runBlocking { resolver.resolve(listOf(coord("widget", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        val art = result.resolved.single()
        assertEquals(ArtifactKind.AAR, art.kind)
        assertEquals("classes.jar", art.classesRoot.name)
        assertTrue(art.classesRoot.exists)
        // the AAR's res/ is exploded next to classes.jar, for the IDE's resource model
        val res = Path.of(art.classesRoot.path).parent.resolve("res/values/strings.xml")
        assertTrue(Files.isRegularFile(res), "AAR res/ should be extracted next to classes.jar: $res")
    }

    @Test
    fun resolvesResourceOnlyAarToUsableClassFreeClassesJar() {
        // A resource-only AAR (no classes.jar inside, e.g. an Android lib that is all resources) must still
        // resolve, producing a NON-EMPTY but class-free classes.jar. A zero-entry jar is unusable on ART
        // (ZipFile/ZipOutputStream throw `ZipException: No entries`), so we write a single manifest entry: it
        // opens fine everywhere and dexes to no classes.
        val files = FakeRepo()
        files.put("res-lib", "1.0", packaging = "aar", jarBytes = aarResOnly())
        val (resolver, _) = newResolver(files)

        val result = runBlocking { resolver.resolve(listOf(coord("res-lib", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        val art = result.resolved.single()
        assertEquals("classes.jar", art.classesRoot.name)
        assertTrue(art.classesRoot.exists)
        assertTrue(result.unresolved.isEmpty(), "a resource-only AAR resolves; got unresolved=${result.unresolved}")
        // Openable via ZipFile (the ART-critical property; a zero-entry zip would throw here) with >=1 entry,
        // and no `.class` entries (so it dexes to nothing).
        val names = java.util.zip.ZipFile(Path.of(art.classesRoot.path).toFile()).use { zf ->
            zf.entries().toList().map { it.name }
        }
        assertTrue(names.isNotEmpty(), "classes.jar must be a non-empty (ART-openable) archive; got $names")
        assertTrue(names.none { it.endsWith(".class") }, "a resource-only AAR has no classes; got $names")
    }

    @Test
    fun terminatesAndRecordsEdgesOnCyclicMetadata() {
        // a → b → a : a cyclic POM graph must not loop forever; both edges must survive for cycle detection.
        val files = FakeRepo()
        files.put("a", "1.0", deps = listOf(Dep("g", "b", "1.0")))
        files.put("b", "1.0", deps = listOf(Dep("g", "a", "1.0")))
        val (resolver, _) = newResolver(files)

        val result = runBlocking { resolver.resolve(listOf(coord("a", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        val byName = result.resolved.associateBy { it.coordinate.name }
        assertEquals(setOf("a", "b"), byName.keys)
        assertEquals(listOf(coord("b", "1.0")), byName.getValue("a").dependsOn)
        assertEquals(listOf(coord("a", "1.0")), byName.getValue("b").dependsOn)
    }

    @Test
    fun dropsTestScopeAndExcludedTransitives() {
        val files = FakeRepo()
        files.put("a", "1.0", deps = listOf(
            Dep("g", "testonly", "1.0", scope = "test"),
            Dep("g", "b", "1.0", exclusions = listOf("g" to "c")),
        ))
        files.put("b", "1.0", deps = listOf(Dep("g", "c", "1.0")))
        files.put("testonly", "1.0")
        files.put("b", "1.0") // overwrite ok
        files.put("c", "1.0")
        val (resolver, _) = newResolver(files)

        val result = runBlocking { resolver.resolve(listOf(coord("a", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        val names = result.resolved.map { it.coordinate.name }.toSet()
        assertEquals(setOf("a", "b"), names) // testonly (test scope) and c (excluded) are gone
    }

    @Test
    fun honorsCallerDeclaredExclusions() {
        // The caller (not a POM) excludes `g:c` on the direct dependency `a` — the Gradle `exclude` semantics.
        val files = FakeRepo()
        files.put("a", "1.0", deps = listOf(Dep("g", "b", "1.0")))
        files.put("b", "1.0", deps = listOf(Dep("g", "c", "1.0")))
        files.put("c", "1.0")
        val (resolver, _) = newResolver(files)

        val a = coord("a", "1.0")
        val result = runBlocking {
            resolver.resolve(
                listOf(a), listOf(repo), ConflictPolicy.NEWEST, noProgress,
                exclusions = mapOf(a to listOf(Exclusion("g", "c"))),
            )
        }
        assertEquals(setOf("a", "b"), result.resolved.map { it.coordinate.name }.toSet(), "c is excluded by the caller")
    }

    @Test
    fun callerExclusionAppliesPerDeclarationNotGlobally() {
        // `a` excludes `g:c`, but `d` pulls `c` with no exclusion → c survives via d (per-path, like Gradle).
        val files = FakeRepo()
        files.put("a", "1.0", deps = listOf(Dep("g", "c", "1.0")))
        files.put("d", "1.0", deps = listOf(Dep("g", "c", "1.0")))
        files.put("c", "1.0")
        val (resolver, _) = newResolver(files)

        val a = coord("a", "1.0"); val d = coord("d", "1.0")
        val result = runBlocking {
            resolver.resolve(
                listOf(a, d), listOf(repo), ConflictPolicy.NEWEST, noProgress,
                exclusions = mapOf(a to listOf(Exclusion("g", "c"))),
            )
        }
        assertTrue("c" in result.resolved.map { it.coordinate.name }, "c reachable through d, which doesn't exclude it")
    }

    @Test
    fun wildcardCallerExclusionDropsAllTransitives() {
        val files = FakeRepo()
        files.put("a", "1.0", deps = listOf(Dep("g", "b", "1.0"), Dep("g", "c", "1.0")))
        files.put("b", "1.0")
        files.put("c", "1.0")
        val (resolver, _) = newResolver(files)

        val a = coord("a", "1.0")
        val result = runBlocking {
            resolver.resolve(
                listOf(a), listOf(repo), ConflictPolicy.NEWEST, noProgress,
                exclusions = mapOf(a to listOf(Exclusion("*", "*"))),
            )
        }
        assertEquals(setOf("a"), result.resolved.map { it.coordinate.name }.toSet(), "`*:*` drops every transitive")
    }

    @Test
    fun resolvesOfflineFromCacheAfterFirstFetch() {
        val files = FakeRepo()
        files.put("a", "1.0", deps = listOf(Dep("g", "common", "1.0")))
        files.put("common", "1.0")

        val tmp = createTempDirectory("deps-offline")
        val lfs = LocalFileSystem(tmp)
        val cache = ResolverCache(tmp)
        val warm = MavenDependencyResolver(cache, lfs::fileFor, files)
        runBlocking { warm.resolve(listOf(coord("a", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }

        // Second run with a fetcher that refuses everything — must still resolve from the populated cache.
        val offline = MavenDependencyResolver(cache, lfs::fileFor, ArtifactFetcher { null })
        val result = runBlocking { offline.resolve(listOf(coord("a", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        assertEquals(setOf("a", "common"), result.resolved.map { it.coordinate.name }.toSet())
    }

    @Test
    fun platformBomSuppliesVersionForVersionlessDependency() {
        // A BOM manages common:1.5; the user declares `common` with no version + imports the BOM.
        val files = FakeRepo()
        files.put("common", "1.5")
        files.putBom("bom", "1.0", manages = listOf(Dep("g", "common", "1.5")))
        val (resolver, _) = newResolver(files)

        val result = runBlocking {
            resolver.resolve(
                listOf(coord("common", "")), listOf(repo), ConflictPolicy.NEWEST, noProgress,
                platforms = listOf(coord("bom", "1.0")),
            )
        }
        assertTrue(result.unresolved.isEmpty(), "unexpected unresolved: ${result.unresolved}")
        assertEquals("1.5", result.resolved.single { it.coordinate.name == "common" }.coordinate.version)
    }

    @Test
    fun platformDoesNotOverrideAnExplicitVersion() {
        // Plain-platform semantics: an explicitly-declared version wins over the BOM's managed version.
        val files = FakeRepo()
        files.put("common", "1.0")
        files.put("common", "2.0")
        files.putBom("bom", "1.0", manages = listOf(Dep("g", "common", "2.0")))
        val (resolver, _) = newResolver(files)

        val result = runBlocking {
            resolver.resolve(
                listOf(coord("common", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress,
                platforms = listOf(coord("bom", "1.0")),
            )
        }
        assertEquals("1.0", result.resolved.single { it.coordinate.name == "common" }.coordinate.version)
    }

    @Test
    fun versionlessDependencyWithoutPlatformIsUnresolved() {
        val files = FakeRepo()
        files.put("common", "1.0")
        val (resolver, _) = newResolver(files)

        val result = runBlocking {
            resolver.resolve(listOf(coord("common", "")), listOf(repo), ConflictPolicy.NEWEST, noProgress)
        }
        assertTrue(result.resolved.isEmpty(), "nothing should resolve: ${result.resolved.map { it.coordinate }}")
        assertEquals(listOf(coord("common", "")), result.unresolved)
    }

    @Test
    fun alignsKotlinStdlibFamilyToOneVersion() {
        // The real failure: `kotlin-stdlib:1.8.22` (which post-1.8 carries CollectionsJDK8Kt) plus an older
        // `kotlin-stdlib-jdk8:1.6.21` (which still carries it) dragged in transitively. These are DISTINCT
        // artifacts, so per-coordinate newest-wins can't collapse them, and D8/R8 abort on the duplicate
        // class. Family alignment must snap kotlin-stdlib-jdk8 up to 1.8.22, where it's an empty shim.
        val k = "org.jetbrains.kotlin"
        val files = FakeRepo()
        files.put("kotlin-stdlib", "1.8.22", group = k)
        files.put("kotlin-stdlib", "1.6.21", group = k)
        // jdk8 1.6.21 is the standalone (class-carrying) artifact; 1.8.22 is the empty shim the build will fetch.
        files.put("kotlin-stdlib-jdk8", "1.6.21", group = k, deps = listOf(Dep(k, "kotlin-stdlib", "1.6.21")))
        files.put("kotlin-stdlib-jdk8", "1.8.22", group = k, deps = listOf(Dep(k, "kotlin-stdlib", "1.8.22")))
        // An older library that still depends on the standalone jdk8 artifact (e.g. an old coroutines build).
        files.put("legacy-lib", "1.0", group = k, deps = listOf(Dep(k, "kotlin-stdlib-jdk8", "1.6.21")))

        val result = runBlocking {
            newResolver(files).first.resolve(
                listOf(Coordinate(k, "kotlin-stdlib", "1.8.22"), Coordinate(k, "legacy-lib", "1.0")),
                listOf(repo), ConflictPolicy.NEWEST, noProgress,
            )
        }
        assertTrue(result.unresolved.isEmpty(), "unexpected unresolved: ${result.unresolved}")
        val byName = result.resolved.associateBy { it.coordinate.name }
        assertEquals("1.8.22", byName.getValue("kotlin-stdlib").coordinate.version)
        // The whole point: jdk8 is pulled in at 1.6.21 but aligned up to 1.8.22 (the empty shim) → no dup class.
        assertEquals("1.8.22", byName.getValue("kotlin-stdlib-jdk8").coordinate.version)
    }

    @Test
    fun unresolvedWhenRepoLacksTheArtifact() {
        val (resolver, _) = newResolver(FakeRepo())
        val result = runBlocking { resolver.resolve(listOf(coord("ghost", "9.9")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        assertTrue(result.resolved.isEmpty())
        assertEquals(listOf(coord("ghost", "9.9")), result.unresolved)
    }

    // ---- fixture helpers ----------------------------------------------------------------------

    private fun newResolver(files: FakeRepo): Pair<MavenDependencyResolver, Path> {
        val tmp = createTempDirectory("deps-test")
        val lfs = LocalFileSystem(tmp)
        return MavenDependencyResolver(ResolverCache(tmp), lfs::fileFor, files) to tmp
    }

    private fun coord(name: String, version: String) = Coordinate("g", name, version)

    private data class Dep(
        val g: String, val a: String, val v: String,
        val scope: String? = null, val optional: Boolean = false,
        val exclusions: List<Pair<String, String>> = emptyList(),
    )

    /** An in-memory Maven repo keyed by request URL; missing entries return null (404). */
    private inner class FakeRepo : ArtifactFetcher {
        private val byUrl = HashMap<String, ByteArray>()
        override fun fetch(url: String): ByteArray? = byUrl[url]

        fun put(name: String, version: String, packaging: String = "jar", deps: List<Dep> = emptyList(), jarBytes: ByteArray = emptyJar(), group: String = "g") {
            byUrl[url(group, name, version, "pom")] = pom(group, name, version, packaging, deps).toByteArray()
            val ext = if (packaging == "aar") "aar" else "jar"
            byUrl[url(group, name, version, ext)] = jarBytes
        }

        /** A `pom`-packaged BOM: only a POM with a `<dependencyManagement>` block, no artifact. */
        fun putBom(name: String, version: String, manages: List<Dep>) {
            byUrl[url("g", name, version, "pom")] = pom("g", name, version, "pom", emptyList(), manages).toByteArray()
        }
    }

    private fun url(g: String, a: String, v: String, ext: String): String =
        "$BASE/${g.replace('.', '/')}/$a/$v/$a-$v.$ext"

    private fun pom(g: String, a: String, v: String, packaging: String, deps: List<Dep>, managed: List<Dep> = emptyList()): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><project>""")
        append("<groupId>$g</groupId><artifactId>$a</artifactId><version>$v</version>")
        if (packaging != "jar") append("<packaging>$packaging</packaging>")
        if (managed.isNotEmpty()) {
            append("<dependencyManagement><dependencies>")
            for (d in managed) {
                append("<dependency><groupId>${d.g}</groupId><artifactId>${d.a}</artifactId><version>${d.v}</version></dependency>")
            }
            append("</dependencies></dependencyManagement>")
        }
        if (deps.isNotEmpty()) {
            append("<dependencies>")
            for (d in deps) {
                append("<dependency><groupId>${d.g}</groupId><artifactId>${d.a}</artifactId><version>${d.v}</version>")
                d.scope?.let { append("<scope>$it</scope>") }
                if (d.optional) append("<optional>true</optional>")
                if (d.exclusions.isNotEmpty()) {
                    append("<exclusions>")
                    d.exclusions.forEach { (eg, ea) -> append("<exclusion><groupId>$eg</groupId><artifactId>$ea</artifactId></exclusion>") }
                    append("</exclusions>")
                }
                append("</dependency>")
            }
            append("</dependencies>")
        }
        append("</project>")
    }

    private fun emptyJar(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { }
        return out.toByteArray()
    }

    /** An AAR with resources/manifest but NO `classes.jar` (a resource-only Android library). */
    private fun aarResOnly(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml")); zos.write("<manifest/>".toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("res/values/strings.xml"))
            zos.write("""<resources><string name="x">x</string></resources>""".toByteArray())
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    private fun aarWithRes(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("classes.jar")); zos.write(emptyJar()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("AndroidManifest.xml")); zos.write("<manifest/>".toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("res/values/strings.xml"))
            zos.write("""<resources><string name="widget_label">W</string></resources>""".toByteArray())
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    private companion object {
        const val BASE = "https://fixture/repo"
    }
}
