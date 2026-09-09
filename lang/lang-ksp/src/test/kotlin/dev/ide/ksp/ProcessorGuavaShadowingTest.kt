package dev.ide.ksp

import dev.ide.platform.ToolUrlClassLoader
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Every processor bundle ships its own guava (30.1.1 for moshi, 33.2.1 for room, 33.6.0 for hilt) and the app
 * ships bundletool's, so under parent-first delegation a processor ran against the app's version. On ART that
 * mixes two dex files whose D8 lambda synthetics are numbered independently, and the Hilt processor died inside
 * a guava collector with `IncompatibleClassChangeError: Class
 * 'com.google.common.collect.CollectCollectors$$ExternalSyntheticLambda62' does not implement interface
 * 'java.util.function.Supplier'`.
 *
 * `ToolClassIsolation` pins `com.google.common.*` to the tool's own jars. This asserts the resolution against
 * the real bundles: the mechanics of the collision (and the fallback) are covered by
 * `dev.ide.platform.ToolClassIsolationTest`.
 */
class ProcessorGuavaShadowingTest {

    private fun appGuava(): List<Path> =
        (System.getProperty("app.guava.classpath") ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }.map { File(it).toPath() }.filter { Files.exists(it) }

    private fun List<Path>.urls() = map { it.toUri().toURL() }.toTypedArray()

    /** The jar a class was actually defined from. */
    private fun ClassLoader.jarOf(name: String): Path =
        Path.of(loadClass(name).protectionDomain.codeSource.location.toURI())

    @Test
    fun processorResolvesItsOwnGuavaOverTheAppProvidedOne() {
        assumeTrue(BundledKspProcessors.isBundled("hilt"), "/processors/hilt.zip not bundled, skipping")
        val appGuava = appGuava()
        assumeTrue(appGuava.isNotEmpty(), "the app's guava was not injected, skipping")
        val hiltJars = BundledKspProcessors.jarsFor("hilt").filter { Files.exists(it) }
        val bundleGuava = hiltJars.filter { it.fileName.toString().startsWith("guava-") }
        assumeTrue(bundleGuava.isNotEmpty(), "the hilt bundle ships no guava, skipping")

        // Both sides must really be different jars, or neither delegation order proves anything.
        assertNotEquals(
            appGuava.single().fileName.toString(),
            bundleGuava.single().fileName.toString(),
            "the app and the hilt bundle ship the same guava jar, so this test proves nothing",
        )

        val parent = URLClassLoader(appGuava.urls(), javaClass.classLoader)
        // Plain parent-first delegation (what the loaders used to do) runs the processor on the app's copy.
        assertEquals(
            appGuava.single(),
            URLClassLoader(hiltJars.urls(), parent).jarOf("com.google.common.collect.ImmutableList"),
            "expected parent-first delegation to reproduce the shadowing",
        )
        // The fix: the processor's own guava wins, so its classes and their dexed lambda synthetics agree.
        assertEquals(
            bundleGuava.single(),
            ToolUrlClassLoader(hiltJars.urls(), parent).jarOf("com.google.common.collect.ImmutableList"),
            "the tool classloader must load com.google.common.* from the processor's own jars",
        )
        // The types that cross the boundary still come from the app.
        assertTrue(
            ToolUrlClassLoader(hiltJars.urls(), parent)
                .loadClass("com.google.devtools.ksp.processing.SymbolProcessorProvider") ===
                Class.forName("com.google.devtools.ksp.processing.SymbolProcessorProvider"),
            "the KSP SPI must stay parent-loaded, or the providers can't cross the classloader boundary",
        )
    }
}
