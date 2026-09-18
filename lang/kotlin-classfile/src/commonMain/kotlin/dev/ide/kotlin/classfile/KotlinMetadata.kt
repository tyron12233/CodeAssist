package dev.ide.kotlin.classfile

/**
 * Declaration-site or use-site variance.
 *
 * The numbers are the protobuf enum's. A star projection is not a variance: it is an argument with no type
 * at all, which is why it lives on [KotlinTypeArgument] rather than here.
 */
enum class KotlinVariance(val number: Int) {
    IN(0), OUT(1), INVARIANT(2);

    companion object {
        fun of(number: Int): KotlinVariance? = entries.firstOrNull { it.number == number }
    }
}

/**
 * One argument in a `<...>` list, with the projection written at the use site.
 *
 * `List<*>` is an argument with no type. `Array<out Number>` and `Comparator<in T>` are arguments whose
 * variance differs from the declaration's, and dropping that is how `Array<out T>` comes back as `Array<T>`
 * and a variance check then passes something it should not.
 */
class KotlinTypeArgument(
    val type: KotlinType?,
    val variance: KotlinVariance = KotlinVariance.INVARIANT,
) {
    val isStar: Boolean get() = type == null

    override fun toString(): String = when {
        type == null -> "*"
        variance == KotlinVariance.OUT -> "out $type"
        variance == KotlinVariance.IN -> "in $type"
        else -> type.toString()
    }
}

/** A declared type parameter: its id (what a type refers to it by), name, variance and bounds. */
class KotlinTypeParameter(
    val id: Int,
    val name: String,
    val variance: KotlinVariance,
    val upperBounds: List<KotlinType>,
)

/**
 * A type as the metadata records it.
 *
 * [classifier] is a class name (`kotlin.String`) or a type parameter's name, and [arguments] its type
 * arguments.
 */
class KotlinType(
    val classifier: String,
    val arguments: List<KotlinTypeArgument>,
    val isNullable: Boolean,
    /**
     * The same class, spelled the way the metadata's own string table spells it: slashes between package
     * parts, dots between nested names. Null when the classifier is a type parameter rather than a class.
     *
     * [classifier] is for reading; this is for computing JVM descriptors, which needs the exact spelling
     * [JvmDescriptors] is keyed on. Deriving one from the other is not possible in either direction: a dot
     * in `kotlin/collections/Map.Entry` means nesting, and a dot in `kotlin.collections.Map.Entry` could be
     * either.
     */
    val jvmName: String? = null,
    /** The `Type` flag word. Its layout is its own chain, sharing no bits with a declaration's. */
    val flags: Int = 0,
    /**
     * The annotations written ON the type, as class ids.
     *
     * Rare and load-bearing: `kotlin/ExtensionFunctionType` is the only thing distinguishing `T.() -> R`
     * from `(T) -> R`, which are the same `Function1` otherwise.
     */
    val annotations: List<String> = emptyList(),
    /**
     * Whether [classifier] names a TYPE PARAMETER rather than a class or a type alias.
     *
     * Not derivable after the fact: a parameter called `T` and a class called `T` produce the same string,
     * and a caller that substitutes one for the other has to know which it has.
     */
    val isTypeParameter: Boolean = false,
    /** The parameter's id, when this is a type parameter referred to by id. */
    val typeParameterId: Int? = null,
    /**
     * The parameter's NAME, when the metadata named it instead of numbering it.
     *
     * Both forms occur in output from the same compiler, and neither is legacy: `type_parameter` (7) and
     * `type_parameter_name` (9) are alternatives, not versions. A reader that handles only the id resolves
     * a real `V` to the fallback `T` and never fails.
     */
    val typeParameterName: String? = null,
) {
    /** A `suspend` function type. Stored in its JVM-lowered shape, so the flag is the only marker. */
    val isSuspend: Boolean get() = KotlinFlags.isSuspendType(flags)

    /** The type as Kotlin would print it, which is also what the oracle compares. */
    fun render(): String = buildString {
        append(classifier)
        if (arguments.isNotEmpty()) {
            append(arguments.joinToString(", ", "<", ">") { it.type?.render() ?: "*" })
        }
        if (isNullable) append('?')
    }

    /** This type's JVM descriptor, or null when it has none because it is a type parameter. */
    fun descriptor(): String? = jvmName?.let(JvmDescriptors::of)

    /** The same type, nullable. The type table stores `T` once and marks a range of ids as `T?`. */
    fun asNullable(): KotlinType =
        if (isNullable) this
        else KotlinType(classifier, arguments, true, jvmName, flags, annotations, isTypeParameter, typeParameterId, typeParameterName)

    override fun toString(): String = render()
}

/** A JVM method or field, as the bytecode names it. */
class JvmMemberSignature(val name: String, val descriptor: String) {
    override fun toString(): String = "$name$descriptor"

    override fun equals(other: Any?): Boolean =
        other is JvmMemberSignature && name == other.name && descriptor == other.descriptor

    override fun hashCode(): Int = name.hashCode() * 31 + descriptor.hashCode()
}

/**
 * The JVM members a Kotlin property compiles into.
 *
 * A property is not one JVM member: it is up to a field, a getter and a setter, any of which may be absent,
 * and their names are not derivable from the property's. `isEmpty` becomes `isEmpty()`, not `getIsEmpty()`;
 * a property in a value class gets a mangled suffix; `private` properties may have no accessors at all.
 */
class KotlinPropertySignatures(
    val field: JvmMemberSignature?,
    val getter: JvmMemberSignature?,
    val setter: JvmMemberSignature?,
)

