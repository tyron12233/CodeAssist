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
 * Room's `room-compiler` pulls `org.xerial:sqlite-jdbc` (12.8 MB) for its compile-time SQL query verifier,
 * which loads a native SQLite library. sqlite-jdbc ships no build for Android/aarch64, and Room's
 * `DatabaseVerifier` calls the native loader from a class STATIC INITIALIZER with no fallback — so on ART the
 * load throws "No native library found for os.name=Linux-Android" as an `ExceptionInInitializerError`, which
 * aborts class-loading and crashes the whole KSP run before Room's own graceful "verification unavailable"
 * path (in `create()`, which DOES catch) can run.
 *
 * The fix (lang-ksp/build.gradle.kts) replaces sqlite-jdbc in the bundled Room processor with a native-free
 * stub (`src/sqliteStub`): the verifier's static init then succeeds (initialize() is a no-op, the stub JDBC
 * self-registers), its connection attempt throws a caught `SQLException`, and Room falls into its
 * `CANNOT_CREATE_VERIFICATION_DATABASE` path — generating the `_Impl` code (identical either way) without
 * compile-time SQL verification. The stub has no native, so it behaves the same on desktop and on ART; this
 * desktop run over the SHIPPED bundle therefore faithfully models the on-device path.
 */
class RoomWithoutSqliteJdbcTest {

    private fun classpathProp(name: String): List<Path> =
        (System.getProperty(name) ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }.map { File(it).toPath() }.filter { Files.exists(it) }

    @Test
    fun bundledRoomShipsSqliteStubAndGeneratesWithoutVerification() {
        assumeTrue(BundledKspProcessors.isBundled("room"), "/processors/room.zip not bundled — did roomProcessorZip run?")
        val runner = classpathProp("ksp.runner.classpath")
        val roomLibs = classpathProp("room.libs.classpath")
        assumeTrue(runner.isNotEmpty() && roomLibs.isNotEmpty(), "KSP runner / room-runtime classpaths not injected — skipping")

        // The build swap: the bundle ships the tiny native-free stub, NOT the 12.8 MB native sqlite-jdbc.
        val roomJars = BundledKspProcessors.jarsFor("room")
        assertTrue(
            roomJars.none { it.fileName.toString().let { n -> n.startsWith("sqlite-jdbc-") && !n.contains("stub") } },
            "the real native sqlite-jdbc must be excluded from the Room bundle: ${roomJars.map { it.fileName }}",
        )
        assertTrue(
            roomJars.any { it.fileName.toString().contains("sqlite-jdbc-stub") },
            "the native-free sqlite-jdbc stub must be bundled instead: ${roomJars.map { it.fileName }}",
        )

        val catalog = KspProcessorCatalog.bundled()
        val logged = mutableListOf<String>()
        withTempDir("ksp-room-stub") { root ->
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
                jdkHome = File(System.getProperty("java.home")).toPath(),
                log = { logged += it },
            )
            val result = generator.generate(request)
            val emitted = Files.walk(genRoot).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
            // With the stub, the verifier can't open a connection, so Room skips query verification and still
            // generates the _Impl — exactly the on-device outcome (build proceeds instead of crashing).
            assertTrue(
                result.success && emitted.any { it.fileName.toString() == "AppDatabase_Impl.kt" },
                "Room must generate AppDatabase_Impl.kt with the sqlite-jdbc stub (verification skipped):\n" +
                    logged.joinToString("\n") + "\n" +
                    emitted.joinToString("\n") { genRoot.relativize(it).toString() },
            )
            assertTrue(
                logged.none { it.contains("crashed", ignoreCase = true) || it.contains("No native library", ignoreCase = true) },
                "the run must not crash on a native library:\n" + logged.joinToString("\n"),
            )
        }
    }
}
