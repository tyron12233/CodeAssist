package dev.ide.lang.kotlin.symbols

import dev.ide.kotlin.classfile.ClassFile
import dev.ide.kotlin.classfile.ClassMember
import dev.ide.kotlin.classfile.JavaSignatures
import dev.ide.kotlin.classfile.JavaType
import dev.ide.kotlin.classfile.JavaTypeArgument
import dev.ide.kotlin.classfile.JavaTypeParameter
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef

/**
 * The Java bytecode shape of a classpath type (the no-`@Metadata` branch): plain Java/Android APIs
 * (`android.jar`, Java libs) carry no Kotlin metadata, so their members are read straight from bytecode.
 * This is what makes `view.findViewById`, `string` Java methods, etc. complete.
 *
 * Generics are read from the JVM generic SIGNATURE attribute (not just the erased descriptor): a class's
 * own type parameters + their bounds, its generic supertypes (`List<E> : Collection<E>`), and each member's
 * generic parameter/return types and own type parameters. That lets the resolver bind `List.of("s")` to
 * `List<String>`, propagate `String` through `list.stream()` (inherited from `Collection<E>`), and type a
 * lambda passed to a Java SAM (`stream().map { it }` → `it: String`). When a member has no signature
 * attribute (no generics involved) the erased descriptor is used, so arity is still known for overloads.
 */
class JavaShape(
    /** The class's own type-parameter names (`E` for `List<E>`), positional with [typeParameterBounds]. */
    val typeParameters: List<String>,
    /** Each type parameter's erased upper bound, for falling back when a raw/unbound use can't infer it. */
    val typeParameterBounds: List<TypeRef>,
    /** Generic supertypes (superclass + interfaces) carrying their type arguments (`Collection<E>`). */
    val superTypes: List<TypeRef>,
    val members: List<KotlinSymbol>,
    /** True when this type is a Java/Android `interface` (`ACC_INTERFACE`) — cannot be instantiated. */
    val isInterface: Boolean = false,
    /** True when this type is `abstract` (`ACC_ABSTRACT`, which interfaces also set) — cannot be instantiated. */
    val isAbstract: Boolean = false,
    /** True when this type is a `final` class (`ACC_FINAL`, and not an interface/enum) — it cannot be extended,
     *  driving the final-supertype check. A non-final Java class is `open` from Kotlin's view, so it is false. */
    val isFinal: Boolean = false,
)

/**
 * Bytecode to symbols, read through `:kotlin-classfile` rather than ASM.
 *
 * Every rule below was ASM-shaped before and is unchanged: which members are hidden, how a JVM primitive
 * becomes a Kotlin classifier, why `byte[]` is `ByteArray` but `String[]` is `Array<String>`, when a
 * `MethodParameters` list is usable, and what order members come in. The only structural difference is that
 * ASM is a push API and this is a pull one, so the visitor nest became a walk.
 *
 * It is the same code `:kotlin-symbols` diffed against the ASM version, member for member and field for
 * field, over `android.jar` (6,440 classes, 97,148 symbols), kotlin-stdlib and the whole test classpath.
 * That diff is why this could replace the original rather than sit beside it.
 */
object JavaBytecode {

    private val BINARY = SymbolOrigin(fromSource = false, file = null)
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

    fun read(bytes: ByteArray, ctx: KotlinTypeContext?): JavaShape? =
        ClassFile.read(bytes)?.let { read(it, ctx) }

