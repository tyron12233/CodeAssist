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
}
