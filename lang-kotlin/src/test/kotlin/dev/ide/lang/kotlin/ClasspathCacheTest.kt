package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.symbols.ClasspathReader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The scan persistence: the classpath extension scan is cached per jar (content-keyed) and written under a
 * cache dir so it survives a fresh reader (i.e. a relaunch), instead of re-decoding every class each time.
 */
class ClasspathCacheTest {

    @Test
    fun extensionScanPersistsAndReloads() {
        val cacheDir = Files.createTempDirectory("kxt-cache")
        val jar = stdlibJarPath()

        ClasspathReader(listOf(jar), cacheDir).use { first ->
            val scan = first.scan(null)
            assertTrue(
                scan.extensionsByReceiver.values.flatten().any { it.name == "map" },
                "first scan should find Iterable.map",
            )
        }

        val cacheFiles = Files.list(cacheDir).use { it.toList() }
        assertTrue(cacheFiles.any { it.toString().endsWith(".kxt") }, "a per-jar .kxt cache file should be written")

        // A fresh reader over the same cache dir must reload from disk (no re-decode) and still see extensions.
        ClasspathReader(listOf(jar), cacheDir).use { second ->
            val scan = second.scan(null)
            assertTrue(
                scan.extensionsByReceiver.values.flatten().any { it.name == "trim" },
                "reloaded scan should still carry CharSequence.trim",
            )
        }
    }

    @Test
    fun nonKotlinJarIsSkippedCheaply() {
        // A jar with no META-INF kotlin_module yields no extensions (and is skipped without decoding classes).
        val emptyJar = Files.createTempFile("plain", ".jar")
        java.util.zip.ZipOutputStream(Files.newOutputStream(emptyJar)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("com/example/Foo.class"))
            zos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
            zos.closeEntry()
        }
        ClasspathReader(listOf(emptyJar)).use { r ->
            val scan = r.scan(null)
            assertTrue(scan.extensionsByReceiver.isEmpty(), "non-Kotlin jar contributes no extensions")
            assertTrue(scan.topLevelByName.isEmpty())
        }
    }
}
