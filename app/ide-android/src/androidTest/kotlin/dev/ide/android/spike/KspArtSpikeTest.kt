package dev.ide.android.spike

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPConfig
import com.google.devtools.ksp.processing.KSPJvmConfig
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import dev.ide.android.ArtKotlinPluginLoader
import dev.ide.android.DexPeerFactory
import dev.ide.build.SourceGenRequest
import dev.ide.ksp.BundledKspProcessors
import dev.ide.ksp.BundledKspThin
import dev.ide.ksp.InterpretedKspProcessors
import dev.ide.ksp.KspProcessorCatalog
import dev.ide.ksp.KspProcessorLoader
import dev.ide.ksp.KspSourceGenerator
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Discovery spike (not a regression test), the KSP2 counterpart of [KotlinCompilerArtSpikeTest]. Runs KSP2's
 * `KotlinSymbolProcessing` on a real device to find out what (if anything) breaks on ART — the empirical
 * input to any patch it needs.
 *
 * The crucial difference from the embeddable: KSP runs as the **thin runner** ([BundledKspThin], ~776 KB —
 * KSP's own classes only) on the IDE's OWN dexed compiler/Analysis API (`:kotlin-compiler-deps`, already in
 * the app and already ART-patched by the `dev.ide.kotlinc-art` ASM passes). So the `ksp.*`-relocated-platform
 * problem of the 78 MB embeddable does NOT apply here — KSP shares our patched platform. The expectation is
 * therefore that this needs FEW or NO new passes; any failure that does surface is a class in KSP's own code
 * (dexed from ksp-thin.jar) that ART can't run, and it is logged in full under [TAG].
 *
 * The thin runner is dexed + loaded through [ArtKotlinPluginLoader] (the same D8 + `DexClassLoader` path the
 * Kotlin compiler plugins use), parented to the app classloader so KSP's impl resolves our AA parent-first.
 * `KotlinSymbolProcessing` isn't a static app class (it lives in the loaded thin jar), so it is invoked
 * reflectively — exactly as the production `dev.ide.ksp.KspSourceGenerator` does it.
 *
 * A trivial in-process processor is used (no Room runtime needed): it resolves the source's classes and emits
 * one Kotlin file, exercising KSP's frontend (parse + symbol resolution) + `CodeGenerator` on ART. (Room on
 * ART is a follow-up: it additionally needs a bundled `room-runtime` for `RoomDatabase`.)
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.KspArtSpikeTest
 *     adb logcat -s KspArtSpike
 */
@RunWith(AndroidJUnit4::class)
class KspArtSpikeTest {

