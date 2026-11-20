package dev.ide.ksp.spike

import com.google.devtools.ksp.processing.SymbolProcessorProvider
import dev.ide.build.SourceGenRequest
import dev.ide.ksp.BundledKspProcessors
import dev.ide.ksp.InterpretedKspProcessors
import dev.ide.ksp.KspProcessorCatalog
import dev.ide.ksp.KspSourceGenerator
import dev.ide.testkit.withTempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Feasibility spike: run a real KSP processor WITHOUT defining its classes. The processor jars (the codegen
 * and its closure: kotlinpoet, guava, ...) are executed by the bytecode VM, while KSP itself (the runner, the
 * Analysis API, and the `symbol-processing-api` types) stays native. The VM hands KSP a peer that implements
 * [SymbolProcessorProvider]; everything behind it is interpreted.
 *
 * It answers two questions before any product work: does the boundary hold for a real processor, and what
 * does interpreting it cost next to loading it. The same module is generated both ways and the outputs must
 * match byte for byte. Timings are printed (`KSP-INTERP`), not asserted.
 */
class InterpretedProcessorSpikeTest {

    private fun classpathProp(name: String): List<Path> =
        (System.getProperty(name) ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }.map { File(it).toPath() }.filter { Files.exists(it) }

    private fun generated(genRoot: Path): Map<String, String> =
        Files.walk(genRoot).use { s ->
            s.filter { Files.isRegularFile(it) }.toList()
                .associate { genRoot.relativize(it).toString() to Files.readString(it) }
        }

    @Test
    fun moshiRunsInterpretedAndMatchesTheLoadedRun() {
        compare(
            id = "moshi",
            libsProperty = "moshi.libs.classpath",
            expectedFile = "PersonJsonAdapter.kt",
            source = """
                package demo
                import com.squareup.moshi.Json
                import com.squareup.moshi.JsonClass
                @JsonClass(generateAdapter = true)
                data class Person(val name: String, val age: Int, @Json(name = "nick_names") val nicknames: List<String> = emptyList())
                @JsonClass(generateAdapter = true)
                data class Team(val lead: Person, val members: Map<String, Person>, val size: Int?)
            """,
        )
    }

    @Test
    fun roomRunsInterpretedAndMatchesTheLoadedRun() {
        compare(
            id = "room",
            libsProperty = "room.libs.classpath",
            expectedFile = "AppDatabase_Impl.kt",
            options = mapOf("room.generateKotlin" to "true"),
            source = """
                package demo
                import androidx.room.*
                @Entity data class User(@PrimaryKey val id: Int, val name: String)
                @Dao interface UserDao {
                    @Query("SELECT * FROM User") suspend fun all(): List<User>
                    @Query("SELECT * FROM User WHERE id = :id") suspend fun byId(id: Int): User?
                    @Insert suspend fun insert(user: User)
                }
                @Database(entities = [User::class], version = 1, exportSchema = false)
                abstract class AppDatabase : RoomDatabase() { abstract fun userDao(): UserDao }
            """,
        )
    }

    /** Room 3 is a separate library from Room 2 (its own group, annotation package and processor). */
    @Test
    fun room3RunsInterpretedAndMatchesTheLoadedRun() {
        compare(
            id = "room3",
            libsProperty = "room3.libs.classpath",
            expectedFile = "AppDatabase_Impl.kt",
            options = mapOf("room.generateKotlin" to "true"),
            declared = listOf("androidx.room3:room3-runtime"),
            source = """
                package demo
                import androidx.room3.*
                @Entity data class User(@PrimaryKey val id: Int, val name: String)
                @Dao interface UserDao {
                    @Query("SELECT * FROM User") suspend fun all(): List<User>
                    @Insert suspend fun insert(user: User)
                }
                @Database(entities = [User::class], version = 1, exportSchema = false)
                abstract class AppDatabase : RoomDatabase() { abstract fun userDao(): UserDao }
            """,
        )
    }

    /** Hilt over a real project's shape: an `@InstallIn` module, constructor injection, qualifiers. Dagger is
     *  the heaviest of the bundled processors, and it generates Java. */
    @Test
    fun hiltRunsInterpretedAndMatchesTheLoadedRun() {
        compare(
            id = "hilt",
            libsProperty = "hilt.libs.classpath",
            expectedFile = "NetModule",
            options = KspProcessorCatalog.HILT_OPTIONS,
            source = """
                package demo

                import dagger.Module
                import dagger.Provides
                import dagger.hilt.InstallIn
                import dagger.hilt.components.SingletonComponent
                import javax.inject.Inject
                import javax.inject.Named

                @Module
                @InstallIn(SingletonComponent::class)
                object NetModule {
                    @Provides @Named("base") fun baseUrl(): String = "https://example.com"
                }

                class Repo @Inject constructor(@param:Named("base") val base: String) {
                    @set:Inject
                    var tag: String = ""
                }
            """,
        )
    }

