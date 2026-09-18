package dev.ide.deps.impl

import dev.ide.deps.ConflictPolicy
import dev.ide.deps.Repository
import dev.ide.platform.deleteFile
import dev.ide.platform.fileInfo
import dev.ide.platform.listDirectory
import dev.ide.platform.readFile
import dev.ide.model.Coordinate
import dev.ide.platform.ContentHash
import dev.ide.platform.ProgressReporter
import dev.ide.vfs.VirtualFile
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Dependency resolution, on whatever platform this runs on.
 *
 * The engine was always written around one injectable I/O seam, so that it could be driven offline against
 * a fixture repository. That is what makes this possible at all — but running it against fixtures on the
 * JVM is not the claim. The claim is that a POM parsed by a hand-written XML reader, a transitive graph
 * walked with a portable concurrent map, and a cache written through a six-operation file system all still
 * agree with each other when `java.nio.file`, `javax.xml.parsers` and `java.util.zip` are not there.
 *
 * What is NOT covered here is the socket: `DepsHttp` is per-platform by definition and a unit test has no
 * business making network calls. The fixture below stands exactly where it does.
 */
class ResolveOffTheJvmTest {

    private val dir = scratchPath("resolve-${nowSuffix()}")

    @AfterTest
    fun cleanUp() {
        deleteTree(dir)
    }

    private val repository = Repository("fixture", BASE)

    private val silent = object : ProgressReporter {
        override fun report(fraction: Double, message: String?) {}
        override fun checkCanceled() {}
        override val isCanceled: Boolean get() = false
    }

    // ---- the graph -------------------------------------------------------------------------------------

    @Test
    fun aTransitiveGraphResolvesAndTheNewestVersionWins() = runTest {
        val repo = FixtureRepo()
        // app -> lib 1.0, and app -> other -> lib 2.0. Newest-wins must settle lib at 2.0, once.
        repo.put("app", "1.0", deps = listOf(Dep("lib", "1.0"), Dep("other", "1.0")))
        repo.put("other", "1.0", deps = listOf(Dep("lib", "2.0")))
        repo.put("lib", "1.0")
        repo.put("lib", "2.0")

        val result = resolver(repo).resolve(
            listOf(Coordinate("g", "app", "1.0")),
            listOf(repository),
            ConflictPolicy.NEWEST,
            silent,
        )

        assertEquals(emptyList(), result.unresolved, "everything the fixture publishes must resolve")
        assertEquals(
            listOf("g:app:1.0", "g:lib:2.0", "g:other:1.0"),
            result.resolved.map { it.coordinate.toString() }.sorted(),
            "the closure, with one lib at the winning version",
        )
        val conflict = assertNotNull(result.conflicts.firstOrNull { it.coordinate == "g:lib" })
        assertEquals("2.0", conflict.chosen)
    }

    /** A `<dependencyManagement>` version reaches a child that declares none — through the parent chain. */
    @Test
    fun aManagedVersionFillsInAVersionlessDependency() = runTest {
        val repo = FixtureRepo()
        repo.put("parent", "1.0", packaging = "pom", managed = listOf(Dep("lib", "3.0")))
        repo.put("app", "1.0", deps = listOf(Dep("lib", null)), parent = Triple("g", "parent", "1.0"))
        repo.put("lib", "3.0")

        val result = resolver(repo).resolve(
            listOf(Coordinate("g", "app", "1.0")),
            listOf(repository),
            ConflictPolicy.NEWEST,
            silent,
        )

        assertEquals(emptyList(), result.unresolved)
        assertTrue(
            result.resolved.any { it.coordinate.toString() == "g:lib:3.0" },
            "dependencyManagement in the parent supplies the version; got ${result.resolved.map { it.coordinate }}",
        )
    }

