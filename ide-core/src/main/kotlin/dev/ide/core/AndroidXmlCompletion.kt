package dev.ide.core

import dev.ide.android.support.metadata.AndroidSdkMetadata
import dev.ide.android.support.metadata.AttributeSpec
import dev.ide.android.support.metadata.LayoutMetadata
import dev.ide.android.support.metadata.Widget
import dev.ide.android.support.resources.AndroidManifestCatalog
import dev.ide.android.support.resources.AndroidToolsCatalog
import dev.ide.android.support.resources.DrawableXmlCatalog
import dev.ide.android.support.resources.ResourceType
import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.TextEdit
import dev.ide.lang.dom.TextRange
import dev.ide.lang.signature.SignatureInfo
import dev.ide.lang.xml.completion.XmlCompletionContributor
import dev.ide.lang.xml.completion.XmlCompletionKind
import dev.ide.lang.xml.completion.XmlCompletionPosition

/** A resource-name candidate for value completion: the [name] plus, for a value resource, its resolved
 *  literal [value] (a string's text, a color's `#…`, a dimen's `16dp`) used as the popup hint. Both come from
 *  ONE incremental-index query, so completion never re-parses or re-fingerprints the resource repository. */
data class ResourceCandidate(val name: String, val value: String? = null)

/**
 * The Android half of XML completion: adapts layout metadata ([LayoutMetadata], the SDK-derived
 * `AndroidSdkMetadata` when its asset is present, else an empty fallback), the project's
 * custom-view attributes ([customAttrs], parsed from `attrs.xml`), the [AndroidManifestCatalog] (manifest
 * schema), and the module's resources into [CompletionItem]s for the language-neutral
 * [XmlCompletionContributor] seam. `lang-xml` decides where the caret is; this decides what Android
 * offers there, switching by file type (`AndroidManifest.xml` for the manifest schema, else layout/res widgets).
 * Providers are lazy so they reflect the latest resources at completion time. [resources] returns the known
 * resource candidates (name + value hint) of a type from the incremental resource index, so this does not
 * parse or fingerprint a `ResourceRepository` per keystroke; [frameworkResources] returns framework
 * (`@android:type/name`) resource names, offered once the user opts in by typing `@android`.
 */
