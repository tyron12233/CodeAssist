package dev.ide.kotlin.classfile

/**
 * The name resolver of a `.kotlin_builtins` fragment.
 *
 * A fragment carries its own two tables: a flat array of strings, and a table of QUALIFIED names in which
 * each entry is a short name plus the index of its parent. A class name is therefore a walk up that chain,
 * not a lookup, and what separates two links depends on the parent's kind — a package parent joins with a
 * slash, a class parent with a dot, which is what keeps `kotlin/collections/Map.Entry` distinguishable from
 * a package called `Map`.
 *
 * Simpler than [JvmNameResolver] because there is nothing to compress against: `@Metadata` lives inside a
 * class file and pays for every byte, while a `.kotlin_builtins` file is a file.
 */
class BuiltInsNameResolver(
    private val strings: List<String>,
    private val qualifiedNames: List<QualifiedName>,
) : NameResolver {

    /** One entry of the `QualifiedNameTable`: a short name, its parent's index, and what kind of name it is. */
    class QualifiedName(val parent: Int, val shortName: Int, val kind: Int)

    override fun getString(index: Int): String = strings.getOrNull(index) ?: ""

    /**
     * The qualified name at [index], walked parent-first.
     *
     * A LOCAL name (one declared inside a function) is marked with a leading dot, the same convention
     * [JvmNameResolver] uses, so a consumer cannot tell the two formats apart by their answers.
     */
    override fun getClassName(index: Int): String {
        val packageParts = ArrayList<String>()
        val classParts = ArrayList<String>()
        var local = false
        var current = index
        // A cycle would be a malformed table; the table's own size bounds an honest walk, so use it as the
        // ceiling rather than trusting the parent chain to terminate.
        var steps = 0
        while (current >= 0 && steps <= qualifiedNames.size) {
            val entry = qualifiedNames.getOrNull(current) ?: break
            val short = getString(entry.shortName)
            when (entry.kind) {
                KIND_PACKAGE -> packageParts.add(short)
                KIND_LOCAL -> { classParts.add(short); local = true }
                else -> classParts.add(short)
            }
            current = entry.parent
            steps++
        }
        packageParts.reverse()
        classParts.reverse()
        val relative = classParts.joinToString(".")
        val name = if (packageParts.isEmpty()) relative else packageParts.joinToString(".") + "." + relative
        return if (local) ".$name" else name
    }

    companion object {
        // metadata.proto, `QualifiedNameTable.QualifiedName.Kind`
        private const val KIND_PACKAGE = 1
        private const val KIND_LOCAL = 2

        // metadata.proto, `StringTable`
        private const val STRING_TABLE_STRING = 1

        // metadata.proto, `QualifiedNameTable` and its nested `QualifiedName`
        private const val QUALIFIED_NAME_TABLE_NAME = 1
        private const val QUALIFIED_NAME_PARENT = 1
        private const val QUALIFIED_NAME_SHORT_NAME = 2
        private const val QUALIFIED_NAME_KIND = 3

        /** A `StringTable` message. */
        fun readStrings(bytes: ByteArray): List<String> {
            val out = ArrayList<String>()
            ProtoReader(bytes).let { reader ->
                reader.forEachField { number, _ ->
                    if (number == STRING_TABLE_STRING) {
                        out.add(reader.readString())
                        true
                    } else {
                        false
                    }
                }
            }
            return out
        }

        /** A `QualifiedNameTable` message. */
        fun readQualifiedNames(bytes: ByteArray): List<QualifiedName> {
            val out = ArrayList<QualifiedName>()
            ProtoReader(bytes).let { reader ->
                reader.forEachField { number, _ ->
                    if (number != QUALIFIED_NAME_TABLE_NAME) return@forEachField false
                    val nested = reader.readMessage()
                    // `parent_qualified_name` defaults to -1, which is what ends the walk: a top-level name
                    // simply omits the field, so reading an absent parent as 0 would loop through entry 0.
                    var parent = -1
                    var shortName = -1
                    var kind = 0
                    nested.forEachField { inner, _ ->
                        when (inner) {
                            QUALIFIED_NAME_PARENT -> { parent = nested.readInt(); true }
                            QUALIFIED_NAME_SHORT_NAME -> { shortName = nested.readInt(); true }
                            QUALIFIED_NAME_KIND -> { kind = nested.readInt(); true }
                            else -> false
                        }
                    }
                    out.add(QualifiedName(parent, shortName, kind))
                    true
                }
            }
            return out
        }
    }
}
