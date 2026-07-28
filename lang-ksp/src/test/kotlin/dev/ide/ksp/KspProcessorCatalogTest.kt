package dev.ide.ksp

import dev.ide.build.SourceGenRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The probe-based activation ([KspProcessorCatalog]): a module opts into Room by putting `room-runtime` on
 * its classpath (the marker), and the catalog then contributes Room's bundled processor — the same pattern
 * the Compose/serialization/Parcelize plugins use. Also drives it end to end through [KspSourceGenerator]
 * (catalog resolver + our thin runner over Room), proving "add the runtime → KSP runs the processor".
 */
class KspProcessorCatalogTest {

    private fun classpathProp(name: String): List<Path> =
        (System.getProperty(name) ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }.map { File(it).toPath() }.filter { Files.exists(it) }

    @Test
    fun probeFiresOnlyWhenTheRuntimeMarkerIsPresent() {
        val roomLibs = classpathProp("room.libs.classpath")
        assumeTrue(roomLibs.isNotEmpty(), "room.libs.classpath not injected — skipping")
        val roomJars = classpathProp("room.processor.classpath")

        val catalog = KspProcessorCatalog.blessed(bundledJars = { id -> if (id == "room") roomJars else emptyList() })

        // room-runtime on the classpath → Room applies and contributes its processor jars.
        assertEquals(listOf("room"), catalog.applicable(roomLibs).map { it.id }, "Room should apply when room-runtime is present")
        assertTrue(catalog.classpathFor(roomLibs).isNotEmpty(), "Room's bundled processor jars should be contributed")

        // No Room runtime on the classpath → nothing applies.
        assertFalse(
            KspProcessorCatalog.classpathHasClass(emptyList(), KspProcessorCatalog.ROOM_MARKER),
            "an empty classpath must not trip the Room probe",
        )
        assertTrue(catalog.applicable(emptyList()).isEmpty(), "no processor should apply on an empty classpath")
    }

    @Test
    fun kspSourceGeneratorRunsTheCatalogSelectedProcessor() {
        val runner = classpathProp("ksp.runner.classpath")
        val roomJars = classpathProp("room.processor.classpath")
        val roomLibs = classpathProp("room.libs.classpath")
        assumeTrue(runner.isNotEmpty() && roomJars.isNotEmpty() && roomLibs.isNotEmpty(), "KSP/Room classpaths not injected — skipping")

        val catalog = KspProcessorCatalog.blessed(bundledJars = { id -> if (id == "room") roomJars else emptyList() })

        val root = Files.createTempDirectory("ksp-catalog-test")
        try {
            val srcRoot = root.resolve("src/main/kotlin")
            Files.createDirectories(srcRoot)
            Files.writeString(
                srcRoot.resolve("Db.kt"),
                """
                package demo
                import androidx.room.*
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
                classpath = roomLibs,               // room-runtime here trips the Room probe
                outputDir = genRoot,
                sourceRoots = listOf(srcRoot),
            )

            val generator = KspSourceGenerator(
                runnerClasspath = { runner },
                processors = { req -> catalog.classpathFor(req.classpath) },   // <-- catalog selects Room
                processorOptions = { mapOf("room.generateKotlin" to "true") },
                jdkHome = File(System.getProperty("java.home")).toPath(),
            )

            assertTrue(generator.appliesTo(request), "generator should apply once room-runtime trips the catalog probe")
            val result = generator.generate(request)
            val emitted = Files.walk(genRoot).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
            assertTrue(
                result.success && emitted.any { it.fileName.toString() == "AppDatabase_Impl.kt" },
                "catalog-selected Room did not generate AppDatabase_Impl.kt:\n${result.messages.joinToString("\n")}\n" +
                    emitted.joinToString("\n") { genRoot.relativize(it).toString() },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
