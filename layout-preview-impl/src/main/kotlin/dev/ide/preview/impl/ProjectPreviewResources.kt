package dev.ide.preview.impl

import dev.ide.android.support.preview.AndroidColor
import dev.ide.android.support.preview.DrawablePreview
import dev.ide.android.support.preview.DrawablePreviewParser
import dev.ide.android.support.preview.DrawableResolver
import dev.ide.android.support.preview.ResolvedDrawable
import dev.ide.android.support.resources.ResourceRepository
import dev.ide.android.support.resources.ResourceType
import dev.ide.preview.PreviewResources
import dev.ide.preview.RImage
import dev.ide.preview.ResolvedValue
import dev.ide.preview.ValueFormat
import kotlin.io.path.readText

/**
 * [PreviewResources] backed by a module's merged [ResourceRepository] and the previewed device density.
 * Parses literal colors/dimensions and resolves `@type/name` references (recursively, so `@color/a → @color/b
 * → #fff` collapses to an ARGB), framework `@android:color/…` via the small builtin table, and `@drawable/…`
 * to a loaded image through the injected [imageLoader] (image decoding is platform-specific). Theme `?attr/…`
 * references are not yet resolved (returns null — the renderer falls back to its default).
 */
class ProjectPreviewResources(
    private val repo: ResourceRepository,
    val density: Float = 1f,
    private val scaledDensity: Float = density,
    private val imageLoader: (resType: String, name: String, file: String?) -> RImage? = { _, _, _ -> null },
    /** When true, prefer `-night`-qualified resource values (dark-theme preview). */
    private val night: Boolean = false,
) : PreviewResources {

    /** The config-appropriate definition of `type/name` — `-night` first when [night], default otherwise. */
    private fun definition(type: ResourceType, name: String): dev.ide.android.support.resources.ResourceItem? {
        val defs = repo.definitions(type, name).filter { it.value != null }
        if (defs.isEmpty()) return null
        return if (night) defs.firstOrNull { it.qualifier.contains("night") } ?: defs.firstOrNull { it.qualifier.isEmpty() } ?: defs.first()
        else defs.firstOrNull { it.qualifier.isEmpty() } ?: defs.first()
    }

    override fun resolve(raw: String, format: ValueFormat): ResolvedValue? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("@")) return resolveRef(s, format)
        if (s.startsWith("?")) return null // theme attribute — not yet modelled
        return parseLiteral(s, format)
    }

    override fun image(ref: String): RImage? {
        val parsed = parseRef(ref) ?: return null
        if (parsed.pkg == "android") return null
        val item = repo.definitions(parsed.type, parsed.name).firstOrNull()
        return imageLoader(parsed.type.rClass, parsed.name, item?.source?.toString())
    }

    /** The backing file path of a `@drawable/@mipmap` reference, for the UI layer to decode; null if unknown. */
    fun imageFilePath(ref: String): String? {
        val parsed = parseRef(ref) ?: return null
        if (parsed.pkg == "android") return null
        return repo.definitions(parsed.type, parsed.name).firstOrNull()?.source?.toString()
    }

    /** Flatten a `<style>`/theme (by raw dotted name) + its parent chain into a single item map (derived wins). */
    fun styleItems(styleName: String): Map<String, String> {
        val chain = ArrayList<String>()
        var cur: String? = styleName
        var guard = 0
        val seen = HashSet<String>()
        while (cur != null && guard++ < 32 && seen.add(cur)) {
            chain.add(cur)
            val s = repo.style(cur)
            cur = s?.parent?.removePrefix("@style/") ?: if ('.' in cur) cur.substringBeforeLast('.') else null
        }
        val out = LinkedHashMap<String, String>()
        for (name in chain.asReversed()) repo.style(name)?.items?.let { out.putAll(it) }
        return out
    }

    /** Parse a `@drawable/@mipmap` background reference into a [DrawablePreview] (XML shapes/layers/etc.). */
    fun backgroundDrawable(ref: String): DrawablePreview? {
        val parsed = parseRef(ref) ?: return null
        if (parsed.pkg == "android") return null
        if (parsed.type != ResourceType.DRAWABLE && parsed.type != ResourceType.MIPMAP) return null
        val src = repo.definitions(parsed.type, parsed.name).firstOrNull()?.source ?: return null
        val path = src.toString()
        if (!path.endsWith(".xml")) return DrawablePreview.BitmapRef(parsed.type.rClass, parsed.name, path)
        val text = runCatching { src.readText() }.getOrNull() ?: return null
        return runCatching { DrawablePreviewParser.parse(text, drawableResolver) }.getOrNull()
    }

    /** A [DrawableResolver] over this repository, so a background shape's `@color`/`@dimen`/nested refs resolve. */
    private val drawableResolver = object : DrawableResolver {
        override fun resolveColor(ref: String): Long? =
            (resolve(ref, ValueFormat.COLOR) as? ResolvedValue.Color)?.argb?.toLong()?.and(0xFFFFFFFFL)

        override fun resolveDimenDp(ref: String): Float? =
            (resolve(ref, ValueFormat.DIMENSION) as? ResolvedValue.Dimension)?.px?.let { if (density != 0f) it / density else it }

        override fun resolveDrawable(ref: String): ResolvedDrawable? {
            val p = parseRef(ref) ?: return null
            val src = repo.definitions(p.type, p.name).firstOrNull()?.source ?: return null
            val path = src.toString()
            return if (path.endsWith(".xml")) runCatching { src.readText() }.getOrNull()?.let { ResolvedDrawable.Xml(it) }
            else ResolvedDrawable.BitmapFile(p.type.rClass, p.name, path)
        }
    }

    private fun parseLiteral(s: String, format: ValueFormat): ResolvedValue? = when (format) {
        ValueFormat.COLOR -> AndroidColor.parseHex(s)?.let { ResolvedValue.Color(it.toInt()) }
        ValueFormat.DIMENSION -> ResolvedValue.Dimension(parseDimensionPx(s))
        ValueFormat.BOOLEAN -> ResolvedValue.BoolV(s.equals("true", ignoreCase = true))
        ValueFormat.INTEGER -> s.toIntOrNull()?.let { ResolvedValue.IntV(it) }
        ValueFormat.FLOAT -> s.toFloatOrNull()?.let { ResolvedValue.FloatV(it) }
        ValueFormat.STRING, ValueFormat.ENUM, ValueFormat.FLAG, ValueFormat.REFERENCE -> ResolvedValue.Str(s)
        ValueFormat.ANY -> when {
            s.startsWith("#") -> AndroidColor.parseHex(s)?.let { ResolvedValue.Color(it.toInt()) } ?: ResolvedValue.Str(s)
            DIMEN.matches(s) -> ResolvedValue.Dimension(parseDimensionPx(s))
            s == "true" || s == "false" -> ResolvedValue.BoolV(s == "true")
            else -> ResolvedValue.Str(s)
        }
    }

    private fun resolveRef(raw: String, format: ValueFormat, depth: Int = 0): ResolvedValue? {
        if (depth > 16) return null
        val ref = parseRef(raw) ?: return null
        if (ref.pkg == "android") {
            return if (ref.type == ResourceType.COLOR) AndroidColor.framework(ref.name)?.let { ResolvedValue.Color(it.toInt()) }
            else null
        }
        // File resources resolve to a reference the renderer loads through image(); not a scalar.
        if (ref.type == ResourceType.DRAWABLE || ref.type == ResourceType.MIPMAP) {
            return ResolvedValue.Ref(ref.type.rClass, ref.name)
        }
        val value = definition(ref.type, ref.name)?.value ?: return null
        // The looked-up value may itself be a literal or another reference — resolve transitively.
        return when (ref.type) {
            ResourceType.COLOR -> if (value.startsWith("@")) resolveRef(value, ValueFormat.COLOR, depth + 1)
            else AndroidColor.parseHex(value)?.let { ResolvedValue.Color(it.toInt()) }
            ResourceType.DIMEN -> if (value.startsWith("@")) resolveRef(value, ValueFormat.DIMENSION, depth + 1)
            else ResolvedValue.Dimension(parseDimensionPx(value))
            ResourceType.STRING -> ResolvedValue.Str(value)
            ResourceType.BOOL -> ResolvedValue.BoolV(value.equals("true", ignoreCase = true))
            ResourceType.INTEGER -> value.toIntOrNull()?.let { ResolvedValue.IntV(it) }
            else -> if (value.startsWith("@")) resolveRef(value, format, depth + 1) else parseLiteral(value, format)
        }
    }

    /** Parse `12dp`/`8dip`/`14sp`/`2px` (etc.) into pixels for the previewed density; 0 if unparseable. */
    fun parseDimensionPx(raw: String): Float {
        val m = DIMEN.find(raw.trim()) ?: return 0f
        val value = m.groupValues[1].toFloatOrNull() ?: return 0f
        return when (m.groupValues[2].lowercase()) {
            "dp", "dip" -> value * density
            "sp" -> value * scaledDensity
            "px", "" -> value
            "pt" -> value * density * 160f / 72f
            "mm" -> value * density * 160f / 25.4f
            "in" -> value * density * 160f
            else -> value
        }
    }

    private data class ParsedRef(val type: ResourceType, val name: String, val pkg: String?)

    private fun parseRef(raw: String): ParsedRef? {
        val m = REF.find(raw) ?: return null
        val pkg = m.groupValues[1].ifEmpty { null }
        val type = ResourceType.byRClass(m.groupValues[2]) ?: return null
        val name = m.groupValues[3].replace('.', '_')
        return ParsedRef(type, name, pkg)
    }

    private companion object {
        // @[+]?[pkg:]type/name
        val REF = Regex("""@\+?(?:([A-Za-z][\w.]*):)?([A-Za-z]\w*)/([A-Za-z_][\w.]*)""")
        val DIMEN = Regex("""^(-?[\d.]+)\s*([A-Za-z]*)$""")
    }
}
