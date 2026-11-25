package dev.ide.index

interface IndexExtension<K : Any, V : Any> {
    val id: IndexId
    val version: Int
    val keyDescriptor: KeyDescriptor<K>
    val valueExternalizer: Externalizer<V>
    val inputFilter: InputFilter
    val matching: MatchingMode

    /**
     * Project-source file suffixes this index needs read, beyond the ones the IDE always reads (SPI 3.9.0).
     *
     * The source walk reads `.java` and `.kt` files under each module's source roots and `.xml` under its
     * resource roots, and nothing else, so an index over another language's files (`.swift`, `.dart`, `.proto`)
     * names their suffixes here, dot included. Those files are then walked under the same source roots, kept
     * current on save and on create, and handed to EVERY registered index, so [inputFilter] still has to select
     * them. Empty by default: an index over the IDE's own file types needs nothing here.
     */
    val sourceFileExtensions: Set<String> get() = emptySet()

    /** Map one unit to its entries. Pure + deterministic. */
    fun index(input: IndexInput): Map<K, Collection<V>>
}
