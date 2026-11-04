package dev.ide.store

/**
 * One plugin offered on the "Get plugins" page: where its APK is, and what to say about it before anyone
 * downloads it. A row of the backend's `plugin_catalog` table.
 */
data class PluginDownload(
    /** The plugin's manifest id, which is also how an installed copy is recognised. */
    val id: String,
    val name: String,
    val tagline: String = "",
    val description: String = "",
    val publisher: String = "",
    val version: String,
    /** The oldest CodeAssist the plugin loads in, or null for any. */
    val minHostVersion: String? = null,
    val sizeBytes: Long? = null,
    /** An https link to the APK. The app opens it in the browser; it never fetches or runs it itself. */
    val downloadUrl: String,
    /** The release page (notes, the APK among the other assets), when there is one. */
    val pageUrl: String? = null,
    val iconUrl: String? = null,
    /** What its manifest declares it does, so the page can say so before download. */
    val capabilities: List<String> = emptyList(),
    /** A short label for what it is for ("C/C++", "Dart · Flutter"). */
    val languages: String = "",
)

/**
 * The downloadable plugins. [fetch] answers the list as the server sent it, raw, so the caller can keep it as
 * the offline copy and read it back with [parse] when there is no network.
 */
interface PluginDownloadService {
    fun available(): Boolean = false

    fun fetch(): StoreResult<String> = StoreResult.Unavailable("Plugin downloads are not configured in this build")

    fun parse(body: String): List<PluginDownload> = emptyList()

    object Unsupported : PluginDownloadService
}
