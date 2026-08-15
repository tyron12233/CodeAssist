package dev.ide.ksp

import dev.ide.build.SourceGenRequest
import dev.ide.platform.ToolUrlClassLoader
import dev.ide.testkit.withTempDir
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Enabling the bundled Hilt/Dagger processor crashed on device with
 * `NoSuchMethodError: No static method provider(Ldagger/internal/Provider;)Ldagger/internal/Provider; in class
 * Ldagger/internal/DoubleCheck;`. That is Dagger's own generated components, inside `dagger-compiler`, failing to
 * build the processor's object graph.
 *
 * Cause: the app dexes bundletool (in-process `.aab` building), whose closure carries an ancient Dagger
 * runtime, and the tool classloader is parent-first, so the app's `dagger.internal.*` shadowed the Dagger 2.6x
 * the processor bundle ships and was compiled against. [dev.ide.platform.ToolClassIsolation] pins `dagger.*` to
 * the tool's own jars; these tests reproduce the exact classloader shape (bundletool's dagger as the parent) and
 * assert both the shadowing and the fix.
 */
class HiltProcessorDaggerShadowingTest {

    private fun classpathProp(name: String): List<Path> =
        (System.getProperty(name) ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }.map { File(it).toPath() }.filter { Files.exists(it) }

    private fun List<Path>.urls() = map { it.toUri().toURL() }.toTypedArray()

    /** The Dagger 2.5x+ overload `dagger-compiler`'s generated components call, absent from older runtimes. */
    private fun Class<*>.hasDaggerProviderOverload(): Boolean =
        methods.any { m -> m.name == "provider" && m.parameterTypes.singleOrNull()?.name == "dagger.internal.Provider" }

    /** The app-like parent: this test's classloader plus the dagger runtime bundletool drags into the app. */
    private fun appLikeParent(appDagger: List<Path>): ClassLoader =
        URLClassLoader(appDagger.urls(), javaClass.classLoader)

    @Test
    fun processorResolvesItsOwnDaggerRuntimeOverTheAppProvidedOne() {
        assumeTrue(BundledKspProcessors.isBundled("hilt"), "/processors/hilt.zip not bundled, skipping")
        val appDagger = classpathProp("app.dagger.classpath")
        assumeTrue(appDagger.isNotEmpty(), "bundletool's dagger runtime not injected, skipping")
        val hiltJars = BundledKspProcessors.jarsFor("hilt").filter { Files.exists(it) }
        assumeTrue(hiltJars.isNotEmpty(), "hilt processor bundle extracted to nothing, skipping")

        val parent = appLikeParent(appDagger)
        // The fixture is only meaningful while the app's dagger really is the incompatible one.
        assertFalse(
            parent.loadClass("dagger.internal.DoubleCheck").hasDaggerProviderOverload(),
            "bundletool's dagger (${appDagger.joinToString { it.fileName.toString() }}) already carries the " +
                "dagger.internal.Provider overload, so it no longer shadows the processor and this test proves nothing",
        )

        // Plain parent-first delegation (what the loaders used to do) hands the processor the app's runtime.
        assertFalse(
            URLClassLoader(hiltJars.urls(), parent).loadClass("dagger.internal.DoubleCheck").hasDaggerProviderOverload(),
            "expected parent-first delegation to reproduce the shadowing",
        )

        // The fix: the processor's bundled Dagger wins for `dagger.*`.
        assertTrue(
            ToolUrlClassLoader(hiltJars.urls(), parent).loadClass("dagger.internal.DoubleCheck").hasDaggerProviderOverload(),
            "the tool classloader must load dagger.internal.DoubleCheck from the processor's own jars",
        )
        // Everything else still comes from the app (one stdlib, one SPI, one IntelliJ platform).
        val shared = ToolUrlClassLoader(hiltJars.urls(), parent).loadClass("com.google.devtools.ksp.processing.SymbolProcessorProvider")
        assertTrue(
            shared === Class.forName("com.google.devtools.ksp.processing.SymbolProcessorProvider"),
            "the KSP SPI must stay parent-loaded, or the providers can't cross the classloader boundary",
        )
    }

    @Test
    fun bundledDaggerProcessorGeneratesAComponentWithTheAppsOldDaggerOnTheParent() {
        assumeTrue(BundledKspProcessors.isBundled("hilt"), "/processors/hilt.zip not bundled, skipping")
        val runner = classpathProp("ksp.runner.classpath")
        val hiltLibs = classpathProp("hilt.libs.classpath")
        val appDagger = classpathProp("app.dagger.classpath")
        assumeTrue(
            runner.isNotEmpty() && hiltLibs.isNotEmpty() && appDagger.isNotEmpty(),
            "KSP runner / Hilt runtime / app dagger classpaths not injected, skipping",
        )

        val parent = appLikeParent(appDagger)
        val catalog = KspProcessorCatalog.bundled()
        withTempDir("ksp-hilt") { root ->
            val srcRoot = root.resolve("src/main/kotlin")
            Files.createDirectories(srcRoot)
            Files.writeString(
                srcRoot.resolve("AppComponent.kt"),
                // Constructor injection only: a @Module would trip Hilt's own "missing @InstallIn" check (the
                // hilt bundle runs BOTH Hilt's and Dagger's processors), which isn't what this test is about.
                """
                package demo
                import dagger.Component
                import javax.inject.Inject

                class Greeter @Inject constructor()

                @Component
                interface AppComponent { fun greeter(): Greeter }
                """.trimIndent(),
            )
            val genRoot = root.resolve("build/generated/ksp")
            Files.createDirectories(genRoot)

            val request = SourceGenRequest(
                moduleName = "app",
                kotlinSources = Files.walk(srcRoot).use { s -> s.filter { it.toString().endsWith(".kt") }.toList() },
                javaSources = emptyList(),
                classpath = hiltLibs,   // hilt-core's dagger.hilt.InstallIn trips the Hilt/Dagger probe
                outputDir = genRoot,
                sourceRoots = listOf(srcRoot),
            )
            val generator = KspSourceGenerator(
                runnerClasspath = { runner },
                processors = { req -> catalog.classpathFor(req.classpath) },
                // The production loader, but parented on the app-like classloader so the ancient dagger is in
                // play exactly as it is on device.
                loader = KspProcessorLoader { cp ->
                    ToolUrlClassLoader(cp.filter { Files.exists(it) }.map { it.toUri().toURL() }.toTypedArray(), parent)
                },
                jdkHome = File(System.getProperty("java.home")).toPath(),
            )

            assertTrue(generator.appliesTo(request), "generator should apply once hilt-core trips the probe")
            val result = generator.generate(request)
            val emitted = Files.walk(genRoot).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
            assertTrue(
                result.success && emitted.any { it.fileName.toString().startsWith("DaggerAppComponent") },
                "bundled Dagger did not generate DaggerAppComponent:\n${result.messages.joinToString("\n")}\n" +
                    emitted.joinToString("\n") { genRoot.relativize(it).toString() },
            )
        }
    }
}
