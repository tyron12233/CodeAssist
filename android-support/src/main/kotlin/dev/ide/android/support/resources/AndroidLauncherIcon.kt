package dev.ide.android.support.resources

import dev.ide.android.support.preview.DrawablePreview
import dev.ide.android.support.preview.DrawablePreviewParser
import dev.ide.android.support.preview.DrawableResolver
import dev.ide.android.support.preview.ResourceDrawableResolver
import java.nio.file.Path
import kotlin.io.path.readText

/** A resolved launcher icon: either a raster image file, or a render-ready drawable model (vector/layers). */
sealed interface LauncherIcon {
    /** A bitmap file (PNG/WebP/…) the host decodes and draws. */
    data class Raster(val path: Path) : LauncherIcon

    /** A parsed drawable (vector / layer-list / adaptive-icon) the host renders on a canvas. */
    data class Drawable(val preview: DrawablePreview) : LauncherIcon
}

/**
 * Resolves an Android module's launcher icon for previewing (e.g. in the project picker), from the manifest's
 * `android:icon`/`android:roundIcon` references and the module's own `res/` roots.
 *
 * Prefers the **densest raster** when one exists (the actual launcher appearance, and what modern projects
 * ship as the adaptive-icon's pre-26 fallback); otherwise parses the **drawable XML** (a `<vector>`,
 * `<layer-list>`, or `<adaptive-icon>`, which is how the default templates ship their icon, vector-only) into
 * a render-ready model. Framework refs (`@android:…`) are skipped; falls back to the conventional
 * `ic_launcher` name when the manifest has no usable reference.
 */
object AndroidLauncherIcon {

    /** The launcher icon for [resRoots], resolving [iconRef] then [roundIconRef] then `ic_launcher`, or null. */
    fun locate(resRoots: List<Path>, iconRef: String?, roundIconRef: String?): LauncherIcon? {
        if (resRoots.isEmpty()) return null
        val repo = runCatching { ResourceModel.DEFAULT.parse(resRoots) }.getOrNull() ?: return null
        if (repo.isEmpty()) return null
        val resolver = ResourceDrawableResolver.of(repo)

        val candidates = listOfNotNull(iconRef?.let(::parseRef), roundIconRef?.let(::parseRef))
            .ifEmpty { listOf(ResourceType.MIPMAP to "ic_launcher", ResourceType.DRAWABLE to "ic_launcher") }
        for ((type, name) in candidates) {
            resolveOne(repo, resolver, type, name)?.let { return it }
        }
        return null
    }

    private fun resolveOne(
        repo: ResourceRepository,
        resolver: DrawableResolver,
        type: ResourceType,
        name: String,
    ): LauncherIcon? {
        val other = if (type == ResourceType.MIPMAP) ResourceType.DRAWABLE else ResourceType.MIPMAP
        val defs = repo.definitions(type, name) + repo.definitions(other, name)
        if (defs.isEmpty()) return null

        // 1) The densest raster, if any (a launcher icon's true rendered appearance).
        defs.filter { it.source?.isRaster() == true }
            .maxByOrNull { densityRank(it.qualifier) }
            ?.source
            ?.let { return LauncherIcon.Raster(it) }

        // 2) Otherwise the first XML drawable that actually parses (skipping anything Unsupported).
        for (def in defs) {
            val src = def.source ?: continue
            if (src.isRaster()) continue
            val text = runCatching { src.readText() }.getOrNull() ?: continue
            val preview = DrawablePreviewParser.parse(text, resolver)
            if (preview !is DrawablePreview.Unsupported) return LauncherIcon.Drawable(preview)
        }
        return null
    }

    /** `@mipmap/ic_launcher` → MIPMAP to `ic_launcher`; null for framework refs or non-resource strings. */
    private fun parseRef(ref: String): Pair<ResourceType, String>? {
        val r = ref.trim()
        if (!r.startsWith("@") || r.contains(':')) return null // skip "@android:..." framework references
        val body = r.removePrefix("@")
        val type = ResourceType.fromFolder(body.substringBefore('/', "")) ?: return null
        val name = body.substringAfter('/', "").ifEmpty { return null }
        return type to name
    }

    private fun Path.isRaster(): Boolean = !toString().endsWith(".xml", ignoreCase = true)

    /** Prefer denser buckets for a crisp icon (xxxhdpi → … → unqualified); `anydpi`/`nodpi` rank lowest. */
    private fun densityRank(qualifier: String): Int = when {
        qualifier.contains("xxxhdpi") -> 6
        qualifier.contains("xxhdpi") -> 5
        qualifier.contains("xhdpi") -> 4
        qualifier.contains("hdpi") -> 3
        qualifier.contains("mdpi") -> 2
        qualifier.contains("ldpi") -> 1
        else -> 0
    }
}
