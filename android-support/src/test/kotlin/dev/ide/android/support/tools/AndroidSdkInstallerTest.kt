package dev.ide.android.support.tools

import org.w3c.dom.Element
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidSdkInstallerTest {

    private val xml = """
        <sdk:sdk-repository xmlns:sdk="http://schemas.android.com/sdk/android/repo/repository2/03"
                            xmlns:common="http://schemas.android.com/repository/android/common/02">
          <remotePackage path="platforms;android-34">
            <display-name>Android SDK Platform 34</display-name>
            <revision><major>3</major></revision>
            <archives>
              <archive><complete><size>1000</size><url>platform-34_r03.zip</url></complete></archive>
            </archives>
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
    fun parsesAndFiltersPackages() {
        val pkgs = AndroidSdkInstaller.parsePackages(xml)
        val paths = pkgs.map { it.path }.toSet()
        assertEquals(setOf("platforms;android-34", "build-tools;34.0.0", "sources;android-34"), paths) // extras filtered out

        val platform = pkgs.first { it.path == "platforms;android-34" }
        assertEquals(AndroidSdkInstaller.Category.PLATFORM, platform.category)
        assertEquals("3", platform.revision)
        assertTrue(platform.archiveUrl!!.endsWith("platform-34_r03.zip"), platform.archiveUrl!!)
        assertTrue(platform.archiveUrl!!.startsWith(AndroidSdkInstaller.REPO_BASE))

        val sources = pkgs.first { it.path == "sources;android-34" }
        assertEquals(AndroidSdkInstaller.Category.SOURCES, sources.category)
        assertEquals(20L, sources.sizeBytes)
    }

    @Test
    fun installDirMapsIdToPath() {
        val pkgs = AndroidSdkInstaller.parsePackages(xml)
        val dir = pkgs.first { it.path == "build-tools;34.0.0" }.installDir(Path.of("/sdk"))
        assertEquals(Path.of("/sdk", "build-tools", "34.0.0"), dir)
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
    fun categoryOfClassifies() {
        assertEquals(AndroidSdkInstaller.Category.PLATFORM, AndroidSdkInstaller.categoryOf("platforms;android-34"))
        assertEquals(AndroidSdkInstaller.Category.BUILD_TOOLS, AndroidSdkInstaller.categoryOf("build-tools;34.0.0"))
        assertEquals(AndroidSdkInstaller.Category.SOURCES, AndroidSdkInstaller.categoryOf("sources;android-34"))
        assertEquals(AndroidSdkInstaller.Category.CMDLINE_TOOLS, AndroidSdkInstaller.categoryOf("cmdline-tools;latest"))
        assertEquals(AndroidSdkInstaller.Category.OTHER, AndroidSdkInstaller.categoryOf("ndk;26.0.0"))
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
