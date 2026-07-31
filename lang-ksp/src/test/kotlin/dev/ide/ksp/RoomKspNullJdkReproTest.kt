package dev.ide.ksp

import dev.ide.build.SourceGenRequest
import dev.ide.testkit.withTempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The ART condition: on device [KspSourceGenerator] runs with `jdkHome = null` (no modular JDK) — a path the
 * other KSP tests never exercise (they pass the host JDK). This proves KSP2 tolerates a null jdkHome and
 * resolves `java.*` from the module's compile classpath (android.jar) instead: the bundled Room processor
 * still generates its `_Impl` on our own compiler/AA. Guards against a regression that would make on-device
 * KSP crash.
 */
class RoomKspNullJdkReproTest {

    private fun classpathProp(name: String): List<Path> =
        (System.getProperty(name) ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }.map { File(it).toPath() }.filter { Files.exists(it) }

    @Test
    fun roomKspWithNullJdkHome() {
        assumeTrue(BundledKspProcessors.isBundled("room"), "/processors/room.zip not bundled")
        val runner = classpathProp("ksp.runner.classpath")
        val roomLibs = classpathProp("room.libs.classpath")
        assumeTrue(runner.isNotEmpty() && roomLibs.isNotEmpty(), "KSP runner / room-runtime classpaths not injected — skipping")

        val catalog = KspProcessorCatalog.bundled()
        withTempDir("ksp-nulljdk-room") { root ->
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
                classpath = roomLibs,
                outputDir = genRoot,
                sourceRoots = listOf(srcRoot),
            )
            val generator = KspSourceGenerator(
                runnerClasspath = { runner },
                processors = { req -> catalog.classpathFor(req.classpath) },
                processorOptions = { mapOf("room.generateKotlin" to "true") },
                jdkHome = null, // <-- the ART condition
                log = { println("[ksp-log] $it") },
            )
            val result = generator.generate(request)
            val emitted = Files.walk(genRoot).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
            assertTrue(
                result.success && emitted.any { it.fileName.toString() == "AppDatabase_Impl.kt" },
                "Room KSP with jdkHome=null (the on-device path) must still generate AppDatabase_Impl.kt:\n" +
                    result.messages.joinToString("\n"),
            )
        }
    }
}
