package dev.ide.android.support.metadata

import dev.ide.index.IndexId
import dev.ide.index.SubtypeIndex
import dev.ide.index.SubtypeValue

/**
 * Discovers custom `View` subclasses declared in the PROJECT's own source (`.java`/`.kt`, not yet compiled)
 * so the layout editor offers them as tags AND lets them inherit their framework superclass's `android:`
 * attributes — the source-side complement of [CustomViewScanner] (which reads compiled library/AAR jars).
 *
 * It walks the direct-inheritor [SubtypeIndex] (source producers) breadth-first from the framework widget
 * names: a source class extending `Button` is a View, a source class extending THAT is a View, and so on.
 * The result reuses [CustomViewScanner.Scan] — the FQN tags plus the simple-name → super-simple-name ancestry
 * that `AndroidSdkMetadata.withCustomHierarchy` bridges into the framework styleables for attribute inheritance.
 *
 * Pure over an injected [query] (`(indexId, key) -> hits`) so it unit-tests without a live index. Empty until
 * the source subtype index has built (the "dumb until indexed" contract) — then source custom views appear.
 */
object SourceCustomViewResolver {

    private val SOURCE_IDS = listOf(SubtypeIndex.JAVA_SOURCE, SubtypeIndex.KOTLIN_SOURCE)

    /**
     * @param frameworkWidgets simple name → is-ViewGroup for every framework widget (the BFS seed); the caller
     *   includes `View`/`ViewGroup` themselves.
     * @param query the index lookup, `(id, key) -> direct subtypes` (e.g. `indexService::exact`).
     * @param budget a safety cap on frontier expansions (a pathological hierarchy can't loop forever).
     */
    fun resolve(
        frameworkWidgets: Map<String, Boolean>,
        query: (IndexId, String) -> Sequence<SubtypeValue>,
        budget: Int = 5000,
    ): CustomViewScanner.Scan {
        if (frameworkWidgets.isEmpty()) return CustomViewScanner.Scan(emptyList(), emptyMap())
        val seeds = frameworkWidgets.keys
        val isGroup = HashMap(frameworkWidgets)         // simple name → is-ViewGroup (grows as views are found)
        val widgets = LinkedHashMap<String, Widget>()   // fqn → Widget (deduped)
        val superNames = LinkedHashMap<String, String>() // simple name → super simple name
        val visited = HashSet<String>()
        val frontier = ArrayDeque(seeds)
        var left = budget
        while (frontier.isNotEmpty() && left-- > 0) {
            val superShort = frontier.removeFirst()
            if (!visited.add(superShort)) continue
            val group = isGroup[superShort] ?: false
            val isSeed = superShort in seeds
            for (id in SOURCE_IDS) for (v in query(id, SubtypeIndex.key(superShort))) {
                // At a framework seed, require the super to actually resolve to android.* so a stray
                // `class X : Button` where Button isn't the framework one doesn't get pulled in.
                if (isSeed && !v.supertype.startsWith("android.")) continue
                if (isFrameworkOrRuntimeFqn(v.fqn)) continue
                val simple = v.fqn.substringAfterLast('.')
                superNames.putIfAbsent(simple, superShort)
                widgets.putIfAbsent(v.fqn, Widget(v.fqn, group))
                isGroup.putIfAbsent(simple, group)
                frontier.addLast(simple)
            }
        }
        return CustomViewScanner.Scan(widgets.values.sortedBy { it.tag }, superNames)
    }

    private fun isFrameworkOrRuntimeFqn(fqn: String): Boolean =
        fqn.startsWith("android.") || fqn.startsWith("java.") || fqn.startsWith("javax.") || fqn.startsWith("kotlin.")
}
