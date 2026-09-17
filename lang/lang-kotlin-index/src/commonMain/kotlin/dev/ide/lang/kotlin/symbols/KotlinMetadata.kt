package dev.ide.lang.kotlin.symbols

import dev.ide.kotlin.classfile.ClassFile
import dev.ide.kotlin.classfile.KotlinClassInfo
import dev.ide.kotlin.classfile.KotlinDeclaration
import dev.ide.kotlin.classfile.KotlinModality
import dev.ide.kotlin.classfile.KotlinTypeParameter
import dev.ide.kotlin.classfile.KotlinVariance
import dev.ide.kotlin.classfile.KotlinVisibility
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef
import dev.ide.kotlin.classfile.KotlinMetadata as MetadataReader
import dev.ide.kotlin.classfile.KotlinType as MetadataType

/**
 * Decodes a classpath `.class` file's `@kotlin.Metadata` into neutral symbols. Recovers the Kotlin view
 * that bytecode erases: extension functions, properties-as-properties, nullability, and type parameters
 * (kept as [KotlinType.isTypeParameter] refs) plus the value-param types and the extension receiver's type
 * arguments, so the inference engine can substitute them.
 *
 * Read through `:kotlin-classfile` rather than kotlin-metadata-jvm, which is why it can live in
 * `commonMain`. Every rule is unchanged, including the ones that look odd, because `:kotlin-symbols` diffed
 * this against the kotlin-metadata-jvm version declaration for declaration over kotlin-stdlib (852 units,
 * 8,871 symbols) and the whole test classpath (2,125 units, 17,446 symbols) before it replaced it.
 */
object KotlinMetadata {

    /** A decoded unit: a class (own members + supertypes + type params) or a file/multifile facade. */
    class Decoded(
        val classFqn: String?,
        /** Generic supertypes carrying their type arguments (`ProvidableCompositionLocal<T>` → `CompositionLocal<T>`),
         *  so a member inherited through a generic supertype substitutes (the `current: T` → `TextStyle` case). */
        val supertypes: List<TypeRef>,
        /** The class's own type-parameter names (`List<T>` → `["T"]`), for member substitution. */
        val typeParameters: List<String>,
        /** Each type parameter's declaration-site variance (positional with [typeParameters]): `"out"`,
         *  `"in"`, or `""` (invariant) — drives variance-aware subtyping (`List<out E>`, `Comparator<in T>`). */
        val typeParameterVariances: List<String> = emptyList(),
        val ownMembers: List<KotlinSymbol>,
        val topLevel: List<KotlinSymbol>,
        val extensions: List<KotlinSymbol>,
        /** For a multi-file class PART, the public FACADE class FQN (`kotlin.math.MathKt`) the part's
         *  top-level functions are actually invoked through — not the part's own `…__…Kt` name. Null for a
         *  plain file facade (its own class name, set by the reader, is correct). */
        val facadeClassFqn: String? = null,
        /** The simple name of this class's companion object (`"Companion"` by default), or null if none. A
         *  bare `Type.` reference resolves to the companion instance, so extensions applicable to it apply. */
        val companionObjectName: String? = null,
        /** True when this class is a Kotlin `object` singleton (`CardDefaults`, `MaterialTheme`). A bare
         *  reference to it (`CardDefaults.`) denotes the INSTANCE, so its members are accessed like an
         *  instance's — not statics off a type. */
        val isObject: Boolean = false,
        /** True when this class is a Kotlin `interface` — it cannot be instantiated directly. */
        val isInterface: Boolean = false,
        /** True when this class is an `abstract` (or `sealed`) class — it cannot be instantiated directly. */
        val isAbstractClass: Boolean = false,
        /** True when this class is a plain FINAL class — a real `class` (not interface/enum/annotation/object)
         *  whose modality is `final` (Kotlin's default). Such a class cannot be extended, driving the
         *  final-supertype check. `open`/`abstract`/`sealed` classes are false (they may be extended). */
        val isFinalClass: Boolean = false,
        /** A `sealed` class/interface's DIRECT subclass FQNs (`km.sealedSubclasses`), for `when`-exhaustiveness
         *  over a library sealed type; empty otherwise. */
        val sealedSubclasses: List<String> = emptyList(),
        /** For a file/multi-file facade: its top-level `typealias` declarations. A typealias has no `.class` of
         *  its own (it lives in the facade's `@Metadata`), so it is enumerated + resolved through the facade,
         *  unlike a real class. Empty for a class. */
        val typeAliases: List<TypeAliasDecl> = emptyList(),
    ) {
        /** Just the supertype classifier FQNs — for the supertype walk that doesn't need type arguments. */
        val supertypeFqns: List<String> get() = supertypes.mapNotNull { (it as? KotlinType)?.qualifiedName }
    }

