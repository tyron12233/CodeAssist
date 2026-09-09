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
import kotlin.test.assertNotNull
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
    private fun jarWith(dir: Path, name: String, vararg entries: String): Path =
        jarOf(dir, name, entries.associateWith { ByteArray(0) })

    /** A jar whose entries carry real bytes — needed once a probe reads a class, not just its presence. */
    private fun jarOf(dir: Path, name: String, entries: Map<String, ByteArray>): Path {
        val jar = dir.resolve(name)
        java.util.zip.ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            entries.forEach { (path, bytes) ->
                zos.putNextEntry(java.util.zip.ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return jar
    }

    /**
     * A compiled annotation type declaring [elements] as `String` elements — a stand-in for one revision of a
     * runtime's annotation. Real bytes, because the member floor reads the class file itself.
     */
    private fun annotationClass(internalName: String, vararg elements: String): ByteArray {
        val cw = org.objectweb.asm.ClassWriter(0)
        cw.visit(
            org.objectweb.asm.Opcodes.V1_8,
            org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_INTERFACE or
                org.objectweb.asm.Opcodes.ACC_ABSTRACT or org.objectweb.asm.Opcodes.ACC_ANNOTATION,
            internalName,
            null,
            "java/lang/Object",
            arrayOf("java/lang/annotation/Annotation"),
        )
        elements.forEach { e ->
            cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_ABSTRACT,
                e, "()Ljava/lang/String;", null, null,
            ).visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** `@AggregatedRoot` as Hilt declares it, at the revision named by [elements]. */
    private fun aggregatedRoot(vararg elements: String): Map<String, ByteArray> = mapOf(
        AGGREGATED_ROOT to annotationClass("dagger/hilt/internal/aggregatedroot/AggregatedRoot", *elements),
    )

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
                jarOf(
                    root, "dagger-current.jar",
                    mapOf(
                        KspProcessorCatalog.HILT_MARKER to ByteArray(0),
                        "dagger/internal/Provider.class" to ByteArray(0),
                    ) + aggregatedRoot(*AGGREGATED_ROOT_2_60),
                )
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

    /**
     * The same skew one level down, where a class probe is blind: the runtime carries the annotation, just an
     * older revision of it.
     *
     * Reported against Hilt 2.51.1 (a real project, 2026-09-04). Hilt 2.57 added `rootComponentPackage` and
     * `rootComponentSimpleNames` to `@AggregatedRoot`; the bundled 2.60.1 processor writes them into
     * `dagger.hilt.internal.aggregatedroot.codegen._<Root>` and READS THAT BACK in a later round, where
     * XProcessing looks each written argument's name up among the annotation's declared methods. On a pre-2.57
     * `AggregatedRoot` there is no such method, and the build died as
     * `[Hilt] Collection contains no element matching the predicate.` — no library, no version, no annotation
     * named. `dagger.internal.Provider` (the class floor) is present from 2.51 on, so it cannot catch this.
     */
    @Test
    fun aRuntimeWhoseAnnotationLacksAnElementTheProcessorWritesIsReportedToo() {
        val catalog = KspProcessorCatalog.blessed()
        withTempDir("ksp-stale-member") { root ->
            val declared = listOf("com.google.dagger:hilt-android:2.51.1")
            val everythingButTheMember = mapOf(
                KspProcessorCatalog.HILT_MARKER to ByteArray(0),
                "dagger/internal/Provider.class" to ByteArray(0),
            )

            // Hilt 2.51.1: every required CLASS present, `AggregatedRoot` present — one revision too old.
            val old = listOf(
                jarOf(root, "hilt-2.51.1.jar", everythingButTheMember + aggregatedRoot(*AGGREGATED_ROOT_2_51))
            )
            val mismatch = assertNotNull(
                catalog.runtimeMismatches(old, declared).singleOrNull(),
                "a runtime whose AggregatedRoot predates 2.57 must be reported",
            )
            assertTrue(mismatch.missing.isEmpty(), "no CLASS is missing — this is a member-level skew")
            assertEquals(listOf("rootComponentPackage"), mismatch.missingMembers.map { it.memberName })
            val message = mismatch.message
            assertTrue(
                "AggregatedRoot.rootComponentPackage()" in message,
                "the message names the annotation AND the element, which the raw failure never does: $message",
            )
            assertTrue("com.google.dagger:hilt-android to 2.60.1" in message, "names the fix: $message")

            // 2.57+ declares it → nothing to report, whatever the version string says.
            val current = listOf(
                jarOf(root, "hilt-current.jar", everythingButTheMember + aggregatedRoot(*AGGREGATED_ROOT_2_60))
            )
            assertTrue(
                catalog.runtimeMismatches(current, declared).isEmpty(),
                "a runtime declaring the element must not be flagged",
            )

            // The annotation missing outright is a mismatch as well: the generated root won't compile either
            // way, and the same instruction fixes it.
            val without = listOf(jarOf(root, "hilt-no-annotation.jar", everythingButTheMember))
            assertEquals(
                listOf("rootComponentPackage"),
                catalog.runtimeMismatches(without, declared).single().missingMembers.map { it.memberName },
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
                preflight = { KspProcessorCatalog.Preflight(blocking = listOf("ksp: runtime too old")) },
            )

            val result = generator.generate(request)

            assertFalse(result.success, "a blocking preflight problem must fail source generation")
            assertEquals(listOf("ksp: runtime too old"), result.messages)
            assertTrue(
                Files.walk(genRoot).use { s -> s.filter { Files.isRegularFile(it) }.toList() }.isEmpty(),
                "nothing may be generated when the preflight blocks the run",
            )
        }
    }

    /**
     * Accepting a mismatch (the editor banner's "Build anyway") has to UNBLOCK generation while still saying so
     * on every build. If it silenced the problem instead, the user would be left with unexplained compile errors
     * in generated code; if it kept blocking, accepting would mean nothing.
     */
    @Test
    fun anAcceptedMismatchBecomesAWarningInsteadOfBlocking() {
        val catalog = KspProcessorCatalog.blessed()
        withTempDir("ksp-accepted") { root ->
            val declared = listOf("com.google.dagger:hilt-android:2.48")
            val old = listOf(jarWith(root, "dagger-2.48.jar", KspProcessorCatalog.HILT_MARKER))

            val blocking = catalog.preflight(old, declared)
            assertEquals(1, blocking.blocking.size, "unaccepted: blocks generation")
            assertTrue(blocking.warnings.isEmpty())

            val accepted = catalog.preflight(old, declared, accepted = setOf("hilt"))
            assertTrue(accepted.blocking.isEmpty(), "accepted: generation is no longer blocked")
            assertEquals(1, accepted.warnings.size, "accepted: still reported once per build")
            assertTrue("building anyway" in accepted.warnings.single(), accepted.warnings.single())

            // Accepting an UNRELATED processor changes nothing about this one.
            assertEquals(1, catalog.preflight(old, declared, accepted = setOf("room")).blocking.size)
        }
    }

    /** A generator run past an accepted warning still carries it in the result, so the console shows it. */
    @Test
    fun acceptedWarningsAreReportedOnASuccessfulRun() {
        withTempDir("ksp-accepted-run") { root ->
            val genRoot = Files.createDirectories(root.resolve("build/generated/ksp"))
            val request = SourceGenRequest(
                moduleName = "data",
                kotlinSources = emptyList(),
                javaSources = emptyList(),
                classpath = emptyList(),
                outputDir = genRoot,
                acceptedWarnings = setOf("hilt"),
            )
            val logged = mutableListOf<String>()
            val generator = KspSourceGenerator(
                runnerClasspath = { listOf(jarWith(root, "runner.jar", "com/google/devtools/ksp/X.class")) },
                processors = { listOf(jarWith(root, "processor.jar", "p/P.class")) },
                // No provider on the fake processor jar, so the run stops right after the preflight; what
                // matters here is that the warning survived into the messages either way.
                preflight = { KspProcessorCatalog.Preflight(warnings = listOf("ksp: building anyway")) },
                log = { logged += it },
            )

            val result = generator.generate(request)

            assertTrue("ksp: building anyway" in logged, "an accepted warning is logged: $logged")
            assertTrue(result.messages.any { "building anyway" in it }, "and carried in the result: ${result.messages}")
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

    private companion object {
        const val AGGREGATED_ROOT = "dagger/hilt/internal/aggregatedroot/AggregatedRoot.class"

        /** `@AggregatedRoot`'s elements up to Hilt 2.56.x. */
        val AGGREGATED_ROOT_2_51 = arrayOf(
            "root", "rootPackage", "rootSimpleNames",
            "originatingRoot", "originatingRootPackage", "originatingRootSimpleNames",
            "rootAnnotation",
        )

        /** …and from 2.57 on, which is what the bundled 2.60.1 processor writes. */
        val AGGREGATED_ROOT_2_60 = AGGREGATED_ROOT_2_51 + arrayOf("rootComponentPackage", "rootComponentSimpleNames")
    }
}
