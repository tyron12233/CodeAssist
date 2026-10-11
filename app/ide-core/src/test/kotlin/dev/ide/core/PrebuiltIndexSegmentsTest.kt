package dev.ide.core

import dev.ide.core.plugins.BUILT_IN_INDEX_EXTENSIONS
import dev.ide.index.IndexScope
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The prebuilt segments are only worth shipping if the device actually finds them: generated on the build host,
 * unpacked into an empty cache, and then looked up through the same stable jar ids the IDE's own scope uses,
 * not one artifact may need building.
 */
class PrebuiltIndexSegmentsTest {

    /** A small real jar: a few JDK-free classes copied out of this test's own classpath. */
    private fun sampleJar(dir: Path): Path {
        val jar = dir.resolve("sample-lib.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { z ->
            for (cls in listOf(PrebuiltIndexSegments::class.java, IndexPassTrigger::class.java)) {
                val name = cls.name.replace('.', '/') + ".class"
                z.putNextEntry(ZipEntry(name))
                cls.classLoader.getResourceAsStream(name)!!.use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun generatedSegmentsAreCacheHitsForTheSameStableIds() {
        withTempDir("prebuilt") { dir ->
            val jar = sampleJar(dir)
            val zip = dir.resolve("index-prebuilt.zip")
            PrebuiltIndexSegments.generate(listOf(jar), zip)

            val cache = dir.resolve("device-cache")
            val written = Files.newInputStream(zip).use { PrebuiltIndexSegments.install(it, cache) }
            assertEquals(BUILT_IN_INDEX_EXTENSIONS.size, written, "one segment per built-in extension")

            val device = IndexServiceImpl(BUILT_IN_INDEX_EXTENSIONS, cache)
            try {
                runBlocking {
                    device.ensureUpToDate(
                        IndexScope(libraryJars = listOf(jar), stableJarIds = mapOf(jar to PrebuiltIndexSegments.stableId(jar)))
                    )
                }
                val stats = device.status.stats!!
                assertEquals(0, stats.artifactsBuilt, "the device must reuse the prebuilt segments, not rebuild")
                assertTrue(device.status.ready)
            } finally {
                device.close()
            }
        }
    }

    @Test
    fun installSkipsSegmentsAlreadyInTheCache() {
        withTempDir("prebuilt") { dir ->
            val zip = zipOf("a/v1/k.seg" to byteArrayOf(1, 2, 3))
            val cache = dir.resolve("cache")
            Files.createDirectories(cache.resolve("a/v1"))
            Files.write(cache.resolve("a/v1/k.seg"), byteArrayOf(9))
            assertEquals(0, PrebuiltIndexSegments.install(ByteArrayInputStream(zip), cache))
            assertEquals(listOf<Byte>(9), Files.readAllBytes(cache.resolve("a/v1/k.seg")).toList(), "an existing segment is left alone")
        }
    }

    @Test
    fun anEntryOutsideTheCacheIsRejected() {
        withTempDir("prebuilt") { dir ->
            val zip = zipOf("../escape.seg" to byteArrayOf(1))
            assertFailsWith<IllegalArgumentException> { PrebuiltIndexSegments.install(ByteArrayInputStream(zip), dir.resolve("cache")) }
            assertTrue(!Files.exists(dir.resolve("escape.seg")))
        }
    }

    @Test
    fun ensureInstalledUnpacksOncePerStamp() {
        withTempDir("prebuilt") { dir ->
            val cache = dir.resolve("cache")
            var opens = 0
            val saved = PrebuiltIndexSegments.source
            try {
                PrebuiltIndexSegments.source = PrebuiltIndexSegments.Source("v1") {
                    opens++; ByteArrayInputStream(zipOf("a/v1/k.seg" to byteArrayOf(1)))
                }
                PrebuiltIndexSegments.ensureInstalled(cache)
                PrebuiltIndexSegments.ensureInstalled(cache)
                assertEquals(1, opens, "the same app version unpacks once")
                assertTrue(Files.exists(cache.resolve("a/v1/k.seg")))

                PrebuiltIndexSegments.source = PrebuiltIndexSegments.Source("v2") {
                    opens++; ByteArrayInputStream(zipOf("a/v1/k.seg" to byteArrayOf(1)))
                }
                PrebuiltIndexSegments.ensureInstalled(cache)
                assertEquals(2, opens, "an update unpacks again")
            } finally {
                PrebuiltIndexSegments.source = saved
            }
        }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