    /** [read] over an already-parsed [classFile] — the index build reuses ONE per class across every binary
     *  index (see [dev.ide.lang.kotlin.index.sharedClassFile]) instead of parsing here per index. */
    fun read(classFile: ClassFile, ctx: KotlinTypeContext?): JavaShape {
        val selfName = classFile.thisClass
        val classFqn = selfName.replace('/', '.').replace('$', '.')

        val typeParameters = ArrayList<String>()
        val typeParameterBounds = ArrayList<TypeRef>()
        val superTypes = ArrayList<TypeRef>()

        val signature = classFile.signature?.let(JavaSignatures::parseClassSignature)
        if (signature != null) {
            for (parameter in signature.typeParameters) {
                typeParameters.add(parameter.name)
                typeParameterBounds.add(parameter.erasureType(ctx))
            }
            signature.superclass?.let { superTypes.add(it.fromSignature(ctx)) }
            for (interfaceType in signature.interfaces) superTypes.add(interfaceType.fromSignature(ctx))
        } else {
            // No generic signature: raw superclass + interfaces (no type arguments). Normalize the binary
            // `$` nested separator to dot-form (as the self-FQN and every other path do), so a
            // supertype-chain walk up a nested class (`FrameLayout.LayoutParams`'s super
            // `ViewGroup$MarginLayoutParams`) compares equal to the dot-form declared/inferred types.
            classFile.superClass?.let { superTypes.add(KotlinType(it.dotted(), context = ctx)) }
            for (name in classFile.interfaces) superTypes.add(KotlinType(name.dotted(), context = ctx))
        }

        // Order matters and is not alphabetical: nested types, then fields, then methods. That is the order
        // the class file puts them in, and the order ASM's visitors appended in.
        val members = ArrayList<KotlinSymbol>()
        for (nested in classFile.innerClasses) {
            val innerName = nested.innerName
            // A directly-nested type (`android/R$string` inside `android/R`) — surfaced for `android.R.string`
            // navigation. Skip anonymous/local (innerName null) and unrelated entries.
            if (hidden(nested.access) || innerName == null || nested.outerName != selfName) continue
            members.add(
                KotlinSymbol(
                    name = innerName,
                    kind = SymbolKind.CLASS,
                    // Built with whatever ctx is given (null at index time → rebound on read), never dropped.
                    type = KotlinType(nested.name.dotted(), context = ctx),
                    modifiers = mods(nested.access) + Modifier.STATIC, // reached statically via the outer
                    origin = BINARY,
                ),
            )
        }
        for (field in classFile.fields) readField(field, ctx)?.let(members::add)
        for (method in classFile.methods) readMethod(method, classFqn, ctx)?.let(members::add)

        return JavaShape(
            typeParameters, typeParameterBounds, superTypes, members,
            isInterface = classFile.accessFlags and ACC_INTERFACE != 0,
            isAbstract = classFile.accessFlags and ACC_ABSTRACT != 0,
            // A final class (but not an enum — enums are ACC_FINAL yet extending one is a different error,
            // and not an interface, which never sets ACC_FINAL). Non-final Java classes are open from Kotlin.
            isFinal = classFile.accessFlags and ACC_FINAL != 0 &&
                classFile.accessFlags and ACC_INTERFACE == 0 &&
                classFile.accessFlags and ACC_ENUM == 0,
        )
    }

    private fun readField(field: ClassMember, ctx: KotlinTypeContext?): KotlinSymbol? {
        if (hidden(field.access)) return null
        val erased = JavaSignatures.parseTypeDescriptor(field.descriptor) ?: return null
        val type = field.signature?.let(JavaSignatures::parseFieldSignature)?.fromSignature(ctx)
            ?: erased.erased(ctx)
        return KotlinSymbol(
            name = field.name,
            kind = SymbolKind.FIELD,
            type = type,
            modifiers = mods(field.access),
            origin = BINARY,
            signature = ": " + erased.jvmClassName().substringAfterLast('.'),
            isDeprecated = field.access and ACC_DEPRECATED != 0,
        )
    }

