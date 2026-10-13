package dev.ide.android.support.tools

import dev.ide.testkit.withTempDir
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidSdkInstallerTest {

    private val xml = """
        <sdk:sdk-repository xmlns:sdk="http://schemas.android.com/sdk/android/repo/repository2/03"
                            xmlns:common="http://schemas.android.com/repository/android/common/02">
          <license id="android-sdk-license" type="text">Terms and Conditions
        This is the Android Software Development Kit License Agreement</license>
          <remotePackage path="platforms;android-34">
            <display-name>Android SDK Platform 34</display-name>
            <revision><major>3</major></revision>
            <uses-license ref="android-sdk-license"/>
            <archives>
              <archive><complete><size>1000</size><url>platform-34_r03.zip</url></complete></archive>
            </archives>
          </remotePackage>
          <remotePackage path="platforms;android-34-ext12">
            <display-name>Android SDK Platform 34-ext12</display-name>
            <archives><archive><complete><size>1</size><url>p34e.zip</url></complete></archive></archives>
          </remotePackage>
          <remotePackage path="platforms;android-37.2-beta1">
            <display-name>Android SDK Platform 37.2 beta</display-name>
            <archives><archive><complete><size>1</size><url>p37b.zip</url></complete></archive></archives>
          </remotePackage>
          <remotePackage path="build-tools;34.0.0">
            <display-name>Android SDK Build-Tools 34</display-name>
            <revision><major>34</major><minor>0</minor><micro>0</micro></revision>
            <archives>
              <archive><host-os>macosx</host-os><complete><size>50</size><url>mac-bt.zip</url></complete></archive>
              <archive><host-os>linux</host-os><complete><size>51</size><url>linux-bt.zip</url></complete></archive>
            </archives>
          </remotePackage>
          <remotePackage path="sources;android-34">
            <display-name>Sources for Android 34</display-name>
            <revision><major>1</major></revision>
            <archives>
              <archive><complete><size>20</size><url>sources-34_r01.zip</url></complete></archive>
            </archives>
          </remotePackage>
          <remotePackage path="extras;google;m2repository">
            <display-name>Some extra</display-name>
            <archives><archive><complete><size>1</size><url>x.zip</url></complete></archive></archives>
          </remotePackage>
        </sdk:sdk-repository>
    """.trimIndent()

    @Test
    fun parsesSourcesAndStablePlatforms() {
        val pkgs = AndroidSdkInstaller.parsePackages(xml)
        // Sources and stable platforms; extension/beta platforms, build-tools and extras are dropped.
        assertEquals(setOf("sources;android-34", "platforms;android-34"), pkgs.map { it.path }.toSet())

        val platform = pkgs.first { it.path == "platforms;android-34" }
        assertEquals(AndroidSdkInstaller.Category.PLATFORMS, platform.category)
        assertEquals("android-sdk-license", platform.license)
        assertNull(pkgs.first { it.path == "sources;android-34" }.license)

        val sources = pkgs.first { it.path == "sources;android-34" }
        assertEquals(AndroidSdkInstaller.Category.SOURCES, sources.category)
        assertEquals("1", sources.revision)
        assertTrue(sources.archiveUrl!!.endsWith("sources-34_r01.zip"), sources.archiveUrl!!)
        assertTrue(sources.archiveUrl!!.startsWith(AndroidSdkInstaller.REPO_BASE))
        assertEquals(20L, sources.sizeBytes)
    }

    @Test
    fun installDirMapsIdToPath() {
        val dir = AndroidSdkInstaller.parsePackages(xml).first { it.path == "sources;android-34" }.installDir(Path.of("/sdk"))
        assertEquals(Path.of("/sdk", "sources", "android-34"), dir)
    }

    @Test
    fun chooseArchivePicksHostThenUniversal() {
        val buildTools = packageElement("build-tools;34.0.0")
        assertTrue(AndroidSdkInstaller.chooseArchive(buildTools, "macosx").first!!.endsWith("mac-bt.zip"))
        assertTrue(AndroidSdkInstaller.chooseArchive(buildTools, "linux").first!!.endsWith("linux-bt.zip"))
        // No Windows archive and no universal fallback → nothing.
        assertNull(AndroidSdkInstaller.chooseArchive(buildTools, "windows").first)

        // The platform has only a universal archive → chosen for any host.
        val platform = packageElement("platforms;android-34")
        assertTrue(AndroidSdkInstaller.chooseArchive(platform, "windows").first!!.endsWith("platform-34_r03.zip"))
    }

    @Test
    fun parsesLicenseText() {
        val text = AndroidSdkInstaller.parseLicenses(xml)["android-sdk-license"]!!
        assertTrue(text.startsWith("Terms and Conditions"), text)
        assertTrue("Android Software Development Kit License Agreement" in text, text)
    }

    @Test
    fun categoryOfClassifies() {
        assertEquals(AndroidSdkInstaller.Category.SOURCES, AndroidSdkInstaller.categoryOf("sources;android-34"))
        assertEquals(AndroidSdkInstaller.Category.PLATFORMS, AndroidSdkInstaller.categoryOf("platforms;android-34"))
        assertEquals(AndroidSdkInstaller.Category.PLATFORMS, AndroidSdkInstaller.categoryOf("platforms;android-36.1"))
        assertEquals(36, AndroidSdkInstaller.platformLevel("platforms;android-36.1"))
        // Extension, preview and pre-21 platforms are not offered; nor is anything that isn't a source or platform.
        assertEquals(AndroidSdkInstaller.Category.OTHER, AndroidSdkInstaller.categoryOf("platforms;android-34-ext12"))
        assertEquals(AndroidSdkInstaller.Category.OTHER, AndroidSdkInstaller.categoryOf("platforms;android-37.2-beta1"))
        assertEquals(AndroidSdkInstaller.Category.OTHER, AndroidSdkInstaller.categoryOf("platforms;android-CANARY"))
        assertEquals(AndroidSdkInstaller.Category.OTHER, AndroidSdkInstaller.categoryOf("platforms;android-19"))
        assertEquals(AndroidSdkInstaller.Category.OTHER, AndroidSdkInstaller.categoryOf("build-tools;34.0.0"))
        assertEquals(AndroidSdkInstaller.Category.OTHER, AndroidSdkInstaller.categoryOf("cmdline-tools;latest"))
        assertEquals(AndroidSdkInstaller.Category.OTHER, AndroidSdkInstaller.categoryOf("ndk;26.0.0"))
    }

    @Test
    fun installPlacesPackageAndTracksCompletion() {
        withTempDir("sdktest") { tmp ->
            val sdkRoot = tmp.resolve("sdk")
            val downloads = tmp.resolve("dl")
            val pkg = AndroidSdkInstaller.parsePackages(xml).first { it.path == "sources;android-34" }

            val err = AndroidSdkInstaller.install(pkg, sdkRoot, downloads, zipFetcher("Foo.java", "class Foo {}"))

            assertNull(err)
            assertTrue(Files.isRegularFile(sdkRoot.resolve("sources").resolve("android-34").resolve("Foo.java")))
            assertEquals(setOf("sources;android-34"), AndroidSdkInstaller.installedPackages(sdkRoot))
            assertTrue(AndroidSdkInstaller.incompletePackages(sdkRoot).isEmpty())
            // The cached archive is dropped once installed.
            assertTrue(Files.list(downloads).use { it.findAny().isEmpty })
        }
    }

    @Test
    fun interruptedInstallReportsIncompleteNotInstalled() {
        withTempDir("sdktest2") { tmp ->
        val sdkRoot = tmp.resolve("sdk")
        val dir = sdkRoot.resolve("sources").resolve("android-34")
        Files.createDirectories(dir)
        // An interrupted install leaves the dir plus its `.installing` marker.
        Files.createFile(sdkRoot.resolve("sources").resolve("android-34.installing"))

        assertFalse("sources;android-34" in AndroidSdkInstaller.installedPackages(sdkRoot))
        assertEquals(setOf("sources;android-34"), AndroidSdkInstaller.incompletePackages(sdkRoot))

        // Re-installing repairs it: the marker clears, and it's now reported installed.
        val pkg = AndroidSdkInstaller.parsePackages(xml).first { it.path == "sources;android-34" }
        assertNull(AndroidSdkInstaller.install(pkg, sdkRoot, sdkRoot.resolve("dl"), zipFetcher("Foo.java", "class Foo {}")))
        assertTrue(AndroidSdkInstaller.incompletePackages(sdkRoot).isEmpty())
        assertEquals(setOf("sources;android-34"), AndroidSdkInstaller.installedPackages(sdkRoot))
        }
    }

    /** A platform archive as the SDK repo ships it: one root dir holding the jar plus files a slim install skips. */
    private fun platformZip(): ByteArray {
        // Incompressible payloads, so the skipped entries are a real share of the archive.
        val random = java.util.Random(1)
        fun noise(n: Int) = ByteArray(n).also(random::nextBytes)
        val bytes = java.io.ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            fun put(name: String, content: ByteArray, stored: Boolean = false) {
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED; e.size = content.size.toLong(); e.compressedSize = content.size.toLong()
                    e.crc = java.util.zip.CRC32().also { it.update(content) }.value
                }
                z.putNextEntry(e); z.write(content); z.closeEntry()
            }
            put("android-34/", ByteArray(0))
            put("android-34/android.jar", noise(200_000))
            put("android-34/framework.aidl", "parcelable android.os.Bundle;".toByteArray(), stored = true)
            put("android-34/data/api-versions.xml", "<api version=\"3\"/>".toByteArray())
            put("android-34/data/res/values/attrs.xml", noise(50_000))
            put("android-34/android-stubs-src.jar", noise(80_000))
            put("android-34/optional/org.apache.http.legacy.jar", ByteArray(1000))
            put("android-34/skins/WVGA800/layout", ByteArray(100))
        }
        return bytes.toByteArray()
    }

    /** Serves [zip] for every URL; Range requests too unless [ranges] is off. Counts the bytes it sends. */
    private class ZipServer(val zip: ByteArray, val ranges: Boolean, val xmlText: String) : SdkNetFetcher {
        var rangeBytes = 0L
        var fullDownloads = 0
        override fun fetchText(url: String): String = xmlText
        override fun download(url: String, dest: Path, onProgress: (Long, Long) -> Unit): Boolean {
            fullDownloads++
            Files.write(dest, zip); onProgress(zip.size.toLong(), zip.size.toLong()); return true
        }
        override fun openRange(url: String, start: Long, endInclusive: Long): java.io.InputStream? {
            if (!ranges) return null
            val end = minOf(endInclusive, zip.size - 1L)
            rangeBytes += end - start + 1
            return java.io.ByteArrayInputStream(zip, start.toInt(), (end - start + 1).toInt())
        }
    }

    private fun platformPkg(size: Long) =
        AndroidSdkInstaller.parsePackages(xml).first { it.path == "platforms;android-34" }.copy(sizeBytes = size)

    @Test
    fun slimPlatformInstallFetchesOnlyTheFilesTheIdeReads() {
        withTempDir("sdkslim") { tmp ->
            val zip = platformZip()
            val server = ZipServer(zip, ranges = true, xmlText = xml)
            val sdkRoot = tmp.resolve("sdk")
            val err = AndroidSdkInstaller.install(platformPkg(zip.size.toLong()), sdkRoot, tmp.resolve("dl"), server, slim = true)
            assertNull(err)
            val dir = sdkRoot.resolve("platforms/android-34")
            assertEquals(200_000L, Files.size(dir.resolve("android.jar")))
            assertEquals("parcelable android.os.Bundle;", Files.readString(dir.resolve("framework.aidl")))
            assertTrue(Files.isRegularFile(dir.resolve("data/api-versions.xml")))
            assertTrue(Files.isRegularFile(dir.resolve("optional/org.apache.http.legacy.jar")))
            assertFalse(Files.exists(dir.resolve("data/res")))
            assertFalse(Files.exists(dir.resolve("android-stubs-src.jar")))
            assertFalse(Files.exists(dir.resolve("skins")))
            assertEquals(0, server.fullDownloads)
            assertTrue(server.rangeBytes < zip.size, "fetched ${server.rangeBytes} of ${zip.size}")
            assertEquals(setOf("platforms;android-34"), AndroidSdkInstaller.installedPackages(sdkRoot))
            assertEquals(mapOf(34 to dir.resolve("android.jar")), AndroidSdkInstaller.installedPlatforms(sdkRoot))
        }
    }

    @Test
    fun slimInstallFallsBackToTheWholeArchiveWithoutRangeSupport() {
        withTempDir("sdkslim2") { tmp ->
            val zip = platformZip()
            val server = ZipServer(zip, ranges = false, xmlText = xml)
            val sdkRoot = tmp.resolve("sdk")
            assertNull(AndroidSdkInstaller.install(platformPkg(zip.size.toLong()), sdkRoot, tmp.resolve("dl"), server, slim = true))
            assertEquals(1, server.fullDownloads)
            // The whole archive arrived, so everything is installed.
            assertTrue(Files.exists(sdkRoot.resolve("platforms/android-34/data/res/values/attrs.xml")))
        }
    }

    @Test
    fun installedPlatformsPreferThePlainRevisionAndSkipBrokenDirs() {
        withTempDir("sdkplat") { tmp ->
            val platforms = tmp.resolve("platforms")
            fun platform(name: String, jar: Boolean = true) {
                val d = Files.createDirectories(platforms.resolve(name))
                if (jar) Files.write(d.resolve("android.jar"), byteArrayOf(1))
            }
            platform("android-36.1"); platform("android-36"); platform("android-35.1"); platform("android-35.2")
            platform("android-34", jar = false)
            platform("android-33"); Files.createFile(platforms.resolve("android-33.installing"))
            val got = AndroidSdkInstaller.installedPlatforms(tmp)
            assertEquals(mapOf(35 to platforms.resolve("android-35.2/android.jar"), 36 to platforms.resolve("android-36/android.jar")), got)
        }
    }

    @Test
    fun platformToolsInstallLeavesAdbRunnable() {
        assertEquals(AndroidSdkInstaller.Category.PLATFORM_TOOLS, AndroidSdkInstaller.categoryOf("platform-tools"))
        withTempDir("sdktools") { tmp ->
            val sdkRoot = tmp.resolve("sdk")
            val pkg = AndroidSdkInstaller.RepoPackage(
                "platform-tools", "Android SDK Platform-Tools", "36.0.0",
                AndroidSdkInstaller.Category.PLATFORM_TOOLS, "https://example.invalid/pt.zip", 3, "android-sdk-license",
            )
            val fetcher = object : SdkNetFetcher {
                override fun fetchText(url: String): String = xml
                override fun download(url: String, dest: Path, onProgress: (Long, Long) -> Unit): Boolean {
                    ZipOutputStream(Files.newOutputStream(dest)).use { z ->
                        for (name in listOf("platform-tools/adb", "platform-tools/lib64/libc++.so", "platform-tools/NOTICE.txt")) {
                            z.putNextEntry(ZipEntry(name)); z.write(byteArrayOf(1)); z.closeEntry()
                        }
                    }
                    return true
                }
            }

            assertNull(AndroidSdkInstaller.install(pkg, sdkRoot, tmp.resolve("dl"), fetcher))

            val adb = sdkRoot.resolve("platform-tools").resolve("adb")
            assertEquals(adb, AndroidSdkInstaller.adbIn(sdkRoot.resolve("platform-tools")))
            assertTrue("platform-tools" in AndroidSdkInstaller.installedPackages(sdkRoot))
            if (Files.getFileStore(tmp).supportsFileAttributeView("posix")) {
                // A zip carries no Unix modes, so without this adb could not be run.
                assertTrue(Files.isExecutable(adb), "adb is executable after the install")
                assertTrue(!Files.isExecutable(sdkRoot.resolve("platform-tools").resolve("NOTICE.txt")))
            }
        }
    }

    /** A fetcher that "downloads" a one-entry zip (wrapped in a single root dir, as the SDK repo zips are). */
    private fun zipFetcher(entryName: String, content: String) = object : SdkNetFetcher {
        override fun fetchText(url: String): String = xml
        override fun download(url: String, dest: Path, onProgress: (Long, Long) -> Unit): Boolean {
            ZipOutputStream(Files.newOutputStream(dest)).use { z ->
                z.putNextEntry(ZipEntry("android-34/$entryName"))
                z.write(content.toByteArray())
                z.closeEntry()
            }
            onProgress(content.length.toLong(), content.length.toLong())
            return true
        }
    }

    private fun packageElement(path: String): Element {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(xml.byteInputStream())
        val remotes = doc.getElementsByTagNameNS("*", "remotePackage")
        for (i in 0 until remotes.length) {
            val e = remotes.item(i) as Element
            if (e.getAttribute("path") == path) return e
        }
        error("no package $path")
    }
}
