package dev.ide.android.support.preview

import dev.ide.android.support.resources.ResourceRepository
import dev.ide.android.support.resources.ResourceType
import kotlin.io.path.readText

/**
 * A [DrawableResolver] backed by a [ResourceRepository]: resolves the `@color`/`@dimen`/`@drawable`/`@mipmap`
 * references a drawable XML contains against a module's parsed resources. Self-contained (no open engine), so
 * it works both inside the IDE and for a cheap off-engine render such as the project picker's launcher icon.
 */
object ResourceDrawableResolver {

    fun of(repo: ResourceRepository): DrawableResolver = object : DrawableResolver {
        override fun resolveColor(ref: String): Long? = resolveColorRef(ref, repo, 0)

        override fun resolveDimenDp(ref: String): Float? {
            val v = repo.definitions(ResourceType.DIMEN, sanitize(ref.substringAfterLast('/')))
                .firstOrNull()?.value ?: return null
            return DIMEN_LITERAL.find(v)?.groupValues?.get(1)?.toFloatOrNull()
        }

        override fun resolveDrawable(ref: String): ResolvedDrawable? {
            val name = sanitize(ref.substringAfterLast('/'))
            // A @color used where a drawable is expected resolves to a flat fill; let the color path handle it.
            if (ref.contains("color") && repo.has(ResourceType.COLOR, name)) return null
            val item = repo.definitions(ResourceType.DRAWABLE, name).firstOrNull()
                ?: repo.definitions(ResourceType.MIPMAP, name).firstOrNull()
                ?: return null
            val src = item.source ?: return null
            return if (src.toString().endsWith(".xml")) {
                runCatching { src.readText() }.getOrNull()?.let { ResolvedDrawable.Xml(it) }
            } else {
                ResolvedDrawable.BitmapFile(item.type.rClass, name, src.toString())
            }
        }

        override fun resolveXml(ref: String): String? {
            val (type, name) = typeAndName(ref) ?: return null
            val src = repo.definitions(type, name).firstOrNull()?.source ?: return null
            if (!src.toString().endsWith(".xml")) return null
            return runCatching { src.readText() }.getOrNull()
        }

        override fun resolveValue(ref: String): String? {
            val (type, name) = typeAndName(ref) ?: return null
            if (type != ResourceType.STRING && type != ResourceType.INTEGER) return null
            return repo.definitions(type, name).firstOrNull()?.value
        }
    }

    /** `@type/name` (never a framework `@android:` one, which a project repository does not hold) → its parts. */
    private fun typeAndName(ref: String): Pair<ResourceType, String>? {
        val m = REF.find(ref.trim()) ?: return null
        if (m.groupValues[1] == "android") return null
        val type = ResourceType.byRClass(m.groupValues[2]) ?: return null
        return type to sanitize(m.groupValues[3])
    }

    private val REF = Regex("""^@\+?(?:([A-Za-z][\w.]*):)?([A-Za-z]\w*)/(.+)$""")

    /** Resolve `@color/x` (transitively through `@color` indirection) to ARGB; `@android:color/x` via the table. */
    private fun resolveColorRef(ref: String, repo: ResourceRepository, depth: Int): Long? {
        if (depth > 8) return null
        val raw = ref.trim()
        if (raw.startsWith("#")) return AndroidColor.parseHex(raw)
        if (raw.contains("android:")) return AndroidColor.framework(raw.substringAfterLast('/'))
        if (!raw.startsWith("@")) return null
        val v = repo.definitions(ResourceType.COLOR, sanitize(raw.substringAfterLast('/')))
            .firstOrNull()?.value ?: return null
        return when {
            v.startsWith("#") -> AndroidColor.parseHex(v)
            v.startsWith("@") -> resolveColorRef(v, repo, depth + 1)
            else -> null
        }
    }

    private fun sanitize(s: String): String = s.replace('.', '_').replace('-', '_').trim()

    private val DIMEN_LITERAL = Regex("""(-?\d+(?:\.\d+)?)""")
}
