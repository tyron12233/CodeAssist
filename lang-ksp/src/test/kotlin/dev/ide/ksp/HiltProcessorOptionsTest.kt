package dev.ide.ksp

import dev.ide.platform.ToolUrlClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Hilt's processor refuses a plain `@AndroidEntryPoint` (the form every project written for AGP uses)
 * unless it is told the Gradle plugin's superclass rewrite will happen:
 *
 * ```
 * ksp error: [Hilt] Expected @AndroidEntryPoint to have a value.
 *            Did you forget to apply the Gradle Plugin? (com.google.dagger.hilt.android)
 * ```
 *
 * There is no Gradle plugin here, so the IDE supplies both halves itself: the processor option
 * ([KspProcessorCatalog.HILT_OPTIONS], asserted here against the processor the app actually bundles) and the
 * rewrite the option promises (`dev.ide.android.support.tools.HiltEntryPoints`, covered in `:android-support`).
 */
class HiltProcessorOptionsTest {

    @Test
    fun theHiltOptionIsOnlyContributedWhenTheHiltProcessorRuns() {
        val marker = KspProcessorCatalog.HILT_MARKER
        val option = KspProcessorCatalog.HILT_OPTIONS.keys.single()
        withTempJar(marker) { hiltJar ->
            val catalog = KspProcessorCatalog.blessed(bundledJars = { listOf(hiltJar) })
            val classpath = listOf(hiltJar)

            assertEquals(
                KspProcessorCatalog.HILT_OPTIONS,
                catalog.optionsFor(classpath, listOf("com.google.dagger:hilt-android:2.60.1")),
            )
            // A processor that doesn't run contributes no options, so Room's runs get exactly what they got before.
            assertTrue(
                catalog.optionsFor(classpath, listOf("androidx.room:room-runtime:2.8.4")).isEmpty(),
                "$option must not leak into a run that is not Hilt's",
            )
        }
    }

    /**
     * The option name is Hilt's, not ours: read it back off the bundled `hilt-compiler` so a Hilt upgrade that
     * renames or drops it fails here rather than at a user's build with the "did you forget the Gradle plugin"
     * error the option exists to prevent.
     */
    @Test
    fun theOptionNameMatchesTheBundledHiltProcessor() {
        assumeTrue(BundledKspProcessors.isBundled("hilt"), "/processors/hilt.zip not bundled, skipping")
        val jars = BundledKspProcessors.jarsFor("hilt").filter { Files.exists(it) }
        assumeTrue(jars.isNotEmpty(), "hilt processor bundle extracted to nothing, skipping")

        val cl = ToolUrlClassLoader(jars.map { it.toUri().toURL() }.toTypedArray(), javaClass.classLoader)
        val options = cl.loadClass("dagger.hilt.processor.internal.HiltCompilerOptions")
        val field = options.getDeclaredField("DISABLE_ANDROID_SUPERCLASS_VALIDATION").apply { isAccessible = true }
        val qualifiedName = field.get(null).let { opt ->
            opt.javaClass.getDeclaredMethod("getQualifiedName").apply { isAccessible = true }.invoke(opt)
        }

        assertEquals(KspProcessorCatalog.HILT_OPTIONS.keys.single(), qualifiedName)
    }

    /** A jar carrying [entry] as a zero-byte class entry, enough to trip the catalog's marker probe. */
    private fun withTempJar(entry: String, body: (Path) -> Unit) {
        val dir = Files.createTempDirectory("hilt-options")
        try {
            val jar = dir.resolve("hilt-android.jar")
            java.util.zip.ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry(entry)); zos.closeEntry()
            }
            body(jar)
        } finally {
            runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