/** One parameter of a function. */
class KotlinParameter(
    val name: String,
    val type: KotlinType?,
    val flags: Int = 0,
    /**
     * For a `vararg` parameter, the ELEMENT type. The declared [type] is the array, so a caller that wants
     * to know what one argument may be has to read this instead.
     */
    val varargElementType: KotlinType? = null,
) {
    val isVararg: Boolean get() = varargElementType != null

    val declaresDefaultValue: Boolean get() = KotlinFlags.declaresDefaultValue(flags)
    val isCrossinline: Boolean get() = KotlinFlags.isCrossinline(flags)
    val isNoinline: Boolean get() = KotlinFlags.isNoinline(flags)
}

/** A declaration read out of a class's Kotlin metadata. */
class KotlinDeclaration(
    val name: String,
    val kind: Kind,
    val returnType: KotlinType? = null,
    val receiverType: KotlinType? = null,
    val parameters: List<KotlinParameter> = emptyList(),
    val flags: Int = 0,
    /** The declaration's OWN type parameters, in order. */
    val typeParameters: List<KotlinTypeParameter> = emptyList(),
    /** For a `typealias`, the type it ultimately expands to. Null for anything else. */
    val expandedType: KotlinType? = null,
    /** The JVM method this compiles to, for a function or constructor. */
    val jvmSignature: JvmMemberSignature? = null,
    /** The JVM field and accessors this compiles to, for a property. */
    val propertySignatures: KotlinPropertySignatures? = null,
) {
    enum class Kind { FUNCTION, PROPERTY, CONSTRUCTOR, TYPE_ALIAS }

    val visibility: KotlinVisibility? get() = KotlinFlags.visibility(flags)
    val modality: KotlinModality? get() = KotlinFlags.modality(flags)

    /**
     * Where the member came from. Null for a constructor and a type alias, which have no member kind: the
     * bits at that offset mean something else there.
     */
    val memberKind: KotlinMemberKind?
        get() = if (kind == Kind.FUNCTION || kind == Kind.PROPERTY) KotlinFlags.memberKind(flags) else null

    /** Declared on this type rather than inherited, delegated or generated. */
    val isDeclaration: Boolean get() = memberKind == KotlinMemberKind.DECLARATION

    val isOperator: Boolean get() = KotlinFlags.isOperator(flags)
    val isInfix: Boolean get() = KotlinFlags.isInfix(flags)
    val isInline: Boolean get() = KotlinFlags.isInline(flags)
    val isTailrec: Boolean get() = KotlinFlags.isTailrec(flags)
    val isSuspend: Boolean get() = KotlinFlags.isSuspend(flags)
    val isExpect: Boolean
        get() = if (kind == Kind.PROPERTY) KotlinFlags.isExpectProperty(flags) else KotlinFlags.isExpectFunction(flags)
    val isExternal: Boolean
        get() = if (kind == Kind.PROPERTY) {
            KotlinFlags.isExternalProperty(flags)
        } else {
            KotlinFlags.isExternalFunction(flags)
        }

    val isVar: Boolean get() = KotlinFlags.isVar(flags)
    val hasGetter: Boolean get() = KotlinFlags.hasGetter(flags)
    val hasSetter: Boolean get() = KotlinFlags.hasSetter(flags)
    val isConst: Boolean get() = KotlinFlags.isConst(flags)
    val isLateinit: Boolean get() = KotlinFlags.isLateinit(flags)
    val isDelegated: Boolean get() = KotlinFlags.isDelegated(flags)

    val isSecondaryConstructor: Boolean get() = KotlinFlags.isSecondaryConstructor(flags)

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
    val flags: Int = 0,
    /** Supertypes WITH their type arguments, which is what makes an inherited generic member substitute. */
    val supertypes: List<KotlinType> = emptyList(),
    val typeParameters: List<KotlinTypeParameter> = emptyList(),
    /** The simple name of this class's companion object, or null when it has none. */
    val companionObjectName: String? = null,
    /** Simple names of the classes nested directly in this one, the companion object included. */
    val nestedClassNames: List<String> = emptyList(),
    /** Entry names, in declaration order, when this is an enum class. */
    val enumEntryNames: List<String> = emptyList(),
    /** A `sealed` type's direct subclasses, which is what makes a `when` over a library type exhaustive. */
    val sealedSubclassNames: List<String> = emptyList(),
) {
    /** A file facade is not a Kotlin declaration and carries no flags, so it has none of these. */
    private val isClassifier: Boolean get() = name != null

    val visibility: KotlinVisibility? get() = if (isClassifier) KotlinFlags.visibility(flags) else null
    val modality: KotlinModality? get() = if (isClassifier) KotlinFlags.modality(flags) else null
    val classKind: KotlinClassKind? get() = if (isClassifier) KotlinFlags.classKind(flags) else null

    val isInner: Boolean get() = KotlinFlags.isInner(flags)
    val isData: Boolean get() = KotlinFlags.isData(flags)
    val isValue: Boolean get() = KotlinFlags.isValueClass(flags)
    val isFunInterface: Boolean get() = KotlinFlags.isFunInterface(flags)
    val isExpect: Boolean get() = KotlinFlags.isExpectClass(flags)
    val isExternal: Boolean get() = KotlinFlags.isExternalClass(flags)
}

