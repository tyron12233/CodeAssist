package dev.ide.android.support.resources

/**
 * A resource reference parsed from XML, namespace-aware. Covers `@type/name`, the `@+id/name`
 * declaration form, namespaced refs `@android:type/name` and `@com.pkg:type/name`, and theme-attribute
 * refs `?attr/name` / `?name` / `?android:attr/name`. [packageName] is null for a local reference,
 * `"android"` for the framework, or another package for a library/module. [range] is the span in the
 * source text (end-exclusive), for diagnostics and (later) navigation.
 */
data class ResourceReference(
    val type: ResourceType?,        // null when the type token isn't a modeled type (e.g. `@null`)
    val name: String,
    val packageName: String?,       // null = local, "android" = framework, else a library/module package
    val create: Boolean,            // `@+id/...` — a declaration, not a reference
    val themeAttr: Boolean,         // `?...` — a theme-attribute reference
    val range: IntRange,
) {
    val isLocal: Boolean get() = packageName == null
    val isFramework: Boolean get() = packageName == "android"
}

/** Parses + validates resource references in res XML. Pure text + the [ResourceRepository]; stdlib-only. */
object ResourceReferences {

    //  [@?]  (+)?  (pkg:)?  (type/)?  name
    private val REF = Regex("""([@?])(\+)?(?:([A-Za-z][\w.]*):)?(?:([A-Za-z]\w*)/)?([A-Za-z_][\w.]*)""")

    /** Special pseudo-resources that are not real references (`@null`, `@empty`). */
    private val PSEUDO = setOf("null", "empty", "undefined")

    /** Types that are most often supplied by the framework or a library (so they are not false-flagged). */
    private val UNVALIDATED_TYPES = setOf(ResourceType.ATTR, ResourceType.STYLEABLE)

    fun scan(text: String): List<ResourceReference> = REF.findAll(text).mapNotNull { m ->
        val sigil = m.groupValues[1]
        val themeAttr = sigil == "?"
        val create = m.groupValues[2] == "+"
        val pkg = m.groupValues[3].ifEmpty { null }
        val typeToken = m.groupValues[4].ifEmpty { if (themeAttr) "attr" else null }
        val name = m.groupValues[5]
        // `@{user.name}` binding / `@null` are filtered later; here just build the parsed ref.
        ResourceReference(
            type = typeToken?.let { ResourceType.byRClass(it) },
            name = name,
            packageName = pkg,
            create = create,
            themeAttr = themeAttr,
            range = m.range,
        )
    }.toList()

    /**
     * Unresolved local references in [text], validated against [repo]. Conservative to avoid false
     * positives while the framework/AAR resources aren't modeled: only flags a reference when it is local
     * (no `@android:`/package), is a real reference (not `@+id`/theme attr), names a modeled type, and that
     * type already has some resource in the repository (so a missing name is a likely typo, not a type
     * sourced entirely from a library not visible here).
     */
    fun problems(text: String, repo: ResourceRepository): List<XmlResourceProblem> {
        val out = ArrayList<XmlResourceProblem>()
        for (ref in scan(text)) {
            if (!ref.isLocal || ref.create || ref.themeAttr) continue
            val type = ref.type ?: continue
            if (type in UNVALIDATED_TYPES) continue
            if (ref.name in PSEUDO) continue
            if (repo.names(type).isEmpty()) continue           // this type is (so far) only from libraries — don't flag
            val name = sanitize(ref.name)
            if (!repo.has(type, name)) {
                out += XmlResourceProblem(ref.range.first, ref.range.last + 1, "Cannot resolve @${type.rClass}/${ref.name}")
            }
        }
        return out
    }

    private fun sanitize(name: String): String = name.replace('.', '_').replace('-', '_').trim()
}

/** A neutral problem (offset span + message) — the host maps it to its diagnostic model. */
data class XmlResourceProblem(val start: Int, val end: Int, val message: String)