    /**
     * A top-level `typealias` a facade declares: its [name] and the FQN of the classifier its EXPANSION names
     * (`Shader` in androidx.compose.ui.graphics expands to `android.graphics.Shader`; a function-type alias
     * expands to `kotlin.FunctionN`). [expandedFqn] is null when the expansion is not a classifier (a bare type
     * parameter). An alias chain is already collapsed: `expandedType` is the fully expanded type.
     */
    class TypeAliasDecl(val name: String, val expandedFqn: String?)

    private val BINARY = SymbolOrigin(fromSource = false, file = null)

    private const val KIND_CLASS = 1
    private const val KIND_FILE_FACADE = 2
    private const val KIND_SYNTHETIC_CLASS = 3
    private const val KIND_MULTIFILE_CLASS = 4
    private const val KIND_MULTIFILE_CLASS_PART = 5

    private const val COMPOSABLE_ANNOTATION = "Landroidx/compose/runtime/Composable;"
    private const val COMPOSER_TYPE = "Landroidx/compose/runtime/Composer;"
    private const val EXTENSION_FUNCTION_TYPE = "kotlin/ExtensionFunctionType"
    private const val COMPOSABLE_TYPE = "androidx/compose/runtime/Composable"

    fun isKotlin(classBytes: ByteArray): Boolean = ClassFile.read(classBytes)?.isKotlin == true

    /**
     * Whether [classBytes] is a Kotlin **file/multi-file facade** or **synthetic** JVM class (`FooKt`,
     * `StringsKt`, `StringsKt__StringsJVMKt`, lambda/`$WhenMappings` classes) — a class that holds top-level
     * callables or compiler-synthesized members, NOT a user-referenceable Kotlin type. `false` for a real
     * `class`/`object`/`interface`/`enum`/`annotation` and for plain (non-Kotlin) bytecode. Lets class-name
     * completion drop facades the bytecode-name-only `java.classNames` index can't distinguish.
     */
    fun isFacadeOrSynthetic(classBytes: ByteArray): Boolean =
        ClassFile.read(classBytes)?.let { isFacadeOrSynthetic(it) } ?: false

    /** [isFacadeOrSynthetic] over an already-parsed [classFile] (the index build reuses one per class). */
    fun isFacadeOrSynthetic(classFile: ClassFile): Boolean =
        when (classFile.metadata?.kind) {
            KIND_FILE_FACADE, KIND_SYNTHETIC_CLASS, KIND_MULTIFILE_CLASS, KIND_MULTIFILE_CLASS_PART -> true
            // A real Class, or plain bytecode with no metadata at all → keep it.
            else -> false
        }

    /**
     * For a MULTI-FILE class facade (`CollectionsKt`, `MathKt` — a public aggregator that carries no members
     * of its own; its top-level declarations live in `…__…Kt` PART classes), the part classes' internal names
     * (`kotlin/collections/CollectionsKt__CollectionsKt`). Null for anything else (a plain class, a single-file
     * facade, non-Kotlin bytecode). Lets a decompiler expand the near-empty facade into its real declarations.
     */
    fun multifileFacadeParts(classBytes: ByteArray): List<String>? =
        ClassFile.read(classBytes)?.metadata?.let(MetadataReader::readMultiFileParts)

