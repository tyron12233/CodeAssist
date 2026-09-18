package dev.ide.kotlin.classfile

/**
 * The per-declaration table a type may be stored in INSTEAD of inline.
 *
 * Every place metadata holds a `Type` it also accepts a `*_type_id`: an index into the table its enclosing
 * class or package carries. The compiler writes whichever is smaller, so the same field is inline in one
 * artifact and an id in another, and a reader that handles only the inline form does not fail — it reports
 * the type as absent. `.kotlin_builtins` is the format where that is the common case, and a member whose
 * type comes back null is a member with no type at all (`Map.Entry.value` losing its `V`).
 *
 * Entries are held as raw bytes and decoded on demand, because a table entry may itself reference the table
 * and because the name scope a type resolves in belongs to the declaration asking, not to the table.
 *
 * [firstNullable] is the format's own compression: everything from that index on is the same type as some
 * earlier entry but nullable, so the flag is applied here rather than stored per entry. Absent means none.
 */
class TypeTable(private val types: List<ByteArray>, private val firstNullable: Int) {

    // Depth of the current resolve, so a table entry that (transitively) names itself stops rather than
    // recursing forever. A malformed artifact is the only way to reach it.
    private var depth = 0

    val size: Int get() = types.size

    /** The type at [id], decoded in [scope], or null when the id is out of range or the walk is too deep. */
    fun resolve(id: Int, names: NameResolver, scope: Map<Int, String>): KotlinType? {
        val bytes = types.getOrNull(id) ?: return null
        if (depth >= MAX_DEPTH) return null
        depth++
        try {
            val type = KotlinMetadata.readTypeMessage(bytes, names, scope, this)
            return if (firstNullable in 0..id && !type.isNullable) type.asNullable() else type
        } finally {
            depth--
        }
    }

    companion object {
        // metadata.proto, `message TypeTable`
        private const val TYPE = 1
        private const val FIRST_NULLABLE = 2

        private const val MAX_DEPTH = 32

        val EMPTY = TypeTable(emptyList(), -1)

        fun read(bytes: ByteArray): TypeTable {
            val types = ArrayList<ByteArray>()
            var firstNullable = -1
            val reader = ProtoReader(bytes)
            reader.forEachField { number, _ ->
                when (number) {
                    TYPE -> { types.add(reader.readBytes()); true }
                    FIRST_NULLABLE -> { firstNullable = reader.readInt(); true }
                    else -> false
                }
            }
            return TypeTable(types, firstNullable)
        }
    }
}
