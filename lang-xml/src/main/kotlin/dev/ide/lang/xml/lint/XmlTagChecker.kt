package dev.ide.lang.xml.lint

/**
 * The element-schema seam, the tag-level twin of [XmlAttributeChecker]: the host answers, for one element
 * occurrence, whether the tag names something that exists and whether that something may hold children. It
 * is what lets lang-xml flag a *non-existent view tag* (`<TextVeiw>`, `<com.example.Gone>`) without itself
 * knowing the Android widget set or the project classpath: lang-xml owns the *rules*
 * ([XmlLintRules.tagProblems] decides which element occurrences are eligible), the host owns the *catalog*
 * (SDK widgets, library/AAR View subclasses, the project's own source views).
 *
 * Deliberately conservative by construction, like the attribute seam: anything the host is not sure about is
 * [TagInfo.Indeterminate] and is never flagged. A host that cannot yet see the project's classes (a cold
 * index) must answer [TagInfo.Indeterminate] rather than guess, so a real custom view is never reported
 * missing while indexing is still running.
 */
fun interface XmlTagChecker {
    /** Describe element [tag] (as written, so possibly a fully-qualified class name) inside element
     *  [parentTag] (null at the document root), in the file at [filePath]. */
    fun describe(filePath: String, tag: String, parentTag: String?): TagInfo

    companion object {
        /** A checker that knows nothing: every element is [TagInfo.Indeterminate] (nothing is flagged).
         *  The default when no Android catalog is injected, keeping lang-xml usable standalone. */
        val NONE = XmlTagChecker { _, _, _ -> TagInfo.Indeterminate }
    }
}

/** What the host knows about an element occurrence: the verdict driving the element diagnostics. */
sealed interface TagInfo {
    /** Existence can't be judged (not a file whose tags name classes, catalog not loaded, index cold). */
    object Indeterminate : TagInfo

    /** The tag names nothing that exists here: an unknown widget or a missing class. Only returned when the
     *  host is confident its catalog is complete. [suggestions] are the closest known names (best first,
     *  possibly empty), for the "did you mean" fixes. */
    data class Unresolved(val suggestions: List<String> = emptyList()) : TagInfo

    /** A known element. [container] tells whether it may contain child elements (a `ViewGroup` can, a plain
     *  `View` cannot), or null when the host doesn't know. Null is never flagged. */
    data class Recognized(val container: Boolean? = null) : TagInfo
}
