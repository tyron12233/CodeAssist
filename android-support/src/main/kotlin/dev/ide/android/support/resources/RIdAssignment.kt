package dev.ide.android.support.resources

/**
 * Deterministic, aapt-shaped resource id assignment over a [ResourceRepository]: `0x7fTTNNNN`, where `TT` is
 * a per-type byte (present types, sorted by `R` class name) and `NNNN` is the entry index (names sorted). The
 * synthetic `R` emits these ids as the field constants, so user code compiled against that `R` gets the same
 * ints the preview's bridge maps back from at runtime (`R.styleable.MyChart` int[] → attr names →
 * resolved values). Because the assignment is a pure function of the (sorted) resource set, the `R` the
 * preview compiles against and the resolver's reverse map agree without any on-disk persistence — and it
 * stays stable across runs as long as the resources do (the §6.1 caching goal; a persisted map can layer on
 * later if reshuffling on add/remove becomes a problem).
 */
class RIdAssignment(repo: ResourceRepository) {

    private val byKey = HashMap<Pair<ResourceType, String>, Int>()
    private val byId = HashMap<Int, Pair<ResourceType, String>>()

    init {
        repo.types().sortedBy { it.rClass }.forEachIndexed { typeIndex, type ->
            val typeId = typeIndex + 1 // 0 is reserved
            repo.names(type).sorted().forEachIndexed { entryIndex, name ->
                val id = (0x7f shl 24) or (typeId shl 16) or (entryIndex and 0xFFFF)
                byKey[type to name] = id
                byId[id] = type to name
            }
        }
    }

    /** The assigned id for `R.<type>.<name>`, or null if absent. */
    fun id(type: ResourceType, name: String): Int? = byKey[type to name]

    /** The (type, name) a previously assigned id maps back to — the bridge's `int → attr name` lookup. */
    fun nameOf(id: Int): Pair<ResourceType, String>? = byId[id]

    /** The `R.styleable.<name>` int[] (attr ids in declaration order; unknown attrs dropped). */
    fun styleableArray(repo: ResourceRepository, styleableName: String): IntArray =
        repo.styleableAttrs(styleableName).mapNotNull { id(ResourceType.ATTR, it) }.toIntArray()
}
