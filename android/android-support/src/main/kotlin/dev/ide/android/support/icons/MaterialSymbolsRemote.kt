package dev.ide.android.support.icons

import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * The HTTP the icon repositories need: one GET returning bytes, or null on any failure. Injectable so the
 * repositories are testable without a network, and so a host can route them through its own client.
 */
fun interface IconHttp {

    fun get(url: String): ByteArray?

    companion object {
        /** `HttpURLConnection`, which is available unchanged on both the desktop JVM and ART. */
        val DEFAULT = IconHttp { url ->
            runCatching {
                val conn = URI(url).toURL().openConnection() as HttpURLConnection
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.setRequestProperty("User-Agent", "CodeAssist-IconManager")
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    conn.inputStream.use { it.readBytes() }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}

/**
 * The full Material Symbols set, fetched from `google/material-design-icons` on demand.
 *
 * Nothing is downloaded until [load] is called, which the picker only does when the user asks to reach past
 * the bundled subset. [load] fetches one small index (the variable font's codepoints listing, which is the
 * cheapest complete list of icon names upstream publishes) and each icon's SVG is then fetched the first time
 * it is shown. Both land in [cacheDir], so a second visit is offline and a re-install is the only thing that
 * costs the download again.
 *
 * Icons arrive as SVG and go through [SvgToVectorDrawable] like any other import, so the same conversion is
 * exercised by every path into the Icon Manager rather than a second, subtly different one.
 */
class MaterialSymbolsRemote(
    private val cacheDir: Path,
    private val http: IconHttp = IconHttp.DEFAULT,
) : IconRepository {

    override val id: String get() = ID
    override val displayName: String get() = "Material Symbols (all)"
    override val license: String get() = "Apache-2.0"
    override val attribution: String get() = "Google Material Symbols"
    override val requiresNetwork: Boolean get() = true

    @Volatile
    private var loaded: List<IconEntry> = emptyList()

    override fun entries(): List<IconEntry> = loaded

    override fun load(): Result<Unit> {
        if (loaded.isNotEmpty()) return Result.success(Unit)
        val bytes = cached(indexCache()) { http.get(INDEX_URL) }
            ?: return Result.failure(IllegalStateException("Could not download the Material Symbols index"))
        val names = parseIndex(bytes.decodeToString())
        if (names.isEmpty()) return Result.failure(IllegalStateException("The Material Symbols index was empty"))
        loaded = names.map { name ->
            IconEntry(
                repositoryId = ID,
                name = name,
                displayName = BundledMaterialIcons.humanize(name),
                styles = ALL_STYLES,
                supportsFill = true,
            )
        }
        return Result.success(Unit)
    }

    override fun artwork(entry: IconEntry, variant: IconVariant): IconArtwork? {
        val svg = cached(svgCache(entry.name, variant)) { http.get(svgUrl(entry.name, variant)) } ?: return null
        val converted = SvgToVectorDrawable.toSpec(svg.decodeToString()) ?: return null
        return IconArtwork(converted.spec, converted.warnings)
    }

    /**
     * The codepoints listing is `"<name> <hex>"` per line. Only the names matter here; the codepoints are for
     * font rendering, which is not how a drawable gets built.
     */
    internal fun parseIndex(text: String): List<String> = text.lineSequence()
        .mapNotNull { line ->
            val name = line.trim().substringBefore(' ').trim()
            name.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' } }
        }
        .distinct()
        .toList()

    private fun svgUrl(name: String, variant: IconVariant): String {
        val family = "materialsymbols" + variant.style.name.lowercase()
        val fill = if (variant.filled) "_fill1" else ""
        return "$SYMBOLS_BASE/$name/$family/$name${fill}_24px.svg"
    }

    private fun indexCache(): Path = cacheDir.resolve("material-symbols/index.codepoints")

    private fun svgCache(name: String, variant: IconVariant): Path {
        val family = variant.style.name.lowercase() + if (variant.filled) "-fill" else ""
        return cacheDir.resolve("material-symbols/$family/$name.svg")
    }

    /**
     * [file]'s contents, downloading through [fetch] and storing them the first time. A cache write that
     * fails (no space, read-only storage) still returns the downloaded bytes: the download is what the caller
     * asked for, and caching is only an optimisation.
     */
    private fun cached(file: Path, fetch: () -> ByteArray?): ByteArray? {
        if (file.exists()) runCatching { file.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val bytes = fetch() ?: return null
        runCatching {
            file.createParentDirectories()
            // Write beside the target and move it into place, so an interrupted download can't be cached.
            val tmp = file.resolveSibling(file.fileName.toString() + ".part")
            tmp.writeBytes(bytes)
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        return bytes
    }

    companion object {
        const val ID = "material-symbols-remote"

        private val ALL_STYLES = setOf(IconStyle.OUTLINED, IconStyle.ROUNDED, IconStyle.SHARP)

        private const val RAW = "https://raw.githubusercontent.com/google/material-design-icons/master"
        private const val SYMBOLS_BASE = "$RAW/symbols/web"

        /** The variable font's codepoints listing: one request for every icon name upstream ships. */
        const val INDEX_URL =
            "$RAW/variablefont/MaterialSymbolsOutlined%5BFILL%2CGRAD%2Copsz%2Cwght%5D.codepoints"
    }
}