    @Test
    fun kspRunsOnArt() {
        // KSP's Analysis API stands up the IntelliJ application environment, like the compiler; keep it warm
        // and point IntelliJ-core at the extracted extension-point descriptors (same provisioning kotlinc needs).
        System.setProperty("kotlin.environment.keepalive", "true")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(ctx.filesDir, "ksp-art-spike").apply { deleteRecursively(); mkdirs() }

        val home = provisionKotlincHome(ctx, File(work, "kotlinc-home"))
        System.setProperty("kotlinc.art.home", home.absolutePath)
        Log.i(TAG, "kotlinc.art.home = $home")

        val androidJar = copyAsset(ctx, "android.jar", File(work, "android.jar"))
        val stdlibJar = copyAsset(ctx, "kotlin-stdlib.jar", File(work, "kotlin-stdlib.jar"))
        val thin = BundledKspThin.jar()
            ?: run { fail("thin-KSP jar (/ksp-thin.jar) not bundled in the app"); return }
        Log.i(TAG, "thin-KSP runner = $thin (${thin.toFile().length()} bytes)")

        val srcDir = File(work, "src").apply { mkdirs() }
        File(srcDir, "Model.kt").writeText("package demo\n\nclass Foo\ndata class Bar(val x: Int, val y: String)\n")
        val out = File(work, "out").apply { mkdirs() }

        // Built statically — KSPConfig/KSPJvmConfig ship in the app (via :lang-ksp's symbol-processing-common-deps).
        // jdkHome = null on ART: java.* is resolved from android.jar on the `libraries` classpath instead.
        val config: KSPConfig = KSPJvmConfig.Builder().apply {
            moduleName = "artspike"
            sourceRoots = listOf(srcDir)
            javaSourceRoots = emptyList()
            libraries = listOf(androidJar, stdlibJar)
            projectBaseDir = work
            outputBaseDir = out
            cachesDir = File(out, "caches")
            kotlinOutputDir = File(out, "kotlin")
            javaOutputDir = File(out, "java")
            classOutputDir = File(out, "classes")
            resourceOutputDir = File(out, "resources")
            languageVersion = "2.4"
            apiVersion = "2.4"
            jvmTarget = "17"
            jdkHome = null
        }.build()

        val logger = RecordingKspLogger()
        // Dex + load the thin runner on the app classloader (which carries our patched compiler/AA + ksp-api).
        val loader = ArtKotlinPluginLoader(androidJar.toPath(), File(work, "loader-cache").toPath(), minApi = 26)
        val exitName: String = try {
            val cl = loader.load(listOf(thin))
            val kspClass = cl.loadClass("com.google.devtools.ksp.impl.KotlinSymbolProcessing")
            val ctor = kspClass.getConstructor(KSPConfig::class.java, List::class.java, KSPLogger::class.java)
            val instance = ctor.newInstance(config, listOf(SpikeListingProvider()), logger)
            val exit = kspClass.getMethod("execute").invoke(instance)
            (exit as Enum<*>).name
        } catch (t: Throwable) {
            Log.e(TAG, "KSP2 (thin, on our AA) failed to RUN on ART — the discovery payload:", t)
            Log.e(TAG, "ksp messages so far:\n${logger.dump()}")
            fail(
                "KSP2 thin runner failed to run on ART: ${t.javaClass.name}: ${t.message}\n" +
                    "If the class in this trace is a `ksp.*`/`com.intellij.*`/`org.jetbrains.kotlin.*` platform " +
                    "class, add/extend a pass in dev.ide.build.kotlinc.ArtPatchPasses; if it is a " +
                    "`com.google.devtools.ksp.*` class, it is KSP's own code needing an ART fix. Re-run after.\n" +
                    t.stackTraceToString(),
            )
            return
        }

        Log.i(TAG, "KotlinSymbolProcessing exit=$exitName\n${logger.dump()}")
        val generated = File(out, "kotlin/com/gen/GeneratedClasses.kt")
        val produced = out.walkTopDown().filter { it.isFile }.map { it.relativeTo(out).path }.toList()
        Log.i(TAG, "generated tree: $produced")
        assertTrue(
            "KSP ran on ART but did not finish OK / generate. exit=$exitName generated=${generated.exists()}\n" +
                "messages:\n${logger.dump()}\ntree:\n${produced.joinToString("\n")}",
            exitName == "OK" && generated.exists(),
        )
    }

    /**
     * The REAL bundled processor on ART, through the production [KspSourceGenerator]: the app-bundled
     * `moshi-kotlin-codegen` (from `/processors/moshi.zip`) is dexed via [ArtKotlinPluginLoader], its
     * `SymbolProcessorProvider` is ServiceLoaded, and it generates a `*JsonAdapter` on our own compiler — the
     * full production path (bundle → dex → ServiceLoader → generate), not just the engine. Moshi's runtime is
     * pure-JVM (staged as the `moshi-libs` androidTest asset), so it needs no AAR provisioning. (Room on device
     * is the same path with a KMP-AAR runtime; the engine + Room-on-our-compiler are already proven, so Moshi
     * here covers "a real bundled processor runs on ART".)
     */
    @Test
    fun bundledMoshiRunsOnArt() {
        runProcessor(MOSHI, interpreted = false)
    }

