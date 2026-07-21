package dev.ide.lang.imports

/**
 * Language-neutral import ordering. Both the JDT (Java) and Kotlin backends reduce their parsed imports to
 * [ImportEntry]s, order them here, and render them back with their own syntax (`import a.b.C;` vs
 * `import a.b.C`). Two operations share this one policy so auto-import placement and the standalone
 * "Optimize Imports" command agree:
 *
 *  - [planInsert] splices ONE new import into the correct sorted position among the existing ones, touching
 *    nothing else (auto-import — completion accept + the unresolved-reference quick-fix).
 *  - [organize] + [renderBlocks] re-emit the WHOLE import section: de-duplicated, wildcard-collapsed per the
 *    thresholds, sorted, and split into blocks (regular / static) separated by a blank line (Optimize Imports).
 *
 * The layout mirrors IntelliJ's default "grouped, statics split" scheme; the thresholds and the static split
 * are the only knobs (see [ImportLayoutConfig]).
 */
object ImportLayout {

    /**
     * One import reduced to what ordering needs. [fqn] is the fully-qualified target WITHOUT a trailing `.*`
     * (a wildcard keeps its package/type in [fqn] and sets [isWildcard]). [isStatic] marks a Java
     * `import static` (Kotlin has none, so it is always false there). [alias] is Kotlin's `import a.b.C as D`
     * (null = none; Java has no aliases). Aliased imports are never wildcard-collapsed (the alias would be
     * lost).
     */
    data class ImportEntry(
        val fqn: String,
        val isStatic: Boolean = false,
        val isWildcard: Boolean = false,
        val alias: String? = null,
    ) {
        /** The package (regular) or declaring type (static) an on-demand import would cover: [fqn] itself for
         *  a wildcard, else the segment before the last dot. */
        val container: String get() = if (isWildcard) fqn else fqn.substringBeforeLast('.', "")

        /** Lexicographic sort key: the displayed path (a wildcard carries its `.*`, so it sorts among its
         *  siblings). The alias never affects ordering — the imported path does. */
        internal val sortKey: String get() = if (isWildcard) "$fqn.*" else fqn

        /** Identity for de-duplication: two directives are the same import when these all match. */
        internal val dedupeKey: List<Any?> get() = listOf(fqn, isStatic, isWildcard, alias)
    }

    /**
     * Layout policy. Defaults are IntelliJ's Java scheme; [KOTLIN] turns off the static split (Kotlin has no
     * static imports). A `*CountToUseWildcard` of 0 disables that collapse.
     */
    data class ImportLayoutConfig(
        /** Keep static imports in their own block, separated from the regular block by a blank line. */
        val separateStaticImports: Boolean = true,
        /** The static block precedes the regular one (some house styles) vs follows it (IntelliJ default). */
        val staticFirst: Boolean = false,
        /** Collapse this-many-or-more single imports sharing a package into one `pkg.*`. 0 disables. */
        val classCountToUseWildcard: Int = 5,
        /** Same, for static-member imports collapsing to `Type.*`. 0 disables. */
        val nameCountToUseStaticWildcard: Int = 3,
        /** Keep exactly one blank line between the package directive and the import block. */
        val blankLineAfterPackage: Boolean = true,
    ) {
        companion object {
            val JAVA = ImportLayoutConfig()
            val KOTLIN = ImportLayoutConfig(separateStaticImports = false)
        }
    }

    /** An existing import with the document offsets of its line: [start] at the directive's first character,
     *  [endExclusive] just past its trailing newline (so `[start, endExclusive)` deletes the whole line). */
    data class PositionedImport(val entry: ImportEntry, val start: Int, val endExclusive: Int)

    /** The splice a [planInsert] resolves to: insert [text] at [offset] (a zero-width edit). */
    data class InsertPlan(val offset: Int, val text: String)

    private val comparator = Comparator<ImportEntry> { a, b -> a.sortKey.compareTo(b.sortKey) }

    /**
     * De-duplicate, wildcard-collapse (per [config]'s thresholds), and sort [entries] into output-ordered
     * blocks: one regular block and, when [ImportLayoutConfig.separateStaticImports], one static block (order
     * per [ImportLayoutConfig.staticFirst]). Empty blocks are dropped. Render with [renderBlocks].
     */
    fun organize(entries: List<ImportEntry>, config: ImportLayoutConfig): List<List<ImportEntry>> {
        val collapsed = collapseWildcards(dedupe(entries), config)
        if (!config.separateStaticImports) {
            val all = collapsed.sortedWith(comparator)
            return if (all.isEmpty()) emptyList() else listOf(all)
        }
        val statics = collapsed.filter { it.isStatic }.sortedWith(comparator)
        val regular = collapsed.filter { !it.isStatic }.sortedWith(comparator)
        val ordered = if (config.staticFirst) listOf(statics, regular) else listOf(regular, statics)
        return ordered.filter { it.isNotEmpty() }
    }

