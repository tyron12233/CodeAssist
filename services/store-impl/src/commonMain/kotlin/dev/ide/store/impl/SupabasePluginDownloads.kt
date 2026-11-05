package dev.ide.store.impl

import dev.ide.platform.JsonReader
import dev.ide.store.PluginDownload
import dev.ide.store.PluginDownloadService
import dev.ide.store.StoreResult
import dev.ide.store.impl.platform.StoreHttp

/**
 * The downloadable plugins, from the `plugin_catalog` table (supabase/migrations/20261105000100). Reads only,
 * with the publishable key: row-level security serves the visible rows and nothing else.
 *
 * A plugin that carries native code per ABI (the NDK plugin's compiler) lists one APK per ABI in
 * `abi_downloads` (`{"arm64-v8a": {"url": ..., "size": ...}}`, migration 20261105000200). The row is offered as
 * the build for the first of [deviceAbis] it has, in the device's preference order, and as its plain
 * `download_url` (which older apps read) when it has none of them.
 */
class SupabasePluginDownloads(
    url: String,
    private val apiKey: String,
    connectTimeoutMs: Int = 10_000,
    readTimeoutMs: Int = 15_000,
    /** This device's ABIs, most preferred first (Android's `Build.SUPPORTED_ABIS`); empty off a device. */
    private val deviceAbis: List<String> = emptyList(),
) : PluginDownloadService {
    private val http = StoreHttp(connectTimeoutMs, readTimeoutMs)
    private val base = url.trimEnd('/')
    private val configured = url.isNotBlank() && apiKey.isNotBlank()

    override fun available(): Boolean = configured

    override fun fetch(): StoreResult<String> {
        if (!configured) return StoreResult.Unavailable("Plugin downloads are not configured in this build")
        val r = http.request(
            method = "GET",
            url = "$base/rest/v1/plugin_catalog?select=*&order=sort.asc,name.asc",
            headers = mapOf("apikey" to apiKey, "Authorization" to "Bearer $apiKey", "Accept" to "application/json"),
        )
        if (r.error != null) return StoreResult.Unavailable(r.error)
        if (r.status !in 200..299) return StoreResult.Failed("Could not load the plugin list", r.status)
        return StoreResult.Ok(r.body)
    }

    override fun parse(body: String): List<PluginDownload> =
        JsonReader.arr(JsonReader.parseOrNull(body)).mapNotNull { row ->
            val id = JsonReader.str(row, "id") ?: return@mapNotNull null
            val perAbi = forThisDevice(JsonReader.obj(row)?.get("abi_downloads"))
            val download = perAbi?.first
                ?: JsonReader.str(row, "download_url")?.takeIf { it.startsWith("https://") }
                ?: return@mapNotNull null
            PluginDownload(
                id = id,
                name = JsonReader.str(row, "name") ?: id,
                tagline = JsonReader.str(row, "tagline").orEmpty(),
                description = JsonReader.str(row, "description").orEmpty(),
                publisher = JsonReader.str(row, "publisher").orEmpty(),
                version = JsonReader.str(row, "version").orEmpty(),
                minHostVersion = JsonReader.str(row, "min_host_version"),
                sizeBytes = (if (perAbi != null) perAbi.second else JsonReader.long(row, "size_bytes")).takeIf { it > 0 },
                downloadUrl = download,
                pageUrl = JsonReader.str(row, "page_url")?.takeIf { it.startsWith("https://") },
                iconUrl = JsonReader.str(row, "icon_url")?.takeIf { it.startsWith("https://") },
                capabilities = JsonReader.strings(row, "capabilities"),
                languages = JsonReader.str(row, "languages").orEmpty(),
            )
        }

    /** The https URL and size of the build for the first of [deviceAbis] that [abiDownloads] lists, or null. */
    private fun forThisDevice(abiDownloads: Any?): Pair<String, Long>? {
        val table = JsonReader.obj(abiDownloads) ?: return null
        for (abi in deviceAbis) {
            val entry = table[abi] ?: continue
            val url = JsonReader.str(entry, "url")?.takeIf { it.startsWith("https://") } ?: continue
            return url to JsonReader.long(entry, "size")
        }
        return null
    }
}