    private fun readMethod(method: ClassMember, classFqn: String, ctx: KotlinTypeContext?): KotlinSymbol? {
        if (hidden(method.access)) return null
        // A static initialiser is not a member anyone can call or complete.
        if (method.name == "<clinit>") return null

        val isConstructor = method.name == "<init>"
        val descriptor = JavaSignatures.parseMethodDescriptor(method.descriptor) ?: return null
        val generic = method.signature?.let(JavaSignatures::parseMethodSignature)

        val returnType: KotlinType
        val paramTypes: List<TypeRef?>
        val methodTypeParameters: List<String>
        val methodBounds: List<TypeRef>
        if (generic != null) {
            returnType = generic.returnType.fromSignature(ctx)
            paramTypes = generic.parameterTypes.map { it.fromSignature(ctx) }
            methodTypeParameters = generic.typeParameters.map { it.name }
            methodBounds = generic.typeParameters.map { it.erasureType(ctx) }
        } else {
            // No generic signature: erased types (primitives mapped to their Kotlin classifier), built with
            // the given ctx (null at index time).
            returnType = descriptor.returnType.erased(ctx)
            paramTypes = descriptor.parameterTypes.map { it.erased(ctx) }
            methodTypeParameters = emptyList()
            methodBounds = emptyList()
        }

        // Real parameter names when the class was compiled with `-parameters` (the MethodParameters
        // attribute); `p0`/`p1` otherwise. Positional with the descriptor or not usable at all: javac emits
        // an entry per descriptor parameter (synthesized/mandated ones included), but a rewritten or
        // truncated attribute would silently shift every name onto the wrong type.
        val declared = method.parameterNames
        val real = declared.size == descriptor.parameterTypes.size && declared.all { it.isNotBlank() }
        val paramNames = if (real) declared else emptyList()

        // The param display keeps `[]` (so a vararg/array param is visible to the arg-count check), and is
        // built from the ERASED descriptor even when a generic signature exists.
        val displayParams = descriptor.parameterTypes.mapIndexed { index, type ->
            "${paramNames.getOrNull(index) ?: "p$index"}: ${type.jvmClassName().substringAfterLast('.')}"
        }
        val display = if (isConstructor) {
            "(${displayParams.joinToString(", ")})"
        } else {
            "(${displayParams.joinToString(", ")}): " +
                descriptor.returnType.jvmClassName().substringAfterLast('.')
        }

        return KotlinSymbol(
            // A constructor is keyed by the simple class name (matching the @Metadata decode) and types to
            // the class itself, so a call site can validate its arguments; `.`-completion excludes it.
            name = if (isConstructor) classFqn.substringAfterLast('.') else method.name,
            kind = if (isConstructor) SymbolKind.CONSTRUCTOR else SymbolKind.METHOD,
            type = if (isConstructor) KotlinType(classFqn, context = ctx) else returnType,
            modifiers = mods(method.access),
            origin = BINARY,
            signature = display,
            typeParameters = methodTypeParameters,
            typeParameterBounds = methodBounds,
            paramTypes = paramTypes,
            // Left EMPTY when the attribute is absent, never filled with `p0`/`p1`: that is the signal the
            // source-doc enrichment keys off to splice the real names in.
            paramNames = paramNames,
            declaringClassFqn = classFqn,
            isDeprecated = method.access and ACC_DEPRECATED != 0,
            // ACC_VARARGS ⇒ the LAST parameter is a vararg (`String...`), so it absorbs trailing args.
            varargParamIndex = if (method.access and ACC_VARARGS != 0) paramTypes.size - 1 else -1,
        )
    }

    private fun hidden(access: Int): Boolean =
        access and (ACC_PRIVATE or ACC_SYNTHETIC or ACC_BRIDGE) != 0

    private fun mods(access: Int): Set<Modifier> = buildSet {
        if (access and ACC_PUBLIC != 0) add(Modifier.PUBLIC)
        if (access and ACC_PROTECTED != 0) add(Modifier.PROTECTED)
        if (access and ACC_STATIC != 0) add(Modifier.STATIC)
        if (access and ACC_FINAL != 0) add(Modifier.FINAL)
        if (access and ACC_ABSTRACT != 0) add(Modifier.ABSTRACT)
    }

    private fun String.dotted(): String = replace('/', '.').replace('$', '.')

    private fun JavaTypeParameter.erasureType(ctx: KotlinTypeContext?): KotlinType =
        (classBound ?: interfaceBounds.firstOrNull())?.fromSignature(ctx) ?: KotlinType(OBJECT, context = ctx)

