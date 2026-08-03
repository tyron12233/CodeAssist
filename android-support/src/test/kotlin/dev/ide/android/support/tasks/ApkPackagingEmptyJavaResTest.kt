package dev.ide.android.support.tasks

import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression for the intermittent on-device "No entries" APK/AAB packaging crash: a module with NO Java
 * resources makes [JavaResMerger] write `merged-java-res.jar` as the canonical 22-byte empty archive, which
 * ART's `ZipFile` REJECTS on read (`ZipException: No entries`) — so [ApkPackaging.assembleApk] /
 * [BundlePackaging.buildBaseModuleZip] crashed on device while reading it back, only "sometimes" (when a
 * module has zero Java resources). The desktop JVM opens that archive fine, so tests never caught it; a
 * CORRUPT jar throws on the desktop JVM too, so it is the desktop-reproducible analog. Packaging must SKIP
 * an empty/unreadable java-res jar (it contributes nothing) rather than aborting the whole build.
 */
class ApkPackagingEmptyJavaResTest {

    /** The 22-byte End-Of-Central-Directory record = a valid but entry-less archive (what JavaResMerger writes). */
    private val emptyZip = byteArrayOf(0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    private fun writeResourcesAp(path: Path) {
        path.parent?.let { Files.createDirectories(it) }
        ZipOutputStream(Files.newOutputStream(path)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml")); zos.write("<manifest/>".toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("resources.arsc")); zos.write(byteArrayOf(2, 0, 12, 0)); zos.closeEntry()
        }
    }

    @Test
    fun assembleApkSkipsEmptyAndCorruptJavaResJars() {
        withTempDir("apk-empty-javares") { dir ->
            val ap = dir.resolve("res/resources.ap_").also { writeResourcesAp(it) }
            val empty = dir.resolve("merged-java-res.jar").also { Files.write(it, emptyZip) }
            val corrupt = dir.resolve("corrupt-java-res.jar").also { Files.write(it, "not a zip".toByteArray()) }
            val outApk = dir.resolve("out/app.apk")

            val written = ApkPackaging.assembleApk(
                resourcesAp = ap,
                dexDirs = emptyList(),
                assetsDirs = emptyList(),
                jniLibDirs = emptyList(),
                outApk = outApk,
                javaResJars = listOf(empty, corrupt),
            )
            assertTrue(Files.exists(outApk), "APK was not produced")
            assertTrue("AndroidManifest.xml" in written, "aapt2 content missing from the APK: $written")
        }
    }

    @Test
    fun bundleModuleSkipsEmptyAndCorruptJavaResJars() {
        withTempDir("aab-empty-javares") { dir ->
            val ap = dir.resolve("res/proto.ap_").also { writeResourcesAp(it) }
            val empty = dir.resolve("merged-java-res.jar").also { Files.write(it, emptyZip) }
            val corrupt = dir.resolve("corrupt-java-res.jar").also { Files.write(it, "not a zip".toByteArray()) }
            val outZip = dir.resolve("out/base.zip")

            val written = BundlePackaging.buildBaseModuleZip(
                protoAp = ap,
                dexDirs = emptyList(),
                assetsDirs = emptyList(),
                jniLibDirs = emptyList(),
                outZip = outZip,
                javaResJars = listOf(empty, corrupt),
            )
            assertTrue(Files.exists(outZip), "bundle base module not produced")
            assertTrue(written.any { it.endsWith("AndroidManifest.xml") }, "manifest missing from the bundle: $written")
        }
    }
}
