package dev.ide.lang.xml.lint

/**
 * The attribute-schema seam: the host answers, for one attribute occurrence, what its element allows. This is
 * what lets lang-xml flag a *potentially wrong attribute* without itself knowing the Android schema - lang-xml
 * owns the *rules* ([XmlLintRules.attributeProblems] decides which attribute occurrences are eligible), the
 * host owns the *schema* (the SDK-derived layout metadata, manifest catalog, library `attrs.xml`, …).
 *
 * Deliberately conservative by construction: every answer the host is unsure about is [AttrInfo.Indeterminate],
 * so an attribute is only flagged when the host positively knows the element's full attribute set (an unknown
 * attribute) or the attribute's closed value set (an out-of-range value). Custom/unknown tags, incomplete
 * curated schemas, and cold metadata all surface as [AttrInfo.Indeterminate].
 */
fun interface XmlAttributeChecker {
    /** Describe attribute [attribute] (its prefixed name, e.g. `android:visibility`) occurring on element
     *  [tag] inside element [parentTag] (null at the document root), in the file at [filePath]. */
    fun describe(filePath: String, tag: String, parentTag: String?, attribute: String): AttrInfo

    companion object {
        /** A checker that knows nothing - every attribute is [AttrInfo.Indeterminate] (nothing is flagged).
         *  The default when no Android metadata is injected, keeping lang-xml usable standalone. */
        val NONE = XmlAttributeChecker { _, _, _, _ -> AttrInfo.Indeterminate }
    }
}

/** What the host knows about an attribute occurrence - the verdict driving the attribute diagnostics. */
sealed interface AttrInfo {
    /** Validity can't be judged (custom/unknown tag, incomplete schema, or metadata not yet loaded). Don't flag. */
    object Indeterminate : AttrInfo

    /** The element's attribute set is fully known and [attribute] is not part of it → a likely typo / wrong
     *  attribute. Only returned when the host is confident the set is complete (e.g. a framework widget). */
    object NotAllowed : AttrInfo

    /** A recognized attribute. [allowedValues] is the *closed* literal value set (an enum, a flag set, or
     *  `{true,false}`), or null when the value is free-form (a dimension, color, string, `@resource`
     *  reference, …) and must not be value-checked. [isFlag] = the value may be a `|`-separated combination
     *  of [allowedValues]. */
    data class Recognized(val allowedValues: Set<String>?, val isFlag: Boolean = false) : AttrInfo
}