    /**
     * A type from the SIGNATURE grammar.
     *
     * Two things are carried over from the ASM version rather than fixed, because this replaced it and a
     * unilateral improvement would be a behaviour change smuggled in as a port. Both are asserted in
     * `:kotlin-symbols`, so the day either is fixed, that fails and says why:
     *
     *  * the `$` in a nested binary name is left alone here, unlike [erased], which normalises it to a dot;
     *  * `Outer<A, B>.Inner<C>` collapses to ONE name carrying `<A, B, C>`, because ASM's visitor
     *    accumulated arguments across `visitClassType` and `visitInnerClassType` into a single list.
     */
    private fun JavaType.fromSignature(ctx: KotlinTypeContext?): KotlinType = when (this) {
        is JavaType.Primitive -> primitive(descriptor, ctx)
        is JavaType.Variable -> KotlinType(name, isTypeParameter = true, context = ctx)
        is JavaType.Array -> arrayType(element.fromSignature(ctx), ctx)
        is JavaType.Class -> KotlinType(
            signatureClassName(),
            flattenedArguments().map { argument ->
                when (argument.wildcard) {
                    // An unbounded `?` carries no type at all: a star projection on `Any`.
                    '*' -> KotlinType("kotlin.Any", context = ctx, projection = "*")
                    '+' -> argument.type!!.fromSignature(ctx).withProjection("out")
                    '-' -> argument.type!!.fromSignature(ctx).withProjection("in")
                    else -> argument.type!!.fromSignature(ctx)
                }
            },
            context = ctx,
        )
    }

    private fun JavaType.Class.signatureClassName(): String {
        val enclosing = outer
        return if (enclosing == null) name.replace('/', '.') else enclosing.signatureClassName() + "." + name
    }

    private fun JavaType.Class.flattenedArguments(): List<JavaTypeArgument> =
        (outer?.flattenedArguments() ?: emptyList()) + arguments

    /**
     * An erased type (the no-generic-signature path): JVM primitives mapped to their Kotlin classifier
     * (`int` → `kotlin.Int`), arrays through [arrayType], references by FQN.
     *
     * The `$` normalisation is not cosmetic. A nested library type reached through an ERASED member —
     * `getLayoutParams(): ViewGroup.LayoutParams` — would otherwise carry the `$` form, and an
     * assignment/return check comparing it to the dot-form declared type false-flagged a mismatch (both
     * resolve, so the check did not back off).
     */
    private fun JavaType.erased(ctx: KotlinTypeContext?): KotlinType = when (this) {
        is JavaType.Primitive -> primitive(descriptor, ctx)
        is JavaType.Variable -> KotlinType(name, isTypeParameter = true, context = ctx)
        is JavaType.Array -> arrayType(element.erased(ctx), ctx)
        is JavaType.Class -> KotlinType(jvmClassName().replace('$', '.'), context = ctx)
    }

    /**
     * The Kotlin type of a JVM array whose element decoded to [element]. A PRIMITIVE element maps to
     * Kotlin's SPECIALISED array class (`byte[]` → `ByteArray`), never `Array<Byte>`: that specialised class
     * IS the Kotlin type of the parameter, so `outputStream.write(byteArray)` type-checks instead of the
     * argument check reporting "inferred type is ByteArray but Array<Byte> was expected". Everything else —
     * a reference element, a type variable (`T[]` → `Array<T>`), a nested array (`byte[][]` →
     * `Array<ByteArray>`) — nests under `kotlin.Array<E>`.
     */
    private fun arrayType(element: KotlinType, ctx: KotlinTypeContext?): KotlinType {
        val specialised =
            if (element.isTypeParameter || element.nullable) null else PRIMITIVE_ARRAYS[element.qualifiedName]
        return if (specialised != null) {
            KotlinType(specialised, context = ctx)
        } else {
            KotlinType("kotlin.Array", listOf(element), context = ctx)
        }
    }

    /** A Kotlin primitive classifier → the specialised array class a JVM array of it maps to. */
    private val PRIMITIVE_ARRAYS = mapOf(
        "kotlin.Int" to "kotlin.IntArray", "kotlin.Long" to "kotlin.LongArray",
        "kotlin.Short" to "kotlin.ShortArray", "kotlin.Byte" to "kotlin.ByteArray",
        "kotlin.Char" to "kotlin.CharArray", "kotlin.Boolean" to "kotlin.BooleanArray",
        "kotlin.Float" to "kotlin.FloatArray", "kotlin.Double" to "kotlin.DoubleArray",
    )

    private fun primitive(descriptor: Char, ctx: KotlinTypeContext?): KotlinType = KotlinType(
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
        context = ctx,
    )
}
