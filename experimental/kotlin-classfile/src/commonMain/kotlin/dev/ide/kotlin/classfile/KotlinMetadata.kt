package dev.ide.kotlin.classfile

/**
 * A type as the metadata records it.
 *
 * [classifier] is a class name (`kotlin.String`) or a type parameter's name, and [arguments] its type
 * arguments. A star projection is an argument with no type, which is why the list holds nulls rather than
 * being filtered.
 */
class KotlinType(
    val classifier: String,
    val arguments: List<KotlinType?>,
    val isNullable: Boolean,
) {
    /** The type as Kotlin would print it, which is also what the oracle compares. */
    fun render(): String = buildString {
        append(classifier)
        if (arguments.isNotEmpty()) {
            append(arguments.joinToString(", ", "<", ">") { it?.render() ?: "*" })
        }
        if (isNullable) append('?')
    }

    override fun toString(): String = render()
}

/** One parameter of a function. */
class KotlinParameter(val name: String, val type: KotlinType?)

/** A declaration read out of a class's Kotlin metadata. */
class KotlinDeclaration(
    val name: String,
    val kind: Kind,
    val returnType: KotlinType? = null,
    val receiverType: KotlinType? = null,
    val parameters: List<KotlinParameter> = emptyList(),
) {
    enum class Kind { FUNCTION, PROPERTY, CONSTRUCTOR, TYPE_ALIAS }

    /** `receiver.name(params): return`, the shape an index and a completion list both want. */
    fun signature(): String = buildString {
        receiverType?.let { append(it.render()).append('.') }
        append(name)
        if (kind == Kind.FUNCTION || kind == Kind.CONSTRUCTOR) {
            append(parameters.joinToString(", ", "(", ")") { "${it.name}: ${it.type?.render() ?: "?"}" })
        }
        returnType?.let { append(": ").append(it.render()) }
    }

    override fun toString(): String = "$kind ${signature()}"
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

    // `message Function` / `message Property`
    private const val RETURN_TYPE = 3
    private const val RECEIVER_TYPE = 5
    private const val VALUE_PARAMETER = 6

    // `message ValueParameter`
    private const val PARAM_NAME = 2
    private const val PARAM_TYPE = 3

    // `message Type`
    private const val TYPE_ARGUMENT = 2
    private const val TYPE_NULLABLE = 3
    private const val TYPE_CLASS_NAME = 6
    private const val TYPE_PARAMETER_ID = 7
    private const val TYPE_PARAMETER_NAME = 9
    private const val TYPE_ALIAS_NAME = 12

    // `message Type.Argument`
    private const val ARGUMENT_TYPE = 2

    // `message TypeParameter`
    private const val TYPE_PARAM_ID = 1
    private const val TYPE_PARAM_NAME = 2

    // `message Class` / `message Function` both declare their own type parameters, on different numbers.
    private const val CLASS_TYPE_PARAMETER = 5
    private const val FUNCTION_TYPE_PARAMETER = 4

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
        // package message. It is not skippable padding: it says how every name index in what follows turns
        // into a string, and the reader would start the real message mid-field without consuming it.
        val names = JvmNameResolver.read(reader.readBytes(), strings)

        return when (annotation.kind) {
            KIND_CLASS -> readClass(reader, names)
            KIND_FILE_FACADE, KIND_MULTIFILE_CLASS_PART -> readPackage(reader, names)
            else -> null
        }
    }

    private fun readClass(reader: ProtoReader, names: JvmNameResolver): KotlinClassInfo {
        var name: String? = null
        val declarations = ArrayList<KotlinDeclaration>()
        // A type in a member can name a type parameter by ID, and the id means nothing without the
        // declaration that introduced it. The class's own parameters are in scope for every member, so they
        // are collected as they are met — which works because the compiler writes them before the members.
        val typeParameters = HashMap<Int, String>()
        reader.forEachField { number, _ ->
            when (number) {
                CLASS_FQ_NAME -> {
                    name = names.getClassName(reader.readInt())
                    true
                }

                CLASS_TYPE_PARAMETER -> {
                    readTypeParameter(reader.readMessage(), names, typeParameters)
                    true
                }

                CLASS_FUNCTION -> declarations.addDeclaration(reader, names, KotlinDeclaration.Kind.FUNCTION, typeParameters)
                CLASS_PROPERTY -> declarations.addDeclaration(reader, names, KotlinDeclaration.Kind.PROPERTY, typeParameters)
                CLASS_TYPE_ALIAS -> declarations.addDeclaration(reader, names, KotlinDeclaration.Kind.TYPE_ALIAS, typeParameters)
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

    private fun readPackage(reader: ProtoReader, names: JvmNameResolver): KotlinClassInfo {
        val declarations = ArrayList<KotlinDeclaration>()
        reader.forEachField { number, _ ->
            when (number) {
                PACKAGE_FUNCTION -> declarations.addDeclaration(reader, names, KotlinDeclaration.Kind.FUNCTION, emptyMap())
                PACKAGE_PROPERTY -> declarations.addDeclaration(reader, names, KotlinDeclaration.Kind.PROPERTY, emptyMap())
                PACKAGE_TYPE_ALIAS -> declarations.addDeclaration(reader, names, KotlinDeclaration.Kind.TYPE_ALIAS, emptyMap())
                else -> false
            }
        }
        return KotlinClassInfo(name = null, declarations = declarations)
    }

    /** Reads one nested declaration message. Returns true, having consumed the field. */
    private fun MutableList<KotlinDeclaration>.addDeclaration(
        reader: ProtoReader,
        names: JvmNameResolver,
        kind: KotlinDeclaration.Kind,
        outerTypeParameters: Map<Int, String>,
    ): Boolean {
        val nested = reader.readMessage()
        // The declaration's OWN parameters shadow and extend the class's, so start from the outer scope.
        val scope = HashMap(outerTypeParameters)
        var nameId = -1
        var returnType: KotlinType? = null
        var receiverType: KotlinType? = null
        val parameters = ArrayList<KotlinParameter>()

        nested.forEachField { number, _ ->
            when (number) {
                NAME -> { nameId = nested.readInt(); true }
                FUNCTION_TYPE_PARAMETER -> { readTypeParameter(nested.readMessage(), names, scope); true }
                RETURN_TYPE -> { returnType = readType(nested.readMessage(), names, scope); true }
                RECEIVER_TYPE -> { receiverType = readType(nested.readMessage(), names, scope); true }
                VALUE_PARAMETER -> { parameters.add(readParameter(nested.readMessage(), names, scope)); true }
                else -> false
            }
        }

        if (nameId >= 0) {
            add(KotlinDeclaration(names.getString(nameId), kind, returnType, receiverType, parameters))
        }
        return true
    }

    /** Records one type parameter's id and name, so a type that names it by id can be rendered. */
    private fun readTypeParameter(reader: ProtoReader, names: JvmNameResolver, into: MutableMap<Int, String>) {
        var id = -1
        var nameId = -1
        reader.forEachField { number, _ ->
            when (number) {
                TYPE_PARAM_ID -> { id = reader.readInt(); true }
                TYPE_PARAM_NAME -> { nameId = reader.readInt(); true }
                else -> false
            }
        }
        if (id >= 0 && nameId >= 0) into[id] = names.getString(nameId)
    }

    private fun readParameter(
        reader: ProtoReader,
        names: JvmNameResolver,
        scope: Map<Int, String>,
    ): KotlinParameter {
        var nameId = -1
        var type: KotlinType? = null
        reader.forEachField { number, _ ->
            when (number) {
                PARAM_NAME -> { nameId = reader.readInt(); true }
                PARAM_TYPE -> { type = readType(reader.readMessage(), names, scope); true }
                else -> false
            }
        }
        return KotlinParameter(if (nameId >= 0) names.getString(nameId) else "", type)
    }

    /**
     * A `Type`.
     *
     * Its classifier arrives as one of four fields, and they are NOT simply mutually exclusive: a type
     * parameter can carry both its id (field 7) and its name (field 9), and the id is the one that
     * identifies it. Taking whichever field arrived last silently prefers the name, which renders as a
     * perfectly reasonable type that happens to be a different one — the sort of difference only an oracle
     * finds. So each candidate is collected and the precedence is applied deliberately.
     */
    private fun readType(reader: ProtoReader, names: JvmNameResolver, scope: Map<Int, String>): KotlinType {
        var className: String? = null
        var aliasName: String? = null
        var parameterId: Int? = null
        var parameterName: String? = null
        var nullable = false
        val arguments = ArrayList<KotlinType?>()

        reader.forEachField { number, _ ->
            when (number) {
                TYPE_CLASS_NAME -> { className = names.getClassName(reader.readInt()); true }
                TYPE_ALIAS_NAME -> { aliasName = names.getClassName(reader.readInt()); true }
                TYPE_PARAMETER_ID -> { parameterId = reader.readInt(); true }
                TYPE_PARAMETER_NAME -> { parameterName = names.getString(reader.readInt()); true }
                TYPE_NULLABLE -> { nullable = reader.readInt() != 0; true }
                TYPE_ARGUMENT -> { arguments.add(readArgument(reader.readMessage(), names, scope)); true }
                else -> false
            }
        }

        val classifier = className
            ?: aliasName
            ?: parameterId?.let { scope[it] ?: "T#$it" }
            ?: parameterName
            ?: "?"
        return KotlinType(classifier, arguments, nullable)
    }

    /** One type argument, or null for a star projection, which carries no type at all. */
    private fun readArgument(reader: ProtoReader, names: JvmNameResolver, scope: Map<Int, String>): KotlinType? {
        var type: KotlinType? = null
        reader.forEachField { number, _ ->
            if (number == ARGUMENT_TYPE) {
                type = readType(reader.readMessage(), names, scope)
                true
            } else {
                false
            }
        }
        return type
    }
}