    /** A plain Dagger component: the whole binding graph is resolved, validated and written out as Java, which
     *  is most of what Dagger is. */
    @Test
    fun daggerComponentRunsInterpretedAndMatchesTheLoadedRun() {
        compare(
            id = "hilt",
            libsProperty = "hilt.libs.classpath",
            expectedFile = "DaggerCarComponent",
            // These are Dagger modules, not Hilt ones, so Hilt must not ask them for an @InstallIn.
            options = KspProcessorCatalog.HILT_OPTIONS + ("dagger.hilt.disableModulesHaveInstallInCheck" to "true"),
            source = """
                package demo

                import dagger.Binds
                import dagger.Component
                import dagger.Module
                import dagger.Provides
                import javax.inject.Inject
                import javax.inject.Named
                import javax.inject.Provider
                import javax.inject.Singleton

                interface Engine
                class Electric @Inject constructor() : Engine
                @Singleton class Garage @Inject constructor(val engines: Provider<Engine>)
                class Car @Inject constructor(val engine: Engine, @param:Named("plate") val plate: String, val garage: Garage)

                @Module
                abstract class EngineModule {
                    @Binds abstract fun engine(electric: Electric): Engine
                }

                @Module
                class PlateModule(private val prefix: String) {
                    @Provides @Named("plate") fun plate(): String = prefix + "-1"
                }

                @Singleton
                @Component(modules = [EngineModule::class, PlateModule::class])
                interface CarComponent {
                    fun car(): Car
                    fun inject(target: Workshop)
                }

                class Workshop {
                    @Inject lateinit var car: Car
                }
            """,
        )
    }

    /** Glide's processor wants an `AppGlideModule` subclass. The Glide runtime is an Android library, so the
     *  one base class the processor looks for is declared beside the source under its real name. */
    @Test
    fun glideRunsInterpretedAndMatchesTheLoadedRun() {
        compare(
            id = "glide",
            libsProperty = "glide.libs.classpath",
            expectedFile = "GeneratedAppGlideModuleImpl",
            source = """
                package demo
                import com.bumptech.glide.annotation.GlideModule
                import com.bumptech.glide.module.AppGlideModule
                @GlideModule
                class MyGlideModule : AppGlideModule()
            """,
            extraSources = mapOf(
                "com/bumptech/glide/module/AppGlideModule.kt" to """
                    package com.bumptech.glide.module
                    abstract class AppGlideModule
                """,
            ),
        )
    }

    private fun compare(
        id: String,
        libsProperty: String,
        expectedFile: String,
        source: String,
        options: Map<String, String> = emptyMap(),
        declared: List<String> = emptyList(),
        extraSources: Map<String, String> = emptyMap(),
    ) {
        assumeTrue(BundledKspProcessors.isBundled(id), "/processors/$id.zip not bundled, skipping")
        val runner = classpathProp("ksp.runner.classpath")
        val libs = classpathProp(libsProperty)
        assumeTrue(runner.isNotEmpty() && libs.isNotEmpty(), "KSP runner / $id runtime classpaths not injected, skipping")
        val catalog = KspProcessorCatalog.bundled()
        val jdk = File(System.getProperty("java.home")).toPath()

        fun run(label: String, root: Path, factory: ((List<Path>) -> List<SymbolProcessorProvider>)?): Pair<Map<String, String>, Long> {
            val srcRoot = root.resolve("src/main/kotlin")
            Files.createDirectories(srcRoot)
            Files.writeString(srcRoot.resolve("Source.kt"), source.trimIndent())
            for ((path, text) in extraSources) {
                Files.createDirectories(srcRoot.resolve(path).parent)
                Files.writeString(srcRoot.resolve(path), text.trimIndent())
            }
            val genRoot = root.resolve("build/generated/ksp")
            Files.createDirectories(genRoot)
            val request = SourceGenRequest(
                moduleName = "app",
                kotlinSources = listOf(srcRoot.resolve("Source.kt")) + extraSources.keys.map { srcRoot.resolve(it) },
                javaSources = emptyList(),
                classpath = libs,
                outputDir = genRoot,
                sourceRoots = listOf(srcRoot),
                declaredDependencies = declared,
            )
            val generator = KspSourceGenerator(
                runnerClasspath = { runner },
                processors = { req ->
                    if (declared.isEmpty()) catalog.classpathFor(req.classpath) else catalog.classpathFor(req.classpath, req.declaredDependencies)
                },
                processorOptions = { options },
                jdkHome = jdk,
                providerFactory = factory,
            )
            val start = System.nanoTime()
            val result = generator.generate(request)
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue(result.success, "$label run failed:\n${result.messages.joinToString("\n")}")
            return generated(genRoot) to ms
        }

        withTempDir("ksp-interp-$id") { root ->
            // Warm KSP's own frontend first so neither measured run pays its one-time setup. The processor's
            // classes are loaded fresh in every run either way (a new loader, a new VM).
            run("warmup", root.resolve("warm"), null)
            val (loaded, loadedMs) = run("loaded", root.resolve("loaded"), null)

            val host = InterpretedKspProcessors()
            val (interpreted, interpretedMs) = host.use { run("interpreted", root.resolve("interp"), it::providers) }

            println("KSP-INTERP $id: loaded=${loadedMs}ms interpreted=${interpretedMs}ms steps=${host.vm?.steps} bridgedCalls=${host.vm?.bridgedCalls}")
            assertTrue(loaded.keys.any { expectedFile in it }, "loaded run generated: ${loaded.keys}")
            assertEquals(loaded.keys, interpreted.keys, "the interpreted run generated a different set of files")
            for ((file, text) in loaded) assertEquals(text, interpreted[file], "interpreted output differs for $file")
        }
    }
}
