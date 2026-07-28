package dev.ide.ksp

import dev.ide.build.SourceGenRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The production generator (task 3): drives [KspSourceGenerator] — the `SourceGenerator` the build's
 * `generateSources` task runs — over a real Room `@Entity`/`@Dao`/`@Database` module, loading the KSP2 runner
 * + room-compiler through [KspProcessorLoader] and invoking `KotlinSymbolProcessing` REFLECTIVELY (the runner
 * is not a static dependency), then asserts Room's `AppDatabase_Impl` lands under the GENERATED root.
 *
 * This exercises the exact production path (reflective runner load + [SourceGenRequest] → `KSPJvmConfig`),
 * whereas [dev.ide.ksp.spike.RoomKspSpikeTest] proved the raw KSP2 API. The generic
 * `generateSources → compile` graph wiring is already proven by `:jvm-build`'s `SourceGenerationTest`
 * (generator-agnostic), so a green run here means KSP slots into the build with no further seam work.
 *
 * Gated on the Gradle-injected classpaths; skips when the KSP/Room artifacts aren't resolvable.
 */
class KspSourceGeneratorTest {

    private fun classpathProp(name: String): List<Path> =
        (System.getProperty(name) ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }.map { File(it).toPath() }.filter { Files.exists(it) }

    @Test
    fun kspSourceGeneratorRunsRoomAndEmitsIntoGeneratedRoot() {
        val runner = classpathProp("ksp.runner.classpath")
        val room = classpathProp("room.processor.classpath")
        val roomLibs = classpathProp("room.libs.classpath")
        assumeTrue(runner.isNotEmpty(), "ksp.runner.classpath not injected — skipping")
        assumeTrue(room.isNotEmpty(), "room.processor.classpath not injected — skipping")
        assumeTrue(roomLibs.isNotEmpty(), "room.libs.classpath not injected — skipping")

        val root = Files.createTempDirectory("ksp-gen-test")
        try {
            val srcRoot = root.resolve("src/main/kotlin")
            Files.createDirectories(srcRoot)
            Files.writeString(
                srcRoot.resolve("Db.kt"),
                """
                package demo

                import androidx.room.Dao
                import androidx.room.Database
                import androidx.room.Entity
                import androidx.room.Insert
                import androidx.room.PrimaryKey
                import androidx.room.Query
                import androidx.room.RoomDatabase

                @Entity
                data class User(@PrimaryKey val id: Int, val name: String)

                @Dao
                interface UserDao {
                    @Query("SELECT * FROM User")
                    suspend fun all(): List<User>

                    @Insert
                    suspend fun insert(user: User)
                }

                @Database(entities = [User::class], version = 1, exportSchema = false)
                abstract class AppDatabase : RoomDatabase() {
                    abstract fun userDao(): UserDao
                }
                """.trimIndent(),
            )

            val generatedRoot = root.resolve("build/generated/ksp")
            Files.createDirectories(generatedRoot)

            val kotlinFiles = Files.walk(srcRoot).use { s -> s.filter { it.toString().endsWith(".kt") }.toList() }
            val request = SourceGenRequest(
                moduleName = "app",
                kotlinSources = kotlinFiles,
                javaSources = emptyList(),
                classpath = roomLibs,
                outputDir = generatedRoot,
                sourceRoots = listOf(srcRoot),
            )

            val messages = mutableListOf<String>()
            val generator = KspSourceGenerator(
                runnerClasspath = { runner },
                processors = { room },
                processorOptions = { mapOf("room.generateKotlin" to "true") },
                jdkHome = File(System.getProperty("java.home")).toPath(),
                log = { messages += it },
            )

            assertTrue(generator.appliesTo(request), "KspSourceGenerator should apply (runner + processors present)")
            val result = generator.generate(request)

            val emitted = Files.walk(generatedRoot).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
            assertTrue(
                result.success,
                "KSP generation failed:\n${(result.messages + messages).joinToString("\n")}\n" +
                    "emitted:\n${emitted.joinToString("\n") { generatedRoot.relativize(it).toString() }}",
            )
            assertTrue(
                emitted.any { it.fileName.toString() == "AppDatabase_Impl.kt" },
                "Room did not emit AppDatabase_Impl.kt into the generated root. Emitted:\n" +
                    emitted.joinToString("\n") { generatedRoot.relativize(it).toString() },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