    /** Resolution is also a download: the artifacts have to be ON DISK afterwards, in Maven layout. */
    @Test
    fun theResolvedArtifactsAreWrittenToTheCache() = runTest {
        val repo = FixtureRepo()
        repo.put("solo", "1.0")
        val cache = ResolverCache(dir)

        val result = MavenDependencyResolver(cache, ::DiskFile, repo).resolve(
            listOf(Coordinate("g", "solo", "1.0")),
            listOf(repository),
            ConflictPolicy.NEWEST,
            silent,
        )

        assertEquals(1, result.resolved.size)
        val jar = cache.fileFor(cache.relativePath(Coordinate("g", "solo", "1.0"), "jar"))
        assertTrue(fileInfo(jar) != null, "the jar must be cached at its Maven path: $jar")
        assertContentEquals(JAR_BYTES, readFile(jar), "and it must be the bytes the repository served")
        assertEquals(jar, result.resolved.single().classesRoot.path)
    }

    /** The second resolve must not touch the fetcher at all: the cache IS the offline repository. */
    @Test
    fun aSecondResolveAnswersFromTheCacheWithNoFetches() = runTest {
        val repo = FixtureRepo()
        repo.put("solo", "1.0")
        val cache = ResolverCache(dir)
        MavenDependencyResolver(cache, ::DiskFile, repo).resolve(
            listOf(Coordinate("g", "solo", "1.0")), listOf(repository), ConflictPolicy.NEWEST, silent,
        )

        val offline = MavenDependencyResolver(cache, ::DiskFile, ArtifactFetcher { error("the network was used") })
        val result = offline.resolve(
            listOf(Coordinate("g", "solo", "1.0")), listOf(repository), ConflictPolicy.NEWEST, silent,
        )
        assertEquals(1, result.resolved.size, "a warm cache resolves with no fetcher at all")
    }

    // ---- the reader ------------------------------------------------------------------------------------

