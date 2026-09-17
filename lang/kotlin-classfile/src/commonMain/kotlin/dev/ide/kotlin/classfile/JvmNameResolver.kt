package dev.ide.kotlin.classfile

/**
 * Turns the name indices in Kotlin metadata into strings.
 *
 * Nothing in the protobuf is a name: everything is an index, and resolving one is not a lookup. `@Metadata`
 * carries a `StringTableTypes` message alongside the strings, and each of its records says how to BUILD the
 * string at that index: take a predefined constant, or a literal, or the string from `d2`; then optionally
 * substring it, replace a character, and map an internal name to a class id. The compiler does this to make
 * the annotation small, and a decoder that skips it gets `java/util/Map$Entry` where the answer is
 * `java/util/Map.Entry`, or an index into the wrong table entirely.
 *
 * Two details that are easy to miss and silently wrong:
 *
 *  * Records carry a `range`, and the list must be EXPANDED by it before indexing, because one record can stand for
 *    many consecutive indices. Index without expanding and every name after the first repeat is someone
 *    else's.
 *  * [PREDEFINED_STRINGS] is indexed BY POSITION, so its order is part of the format. It is reproduced here
 *    verbatim, including the two entries spelled with a dot.
 */
class JvmNameResolver(private val strings: Array<String>, records: List<Record>, private val localNames: Set<Int>) {

    /** One string-table instruction. Field numbers are from `jvm_metadata.proto`, `StringTableTypes.Record`. */
    class Record(
        val range: Int,
        val predefinedIndex: Int,
        val literal: String?,
        val operation: Int,
        val substring: IntArray,
        val replaceChar: IntArray,
    )

    private val expanded: List<Record> = buildList {
        for (record in records) repeat(record.range) { add(record) }
    }

    fun isLocalClassName(index: Int): Boolean = index in localNames

    /** The string at [index], built as its record instructs. */
    fun getString(index: Int): String {
        val record = expanded.getOrNull(index)
            ?: return strings.getOrNull(index) ?: ""

        var string = when {
            record.literal != null -> record.literal
            record.predefinedIndex in PREDEFINED_STRINGS.indices -> PREDEFINED_STRINGS[record.predefinedIndex]
            else -> strings.getOrNull(index) ?: return ""
        }

        if (record.substring.size >= 2) {
            val begin = record.substring[0]
            val end = record.substring[1]
            if (begin in 0..end && end <= string.length) string = string.substring(begin, end)
        }

        if (record.replaceChar.size >= 2) {
            string = string.replace(record.replaceChar[0].toChar(), record.replaceChar[1].toChar())
        }

        return when (record.operation) {
            OPERATION_INTERNAL_TO_CLASS_ID -> string.replace('$', '.')
            OPERATION_DESC_TO_CLASS_ID ->
                (if (string.length >= 2) string.substring(1, string.length - 1) else string).replace('$', '.')

            else -> string
        }
    }

    /**
     * A class name as Kotlin spells it: dots between packages, dots between nested names.
     *
     * A LOCAL class (one declared inside a function, or an anonymous object) is marked with a leading dot.
     * That convention is the only thing distinguishing it from a top-level class of the same name, and it is
     * what `local_name` in the string table exists to record. Dropping it is not cosmetic: two different
     * classes then answer to one name.
     */
    fun getClassName(index: Int): String {
        val name = getString(index).replace('/', '.')
        return if (isLocalClassName(index)) ".$name" else name
    }

    companion object {
        private const val OPERATION_INTERNAL_TO_CLASS_ID = 1
        private const val OPERATION_DESC_TO_CLASS_ID = 2

        // jvm_metadata.proto, `StringTableTypes`
        private const val FIELD_RECORD = 1
        private const val FIELD_LOCAL_NAME = 5

        // ...and `Record`
        private const val RECORD_RANGE = 1
        private const val RECORD_PREDEFINED_INDEX = 2
        private const val RECORD_OPERATION = 3
        private const val RECORD_SUBSTRING_INDEX = 4
        private const val RECORD_REPLACE_CHAR = 5
        private const val RECORD_STRING = 6

        /** Read a `StringTableTypes` message. */
        fun read(bytes: ByteArray, strings: Array<String>): JvmNameResolver {
            val reader = ProtoReader(bytes)
            val records = ArrayList<Record>()
            val localNames = HashSet<Int>()
            reader.forEachField { number, wireType ->
                when (number) {
                    FIELD_RECORD -> {
                        records.add(readRecord(reader.readMessage()))
                        true
                    }

                    FIELD_LOCAL_NAME -> {
                        // Packed when repeated, but a single value is written bare, so both shapes occur.
                        if (wireType == 2) {
                            val packed = ProtoReader(reader.readBytes())
                            while (packed.hasMore) localNames.add(packed.readInt())
                        } else {
                            localNames.add(reader.readInt())
                        }
                        true
                    }

                    else -> false
                }
            }
            return JvmNameResolver(strings, records, localNames)
        }

        private fun readRecord(reader: ProtoReader): Record {
            var range = 1
            var predefined = -1
            var literal: String? = null
            var operation = 0
            var substring = IntArray(0)
            var replaceChar = IntArray(0)
            reader.forEachField { number, _ ->
                when (number) {
                    RECORD_RANGE -> { range = reader.readInt(); true }
                    RECORD_PREDEFINED_INDEX -> { predefined = reader.readInt(); true }
                    RECORD_OPERATION -> { operation = reader.readInt(); true }
                    RECORD_SUBSTRING_INDEX -> { substring = reader.readPackedInts(); true }
                    RECORD_REPLACE_CHAR -> { replaceChar = reader.readPackedInts(); true }
                    RECORD_STRING -> { literal = reader.readString(); true }
                    else -> false
                }
            }
            return Record(range, predefined, literal, operation, substring, replaceChar)
        }

        /**
         * The constants a record may name by index.
         *
         * Verbatim from the compiler, ORDER INCLUDED: `predefinedIndex` is a position in this list, so a
         * reordering or a dropped entry renames types rather than failing.
         */
        val PREDEFINED_STRINGS: List<String> = listOf(
            "kotlin/Any", "kotlin/Nothing", "kotlin/Unit", "kotlin/Throwable", "kotlin/Number",
            "kotlin/Byte", "kotlin/Double", "kotlin/Float", "kotlin/Int",
            "kotlin/Long", "kotlin/Short", "kotlin/Boolean", "kotlin/Char",
            "kotlin/CharSequence", "kotlin/String", "kotlin/Comparable", "kotlin/Enum",
            "kotlin/Array",
            "kotlin/ByteArray", "kotlin/DoubleArray", "kotlin/FloatArray", "kotlin/IntArray",
            "kotlin/LongArray", "kotlin/ShortArray", "kotlin/BooleanArray", "kotlin/CharArray",
            "kotlin/Cloneable", "kotlin/Annotation",
            "kotlin/collections/Iterable", "kotlin/collections/MutableIterable",
            "kotlin/collections/Collection", "kotlin/collections/MutableCollection",
            "kotlin/collections/List", "kotlin/collections/MutableList",
            "kotlin/collections/Set", "kotlin/collections/MutableSet",
            "kotlin/collections/Map", "kotlin/collections/MutableMap",
            "kotlin/collections/Map.Entry", "kotlin/collections/MutableMap.MutableEntry",
            "kotlin/collections/Iterator", "kotlin/collections/MutableIterator",
            "kotlin/collections/ListIterator", "kotlin/collections/MutableListIterator",
        )
    }
}