    /**
     * Maps each name a caller resolves a Kotlin declaration by — a function's Kotlin name, and a property's
     * conventional accessor names (`getX`/`setX`) — to the ACTUAL JVM method names the compiler emitted for it,
     * INCLUDING the value-class `name-<hash>` and `internal` `name$module` manglings. This is the authoritative
     * mapping (the JVM signature is stored verbatim in `@Metadata`, exactly what kotlin-reflect reads), so a
     * caller can match a mangled reflective method by EXACT name instead of guessing the mangling shape.
     * Returns null when the class carries no members to map (a multi-file FACADE — its members live in
     * `…__…Kt` PART classes — or an unparseable blob); a caller treats that as "no authoritative answer"
     * and falls back to its shape heuristic.
     *
     * The accessor keys are derived with the SAME `get`/`set` + first-char-titlecase convention the reflective
     * reader forms its lookup name with, so a value-class-typed property whose getter mangles to
     * `getBalance-<hash>` is found by the reader's `getBalance` lookup — and a boolean `isX` property (getter
     * `isX`, which the reader would still probe as `getIsX`) is picked up by its `getIsX` key too.
     */
    fun jvmNameIndex(classBytes: ByteArray): Map<String, Set<String>>? =
        jvmNameIndex(ClassFile.read(classBytes)?.metadata?.let(MetadataReader::read))

    /** [jvmNameIndex] over an already-decoded class, for a caller that has one. */
    internal fun jvmNameIndex(info: KotlinClassInfo?): Map<String, Set<String>>? {
        if (info == null) return null
        val out = HashMap<String, MutableSet<String>>()
        fun put(lookup: String, jvm: String) = out.getOrPut(lookup) { HashSet() }.add(jvm)
        for (declaration in info.declarations) {
            when (declaration.kind) {
                KotlinDeclaration.Kind.FUNCTION ->
                    put(declaration.name, declaration.jvmSignature?.name ?: declaration.name)

                KotlinDeclaration.Kind.PROPERTY -> {
                    val signatures = declaration.propertySignatures
                    signatures?.getter?.let { put("get" + declaration.name.capitalizeAscii(), it.name) }
                    signatures?.setter?.let { put("set" + declaration.name.capitalizeAscii(), it.name) }
                }

                else -> Unit
            }
        }
        return if (out.isEmpty()) null else out
    }