    /** The POM parser, now over a hand-written XML reader rather than `javax.xml.parsers`. */
    @Test
    fun aPomParsesWithItsParentPropertiesAndExclusions() {
        val pom = PomParser.parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!-- a comment, which is not a field -->
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <parent>
                <groupId>com.example</groupId><artifactId>base</artifactId><version>2.1</version>
              </parent>
              <artifactId>child</artifactId>
              <packaging>aar</packaging>
              <properties><lib.version>4.5</lib.version></properties>
              <dependencies>
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>${'$'}{lib.version}</version>
                  <scope>compile</scope>
                  <exclusions>
                    <exclusion><groupId>junk</groupId><artifactId>*</artifactId></exclusion>
                  </exclusions>
                </dependency>
                <dependency><groupId>com.example</groupId><artifactId>opt</artifactId><optional>true</optional></dependency>
              </dependencies>
            </project>
            """.trimIndent().encodeToByteArray(),
        )

        assertEquals(Coordinate("com.example", "base", "2.1"), pom.parent)
        assertEquals("child", pom.artifactId)
        assertEquals("aar", pom.packaging)
        // The group and version come from the parent, which is the whole point of the chain.
        assertEquals(Coordinate("com.example", "child", "2.1"), pom.coordinate())
        assertEquals("4.5", resolveProperties(pom.dependencies[0].version, pom.properties, pom.coordinate()))
        assertEquals(setOf(GA("junk", "*")), pom.dependencies[0].exclusions)
        assertTrue(pom.dependencies[1].optional)
    }

    /** The five predefined entities and a numeric reference, which a POM's `<name>`/`<url>` really do use. */
    @Test
    fun theXmlReaderResolvesEntitiesAndSkipsWhatIsNotAField() {
        val root = Xml.parse(
            """
            <project>
              <name>A &amp; B &lt;ok&gt; &#65;</name>
              <empty/>
              <cdata><![CDATA[<not a tag>]]></cdata>
            </project>
            """.trimIndent(),
        )
        assertEquals("A & B <ok> A", root.childText("name"))
        assertEquals("", root.child("empty")?.text)
        assertEquals("<not a tag>", root.childText("cdata"))
    }

    /** A DOCTYPE is refused rather than parsed: this reads bytes fetched off a repository. */
    @Test
    fun theXmlReaderRefusesADoctype() {
        val thrown = runCatching {
            Xml.parse("""<!DOCTYPE project SYSTEM "http://example.com/evil.dtd"><project/>""")
        }.exceptionOrNull()
        assertTrue(thrown is Xml.XmlException, "a DTD must not be parsed at all; got $thrown")
    }

    @Test
    fun aQueryIsPercentEncodedForASearchUrl() {
        assertEquals("a+b%2Fc%3Ad", percentEncode("a b/c:d"))
        assertEquals("caf%C3%A9", percentEncode("café"), "non-ASCII goes out as its UTF-8 bytes")
    }

    // ---- fixtures --------------------------------------------------------------------------------------

    private fun resolver(repo: ArtifactFetcher) = MavenDependencyResolver(ResolverCache(dir), ::DiskFile, repo)

    private class Dep(val name: String, val version: String?, val group: String = "g")

    /** An in-memory Maven repository: the URLs the resolver builds, mapped to the bytes it would fetch. */
    private class FixtureRepo : ArtifactFetcher {
        private val byUrl = HashMap<String, ByteArray>()

        override fun fetch(url: String): ByteArray? = byUrl[url]

        fun put(
            name: String,
            version: String,
            packaging: String = "jar",
            deps: List<Dep> = emptyList(),
            managed: List<Dep> = emptyList(),
            parent: Triple<String, String, String>? = null,
            group: String = "g",
        ) {
            val rel = "${group.replace('.', '/')}/$name/$version/$name-$version"
            byUrl["$BASE/$rel.pom"] = pom(group, name, version, packaging, deps, managed, parent).encodeToByteArray()
            if (packaging != "pom") byUrl["$BASE/$rel.jar"] = JAR_BYTES
        }

        private fun pom(
            group: String,
            name: String,
            version: String,
            packaging: String,
            deps: List<Dep>,
            managed: List<Dep>,
            parent: Triple<String, String, String>?,
        ): String = buildString {
            append("<?xml version=\"1.0\"?>\n<project>\n")
            parent?.let { (g, a, v) ->
                append("<parent><groupId>$g</groupId><artifactId>$a</artifactId><version>$v</version></parent>\n")
            }
            append("<groupId>$group</groupId><artifactId>$name</artifactId><version>$version</version>\n")
            append("<packaging>$packaging</packaging>\n")
            if (managed.isNotEmpty()) {
                append("<dependencyManagement><dependencies>\n")
                managed.forEach { append(dependency(it)) }
                append("</dependencies></dependencyManagement>\n")
            }
            if (deps.isNotEmpty()) {
                append("<dependencies>\n")
                deps.forEach { append(dependency(it)) }
                append("</dependencies>\n")
            }
            append("</project>\n")
        }

        private fun dependency(d: Dep): String = buildString {
            append("<dependency><groupId>${d.group}</groupId><artifactId>${d.name}</artifactId>")
            d.version?.let { append("<version>$it</version>") }
            append("</dependency>\n")
        }
    }

    private companion object {
        const val BASE = "https://fixture.invalid/maven"

        /** Not a real archive: nothing in this test opens it, and an AAR (which would be opened) is the one
         *  thing the platform seam covers. */
        val JAR_BYTES = "not-really-a-jar".encodeToByteArray()
    }
}

/** A [VirtualFile] over the portable file system, for the resolver's `fileFor`. */
private class DiskFile(override val path: String) : VirtualFile {
    private val info get() = fileInfo(path)
    override val name: String get() = path.trimEnd('/').substringAfterLast('/')
    override val isDirectory: Boolean get() = info?.isDirectory == true
    override val exists: Boolean get() = info != null
    override val length: Long get() = info?.size ?: 0L
    override fun parent(): VirtualFile? {
        val at = path.trimEnd('/').lastIndexOf('/')
        return if (at <= 0) null else DiskFile(path.substring(0, at))
    }

    override fun children(): List<VirtualFile> =
        if (!isDirectory) emptyList() else listDirectory(path).map { DiskFile(it) }

    override fun contentHash(): ContentHash = ContentHash.of(readBytes())
    override fun readBytes(): ByteArray = readFile(path) ?: ByteArray(0)
    override fun readText(): CharSequence = readBytes().decodeToString()
}

/** Remove [path] and everything under it, best effort. */
private fun deleteTree(path: String) {
    val info = fileInfo(path) ?: return
    if (info.isDirectory) for (child in listDirectory(path)) deleteTree(child)
    deleteFile(path)
}
