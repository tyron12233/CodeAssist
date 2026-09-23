package dev.ide.preview

import kotlin.math.max
import kotlin.math.min

/**
 * The device configuration a preview render resolves resources under: the frame size in dp plus the
 * configuration axes that select between qualified resource folders (`layout-land`, `values-night`,
 * `layout-sw600dp`). [landscape] follows the dp pair, the way the framework derives
 * `Configuration.orientation` from the screen size.
 */
data class PreviewConfig(
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val smallestWidthDp: Int,
    val night: Boolean,
    val rtl: Boolean,
) {
    val landscape: Boolean get() = screenWidthDp > screenHeightDp
}

/**
 * Reads an Android resource folder name (`layout`, `layout-land`, `values-night-v21`) into the configuration
 * axes a preview can honor, and folds them onto the previewed device frame.
 *
 * A real-view render resolves the previewed layout by resource NAME, so the resource table picks whichever
 * variant matches the render configuration. Folding the opened file's own qualifiers onto the frame is what
 * makes `res/layout-land/main.xml` render as itself rather than as the unqualified `res/layout/main.xml`.
 *
 * Qualifiers that a preview cannot simulate (API level, locale, screen type) are ignored rather than
 * rejected: the configuration keeps the host's value for those axes, which still selects the opened file.
 */
object ResourceQualifiers {

    /** The axes a folder name pins. A null field means the folder says nothing about that axis. */
    data class Overrides(
        val landscape: Boolean? = null,
        val night: Boolean? = null,
        val screenWidthDp: Int? = null,
        val screenHeightDp: Int? = null,
        val smallestWidthDp: Int? = null,
        val rtl: Boolean? = null,
    )

    private val SMALLEST_WIDTH = Regex("^sw(\\d+)dp$")
    private val WIDTH = Regex("^w(\\d+)dp$")
    private val HEIGHT = Regex("^h(\\d+)dp$")

    /** Parse a res folder name's qualifiers; the leading type segment (`layout`, `values`) is skipped. */
    fun parse(folderName: String): Overrides {
        var out = Overrides()
        for (q in folderName.lowercase().split('-').drop(1)) {
            val sw = SMALLEST_WIDTH.matchEntire(q)?.groupValues?.get(1)?.toIntOrNull()
            val w = WIDTH.matchEntire(q)?.groupValues?.get(1)?.toIntOrNull()
            val h = HEIGHT.matchEntire(q)?.groupValues?.get(1)?.toIntOrNull()
            out = when {
                q == "land" -> out.copy(landscape = true)
                q == "port" -> out.copy(landscape = false)
                q == "night" -> out.copy(night = true)
                q == "notnight" -> out.copy(night = false)
                q == "ldrtl" -> out.copy(rtl = true)
                q == "ldltr" -> out.copy(rtl = false)
                // The screen-size buckets that predate `swNNNdp`, as their smallest-width floors.
                q == "large" -> out.copy(smallestWidthDp = max(out.smallestWidthDp ?: 0, 480))
                q == "xlarge" -> out.copy(smallestWidthDp = max(out.smallestWidthDp ?: 0, 720))
                sw != null -> out.copy(smallestWidthDp = max(out.smallestWidthDp ?: 0, sw))
                w != null -> out.copy(screenWidthDp = max(out.screenWidthDp ?: 0, w))
                h != null -> out.copy(screenHeightDp = max(out.screenHeightDp ?: 0, h))
                else -> out
            }
        }
        return out
    }

    /** Parse the qualifiers of the res folder holding [path] (`…/res/layout-land/main.xml`). */
    fun parseFile(path: String): Overrides = parse(folderOf(path))

    /**
     * The configuration to render [path] under: the device frame ([frameWidthDp] x [frameHeightDp] dp,
     * [night]) with the file's own folder qualifiers folded on top. A `-land` file rotates a portrait frame,
     * and a `-swNNNdp` / `-wNNNdp` / `-hNNNdp` file widens it to the size the qualifier requires (the frame
     * is never shrunk, so a qualifier the frame already satisfies leaves it alone).
     */
    fun forFile(
        path: String,
        frameWidthDp: Int,
        frameHeightDp: Int,
        night: Boolean,
        rtl: Boolean = false,
    ): PreviewConfig {
        val q = parseFile(path)
        var w = frameWidthDp.coerceAtLeast(1)
        var h = frameHeightDp.coerceAtLeast(1)
        if (q.landscape != null && q.landscape != (w > h)) {
            val turned = w; w = h; h = turned
        }
        q.screenWidthDp?.let { w = max(w, it) }
        q.screenHeightDp?.let { h = max(h, it) }
        q.smallestWidthDp?.let { w = max(w, it); h = max(h, it) }
        return PreviewConfig(w, h, min(w, h), q.night ?: night, q.rtl ?: rtl)
    }

    private fun folderOf(path: String): String =
        path.replace('\\', '/').substringBeforeLast('/').substringAfterLast('/')
}
