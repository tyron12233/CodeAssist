package dev.ide.store.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SupabasePluginDownloadsTest {
    private val service = SupabasePluginDownloads(url = "", apiKey = "")

    @Test
    fun aRowBecomesAPluginDownload() {
        val body = """[{"id":"dev.codeassist.ndk","name":"NDK (C/C++)","tagline":"C and C++","description":"Compiles C.",
            "publisher":"CodeAssist","version":"1.3.0","min_host_version":"3.21.0","size_bytes":47354399,
            "download_url":"https://github.com/x/releases/download/v3.35.1/ndk.apk","page_url":"https://github.com/x/releases/tag/v3.35.1",
            "icon_url":null,"capabilities":["fs.read","lang.backend"],"languages":"C/C++","sort":10,"visible":true}]"""
        val d = service.parse(body).single()
        assertEquals("dev.codeassist.ndk", d.id)
        assertEquals("1.3.0", d.version)
        assertEquals("3.21.0", d.minHostVersion)
        assertEquals(47354399L, d.sizeBytes)
        assertEquals(listOf("fs.read", "lang.backend"), d.capabilities)
        assertEquals("C/C++", d.languages)
        assertNull(d.iconUrl)
    }

    @Test
    fun aRowWithoutAnHttpsDownloadIsDropped() {
        // The page opens the link in a browser; anything but https is not something to offer.
        val body = """[{"id":"a","name":"A","version":"1.0","download_url":"http://example.com/a.apk"},
            {"id":"b","name":"B","version":"1.0"},
            {"id":"c","name":"C","version":"1.0","download_url":"https://example.com/c.apk","page_url":"javascript:alert(1)"}]"""
        val plugins = service.parse(body)
        assertEquals(listOf("c"), plugins.map { it.id })
        assertNull(plugins.single().pageUrl)
    }

    @Test
    fun unconfiguredReportsUnavailable() {
        assertEquals(false, service.available())
        assertEquals(true, service.fetch() is dev.ide.store.StoreResult.Unavailable)
    }

    /** The row the NDK plugin has once it is built per ABI: the device's own build, else the plain link. */
    private val perAbiRow = """[{"id":"dev.codeassist.ndk","name":"NDK","version":"1.4.0","size_bytes":100,
        "download_url":"https://example.com/ndk-arm64.apk",
        "abi_downloads":{"arm64-v8a":{"url":"https://example.com/ndk-arm64.apk","size":100},
                         "x86_64":{"url":"https://example.com/ndk-x86_64.apk","size":200},
                         "armeabi-v7a":{"url":"http://example.com/not-https.apk","size":300}}}]"""

    @Test
    fun aDeviceGetsTheBuildForItsFirstListedAbi() {
        val x86 = SupabasePluginDownloads(url = "", apiKey = "", deviceAbis = listOf("x86_64", "x86", "arm64-v8a"))
        val d = x86.parse(perAbiRow).single()
        assertEquals("https://example.com/ndk-x86_64.apk", d.downloadUrl)
        assertEquals(200L, d.sizeBytes)
    }

    @Test
    fun aDeviceWithNoListedAbiGetsThePlainLink() {
        // armeabi-v7a is listed, but not over https, so it is not offered; nor is x86, which is absent.
        val arm32 = SupabasePluginDownloads(url = "", apiKey = "", deviceAbis = listOf("armeabi-v7a", "x86"))
        val d = arm32.parse(perAbiRow).single()
        assertEquals("https://example.com/ndk-arm64.apk", d.downloadUrl)
        assertEquals(100L, d.sizeBytes)
        // And off a device (no ABIs), the plain link too.
        assertEquals("https://example.com/ndk-arm64.apk", service.parse(perAbiRow).single().downloadUrl)
    }
}
