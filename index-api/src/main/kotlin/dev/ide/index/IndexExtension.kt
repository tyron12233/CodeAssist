package dev.ide.index

interface IndexExtension<K : Any, V : Any> {
    val id: IndexId
    val version: Int
    val keyDescriptor: KeyDescriptor<K>
    val valueExternalizer: Externalizer<V>
    val inputFilter: InputFilter
    val matching: MatchingMode

    /** Map one unit to its entries. Pure + deterministic. */
    fun index(input: IndexInput): Map<K, Collection<V>>
}