    /** Render [organize]'s blocks into one string: each entry via [render] on its own line, blocks separated
     *  by a single blank line. No leading or trailing newline — the caller positions the block. */
    fun renderBlocks(blocks: List<List<ImportEntry>>, render: (ImportEntry) -> String): String =
        blocks.joinToString("\n\n") { block -> block.joinToString("\n", transform = render) }

    /**
     * Where to splice a single new [entry] among the file's [existing] imports (in document order). Returns
     * the insertion offset and the exact text (rendered + newlines), or null when [entry] is already present.
     * [render] renders one import line WITHOUT a trailing newline. [packageLineEnd] is the offset just past
     * the package directive's newline (null when there is no package directive); [importRegionStart] is where
     * the first import would go with neither a package nor existing imports (usually 0). [text] is the whole
     * buffer, read only to avoid doubling an existing blank line after the package.
     */
    fun planInsert(
        entry: ImportEntry,
        existing: List<PositionedImport>,
        config: ImportLayoutConfig,
        text: CharSequence,
        packageLineEnd: Int?,
        importRegionStart: Int = 0,
        render: (ImportEntry) -> String,
    ): InsertPlan? {
        if (existing.any { it.entry.dedupeKey == entry.dedupeKey }) return null // already imported
        val line = render(entry)
        val sameBlock = existing.filter { sameBlock(it.entry, entry, config) }

        // Common case: the entry's block already has imports — insert in sorted position within it.
        if (sameBlock.isNotEmpty()) {
            val after = sameBlock.lastOrNull { comparator.compare(it.entry, entry) <= 0 }
            return if (after == null) {
                // Sorts before every same-block import: splice at the first one's start.
                InsertPlan(sameBlock.first().start, "$line\n")
            } else {
                InsertPlan(after.endExclusive, "$line\n")
            }
        }

        // The entry starts a NEW block. When a sibling block (static vs regular) already exists, place the new
        // block on the correct side of it with one blank line between.
        val otherBlock = existing // everything is the other block here (sameBlock is empty)
        if (otherBlock.isNotEmpty() && config.separateStaticImports) {
            val entryComesFirst = if (config.staticFirst) entry.isStatic else !entry.isStatic
            return if (entryComesFirst) {
                // New block precedes the existing one: splice before the existing block, add a trailing blank.
                InsertPlan(otherBlock.minOf { it.start }, "$line\n\n")
            } else {
                // New block follows the existing one: splice after it, add a leading blank.
                InsertPlan(otherBlock.maxOf { it.endExclusive }, "\n$line\n")
            }
        }

        // No imports at all: place the block after the package (one blank line) or at the region start. Skip
        // any blank line(s) already following the package so the import lands after them (and the existing
        // blank serves as the separator); add one blank line only when the package is immediately followed by
        // code.
        if (packageLineEnd != null) {
            var i = packageLineEnd
            var sawBlank = false
            while (i < text.length && text[i] == '\n') { i++; sawBlank = true }
            val prefix = if (config.blankLineAfterPackage && !sawBlank) "\n" else ""
            return InsertPlan(i, "$prefix$line\n")
        }
        return InsertPlan(importRegionStart, "$line\n")
    }

    /** Two entries share a block when they are the same static-ness (or the static split is off). */
    private fun sameBlock(a: ImportEntry, b: ImportEntry, config: ImportLayoutConfig): Boolean =
        !config.separateStaticImports || a.isStatic == b.isStatic

    private fun dedupe(entries: List<ImportEntry>): List<ImportEntry> {
        val seen = HashSet<List<Any?>>()
        return entries.filter { seen.add(it.dedupeKey) }
    }

    /** Replace >= threshold single imports sharing a (container, static-ness) with one wildcard. Existing
     *  wildcards and aliased imports pass through untouched; a collapse that duplicates an existing wildcard is
     *  de-duplicated away. */
    private fun collapseWildcards(entries: List<ImportEntry>, config: ImportLayoutConfig): List<ImportEntry> {
        val passthrough = entries.filter { it.isWildcard || it.alias != null }
        val collapsible = entries.filter { !it.isWildcard && it.alias == null }
        val out = ArrayList<ImportEntry>(passthrough)
        collapsible.groupBy { it.container to it.isStatic }.forEach { (key, group) ->
            val (container, isStatic) = key
            val threshold = if (isStatic) config.nameCountToUseStaticWildcard else config.classCountToUseWildcard
            if (threshold > 0 && container.isNotEmpty() && group.size >= threshold) {
                out.add(ImportEntry(container, isStatic = isStatic, isWildcard = true))
            } else {
                out.addAll(group)
            }
        }
        return dedupe(out)
    }
}
