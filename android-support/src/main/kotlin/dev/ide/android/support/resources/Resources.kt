package dev.ide.android.support.resources

import java.nio.file.Path

/**
 * One resource definition: a [type]+[name] pair from a particular [source] file and config [qualifier]
 * (e.g. `night`, `v21`, `en-rUS`; empty for the default config). [value] is the inline text for a value
 * resource (for hover), null for a file resource. The same [type]/[name] may have several items (one per
 * config); [ResourceRepository] dedups by type+name for `R` and reference resolution.
 */
/** A parsed `<style>`/theme: its [parent] (explicit `parent="…"`, raw) and `<item name>` → value map. */
data class StyleData(val parent: String?, val items: Map<String, String>)

data class ResourceItem(
    val type: ResourceType,
    val name: String,
    val source: Path? = null,
    val qualifier: String = "",
    val value: String? = null,
)

/**
 * A queryable, merged view of a module's resources — the analogue of `sdk-common`'s `ResourceRepository`.
 * Built from one or more `res/` roots in overlay order (later roots win, like a
 * build's source-set/dependency merge). Backs the synthetic `R` class and XML reference resolution.
 */
class ResourceRepository(
    items: List<ResourceItem>,
    /** `<declare-styleable>` name → its child `<attr>` names in declaration order. Backs `R.styleable.*` arrays. */
    private val styleableAttrs: Map<String, List<String>> = emptyMap(),
    /** Raw (unsanitized) `<style>`/theme name → its parent + `<item>` map. Backs theme/chrome resolution. */
    private val styles: Map<String, StyleData> = emptyMap(),
    /** `<attr>` name → its `format` token (color/dimension/integer/…), for typing styled-attribute lookups. */
    private val attrFormats: Map<String, String> = emptyMap(),
) {

    /** The declared `format` of `<attr name=…>` (e.g. `color`), or null if unknown. */
    fun attrFormat(name: String): String? = attrFormats[name]
    private val items: List<ResourceItem> = items
    private val byType: Map<ResourceType, List<ResourceItem>> = items.groupBy { it.type }

    fun all(): List<ResourceItem> = items
    fun types(): Set<ResourceType> = byType.keys

    /** The attr names of `<declare-styleable name=[styleableName]>`, in declaration order (empty if unknown). */
    fun styleableAttrs(styleableName: String): List<String> = styleableAttrs[styleableName] ?: emptyList()

    /** The `<style name=[styleName]>` definition (parent + items), keyed by its raw dotted name, or null. */
    fun style(styleName: String): StyleData? = styles[styleName]

    /** Distinct resource names of [type] (what `R.<type>` exposes). */
    fun names(type: ResourceType): Set<String> =
        byType[type]?.mapTo(LinkedHashSet()) { it.name } ?: emptySet()

    /** Whether `@<type>/<name>` (or `R.<type>.<name>`) exists — for reference validation. */
    fun has(type: ResourceType, name: String): Boolean = byType[type]?.any { it.name == name } == true

    /** All definitions of a resource (one per config) — for go-to-definition (every place it's declared). */
    fun definitions(type: ResourceType, name: String): List<ResourceItem> =
        byType[type]?.filter { it.name == name } ?: emptyList()

    fun isEmpty(): Boolean = items.isEmpty()
}

/**
 * The resource-parsing port: turns a set of `res/` directories (in overlay order) into a
 * [ResourceRepository]. The default [StdlibResourceModel] is a dependency-free implementation of
 * aapt2/sdk-common's folder + value parsing; the seam lets a host swap in an sdk-common-backed parser
 * (or a future aapt2-`R.txt` one) without touching callers.
 */
interface ResourceModel {
    fun parse(resDirs: List<Path>): ResourceRepository

    companion object {
        /** The default, dependency-free model used on desktop and ART alike. */
        val DEFAULT: ResourceModel get() = StdlibResourceModel
    }
}
