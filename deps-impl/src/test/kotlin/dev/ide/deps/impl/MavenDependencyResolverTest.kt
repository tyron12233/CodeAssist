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
import kotlin.test.assertFalse
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
    fun fullyCachedReResolveTouchesNoNetworkAndNegativeCachesMissingSources() {
        // The reopen case: once a closure is cached, re-resolving must hit the network ZERO times — including
        // not re-probing a `-sources.jar` that doesn't exist (the dominant repeat-download cause). The first
        // resolve probes (and 404s) the sources jar; that miss is negative-cached, so the second resolve is
        // fully offline.
        val files = FakeRepo()
        files.put("a", "1.0")   // pom + jar present; no -sources.jar published → 404
        val tmp = createTempDirectory("deps-neg")
        val lfs = LocalFileSystem(tmp)
        val cache = ResolverCache(tmp)
        val seen = mutableListOf<String>()
        val counting = ArtifactFetcher { url -> seen += url; files.fetch(url) }
        val resolver = MavenDependencyResolver(cache, lfs::fileFor, counting)

        runBlocking { resolver.resolve(listOf(coord("a", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        assertTrue(seen.any { it.contains("-sources.jar") }, "first resolve should probe the sources jar")

        seen.clear()
        val result = runBlocking { resolver.resolve(listOf(coord("a", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        assertEquals(setOf("a"), result.resolved.map { it.coordinate.name }.toSet())
        assertTrue(seen.isEmpty(), "a fully-cached re-resolve must touch the network 0 times (no sources re-probe): $seen")
    }

    @Test
    fun hard404IsNotRetriable() {
        // A coordinate that 404s on every repo is permanently absent → not retriable (so callers can stop
        // re-walking it every open; the negative cache + an explicit Retry handle recovery).
        val (resolver, _) = newResolver(FakeRepo())
        val result = runBlocking { resolver.resolve(listOf(coord("ghost", "9.9")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        assertEquals(listOf(coord("ghost", "9.9")), result.unresolved)
        assertFalse(result.retriable, "a clean 404 across all repos is permanent, not retriable")
    }

    @Test
    fun transientNetworkFailureIsRetriable() {
        // A thrown I/O error (host unreachable / timeout / 5xx) is NOT a 404 — it may succeed once back online,
        // so the result is retriable and the miss is NOT negative-cached.
        val tmp = createTempDirectory("deps-transient")
        val lfs = LocalFileSystem(tmp)
        val throwing = ArtifactFetcher { throw java.io.IOException("connection reset") }
        val resolver = MavenDependencyResolver(ResolverCache(tmp), lfs::fileFor, throwing)
        val result = runBlocking { resolver.resolve(listOf(coord("x", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        assertTrue(result.unresolved.isNotEmpty(), "the throwing fetcher can't resolve anything")
        assertTrue(result.retriable, "a network error (not a 404) must be retriable")
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

    // ---- Gradle Module Metadata variant selection --------------------------------------------

    private val androidApi = mapOf(
        "org.gradle.category" to "library", "org.gradle.usage" to "java-api",
        "org.jetbrains.kotlin.platform.type" to "androidJvm", "org.gradle.jvm.environment" to "android",
    )
    private val jvmApi = mapOf(
        "org.gradle.category" to "library", "org.gradle.usage" to "java-api",
        "org.jetbrains.kotlin.platform.type" to "jvm", "org.gradle.jvm.environment" to "standard-jvm",
    )

    @Test
    fun selectsAndroidVariantOverJvmFromGmm() {
        // The core regression: a KMP library publishing both an `-android` and a `-jvm` variant must resolve
        // to ONLY the `-android` artifact (no `-jvm`), so the old dedup band-aid is no longer needed.
        val files = FakeRepo()
        files.putModule("lib", "1.0", variants = listOf(
            GmmVar("androidApiElements", androidApi, files = listOf("lib-android-1.0.jar")),
            GmmVar("jvmApiElements", jvmApi, files = listOf("lib-jvm-1.0.jar")),
        ))
        val (resolver, _) = newResolver(files)
        val result = runBlocking { resolver.resolve(listOf(coord("lib", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        val art = result.resolved.single { it.coordinate.name == "lib" }
        assertTrue(art.classesRoot.path.endsWith("lib-android-1.0.jar"), "the -android artifact must win: ${art.classesRoot.path}")
        assertFalse(art.classesRoot.path.contains("lib-jvm"), "the -jvm artifact must not be chosen: ${art.classesRoot.path}")
    }

    @Test
    fun kmpAvailableAtRedirectsRootToPlatformModule() {
        // A KMP root module whose variants only point (`available-at`) to platform modules: the android
        // variant redirects to `-core-android`, which carries the file + the real transitives.
        val k = "org.jetbrains.kotlinx"
        val files = FakeRepo()
        files.putModule("coroutines-core", "1.8.0", group = k, variants = listOf(
            GmmVar("androidApiElements", androidApi, availableAt = Triple(k, "coroutines-core-android", "1.8.0")),
            GmmVar("jvmApiElements", jvmApi, availableAt = Triple(k, "coroutines-core-jvm", "1.8.0")),
        ))
        files.putModule("coroutines-core-android", "1.8.0", group = k, variants = listOf(
            GmmVar("androidApiElements", androidApi, files = listOf("coroutines-core-android-1.8.0.jar"),
                deps = listOf(Dep(k, "atomicfu", "0.23.0"))),
        ))
        files.put("atomicfu", "0.23.0", group = k)
        val (resolver, _) = newResolver(files)
        val result = runBlocking {
            resolver.resolve(listOf(Coordinate(k, "coroutines-core", "1.8.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress)
        }
        val byName = result.resolved.associateBy { it.coordinate.name }
        val core = byName.getValue("coroutines-core")
        assertTrue(core.classesRoot.path.endsWith("coroutines-core-android-1.8.0.jar"), "redirected to the -android jar: ${core.classesRoot.path}")
        assertTrue("atomicfu" in byName.keys, "the platform module's transitive must be walked: ${byName.keys}")
        assertFalse(byName.keys.any { it.contains("-jvm") }, "the -jvm platform module must not be pulled: ${byName.keys}")
    }

    @Test
    fun kmpAarFileUrlDiffersFromName() {
        // The real AndroidX KMP AAR shape: the `-android` platform module lists its file with a logical
        // name (`<artifact>-release.aar`) but a different `url` (`<artifact>-android-<version>.aar`). The
        // download must use the URL — using the name 404s every KMP AndroidX AAR (the datastore/lifecycle bug).
        val g = "androidx.datastore"
        val files = FakeRepo()
        files.putModule("datastore-preferences", "1.1.1", group = g, variants = listOf(
            GmmVar("releaseApiElements-published",
                mapOf("org.gradle.category" to "library", "org.gradle.usage" to "java-api", "org.jetbrains.kotlin.platform.type" to "androidJvm"),
                availableAt = Triple(g, "datastore-preferences-android", "1.1.1")),
        ))
        files.putModule("datastore-preferences-android", "1.1.1", group = g, packaging = "aar", variants = listOf(
            GmmVar("releaseApiElements-published",
                mapOf("org.gradle.category" to "library", "org.gradle.usage" to "java-api", "org.jetbrains.kotlin.platform.type" to "androidJvm"),
                files = listOf("datastore-preferences-release.aar"),
                fileUrls = mapOf("datastore-preferences-release.aar" to "datastore-preferences-android-1.1.1.aar")),
        ))
        val (resolver, _) = newResolver(files)
        val result = runBlocking {
            resolver.resolve(listOf(Coordinate(g, "datastore-preferences", "1.1.1")), listOf(repo), ConflictPolicy.NEWEST, noProgress)
        }
        assertTrue(result.unresolved.isEmpty(), "KMP AAR with name≠url must resolve, not 404: ${result.unresolved}")
        val art = result.resolved.single { it.coordinate.name == "datastore-preferences" }
        assertEquals(ArtifactKind.AAR, art.kind)
        assertTrue(art.classesRoot.exists, "the redirected -android AAR's classes.jar must be extracted")
        // It was fetched from the URL path (the -android coordinate dir), not the logical name.
        assertTrue(art.classesRoot.path.contains("datastore-preferences-android"), "fetched via the file url: ${art.classesRoot.path}")
    }

    private val libApi = mapOf("org.gradle.category" to "library", "org.gradle.usage" to "java-api")

    @Test
    fun gmmDependencyConstraintAlignsAGaInTheGraph() {
        // libA depends on shared:1.0 AND constrains shared→2.0 (an atomic-group constraint). The constraint
        // must align shared up to 2.0 even though only 1.0 was requested — preventing a split-version dex clash.
        val files = FakeRepo()
        files.putModule("libA", "1.0", variants = listOf(
            GmmVar("api", libApi, files = listOf("libA-1.0.jar"),
                deps = listOf(Dep("g", "shared", "1.0")), constraints = listOf(Dep("g", "shared", "2.0"))),
        ))
        files.put("shared", "1.0"); files.put("shared", "2.0")
        val (resolver, _) = newResolver(files)
        val result = runBlocking { resolver.resolve(listOf(coord("libA", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        assertEquals("2.0", result.resolved.single { it.coordinate.name == "shared" }.coordinate.version, "the dependencyConstraint aligns shared up to 2.0")
    }

    @Test
    fun followsRuntimeOnlyGmmTransitive() {
        // AppCompat's shape: the `api` variant omits a transitive that the `runtime` variant carries
        // (`emoji2-views-helper`, which auto-enables EmojiCompat in AppCompatTextView). GMM has no scope —
        // variant membership IS the scope — so the packaged/dex closure must follow the runtime variant's deps
        // too, else the built APK AND the layout preview crash with NoClassDefFoundError when the widget inflates.
        val files = FakeRepo()
        val libRuntime = mapOf("org.gradle.category" to "library", "org.gradle.usage" to "java-runtime")
        files.putModule("widget", "1.0", variants = listOf(
            GmmVar("apiElements", libApi, files = listOf("widget-1.0.jar"), deps = listOf(Dep("g", "core", "1.0"))),
            GmmVar("runtimeElements", libRuntime, files = listOf("widget-1.0.jar"),
                deps = listOf(Dep("g", "core", "1.0"), Dep("g", "emoji", "1.0"))),
        ))
        files.put("core", "1.0")
        files.put("emoji", "1.0")
        val (resolver, _) = newResolver(files)

        val result = runBlocking { resolver.resolve(listOf(coord("widget", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        val names = result.resolved.map { it.coordinate.name }.toSet()
        assertTrue("emoji" in names, "runtime-only GMM transitive (like AppCompat's emoji2-views-helper) must resolve: $names")
        assertTrue("core" in names, "api transitive still resolves: $names")
    }

    @Test
    fun constraintBumpToUnwalkedVersionFetchesTheNewVersionsFile() {
        // The lifecycle `-ktx` bug: `ktx` is WALKED at 1.0 (a plain GMM jar), then a dependencyConstraint bumps
        // it to 2.0 — a version we never walked, whose files live elsewhere via `available-at` (`ktx-android`).
        // The download must re-load 2.0's metadata and fetch ITS file, not reuse the 1.0 file refs captured at
        // walk time (which produced a 2.0-labelled artifact backed by the 1.0 jar → a duplicate-class dex clash).
        val files = FakeRepo()
        files.putModule("ktx", "1.0", variants = listOf(
            GmmVar("api", libApi, files = listOf("ktx-1.0.jar")),
        ))
        files.putModule("ktx", "2.0", variants = listOf(
            GmmVar("releaseApiElements-published", androidApi, availableAt = Triple("g", "ktx-android", "2.0")),
        ))
        files.putModule("ktx-android", "2.0", variants = listOf(
            GmmVar("releaseApiElements-published", androidApi, files = listOf("ktx-android-2.0.jar")),
        ))
        // main pulls ktx:1.0 (so it's walked at 1.0) AND constrains it to 2.0 (so it's bumped post-walk).
        files.putModule("main", "1.0", variants = listOf(
            GmmVar("api", libApi, files = listOf("main-1.0.jar"),
                deps = listOf(Dep("g", "ktx", "1.0")), constraints = listOf(Dep("g", "ktx", "2.0"))),
        ))
        val (resolver, _) = newResolver(files)
        val result = runBlocking { resolver.resolve(listOf(coord("main", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        val ktx = result.resolved.single { it.coordinate.name == "ktx" }
        assertEquals("2.0", ktx.coordinate.version, "ktx aligns to 2.0")
        assertTrue(ktx.classesRoot.path.contains("ktx-android-2.0"), "must fetch 2.0's available-at file, not the 1.0 jar: ${ktx.classesRoot.path}")
        assertFalse(ktx.classesRoot.path.contains("ktx-1.0"), "stale 1.0 file must NOT back the 2.0 coordinate: ${ktx.classesRoot.path}")
    }

    @Test
    fun gmmConstraintOnAvailableAtTargetAlignsStrayKtx() {
        // The lifecycle `-ktx` merge: `lifecycle-runtime` (KMP root) redirects via `available-at` to
        // `lifecycle-runtime-android`, which CONSTRAINS `lifecycle-runtime-ktx -> 2.10.0` (the empty post-merge
        // shim). A stray old `lifecycle-runtime-ktx:2.6.1` (still carrying the merged classes) must align up to
        // 2.10.0, else both define the same class and the Android dex fails ("defined multiple times").
        val files = FakeRepo()
        files.putModule("lifecycle-runtime", "2.10.0", variants = listOf(
            GmmVar("releaseApiElements-published", androidApi, availableAt = Triple("g", "lifecycle-runtime-android", "2.10.0")),
        ))
        files.putModule("lifecycle-runtime-android", "2.10.0", packaging = "aar", variants = listOf(
            GmmVar("releaseApiElements-published", androidApi, files = listOf("lifecycle-runtime-android-2.10.0.aar"),
                constraints = listOf(Dep("g", "lifecycle-runtime-ktx", "2.10.0"))),
        ))
        files.put("lifecycle-runtime-ktx", "2.6.1")    // stray old artifact (carries the classes)
        files.put("lifecycle-runtime-ktx", "2.10.0")   // empty post-merge shim
        files.put("oldlib", "1.0", deps = listOf(Dep("g", "lifecycle-runtime-ktx", "2.6.1")))

        val (resolver, _) = newResolver(files)
        val result = runBlocking {
            resolver.resolve(listOf(coord("lifecycle-runtime", "2.10.0"), coord("oldlib", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress)
        }
        assertEquals("2.10.0", result.resolved.single { it.coordinate.name == "lifecycle-runtime-ktx" }.coordinate.version,
            "the constraint on the available-at target must align the stray -ktx up to 2.10.0")
    }

    @Test
    fun gmmConstraintDoesNotPullAnAbsentGa() {
        // A constraint only aligns a GA that's in the graph; it must not pull `unused` into the closure.
        val files = FakeRepo()
        files.putModule("libA", "1.0", variants = listOf(
            GmmVar("api", libApi, files = listOf("libA-1.0.jar"), constraints = listOf(Dep("g", "unused", "2.0"))),
        ))
        val (resolver, _) = newResolver(files)
        val result = runBlocking { resolver.resolve(listOf(coord("libA", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        assertEquals(setOf("libA"), result.resolved.map { it.coordinate.name }.toSet(), "a constraint must not add a dependency")
    }

    @Test
    fun gmmStrictlyPinWinsOverANewerRequirement() {
        // libA strictly-pins shared to 1.0; libB requires shared 2.0. The strict pin must win → shared stays 1.0.
        val files = FakeRepo()
        files.putModule("libA", "1.0", variants = listOf(
            GmmVar("api", libApi, files = listOf("libA-1.0.jar"), strictDeps = listOf(Dep("g", "shared", "1.0"))),
        ))
        files.put("libB", "1.0", deps = listOf(Dep("g", "shared", "2.0")))
        files.put("shared", "1.0"); files.put("shared", "2.0")
        val (resolver, _) = newResolver(files)
        val result = runBlocking {
            resolver.resolve(listOf(coord("libA", "1.0"), coord("libB", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress)
        }
        assertEquals("1.0", result.resolved.single { it.coordinate.name == "shared" }.coordinate.version, "a strictly pin is not bumped by a newer requirement")
    }

    @Test
    fun selectsKotlinRuntimeWhenNoJavaUsagePublished() {
        // A pure-Kotlin JVM library publishes only kotlin-api/kotlin-runtime (no java-*); it must still resolve.
        val files = FakeRepo()
        files.putModule("klib", "1.0", variants = listOf(
            GmmVar("runtime", mapOf("org.gradle.category" to "library", "org.gradle.usage" to "kotlin-runtime", "org.jetbrains.kotlin.platform.type" to "jvm"),
                files = listOf("klib-1.0.jar")),
        ))
        val (resolver, _) = newResolver(files)
        val result = runBlocking { resolver.resolve(listOf(coord("klib", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        assertEquals(setOf("klib"), result.resolved.map { it.coordinate.name }.toSet())
    }

    @Test
    fun fetchesAllPrimaryFilesOfAMultiFileVariant() {
        val files = FakeRepo()
        files.putModule("multi", "1.0", variants = listOf(
            GmmVar("api", libApi, files = listOf("multi-1.0.jar", "multi-extra-1.0.jar")),
        ))
        val (resolver, _) = newResolver(files)
        val result = runBlocking { resolver.resolve(listOf(coord("multi", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        val art = result.resolved.single { it.coordinate.name == "multi" }
        assertTrue(art.classesRoot.path.endsWith("multi-1.0.jar"), "primary: ${art.classesRoot.path}")
        assertTrue(art.extraClassesRoots.any { it.path.endsWith("multi-extra-1.0.jar") }, "the extra file joins the classpath: ${art.extraClassesRoots.map { it.path }}")
    }

    @Test
    fun usesTheGmmSourcesVariantForSources() {
        // The sources jar lives in a GMM sources variant with a non-default name; the classifier guess would
        // 404, so the GMM url must be used.
        val files = FakeRepo()
        files.putModule("withsrc", "1.0", variants = listOf(
            GmmVar("api", libApi, files = listOf("withsrc-1.0.jar")),
            GmmVar("sources", mapOf("org.gradle.category" to "documentation", "org.gradle.docstype" to "sources"),
                files = listOf("withsrc-1.0-weird-sources.jar")),
        ))
        val (resolver, _) = newResolver(files)
        val result = runBlocking { resolver.resolve(listOf(coord("withsrc", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        val art = result.resolved.single { it.coordinate.name == "withsrc" }
        assertTrue(art.sourcesRoot?.path?.endsWith("withsrc-1.0-weird-sources.jar") == true, "GMM sources url used: ${art.sourcesRoot?.path}")
    }

    @Test
    fun capabilityConflictEvictsTheSupersededModule() {
        // guava declares it also provides the `com.google.collections:google-collections` capability, so it
        // supersedes the old google-collections jar (which provides that capability implicitly). The loser is
        // evicted from the graph — only guava is resolved (no duplicate classes at dex).
        val files = FakeRepo()
        files.putModule("guava", "33.0", group = "com.google.guava", variants = listOf(
            GmmVar("api", libApi, files = listOf("guava-33.0.jar"),
                capabilities = listOf(Dep("com.google.guava", "guava", "33.0"), Dep("com.google.collections", "google-collections", "33.0"))),
        ))
        files.put("google-collections", "1.0", group = "com.google.collections")   // POM-only; implicit capability
        val (resolver, _) = newResolver(files)
        val result = runBlocking {
            resolver.resolve(
                listOf(Coordinate("com.google.guava", "guava", "33.0"), Coordinate("com.google.collections", "google-collections", "1.0")),
                listOf(repo), ConflictPolicy.NEWEST, noProgress,
            )
        }
        val names = result.resolved.map { it.coordinate.name }.toSet()
        assertTrue("guava" in names, "the superseding module is kept: $names")
        assertFalse("google-collections" in names, "the superseded module is evicted: $names")
    }

    @Test
    fun capabilityConflictEvictsTheLowerVersionUnconditionally() {
        // Two modules both declare the `g:shared` capability. Gradle selects one (the highest capability version)
        // and evicts the other entirely — it never keeps both. b (2.0) wins; a (1.0) is gone.
        val files = FakeRepo()
        files.putModule("a", "1.0", variants = listOf(
            GmmVar("api", libApi, files = listOf("a-1.0.jar"),
                capabilities = listOf(Dep("g", "a", "1.0"), Dep("g", "shared", "1.0"))),
        ))
        files.putModule("b", "2.0", variants = listOf(
            GmmVar("api", libApi, files = listOf("b-2.0.jar"),
                capabilities = listOf(Dep("g", "b", "2.0"), Dep("g", "shared", "2.0"))),
        ))
        val (resolver, _) = newResolver(files)
        val result = runBlocking {
            resolver.resolve(listOf(coord("a", "1.0"), coord("b", "2.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress)
        }
        assertEquals(setOf("b"), result.resolved.map { it.coordinate.name }.toSet(), "highest capability version wins; the loser is evicted")
    }

    @Test
    fun evictedModulesExclusiveTransitivesArePruned() {
        // guava supersedes google-collections; google-collections' own exclusive transitive (`gc-only`) must be
        // pruned from the graph — Gradle keeps only what's reachable once the loser is evicted.
        val g = "com.google.guava"
        val files = FakeRepo()
        files.putModule("guava", "33.0", group = g, variants = listOf(
            GmmVar("api", libApi, files = listOf("guava-33.0.jar"),
                capabilities = listOf(Dep(g, "guava", "33.0"), Dep("com.google.collections", "google-collections", "33.0"))),
        ))
        // google-collections (POM) pulls a transitive nothing else needs.
        files.put("google-collections", "1.0", group = "com.google.collections", deps = listOf(Dep("com.google.collections", "gc-only", "1.0")))
        files.put("gc-only", "1.0", group = "com.google.collections")
        val (resolver, _) = newResolver(files)
        val result = runBlocking {
            resolver.resolve(
                listOf(Coordinate(g, "guava", "33.0"), Coordinate("com.google.collections", "google-collections", "1.0")),
                listOf(repo), ConflictPolicy.NEWEST, noProgress,
            )
        }
        val names = result.resolved.map { it.coordinate.name }.toSet()
        assertEquals(setOf("guava"), names, "google-collections + its exclusive transitive gc-only are pruned: $names")
    }

    @Test
    fun gmmVariantDependenciesReplacePomTransitives() {
        // When a `.module` is present its selected variant's dependencies are authoritative; the POM's
        // `<dependencies>` (which a Gradle-published artifact keeps only for legacy Maven consumers) are ignored.
        val files = FakeRepo()
        files.putModule("lib", "1.0",
            variants = listOf(GmmVar("apiElements", mapOf("org.gradle.category" to "library", "org.gradle.usage" to "java-api"),
                files = listOf("lib-1.0.jar"), deps = listOf(Dep("g", "gmmDep", "1.0")))),
            pomDeps = listOf(Dep("g", "pomOnly", "1.0")),
        )
        files.put("gmmDep", "1.0")
        files.put("pomOnly", "1.0")
        val (resolver, _) = newResolver(files)
        val result = runBlocking { resolver.resolve(listOf(coord("lib", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        val names = result.resolved.map { it.coordinate.name }.toSet()
        assertTrue("gmmDep" in names, "GMM variant deps must be used: $names")
        assertFalse("pomOnly" in names, "POM deps must be ignored when GMM is present: $names")
    }

    @Test
    fun fallsBackToPomWhenNoGmm() {
        // No `.module` published → the resolver must behave exactly as the POM-only path (back-compat).
        val files = FakeRepo()
        files.put("a", "1.0", deps = listOf(Dep("g", "b", "1.0")))
        files.put("b", "1.0")
        val (resolver, _) = newResolver(files)
        val result = runBlocking { resolver.resolve(listOf(coord("a", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        assertEquals(setOf("a", "b"), result.resolved.map { it.coordinate.name }.toSet())
    }

    @Test
    fun picksRuntimeVariantWhenNoApiVariant() {
        // A library publishing only runtime variants must still resolve (the api→runtime usage fallback).
        val files = FakeRepo()
        files.putModule("lib", "1.0", variants = listOf(
            GmmVar("runtimeElements", mapOf(
                "org.gradle.category" to "library", "org.gradle.usage" to "java-runtime",
                "org.jetbrains.kotlin.platform.type" to "androidJvm", "org.gradle.jvm.environment" to "android",
            ), files = listOf("lib-1.0.jar")),
        ))
        val (resolver, _) = newResolver(files)
        val result = runBlocking { resolver.resolve(listOf(coord("lib", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        assertEquals(setOf("lib"), result.resolved.map { it.coordinate.name }.toSet())
    }

    @Test
    fun gmmResolvesOfflineFromCacheAfterFirstFetch() {
        val files = FakeRepo()
        files.putModule("lib", "1.0", variants = listOf(
            GmmVar("androidApiElements", androidApi, files = listOf("lib-android-1.0.jar")),
            GmmVar("jvmApiElements", jvmApi, files = listOf("lib-jvm-1.0.jar")),
        ))
        val tmp = createTempDirectory("deps-gmm-offline")
        val lfs = LocalFileSystem(tmp)
        val cache = ResolverCache(tmp)
        runBlocking { MavenDependencyResolver(cache, lfs::fileFor, files).resolve(listOf(coord("lib", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }

        val offline = MavenDependencyResolver(cache, lfs::fileFor, ArtifactFetcher { null })
        val result = runBlocking { offline.resolve(listOf(coord("lib", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        val art = result.resolved.single { it.coordinate.name == "lib" }
        assertTrue(art.classesRoot.path.endsWith("lib-android-1.0.jar"), "offline GMM resolve still picks -android: ${art.classesRoot.path}")
    }

    @Test
    fun unresolvedWhenRepoLacksTheArtifact() {
        val (resolver, _) = newResolver(FakeRepo())
        val result = runBlocking { resolver.resolve(listOf(coord("ghost", "9.9")), listOf(repo), ConflictPolicy.NEWEST, noProgress) }
        assertTrue(result.resolved.isEmpty())
        assertEquals(listOf(coord("ghost", "9.9")), result.unresolved)
    }

    @Test
    fun availableVersionsReturnsPublishedVersionsNewestFirst() {
        // maven-metadata lists versions oldest-first; availableVersions must return them newest-first
        // (MavenVersion order: a release outranks its pre-releases, e.g. 2.0.0 > 2.0.0-rc1).
        val files = FakeRepo()
        files.putMetadata("widget", listOf("1.0.0", "1.2.0", "2.0.0-rc1", "2.0.0"))
        val (resolver, _) = newResolver(files)

        val versions = runBlocking { resolver.availableVersions("g", "widget", listOf(repo)) }
        assertEquals(listOf("2.0.0", "2.0.0-rc1", "1.2.0", "1.0.0"), versions)
    }

    @Test
    fun availableVersionsMergesAcrossRepositoriesAndDedupes() {
        // The same artifact can be published to more than one repo; the union is returned once, newest-first.
        val other = "https://fixture/other"
        val files = FakeRepo()
        files.putMetadata("widget", listOf("1.0.0", "2.0.0"), base = BASE)
        files.putMetadata("widget", listOf("2.0.0", "3.0.0"), base = other)
        val (resolver, _) = newResolver(files)

        val versions = runBlocking {
            resolver.availableVersions("g", "widget", listOf(repo, Repository("other", other)))
        }
        assertEquals(listOf("3.0.0", "2.0.0", "1.0.0"), versions)
    }

    @Test
    fun availableVersionsEmptyWhenNoMetadata() {
        val (resolver, _) = newResolver(FakeRepo())
        val versions = runBlocking { resolver.availableVersions("g", "missing", listOf(repo)) }
        assertTrue(versions.isEmpty(), "no metadata → no versions: $versions")
    }

    @Test
    fun searchFindsGoogleMavenArtifactNotOnCentral() {
        // androidx.* live ONLY on Google Maven (not mirrored to Central), so search must read the Google index.
        val files = FakeRepo()
        files.putGoogleMaster("androidx.documentfile", "androidx.core", "androidx.annotation")
        files.putGoogleGroup("androidx.documentfile", mapOf("documentfile" to listOf("1.0.0", "1.1.0-rc01", "1.1.0")))
        files.putGooglePom("androidx.documentfile", "documentfile", "1.1.0", "aar")

        val resolver = googleResolver(files)
        val hits = runBlocking { resolver.search("androidx.documentfile") }

        val hit = hits.single { it.coordinate.group == "androidx.documentfile" }
        assertEquals("documentfile", hit.coordinate.name)
        assertEquals("1.1.0", hit.coordinate.version, "newest STABLE, not the -rc01 pre-release")
        assertEquals("aar", hit.packaging)
    }

    @Test
    fun searchReadsPackagingFromPomAndPrefersStable() {
        // A jar-packaged androidx lib (annotation) must be reported as `jar` (POM has no <packaging>), and the
        // stable version wins over a newer alpha so the picker defaults to something releasable.
        val files = FakeRepo()
        files.putGoogleMaster("androidx.annotation")
        files.putGoogleGroup("androidx.annotation", mapOf("annotation" to listOf("1.8.0", "1.9.0-alpha01")))
        files.putGooglePom("androidx.annotation", "annotation", "1.8.0", "jar")

        val resolver = googleResolver(files)
        val hit = runBlocking { resolver.search("androidx.annotation:annotation") }.single()

        assertEquals("1.8.0", hit.coordinate.version, "stable 1.8.0 preferred over 1.9.0-alpha01")
        assertEquals("jar", hit.packaging)
    }

    @Test
    fun searchMatchesGoogleGroupByBareNameSubstring() {
        // Typing just the artifact name ("documentfile") matches the group whose id contains it.
        val files = FakeRepo()
        files.putGoogleMaster("androidx.documentfile", "androidx.core")
        files.putGoogleGroup("androidx.documentfile", mapOf("documentfile" to listOf("1.1.0")))
        files.putGooglePom("androidx.documentfile", "documentfile", "1.1.0", "aar")

        val resolver = googleResolver(files)
        val hits = runBlocking { resolver.search("documentfile") }

        assertEquals("androidx.documentfile:documentfile:1.1.0", hits.single().coordinate.toString())
    }

    @Test
    fun searchAcceptsFullyTypedCoordinateInTheBox() {
        // Pasting "group:name:version" into the search box still finds the artifact (the version is ignored
        // for matching; the picker defaults to the newest).
        val files = FakeRepo()
        files.putGoogleMaster("androidx.documentfile")
        files.putGoogleGroup("androidx.documentfile", mapOf("documentfile" to listOf("1.0.0", "1.1.0")))
        files.putGooglePom("androidx.documentfile", "documentfile", "1.1.0", "aar")

        val resolver = googleResolver(files)
        val hits = runBlocking { resolver.search("androidx.documentfile:documentfile:1.0.0") }

        assertEquals("androidx.documentfile:documentfile:1.1.0", hits.single().coordinate.toString())
    }

    @Test
    fun searchEmptyWhenNoGoogleGroupMatchesAndCentralOffline() {
        val files = FakeRepo()
        files.putGoogleMaster("androidx.core", "androidx.appcompat")
        val resolver = googleResolver(files)
        val hits = runBlocking { resolver.search("com.squareup.retrofit2") }
        assertTrue(hits.isEmpty(), "no group matches and Central returns nothing: $hits")
    }

    private fun googleResolver(files: FakeRepo): MavenDependencyResolver {
        val tmp = createTempDirectory("deps-search")
        val lfs = LocalFileSystem(tmp)
        return MavenDependencyResolver(ResolverCache(tmp), lfs::fileFor, files, googleMavenBase = GOOGLE)
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

    /** A Gradle Module Metadata variant fixture: its attributes, published files, deps, and optional redirect.
     *  [files] are logical names; [fileUrls] overrides a name's actual download path (AGP's name≠url case). */
    private data class GmmVar(
        val name: String,
        val attrs: Map<String, String>,
        val files: List<String> = emptyList(),
        val deps: List<Dep> = emptyList(),
        val availableAt: Triple<String, String, String>? = null,   // group, module, version
        val fileUrls: Map<String, String> = emptyMap(),
        val strictDeps: List<Dep> = emptyList(),       // deps published with version.strictly
        val constraints: List<Dep> = emptyList(),      // dependencyConstraints (g:a:v)
        val capabilities: List<Dep> = emptyList(),     // declared capabilities (g:a:v); empty = implicit only
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

        /** Publish Google Maven's `master-index.xml` listing [groups] (self-closing `<group.id/>` entries). */
        fun putGoogleMaster(vararg groups: String) {
            val entries = groups.joinToString("\n") { "  <$it/>" }
            byUrl["$GOOGLE/master-index.xml"] = "<?xml version='1.0' encoding='UTF-8'?>\n<metadata>\n$entries\n</metadata>".toByteArray()
        }

        /** Publish a group's `group-index.xml` (`<artifact versions="a,b,c"/>` per [artifacts] entry). */
        fun putGoogleGroup(group: String, artifacts: Map<String, List<String>>) {
            val body = artifacts.entries.joinToString("\n") { (a, vs) -> "  <$a versions=\"${vs.joinToString(",")}\"/>" }
            byUrl["$GOOGLE/${group.replace('.', '/')}/group-index.xml"] = "<$group>\n$body\n</$group>".toByteArray()
        }

        /** Publish a POM under the Google base so [MavenDependencyResolver.search] can read its packaging. */
        fun putGooglePom(group: String, artifact: String, version: String, packaging: String) {
            val rel = "${group.replace('.', '/')}/$artifact/$version/$artifact-$version.pom"
            byUrl["$GOOGLE/$rel"] = pom(group, artifact, version, packaging, emptyList()).toByteArray()
        }

        /** Publish a `maven-metadata.xml` listing [versions] for [group]:[name] under [base] (the version index). */
        fun putMetadata(name: String, versions: List<String>, group: String = "g", base: String = BASE) {
            val rel = "${group.replace('.', '/')}/$name/maven-metadata.xml"
            byUrl["$base/$rel"] = metadata(group, name, versions).toByteArray()
        }

        /** Publish a `*.module` (Gradle Module Metadata) + a POM + every variant's files (as empty jars). */
        fun putModule(
            name: String, version: String, variants: List<GmmVar>,
            group: String = "g", pomDeps: List<Dep> = emptyList(), packaging: String = "jar",
        ) {
            byUrl[url(group, name, version, "module")] = gmmJson(group, name, version, variants).toByteArray()
            byUrl[url(group, name, version, "pom")] = pom(group, name, version, packaging, pomDeps).toByteArray()
            for (v in variants) for (f in v.files) {
                val actual = v.fileUrls[f] ?: f   // the real download path (AGP's name≠url case)
                val bytes = if (actual.endsWith(".aar")) aarWithRes() else emptyJar()
                byUrl["$BASE/${group.replace('.', '/')}/$name/$version/$actual"] = bytes
            }
        }
    }

    private fun gmmJson(group: String, name: String, version: String, variants: List<GmmVar>): String = buildString {
        append("""{"formatVersion":"1.1","component":{"group":"$group","module":"$name","version":"$version"},"variants":[""")
        variants.forEachIndexed { i, v ->
            if (i > 0) append(",")
            append("""{"name":"${v.name}","attributes":{""")
            v.attrs.entries.forEachIndexed { j, (k, value) -> if (j > 0) append(","); append(""""$k":"$value"""") }
            append("}")
            v.availableAt?.let { append(""","available-at":{"group":"${it.first}","module":"${it.second}","version":"${it.third}"}""") }
            val allDeps = v.deps.map { it to "requires" } + v.strictDeps.map { it to "strictly" }
            if (allDeps.isNotEmpty()) {
                append(""","dependencies":[""")
                allDeps.forEachIndexed { j, (d, key) -> if (j > 0) append(","); append("""{"group":"${d.g}","module":"${d.a}","version":{"$key":"${d.v}"}}""") }
                append("]")
            }
            if (v.constraints.isNotEmpty()) {
                append(""","dependencyConstraints":[""")
                v.constraints.forEachIndexed { j, c -> if (j > 0) append(","); append("""{"group":"${c.g}","module":"${c.a}","version":{"requires":"${c.v}"}}""") }
                append("]")
            }
            if (v.capabilities.isNotEmpty()) {
                append(""","capabilities":[""")
                v.capabilities.forEachIndexed { j, c -> if (j > 0) append(","); append("""{"group":"${c.g}","name":"${c.a}","version":"${c.v}"}""") }
                append("]")
            }
            if (v.files.isNotEmpty()) {
                append(""","files":[""")
                v.files.forEachIndexed { j, f -> if (j > 0) append(","); append("""{"name":"$f","url":"${v.fileUrls[f] ?: f}"}""") }
                append("]")
            }
            append("}")
        }
        append("]}")
    }

    private fun metadata(g: String, a: String, versions: List<String>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><metadata>""")
        append("<groupId>$g</groupId><artifactId>$a</artifactId><versioning>")
        versions.lastOrNull()?.let { append("<latest>$it</latest><release>$it</release>") }
        append("<versions>")
        versions.forEach { append("<version>$it</version>") }
        append("</versions></versioning></metadata>")
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
        const val GOOGLE = "https://fixture/google"
    }
}
