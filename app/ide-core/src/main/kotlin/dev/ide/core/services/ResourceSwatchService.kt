package dev.ide.core.services

import dev.ide.android.support.index.ResourceDeclValue
import dev.ide.android.support.navigation.ResourceOccurrences
import dev.ide.android.support.navigation.XmlNavTarget
import dev.ide.android.support.navigation.sanitizeResourceName
import dev.ide.android.support.preview.AndroidColor
import dev.ide.android.support.preview.DrawablePreview
import dev.ide.android.support.preview.DrawablePreviewParser
import dev.ide.android.support.preview.DrawableResolver
import dev.ide.android.support.preview.ResolvedDrawable
import dev.ide.android.support.resources.ResourceReferences
import dev.ide.android.support.resources.ResourceType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A gutter swatch: what a color or drawable reference (or a `#hex` literal) on a line looks like. Exactly one of
 * [argb] (a color), [drawable] (an XML drawable, already resolved) or [bitmap] (an image file's bytes) is set.
 * [target] is where tapping it goes: the resource's declaration, when it has one.
 */
class ResourceSwatch(
    val offset: Int,
    val argb: Long? = null,
    val drawable: DrawablePreview? = null,
    val bitmap: ByteArray? = null,
    val target: Pair<Path, Int>? = null,
)

/**
 * The gutter's color and drawable previews, for res XML and for `R.color.x` / `R.drawable.x` in code.
 *
 * Everything resolves through the resource index (each value resource carries its literal), never the
 * merged resource repository: this runs on the editor's debounced pass after every edit, and the repository's
 * freshness check walks every `res/` directory. A drawable XML is parsed with an index-backed
 * [DrawableResolver]; a bitmap is read once per file version and kept in a small cache.
 */
internal class ResourceSwatchService(private val files: AndroidProjectFiles) {

    fun swatches(file: Path, text: String): List<ResourceSwatch> {
        if (files.androidModuleOf(file) == null) return emptyList()
        val refs: List<Pair<Int, String>> = if (AndroidNavigationService.isXml(file)) xmlRefs(text) else codeRefs(text)
        val out = ArrayList<ResourceSwatch>()
        val seenLines = HashSet<Int>()
        val lineOf = LineIndex(text)
        for ((offset, ref) in refs) {
            if (out.size >= MAX_SWATCHES) break
            val line = lineOf.line(offset)
            if (line in seenLines) continue // the gutter fits one swatch per line: the first that resolves
            val swatch = swatchFor(offset, ref) ?: continue
            seenLines += line
            out += swatch
        }
        return out
    }

    /** `@color/x`, `@drawable/x`, `@mipmap/x`, `@android:color/x` and `#hex` literals, with their offsets. */
    private fun xmlRefs(text: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        for (r in ResourceReferences.scan(text)) {
            if (r.themeAttr || r.create) continue
            val type = r.type ?: continue
            if (type != ResourceType.COLOR && type != ResourceType.DRAWABLE && type != ResourceType.MIPMAP) continue
            if (r.packageName != null && !(r.isFramework && type == ResourceType.COLOR)) continue
            out += r.range.first to text.substring(r.range.first, r.range.last + 1)
        }
        for (m in HEX.findAll(text)) out += m.range.first to m.value
        return out.sortedBy { it.first }
    }

    private fun codeRefs(text: String): List<Pair<Int, String>> =
        ResourceOccurrences.codeReferences(text).mapNotNull { r: XmlNavTarget.Resource ->
            if (r.type != ResourceType.COLOR && r.type != ResourceType.DRAWABLE && r.type != ResourceType.MIPMAP) return@mapNotNull null
            if (r.isFramework && r.type != ResourceType.COLOR) return@mapNotNull null
            r.range.first to "@" + (if (r.isFramework) "android:" else "") + "${r.type.rClass}/${r.name}"
        }

    private fun swatchFor(offset: Int, ref: String): ResourceSwatch? {
        if (ref.startsWith("#")) return AndroidColor.parseHex(ref)?.let { ResourceSwatch(offset, argb = it) }
        val type = ref.substringAfter('@').substringAfter("android:").substringBefore('/')
        return when (type) {
            "color" -> colorOf(ref, 0)?.let { ResourceSwatch(offset, argb = it, target = targetOf(ResourceType.COLOR, ref)) }
            "drawable", "mipmap" -> {
                val rt = if (type == "drawable") ResourceType.DRAWABLE else ResourceType.MIPMAP
                when (val d = resolveDrawable(ref)) {
                    is ResolvedDrawable.Xml -> runCatching { DrawablePreviewParser.parse(d.text, resolver) }.getOrNull()
                        ?.let { ResourceSwatch(offset, drawable = it, target = targetOf(rt, ref)) }
                    is ResolvedDrawable.BitmapFile -> d.path?.let { bitmapBytes(Paths.get(it)) }
                        ?.let { ResourceSwatch(offset, bitmap = it, target = targetOf(rt, ref)) }
                    null -> null
                }
            }
            else -> null
        }
    }

    /** The declaration a tap opens: its declaring line, as indexed. Not refined to the name the way Go to is,
     *  which would read the declaring file on every pass for every swatch. */
    private fun targetOf(type: ResourceType, ref: String): Pair<Path, Int>? {
        if (ref.startsWith("@android:")) return null
        val decl = pick(type, ref.substringAfter('/')) ?: return null
        return Paths.get(decl.filePath) to decl.offset
    }

    /** `#hex`, `@color/x` (followed through aliases and a color-state list's default), `@android:color/x`. */
    private fun colorOf(raw: String, depth: Int): Long? {
        if (depth > MAX_ALIAS_DEPTH) return null
        val v = raw.trim()
        return when {
            v.startsWith("#") -> AndroidColor.parseHex(v)
            v.startsWith("@android:color/") -> AndroidColor.framework(v.removePrefix("@android:color/"))
            v.startsWith("@color/") -> {
                val decl = pick(ResourceType.COLOR, v.removePrefix("@color/")) ?: return null
                decl.value?.let { return colorOf(it, depth + 1) }
                // A color-state list (`res/color/x.xml`): its last item is the default state.
                val xml = files.textOf(Paths.get(decl.filePath)) ?: return null
                SELECTOR_COLOR.findAll(xml).lastOrNull()?.groupValues?.get(1)?.let { colorOf(it, depth + 1) }
            }
            else -> null
        }
    }

    private fun resolveDrawable(ref: String): ResolvedDrawable? {
        if (ref.startsWith("@android:")) return null
        val typeName = ref.substringAfter('@').substringBefore('/')
        val type = ResourceType.byRClass(typeName) ?: return null
        val decls = files.declarations(type, sanitizeResourceName(ref.substringAfter('/')))
        if (decls.isEmpty()) return null
        // A vector or other XML drawable renders at any size; else the densest bitmap looks best scaled down.
        decls.filter { it.filePath.endsWith(".xml") }.minByOrNull { AndroidNavigationService.qualifierRank(it.filePath) }?.let { d ->
            return files.textOf(Paths.get(d.filePath))?.let { ResolvedDrawable.Xml(it) }
        }
        val best = decls.maxByOrNull { densityRank(it.filePath) } ?: return null
        return ResolvedDrawable.BitmapFile(type.rClass, best.name, best.filePath)
    }

    /** The declaration to read a value from: the project's default configuration first. */
    private fun pick(type: ResourceType, name: String): ResourceDeclValue? =
        files.declarations(type, sanitizeResourceName(name)).minWithOrNull(
            compareBy({ !files.isProjectResource(Paths.get(it.filePath)) }, { AndroidNavigationService.qualifierRank(it.filePath) }),
        )

    private val resolver = object : DrawableResolver {
        override fun resolveColor(ref: String): Long? = colorOf(ref, 0)
        override fun resolveDimenDp(ref: String): Float? {
            if (!ref.startsWith("@dimen/")) return null
            val value = pick(ResourceType.DIMEN, ref.removePrefix("@dimen/"))?.value ?: return null
            return DIMEN.matchEntire(value.trim())?.groupValues?.get(1)?.toFloatOrNull()
        }
        override fun resolveDrawable(ref: String): ResolvedDrawable? = this@ResourceSwatchService.resolveDrawable(ref)
        override fun resolveXml(ref: String): String? {
            val type = ResourceType.byRClass(ref.substringAfter('@').substringBefore('/')) ?: return null
            return pick(type, ref.substringAfter('/'))?.takeIf { it.filePath.endsWith(".xml") }
                ?.let { files.textOf(Paths.get(it.filePath)) }
        }
        override fun resolveValue(ref: String): String? {
            val type = ResourceType.byRClass(ref.substringAfter('@').substringBefore('/')) ?: return null
            return pick(type, ref.substringAfter('/'))?.value
        }
    }

    /** An image file's bytes, cached per file version; null past [MAX_BITMAP_BYTES] (too big for a 14dp swatch). */
    private fun bitmapBytes(path: Path): ByteArray? {
        val size = runCatching { Files.size(path) }.getOrNull() ?: return null
        if (size > MAX_BITMAP_BYTES) return null
        val stamp = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
        val key = "$path|$stamp|$size"
        synchronized(bitmapCache) { bitmapCache[key]?.let { return it } }
        val bytes = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return null
        synchronized(bitmapCache) { bitmapCache[key] = bytes }
        return bytes
    }

    private val bitmapCache = object : LinkedHashMap<String, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 64
    }

    private class LineIndex(text: String) {
        private val starts = buildList { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
        fun line(offset: Int): Int = starts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
    }

    private companion object {
        const val MAX_SWATCHES = 300
        const val MAX_ALIAS_DEPTH = 8
        const val MAX_BITMAP_BYTES = 256 * 1024L
        val HEX = Regex("""(?<=["'>\s])#(?:[0-9A-Fa-f]{8}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{3,4})(?=["'<\s])""")
        val SELECTOR_COLOR = Regex("""android:color\s*=\s*"([^"]+)"""")
        val DIMEN = Regex("""(-?[0-9.]+)\s*(dp|dip|sp|px)""")

        /** Densest first: a bitmap scaled down to the gutter looks better than one scaled up. */
        fun densityRank(filePath: String): Int {
            val folder = Paths.get(filePath).parent?.fileName?.toString().orEmpty()
            return listOf("ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi").indexOfFirst { q ->
                folder.split('-').contains(q)
            }.let { if (it < 0) 2 else it }
        }
    }
}
