package dev.ide.ksp

import dev.ide.build.SourceGenRequest
import dev.ide.testkit.withTempDir
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

    /** A jar carrying only [entries] (zero-byte class entries) — enough to trip [classpathHasClass]. */
    private fun jarWith(dir: Path, name: String, vararg entries: String): Path {
        val jar = dir.resolve(name)
        java.util.zip.ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            entries.forEach { e -> zos.putNextEntry(java.util.zip.ZipEntry(e)); zos.closeEntry() }
        }
        return jar
    }

    /**
     * The AGP-faithful activation rule: a processor runs only when its runtime is a **directly-declared**
     * dependency — a runtime that merely arrives transitively (its marker on the classpath, but not declared)
     * must NOT activate the processor. This is the JetSnack fix: JetSnack pulls `room-runtime` transitively
     * (through Glance) but never declares Room, so the Room processor must not fire (and crash on ART's missing
     * SQLite native). Self-contained — the marker jar is synthesized here, no injected classpath needed.
     */
    @Test
    fun processorActivatesOnlyWhenItsRuntimeIsDirectlyDeclared() {
        val catalog = KspProcessorCatalog.blessed()
        withTempDir("ksp-gate-test") { root ->
            // A transitive room-runtime: its marker is on the classpath, but Room is not declared.
            val roomMarkerJar = jarWith(root, "room-runtime.jar", KspProcessorCatalog.ROOM_MARKER)
            val classpath = listOf(roomMarkerJar)

            // Marker-only probe still sees it (that path drives UI display, not activation).
            assertEquals(listOf("room"), catalog.applicable(classpath).map { it.id })

            // Declared-aware activation: nothing declared, or something else declared → Room does NOT run.
            assertTrue(
                catalog.applicable(classpath, declaredDependencies = emptyList()).isEmpty(),
                "a transitive-only room-runtime must not activate Room",
            )
            assertTrue(
                catalog.applicable(classpath, declaredDependencies = listOf("androidx.glance:glance-appwidget")).isEmpty(),
                "declaring an unrelated library must not activate Room",
            )

            // Room IS declared (with or without a version) → Room runs.
            assertEquals(
                listOf("room"),
                catalog.applicable(classpath, declaredDependencies = listOf("androidx.room:room-runtime")).map { it.id },
                "declaring room-runtime must activate Room",
            )
            assertEquals(
                listOf("room"),
                catalog.applicable(classpath, declaredDependencies = listOf("androidx.room:room-runtime:2.8.4")).map { it.id },
                "a versioned room-runtime coordinate must match by group:name",
            )

            // Declared but the marker isn't actually present (unresolved/offline) → don't run.
            assertTrue(
                catalog.applicable(emptyList(), declaredDependencies = listOf("androidx.room:room-runtime")).isEmpty(),
                "declared but absent from the classpath → not run",
            )
        }
    }

    /**
     * The bundled processor version is fixed (executed code ships with the app, never downloaded), so a project
     * pinning an OLDER runtime gets generated sources its own runtime cannot compile. Real case: Hilt/Dagger.
     * The bundled 2.60.1 processor emits `_Factory` classes importing `dagger.internal.Provider`, absent from a
     * pre-2.5x Dagger, so the module failed with "The import dagger.internal.Provider cannot be resolved" in
     * every generated file, pointing at generated code instead of the version skew behind it.
     */
    @Test
    fun aRuntimeTooOldForTheBundledProcessorIsReportedBeforeAnythingIsGenerated() {
        val catalog = KspProcessorCatalog.blessed()
        withTempDir("ksp-stale-runtime") { root ->
            val declared = listOf("com.google.dagger:hilt-android:2.48")

            // Hilt declared and its marker present, but the runtime predates `dagger.internal.Provider`.
            val old = listOf(jarWith(root, "dagger-2.48.jar", KspProcessorCatalog.HILT_MARKER, "dagger/internal/Factory.class"))
            assertEquals(
                listOf("hilt"), catalog.applicable(old, declared).map { it.id },
                "the processor is still RUN-eligible: the mismatch must not be hidden by skipping it",
            )
            val mismatches = catalog.runtimeMismatches(old, declared)
            assertEquals(listOf("hilt"), mismatches.map { it.processor.id })
            assertEquals(listOf("dagger/internal/Provider.class"), mismatches.single().missing)
            val message = mismatches.single().message
            assertTrue("dagger.internal.Provider" in message, "names the missing symbol: $message")
            assertTrue("com.google.dagger:hilt-android to 2.60.1" in message, "names the version to bump to: $message")

            // A runtime that DOES carry the class is accepted: the check is a class probe, not a version compare,
            // so any runtime new enough to work passes regardless of its version string.
            val current = listOf(
                jarWith(root, "dagger-current.jar", KspProcessorCatalog.HILT_MARKER, "dagger/internal/Provider.class")
            )
            assertTrue(
                catalog.runtimeMismatches(current, declared).isEmpty(),
                "a runtime carrying dagger.internal.Provider must not be flagged",
            )

            // Not declared → not RUN-eligible → no complaint about an unrelated library's runtime.
            assertTrue(
                catalog.runtimeMismatches(old, declaredDependencies = emptyList()).isEmpty(),
                "an inapplicable processor must never report a mismatch",
            )
        }
    }

    /** The preflight is what turns that mismatch into a failed `generateSources`, with no processor run. */
    @Test
    fun preflightProblemsFailGenerationWithoutRunningTheProcessor() {
        withTempDir("ksp-preflight") { root ->
            val genRoot = Files.createDirectories(root.resolve("build/generated/ksp"))
            val request = SourceGenRequest(
                moduleName = "data",
                kotlinSources = emptyList(),
                javaSources = emptyList(),
                classpath = emptyList(),
                outputDir = genRoot,
            )
            val generator = KspSourceGenerator(
                runnerClasspath = { listOf(jarWith(root, "runner.jar", "com/google/devtools/ksp/X.class")) },
                processors = { listOf(jarWith(root, "processor.jar", "p/P.class")) },
                preflight = { listOf("ksp: Hilt / Dagger: runtime too old") },
            )

            val result = generator.generate(request)

            assertFalse(result.success, "a blocking preflight problem must fail source generation")
            assertEquals(listOf("ksp: Hilt / Dagger: runtime too old"), result.messages)
            assertTrue(
                Files.walk(genRoot).use { s -> s.filter { Files.isRegularFile(it) }.toList() }.isEmpty(),
                "nothing may be generated when the preflight blocks the run",
            )
        }
    }

    @Test
    fun kspSourceGeneratorRunsTheCatalogSelectedProcessor() {
        val runner = classpathProp("ksp.runner.classpath")
        val roomJars = classpathProp("room.processor.classpath")
        val roomLibs = classpathProp("room.libs.classpath")
        assumeTrue(runner.isNotEmpty() && roomJars.isNotEmpty() && roomLibs.isNotEmpty(), "KSP/Room classpaths not injected — skipping")

        val catalog = KspProcessorCatalog.blessed(bundledJars = { id -> if (id == "room") roomJars else emptyList() })

        withTempDir("ksp-catalog-test") { root ->
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
        }
    }
}
