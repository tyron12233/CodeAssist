package dev.ide.deps.impl

import dev.ide.deps.ConflictPolicy
import dev.ide.deps.Repository
import dev.ide.model.Coordinate
import dev.ide.platform.ProgressReporter
import dev.ide.vfs.local.LocalFileSystem
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Resolution of a module's SECONDARY artifacts, the Maven classifiers behind Gradle's four-part
 * `group:name:version:classifier` notation.
 *
 * The case these are modeled on is `com.badlogicgames.gdx:gdx-platform`: `pom` packaging, an empty
 * `<dependencies>`, and no main jar published at all. Everything it ships hangs off a classifier, one per
 * ABI. So the plain three-part coordinate resolves without error and yields nothing, and the classifier is
 * the only way to name anything real.
 */
class ClassifierArtifactResolutionTest {

    private val noProgress = object : ProgressReporter {
        override fun report(fraction: Double, message: String?) {}
        override fun checkCanceled() {}
        override val isCanceled: Boolean = false
    }

    private val repo = Repository("fixture", BASE)

    @Test
    fun classifierOnlyModuleResolvesEveryDeclaredClassifier() {
        val files = FakeRepo()
        files.putClassifierOnly("gdx-platform", "1.14.2", listOf("natives-arm64-v8a", "natives-armeabi-v7a"))

        val result = runBlocking {
            resolver(files).resolve(
                listOf(
                    Coordinate(GROUP, "gdx-platform", "1.14.2", "natives-arm64-v8a"),
                    Coordinate(GROUP, "gdx-platform", "1.14.2", "natives-armeabi-v7a"),
                ),
                listOf(repo), ConflictPolicy.NEWEST, noProgress,
            )
        }

        assertTrue(result.unresolved.isEmpty(), "unexpected unresolved: ${result.unresolved}")
        assertTrue(result.artifactless.isEmpty(), "a classifier was asked for, so nothing is artifactless")
        assertEquals(
            listOf("natives-arm64-v8a", "natives-armeabi-v7a"),
            result.resolved.mapNotNull { it.coordinate.classifier }.sorted(),
            "both declared classifiers must resolve; the module publishes no main jar to fall back on",
        )
        // Each resolved artifact points at its OWN file, not at one shared download.
        assertEquals(
            listOf("gdx-platform-1.14.2-natives-arm64-v8a.jar", "gdx-platform-1.14.2-natives-armeabi-v7a.jar"),
            result.resolved.map { it.classesRoot.path.substringAfterLast('/') }.sorted(),
        )
    }

    @Test
    fun plainCoordinateOfAClassifierOnlyModuleIsReportedAsArtifactless() {
        val files = FakeRepo()
        files.putClassifierOnly("gdx-platform", "1.14.2", listOf("natives-arm64-v8a"))

        val result = runBlocking {
            resolver(files).resolve(
                listOf(Coordinate(GROUP, "gdx-platform", "1.14.2")),
                listOf(repo), ConflictPolicy.NEWEST, noProgress,
            )
        }

        // Nothing FAILED: the POM was there and was read. It just gave nothing back, which is the whole
        // point of reporting it separately from `unresolved` (a retry can't change this outcome).
        assertTrue(result.unresolved.isEmpty(), "the POM resolves fine: ${result.unresolved}")
        assertTrue(result.resolved.isEmpty(), "there is no main artifact to resolve: ${result.resolved}")
        val reported = result.artifactless.single()
        assertEquals(Coordinate(GROUP, "gdx-platform", "1.14.2"), reported.coordinate)
        assertTrue("classifier" in reported.reason, "the reason must point at classifiers: ${reported.reason}")
    }