    /** First-char titlecase, matching the reflective reader's `get`/`set` accessor-name convention. */
    private fun String.capitalizeAscii(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    fun decode(classBytes: ByteArray, ctx: KotlinTypeContext?): Decoded? =
        ClassFile.read(classBytes)?.let { decode(it, ctx) }

    /** [decode] over an already-parsed [classFile] — the index build reuses ONE per class across every
     *  binary index (see [dev.ide.lang.kotlin.index.sharedClassFile]) instead of parsing here per index. */
    fun decode(classFile: ClassFile, ctx: KotlinTypeContext?): Decoded? {
        val annotation = classFile.metadata ?: return null
        val info = MetadataReader.read(annotation) ?: return null
        // `@Composable` isn't in the @Metadata blob; detect it from the bytecode (the annotation and/or the
        // rewritten `Composer` parameter the Compose compiler threads through).
        val composable = composableMethodNames(classFile)
        return when (annotation.kind) {
            KIND_CLASS -> decodeClass(info, ctx, composable)
            KIND_FILE_FACADE -> decodePackage(info, ctx, null, composable)
            KIND_MULTIFILE_CLASS_PART ->
                decodePackage(info, ctx, annotation.extraString?.replace('/', '.'), composable)

            else -> null
        }
    }

    /** JVM method names that are `@Composable`: either carry the annotation or take a `Composer` parameter. */
    private fun composableMethodNames(classFile: ClassFile): Set<String> = buildSet {
        for (method in classFile.methods) {
            if (COMPOSER_TYPE in method.descriptor || COMPOSABLE_ANNOTATION in method.annotations) {
                add(method.name)
            }
        }
    }

    private fun decodeClass(info: KotlinClassInfo, ctx: KotlinTypeContext?, composable: Set<String>): Decoded {
        val classFqn = info.name ?: ""
        val owner = KotlinSymbol(classFqn.substringAfterLast('.'), SymbolKind.CLASS, origin = BINARY)
        val classTp = info.typeParameters.associate { it.id to it.name }
        val own = ArrayList<KotlinSymbol>()
        val ext = ArrayList<KotlinSymbol>()

        // Grouped by kind rather than taken in protobuf order: functions, then properties, then
        // constructors, which is the order the members were appended in before and which the consumers see.
        for (declaration in info.declarations) {
            if (declaration.kind != KotlinDeclaration.Kind.FUNCTION) continue
            funcSymbol(declaration, ctx, owner, classTp, classFqn, composable)
                .let { if (it.isExtension) ext += it else own += it }
        }
        for (declaration in info.declarations) {
            if (declaration.kind != KotlinDeclaration.Kind.PROPERTY) continue
            propSymbol(declaration, ctx, owner, classTp, classFqn)
                .let { if (it.isExtension) ext += it else own += it }
        }
        for (declaration in info.declarations) {
            if (declaration.kind != KotlinDeclaration.Kind.CONSTRUCTOR) continue
            own += KotlinSymbol(
                name = classFqn.substringAfterLast('.'),
                kind = SymbolKind.CONSTRUCTOR,
                type = ctx?.let { KotlinType(classFqn, context = it) },
                owner = owner,
                origin = BINARY,
                signature = "(" + declaration.parameters.joinToString(", ") { vp ->
                    "${vp.name}: ${typeText(vp.type, classTp)}"
                } + ")",
                paramTypes = declaration.parameters.map {
                    typeRef(it.varargElementType ?: it.type, ctx, classTp)
                },
                paramNames = declaration.parameters.map { it.name },
                declaringClassFqn = classFqn,
                varargParamIndex = declaration.parameters.indexOfFirst { it.varargElementType != null },
            )
        }

        // Enum entries are accessed statically off the enum type (`Color.RED`); surface them as static fields
        // typed to the enum, so expected-type completion can offer them.
        for (entry in info.enumEntryNames) {
            own += KotlinSymbol(
                name = entry,
                kind = SymbolKind.ENUM_CONSTANT,
                type = ctx?.let { KotlinType(classFqn, context = it) },
                owner = owner,
                modifiers = setOf(Modifier.STATIC),
                origin = BINARY,
            )
        }

        // nested classes support
        for (nested in info.nestedClassNames) {
            if (nested == info.companionObjectName) continue
            own += KotlinSymbol(
                name = nested,
                kind = SymbolKind.CLASS,
                type = KotlinType("$classFqn.$nested", context = ctx),
                owner = owner,
                modifiers = setOf(Modifier.STATIC), // a nested type is reached statically via the outer
                origin = BINARY,
            )
        }

        return Decoded(
            classFqn,
            info.supertypes.mapNotNull { typeRef(it, ctx, classTp) },
            info.typeParameters.map { it.name },
            info.typeParameters.map { varianceStr(it.variance) },
            own,
            emptyList(),
            ext,
            companionObjectName = info.companionObjectName,
            isObject = info.classKind?.name == "OBJECT",
            isInterface = info.classKind?.name == "INTERFACE",
            // SEALED is abstract for instantiation purposes too (a sealed class can't be instantiated directly).
            isAbstractClass = info.modality == KotlinModality.ABSTRACT || info.modality == KotlinModality.SEALED,
            // A plain final class (kind CLASS + modality FINAL): the only shape a `: Super()` supertype is
            // illegal to extend. enum/annotation/object kinds and open/abstract/sealed modality are excluded.
            isFinalClass = info.classKind?.name == "CLASS" && info.modality == KotlinModality.FINAL,
            sealedSubclasses = info.sealedSubclassNames,
        )
    }

    private fun decodePackage(
        info: KotlinClassInfo,
        ctx: KotlinTypeContext?,
        facadeFqn: String?,
        composable: Set<String>,
    ): Decoded {
        val top = ArrayList<KotlinSymbol>()
        val ext = ArrayList<KotlinSymbol>()
        // The facade class FQN isn't in the package message for a plain file facade — the ClasspathReader
        // sets declaringClassFqn from the .class entry name. For a multi-file class PART, [facadeFqn]
        // overrides it (the part's `…__…Kt` name isn't where the public static method lives).
        for (declaration in info.declarations) {
            if (declaration.kind != KotlinDeclaration.Kind.FUNCTION) continue
            funcSymbol(declaration, ctx, null, emptyMap(), facadeFqn, composable)
                .let { if (it.isExtension) ext += it else top += it }
        }
        for (declaration in info.declarations) {
            if (declaration.kind != KotlinDeclaration.Kind.PROPERTY) continue
            propSymbol(declaration, ctx, null, emptyMap(), facadeFqn)
                .let { if (it.isExtension) ext += it else top += it }
        }
        return Decoded(
            null, emptyList(), emptyList(), emptyList(), emptyList(), top, ext,
            facadeClassFqn = facadeFqn,
            typeAliases = info.declarations
                .filter { it.kind == KotlinDeclaration.Kind.TYPE_ALIAS }
                .map { TypeAliasDecl(it.name, classifierFqn(it.expandedType)) },
        )
    }

    private fun funcSymbol(
        declaration: KotlinDeclaration,
        ctx: KotlinTypeContext?,
        owner: KotlinSymbol?,
        classTp: Map<Int, String>,
        declaringFqn: String?,
        composable: Set<String>,
    ): KotlinSymbol {
        val tp = classTp + declaration.typeParameters.associate { it.id to it.name }
        val receiver = declaration.receiverType
        val (recvFqn, recvParam) = receiver?.let { receiverInfo(it, declaration.typeParameters) }
            ?: (null to null)
        val params = declaration.parameters.joinToString(", ") { vp ->
            "${vp.name}: ${typeText(vp.varargElementType ?: vp.type, tp)}"
        }
        return KotlinSymbol(
            name = declaration.name,
            kind = SymbolKind.METHOD,
            type = typeRef(declaration.returnType, ctx, tp),
            owner = owner,
            modifiers = visibilityMods(declaration.visibility) + abstractMod(declaration.modality),
            isInternal = declaration.visibility == KotlinVisibility.INTERNAL,
            origin = BINARY,
            receiverTypeFqn = recvFqn,
            signature = "($params): ${typeText(declaration.returnType, tp)}",
            typeParameters = declaration.typeParameters.map { it.name },
            typeParamBoundNames = declaration.typeParameters.map { siblingBoundName(it, tp) },
            paramTypes = declaration.parameters.map { typeRef(it.varargElementType ?: it.type, ctx, tp) },
            paramNames = declaration.parameters.map { it.name },
            receiverTypeArgs = receiver?.arguments.orEmpty().map { argument ->
                argument.type?.let { typeRef(it, ctx, tp) } ?: KotlinType("kotlin.Any", context = ctx)
            },
            receiverTypeParam = recvParam,
            declaringClassFqn = declaringFqn,
            isComposable = declaration.name in composable,
            isInline = declaration.isInline,
            isInfix = declaration.isInfix,
            isSuspend = declaration.isSuspend,
            varargParamIndex = declaration.parameters.indexOfFirst { it.varargElementType != null },
            paramHasDefault = declaration.parameters.map { it.declaresDefaultValue },
        )
    }

    private fun propSymbol(
        declaration: KotlinDeclaration,
        ctx: KotlinTypeContext?,
        owner: KotlinSymbol?,
        classTp: Map<Int, String>,
        declaringFqn: String?,
    ): KotlinSymbol {
        val tp = classTp + declaration.typeParameters.associate { it.id to it.name }
        val receiver = declaration.receiverType
        val (recvFqn, recvParam) = receiver?.let { receiverInfo(it, declaration.typeParameters) }
            ?: (null to null)
        return KotlinSymbol(
            name = declaration.name,
            kind = SymbolKind.FIELD,
            type = typeRef(declaration.returnType, ctx, tp),
            owner = owner,
            modifiers = visibilityMods(declaration.visibility) + abstractMod(declaration.modality),
            isInternal = declaration.visibility == KotlinVisibility.INTERNAL,
            origin = BINARY,
            receiverTypeFqn = recvFqn,
            signature = ": ${typeText(declaration.returnType, tp)}",
            receiverTypeArgs = receiver?.arguments.orEmpty().map { argument ->
                argument.type?.let { typeRef(it, ctx, tp) } ?: KotlinType("kotlin.Any", context = ctx)
            },
            receiverTypeParam = recvParam,
            declaringClassFqn = declaringFqn,
        )
    }

    /** A type parameter's declaration-site variance as the string the model uses: `"out"`/`"in"`/`""`. */
    private fun varianceStr(variance: KotlinVariance): String = when (variance) {
        KotlinVariance.OUT -> "out"
        KotlinVariance.IN -> "in"
        KotlinVariance.INVARIANT -> ""
    }

    /** When [parameter]'s upper bound is a SIBLING type parameter (`fun <R, T : R>` → T's bound is R), that
     *  parameter's name; null when the bound is a class/absent. Drives `T : R` constraint propagation. */
    private fun siblingBoundName(parameter: KotlinTypeParameter, tp: Map<Int, String>): String? =
        parameter.upperBounds.firstNotNullOfOrNull { bound ->
            if (bound.isTypeParameter) resolvedTypeParameterName(bound, tp) else null
        }

    /**
     * For an extension receiver: (keying FQN, type-param name if the receiver is a bare type parameter).
     * `T.also` keys by T's upper bound (`kotlin.Any` if unbounded) and remembers `T` to bind to the receiver.
     *
     * Matched by id when the metadata numbered the reference and by name when it named it: both forms come
     * out of the same compiler, and matching only the first leaves `fun <T> T.runCatching` with no receiver
     * parameter at all.
     */
    private fun receiverInfo(
        receiver: MetadataType,
        declared: List<KotlinTypeParameter>,
    ): Pair<String?, String?> {
        if (!receiver.isTypeParameter) return receiver.classifier to null
        val parameter = declared.firstOrNull { it.id == receiver.typeParameterId }
            ?: declared.firstOrNull { it.name == receiver.typeParameterName }
        val bound = parameter?.upperBounds?.firstNotNullOfOrNull { classifierFqn(it) } ?: "kotlin.Any"
        return bound to parameter?.name
    }

    fun classifierFqn(type: MetadataType?): String? =
        if (type == null || type.isTypeParameter) null else type.classifier

    private fun typeRef(type: MetadataType?, ctx: KotlinTypeContext?, tp: Map<Int, String>): TypeRef? {
        if (type == null) return null
        if (type.isTypeParameter) {
            return KotlinType(
                typeParameterName(type, tp),
                nullable = type.isNullable,
                context = ctx,
                isTypeParameter = true,
            )
        }
        val fqn = type.classifier
        val args = type.arguments.map { argument ->
            // Capture the USE-SITE projection (`Array<out T>`, `Comparator<in T>`, `List<*>`) onto the argument.
            val projection = when {
                argument.type == null -> "*"
                argument.variance == KotlinVariance.OUT -> "out"
                argument.variance == KotlinVariance.IN -> "in"
                else -> ""
            }
            val base = (argument.type?.let { typeRef(it, ctx, tp) } as? KotlinType)
                ?: KotlinType("kotlin.Any", context = ctx)
            if (projection.isEmpty()) base else base.withProjection(projection)
        }
        // `T.() -> R` (apply/with/run blocks, DSL builders) carries @kotlin.ExtensionFunctionType on the type;
        // a Compose content slot (`@Composable () -> Unit`) carries @androidx.compose.runtime.Composable on it.
        val annos = if (TypeRendering.isFunctionType(fqn)) type.annotations else emptyList()
        val isExtFn = EXTENSION_FUNCTION_TYPE in annos
        val isComposable = COMPOSABLE_TYPE in annos
        // A `suspend (…) -> R` parameter is flagged `isSuspend` but stored in its JVM-lowered shape: classifier
        // `FunctionN` with a trailing `Continuation<R>` value parameter and an erased `Any` return. Rewrite it to
        // the source shape `kotlin.SuspendFunction{N-1}` so binary suspend types match the source representation.
        if (type.isSuspend && TypeRendering.isFunctionType(fqn) && args.size >= 2) {
            return desugarSuspendFunctionType(args, type.isNullable, ctx, isExtFn)
        }
        return KotlinType(
            fqn,
            args,
            nullable = type.isNullable,
            context = ctx,
            isExtensionFunctionType = isExtFn,
            isComposable = isComposable,
        )
    }

    /** Convert a JVM-lowered suspend function type's [loweredArgs] (`[p0, …, p{k-1}, Continuation<R>, Any]`) into
     *  the source-level `kotlin.SuspendFunction{k}` with arguments `[p0, …, p{k-1}, R]`. The continuation is the
     *  last value parameter (index `size - 2`; the final entry is the erased `Any` return), and the real return
     *  `R` is its sole type argument. A receiver function type keeps `p0` as the receiver via [isExtFn]. */
    private fun desugarSuspendFunctionType(
        loweredArgs: List<TypeRef>,
        nullable: Boolean,
        ctx: KotlinTypeContext?,
        isExtFn: Boolean,
    ): KotlinType {
        val continuation = loweredArgs[loweredArgs.size - 2]
        val realReturn = (continuation as? KotlinType)?.typeArguments?.firstOrNull()
            ?: KotlinType("kotlin.Unit", context = ctx)
        val realParams = loweredArgs.subList(0, loweredArgs.size - 2)
        return KotlinType(
            "kotlin.SuspendFunction${realParams.size}", realParams + realReturn,
            nullable = nullable, context = ctx, isExtensionFunctionType = isExtFn,
        )
    }

    private fun typeText(type: MetadataType?, tp: Map<Int, String>): String {
        if (type == null) return "?"
        if (type.isTypeParameter) {
            return typeParameterName(type, tp) + if (type.isNullable) "?" else ""
        }
        val args = type.arguments.map { it.type?.let { inner -> typeText(inner, tp) } ?: "*" }
        return TypeRendering.render(type.classifier, args, type.isNullable)
    }

    /**
     * What a type parameter reference is called.
     *
     * The metadata refers to one either by id or BY NAME, and both forms come out of the same compiler.
     * Handling only the id silently resolves a real `V` to the fallback `T`.
     */
    private fun typeParameterName(type: MetadataType, tp: Map<Int, String>): String =
        resolvedTypeParameterName(type, tp) ?: "T"

    private fun resolvedTypeParameterName(type: MetadataType, tp: Map<Int, String>): String? =
        type.typeParameterId?.let { tp[it] } ?: type.typeParameterName

    private fun visibilityMods(visibility: KotlinVisibility?): Set<Modifier> = when (visibility) {
        KotlinVisibility.PRIVATE, KotlinVisibility.PRIVATE_TO_THIS, KotlinVisibility.LOCAL ->
            setOf(Modifier.PRIVATE)

        KotlinVisibility.PROTECTED -> setOf(Modifier.PROTECTED)
        else -> emptySet()
    }

    /** An ABSTRACT member (an interface member with no body, or an `abstract` class member) carries
     *  [Modifier.ABSTRACT] so the "must implement abstract member" check can require an override. An interface
     *  member WITH a default body decodes as OPEN, so it is correctly NOT required. */
    private fun abstractMod(modality: KotlinModality?): Set<Modifier> =
        if (modality == KotlinModality.ABSTRACT) setOf(Modifier.ABSTRACT) else emptySet()
}
