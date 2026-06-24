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
import dev.ide.lang.xml.completion.XmlCompletionContributor
import dev.ide.lang.xml.completion.XmlCompletionKind
import dev.ide.lang.xml.completion.XmlCompletionPosition

/**
 * The Android half of XML completion: adapts layout metadata ([LayoutMetadata], the SDK-derived
 * `AndroidSdkMetadata` when its asset is present, else an empty fallback), the project's
 * custom-view attributes ([customAttrs], parsed from `attrs.xml`), the [AndroidManifestCatalog] (manifest
 * schema), and the module's merged [ResourceRepository] into [CompletionItem]s for the language-neutral
 * [XmlCompletionContributor] seam. `lang-xml` decides where the caret is; this decides what Android
 * offers there, switching by file type (`AndroidManifest.xml` for the manifest schema, else layout/res widgets).
 * Providers are lazy so they reflect the latest resources at completion time. [resourceNames] returns the
 * known resource names of a type, backed by the incremental resource index, so this does not
 * rebuild the whole `ResourceRepository` per keystroke.
 */
class AndroidXmlContributor(
    private val resourceNames: (ResourceType) -> List<String>,
    private val layout: () -> LayoutMetadata = { AndroidSdkMetadata.bundled() },
    private val customAttrs: () -> LayoutMetadata? = { null },
    /** Custom View subclasses from the module's library classpath (FQN tags); offered alongside framework widgets. */
    private val customViews: () -> List<Widget> = { emptyList() },
    /** The resolved inline value of `@type/name` (a string's text, a color's `#…`, a dimen's literal), for the
     *  completion-popup hint; null for a file resource (drawable/layout) or an unresolved/cold reference. */
    private val resourceValue: (ResourceType, String) -> String? = { _, _ -> null },
) : XmlCompletionContributor {

    override fun contribute(position: XmlCompletionPosition): List<CompletionItem> {
        val path = position.filePath.replace('\\', '/')
        val flavor = when {
            path.endsWith("AndroidManifest.xml") -> Flavor.MANIFEST
            DrawableXmlCatalog.appliesTo(path) -> Flavor.DRAWABLE
            else -> Flavor.LAYOUT
        }
        return when (position.kind) {
            XmlCompletionKind.TAG_NAME -> when (flavor) {
                Flavor.MANIFEST -> manifestTagItems(position)
                Flavor.DRAWABLE -> drawableTagItems(position)
                Flavor.LAYOUT -> tagItems(position)
            }
            XmlCompletionKind.ATTRIBUTE_NAME -> when {
                // Declaring a namespace: `xmlns:|` offers the undeclared standard namespaces (android/app/tools).
                position.prefix.startsWith("xmlns", ignoreCase = true) -> namespaceDeclItems(position)
                else -> {
                    val specs = when (flavor) {
                        Flavor.MANIFEST -> AndroidManifestCatalog.attributesFor(position.tag)
                        Flavor.DRAWABLE -> DrawableXmlCatalog.attributesFor(position.tag)
                        Flavor.LAYOUT -> mergedAttributes(position)
                    }
                    val base = specs.filter { it.name !in position.existingAttributes }.map { attributeItem(it, position) }
                    // `tools:` design-time attributes (layouts) once the user is typing the tools namespace.
                    val tools = if (flavor == Flavor.LAYOUT && position.prefix.startsWith("tools", ignoreCase = true))
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
                        toolsValueSpec(position, an)?.let(::valueItemsFor) ?: emptyList()
                    else -> {
                        val spec = when (flavor) {
                            Flavor.MANIFEST -> AndroidManifestCatalog.attribute(position.tag, an)
                            Flavor.DRAWABLE -> DrawableXmlCatalog.attribute(position.tag, an)
                            Flavor.LAYOUT -> mergedAttributes(position).firstOrNull { it.name == an }
                        }
                        spec?.let(::valueItemsFor) ?: emptyList()
                    }
                }
            }
            else -> emptyList()
        }
    }

    private enum class Flavor { MANIFEST, DRAWABLE, LAYOUT }

    private fun drawableTagItems(pos: XmlCompletionPosition): List<CompletionItem> =
        DrawableXmlCatalog.childrenOf(pos.parentTag).map { tag ->
            CompletionItem(label = tag, insertText = tag, kind = CompletionItemKind.CLASS, detail = "drawable")
        }

    /** Framework (SDK or curated) attributes for the tag + its custom-view attributes, deduped by name. */
    private fun mergedAttributes(pos: XmlCompletionPosition): List<AttributeSpec> {
        val framework = layout().attributesFor(pos.tag, pos.parentTag)
        val custom = customAttrs()?.attributesFor(pos.tag, pos.parentTag).orEmpty()
        return (framework + custom).distinctBy { it.name }
    }

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
            out += attributeItem(spec.copy(name = toolsName), pos) // reuse the override's value shape under tools:
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
            CompletionItem(label = tag, insertText = tag, kind = CompletionItemKind.CLASS, detail = "manifest")
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
    private fun namespaceDeclarationEdit(prefix: String, pos: XmlCompletionPosition): List<TextEdit> {
        val uri = NAMESPACE_URIS[prefix] ?: return emptyList()
        if (prefix in pos.declaredNamespaces || pos.namespaceInsertOffset < 0) return emptyList()
        val at = pos.namespaceInsertOffset
        return listOf(TextEdit(TextRange(at, at), " xmlns:$prefix=\"$uri\""))
    }

    private fun valueItemsFor(spec: AttributeSpec): List<CompletionItem> {
        val out = ArrayList<CompletionItem>()

        (spec.enumValues + spec.flags).forEach { v ->
            out += CompletionItem(v, v, CompletionItemKind.ENUM_CONSTANT, detail = "value")
        }
        if (spec.boolean) listOf("true", "false").forEach {
            out += CompletionItem(it, it, CompletionItemKind.ENUM_CONSTANT, detail = "boolean")
        }

        for (type in spec.resourceTypes) {
            if (type == ResourceType.ID) {
                // An id attribute almost always declares a new id.
                out += CompletionItem("@+id/", "@+id/", CompletionItemKind.SNIPPET, detail = "new id")
            }
            for (name in runCatching { resourceNames(type) }.getOrDefault(emptyList())) {
                val ref = "@${type.rClass}/$name"
                // Show the resolved value as the hint (@string/app_name → "CodeAssist", @color/primary → #6200EE)
                // so the right resource is pickable at a glance; fall back to the resource type for file resources.
                val resolved = runCatching { resourceValue(type, name) }.getOrNull()?.let(::valuePreview)
                out += CompletionItem(ref, ref, CompletionItemKind.FIELD, detail = resolved ?: type.rClass)
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

    private companion object {
        /** The standard Android namespace URIs, by conventional prefix — for auto-declaring a missing xmlns. */
        val NAMESPACE_URIS = mapOf(
            "android" to "http://schemas.android.com/apk/res/android",
            "app" to "http://schemas.android.com/apk/res-auto",
            "tools" to "http://schemas.android.com/tools",
        )
    }
}
