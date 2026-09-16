package dev.ide.kotlin.symbols

import dev.ide.kotlin.classfile.ClassFile
import dev.ide.kotlin.classfile.ClassMember
import dev.ide.kotlin.classfile.JavaSignatures
import dev.ide.kotlin.classfile.JavaType
import dev.ide.kotlin.classfile.JavaTypeArgument
import dev.ide.kotlin.classfile.JavaTypeParameter

/**
 * Bytecode to symbols, ported off ASM.
 *
 * The no-`@Metadata` branch: plain Java and Android APIs carry no Kotlin metadata, so their members come
 * straight from the class file. This is what makes `view.findViewById` complete, and `android.jar` is 6,440
 * classes of it.
 *
 * A faithful port of `:lang-kotlin-index`'s `JavaBytecode`, rule for rule, including the rules that look
 * arbitrary. That is the whole point: the decoder agreeing with ASM about what is in the bytes is a
 * different claim from the CONSUMER agreeing about what the bytes MEAN, and the second claim is the one an
 * index depends on. Each rule below is here because the original has it, and `JavaSymbolsPortTest` fails if
 * any of them drifts.
 *
 * The one structural difference from the original is not a choice: ASM is a push API and this is a pull one,
 * so the visitor nest becomes a walk. Everything else is meant to be recognisable line for line.
 */
object JavaSymbols {

    private const val OBJECT = "java.lang.Object"

    // Access flags, from the JVM spec. The class file's own numbers, not ASM's names for them.
    private const val ACC_PUBLIC = 0x0001
    private const val ACC_PRIVATE = 0x0002
    private const val ACC_PROTECTED = 0x0004
    private const val ACC_STATIC = 0x0008
    private const val ACC_FINAL = 0x0010
    private const val ACC_INTERFACE = 0x0200
    private const val ACC_ABSTRACT = 0x0400
    private const val ACC_SYNTHETIC = 0x1000
    private const val ACC_ENUM = 0x4000
    private const val ACC_BRIDGE = 0x0040
    private const val ACC_VARARGS = 0x0080
    private const val ACC_DEPRECATED = 0x20000

    fun read(bytes: ByteArray): JavaShape? = ClassFile.read(bytes)?.let(::read)

    fun read(classFile: ClassFile): JavaShape {
        val selfName = classFile.thisClass
        val classFqn = selfName.replace('/', '.').replace('$', '.')

        val typeParameters = ArrayList<String>()
        val typeParameterBounds = ArrayList<TypeName>()
        val superTypes = ArrayList<TypeName>()

        val signature = classFile.signature?.let(JavaSignatures::parseClassSignature)
        if (signature != null) {
            for (parameter in signature.typeParameters) {
                typeParameters.add(parameter.name)
                typeParameterBounds.add(parameter.erasureTypeName())
            }
            signature.superclass?.let { superTypes.add(it.fromSignature()) }
            for (interfaceType in signature.interfaces) superTypes.add(interfaceType.fromSignature())
        } else {
            // No generic signature: the raw superclass and interfaces, with no type arguments. The binary
            // `$` nested separator is normalised to dot-form here, as the self-FQN and every other path do,
            // so that a supertype-chain walk up a nested class compares equal to the dot-form declared type.
            classFile.superClass?.let { superTypes.add(TypeName(it.replace('/', '.').replace('$', '.'))) }
            for (interfaceName in classFile.interfaces) {
                superTypes.add(TypeName(interfaceName.replace('/', '.').replace('$', '.')))
            }
        }

        // Order matters, and it is not alphabetical: nested types, then fields, then methods. That is the
        // order the class file itself puts them in, which is the order the original's visitors append in.
        val members = ArrayList<JavaSymbol>()
        for (nested in classFile.innerClasses) {
            if (hidden(nested.access) || nested.innerName == null || nested.outerName != selfName) continue
            members.add(
                JavaSymbol(
                    name = nested.innerName!!,
                    kind = SymbolKind.CLASS,
                    type = TypeName(nested.name.replace('/', '.').replace('$', '.')),
                    // A nested type is reached statically through the outer one.
                    modifiers = modifiers(nested.access) + Modifier.STATIC,
                    signature = null,
                    typeParameters = emptyList(),
                    typeParameterBounds = emptyList(),
                    paramTypes = emptyList(),
                    paramNames = emptyList(),
                    declaringClassFqn = null,
                    isDeprecated = false,
                    varargParamIndex = -1,
                )
            )
        }
        for (field in classFile.fields) readField(field)?.let(members::add)
        for (method in classFile.methods) readMethod(method, classFqn)?.let(members::add)

        return JavaShape(
            typeParameters = typeParameters,
            typeParameterBounds = typeParameterBounds,
            superTypes = superTypes,
            members = members,
            isInterface = classFile.accessFlags and ACC_INTERFACE != 0,
            isAbstract = classFile.accessFlags and ACC_ABSTRACT != 0,
            // A final class, but not an enum (enums are final yet extending one is a different error) and
            // not an interface (which never sets the bit). A non-final Java class is `open` to Kotlin.
            isFinal = classFile.accessFlags and ACC_FINAL != 0 &&
                classFile.accessFlags and ACC_INTERFACE == 0 &&
                classFile.accessFlags and ACC_ENUM == 0,
        )
    }

