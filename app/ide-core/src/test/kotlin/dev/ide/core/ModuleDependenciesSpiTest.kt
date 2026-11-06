package dev.ide.core

import dev.ide.model.LibraryDependency
import dev.ide.model.MODULE_DEPENDENCIES
import dev.ide.model.ModuleDependencies
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `platform.moduleDependencies`, the plugin-facing view of the Dependencies screen. Fully offline: the
 * resolver's on-disk store is its repository, so the fixture coordinates are seeded there (as in
 * [DeclaredDependencyPersistenceTest]) and no repository is consulted.
 *
 * Fixture: `lib-a:1.0.0` depends on `shared:2.0.0`, and `shared:1.0.0` is declared directly too, so the
 * graph has a version conflict to report.
 */
class ModuleDependenciesSpiTest {

    private val group = "com.example.spi"
    private val libA = "$group:lib-a:1.0.0"
    private val sharedOld = "$group:shared:1.0.0"

    @Test
    fun addingThroughTheSpiDeclaresAndResolvesLikeTheScreen() {
        withTempDir("deps-spi") { dir ->
            IdeServices.bootstrapJavaDemo(dir.resolve("proj")).use { ide ->
                seed(dir.resolve("proj"))
                val deps = ide.store.workspace.service(MODULE_DEPENDENCIES)
                val app = ide.modules().first { it.name == "app" }

                for (coordinate in listOf(libA, sharedOld)) {
                    val change = runBlocking { deps.add(app, coordinate) }
                    assertTrue(change.added, "adding $coordinate: ${change.message}")
                }
                assertEquals(
                    listOf(libA, sharedOld),
                    ide.modules().first { it.name == "app" }.dependencies.filterIsInstance<LibraryDependency>().map { it.library.name },
                    "declared in the model, the same as an add from the screen",
                )

                val resolved = assertNotNull(runBlocking { deps.resolved(app) })
                assertEquals(setOf("$group:lib-a", "$group:shared"), resolved.declared.map { "${it.group}:${it.name}" }.toSet())
                assertTrue(resolved.declared.all { it.declared && it.scope != null })
                val a = resolved.artifacts.first { it.name == "lib-a" }
                assertTrue(a.dependsOn.any { it.startsWith("$group:shared:") }, "the edge to what it pulls in")

                val conflict = resolved.conflicts.single { it.artifact == "$group:shared" }
                assertEquals(setOf("1.0.0", "2.0.0"), conflict.requested.toSet())
                assertTrue(conflict.chosen in conflict.requested)
                assertTrue(resolved.unresolved.isEmpty(), "everything came from the seeded store")
            }
        }
    }

    @Test
    fun repositoriesAreListedAndOnlyARealNewOneIsAdded() {
        withTempDir("deps-spi-repos") { dir ->
            IdeServices.bootstrapJavaDemo(dir.resolve("proj")).use { ide ->
                val deps: ModuleDependencies = ide.store.workspace.service(MODULE_DEPENDENCIES)
                val builtIn = deps.repositories()
                assertTrue(builtIn.isNotEmpty() && builtIn.all { it.builtIn }, "the shipped ones, locked")

                assertFalse(deps.addRepository("Bad", "ftp://example.com/maven"), "only http(s)")
                assertFalse(deps.addRepository("Again", builtIn.first().url), "already listed")
                assertTrue(deps.addRepository("JitPack", "https://jitpack.io"))
                val added = deps.repositories().single { !it.builtIn }
                assertEquals("JitPack" to "https://jitpack.io", added.name to added.url)
            }
        }
    }

    // ---- offline fixture repository (see DeclaredDependencyPersistenceTest) --------------------------

    private fun seed(root: Path) {
        publish(root, "lib-a", "1.0.0", dependsOn = "shared" to "2.0.0")
        publish(root, "shared", "1.0.0")
        publish(root, "shared", "2.0.0")
    }

    private fun publish(root: Path, name: String, version: String, dependsOn: Pair<String, String>? = null) {
        val rel = "${group.replace('.', '/')}/$name/$version/$name-$version"
        write(root.resolve(CACHE).resolve("$rel.pom"), pom(name, version, dependsOn).toByteArray())
        write(root.resolve(CACHE).resolve("$rel.jar"), jar(name))
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
