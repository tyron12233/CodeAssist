package dev.ide.core.services

import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.index.AndroidResourceIndex
import dev.ide.android.support.index.ResourceDeclValue
import dev.ide.android.support.preview.AndroidColor
import dev.ide.android.support.preview.ColorEntry
import dev.ide.android.support.preview.ColorResources
import dev.ide.android.support.preview.DrawablePreview
import dev.ide.android.support.preview.DrawablePreviewParser
import dev.ide.android.support.preview.DrawableResolver
import dev.ide.android.support.preview.ResolvedDrawable
import dev.ide.android.support.resources.DrawableXmlCatalog
import dev.ide.android.support.resources.ResourceReferences
import dev.ide.android.support.resources.ResourceRepository
import dev.ide.android.support.resources.ResourceType
import dev.ide.core.EngineContext
import dev.ide.model.Module
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText

/**
 * WORKSPACE-scoped engine service: Android resource navigation + preview, carved out of
 * [dev.ide.core.IdeServices]. Go-to-definition for `@type/name` (res XML) and `R.type.name` (Java) references
 * (resolved purely through the resource index), plus the render-ready drawable model and the `<color>` swatch
 * list for the resource Preview. The `@color`/`@dimen`/`@drawable` reference resolver it needs is private here
 * (nothing else uses it); resource *authoring* and the resource-index feed the XML analyzer also reads stay on
 * the engine.
 */
internal class AndroidResourceService(private val ctx: EngineContext) {

    /** Go-to-definition for the resource reference under [offset] in [file] ([text] = live buffer). */
    fun definitionAt(file: Path, text: String, offset: Int): Pair<Path, Int>? {
        val isXml = file.fileName?.toString()?.endsWith(".xml") == true
        val module = (if (isXml) ctx.moduleForResourceFile(file) else ctx.moduleForFile(file)) ?: return null
        if (module.facets.get(AndroidFacet.KEY) == null) return null
        val (type, name) = (if (isXml) xmlResourceRefAt(text, offset) else rClassRefAt(text, offset)) ?: return null
        // The index carries the precise declaring offset; a resource added/edited in an OPEN res buffer isn't
        // indexed until save, so fall back to the buffer-aware repository (declaring file only, offset 0) so
        // go-to-def still lands in the right file for an as-yet-unsaved declaration.
        return indexDefinition(type, name) ?: repoDefinition(module, type, name)
    }

    /** Buffer-aware go-to-target for an unindexed resource: the declaring file at offset 0 (the repository
     *  tracks the source file per resource, not the precise offset — only the index does). */
    private fun repoDefinition(module: Module, type: ResourceType, name: String): Pair<Path, Int>? =
        ctx.resourceRepo(module)?.definitions(type, name)?.firstNotNullOfOrNull { it.source }?.let { it to 0 }

    /** The local resource reference under [offset] in res XML (`@type/name`), as (type, R-field name). */
    private fun xmlResourceRefAt(text: String, offset: Int): Pair<ResourceType, String>? {
        val ref = ResourceReferences.scan(text).firstOrNull { offset in it.range } ?: return null
        val type = ref.type
        if (!ref.isLocal || ref.create || type == null) return null
        return type to sanitizeResName(ref.name)
    }