    private fun readField(field: ClassMember): JavaSymbol? {
        if (hidden(field.access)) return null
        val erased = JavaSignatures.parseTypeDescriptor(field.descriptor) ?: return null
        val type = field.signature?.let(JavaSignatures::parseFieldSignature)?.fromSignature() ?: erased.erased()
        return JavaSymbol(
            name = field.name,
            kind = SymbolKind.FIELD,
            type = type,
            modifiers = modifiers(field.access),
            signature = ": " + erased.jvmClassName().substringAfterLast('.'),
            typeParameters = emptyList(),
            typeParameterBounds = emptyList(),
            paramTypes = emptyList(),
            paramNames = emptyList(),
            declaringClassFqn = null,
            isDeprecated = field.access and ACC_DEPRECATED != 0,
            varargParamIndex = -1,
        )
    }

    private fun readMethod(method: ClassMember, classFqn: String): JavaSymbol? {
        if (hidden(method.access)) return null
        // A static initialiser is not a member anyone can call or complete.
        if (method.name == "<clinit>") return null

        val isConstructor = method.name == "<init>"
        val descriptor = JavaSignatures.parseMethodDescriptor(method.descriptor) ?: return null
        val generic = method.signature?.let(JavaSignatures::parseMethodSignature)

        val returnType: TypeName
        val paramTypes: List<TypeName?>
        val methodTypeParameters: List<String>
        val methodBounds: List<TypeName>
        if (generic != null) {
            returnType = generic.returnType.fromSignature()
            paramTypes = generic.parameterTypes.map { it.fromSignature() }
            methodTypeParameters = generic.typeParameters.map { it.name }
            methodBounds = generic.typeParameters.map { it.erasureTypeName() }
        } else {
            returnType = descriptor.returnType.erased()
            paramTypes = descriptor.parameterTypes.map { it.erased() }
            methodTypeParameters = emptyList()
            methodBounds = emptyList()
        }

        // Real names when the class was compiled with `-parameters`, and NOTHING otherwise. The list has to
        // be positional with the descriptor or it is not usable at all: javac writes an entry per descriptor
        // parameter, synthesized and mandated ones included, but a rewritten or truncated attribute would
        // silently shift every name onto the wrong type.
        val declared = method.parameterNames
        val real = declared.size == descriptor.parameterTypes.size && declared.all { it.isNotBlank() }
        val paramNames = if (real) declared else emptyList()

        // The display keeps `[]`, so that a vararg or array parameter is visible to the argument-count
        // check, and it is built from the ERASED descriptor even when a generic signature exists.
        val displayParams = descriptor.parameterTypes.mapIndexed { index, type ->
            "${paramNames.getOrNull(index) ?: "p$index"}: ${type.jvmClassName().substringAfterLast('.')}"
        }
        val display = if (isConstructor) {
            "(${displayParams.joinToString(", ")})"
        } else {
            "(${displayParams.joinToString(", ")}): " +
                descriptor.returnType.jvmClassName().substringAfterLast('.')
        }

        return JavaSymbol(
            // A constructor is keyed by the simple class name, matching the `@Metadata` decode, and types to
            // the class itself so a call site can validate its arguments.
            name = if (isConstructor) classFqn.substringAfterLast('.') else method.name,
            kind = if (isConstructor) SymbolKind.CONSTRUCTOR else SymbolKind.METHOD,
            type = if (isConstructor) TypeName(classFqn) else returnType,
            modifiers = modifiers(method.access),
            signature = display,
            typeParameters = methodTypeParameters,
            typeParameterBounds = methodBounds,
            paramTypes = paramTypes,
            paramNames = paramNames,
            declaringClassFqn = classFqn,
            isDeprecated = method.access and ACC_DEPRECATED != 0,
            // The flag means the LAST parameter is a vararg, so it absorbs trailing arguments.
            varargParamIndex = if (method.access and ACC_VARARGS != 0) paramTypes.size - 1 else -1,
        )
    }

    /** Private, synthetic and bridge members are not part of anyone's API. */
    private fun hidden(access: Int): Boolean =
        access and (ACC_PRIVATE or ACC_SYNTHETIC or ACC_BRIDGE) != 0

    private fun modifiers(access: Int): Set<Modifier> = buildSet {
        if (access and ACC_PUBLIC != 0) add(Modifier.PUBLIC)
        if (access and ACC_PROTECTED != 0) add(Modifier.PROTECTED)
        if (access and ACC_STATIC != 0) add(Modifier.STATIC)
        if (access and ACC_FINAL != 0) add(Modifier.FINAL)
        if (access and ACC_ABSTRACT != 0) add(Modifier.ABSTRACT)
    }