    /**
     * The same processors with NONE of their classes defined on ART: the processor jars are executed by the
     * bytecode VM (`:jvm-interp`) through the production [InterpretedKspProcessors], and only the thin KSP
     * runner is dexed. KSP is handed a peer implementing `SymbolProcessorProvider`; the code generator,
     * kotlinpoet/javapoet, guava and Dagger's own model run interpreted behind it. This is the path a
     * processor the app does not bundle would take. Each run logs its wall time under [TAG]; the desktop twin
     * (`InterpretedProcessorSpikeTest`) checks the output against a loaded run byte for byte.
     */
    @Test fun moshiRunsInterpretedOnArt() = runProcessor(MOSHI, interpreted = true)
    @Test fun roomRunsInterpretedOnArt() = runProcessor(ROOM, interpreted = true)
    @Test fun room3RunsInterpretedOnArt() = runProcessor(ROOM3, interpreted = true)
    @Test fun hiltRunsInterpretedOnArt() = runProcessor(HILT, interpreted = true)
    @Test fun daggerComponentRunsInterpretedOnArt() = runProcessor(DAGGER, interpreted = true)
    @Test fun glideRunsInterpretedOnArt() = runProcessor(GLIDE, interpreted = true)

    /** One processor's fixture: the runtime it reads annotations from, a module to process, what must come out. */
    private class Case(
        val id: String,
        val libsAsset: String,
        val expected: String,
        val sources: Map<String, String>,
        val options: Map<String, String> = emptyMap(),
        val declared: List<String> = emptyList(),
    )