    @Test
    fun anAggregatorPomIsNotArtifactlessBecauseItsClosureIsWhatItContributes() {
        val files = FakeRepo()
        files.put("kernel", "1.0")
        files.putPomOnly("aggregator", "1.0", deps = listOf("kernel" to "1.0"))

        val result = runBlocking {
            resolver(files).resolve(
                listOf(Coordinate(GROUP, "aggregator", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress,
            )
        }
        assertEquals(listOf("kernel"), result.resolved.map { it.coordinate.name })
        assertTrue(
            result.artifactless.isEmpty(),
            "a `pom` that pulls a real closure contributes that closure: ${result.artifactless}",
        )
    }

    @Test
    fun aClassifierOnATransitiveEdgeIsFetchedAlongsideTheMainArtifact() {
        val files = FakeRepo()
        // `lib` depends on `native-bits` twice: once plainly, once for its `natives-arm64-v8a` file. Maven
        // resolves ONE version for the module and both of its artifacts.
        files.putClassified("native-bits", "2.0", listOf("natives-arm64-v8a"))
        files.put("lib", "1.0", deps = listOf(Dep("native-bits", "2.0"), Dep("native-bits", "2.0", "natives-arm64-v8a")))

        val result = runBlocking {
            resolver(files).resolve(
                listOf(Coordinate(GROUP, "lib", "1.0")), listOf(repo), ConflictPolicy.NEWEST, noProgress,
            )
        }
        assertTrue(result.unresolved.isEmpty(), "unexpected unresolved: ${result.unresolved}")
        assertEquals(
            listOf("lib:null", "native-bits:natives-arm64-v8a", "native-bits:null"),
            result.resolved.map { "${it.coordinate.name}:${it.coordinate.classifier}" }.sorted(),
        )
    }

    @Test
    fun aClassifierArtifactDoesNotProbeForItsOwnSourcesJar() {
        val files = FakeRepo()
        files.putClassifierOnly("gdx-platform", "1.14.2", listOf("natives-arm64-v8a"))

        val result = runBlocking {
            resolver(files).resolve(
                listOf(Coordinate(GROUP, "gdx-platform", "1.14.2", "natives-arm64-v8a")),
                listOf(repo), ConflictPolicy.NEWEST, noProgress,
            )
        }
        // There is no `-natives-arm64-v8a-sources.jar` convention, so no request should be made for one.
        assertTrue(
            files.requested.none { it.contains("sources") },
            "a classifier artifact must not spend a 404 on sources: ${files.requested.filter { "sources" in it }}",
        )
        assertEquals(null, result.resolved.single().sourcesRoot)
    }

    private fun resolver(files: FakeRepo): MavenDependencyResolver {
        val tmp = createTempDirectory("classifier-deps-test")
        return MavenDependencyResolver(ResolverCache(tmp), LocalFileSystem(tmp)::fileFor, files)
    }

    private data class Dep(val name: String, val version: String, val classifier: String? = null)

    /** An in-memory Maven repo keyed by request URL, recording every URL asked for. */
    private class FakeRepo : ArtifactFetcher {
        private val byUrl = HashMap<String, ByteArray>()
        val requested = ArrayList<String>()

        override fun fetch(url: String): ByteArray? {
            requested.add(url)
            return byUrl[url]
        }

        fun put(name: String, version: String, deps: List<Dep> = emptyList()) {
            byUrl[url(name, version, "pom")] = pom(name, version, "jar", deps).toByteArray()
            byUrl[url(name, version, "jar")] = emptyJar()
        }

        /** A module with a main jar AND classifier files, e.g. an ordinary library shipping natives too. */
        fun putClassified(name: String, version: String, classifiers: List<String>) {
            put(name, version)
            for (c in classifiers) byUrl[url(name, version, "jar", c)] = emptyJar()
        }

        /** The `gdx-platform` shape: `pom` packaging, no dependencies, no main jar, classifier files only. */
        fun putClassifierOnly(name: String, version: String, classifiers: List<String>) {
            byUrl[url(name, version, "pom")] = pom(name, version, "pom", emptyList()).toByteArray()
            for (c in classifiers) byUrl[url(name, version, "jar", c)] = emptyJar()
        }

        /** An aggregator: `pom` packaging with real dependencies, whose closure IS its contribution. */
        fun putPomOnly(name: String, version: String, deps: List<Pair<String, String>>) {
            byUrl[url(name, version, "pom")] =
                pom(name, version, "pom", deps.map { Dep(it.first, it.second) }).toByteArray()
        }

        private fun url(name: String, version: String, ext: String, classifier: String? = null): String {
            val suffix = if (classifier == null) "" else "-$classifier"
            return "$BASE/${GROUP.replace('.', '/')}/$name/$version/$name-$version$suffix.$ext"
        }

        private fun pom(name: String, version: String, packaging: String, deps: List<Dep>) = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?><project>""")
            append("<groupId>$GROUP</groupId><artifactId>$name</artifactId><version>$version</version>")
            append("<packaging>$packaging</packaging>")
            if (deps.isNotEmpty()) {
                append("<dependencies>")
                for (d in deps) {
                    append("<dependency><groupId>$GROUP</groupId><artifactId>${d.name}</artifactId>")
                    append("<version>${d.version}</version>")
                    d.classifier?.let { append("<classifier>$it</classifier>") }
                    append("</dependency>")
                }
                append("</dependencies>")
            }
            append("</project>")
        }

        private fun emptyJar(): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("libgdx.so")); it.write(byteArrayOf(1, 2, 3)); it.closeEntry() }
            return out.toByteArray()
        }
    }

    private companion object {
        const val BASE = "https://fixture/repo"
        const val GROUP = "com.badlogicgames.gdx"
    }
}
