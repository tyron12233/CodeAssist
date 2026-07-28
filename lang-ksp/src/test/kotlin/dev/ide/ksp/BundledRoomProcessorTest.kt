package dev.ide.ksp

import dev.ide.build.SourceGenRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The real in-app bundling (#5): Room's processor now ships as the `/processors/room.zip` resource. This runs
 * KSP with the processor classpath taken from [BundledKspProcessors] (the BUNDLE), not a resolved Gradle
 * classpath — proving the zip-of-jars extract + load actually produces a working `room-compiler`. Combined
 * with the thin runner ([BundledKspThin]) it exercises the exact production shape: bundled thin KSP + bundled
 * Room on our own compiler/AA.
 *
 * Gated on the runtime/marker classpath (the module still compiles against `room-runtime`, which is data).
 */
class BundledRoomProcessorTest {

    private fun classpathProp(name: String): List<Path> =
        (System.getProperty(name) ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }.map { File(it).toPath() }.filter { Files.exists(it) }

    @Test
    fun bundledRoomProcessorGeneratesOnOurCompiler() {
        assumeTrue(BundledKspProcessors.isBundled("room"), "/processors/room.zip not bundled — did roomProcessorZip run?")
        val runner = classpathProp("ksp.runner.classpath")
        val roomLibs = classpathProp("room.libs.classpath")
        assumeTrue(runner.isNotEmpty() && roomLibs.isNotEmpty(), "KSP runner / room-runtime classpaths not injected — skipping")

        // The bundle really extracted a room-compiler closure.
        val roomJars = BundledKspProcessors.jarsFor("room")
        assertTrue(roomJars.size > 5, "expected room-compiler's closure (several jars), got ${roomJars.map { it.fileName }}")
        assertTrue(roomJars.any { it.fileName.toString().startsWith("room-compiler-") }, "room-compiler jar missing from the bundle")

        val catalog = KspProcessorCatalog.bundled()   // sources processors from BundledKspProcessors

        val root = Files.createTempDirectory("ksp-bundled-room")
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
                classpath = roomLibs,      // room-runtime trips the catalog's Room probe
                outputDir = genRoot,
                sourceRoots = listOf(srcRoot),
            )
            val generator = KspSourceGenerator(
                runnerClasspath = { runner },
                processors = { req -> catalog.classpathFor(req.classpath) },
                processorOptions = { mapOf("room.generateKotlin" to "true") },
                jdkHome = File(System.getProperty("java.home")).toPath(),
            )

            assertTrue(generator.appliesTo(request), "generator should apply (bundled Room + room-runtime present)")
            val result = generator.generate(request)
            val emitted = Files.walk(genRoot).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
            assertTrue(
                result.success && emitted.any { it.fileName.toString() == "AppDatabase_Impl.kt" },
                "bundled Room did not generate AppDatabase_Impl.kt:\n${result.messages.joinToString("\n")}\n" +
                    emitted.joinToString("\n") { genRoot.relativize(it).toString() },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
