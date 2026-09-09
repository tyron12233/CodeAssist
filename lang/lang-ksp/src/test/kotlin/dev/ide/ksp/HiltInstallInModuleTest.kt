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
 * The bundled Hilt processor over the shape a real Hilt project is written in: a `@Module` carrying
 * `@InstallIn(SingletonComponent::class)` — an annotation with an ARGUMENT — plus constructor injection and
 * use-site-targeted qualifiers. [HiltProcessorDaggerShadowingTest] proves the processor's classloader is
 * sound, but it uses a bare `@Component`, so nothing exercised reading an annotation's arguments: that is
 * XProcessing's `KspAnnotationValue.findMethod` (`getDeclaredMethods().single { … == argName }`) and KSP2's
 * `kaAnnotations.single { it.psi == entry }`, the two places a Hilt run reports
 * `[Hilt] Collection contains no element matching the predicate.` when an annotation doesn't line up.
 */
class HiltInstallInModuleTest {

    private fun classpathProp(name: String): List<Path> =
        (System.getProperty(name) ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }.map { File(it).toPath() }.filter { Files.exists(it) }

    @Test
    fun installInModuleWithArgumentsAndQualifiersGenerates() {
        assumeTrue(BundledKspProcessors.isBundled("hilt"), "/processors/hilt.zip not bundled, skipping")
        val runner = classpathProp("ksp.runner.classpath")
        val hiltLibs = classpathProp("hilt.libs.classpath")
        assumeTrue(runner.isNotEmpty() && hiltLibs.isNotEmpty(), "KSP runner / Hilt runtime not injected, skipping")

        val catalog = KspProcessorCatalog.bundled()
        withTempDir("ksp-hilt-installin") { root ->
            val srcRoot = root.resolve("src/main/kotlin")
            Files.createDirectories(srcRoot)
            Files.writeString(
                srcRoot.resolve("NetModule.kt"),
                """
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
                """.trimIndent(),
            )
            val genRoot = root.resolve("build/generated/ksp")
            Files.createDirectories(genRoot)

            val request = SourceGenRequest(
                moduleName = "app",
                kotlinSources = Files.walk(srcRoot).use { s -> s.filter { it.toString().endsWith(".kt") }.toList() },
                javaSources = emptyList(),
                classpath = hiltLibs,
                outputDir = genRoot,
                sourceRoots = listOf(srcRoot),
            )
            val messages = mutableListOf<String>()
            val generator = KspSourceGenerator(
                runnerClasspath = { runner },
                processors = { req -> catalog.classpathFor(req.classpath) },
                processorOptions = { req -> catalog.optionsFor(req.classpath, req.declaredDependencies) },
                jdkHome = File(System.getProperty("java.home")).toPath(),
                log = { messages += it },
            )

            assertTrue(generator.appliesTo(request), "generator should apply once hilt-core trips the probe")
            val result = generator.generate(request)
            val emitted = Files.walk(genRoot).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
            assertTrue(
                result.success,
                "@InstallIn module failed to process:\n${result.messages.joinToString("\n")}\n" +
                    messages.joinToString("\n"),
            )
            assertTrue(
                emitted.any { it.fileName.toString().contains("NetModule") },
                "Hilt generated nothing for the @InstallIn module:\n" +
                    emitted.joinToString("\n") { genRoot.relativize(it).toString() },
            )
        }
    }
}