/**
 * Decodes the protobuf inside `@kotlin.Metadata`.
 *
 * **Only the fields an index reads.** The schema (`metadata.proto`, 709 lines) describes far more than this,
 * and the generated Java reader for all of it is 34,545 lines. Nothing here needs contracts, version
 * requirements or type tables, so nothing here decodes them, and [ProtoReader] skips any field not asked for,
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
    private const val CLASS_FLAGS = 1
    private const val CLASS_SUPERTYPE_ID = 2
    private const val CLASS_FQ_NAME = 3
    private const val CLASS_COMPANION_OBJECT_NAME = 4
    private const val CLASS_SUPERTYPE = 6
    private const val CLASS_NESTED_CLASS_NAME = 7
    private const val CLASS_CONSTRUCTOR = 8
    private const val CLASS_FUNCTION = 9
    private const val CLASS_PROPERTY = 10
    private const val CLASS_TYPE_ALIAS = 11
    private const val CLASS_ENUM_ENTRY = 13
    private const val CLASS_SEALED_SUBCLASS_FQ_NAME = 16

    /**
     * `Class.type_table` and `Package.type_table`, which share a number.
     *
     * It is written AFTER the members that reference it — protobuf orders fields by number — so it cannot be
     * picked up by the same forward pass that decodes them. It gets its own pre-pass, like the type-parameter
     * names do, and for the same reason.
     */
    private const val TYPE_TABLE = 30

    /** `Class.flags` when the field is absent: `public final class`, no annotations. */
    private const val CLASS_FLAGS_DEFAULT = 6

    // metadata.proto, `message Package`
    private const val PACKAGE_FUNCTION = 3
    private const val PACKAGE_PROPERTY = 4
    private const val PACKAGE_TYPE_ALIAS = 5

    // `message Function`, `message Property`, `message TypeAlias`: all spell their name the same way.
    private const val NAME = 2

    /**
     * Flags moved.
     *
     * `Function` and `Property` each carry their flags on a NEW field number and keep the original as
     * `old_flags`, so a reader that knows only field 1 still gets an answer from a current compiler. Both
     * numbers are read here, newest first: the corpus a classpath index meets was built by every compiler
     * that ever shipped, and a jar from either era has to decode.
     */
    private const val FUNCTION_FLAGS = 9
    private const val PROPERTY_FLAGS = 11
    private const val OLD_FLAGS = 1

    /** `Function.flags` when absent: `public final fun`, no annotations. */
    private const val FUNCTION_FLAGS_DEFAULT = 6

    /** `Property.flags` when absent: `public final val` (6) with a getter (512). */
    private const val PROPERTY_FLAGS_DEFAULT = 518

    /** `TypeAlias.flags` when absent: `public`, no annotations. */
    private const val TYPE_ALIAS_FLAGS_DEFAULT = 6

    /** `Constructor.flags` when absent: `public` primary constructor, no annotations. */
    private const val CONSTRUCTOR_FLAGS_DEFAULT = 6

    // `message Function` / `message Property`
    private const val RETURN_TYPE = 3
    private const val RECEIVER_TYPE = 5
    private const val VALUE_PARAMETER = 6

    /**
     * The table-referenced twins of `return_type` and `receiver_type`.
     *
     * Different numbers on a Function than on a Property, and on a Property they collide with numbers that
     * mean something else on a Function (9 is a Function's `flags`), so they are dispatched by kind exactly
     * as field 6 already is.
     */
    private const val FUNCTION_RETURN_TYPE_ID = 7
    private const val FUNCTION_RECEIVER_TYPE_ID = 8
    private const val PROPERTY_RETURN_TYPE_ID = 9
    private const val PROPERTY_RECEIVER_TYPE_ID = 10
    private const val TYPE_ALIAS_EXPANDED_TYPE_ID = 7

    /**
     * A `TypeAlias` renumbers everything.
     *
     * Its type parameters are field 3 where a function's are 4, and fields 3, 4 and 5 hold a type parameter,
     * the underlying type and an id where a function holds a return type, its type parameters and a receiver.
     * So every one of those has to be dispatched by kind, the way field 6 already was — reading a `Type` as
     * a `TypeParameter` does not fail, it desynchronises the reader and throws somewhere else entirely.
     */
    private const val TYPE_ALIAS_TYPE_PARAMETER = 3

    // `message Constructor`: no name and no return type, and its parameters are on a different number.
    private const val CONSTRUCTOR_VALUE_PARAMETER = 2

    // `message ValueParameter`
    private const val PARAM_FLAGS = 1
    private const val PARAM_NAME = 2
    private const val PARAM_TYPE = 3
    private const val PARAM_VARARG_ELEMENT_TYPE = 4
    private const val PARAM_TYPE_ID = 5
    private const val PARAM_VARARG_ELEMENT_TYPE_ID = 6

    // `message EnumEntry`
    private const val ENUM_ENTRY_NAME = 1

    // `message Annotation`
    private const val ANNOTATION_ID = 1

    // `message TypeAlias`
    private const val TYPE_ALIAS_EXPANDED_TYPE = 6

    // `message Type`
    private const val TYPE_FLAGS = 1
    private const val TYPE_ARGUMENT = 2
    private const val TYPE_NULLABLE = 3
    private const val TYPE_CLASS_NAME = 6
    private const val TYPE_PARAMETER_ID = 7
    private const val TYPE_PARAMETER_NAME = 9
    private const val TYPE_ALIAS_NAME = 12
    private const val TYPE_ANNOTATION = 100

    // `message Type.Argument`. The projection is an enum, and STAR means "no type is written at all".
    private const val ARGUMENT_PROJECTION = 1
    private const val ARGUMENT_TYPE = 2
    private const val ARGUMENT_TYPE_ID = 3
    private const val PROJECTION_STAR = 3

    // `message TypeParameter`
    private const val TYPE_PARAM_ID = 1
    private const val TYPE_PARAM_NAME = 2
    private const val TYPE_PARAM_VARIANCE = 4
    private const val TYPE_PARAM_UPPER_BOUND = 5
    private const val TYPE_PARAM_UPPER_BOUND_ID = 6

    // `message Class` / `message Function` both declare their own type parameters, on different numbers.
    private const val CLASS_TYPE_PARAMETER = 5
    private const val FUNCTION_TYPE_PARAMETER = 4

    /**
     * The JVM signature extensions, from `jvm_metadata.proto`.
     *
     * Protobuf extensions are ordinary fields on the wire: the `extend Function { ... = 100 }` declaration
     * lives in another file only so that the platform-independent schema does not have to know about the
     * JVM. Field 100 means a different message on each of the three, which is why they are read by kind.
     */
    private const val EXTENSION_SIGNATURE = 100

    // `message JvmMethodSignature` and `message JvmFieldSignature`, which share a shape.
    private const val JVM_SIGNATURE_NAME = 1
    private const val JVM_SIGNATURE_DESC = 2

    // `message JvmPropertySignature`
    private const val JVM_PROPERTY_FIELD = 1
    private const val JVM_PROPERTY_GETTER = 3
    private const val JVM_PROPERTY_SETTER = 4

    /**
     * What `@Metadata`'s `k` means: 1 a class, 2 a file facade, 3 a synthetic class, 4 a multi-file class
     * FACADE, 5 one of its parts.
     *
     * 4 and 5 are easy to transpose, and the mistake is not a wrong answer but a crash several hundred bytes
     * later. A facade's `d1` is not protobuf at all: it is the plain internal names of the part classes,
     * which the encoding step is never applied to. Handing that to a protobuf reader yields a length prefix
     * read out of the middle of a class name, and then a read past the end of the array.
     */
    private const val KIND_CLASS = 1
    private const val KIND_FILE_FACADE = 2
    private const val KIND_MULTIFILE_CLASS = 4
    private const val KIND_MULTIFILE_CLASS_PART = 5

    /**
     * Read [annotation], or null when it carries nothing a caller can use.
     *
     * A synthetic class or a multi-file facade header holds no declarations of its own, so there is nothing
     * to return rather than an empty answer that looks like a decoding failure.
     */
    fun read(annotation: KotlinMetadataAnnotation): KotlinClassInfo? {
        if (annotation.data1.isEmpty() || annotation.kind == KIND_MULTIFILE_CLASS) return null
        val bytes = MetadataEncoding.decodeBytes(annotation.data1)
        val strings = annotation.data2

        val reader = ProtoReader(bytes)
        // The payload is a `StringTableTypes` message written length-delimited FIRST, then the class or
        // package message. It is not skippable padding: it says how every name index in what follows turns
        // into a string, and the reader would start the real message mid-field without consuming it.
        val names = JvmNameResolver.read(reader.readBytes(), strings)

        val message = reader.remainingBytes()
        return when (annotation.kind) {
            KIND_CLASS -> readClass(message, names)
            KIND_FILE_FACADE, KIND_MULTIFILE_CLASS_PART -> readPackage(message, names)
            else -> null
        }
    }

    /**
     * The part classes behind a multi-file facade, or null when [annotation] is not one.
     *
     * A `@JvmMultifileClass` file compiles to a facade holding no members and one part class per source
     * file holding them all. An index that reads only the facade finds nothing, so the parts have to be
     * followed, and only the facade knows their names.
     */
    fun readMultiFileParts(annotation: KotlinMetadataAnnotation): List<String>? =
        if (annotation.kind == KIND_MULTIFILE_CLASS) annotation.data1.toList() else null

    internal fun readClass(message: ByteArray, names: NameResolver): KotlinClassInfo {
        var name: String? = null
        var flags = CLASS_FLAGS_DEFAULT
        var companionObjectName: String? = null
        val declarations = ArrayList<KotlinDeclaration>()
        val supertypes = ArrayList<KotlinType>()
        val nestedClassNames = ArrayList<String>()
        val enumEntryNames = ArrayList<String>()
        val sealedSubclassNames = ArrayList<String>()

        // A type in a member can name a type parameter by ID, and the id means nothing without the
        // declaration that introduced it. Every id-to-name pair is registered in a FIRST pass, because a
        // bound may name a sibling written after it and one forward pass cannot resolve that.
        val scope = HashMap<Int, String>()
        registerTypeParameterNames(message, CLASS_TYPE_PARAMETER, names, scope)
        val typeParameters = ArrayList<KotlinTypeParameter>()
        // Same shape, same reason: the table is written after everything that indexes into it.
        val table = readTypeTable(message)
        // A supertype may be an id instead of a type, and the ids arrive packed and out of band from the
        // inline ones. Collected separately so the inline ones keep their position.
        val supertypeIds = ArrayList<Int>()

        val reader = ProtoReader(message)
        reader.forEachField { number, _ ->
            when (number) {
                CLASS_FLAGS -> {
                    flags = reader.readInt()
                    true
                }

                CLASS_FQ_NAME -> {
                    name = names.getClassName(reader.readInt())
                    true
                }

                CLASS_COMPANION_OBJECT_NAME -> {
                    companionObjectName = names.getString(reader.readInt())
                    true
                }

                CLASS_TYPE_PARAMETER -> {
                    typeParameters.add(readTypeParameter(reader.readMessage(), names, scope, table))
                    true
                }

                CLASS_SUPERTYPE -> {
                    supertypes.add(readType(reader.readMessage(), names, scope, table))
                    true
                }

                CLASS_SUPERTYPE_ID -> {
                    for (id in reader.readPackedInts()) supertypeIds.add(id)
                    true
                }

                CLASS_NESTED_CLASS_NAME -> {
                    for (id in reader.readPackedInts()) nestedClassNames.add(names.getString(id))
                    true
                }

                CLASS_ENUM_ENTRY -> {
                    readEnumEntry(reader.readMessage(), names)?.let(enumEntryNames::add)
                    true
                }

                CLASS_SEALED_SUBCLASS_FQ_NAME -> {
                    for (id in reader.readPackedInts()) sealedSubclassNames.add(names.getClassName(id))
                    true
                }

                CLASS_FUNCTION -> declarations.addDeclaration(reader, names, KotlinDeclaration.Kind.FUNCTION, scope, table)
                CLASS_PROPERTY -> declarations.addDeclaration(reader, names, KotlinDeclaration.Kind.PROPERTY, scope, table)
                CLASS_TYPE_ALIAS -> declarations.addDeclaration(reader, names, KotlinDeclaration.Kind.TYPE_ALIAS, scope, table)
                CLASS_CONSTRUCTOR -> {
                    declarations.add(readConstructor(reader.readMessage(), names, scope, table))
                    true
                }

                else -> false
            }
        }
        for (id in supertypeIds) table.resolve(id, names, scope)?.let(supertypes::add)
        return KotlinClassInfo(
            name = name,
            declarations = declarations,
            flags = flags,
            supertypes = supertypes,
            typeParameters = typeParameters,
            companionObjectName = companionObjectName,
            nestedClassNames = nestedClassNames,
            enumEntryNames = enumEntryNames,
            sealedSubclassNames = sealedSubclassNames,
        )
    }

    /**
     * The type-table pass.
     *
     * Reads ONLY field 30 out of [message]. A class or package writes its table last, so this is a second
     * walk rather than a branch in the main one.
     */
    private fun readTypeTable(message: ByteArray): TypeTable {
        var table = TypeTable.EMPTY
        val reader = ProtoReader(message)
        reader.forEachField { number, _ ->
            if (number != TYPE_TABLE) return@forEachField false
            table = TypeTable.read(reader.readBytes())
            true
        }
        return table
    }

    /** Decode one `Type` message. Public so [TypeTable] can decode an entry it is asked for. */
    internal fun readTypeMessage(
        bytes: ByteArray,
        names: NameResolver,
        scope: Map<Int, String>,
        table: TypeTable,
    ): KotlinType = readType(ProtoReader(bytes), names, scope, table)

    /**
     * The id-to-name pass.
     *
     * Reads ONLY [field]'s id and name out of [message], leaving bounds and everything else alone, so that a
     * later full pass can resolve a bound naming any sibling regardless of the order they were written in.
     */
    private fun registerTypeParameterNames(
        message: ByteArray,
        field: Int,
        names: NameResolver,
        into: MutableMap<Int, String>,
    ) {
        val reader = ProtoReader(message)
        reader.forEachField { number, _ ->
            if (number != field) return@forEachField false
            val nested = reader.readMessage()
            var id = -1
            var nameId = -1
            nested.forEachField { inner, _ ->
                when (inner) {
                    TYPE_PARAM_ID -> { id = nested.readInt(); true }
                    TYPE_PARAM_NAME -> { nameId = nested.readInt(); true }
                    else -> false
                }
            }
            if (id >= 0 && nameId >= 0) into[id] = names.getString(nameId)
            true
        }
    }

    private fun readEnumEntry(reader: ProtoReader, names: NameResolver): String? {
        var nameId = -1
        reader.forEachField { number, _ ->
            if (number == ENUM_ENTRY_NAME) {
                nameId = reader.readInt()
                true
            } else {
                false
            }
        }
        return if (nameId >= 0) names.getString(nameId) else null
    }

    internal fun readPackage(message: ByteArray, names: NameResolver): KotlinClassInfo {
        val declarations = ArrayList<KotlinDeclaration>()
        val table = readTypeTable(message)
        val reader = ProtoReader(message)
        reader.forEachField { number, _ ->
            when (number) {
                PACKAGE_FUNCTION -> declarations.addDeclaration(reader, names, KotlinDeclaration.Kind.FUNCTION, emptyMap(), table)
                PACKAGE_PROPERTY -> declarations.addDeclaration(reader, names, KotlinDeclaration.Kind.PROPERTY, emptyMap(), table)
                PACKAGE_TYPE_ALIAS -> declarations.addDeclaration(reader, names, KotlinDeclaration.Kind.TYPE_ALIAS, emptyMap(), table)
                else -> false
            }
        }
        return KotlinClassInfo(name = null, declarations = declarations)
    }

    /**
     * A constructor: parameters and flags, and no name of its own.
     *
     * It is recorded under `<init>`, which is what the JVM calls it and what a signature lookup needs; the
     * Kotlin name of a constructor is the class's, which the caller already has.
     */
    private fun readConstructor(
        reader: ProtoReader,
        names: NameResolver,
        scope: Map<Int, String>,
        table: TypeTable,
    ): KotlinDeclaration {
        var flags = CONSTRUCTOR_FLAGS_DEFAULT
        val parameters = ArrayList<KotlinParameter>()
        var signature: JvmMemberSignature? = null

        reader.forEachField { number, _ ->
            when (number) {
                OLD_FLAGS -> { flags = reader.readInt(); true }
                CONSTRUCTOR_VALUE_PARAMETER -> { parameters.add(readParameter(reader.readMessage(), names, scope, table)); true }
                EXTENSION_SIGNATURE -> { signature = readMethodSignature(reader.readMessage(), names); true }
                else -> false
            }
        }

        // A constructor returns nothing, so the descriptor the compiler declined to write always ends in V.
        val resolved = resolveMethodSignature(
            declared = signature,
            names = names,
            fallbackName = "<init>",
            parameterTypes = parameters.map { it.type },
            returnDescriptor = "V",
        )
        return KotlinDeclaration(
            name = "<init>",
            kind = KotlinDeclaration.Kind.CONSTRUCTOR,
            parameters = parameters,
            flags = flags,
            jvmSignature = resolved,
        )
    }

    /** Reads one nested declaration message. Returns true, having consumed the field. */
    private fun MutableList<KotlinDeclaration>.addDeclaration(
        reader: ProtoReader,
        names: NameResolver,
        kind: KotlinDeclaration.Kind,
        outerTypeParameters: Map<Int, String>,
        table: TypeTable,
    ): Boolean {
        val message = reader.readBytes()
        // The declaration's OWN parameters shadow and extend the class's, so start from the outer scope,
        // and register every one of them before any type is decoded.
        val scope = HashMap(outerTypeParameters)
        registerTypeParameterNames(
            message,
            if (kind == KotlinDeclaration.Kind.TYPE_ALIAS) TYPE_ALIAS_TYPE_PARAMETER else FUNCTION_TYPE_PARAMETER,
            names,
            scope,
        )

        val nested = ProtoReader(message)
        val newFlagsField = when (kind) {
            KotlinDeclaration.Kind.FUNCTION -> FUNCTION_FLAGS
            KotlinDeclaration.Kind.PROPERTY -> PROPERTY_FLAGS
            else -> -1
        }
        // The `*_type_id` twins of the three type fields, by kind. -1 where the kind has none, which no
        // field number can equal, so the branch is simply never taken.
        val returnTypeIdField = when (kind) {
            KotlinDeclaration.Kind.FUNCTION -> FUNCTION_RETURN_TYPE_ID
            KotlinDeclaration.Kind.PROPERTY -> PROPERTY_RETURN_TYPE_ID
            else -> -1
        }
        val receiverTypeIdField = when (kind) {
            KotlinDeclaration.Kind.FUNCTION -> FUNCTION_RECEIVER_TYPE_ID
            KotlinDeclaration.Kind.PROPERTY -> PROPERTY_RECEIVER_TYPE_ID
            else -> -1
        }
        val isTypeAlias = kind == KotlinDeclaration.Kind.TYPE_ALIAS
        val expandedTypeIdField = if (isTypeAlias) TYPE_ALIAS_EXPANDED_TYPE_ID else -1
        val typeParameterField = if (isTypeAlias) TYPE_ALIAS_TYPE_PARAMETER else FUNCTION_TYPE_PARAMETER
        val returnTypeField = if (isTypeAlias) -1 else RETURN_TYPE
        val receiverTypeField = if (isTypeAlias) -1 else RECEIVER_TYPE
        var newFlags: Int? = null
        var oldFlags: Int? = null
        var nameId = -1
        var returnType: KotlinType? = null
        var receiverType: KotlinType? = null
        var expandedType: KotlinType? = null
        val typeParameters = ArrayList<KotlinTypeParameter>()
        val parameters = ArrayList<KotlinParameter>()
        var methodSignature: JvmMemberSignature? = null
        var propertySignature: ProtoReader? = null

        nested.forEachField { number, _ ->
            when (number) {
                newFlagsField -> { newFlags = nested.readInt(); true }
                OLD_FLAGS -> { oldFlags = nested.readInt(); true }
                NAME -> { nameId = nested.readInt(); true }
                typeParameterField -> {
                    typeParameters.add(readTypeParameter(nested.readMessage(), names, scope, table))
                    true
                }

                returnTypeField -> { returnType = readType(nested.readMessage(), names, scope, table); true }
                receiverTypeField -> { receiverType = readType(nested.readMessage(), names, scope, table); true }
                returnTypeIdField -> { returnType = table.resolve(nested.readInt(), names, scope) ?: returnType; true }
                receiverTypeIdField -> { receiverType = table.resolve(nested.readInt(), names, scope) ?: receiverType; true }
                expandedTypeIdField -> { expandedType = table.resolve(nested.readInt(), names, scope) ?: expandedType; true }
                // Field 6 means three different things. On a Function it is `value_parameter`, on a Property
                // the SETTER's parameter, and on a TypeAlias the expanded type. Reading it the same way for
                // all three puts a setter's argument into a property's parameter list and a type where a
                // parameter was expected.
                VALUE_PARAMETER -> when (kind) {
                    KotlinDeclaration.Kind.FUNCTION -> {
                        parameters.add(readParameter(nested.readMessage(), names, scope, table))
                        true
                    }

                    KotlinDeclaration.Kind.TYPE_ALIAS -> {
                        expandedType = readType(nested.readMessage(), names, scope, table)
                        true
                    }

                    else -> false
                }

                EXTENSION_SIGNATURE -> {
                    when (kind) {
                        KotlinDeclaration.Kind.FUNCTION -> methodSignature = readMethodSignature(nested.readMessage(), names)
                        KotlinDeclaration.Kind.PROPERTY -> propertySignature = nested.readMessage()
                        else -> return@forEachField false
                    }
                    true
                }

                else -> false
            }
        }

        if (nameId < 0) return true
        val name = names.getString(nameId)
        val flags = newFlags ?: oldFlags ?: when (kind) {
            KotlinDeclaration.Kind.FUNCTION -> FUNCTION_FLAGS_DEFAULT
            KotlinDeclaration.Kind.PROPERTY -> PROPERTY_FLAGS_DEFAULT
            else -> TYPE_ALIAS_FLAGS_DEFAULT
        }

        val jvmSignature = if (kind == KotlinDeclaration.Kind.FUNCTION) {
            resolveMethodSignature(
                declared = methodSignature,
                names = names,
                fallbackName = name,
                // An extension function's receiver is its first JVM parameter. Leaving it out produces a
                // descriptor that is one argument short and matches no method.
                parameterTypes = listOfNotNull(receiverType) + parameters.map { it.type },
                returnDescriptor = returnType?.descriptor(),
            )
        } else {
            null
        }

        add(
            KotlinDeclaration(
                name = name,
                kind = kind,
                returnType = returnType,
                receiverType = receiverType,
                parameters = parameters,
                flags = flags,
                typeParameters = typeParameters,
                expandedType = expandedType,
                jvmSignature = jvmSignature,
                propertySignatures = propertySignature?.let { readPropertySignatures(it, names, name, returnType) },
            ),
        )
        return true
    }

    /**
     * One type parameter, with the bounds it declares.
     *
     * The id-to-name registration has already happened in [registerTypeParameterNames], so a bound naming a
     * sibling resolves here no matter which order the two were written in.
     */
    private fun readTypeParameter(
        reader: ProtoReader,
        names: NameResolver,
        scope: Map<Int, String>,
        table: TypeTable,
    ): KotlinTypeParameter {
        var id = -1
        var nameId = -1
        var variance = KotlinVariance.INVARIANT
        val upperBounds = ArrayList<KotlinType>()
        reader.forEachField { number, _ ->
            when (number) {
                TYPE_PARAM_ID -> { id = reader.readInt(); true }
                TYPE_PARAM_NAME -> { nameId = reader.readInt(); true }
                TYPE_PARAM_VARIANCE -> {
                    variance = KotlinVariance.of(reader.readInt()) ?: KotlinVariance.INVARIANT
                    true
                }

                TYPE_PARAM_UPPER_BOUND -> {
                    upperBounds.add(readType(reader.readMessage(), names, scope, table))
                    true
                }

                TYPE_PARAM_UPPER_BOUND_ID -> {
                    for (id in reader.readPackedInts()) table.resolve(id, names, scope)?.let(upperBounds::add)
                    true
                }

                else -> false
            }
        }
        return KotlinTypeParameter(
            id = id,
            name = if (nameId >= 0) names.getString(nameId) else "",
            variance = variance,
            upperBounds = upperBounds,
        )
    }

    private fun readParameter(
        reader: ProtoReader,
        names: NameResolver,
        scope: Map<Int, String>,
        table: TypeTable,
    ): KotlinParameter {
        var flags = 0
        var nameId = -1
        var type: KotlinType? = null
        var varargElementType: KotlinType? = null
        reader.forEachField { number, _ ->
            when (number) {
                PARAM_FLAGS -> { flags = reader.readInt(); true }
                PARAM_NAME -> { nameId = reader.readInt(); true }
                PARAM_TYPE -> { type = readType(reader.readMessage(), names, scope, table); true }
                PARAM_TYPE_ID -> { type = table.resolve(reader.readInt(), names, scope) ?: type; true }
                PARAM_VARARG_ELEMENT_TYPE -> {
                    varargElementType = readType(reader.readMessage(), names, scope, table)
                    true
                }

                PARAM_VARARG_ELEMENT_TYPE_ID -> {
                    varargElementType = table.resolve(reader.readInt(), names, scope) ?: varargElementType
                    true
                }

                else -> false
            }
        }
        return KotlinParameter(
            name = if (nameId >= 0) names.getString(nameId) else "",
            type = type,
            flags = flags,
            varargElementType = varargElementType,
        )
    }

    /**
     * A `Type`.
     *
     * Its classifier arrives as one of four fields, and they are NOT simply mutually exclusive: a type
     * parameter can carry both its id (field 7) and its name (field 9), and the id is the one that
     * identifies it. Taking whichever field arrived last silently prefers the name, which renders as a
     * perfectly reasonable type that happens to be a different one, the sort of difference only an oracle
     * finds. So each candidate is collected and the precedence is applied deliberately.
     */
    private fun readType(
        reader: ProtoReader,
        names: NameResolver,
        scope: Map<Int, String>,
        table: TypeTable,
    ): KotlinType {
        var className: String? = null
        var classNameRaw: String? = null
        var aliasName: String? = null
        var parameterId: Int? = null
        var parameterName: String? = null
        var nullable = false
        var flags = 0
        val annotations = ArrayList<String>()
        val arguments = ArrayList<KotlinTypeArgument>()

        reader.forEachField { number, _ ->
            when (number) {
                TYPE_FLAGS -> { flags = reader.readInt(); true }
                TYPE_ANNOTATION -> {
                    readAnnotationClassName(reader.readMessage(), names)?.let(annotations::add)
                    true
                }

                TYPE_CLASS_NAME -> {
                    val id = reader.readInt()
                    className = names.getClassName(id)
                    classNameRaw = names.getString(id)
                    true
                }

                TYPE_ALIAS_NAME -> { aliasName = names.getClassName(reader.readInt()); true }
                TYPE_PARAMETER_ID -> { parameterId = reader.readInt(); true }
                TYPE_PARAMETER_NAME -> { parameterName = names.getString(reader.readInt()); true }
                TYPE_NULLABLE -> { nullable = reader.readInt() != 0; true }
                TYPE_ARGUMENT -> { arguments.add(readArgument(reader.readMessage(), names, scope, table)); true }
                else -> false
            }
        }

        val classifier = className
            ?: aliasName
            ?: parameterId?.let { scope[it] ?: "T#$it" }
            ?: parameterName
            ?: "?"
        val isTypeParameter = className == null && aliasName == null &&
            (parameterId != null || parameterName != null)
        return KotlinType(
            classifier = classifier,
            arguments = arguments,
            isNullable = nullable,
            jvmName = classNameRaw,
            flags = flags,
            annotations = annotations,
            isTypeParameter = isTypeParameter,
            typeParameterId = parameterId,
            typeParameterName = parameterName,
        )
    }

    /**
     * One type argument.
     *
     * A STAR projection carries no type at all, which is why the type is nullable rather than filled with
     * `Any`. The projection is written as an enum whose default is invariant, so an absent field is not an
     * absent projection.
     */
    private fun readArgument(
        reader: ProtoReader,
        names: NameResolver,
        scope: Map<Int, String>,
        table: TypeTable,
    ): KotlinTypeArgument {
        var projection = KotlinVariance.INVARIANT.number
        var type: KotlinType? = null
        reader.forEachField { number, _ ->
            when (number) {
                ARGUMENT_PROJECTION -> { projection = reader.readInt(); true }
                ARGUMENT_TYPE -> { type = readType(reader.readMessage(), names, scope, table); true }
                ARGUMENT_TYPE_ID -> { type = table.resolve(reader.readInt(), names, scope) ?: type; true }
                else -> false
            }
        }
        if (projection == PROJECTION_STAR) return KotlinTypeArgument(null)
        return KotlinTypeArgument(type, KotlinVariance.of(projection) ?: KotlinVariance.INVARIANT)
    }

    /**
     * An annotation's class, as the string table spells it: slashes between packages, dots between nested
     * names. That is the form a caller compares against, so it is not turned into a display name here.
     */
    private fun readAnnotationClassName(reader: ProtoReader, names: NameResolver): String? {
        var id = -1
        reader.forEachField { number, _ ->
            if (number == ANNOTATION_ID) {
                id = reader.readInt()
                true
            } else {
                false
            }
        }
        return if (id >= 0) names.getString(id) else null
    }

    /**
     * A `JvmMethodSignature` or `JvmFieldSignature`, whose two fields are both string-table indices and both
     * optional: the name is omitted when it matches the Kotlin one, and the descriptor when it is derivable.
     * So this returns the parts, and the caller supplies what is missing.
     */
    private fun readMethodSignature(reader: ProtoReader, names: NameResolver): JvmMemberSignature {
        var name: String? = null
        var descriptor: String? = null
        reader.forEachField { number, _ ->
            when (number) {
                JVM_SIGNATURE_NAME -> { name = names.getString(reader.readInt()); true }
                JVM_SIGNATURE_DESC -> { descriptor = names.getString(reader.readInt()); true }
                else -> false
            }
        }
        // Either half may be absent; the empty string stands for "not written", so the two halves travel
        // together and the caller fills whichever gaps it knows how to fill.
        return JvmMemberSignature(name ?: "", descriptor ?: "")
    }

    private fun JvmMemberSignature.orNull(): JvmMemberSignature? =
        takeIf { it.name.isNotEmpty() && it.descriptor.isNotEmpty() }

    /**
     * Fill in what the compiler left out.
     *
     * A signature is written into the metadata only when it is NOT computable from the Kotlin declaration,
     * so the common case is that there is no extension at all and the whole thing has to be rebuilt. Missing
     * a type's descriptor means the rebuild is impossible, and the answer is then no signature rather than a
     * wrong one: a descriptor with a hole in it would match nothing and look like a real answer.
     */
    private fun resolveMethodSignature(
        declared: JvmMemberSignature?,
        names: NameResolver,
        fallbackName: String,
        parameterTypes: List<KotlinType?>,
        returnDescriptor: String?,
    ): JvmMemberSignature? {
        val name = declared?.name?.takeIf { it.isNotEmpty() } ?: fallbackName
        val descriptor = declared?.descriptor?.takeIf { it.isNotEmpty() } ?: buildString {
            append('(')
            for (type in parameterTypes) append(type?.descriptor() ?: return null)
            append(')')
            append(returnDescriptor ?: return null)
        }
        return JvmMemberSignature(name, descriptor)
    }

    /**
     * The field and accessors of a property.
     *
     * The backing field is reported only when the extension says there is one. A property without a field
     * (one with a custom getter, or an interface member) would otherwise be reported as having a field named
     * after it, and a lookup for that field finds a different property's, or nothing.
     */
    private fun readPropertySignatures(
        reader: ProtoReader,
        names: NameResolver,
        propertyName: String,
        returnType: KotlinType?,
    ): KotlinPropertySignatures {
        var field: JvmMemberSignature? = null
        var getter: JvmMemberSignature? = null
        var setter: JvmMemberSignature? = null

        reader.forEachField { number, _ ->
            when (number) {
                JVM_PROPERTY_FIELD -> {
                    val declared = readMethodSignature(reader.readMessage(), names)
                    val descriptor = declared.descriptor.takeIf { it.isNotEmpty() } ?: returnType?.descriptor()
                    if (descriptor != null) {
                        field = JvmMemberSignature(declared.name.takeIf { it.isNotEmpty() } ?: propertyName, descriptor)
                    }
                    true
                }

                // Unlike a function's, an accessor's signature is written in full when it is written at all:
                // its name is not the property's and its descriptor is not the property's type.
                JVM_PROPERTY_GETTER -> { getter = readMethodSignature(reader.readMessage(), names).orNull(); true }
                JVM_PROPERTY_SETTER -> { setter = readMethodSignature(reader.readMessage(), names).orNull(); true }
                else -> false
            }
        }
        return KotlinPropertySignatures(field, getter, setter)
    }
}
