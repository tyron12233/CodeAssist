package dev.ide.lang.jdt

import dev.ide.lang.AnnotationProcessor
import dev.ide.lang.CompilationContext
import dev.ide.model.ClasspathEntry
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.ClasspathSnapshot
import dev.ide.model.LanguageLevel
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The Android platform sources dir is matched by MAJOR API level, not exact name: the SDK ships framework
 * sources keyed by the base level (`sources/android-36`) while the installed platform jar can be a minor /
 * extension revision (`platforms/android-36.1/android.jar`) or vice-versa. Without the fallback, framework
 * APIs complete with `p0` placeholders and no javadoc from a `.kt`/`.java` file whenever the two don't align.
 *
 * Builds a synthetic SDK layout where the two DON'T line up (platform `android-36`, sources `android-36.1`),
 * so the fallback is the only way `Activity.onCreate`'s real parameter name resolves.
 */
class AndroidPlatformSourcesTest {

    @Test
    fun frameworkParamNamesResolveAcrossMinorVersionSkew() {
        val sdk = Files.createTempDirectory("fake-sdk")
        val androidJar = sdk.resolve("platforms/android-36/android.jar")
        Files.createDirectories(androidJar.parent)
        ZipOutputStream(Files.newOutputStream(androidJar)).close() // a valid (empty) jar — JDT opens it

        val activity = sdk.resolve("sources/android-36.1/android/app/Activity.java")
        Files.createDirectories(activity.parent)
        Files.writeString(
            activity,
            """
            package android.app;
            public class Activity {
                /** Called when the activity is starting. */
                protected void onCreate(android.os.Bundle savedInstanceState) {}
            }
            """.trimIndent(),
        )

        val resolver = analyzerWithBoot(androidJar).sourceMethodResolver
        val onCreate = resolver.method("android.app.Activity", "onCreate", 1)
        assertNotNull(onCreate, "Activity.onCreate should resolve from the same-major sources dir")
        assertEquals(listOf("savedInstanceState"), onCreate.names, "real framework param name")
    }

    /**
     * On device `android.jar` is a bundled FLAT asset (`<home>/android.jar`, no `platforms/android-NN/` parent),
     * so the analyzer can't DERIVE the framework sources dir from its path — the SDK-Manager-installed sources
     * live at an unrelated root. This is the disconnect that left `setContentView` showing `p0`/no javadoc on
     * device even after installing sources. The host bridges it by attaching the sources dir explicitly via
     * [JdtSourceAnalyzer.addSourceDirs]; without that attach the method must NOT resolve.
     */
    @Test
    fun bundledFlatJarResolvesFrameworkDocsOnlyAfterExplicitSourceDirAttach() {
        val home = Files.createTempDirectory("device-home")
        val androidJar = home.resolve("android.jar")
        ZipOutputStream(Files.newOutputStream(androidJar)).close()

        val sdkManagerRoot = Files.createTempDirectory("sdk-manager-root")
        val activity = sdkManagerRoot.resolve("sources/android-36/android/app/Activity.java")
        Files.createDirectories(activity.parent)
        Files.writeString(
            activity,
            """
            package android.app;
            public class Activity {
                /** Set the activity content from a layout resource. */
                public void setContentView(int layoutResID) {}
            }
            """.trimIndent(),
        )

        val analyzer = analyzerWithBoot(androidJar)
        assertNull(
            analyzer.sourceMethodResolver.method("android.app.Activity", "setContentView", 1),
            "a flat bundled android.jar has no derivable sources dir — resolution should miss",
        )

        analyzer.addSourceDirs(listOf(sdkManagerRoot.resolve("sources/android-36")))
        val doc = analyzer.sourceMethodResolver.method("android.app.Activity", "setContentView", 1)
        assertNotNull(doc, "setContentView should resolve after the SDK-Manager sources dir is attached")
        assertEquals(listOf("layoutResID"), doc.names, "real framework param name")
        assertNotNull(doc.doc, "framework javadoc should be attached")
    }

    private fun analyzerWithBoot(androidJar: Path): JdtSourceAnalyzer {
        val ctx = object : CompilationContext {
            override val sourceRoots: List<VirtualFile> = emptyList()
            override val classpath: ClasspathSnapshot = empty()
            override val bootClasspath: ClasspathSnapshot = object : ClasspathSnapshot {
                override val entries = listOf(ClasspathEntry(StubFile(androidJar.toString()), ClasspathEntryKind.SDK_BOOTCLASSPATH))
                override fun fingerprint() = ContentHash(androidJar.toString())
            }
            override val languageLevel = LanguageLevel.JAVA_17
            override val outputDir: VirtualFile = StubFile("/out")
            override val processors: List<AnnotationProcessor> = emptyList()
        }
        return JdtSourceAnalyzer(ctx)
    }

    private fun empty() = object : ClasspathSnapshot {
        override val entries: List<ClasspathEntry> = emptyList()
        override fun fingerprint() = ContentHash("")
    }
}
