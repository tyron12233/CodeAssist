package dev.ide.core

import dev.ide.android.support.metadata.AndroidSdkMetadata
import dev.ide.lang.xml.lint.TagInfo
import dev.ide.lang.xml.lint.XmlTagChecker

/**
 * The Android half of XML element analysis: answers [XmlTagChecker]'s questions from the same catalogs XML
 * completion offers tags from - the SDK-derived [AndroidSdkMetadata] (framework widgets, with their
 * ViewGroup-ness) plus the module's library/AAR and project-source `View` subclasses. `lang-xml` decides
 * *which* elements to check; this decides whether Android can actually inflate one, so the unknown-element
 * diagnostic never contradicts what completion suggested.
 *
 * Deliberately conservative (it answers [TagInfo.Indeterminate] unless it is sure):
 *  - Only files under `res/layout` are judged. Every other XML flavor has element names that aren't classes
 *    (`res/xml` preferences, menus, drawables, values, the manifest), so a missing class means nothing there.
 *  - [projectViews] returning null means "the project's own classes aren't known yet" (a cold index): nothing
 *    is flagged at all, so a real custom view is never reported missing while indexing runs.
 *  - The framework widget list is exhaustive by construction (every public concrete `View` subclass in
 *    `android.jar`), which is what makes an unqualified non-widget tag a real error: `LayoutInflater` resolves
 *    an unqualified name only against the framework packages, so a custom view MUST be fully qualified.
 *  - A name the view catalog doesn't have is still cleared by [classExists]: the catalogs only see classes
 *    whose `View` ancestry they could walk, so a class that merely *exists* is treated as recognized (with
 *    unknown containment) rather than reported missing.
 *  - The inflater's own pseudo-elements (`<merge>`, `<include>`, `<fragment>`, `<view>`, data binding's
 *    `<layout>`/`<data>`, …) are recognized without a class, and their containment is left unknown unless it
 *    is certain, so the "cannot contain children" check can't fire on them by accident.
 */
class AndroidXmlTagChecker(
    private val layout: () -> AndroidSdkMetadata = { AndroidSdkMetadata.bundled() },
    /**
     * Every non-framework `View` class the file's module can name (fully-qualified, or a bare name for the
     * default package) mapped to whether it is a `ViewGroup`, or null while that set is unknown.
     */
    private val projectViews: (String) -> Map<String, Boolean>? = { null },
    /** Does a class of this name exist for the file at all (project source or classpath)? The last word
     *  before an element is called missing, so a real class is never flagged for not looking like a View. */
    private val classExists: (String, String) -> Boolean = { _, _ -> false },
) : XmlTagChecker {

    override fun describe(filePath: String, tag: String, parentTag: String?): TagInfo {
        val path = filePath.replace('\\', '/')
        if (!path.contains("/res/layout")) return TagInfo.Indeterminate
        SPECIAL_ELEMENTS[tag]?.let { return it }
        val widgets = widgetsOf(layout())
        if (widgets.isEmpty()) return TagInfo.Indeterminate    // no SDK metadata → we know nothing
        val views = projectViews(filePath)
        views?.get(tag)?.let { return TagInfo.Recognized(container = it) }
        // A framework widget, whether written bare (`TextView`) or spelled out (`android.widget.TextView`).
        if (!tag.contains('.') || tag.startsWith("android.")) {
            widgets[tag.substringAfterLast('.')]?.let { return TagInfo.Recognized(container = it) }
        }
        // Not a framework widget and not a known project/library view. Only a *known* class set can call it
        // missing, so stay quiet until the project's views are in.
        if (views == null) return TagInfo.Indeterminate
        // A class the view catalogs missed (they only see what they could walk a View ancestry for, e.g. not a
        // source class extending an AAR view) but that plainly exists: recognized, containment unknown.
        if (classExists(filePath, tag)) return TagInfo.Recognized(container = null)
        return TagInfo.Unresolved(suggestions(tag, widgets.keys, views.keys))
    }

    /** The closest known element names to [tag], best first: framework widgets and project views whose simple
     *  name is within a length-scaled edit distance of what was typed (so `TextVeiw` → `TextView`). */
    private fun suggestions(tag: String, widgets: Set<String>, views: Set<String>): List<String> {
        val typed = tag.substringAfterLast('.')
        val budget = when {
            typed.length <= 4 -> 1
            typed.length <= 8 -> 2
            else -> 3
        }
        val scored = ArrayList<Pair<String, Int>>()
        for (w in widgets) editDistance(typed, w, budget)?.let { scored += w to it }
        // A qualified typo (`com.exmaple.MyView`) is matched on the simple name but suggested in full, since
        // that is what has to be written in the layout.
        for (v in views) editDistance(typed, v.substringAfterLast('.'), budget)?.let { scored += v to it }
        return scored.sortedWith(compareBy({ it.second }, { it.first.length }, { it.first }))
            .map { it.first }.distinct().take(3)
    }

    /** Levenshtein distance between [a] and [b], or null once it provably exceeds [max] (the early exit keeps
     *  this cheap over the whole widget catalog). */
    private fun editDistance(a: String, b: String, max: Int): Int? {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > max) return null
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var best = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                best = minOf(best, cur[j])
            }
            if (best > max) return null
            val swap = prev; prev = cur; cur = swap
        }
        return prev[b.length].takeIf { it <= max }
    }

    /** Framework simple name → is-it-a-ViewGroup, memoized on the metadata instance (one map per SDK load). */
    private fun widgetsOf(md: AndroidSdkMetadata): Map<String, Boolean> {
        cachedWidgets?.let { if (it.first === md) return it.second }
        val map = md.childTagsFor(null).associate { it.tag to it.isViewGroup }
        cachedWidgets = md to map
        return map
    }

    @Volatile
    private var cachedWidgets: Pair<AndroidSdkMetadata, Map<String, Boolean>>? = null

    private companion object {
        /**
         * Layout elements that name no class: `LayoutInflater`'s own pseudo-tags and data binding's wrapper
         * elements. Containment is only stated where it is certain (`<merge>` and `<layout>` hold children,
         * `<requestFocus>`/`<tag>`/`<variable>`/`<import>` never do); the rest stay unknown (null) so the
         * illegal-child check leaves them alone.
         */
        val SPECIAL_ELEMENTS: Map<String, TagInfo> = mapOf(
            "merge" to TagInfo.Recognized(container = true),
            "include" to TagInfo.Recognized(container = null),
            "fragment" to TagInfo.Recognized(container = null),
            "view" to TagInfo.Recognized(container = null),
            "blink" to TagInfo.Recognized(container = true),
            "requestFocus" to TagInfo.Recognized(container = false),
            "tag" to TagInfo.Recognized(container = false),
            // Data binding: <layout> wraps the real root, <data> groups <variable>/<import>.
            "layout" to TagInfo.Recognized(container = true),
            "data" to TagInfo.Recognized(container = true),
            "variable" to TagInfo.Recognized(container = false),
            "import" to TagInfo.Recognized(container = false),
        )
    }
}