    /** An `R.type.name` access under [offset] in Java, as (type, name) — tolerant of where the caret is. */
    private fun rClassRefAt(text: String, offset: Int): Pair<ResourceType, String>? {
        val n = text.length
        if (offset < 0 || offset > n) return null
        fun part(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.' || c == '$'
        var s = offset.coerceIn(0, n)
        var e = offset.coerceIn(0, n)
        while (s > 0 && part(text[s - 1])) s--
        while (e < n && part(text[e])) e++
        val segs = text.substring(s, e).split('.').filter { it.isNotEmpty() }
        val ri = segs.indexOf("R")
        if (ri < 0 || ri + 2 >= segs.size) return null
        return (ResourceType.byRClass(segs[ri + 1]) ?: return null) to segs[ri + 2]
    }

    /** Resolve a resource to its declaration (file + offset) via the resource index, or null. */
    private fun indexDefinition(type: ResourceType, name: String): Pair<Path, Int>? =
        ctx.indexService.exact<ResourceDeclValue>(
            AndroidResourceIndex.id, AndroidResourceIndex.key(type.rClass, name)
        ).firstOrNull()?.let { Paths.get(it.filePath) to it.offset }

    /**
     * A render-ready model of the drawable XML in [file] ([text] is the live buffer), with every
     * `@color`/`@dimen`/`@drawable` reference resolved against the module's merged resources. Null for a
     * non-Android module or a file that isn't a drawable/color/mipmap resource.
     */
    fun drawablePreview(file: Path, text: String): DrawablePreview? {
        if (!DrawableXmlCatalog.appliesTo(file.toString())) return null
        val module = ctx.moduleForResourceFile(file) ?: return null
        if (module.facets.get(AndroidFacet.KEY) == null) return null
        return runCatching { DrawablePreviewParser.parse(text, drawableResolver(module)) }.getOrNull()
    }

    /** The `<color>` entries of a `res/values` XML [file] ([text] = live buffer), resolved to ARGB for swatches. */
    fun colorResources(file: Path, text: String): List<ColorEntry> {
        val resolver = ctx.moduleForResourceFile(file)?.let { drawableResolver(it) } ?: DrawableResolver.NONE
        return runCatching { ColorResources.parse(text, resolver) }.getOrDefault(emptyList())
    }

    /** Raw bytes of a resource file (for bitmap preview); null if unreadable. */
    fun resourceBytes(file: Path): ByteArray? = runCatching { Files.readAllBytes(file) }.getOrNull()

    private fun drawableResolver(module: Module): DrawableResolver {
        val repo = ctx.resourceRepo(module) ?: return DrawableResolver.NONE
        return object : DrawableResolver {
            override fun resolveColor(ref: String): Long? = resolveColorRef(ref, repo, 0)

            override fun resolveDimenDp(ref: String): Float? {
                val name = sanitizeResName(ref.substringAfterLast('/'))
                val v = repo.definitions(ResourceType.DIMEN, name).firstOrNull()?.value ?: return null
                return DIMEN_LITERAL.find(v)?.groupValues?.get(1)?.toFloatOrNull()
            }

            override fun resolveDrawable(ref: String): ResolvedDrawable? {
                val name = sanitizeResName(ref.substringAfterLast('/'))
                // A @color used where a drawable is expected resolves to a flat fill — let the color path handle it.
                if (ref.contains("color") && repo.has(ResourceType.COLOR, name)) return null
                val item = repo.definitions(ResourceType.DRAWABLE, name).firstOrNull()
                    ?: repo.definitions(ResourceType.MIPMAP, name).firstOrNull() ?: return null
                val src = item.source ?: return null
                val p = src.toString()
                return if (p.endsWith(".xml")) {
                    runCatching { src.readText() }.getOrNull()?.let { ResolvedDrawable.Xml(it) }
                } else {
                    ResolvedDrawable.BitmapFile(item.type.rClass, name, p)
                }
            }
        }
    }

    /** Resolve `@color/x` (transitively through `@color` indirection) to ARGB; `@android:color/x` via the table. */
    private fun resolveColorRef(ref: String, repo: ResourceRepository, depth: Int): Long? {
        if (depth > 8) return null
        val raw = ref.trim()
        if (raw.startsWith("#")) return AndroidColor.parseHex(raw)
        if (raw.contains("android:")) return AndroidColor.framework(raw.substringAfterLast('/'))
        if (!raw.startsWith("@")) return null
        val name = sanitizeResName(raw.substringAfterLast('/'))
        val v = repo.definitions(ResourceType.COLOR, name).firstOrNull()?.value ?: return null
        return when {
            v.startsWith("#") -> AndroidColor.parseHex(v)
            v.startsWith("@") -> resolveColorRef(v, repo, depth + 1)
            else -> null
        }
    }

    private fun sanitizeResName(s: String): String = s.replace('.', '_').replace('-', '_').trim()

    private val DIMEN_LITERAL = Regex("""(-?\d+(?:\.\d+)?)""")
}
