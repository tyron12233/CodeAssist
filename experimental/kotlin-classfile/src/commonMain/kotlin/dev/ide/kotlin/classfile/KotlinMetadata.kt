package dev.ide.kotlin.classfile

/** A declaration read out of a class's Kotlin metadata. */
class KotlinDeclaration(val name: String, val kind: Kind) {
    enum class Kind { FUNCTION, PROPERTY, CONSTRUCTOR, TYPE_ALIAS }

    override fun toString(): String = "$kind $name"
}

/** What a class file's `@Metadata` says the class contains. */
class KotlinClassInfo(
    /** The class's own name as Kotlin spells it, or null for a file facade. */
    val name: String?,
    val declarations: List<KotlinDeclaration>,
)

/**
 * Decodes the protobuf inside `@kotlin.Metadata`.
 *
 * **Only the fields an index reads.** The schema (`metadata.proto`, 709 lines) describes far more than this,
 * and the generated Java reader for all of it is 34,545 lines. Nothing here needs contracts, version
 * requirements or type tables, so nothing here decodes them — [ProtoReader] skips any field not asked for,
 * which is also what makes this survive a Kotlin release that adds more.
 *
 * **The field numbers are the contract, and they are not guessable.** Each one below is cited to the message
 * it comes from, because a wrong number does not fail: it reads a different field and returns nonsense that
 * looks plausible.
 *
 * **Names are indices, not strings.** Every name in the protobuf is an index into the string table carried
 * separately in `@Metadata`'s `d2`. That indirection is why decoding needs both arrays and why a decoder
 * given only `d1` produces numbers.
 */
object KotlinMetadata {

    // metadata.proto, `message Class`
    private const val CLASS_FQ_NAME = 3
    private const val CLASS_CONSTRUCTOR = 8
    private const val CLASS_FUNCTION = 9
    private const val CLASS_PROPERTY = 10
    private const val CLASS_TYPE_ALIAS = 11

    // metadata.proto, `message Package`
    private const val PACKAGE_FUNCTION = 3
    private const val PACKAGE_PROPERTY = 4
    private const val PACKAGE_TYPE_ALIAS = 5

    // `message Function`, `message Property`, `message TypeAlias`: all spell their name the same way.
    private const val NAME = 2

    /** What `@Metadata`'s `k` means. */
    private const val KIND_CLASS = 1
    private const val KIND_FILE_FACADE = 2
    private const val KIND_MULTIFILE_CLASS_PART = 4

    /**
     * Read [annotation], or null when it carries nothing a caller can use.
     *
     * A synthetic class or a multi-file facade header holds no declarations of its own, so there is nothing
     * to return rather than an empty answer that looks like a decoding failure.
     */
    fun read(annotation: KotlinMetadataAnnotation): KotlinClassInfo? {
        if (annotation.data1.isEmpty()) return null
        val bytes = MetadataEncoding.decodeBytes(annotation.data1)
        val strings = annotation.data2

        val reader = ProtoReader(bytes)
        // The payload is a `StringTableTypes` message written length-delimited FIRST, then the class or
        // package message. Skipping it is not optional: without consuming it, the reader starts the real
        // message in the middle of another one.
        reader.readBytes()

        return when (annotation.kind) {
            KIND_CLASS -> readClass(reader, strings)
            KIND_FILE_FACADE, KIND_MULTIFILE_CLASS_PART -> readPackage(reader, strings)
            else -> null
        }
    }

    private fun readClass(reader: ProtoReader, strings: Array<String>): KotlinClassInfo {
        var name: String? = null
        val declarations = ArrayList<KotlinDeclaration>()
        reader.forEachField { number, _ ->
            when (number) {
                CLASS_FQ_NAME -> {
                    // A class's own name is an index into the qualified-name table for a normal build, but
                    // the JVM metadata resolves it through the same string table; slashes are how the
                    // compiler spells nesting here.
                    name = strings.getOrNull(reader.readInt())?.replace('/', '.')
                    true
                }

                CLASS_FUNCTION -> declarations.addDeclaration(reader, strings, KotlinDeclaration.Kind.FUNCTION)
                CLASS_PROPERTY -> declarations.addDeclaration(reader, strings, KotlinDeclaration.Kind.PROPERTY)
                CLASS_TYPE_ALIAS -> declarations.addDeclaration(reader, strings, KotlinDeclaration.Kind.TYPE_ALIAS)
                CLASS_CONSTRUCTOR -> {
                    // A constructor has no name field; it is named after the class by convention.
                    reader.readBytes()
                    declarations.add(KotlinDeclaration("<init>", KotlinDeclaration.Kind.CONSTRUCTOR))
                    true
                }

                else -> false
            }
        }
        return KotlinClassInfo(name, declarations)
    }

    private fun readPackage(reader: ProtoReader, strings: Array<String>): KotlinClassInfo {
        val declarations = ArrayList<KotlinDeclaration>()
        reader.forEachField { number, _ ->
            when (number) {
                PACKAGE_FUNCTION -> declarations.addDeclaration(reader, strings, KotlinDeclaration.Kind.FUNCTION)
                PACKAGE_PROPERTY -> declarations.addDeclaration(reader, strings, KotlinDeclaration.Kind.PROPERTY)
                PACKAGE_TYPE_ALIAS -> declarations.addDeclaration(reader, strings, KotlinDeclaration.Kind.TYPE_ALIAS)
                else -> false
            }
        }
        return KotlinClassInfo(name = null, declarations = declarations)
    }

    /** Reads one nested declaration message and takes its name. Returns true, having consumed the field. */
    private fun MutableList<KotlinDeclaration>.addDeclaration(
        reader: ProtoReader,
        strings: Array<String>,
        kind: KotlinDeclaration.Kind,
    ): Boolean {
        val nested = reader.readMessage()
        var nameId = -1
        nested.forEachField { number, _ ->
            if (number == NAME) {
                nameId = nested.readInt()
                true
            } else {
                false
            }
        }
        strings.getOrNull(nameId)?.let { add(KotlinDeclaration(it, kind)) }
        return true
    }
}