    private fun runProcessor(case: Case, interpreted: Boolean) {
        System.setProperty("kotlin.environment.keepalive", "true")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(ctx.filesDir, "ksp-${case.id}-${if (interpreted) "interp" else "dex"}-${case.expected.lowercase().take(12)}")
            .apply { deleteRecursively(); mkdirs() }
        val home = provisionKotlincHome(ctx, File(work, "kotlinc-home"))
        System.setProperty("kotlinc.art.home", home.absolutePath)

        val androidJar = copyAsset(ctx, "android.jar", File(work, "android.jar"))
        val stdlibJar = copyAsset(ctx, "kotlin-stdlib.jar", File(work, "kotlin-stdlib.jar"))
        // The runtime jars are androidTest assets → they live in the INSTRUMENTATION apk, not the app under
        // test, so read them from the instrumentation context (android.jar etc. above are the app's own).
        val libs = copyAssetDir(InstrumentationRegistry.getInstrumentation().context, case.libsAsset, File(work, "libs"))
        assertTrue("${case.libsAsset} asset empty", libs.isNotEmpty())
        assertTrue("/processors/${case.id}.zip not bundled in the app", BundledKspProcessors.isBundled(case.id))

        val srcDir = File(work, "src").apply { mkdirs() }
        val sources = case.sources.map { (path, text) ->
            File(srcDir, path).apply { parentFile?.mkdirs(); writeText(text.trimIndent()) }
        }
        val catalog = KspProcessorCatalog.bundled()
        // An interpreted case runs twice: the first run pays for dexing every small bridge class the processor
        // needs (a peer per interpreted class that extends a platform type, a stand-in per reflected class),
        // the second finds them cached, which is what every build after a project's first sees.
        repeat(if (interpreted) 2 else 1) { round ->
            val genRoot = File(work, "generated-$round").apply { mkdirs() }
            val host = InterpretedKspProcessors(DexPeerFactory(File(work, "peer-cache").apply { mkdirs() }.toPath()))
            val generator = KspSourceGenerator(
                runnerClasspath = { listOfNotNull(BundledKspThin.jar()) },
                // Catalog probe: the runtime on `classpath` selects the bundled processor.
                processors = { req ->
                    if (case.declared.isEmpty()) catalog.classpathFor(req.classpath) else catalog.classpathFor(req.classpath, req.declaredDependencies)
                },
                processorOptions = { case.options },
                // Dex + load on the app classloader (our compiler/AA), like production ART: the runner and the
                // processor for a loaded run, the runner alone for an interpreted one.
                loader = KspProcessorLoader { cp ->
                    ArtKotlinPluginLoader(androidJar.toPath(), File(work, "loader-cache").toPath(), minApi = 26).load(cp)
                },
                jdkHome = null, // ART: java.* resolves from android.jar on the classpath below
                providerFactory = if (interpreted) host::providers else null,
            )
            val request = SourceGenRequest(
                moduleName = "app",
                kotlinSources = sources.map { it.toPath() },
                javaSources = emptyList(),
                classpath = (listOf(androidJar, stdlibJar) + libs).map { it.toPath() },
                outputDir = genRoot.toPath(),
                sourceRoots = listOf(srcDir.toPath()),
                declaredDependencies = case.declared,
            )

            assertTrue("generator should apply (bundled ${case.id} + its runtime present)", generator.appliesTo(request))
            val started = System.nanoTime()
            val result = try {
                host.use { generator.generate(request) }
            } catch (t: Throwable) {
                Log.e(TAG, "bundled ${case.id} failed to RUN on ART:", t)
                fail("bundled ${case.id} failed on ART: ${t.javaClass.name}: ${t.message}\n${t.stackTraceToString()}")
                return
            }
            val emitted = genRoot.walkTopDown().filter { it.isFile }.map { it.relativeTo(genRoot).path }.toList()
            val cached = File(work, "peer-cache").listFiles()?.size ?: 0
            Log.i(TAG, "KSP-ON-ART ${case.id}/${case.expected} interpreted=$interpreted run=${round + 1} took=${(System.nanoTime() - started) / 1_000_000}ms steps=${host.vm?.steps} bridgedCalls=${host.vm?.bridgedCalls} bridgeClasses=$cached success=${result.success} emitted=${emitted.size}")
            if (!result.success) result.messages.forEach { Log.e(TAG, "  $it") }
            assertTrue(
                "bundled ${case.id} did not generate ${case.expected} on ART.\n${result.messages.joinToString("\n")}\n$emitted",
                result.success && emitted.any { case.expected in it },
            )
        }
    }

    private fun copyAsset(ctx: Context, assetName: String, dest: File): File {
        ctx.assets.open(assetName).use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest
    }

    /** Copy every file in the asset dir [assetDir] into [dest]; returns the copied files. */
    private fun copyAssetDir(ctx: Context, assetDir: String, dest: File): List<File> {
        dest.mkdirs()
        return (ctx.assets.list(assetDir) ?: emptyArray()).map { name ->
            copyAsset(ctx, "$assetDir/$name", File(dest, name))
        }
    }

