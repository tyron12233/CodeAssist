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
 * A SECOND bundled processor end-to-end (Moshi), proving the catalog + generator aren't Room-specific: a
 * module carrying the Moshi runtime (`com.squareup.moshi.JsonClass` marker) trips the probe, and the bundled
 * `moshi-kotlin-codegen` generates a `*JsonAdapter` on our own compiler — the same path as Room.
 */
class MoshiKspTest {

    private fun classpathProp(name: String): List<Path> =
        (System.getProperty(name) ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }.map { File(it).toPath() }.filter { Files.exists(it) }

    @Test
    fun bundledMoshiGeneratesJsonAdapter() {
        assumeTrue(BundledKspProcessors.isBundled("moshi"), "/processors/moshi.zip not bundled — skipping")
        val runner = classpathProp("ksp.runner.classpath")
        val moshiLibs = classpathProp("moshi.libs.classpath")
        assumeTrue(runner.isNotEmpty() && moshiLibs.isNotEmpty(), "KSP runner / Moshi runtime classpaths not injected — skipping")

        val catalog = KspProcessorCatalog.bundled()
        withTempDir("ksp-moshi") { root ->
            val srcRoot = root.resolve("src/main/kotlin")
            Files.createDirectories(srcRoot)
            Files.writeString(
                srcRoot.resolve("Person.kt"),
                """
                package demo
                import com.squareup.moshi.JsonClass
                @JsonClass(generateAdapter = true)
                data class Person(val name: String, val age: Int)
                """.trimIndent(),
            )
            val genRoot = root.resolve("build/generated/ksp")
            Files.createDirectories(genRoot)

            val request = SourceGenRequest(
                moduleName = "app",
                kotlinSources = Files.walk(srcRoot).use { s -> s.filter { it.toString().endsWith(".kt") }.toList() },
                javaSources = emptyList(),
                classpath = moshiLibs,   // moshi runtime here trips the Moshi probe
                outputDir = genRoot,
                sourceRoots = listOf(srcRoot),
            )
            val generator = KspSourceGenerator(
                runnerClasspath = { runner },
                processors = { req -> catalog.classpathFor(req.classpath) },
                jdkHome = File(System.getProperty("java.home")).toPath(),
            )

            assertTrue(generator.appliesTo(request), "generator should apply once the Moshi runtime trips the probe")
            val result = generator.generate(request)
            val emitted = Files.walk(genRoot).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
            assertTrue(
                result.success && emitted.any { it.fileName.toString() == "PersonJsonAdapter.kt" },
                "bundled Moshi did not generate PersonJsonAdapter.kt:\n${result.messages.joinToString("\n")}\n" +
                    emitted.joinToString("\n") { genRoot.relativize(it).toString() },
            )
        }
    }
}
