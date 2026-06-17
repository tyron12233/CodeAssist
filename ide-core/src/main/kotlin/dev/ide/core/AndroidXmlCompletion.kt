package dev.ide.core

import dev.ide.android.support.metadata.AndroidSdkMetadata
import dev.ide.android.support.metadata.AttributeSpec
import dev.ide.android.support.metadata.LayoutMetadata
import dev.ide.android.support.metadata.Widget
import dev.ide.android.support.resources.AndroidManifestCatalog
import dev.ide.android.support.resources.DrawableXmlCatalog
import dev.ide.android.support.resources.ResourceType
import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
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
            XmlCompletionKind.ATTRIBUTE_NAME -> {
                val specs = when (flavor) {
                    Flavor.MANIFEST -> AndroidManifestCatalog.attributesFor(position.tag)
                    Flavor.DRAWABLE -> DrawableXmlCatalog.attributesFor(position.tag)
                    Flavor.LAYOUT -> mergedAttributes(position)
                }
                specs.filter { it.name !in position.existingAttributes }.map(::attributeItem)
            }
            XmlCompletionKind.ATTRIBUTE_VALUE -> {
                val spec = when (flavor) {
                    Flavor.MANIFEST -> AndroidManifestCatalog.attribute(position.tag, position.attributeName)
                    Flavor.DRAWABLE -> DrawableXmlCatalog.attribute(position.tag, position.attributeName)
                    Flavor.LAYOUT -> mergedAttributes(position).firstOrNull { it.name == position.attributeName }
                }
                spec?.let(::valueItemsFor) ?: emptyList()
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

    private fun attributeItem(spec: AttributeSpec): CompletionItem {
        // Insert `name=""` and land the caret between the quotes, ready for value completion.
        val insert = "${spec.name}=\"\""
        return CompletionItem(
            label = spec.name,
            insertText = insert,
            kind = CompletionItemKind.FIELD,
            detail = valueHint(spec),
            caret = CaretAction.At(insert.length - 1),
        )
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
                out += CompletionItem(ref, ref, CompletionItemKind.FIELD, detail = type.rClass)
            }
        }
        return out
    }

    private fun valueHint(spec: AttributeSpec): String? = when {
        spec.enumValues.isNotEmpty() -> "enum"
        spec.flags.isNotEmpty() -> "flags"
        spec.boolean -> "boolean"
        spec.resourceTypes.isNotEmpty() -> spec.resourceTypes.joinToString("|") { "@${it.rClass}" }
        else -> null
    }
}
