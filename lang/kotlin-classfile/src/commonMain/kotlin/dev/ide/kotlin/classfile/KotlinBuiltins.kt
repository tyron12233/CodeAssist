package dev.ide.kotlin.classfile

/**
 * The declarations of one `.kotlin_builtins` fragment.
 *
 * [classes] are the built-in TYPES (`kotlin.collections.List`, `kotlin.Int`, …) keyed by the Kotlin FQN the
 * fragment spells them with; [topLevel] holds the package-level functions and properties, which is where the
 * compiler intrinsics live (`arrayOf`, `emptyArray`, `intArrayOf`) — declarations with no `.class` file
 * anywhere, so nothing that scans bytecode ever finds them.
 */
class KotlinBuiltinsFragment(
    val classes: Map<String, KotlinClassInfo>,
    val topLevel: KotlinClassInfo,
)

/**
 * Reads Kotlin's built-in declarations out of the `.kotlin_builtins` files in `kotlin-stdlib.jar`.
 *
 * These are the real `List`, `Int` and `String` — the types the compiler knows about without a class file to
 * read — and they are the same metadata protobuf as `@kotlin.Metadata`, differently packaged. Two things
 * differ, and both are in the wrapper rather than the declarations:
 *
 *  * the payload starts with a VERSION HEADER, written by `BinaryVersion`: a big-endian count followed by
 *    that many big-endian ints. It is not protobuf, and handing it to a protobuf reader yields a length
 *    prefix read out of a version number.
 *  * the names are resolved against tables carried INSIDE the message (`strings`, `qualified_names`) rather
 *    than against the class file's annotation, which is why [NameResolver] is an interface.
 *
 * Everything below that — classes, members, types, flags, the type table — is [KotlinMetadata]'s, unchanged.
 */
object KotlinBuiltins {

    // metadata.proto, `message PackageFragment`
    private const val FRAGMENT_STRINGS = 1
    private const val FRAGMENT_QUALIFIED_NAMES = 2
    private const val FRAGMENT_PACKAGE = 3
    private const val FRAGMENT_CLASS = 4

    /** Decode one `.kotlin_builtins` file, or null when [bytes] is not one. */
    fun read(bytes: ByteArray): KotlinBuiltinsFragment? {
        val start = skipVersionHeader(bytes) ?: return null

        // The tables are written first (fields 1 and 2 precede 3 and 4), but relying on field order to fill
        // the resolver before the declarations need it is exactly the assumption that breaks on a re-ordered
        // writer. The message is walked twice instead: names, then declarations.
        var strings: List<String> = emptyList()
        var qualifiedNames: List<BuiltInsNameResolver.QualifiedName> = emptyList()
        val tables = ProtoReader(bytes, start)
        tables.forEachField { number, _ ->
            when (number) {
                FRAGMENT_STRINGS -> { strings = BuiltInsNameResolver.readStrings(tables.readBytes()); true }
                FRAGMENT_QUALIFIED_NAMES -> {
                    qualifiedNames = BuiltInsNameResolver.readQualifiedNames(tables.readBytes())
                    true
                }

                else -> false
            }
        }
        val names = BuiltInsNameResolver(strings, qualifiedNames)

        val classes = LinkedHashMap<String, KotlinClassInfo>()
        var topLevel = KotlinClassInfo(name = null, declarations = emptyList())
        val reader = ProtoReader(bytes, start)
        reader.forEachField { number, _ ->
            when (number) {
                FRAGMENT_PACKAGE -> { topLevel = KotlinMetadata.readPackage(reader.readBytes(), names); true }
                FRAGMENT_CLASS -> {
                    val info = KotlinMetadata.readClass(reader.readBytes(), names)
                    info.name?.let { classes[it] = info }
                    true
                }

                else -> false
            }
        }
        return KotlinBuiltinsFragment(classes, topLevel)
    }

    /**
     * The offset the protobuf starts at, or null when the header does not read as one.
     *
     * `BinaryVersion.readFrom` is a `DataInputStream`: an int for the number of version components, then
     * that many ints, all big-endian. The count is bounded here because a file that is not a builtins
     * fragment produces a plausible-looking one and would otherwise seek past the end.
     */
    private fun skipVersionHeader(bytes: ByteArray): Int? {
        if (bytes.size < 4) return null
        val count = readBigEndianInt(bytes, 0)
        if (count < 0 || count > MAX_VERSION_COMPONENTS) return null
        val end = 4 + count * 4
        return if (end <= bytes.size) end else null
    }

    private fun readBigEndianInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)

    private const val MAX_VERSION_COMPONENTS = 16
}
