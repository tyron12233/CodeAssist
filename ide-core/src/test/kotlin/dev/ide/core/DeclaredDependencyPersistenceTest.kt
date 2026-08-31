package dev.ide.core

import dev.ide.model.LibraryDependency
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A change to a module's DECLARED dependencies has to reach `module.toml` on its own.
 *
 * The classpath assembly that follows an add/edit saves the model only when the LIBRARY TABLE changed, and a
 * declaration can change without changing any library: [DependencyPartition] claims each resolved artifact for
 * the FIRST declarer that reaches it, so a coordinate an earlier declarer already pulls in transitively gets an
 * empty partition and rewrites nothing. The fixture below is that shape — `lib-a` depends on `shared:2.0.0`,
 * and `shared` is ALSO declared directly, exactly as `kotlinx-coroutines-core` sits under almost any Kotlin
 * dependency. Reported: the Dependencies screen showed the edited version, and reopening the project showed the
 * old one again.
 *
 * Runs fully offline: the resolver's on-disk store IS its repository, so the fixture coordinates are seeded
 * there (plus negative-cache markers for the optional files) and no repository is ever consulted.
 *
 * Each test method takes a BLOCK body — an expression-body test returns whatever its last call does, and JUnit
 * Jupiter silently skips a test method with a non-Unit return type.
 */
class DeclaredDependencyPersistenceTest {

    private val group = "com.example.persist"
    private val libA = "$group:lib-a:1.0.0"
    private val sharedOld = "$group:shared:1.0.0"
    private val sharedNew = "$group:shared:2.0.0"

    @Test
    fun anAddedDeclarationSurvivesReopeningTheProject() {
        withTempDir("ide-dep-add") { dir ->
            val root = declareBoth(dir)
            IdeServices.open(root).use { ide ->
                assertEquals(listOf(libA, sharedOld), declaredLibraries(ide))
            }
        }
    }

    @Test
    fun aVersionChangeSurvivesReopeningTheProject() {
        withTempDir("ide-dep-version") { dir ->
            val root = declareBoth(dir)
            IdeServices.open(root).use { ide ->
                val result = runBlocking {
                    ide.dependencies.updateDependency("app", sharedOld, "2.0.0", "implementation", emptyList())
                }
                assertTrue(result.success, result.message)
                assertEquals(listOf(libA, sharedNew), declaredLibraries(ide), "the edit is in the open model")
            }
            IdeServices.open(root).use { ide ->
                assertEquals(listOf(libA, sharedNew), declaredLibraries(ide), "and was persisted")
            }
        }
    }

    @Test
    fun anExclusionChangeSurvivesReopeningTheProject() {
        withTempDir("ide-dep-exclusions") { dir ->
            val root = declareBoth(dir)
            IdeServices.open(root).use { ide ->
                val result = runBlocking {
                    ide.dependencies.setExclusions("app", libA, listOf("$group:shared"))
                }
                assertTrue(result.success, result.message)
            }
            IdeServices.open(root).use { ide ->
                assertEquals(
                    listOf("$group:shared"),
                    exclusionsOf(ide, libA),
                    "the exclusion was persisted",
                )
            }
        }
    }

    /**
     * A project whose `app` module declares `lib-a` and then `shared` — in that order, so `lib-a`'s subtree
     * claims the `shared` artifact before the direct declaration of `shared` is reached.
     */
    private fun declareBoth(dir: Path): Path {
        val root = dir.resolve("proj")
        IdeServices.bootstrapJavaDemo(root).use { ide ->
            seedOfflineRepository(root)
            for (coordinate in listOf(libA, sharedOld)) {
                val result = runBlocking { ide.dependencies.addDependency("app", coordinate, "implementation") }
                assertTrue(result.success, "adding $coordinate: ${result.message}")
            }
            assertEquals(listOf(libA, sharedOld), declaredLibraries(ide), "both declared in the open model")
        }
        return root
    }

    /** The `app` module's declared library coordinates, in declaration order. */
    private fun declaredLibraries(ide: IdeServices): List<String> =
        appLibraries(ide).map { it.library.name }

    private fun exclusionsOf(ide: IdeServices, coordinate: String): List<String> =
        appLibraries(ide).first { it.library.name == coordinate }.exclusions.map { "${it.group}:${it.name}" }

    private fun appLibraries(ide: IdeServices): List<LibraryDependency> =
        ide.modules().first { it.name == "app" }.dependencies.filterIsInstance<LibraryDependency>()

    // ---- offline fixture repository -------------------------------------------------------------

    /** Lay the fixture coordinates into the resolver's on-disk store, which it reads before any repository. */
    private fun seedOfflineRepository(root: Path) {
        publish(root, "lib-a", "1.0.0", dependsOn = "shared" to "2.0.0")
        publish(root, "shared", "1.0.0")
        publish(root, "shared", "2.0.0")
    }

    private fun publish(root: Path, name: String, version: String, dependsOn: Pair<String, String>? = null) {
        val rel = "${group.replace('.', '/')}/$name/$version/$name-$version"
        write(root.resolve(CACHE).resolve("$rel.pom"), pom(name, version, dependsOn).toByteArray())
        write(root.resolve(CACHE).resolve("$rel.jar"), jar(name))
        // The optional files a real repository would 404 on. Recorded as known-missing (the value is the probe
        // timestamp) so the resolver skips them instead of reaching for the network.
        val now = System.currentTimeMillis().toString().toByteArray()
        write(root.resolve(MISSES).resolve("$rel.module.miss"), now)
        write(root.resolve(MISSES).resolve("$rel-sources.jar.miss"), now)
    }

    private fun pom(name: String, version: String, dependsOn: Pair<String, String>?): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><project>""")
        append("<groupId>$group</groupId><artifactId>$name</artifactId><version>$version</version>")
        dependsOn?.let { (depName, depVersion) ->
            append("<dependencies><dependency>")
            append("<groupId>$group</groupId><artifactId>$depName</artifactId><version>$depVersion</version>")
            append("</dependency></dependencies>")
        }
        append("</project>")
    }

    /** A jar with one entry — a real artifact has content, and an entry-less zip isn't readable everywhere. */
    private fun jar(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("${name.replace('-', '/')}/Marker.class"))
            zip.write(ByteArray(32))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun write(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
    }

    private companion object {
        const val CACHE = ".platform/caches/resolved-deps"
        const val MISSES = ".platform/caches/resolved-deps-misses"
    }
}