    private fun JavaTypeParameter.erasureTypeName(): TypeName =
        (classBound ?: interfaceBounds.firstOrNull())?.fromSignature() ?: TypeName(OBJECT)

    /**
     * A type from the SIGNATURE grammar.
     *
     * Two things here are carried over from the original rather than fixed, because the port is measured
     * against the original and a unilateral improvement shows up as a difference:
     *
     *  * The `${'$'}` in a nested binary name is left alone, unlike the erased path which normalises it to a dot.
     *    See `JavaSymbolsPortTest.theTwoPathsDisagreeAboutNestedNames`.
     *  * `Outer<A, B>.Inner<C>` collapses to ONE name carrying `<A, B, C>`, because the original's visitor
     *    accumulates arguments across `visitClassType` and `visitInnerClassType` into a single list and
     *    reports them all at the end. See `JavaSymbolsPortTest.anInnerClassOfAGenericOuterMergesArguments`.
     */
    private fun JavaType.fromSignature(): TypeName = when (this) {
        is JavaType.Primitive -> primitive(descriptor)
        is JavaType.Variable -> TypeName(name, isTypeParameter = true)
        is JavaType.Array -> arrayType(element.fromSignature())
        is JavaType.Class -> TypeName(
            signatureClassName(),
            flattenedArguments().map { argument ->
                when (argument.wildcard) {
                    // An unbounded `?` carries no type at all, and becomes a star projection on `Any`.
                    '*' -> TypeName("kotlin.Any", projection = "*")
                    '+' -> argument.type!!.fromSignature().withProjection("out")
                    '-' -> argument.type!!.fromSignature().withProjection("in")
                    else -> argument.type!!.fromSignature()
                }
            },
        )
    }

    /** `java.util.Map${'$'}Entry` for the flat form, `java.util.Map.Entry` for the outer-with-suffix form. */
    private fun JavaType.Class.signatureClassName(): String {
        val enclosing = outer
        return if (enclosing == null) name.replace('/', '.') else enclosing.signatureClassName() + "." + name
    }

    /** Outermost arguments first, then each nested level's, all on one name. See [fromSignature]. */
    private fun JavaType.Class.flattenedArguments(): List<JavaTypeArgument> =
        (outer?.flattenedArguments() ?: emptyList()) + arguments

    /**
     * A type from the erased DESCRIPTOR grammar: JVM primitives mapped to their Kotlin classifier, arrays
     * through [arrayType], references by dot-form FQN.
     *
     * The `$` normalisation here is not cosmetic. A nested library type reached through a member with no
     * generic signature would otherwise carry the binary form, and an assignment check comparing it to the
     * dot-form declared type false-flagged a mismatch: both resolve, so the check did not back off.
     */
    private fun JavaType.erased(): TypeName = when (this) {
        is JavaType.Primitive -> primitive(descriptor)
        is JavaType.Variable -> TypeName(name, isTypeParameter = true)
        is JavaType.Array -> arrayType(element.erased())
        is JavaType.Class -> TypeName(jvmClassName().replace('$', '.'))
    }

    /**
     * The Kotlin type of a JVM array.
     *
     * A PRIMITIVE element maps to Kotlin's specialised array class, never `Array<Byte>`: that specialised
     * class IS the Kotlin type of the parameter, so `outputStream.write(byteArray)` type-checks instead of
     * the argument check reporting that a `ByteArray` was found where an `Array<Byte>` was expected.
     * Everything else, a reference element and a type variable included, nests under `kotlin.Array<E>`.
     */
    private fun arrayType(element: TypeName): TypeName {
        val specialised = if (element.isTypeParameter) null else PRIMITIVE_ARRAYS[element.qualifiedName]
        return if (specialised != null) TypeName(specialised) else TypeName("kotlin.Array", listOf(element))
    }

    private val PRIMITIVE_ARRAYS = mapOf(
        "kotlin.Int" to "kotlin.IntArray", "kotlin.Long" to "kotlin.LongArray",
        "kotlin.Short" to "kotlin.ShortArray", "kotlin.Byte" to "kotlin.ByteArray",
        "kotlin.Char" to "kotlin.CharArray", "kotlin.Boolean" to "kotlin.BooleanArray",
        "kotlin.Float" to "kotlin.FloatArray", "kotlin.Double" to "kotlin.DoubleArray",
    )

    private fun primitive(descriptor: Char): TypeName = TypeName(
        when (descriptor) {
            'I' -> "kotlin.Int"
            'J' -> "kotlin.Long"
            'Z' -> "kotlin.Boolean"
            'B' -> "kotlin.Byte"
            'S' -> "kotlin.Short"
            'C' -> "kotlin.Char"
            'F' -> "kotlin.Float"
            'D' -> "kotlin.Double"
            'V' -> "kotlin.Unit"
            else -> "kotlin.Any"
        },
    )
}
