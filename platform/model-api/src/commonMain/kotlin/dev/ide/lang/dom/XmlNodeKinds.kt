package dev.ide.lang.dom

/**
 * The XML-specific [NodeKind]s the tolerant parser produces, layered on top of the neutral
 * [NodeKind.ERROR]/[NodeKind.MISSING] recovery kinds. Editor features match on these ids; the set is
 * intentionally small (XML's grammar is tiny) and string-backed so a richer dialect can add its own.
 *
 * Published beside [NodeKind] rather than kept in `:lang-xml`, for the reason given on [KotlinNodeKinds]:
 * the ids are the contract, and a consumer that matches on them should not have to link a backend.
 */
object XmlNodeKinds {
    /** The whole file. Mapped to the neutral COMPILATION_UNIT so generic tooling treats it as the root. */
    val DOCUMENT = NodeKind.COMPILATION_UNIT

    /** An element, `<TextView …>…</TextView>` or `<View …/>`. The backend's own node holds the tag name. */
    val TAG = NodeKind("xml_tag")

    /** A single `name="value"` (or `name` with a missing value). The backend's own node holds the attribute name. */
    val ATTRIBUTE = NodeKind("xml_attribute")

    /** The value of an attribute; its range spans the text BETWEEN the quotes (the editable content). */
    val ATTR_VALUE = NodeKind("xml_attr_value")

    /** Character data between tags. */
    val TEXT = NodeKind("xml_text")

    /** `<!-- … -->`. */
    val COMMENT = NodeKind("xml_comment")

    /** `<![CDATA[ … ]]>`. */
    val CDATA = NodeKind("xml_cdata")

    /** The `<?xml … ?>` declaration / any processing instruction. */
    val PROLOG = NodeKind("xml_prolog")

    /** `<!DOCTYPE …>`. */
    val DOCTYPE = NodeKind("xml_doctype")
}
