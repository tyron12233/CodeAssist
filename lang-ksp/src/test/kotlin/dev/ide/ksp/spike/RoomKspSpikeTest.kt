package dev.ide.ksp.spike

import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import com.google.devtools.ksp.processing.KSPJvmConfig
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSNode
import dev.ide.testkit.withTempDir
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The real-ecosystem spike (task 2): run the actual `androidx.room:room-compiler` KSP processor over a small
 * `@Entity`/`@Dao`/`@Database` module and assert Room's `*_Impl` is generated. Unlike [KspEngineSpikeTest]
 * (which hands the provider in-process), this loads Room's `SymbolProcessorProvider` through a
 * `URLClassLoader` + `ServiceLoader` from an ISOLATED processor classpath — exactly the path the production
 * `KspSourceGenerator` + `KotlinPluginLoader` take (desktop URLClassLoader / ART DexClassLoader).
 *
 * Gated on the Gradle-injected classpaths (`room.processor.classpath` / `room.libs.classpath`); a CI run
 * without the Room artifacts just skips.
 */
class RoomKspSpikeTest {

    private class RecordingLogger : KSPLogger {
        val messages = mutableListOf<String>()
        override fun logging(message: String, symbol: KSNode?) { messages += "LOG:  $message" }
        override fun info(message: String, symbol: KSNode?) { messages += "INFO: $message" }
        override fun warn(message: String, symbol: KSNode?) { messages += "WARN: $message" }
        override fun error(message: String, symbol: KSNode?) { messages += "ERR:  $message" }
        override fun exception(e: Throwable) { messages += "EXC:  ${e.stackTraceToString()}" }
    }

    private fun classpathProp(name: String): List<File> =
        (System.getProperty(name) ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }.map { File(it) }.filter { it.exists() }

    @Test
    fun runsRoomKspProcessorAndGeneratesDaoImpl() {
        val processorClasspath = classpathProp("room.processor.classpath")
        val roomLibs = classpathProp("room.libs.classpath")
        assumeTrue(processorClasspath.isNotEmpty(), "room.processor.classpath not injected — skipping Room spike")
        assumeTrue(roomLibs.isNotEmpty(), "room.libs.classpath not injected — skipping Room spike")

        withTempDir("ksp-room-spike") { dir ->
            val root = dir.toFile()
            val src = File(root, "src").apply { mkdirs() }
            File(src, "Db.kt").writeText(
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

                // suspend DAO functions: a standalone KSP run has no Android platform target, and Room
                // requires suspend DAOs on non-Android source sets. The production path runs with android.jar
                // on the libraries classpath (an Android module), where non-suspend DAOs are also allowed.
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

            // Load Room's provider through an isolated classloader parented to THIS test's loader (which
            // carries the KSP SPI base type `SymbolProcessorProvider`), so Room resolves it by delegation —
            // the same parent-delegation contract KotlinPluginLoader relies on.
            val processorLoader = URLClassLoader(
                processorClasspath.map { it.toURI().toURL() }.toTypedArray(),
                javaClass.classLoader,
            )
            val providers: List<SymbolProcessorProvider> =
                ServiceLoader.load(SymbolProcessorProvider::class.java, processorLoader).toList()
            assertTrue(
                providers.any { it.javaClass.name.contains("room", ignoreCase = true) },
                "Room's SymbolProcessorProvider was not found via ServiceLoader. Found: ${providers.map { it.javaClass.name }}",
            )

            val out = File(root, "out")
            val config = KSPJvmConfig.Builder().apply {
                moduleName = "roomspike"
                sourceRoots = listOf(src)
                javaSourceRoots = emptyList()
                libraries = roomLibs
                projectBaseDir = root
                outputBaseDir = out
                cachesDir = File(root, "caches")
                kotlinOutputDir = File(out, "kotlin")
                javaOutputDir = File(out, "java")
                classOutputDir = File(out, "classes")
                resourceOutputDir = File(out, "resources")
                languageVersion = "2.4"
                apiVersion = "2.4"
                jvmTarget = "17"
                jdkHome = File(System.getProperty("java.home"))
                // Emit Kotlin (Room defaults to Java); exportSchema=false above so no schema dir is required.
                processorOptions = mapOf("room.generateKotlin" to "true")
            }.build()

            val logger = RecordingLogger()
            val exit = KotlinSymbolProcessing(config, providers, logger).execute()

            val generated = out.walkTopDown().filter { it.isFile }.toList()
            assertEquals(
                KotlinSymbolProcessing.ExitCode.OK, exit,
                "Room KSP run failed.\nmessages:\n${logger.messages.joinToString("\n")}\n" +
                    "generated:\n${generated.joinToString("\n") { it.relativeTo(out).path }}",
            )
            assertTrue(
                generated.any { it.name == "AppDatabase_Impl.kt" || it.name == "AppDatabase_Impl.java" },
                "Room did not generate AppDatabase_Impl. Generated:\n" +
                    generated.joinToString("\n") { it.relativeTo(out).path },
            )
            assertTrue(
                generated.any { it.name.startsWith("UserDao_Impl") },
                "Room did not generate UserDao_Impl. Generated:\n" +
                    generated.joinToString("\n") { it.relativeTo(out).path },
            )
        }
    }
}
