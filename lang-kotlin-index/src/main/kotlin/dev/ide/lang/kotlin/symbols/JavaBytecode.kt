package dev.ide.lang.kotlin.symbols

import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor

/**
 * The Java bytecode shape of a classpath type (the no-`@Metadata` branch): plain Java/Android APIs
 * (`android.jar`, Java libs) carry no Kotlin metadata, so their members are read straight from bytecode
 * with ASM. This is what makes `view.findViewById`, `string` Java methods, etc. complete.
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

object JavaBytecode {

    private val BINARY = SymbolOrigin(fromSource = false, file = null)
    private const val OBJECT = "java.lang.Object"

    fun read(bytes: ByteArray, ctx: KotlinTypeContext?): JavaShape? =
        (runCatching { ClassReader(bytes) }.getOrNull() ?: return null).let { read(it, ctx) }

    /** [read] over an already-parsed [cr] — the index build reuses ONE shared reader per class (see
     *  [dev.ide.lang.kotlin.index.sharedClassReader]) instead of constructing one here per binary index. */
    fun read(cr: ClassReader, ctx: KotlinTypeContext?): JavaShape? {
        val members = ArrayList<KotlinSymbol>()
        val typeParams = ArrayList<String>()
        val typeParamBounds = ArrayList<TypeRef>()
        val superTypes = ArrayList<TypeRef>()
        var selfName: String? = null
        var classAccess = 0
        cr.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int,
                access: Int,
                name: String?,
                sig: String?,
                superName: String?,
                interfaces: Array<out String>?
            ) {
                selfName = name
                classAccess = access
                if (sig != null) {
                    val v = ClassSigVisitor(ctx)
                    SignatureReader(sig).accept(v)
                    typeParams += v.typeParameters
                    typeParamBounds += v.typeParameterBounds
                    superTypes += v.superTypes
                } else {
                    // No generic signature: raw superclass + interfaces (no type arguments). Normalize the
                    // binary `$` nested separator to dot-form (as the self-FQN and every other path do), so a
                    // supertype-chain walk up a nested class (`FrameLayout.LayoutParams`'s super
                    // `ViewGroup$MarginLayoutParams`) compares equal to the dot-form declared/inferred types.
                    superName?.let { superTypes += KotlinType(it.replace('/', '.').replace('$', '.'), context = ctx) }
                    interfaces?.forEach {
                        superTypes += KotlinType(
                            it.replace('/', '.').replace('$', '.'), context = ctx
                        )
                    }
                }
            }

            override fun visitInnerClass(
                name: String, outerName: String?, innerName: String?, access: Int
            ) {
                // A directly-nested type (`android/R$string` inside `android/R`) — surfaced for `android.R.string`
                // navigation. Skip anonymous/local (innerName null) and unrelated entries.
                if (hidden(access) || innerName == null || outerName != selfName) return
                members += KotlinSymbol(
                    name = innerName,
                    kind = SymbolKind.CLASS,
                    // Built with whatever ctx is given (null at index time → rebound on read), never dropped.
                    type = KotlinType(name.replace('/', '.').replace('$', '.'), context = ctx),
                    modifiers = mods(access) + Modifier.STATIC, // a nested type is reached statically via the outer
                    origin = BINARY,
                )
            }

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                sig: String?,
                exceptions: Array<out String>?
            ): MethodVisitor? {
                if (hidden(access)) {
                    return null
                }
                if (name == "<clinit>") {
                    return null
                }
                val isCtor = name == "<init>"
                val t = Type.getMethodType(descriptor)
                val classFqn = selfName?.replace('/', '.')?.replace('$', '.')
                val (retType, paramTypes, methodTypeParams, methodBounds) = if (sig != null) parseMethodSignature(
                    sig,
                    ctx
                )
                else MethodTypes(
                    // No generic signature: erased types (primitives mapped to their Kotlin classifier),
                    // built with the given ctx (null at index time).
                    erased(t.returnType, ctx),
                    t.argumentTypes.map { at -> erased(at, ctx) },
                    emptyList(), emptyList(),
                )
                // Real parameter names when the class was compiled with `-parameters` (the MethodParameters
                // attribute); `p0`/`p1` otherwise. Collected via visitParameter, so the symbol can only be
                // built once the visitor ends. The alternative, the LocalVariableTable, lives inside the Code
                // attribute that SKIP_CODE drops, and reading it would mean parsing every method body.
                return object : MethodVisitor(Opcodes.ASM9) {
                    private val declared = ArrayList<String>()

                    override fun visitParameter(paramName: String?, paramAccess: Int) {
                        declared += paramName.orEmpty()
                    }

                    override fun visitEnd() {
                        // Positional with the descriptor or not usable at all: javac emits an entry per
                        // descriptor parameter (synthesized/mandated ones included), but a rewritten or
                        // truncated attribute would silently shift every name onto the wrong type.
                        val real = declared.size == t.argumentTypes.size && declared.all { it.isNotBlank() }
                        val paramNames = if (real) declared.toList() else emptyList()
                        // The param display keeps `[]` (so a vararg/array param is visible to the arg-count check).
                        val displayParams = t.argumentTypes.mapIndexed { i, at ->
                            "${paramNames.getOrNull(i) ?: "p$i"}: ${at.className.substringAfterLast('.')}"
                        }
                        val display = if (isCtor) "(${displayParams.joinToString(", ")})"
                        else "(${displayParams.joinToString(", ")}): ${
                            t.returnType.className.substringAfterLast(
                                '.'
                            )
                        }"
                        members += KotlinSymbol(
                            // A constructor is keyed by the simple class name (matching the @Metadata decode) and
                            // types to the class itself, so a call site can validate its arguments;
                            // `.`-completion excludes it.
                            name = if (isCtor) classFqn?.substringAfterLast('.') ?: "<init>" else name,
                            kind = if (isCtor) SymbolKind.CONSTRUCTOR else SymbolKind.METHOD,
                            type = if (isCtor) classFqn?.let { KotlinType(it, context = ctx) } else retType,
                            modifiers = mods(access),
                            origin = BINARY,
                            signature = display,
                            typeParameters = methodTypeParams,
                            typeParameterBounds = methodBounds,
                            paramTypes = paramTypes,
                            // Left EMPTY when the attribute is absent, never filled with `p0`/`p1`: that is the
                            // signal the source-doc enrichment keys off to splice the real names in.
                            paramNames = paramNames,
                            declaringClassFqn = classFqn,
                            isDeprecated = access and Opcodes.ACC_DEPRECATED != 0,
                            // ACC_VARARGS ⇒ the LAST parameter is a vararg (`String...`), so it absorbs trailing args.
                            varargParamIndex = if (access and Opcodes.ACC_VARARGS != 0) paramTypes.size - 1 else -1,
                        )
                    }
                }
            }

            override fun visitField(
                access: Int, name: String, descriptor: String, sig: String?, value: Any?
            ): FieldVisitor? {
                if (hidden(access)) return null
                val t = Type.getType(descriptor)
                val type = if (sig != null) parseTypeSignature(sig, ctx) else erased(t, ctx)
                members += KotlinSymbol(
                    name = name,
                    kind = SymbolKind.FIELD,
                    type = type,
                    modifiers = mods(access),
                    origin = BINARY,
                    signature = ": ${t.className.substringAfterLast('.')}",
                    isDeprecated = access and Opcodes.ACC_DEPRECATED != 0,
                )
                return null
            }
            // NOT SKIP_DEBUG: ASM gates the MethodParameters attribute (the real parameter names) on it.
            // Dropping the flag adds no work of its own here: the LineNumberTable/LocalVariableTable it would
            // otherwise skip live inside the Code attribute, which SKIP_CODE drops wholesale either way.
            // Returning a MethodVisitor at all is the real cost: ASM skips every method attribute when
            // visitMethod returns null, so it now walks method ANNOTATIONS to hand them to a visitor that
            // ignores them. Bounded and worth it: that walk is proportional to annotation bytes, not method
            // bodies (~19% of android.jar's classes carry any), and it is what buys real names for the ~6% of
            // android.jar and the many library jars (protobuf-java, sdk-common, intellij-core) built with
            // `-parameters`, none of which need a `-sources.jar` attached to be named any more.
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
        return JavaShape(
            typeParams, typeParamBounds, superTypes, members,
            isInterface = classAccess and Opcodes.ACC_INTERFACE != 0,
            isAbstract = classAccess and Opcodes.ACC_ABSTRACT != 0,
            // A final class (but not an enum — enums are ACC_FINAL yet extending one is a different error, and
            // not an interface, which never sets ACC_FINAL). Non-final Java classes are open from Kotlin.
            isFinal = classAccess and Opcodes.ACC_FINAL != 0 &&
                classAccess and Opcodes.ACC_INTERFACE == 0 && classAccess and Opcodes.ACC_ENUM == 0,
        )
    }

    private fun hidden(access: Int): Boolean =
        access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) != 0

    private fun mods(access: Int): Set<Modifier> = buildSet {
        if (access and Opcodes.ACC_PUBLIC != 0) add(Modifier.PUBLIC)
        if (access and Opcodes.ACC_PROTECTED != 0) add(Modifier.PROTECTED)
        if (access and Opcodes.ACC_STATIC != 0) add(Modifier.STATIC)
        if (access and Opcodes.ACC_FINAL != 0) add(Modifier.FINAL)
        if (access and Opcodes.ACC_ABSTRACT != 0) add(Modifier.ABSTRACT)
    }

    // --- generic signature parsing (ASM SignatureVisitor) ---

    private class MethodTypes(
        val returnType: KotlinType?,
        val paramTypes: List<KotlinType?>,
        val typeParameters: List<String>,
        val typeParameterBounds: List<TypeRef>,
    ) {
        operator fun component1() = returnType
        operator fun component2() = paramTypes
        operator fun component3() = typeParameters
        operator fun component4() = typeParameterBounds
    }

    private fun parseMethodSignature(sig: String, ctx: KotlinTypeContext?): MethodTypes {
        val v = MethodSigVisitor(ctx)
        SignatureReader(sig).accept(v)
        return MethodTypes(v.returnType, v.paramTypes, v.typeParameters, v.typeParameterBounds)
    }

    private fun parseTypeSignature(sig: String, ctx: KotlinTypeContext?): KotlinType? {
        var result: KotlinType? = null
        SignatureReader(sig).acceptType(TypeSigVisitor(ctx) { result = it })
        return result
    }

    /** Collects formal type parameters + their first (erased) bound. Shared by class/method signatures. */
    private abstract class FormalsVisitor(protected val ctx: KotlinTypeContext?) :
        SignatureVisitor(Opcodes.ASM9) {
        val typeParameters = ArrayList<String>()
        val typeParameterBounds = ArrayList<TypeRef>()
        private var currentBoundSet = false

        override fun visitFormalTypeParameter(name: String) {
            typeParameters += name
            typeParameterBounds += KotlinType(OBJECT, context = ctx)
            currentBoundSet = false
        }

        override fun visitClassBound(): SignatureVisitor = boundCollector()
        override fun visitInterfaceBound(): SignatureVisitor = boundCollector()

        private fun boundCollector(): SignatureVisitor = TypeSigVisitor(ctx) { t ->
            // The leftmost bound is the erasure target; keep only the first.
            if (!currentBoundSet && typeParameterBounds.isNotEmpty()) {
                typeParameterBounds[typeParameterBounds.lastIndex] = t
                currentBoundSet = true
            }
        }
    }

    private class ClassSigVisitor(ctx: KotlinTypeContext?) : FormalsVisitor(ctx) {
        val superTypes = ArrayList<TypeRef>()
        override fun visitSuperclass(): SignatureVisitor = TypeSigVisitor(ctx) { superTypes += it }
        override fun visitInterface(): SignatureVisitor = TypeSigVisitor(ctx) { superTypes += it }
    }

    private class MethodSigVisitor(ctx: KotlinTypeContext?) : FormalsVisitor(ctx) {
        val paramTypes = ArrayList<KotlinType?>()
        var returnType: KotlinType? = null
        override fun visitParameterType(): SignatureVisitor =
            TypeSigVisitor(ctx) { paramTypes += it }

        override fun visitReturnType(): SignatureVisitor = TypeSigVisitor(ctx) { returnType = it }
        override fun visitExceptionType(): SignatureVisitor = TypeSigVisitor(ctx) { }
    }

    /**
     * Builds one [KotlinType] from a type-signature position and reports it to [sink]. A type variable
     * becomes an `isTypeParameter` type keyed by its name (`T`); an array goes through [arrayType]; a
     * wildcard contributes its bound (`? extends X` / `? super X` → `X`, unbounded `?` → `Any`); a class
     * type accumulates its arguments and reports on [visitEnd].
     */
    private class TypeSigVisitor(
        private val ctx: KotlinTypeContext?,
        private val sink: (KotlinType) -> Unit,
    ) : SignatureVisitor(Opcodes.ASM9) {
        private var classFqn: String? = null
        private val args = ArrayList<KotlinType>()

        override fun visitBaseType(descriptor: Char) = sink(primitive(descriptor, ctx))

        override fun visitTypeVariable(name: String) =
            sink(KotlinType(name, isTypeParameter = true, context = ctx))

        override fun visitArrayType(): SignatureVisitor = TypeSigVisitor(ctx) { el ->
            sink(arrayType(el, ctx))
        }

        override fun visitClassType(name: String) {
            classFqn = name.replace('/', '.')
        }

        override fun visitInnerClassType(name: String) {
            classFqn = (classFqn ?: "") + "." + name
        }

        override fun visitTypeArgument() {
            args += KotlinType("kotlin.Any", context = ctx, projection = "*")
        } // unbounded `?` → star projection

        override fun visitTypeArgument(wildcard: Char): SignatureVisitor =
            TypeSigVisitor(ctx) { t -> // `+` (extends/out) / `-` (super/in) / `=` (invariant): the bound + projection
                args += when (wildcard) {
                    SignatureVisitor.EXTENDS -> t.withProjection("out")
                    SignatureVisitor.SUPER -> t.withProjection("in")
                    else -> t
                }
            }

        override fun visitEnd() {
            classFqn?.let { sink(KotlinType(it, args.toList(), context = ctx)) }
        }
    }

    /** An erased ASM [Type] (the no-generic-signature path) as a [KotlinType]: JVM primitives mapped to their
     *  Kotlin classifier (`int`→`kotlin.Int`), arrays through [arrayType], references by FQN. */
    private fun erased(t: Type, ctx: KotlinTypeContext?): KotlinType = when (t.sort) {
        Type.VOID -> KotlinType("kotlin.Unit", context = ctx)
        Type.BOOLEAN -> KotlinType("kotlin.Boolean", context = ctx)
        Type.CHAR -> KotlinType("kotlin.Char", context = ctx)
        Type.BYTE -> KotlinType("kotlin.Byte", context = ctx)
        Type.SHORT -> KotlinType("kotlin.Short", context = ctx)
        Type.INT -> KotlinType("kotlin.Int", context = ctx)
        Type.FLOAT -> KotlinType("kotlin.Float", context = ctx)
        Type.LONG -> KotlinType("kotlin.Long", context = ctx)
        Type.DOUBLE -> KotlinType("kotlin.Double", context = ctx)
        // `Type.elementType` strips EVERY dimension (`[[B` → `byte`), so re-wrap once per dimension: a
        // `byte[][]` is `Array<ByteArray>`, not the single-level type a bare elementType would produce.
        Type.ARRAY -> (1..t.dimensions).fold(erased(t.elementType, ctx)) { el, _ -> arrayType(el, ctx) }
        // ASM's `Type.className` keeps the binary `$` nested separator (`android.view.ViewGroup$LayoutParams`),
        // but every other type FQN in the model is dot-form: the class self-FQN (`selfName.replace('$', '.')`),
        // the generic-signature path ([TypeSigVisitor]), and `typeFromText`. A nested library type reached
        // through an ERASED (no-generic-signature) member — `getLayoutParams(): ViewGroup.LayoutParams` — would
        // otherwise carry the `$` form, and an assignment/return check comparing it to the dot-form declared type
        // false-flagged a mismatch (both resolve, so the check didn't back off). Normalize to dot-form to match.
        else -> KotlinType(t.className.replace('$', '.'), context = ctx)
    }

    /**
     * The Kotlin type of a JVM array whose element decoded to [el]. A PRIMITIVE element maps to Kotlin's
     * SPECIALISED array class (`byte[]` → `ByteArray`, `int[]` → `IntArray`), never `Array<Byte>`/`Array<Int>`:
     * that specialised class IS the Kotlin type of the parameter, so `outputStream.write(byteArray)` type-checks
     * instead of the argument check reporting "inferred type is ByteArray but Array<Byte> was expected" (and the
     * mismatch quick-fix then offering an unrelated same-named extension to import). Everything else — a
     * reference element, a type variable (`T[]` → `Array<T>`), a nested array (`byte[][]` → `Array<ByteArray>`)
     * — nests under `kotlin.Array<E>`.
     */
    private fun arrayType(el: KotlinType, ctx: KotlinTypeContext?): KotlinType {
        val specialised = if (el.isTypeParameter || el.nullable) null else PRIMITIVE_ARRAYS[el.qualifiedName]
        return if (specialised != null) KotlinType(specialised, context = ctx)
        else KotlinType("kotlin.Array", listOf(el), context = ctx)
    }

    /** A Kotlin primitive classifier → the specialised array class a JVM array of it maps to (see [arrayType]). */
    private val PRIMITIVE_ARRAYS = mapOf(
        "kotlin.Int" to "kotlin.IntArray", "kotlin.Long" to "kotlin.LongArray",
        "kotlin.Short" to "kotlin.ShortArray", "kotlin.Byte" to "kotlin.ByteArray",
        "kotlin.Char" to "kotlin.CharArray", "kotlin.Boolean" to "kotlin.BooleanArray",
        "kotlin.Float" to "kotlin.FloatArray", "kotlin.Double" to "kotlin.DoubleArray",
    )

    private fun primitive(descriptor: Char, ctx: KotlinTypeContext?): KotlinType = KotlinType(
        when (descriptor) {
            'I' -> "kotlin.Int"; 'J' -> "kotlin.Long"; 'Z' -> "kotlin.Boolean"; 'B' -> "kotlin.Byte"
            'S' -> "kotlin.Short"; 'C' -> "kotlin.Char"; 'F' -> "kotlin.Float"; 'D' -> "kotlin.Double"
            'V' -> "kotlin.Unit"; else -> "kotlin.Any"
        },
        context = ctx,
    )
}
