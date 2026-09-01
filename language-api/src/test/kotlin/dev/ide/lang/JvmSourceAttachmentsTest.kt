package dev.ide.lang

import dev.ide.testkit.compilationContext
import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The source roots every JVM backend must publish, derived in one place so the JDT and IntelliJ-PSI `.java`
 * backends cannot disagree about them. A missing root is invisible at runtime (Java bytecode carries no
 * parameter names, so the editor silently falls back to `p0`), which is exactly how the IntelliJ-PSI backend
 * shipped without the declared `-sources.jar`s.
 */
class JvmSourceAttachmentsTest {

    @Test
    fun declaredAttachmentsSplitIntoArchivesAndDirs() = withTempDir("attachments") { dir ->
        val jar = zipAt(dir.resolve("lib-sources.jar"))
        val zip = zipAt(dir.resolve("lib-sources.zip"))
        val exploded = Files.createDirectories(dir.resolve("exploded"))
        val missing = dir.resolve("gone-sources.jar")

        val ctx = compilationContext(
            sourceRoots = listOf(dir),
            sourceAttachments = listOf(jar, zip, exploded, missing),
        )

        assertEquals(listOf(jar, zip), JvmSourceAttachments.attachmentJars(ctx))
        assertEquals(listOf(exploded), JvmSourceAttachments.attachmentDirs(ctx))
        assertEquals(
            listOf(jar, zip, exploded),
            JvmSourceAttachments.librarySourceArchives(ctx, jdkHome = null),
            "a path that doesn't exist is dropped, everything real is kept",
        )
    }

    @Test
    fun jdkSrcZipIsDerivedFromTheJdkImage() = withTempDir("jdk") { jdkHome ->
        assertNull(JvmSourceAttachments.jdkSrcZip(jdkHome), "no src.zip shipped")
        assertNull(JvmSourceAttachments.jdkSrcZip(null))

        val srcZip = zipAt(Files.createDirectories(jdkHome.resolve("lib")).resolve("src.zip"))
        assertEquals(srcZip, JvmSourceAttachments.jdkSrcZip(jdkHome))
    }

    /**
     * The SDK ships framework sources keyed by BASE api level (`sources/android-36`) while the installed
     * platform jar can be a minor/extension revision (`platforms/android-36.1/android.jar`). An exact-name
     * match alone would miss them whenever the two don't line up.
     */
    @Test
    fun androidPlatformSourcesMatchOnMajorApiLevel() = withTempDir("fake-sdk") { sdk ->
        val androidJar = sdk.resolve("platforms/android-36.1/android.jar")
        Files.createDirectories(androidJar.parent)
        zipAt(androidJar)
        val sources = Files.createDirectories(sdk.resolve("sources/android-36"))

        val ctx = compilationContext(sourceRoots = listOf(sdk), bootClasspath = listOf(androidJar))
        assertEquals(sources, JvmSourceAttachments.androidPlatformSources(ctx))
        assertTrue(sources in JvmSourceAttachments.librarySourceArchives(ctx, jdkHome = null))
    }

    /** On device `android.jar` is a bundled FLAT asset: nothing to derive, so the host attaches sources itself. */
    @Test
    fun flatBundledAndroidJarHasNoDerivableSources() = withTempDir("device-home") { home ->
        val androidJar = zipAt(home.resolve("android.jar"))
        val ctx = compilationContext(sourceRoots = listOf(home), bootClasspath = listOf(androidJar))
        assertNull(JvmSourceAttachments.androidPlatformSources(ctx))
    }

    private fun zipAt(p: Path): Path = p.also { ZipOutputStream(Files.newOutputStream(it)).close() }
}