    /** Extract the kotlinc-resources.zip asset (IntelliJ-core's extension descriptors) into [home]. */
    private fun provisionKotlincHome(ctx: Context, home: File): File {
        home.deleteRecursively(); home.mkdirs()
        val canonicalHome = home.canonicalPath + File.separator
        ctx.assets.open("kotlinc-resources.zip").use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(home, entry.name)
                    if (outFile.canonicalPath.startsWith(canonicalHome)) {
                        if (entry.isDirectory) outFile.mkdirs()
                        else { outFile.parentFile?.mkdirs(); outFile.outputStream().use { zis.copyTo(it) } }
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return home
    }

    /** A trivial run-once processor: lists the source's classes and emits one Kotlin file. */
    private class SpikeListingProcessor(
        private val codeGenerator: CodeGenerator,
        private val logger: KSPLogger,
    ) : SymbolProcessor {
        private var done = false
        override fun process(resolver: Resolver): List<KSAnnotated> {
            if (done) return emptyList()
            val names = resolver.getAllFiles()
                .flatMap { it.declarations }
                .filterIsInstance<KSClassDeclaration>()
                .map { it.simpleName.asString() }
                .toList()
            if (names.isEmpty()) return emptyList()
            done = true
            codeGenerator.createNewFile(Dependencies(false), "com.gen", "GeneratedClasses", "kt")
                .bufferedWriter().use { w ->
                    w.appendLine("package com.gen")
                    w.appendLine("object GeneratedClasses { val names = listOf(${names.joinToString(", ") { "\"$it\"" }}) }")
                }
            logger.warn("SpikeListingProcessor generated for $names")
            return emptyList()
        }
    }

    private class SpikeListingProvider : SymbolProcessorProvider {
        override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
            SpikeListingProcessor(environment.codeGenerator, environment.logger)
    }

    private class RecordingKspLogger : KSPLogger {
        private val lines = mutableListOf<String>()
        override fun logging(message: String, symbol: KSNode?) {}
        override fun info(message: String, symbol: KSNode?) { lines += "INFO: $message" }
        override fun warn(message: String, symbol: KSNode?) { lines += "WARN: $message" }
        override fun error(message: String, symbol: KSNode?) { lines += "ERR:  $message" }
        override fun exception(e: Throwable) { lines += "EXC:  ${e.stackTraceToString()}" }
        fun dump(): String = if (lines.isEmpty()) "(no messages)" else lines.joinToString("\n")
    }

    private companion object {
        const val TAG = "KspArtSpike"

        val MOSHI = Case(
            "moshi", "moshi-libs", "PersonJsonAdapter.kt",
            mapOf("Person.kt" to """
                package demo
                import com.squareup.moshi.JsonClass
                @JsonClass(generateAdapter = true)
                data class Person(val name: String, val age: Int)
            """),
        )
        val ROOM = Case(
            "room", "ksp-libs/room", "AppDatabase_Impl.kt",
            mapOf("Db.kt" to """
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
            """),
            options = mapOf("room.generateKotlin" to "true"),
        )
        val ROOM3 = Case(
            "room3", "ksp-libs/room3", "AppDatabase_Impl.kt",
            mapOf("Db.kt" to """
                package demo
                import androidx.room3.*
                @Entity data class User(@PrimaryKey val id: Int, val name: String)
                @Dao interface UserDao {
                    @Query("SELECT * FROM User") suspend fun all(): List<User>
                    @Insert suspend fun insert(user: User)
                }
                @Database(entities = [User::class], version = 1, exportSchema = false)
                abstract class AppDatabase : RoomDatabase() { abstract fun userDao(): UserDao }
            """),
            options = mapOf("room.generateKotlin" to "true"),
            declared = listOf("androidx.room3:room3-runtime"),
        )
        val HILT = Case(
            "hilt", "ksp-libs/hilt", "NetModule",
            mapOf("NetModule.kt" to """
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
            """),
            options = KspProcessorCatalog.HILT_OPTIONS,
        )
        val DAGGER = Case(
            "hilt", "ksp-libs/hilt", "DaggerCarComponent",
            mapOf("Car.kt" to """
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
            """),
            options = KspProcessorCatalog.HILT_OPTIONS + ("dagger.hilt.disableModulesHaveInstallInCheck" to "true"),
        )
        val GLIDE = Case(
            "glide", "ksp-libs/glide", "GeneratedAppGlideModuleImpl",
            mapOf(
                "MyGlideModule.kt" to """
                    package demo
                    import com.bumptech.glide.annotation.GlideModule
                    import com.bumptech.glide.module.AppGlideModule
                    @GlideModule
                    class MyGlideModule : AppGlideModule()
                """,
                // The Glide runtime is an Android library; the one base class the processor looks for is
                // declared beside the source under its real name.
                "com/bumptech/glide/module/AppGlideModule.kt" to """
                    package com.bumptech.glide.module
                    abstract class AppGlideModule
                """,
            ),
        )
    }
}