class AndroidXmlContributor(
    private val resources: (ResourceType) -> List<ResourceCandidate>,
    private val layout: () -> LayoutMetadata = { AndroidSdkMetadata.bundled() },
    private val customAttrs: () -> LayoutMetadata? = { null },
    /** Custom View subclasses from the module's library classpath (FQN tags); offered alongside framework widgets. */
    private val customViews: () -> List<Widget> = { emptyList() },
    /** Framework resource names of a type (from `android.jar`'s `android.R$*`), for `@android:type/name` refs. */
    private val frameworkResources: (ResourceType) -> List<String> = { emptyList() },
) : XmlCompletionContributor {

    override fun contribute(position: XmlCompletionPosition): List<CompletionItem> {
        val flavor = flavorOf(position.filePath)
        return when (position.kind) {
            XmlCompletionKind.TAG_NAME -> when (flavor) {
                Flavor.MANIFEST -> manifestTagItems(position)
                Flavor.DRAWABLE -> drawableTagItems(position)
                Flavor.LAYOUT -> tagItems(position)
            }

            XmlCompletionKind.ATTRIBUTE_NAME -> when {
                // Declaring a namespace: `xmlns:|` offers the undeclared standard namespaces (android/app/tools).
                position.prefix.startsWith("xmlns", ignoreCase = true) -> namespaceDeclItems(
                    position
                )

                else -> {
                    val specs = when (flavor) {
                        Flavor.MANIFEST -> AndroidManifestCatalog.attributesFor(position.tag)
                        Flavor.DRAWABLE -> DrawableXmlCatalog.attributesFor(position.tag)
                        Flavor.LAYOUT -> mergedAttributes(position)
                    }
                    val base = specs.filter { it.name !in position.existingAttributes }
                        .map { attributeItem(it, position) }
                    // `tools:` design-time attributes (layouts) once the user is typing the tools namespace.
                    val tools = if (flavor == Flavor.LAYOUT && position.prefix.startsWith(
                            "tools",
                            ignoreCase = true
                        )
                    )
                        toolsAttributeItems(position) else emptyList()
                    base + tools
                }
            }

            XmlCompletionKind.ATTRIBUTE_VALUE -> {
                val an = position.attributeName
                when {
                    // The value of a namespace declaration is its URI.
                    an != null && an.startsWith("xmlns:") -> namespaceUriItems(an)
                    // A `tools:` attribute's value: its curated spec, else the android:/app: attribute it overrides.
                    flavor == Flavor.LAYOUT && an != null && an.startsWith("tools:") ->
                        toolsValueSpec(position, an)?.let { valueItemsFor(it, position.prefix) }
                            ?: emptyList()

                    else -> attributeSpecFor(position, flavor)?.let {
                        valueItemsFor(
                            it,
                            position.prefix
                        )
                    } ?: emptyList()
                }
            }

            else -> emptyList()
        }
    }

    private enum class Flavor { MANIFEST, DRAWABLE, LAYOUT }

    private fun drawableTagItems(pos: XmlCompletionPosition): List<CompletionItem> =
        DrawableXmlCatalog.childrenOf(pos.parentTag).map { tag ->
            CompletionItem(
                label = tag,
                insertText = tag,
                kind = CompletionItemKind.CLASS,
                detail = "drawable"
            )
        }

    /** Framework (SDK or curated) attributes for the tag + its custom-view attributes, deduped by name. */
    private fun mergedAttributes(pos: XmlCompletionPosition): List<AttributeSpec> =
        allowedAttributes(pos.tag, pos.parentTag)

    /**
     * The full set of attributes valid on a [tag] (as written in the layout) under [parentTag] — the framework
     * SDK attributes for the view (hierarchy-aware, incl. AppCompat/Material substitutions) plus the parent's
     * `layout_*` params plus the module's custom-view (`app:`) attributes, deduped by name. This is the exact
     * "only allowed attributes" set the layout attribute editor offers; it is the same computation layout
     * ATTRIBUTE_NAME completion uses.
     */
    fun allowedAttributes(tag: String?, parentTag: String?): List<AttributeSpec> {
        val framework = layout().attributesFor(tag, parentTag)
        val custom = customAttrs()?.attributesFor(tag, parentTag).orEmpty()
        return (framework + custom).distinctBy { it.name }
    }

    private fun flavorOf(filePath: String): Flavor {
        val path = filePath.replace('\\', '/')
        return when {
            path.endsWith("AndroidManifest.xml") -> Flavor.MANIFEST
            DrawableXmlCatalog.appliesTo(path) -> Flavor.DRAWABLE
            else -> Flavor.LAYOUT
        }
    }

    /** The schema spec for the attribute [pos] names — the flavor's catalog, or the merged layout attributes. */
    private fun attributeSpecFor(pos: XmlCompletionPosition, flavor: Flavor): AttributeSpec? {
        val an = pos.attributeName ?: return null
        return when (flavor) {
            Flavor.MANIFEST -> AndroidManifestCatalog.attribute(pos.tag, an)
            Flavor.DRAWABLE -> DrawableXmlCatalog.attribute(pos.tag, an)
            Flavor.LAYOUT -> mergedAttributes(pos).firstOrNull { it.name == an }
        }
    }

    /**
     * Signature help / parameter hint for the attribute value the caret sits in (`android:x="|"`): a one-line
     * description of what the attribute accepts (`true | false`, an enum's members, `@string/…`, …). Null when
     * the caret isn't inside a value; a bare name when the schema doesn't describe it (so the panel still names
     * the attribute).
     */
    fun describeValue(pos: XmlCompletionPosition): SignatureInfo? {
        if (pos.kind != XmlCompletionKind.ATTRIBUTE_VALUE) return null
        val an = pos.attributeName ?: return null
        if (an.startsWith("xmlns:")) return SignatureInfo("$an = namespace URI", emptyList())
        val flavor = flavorOf(pos.filePath)
        val spec = if (flavor == Flavor.LAYOUT && an.startsWith("tools:")) toolsValueSpec(pos, an)
        else attributeSpecFor(pos, flavor)
        return SignatureInfo(label = spec?.let { describeSpec(an, it) } ?: an,
            parameters = emptyList())
    }

    /** A compact `name = a | b | @type/…` rendering of an attribute's accepted values, for the hint panel. */
    private fun describeSpec(name: String, spec: AttributeSpec): String {
        val parts = buildList {
            if (spec.boolean) add("true | false")
            if (spec.enumValues.isNotEmpty()) add(capped(spec.enumValues).joinToString(" | "))
            if (spec.flags.isNotEmpty()) add(capped(spec.flags).joinToString(" | ") + " (combine with |)")
            spec.resourceTypes.forEach { add("@${it.rClass}/…") }
            if (spec.resourceTypes.isNotEmpty()) add("?attr/…")
        }
        return if (parts.isEmpty()) name else "$name = ${parts.joinToString("  |  ")}"
    }

    private fun capped(vs: List<String>, n: Int = 12): List<String> =
        if (vs.size <= n) vs else vs.take(n) + "…"

    /** `tools:` items: the curated design-time attributes plus a `tools:`-prefixed override of every
     *  `android:`/`app:` attribute the element accepts (`tools:text`, `tools:visibility`, …). */
    private fun toolsAttributeItems(pos: XmlCompletionPosition): List<CompletionItem> {
        val out = ArrayList<CompletionItem>()
        for (spec in AndroidToolsCatalog.attributesFor(pos.tag)) {
            if (spec.name !in pos.existingAttributes) out += attributeItem(spec, pos)
        }
        for (spec in mergedAttributes(pos)) {
            val toolsName = "tools:" + spec.name.substringAfter(':', spec.name)
            if (toolsName in pos.existingAttributes || AndroidToolsCatalog.attribute(toolsName) != null) continue
            out += attributeItem(
                spec.copy(name = toolsName),
                pos
            ) // reuse the override's value shape under tools:
        }
        return out
    }

    /** The value spec for a `tools:` attribute — its curated spec, else the `android:`/`app:` attribute it
     *  overrides (so `tools:visibility` completes the same enum, `tools:text` the same `@string` refs). */
    private fun toolsValueSpec(pos: XmlCompletionPosition, attributeName: String): AttributeSpec? {
        AndroidToolsCatalog.attribute(attributeName)?.let { return it }
        val local = attributeName.removePrefix("tools:")
        return mergedAttributes(pos).firstOrNull { it.name == "android:$local" || it.name == "app:$local" }
            ?.copy(name = attributeName)
    }

    /** `xmlns:android`/`app`/`tools` declarations not yet present on the root — each inserts the full
     *  `xmlns:prefix="uri"`. */
    private fun namespaceDeclItems(pos: XmlCompletionPosition): List<CompletionItem> =
        NAMESPACE_URIS.entries.filter { it.key !in pos.declaredNamespaces }.map { (prefix, uri) ->
            val insert = "xmlns:$prefix=\"$uri\""
            CompletionItem(
                label = "xmlns:$prefix",
                insertText = insert,
                kind = CompletionItemKind.FIELD,
                detail = uri,
                caret = CaretAction.At(insert.length), // caret past the closing quote
            )
        }

    /** The URI value for an `xmlns:prefix="…"` declaration of a known prefix. */
    private fun namespaceUriItems(attributeName: String): List<CompletionItem> {
        val uri = NAMESPACE_URIS[attributeName.removePrefix("xmlns:")] ?: return emptyList()
        return listOf(CompletionItem(uri, uri, CompletionItemKind.FIELD, detail = "namespace URI"))
    }

    private fun tagItems(pos: XmlCompletionPosition): List<CompletionItem> {
        // Framework widgets (simple names, from the SDK metadata) + custom views from the library classpath
        // (fully-qualified names — a layout must spell a non-framework view with its FQN). Deduped by tag.
        val seen = HashSet<String>()
        val out = ArrayList<CompletionItem>()
        for (w in layout().childTagsFor(pos.parentTag)) if (seen.add(w.tag)) out += widgetItem(w)
        for (w in customViews()) if (seen.add(w.tag)) out += widgetItem(w)
        return out
    }

    private fun widgetItem(w: Widget): CompletionItem =
        CompletionItem(
            label = w.tag,
            insertText = w.tag,
            kind = CompletionItemKind.CLASS,
            detail = if (w.isViewGroup) "ViewGroup" else "View",
        )

    private fun manifestTagItems(pos: XmlCompletionPosition): List<CompletionItem> =
        AndroidManifestCatalog.childrenOf(pos.parentTag).map { tag ->
            CompletionItem(
                label = tag,
                insertText = tag,
                kind = CompletionItemKind.CLASS,
                detail = "manifest"
            )
        }

    private fun attributeItem(spec: AttributeSpec, pos: XmlCompletionPosition): CompletionItem {
        // Insert `name=""` and land the caret between the quotes, ready for value completion.
        val insert = "${spec.name}=\"\""
        return CompletionItem(
            label = spec.name,
            insertText = insert,
            kind = CompletionItemKind.FIELD,
            detail = valueHint(spec),
            caret = CaretAction.At(insert.length - 1),
            // Accepting a namespaced attribute auto-declares its xmlns on the root when it's missing (the
            // Android Studio behavior) — e.g. picking `app:layout_…` adds xmlns:app to the root element.
            additionalEdits = namespaceDeclarationEdit(spec.name.substringBefore(':', ""), pos),
        )
    }

    /** The edit (if any) that declares the [prefix] namespace on the root element, when a namespaced attribute
     *  is being accepted and that namespace isn't declared yet. Empty for an unprefixed/unknown prefix or when
     *  it's already declared (or there's no root to attach it to). */
    private fun namespaceDeclarationEdit(
        prefix: String,
        pos: XmlCompletionPosition
    ): List<TextEdit> {
        val uri = NAMESPACE_URIS[prefix] ?: return emptyList()
        if (prefix in pos.declaredNamespaces || pos.namespaceInsertOffset < 0) return emptyList()
        val at = pos.namespaceInsertOffset
        return listOf(TextEdit(TextRange(at, at), " xmlns:$prefix=\"$uri\""))
    }

    private fun valueItemsFor(spec: AttributeSpec, prefix: String): List<CompletionItem> {
        val out = ArrayList<CompletionItem>()

        (spec.enumValues + spec.flags).forEach { v ->
            out += CompletionItem(v, v, CompletionItemKind.ENUM_CONSTANT, detail = "value")
        }
        if (spec.boolean) listOf("true", "false").forEach {
            out += CompletionItem(it, it, CompletionItemKind.ENUM_CONSTANT, detail = "boolean")
        }

        // Framework `@android:` resources are large, so they're offered only once the user opts in by typing
        // `@android` (the engine then narrows by the rest of the prefix); local resources always show.
        val wantFramework = prefix.startsWith("@android", ignoreCase = true)
        for (type in spec.resourceTypes) {
            if (type == ResourceType.ID) {
                // An id attribute almost always declares a new id.
                out += CompletionItem(
                    "@+id/",
                    "@+id/",
                    CompletionItemKind.SNIPPET,
                    detail = "new id"
                )
            }
            for (c in runCatching { resources(type) }.getOrDefault(emptyList())) {
                val ref = "@${type.rClass}/${c.name}"
                // Show the resolved value as the hint (@string/app_name → "CodeAssist", @color/primary → #6200EE)
                // so the right resource is pickable at a glance; fall back to the resource type for file resources.
                out += CompletionItem(
                    ref,
                    ref,
                    CompletionItemKind.FIELD,
                    detail = c.value?.let(::valuePreview) ?: type.rClass
                )
            }
            if (wantFramework) for (name in runCatching { frameworkResources(type) }.getOrDefault(
                emptyList()
            )) {
                val ref = "@android:${type.rClass}/$name"
                out += CompletionItem(ref, ref, CompletionItemKind.FIELD, detail = "android")
            }
        }

        // Theme attribute references (`?attr/name` / `?android:attr/name`). A theme attr can supply whatever
        // typed value the attribute expects, so they're offered for any attribute that accepts a resource
        // reference — but only once the user opts in by typing `?` (theme attrs are numerous), mirroring the
        // `@android` gate for framework resources. The app/library theme attrs come from the resource index
        // (`<attr>` declarations, incl. AppCompat/Material's `colorPrimary`, `colorSurface`, …); the framework
        // ones (`android.R.attr`, thousands) stay behind the extra `?android` opt-in.
        if (spec.resourceTypes.isNotEmpty() && prefix.startsWith("?")) {
            for (c in runCatching { resources(ResourceType.ATTR) }.getOrDefault(emptyList())) {
                val ref = "?attr/${c.name}"
                out += CompletionItem(ref, ref, CompletionItemKind.FIELD, detail = "theme attr")
            }
            if (prefix.startsWith("?android", ignoreCase = true)) {
                for (name in runCatching { frameworkResources(ResourceType.ATTR) }.getOrDefault(
                    emptyList()
                )) {
                    val ref = "?android:attr/$name"
                    out += CompletionItem(
                        ref,
                        ref,
                        CompletionItemKind.FIELD,
                        detail = "android theme attr"
                    )
                }
            }
        }
        return out
    }

    /** A one-line, length-capped rendering of a resolved resource value for the completion-popup hint, or null
     *  when it's blank (so the caller falls back to the resource type). */
    private fun valuePreview(value: String): String? {
        val collapsed = value.replace(Regex("\\s+"), " ").trim()
        if (collapsed.isEmpty()) return null
        return if (collapsed.length > 32) collapsed.take(31) + "…" else collapsed
    }

    private fun valueHint(spec: AttributeSpec): String? = when {
        spec.enumValues.isNotEmpty() -> "enum"
        spec.flags.isNotEmpty() -> "flags"
        spec.boolean -> "boolean"
        spec.resourceTypes.isNotEmpty() -> spec.resourceTypes.joinToString("|") { "@${it.rClass}" }
        else -> null
    }

    companion object {
        /** The standard Android namespace URIs, by conventional prefix — for auto-declaring a missing xmlns. */
        val NAMESPACE_URIS = mapOf(
            "android" to "http://schemas.android.com/apk/res/android",
            "app" to "http://schemas.android.com/apk/res-auto",
            "tools" to "http://schemas.android.com/tools",
        )
    }
}
