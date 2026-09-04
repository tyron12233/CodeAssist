package dev.ide.ksp

import dev.ide.build.SourceGenRequest
import dev.ide.testkit.withTempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Room 3 (`androidx.room3`) end to end on the bundled processor — the same proof [BundledRoomProcessorTest]
 * gives for Room 2, for what is a genuinely separate library.
 *
 * Room 3 is not a version bump: different artifact GROUP, different annotation package. A project on it
 * declares `androidx.room3:room3-runtime` and imports `androidx.room3.*`, so it trips neither
 * [KspProcessorCatalog.ROOM_MARKER] nor the `androidx.room:room-runtime` declared gate — the Room processor
 * never ran for it, silently, and the module failed much later on a missing `_Impl`. Reported from a real
 * project (2026-09-05) whose 6 DAOs / 6 entities / 1 database generated nothing at all.
 *
 * Also covers the `org.sqlite` stub for this bundle: `androidx.room3.verifier.DatabaseVerifier` loads the
 * same `SQLiteJDBCLoader` / `JDBC` / `SQLiteConnection` as Room 2's, so it needs the same native-free stand-in
 * (see `src/sqliteStub` and the note in lang-ksp/build.gradle.kts). If the stub didn't cover Room 3, this
 * generates nothing.
 */
class BundledRoom3ProcessorTest {

    private fun classpathProp(name: String): List<Path> =
        (System.getProperty(name) ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }.map { File(it).toPath() }.filter { Files.exists(it) }

    /** The catalogs' two Room entries must not overlap: each activates on its OWN group, never the other's. */
    @Test
    fun roomAndRoom3AreSeparateEntriesThatDoNotActivateEachOther() {
        val catalog = KspProcessorCatalog.blessed()
        withTempDir("ksp-room3-gate") { root ->
            fun jar(name: String, entry: String): Path {
                val p = root.resolve(name)
                java.util.zip.ZipOutputStream(Files.newOutputStream(p)).use { zos ->
                    zos.putNextEntry(java.util.zip.ZipEntry(entry)); zos.closeEntry()
                }
                return p
            }
            val room3 = listOf(jar("room3-runtime.jar", KspProcessorCatalog.ROOM3_MARKER))
            val room2 = listOf(jar("room-runtime.jar", KspProcessorCatalog.ROOM_MARKER))

            assertEquals(
                listOf("room3"),
                catalog.applicable(room3, listOf("androidx.room3:room3-runtime:3.0.1")).map { it.id },
                "a Room 3 project must activate Room 3",
            )
            assertEquals(
                listOf("room"),
                catalog.applicable(room2, listOf("androidx.room:room-runtime:2.8.4")).map { it.id },
                "a Room 2 project must still activate Room 2 only",
            )
            assertTrue(
                catalog.applicable(room3, listOf("androidx.room:room-runtime:2.8.4")).isEmpty(),
                "declaring Room 2 must not activate the Room 3 processor on a room3 classpath",
            )
        }
    }

    @Test
    fun bundledRoom3ProcessorGeneratesOnOurCompiler() {
        assumeTrue(BundledKspProcessors.isBundled("room3"), "/processors/room3.zip not bundled — did kspRoom3ProcessorZip run?")
        val runner = classpathProp("ksp.runner.classpath")
        val room3Libs = classpathProp("room3.libs.classpath")
        assumeTrue(runner.isNotEmpty() && room3Libs.isNotEmpty(), "KSP runner / room3-runtime classpaths not injected — skipping")

        val jars = BundledKspProcessors.jarsFor("room3")
        assertTrue(jars.any { it.fileName.toString().startsWith("room3-compiler-") }, "room3-compiler missing from the bundle: ${jars.map { it.fileName }}")
        assertTrue(jars.none { it.fileName.toString().startsWith("sqlite-jdbc-3") }, "the native sqlite-jdbc must not be bundled: ${jars.map { it.fileName }}")

        val catalog = KspProcessorCatalog.bundled()

        withTempDir("ksp-bundled-room3") { root ->
            val srcRoot = root.resolve("src/main/kotlin")
            Files.createDirectories(srcRoot)
            Files.writeString(
                srcRoot.resolve("Db.kt"),
                """
                package demo
                import androidx.room3.*
                @Entity data class User(@PrimaryKey val id: Int, val name: String)
                @Dao interface UserDao { @Query("SELECT * FROM User") suspend fun all(): List<User> }
                @Database(entities = [User::class], version = 1, exportSchema = false)
                abstract class AppDatabase : RoomDatabase() { abstract fun userDao(): UserDao }
                """.trimIndent(),
            )
            val genRoot = root.resolve("build/generated/ksp")
            Files.createDirectories(genRoot)

            val request = SourceGenRequest(
                moduleName = "app",
                kotlinSources = Files.walk(srcRoot).use { s -> s.filter { it.toString().endsWith(".kt") }.toList() },
                javaSources = emptyList(),
                classpath = room3Libs,
                outputDir = genRoot,
                sourceRoots = listOf(srcRoot),
                declaredDependencies = listOf("androidx.room3:room3-runtime"),
            )
            val generator = KspSourceGenerator(
                runnerClasspath = { runner },
                processors = { req -> catalog.classpathFor(req.classpath, req.declaredDependencies) },
                processorOptions = { mapOf("room.generateKotlin" to "true") },
                jdkHome = File(System.getProperty("java.home")).toPath(),
            )

            assertTrue(generator.appliesTo(request), "generator should apply (bundled Room 3 + room3-runtime declared)")
            val result = generator.generate(request)
            val emitted = Files.walk(genRoot).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
            assertTrue(
                result.success && emitted.any { it.fileName.toString() == "AppDatabase_Impl.kt" },
                "bundled Room 3 did not generate AppDatabase_Impl.kt:\n${result.messages.joinToString("\n")}\n" +
                    emitted.joinToString("\n") { genRoot.relativize(it).toString() },
            )
        }
    }
}
