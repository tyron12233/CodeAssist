package dev.ide.lang.xml

import dev.ide.lang.dom.NodeKind

/**
 * The XML-specific [NodeKind]s the tolerant parser produces, layered on top of the neutral
 * [NodeKind.ERROR]/[NodeKind.MISSING] recovery kinds. Editor features match on these ids; the set is
 * intentionally small (XML's grammar is tiny) and string-backed so a richer dialect can add its own.
 */
object XmlNodeKinds {
    /** The whole file. Mapped to the neutral COMPILATION_UNIT so generic tooling treats it as the root. */
    val DOCUMENT = NodeKind.COMPILATION_UNIT

    /** An element, `<TextView …>…</TextView>` or `<View …/>`. [XmlNode.name] holds the tag name. */
    val TAG = NodeKind("xml_tag")

    /** A single `name="value"` (or `name` with a missing value). [XmlNode.name] holds the attribute name. */
    val ATTRIBUTE = NodeKind("xml_attribute")

    /** The value of an attribute; [XmlNode.range] spans the text BETWEEN the quotes (the editable content). */
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
