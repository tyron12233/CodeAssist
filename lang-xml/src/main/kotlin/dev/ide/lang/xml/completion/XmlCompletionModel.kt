package dev.ide.lang.xml.completion

import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.dom.TextRange

/**
 * What the caret sits on inside an XML document — the situation the completion engine derives and hands
 * to contributors. This is the seam that keeps `lang-xml` Android-agnostic: the engine computes *where*
 * the caret is (tag name / attribute name / attribute value) and the host's [XmlCompletionContributor]
 * decides *what* belongs there (widgets, attributes, resource references).
 */
enum class XmlCompletionKind {
    /** Typing an element name: `<Tex|` or `<|`. Candidates are the tags valid inside [parentTag]. */
    TAG_NAME,
    /** Typing an attribute name inside a start tag: `<TextView a|`. Candidates depend on [tag]. */
    ATTRIBUTE_NAME,
    /** Typing an attribute value: `android:text="@string/ho|"`. Candidates depend on [tag]+[attributeName]. */
    ATTRIBUTE_VALUE,
    /** Element text content (between tags) — rarely completed; offered for completeness. */
    TEXT,
    UNKNOWN,
}

data class XmlCompletionPosition(
    val kind: XmlCompletionKind,
    /** The element whose start tag the caret is in (ATTRIBUTE_*), or being typed (TAG_NAME). May be null. */
    val tag: String?,
    /** The nearest enclosing open element — the container a TAG_NAME completion would be a child of. */
    val parentTag: String?,
    /** For ATTRIBUTE_VALUE: the attribute name whose value is being typed. */
    val attributeName: String?,
    /** Attribute names already present on [tag] — so a contributor can avoid re-suggesting them. */
    val existingAttributes: Set<String>,
    /** Text typed so far for the token under the caret; candidates are prefix-matched against it. */
    val prefix: String,
    /** Range the accepted item replaces (the partial token under the caret). */
    val replacementRange: TextRange,
    /** Absolute path of the file being completed — lets a contributor specialize by res folder/file type. */
    val filePath: String,
    /** Namespace prefixes already declared on the root element (`xmlns:android`/`app`/`tools` → `{android,…}`),
     *  so a contributor can auto-declare a missing one when a namespaced attribute is accepted. */
    val declaredNamespaces: Set<String> = emptySet(),
    /** Offset just after the root element's name — where to splice a ` xmlns:prefix="uri"` declaration — or
     *  -1 when there's no root element to attach it to. */
    val namespaceInsertOffset: Int = -1,
)

/**
 * A source of XML completion candidates for a given [XmlCompletionPosition]. The host registers one (or
 * more) — e.g. an Android contributor backed by the SDK widget catalog and the module resource index.
 * Returned items are merged and prefix-filtered by the engine, so a contributor may return a superset.
 */
fun interface XmlCompletionContributor {
    fun contribute(position: XmlCompletionPosition): List<CompletionItem>
}
