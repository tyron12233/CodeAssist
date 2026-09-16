package dev.ide.kotlin.symbols

import dev.ide.kotlin.classfile.ClassFile
import dev.ide.kotlin.classfile.KotlinClassInfo
import dev.ide.kotlin.classfile.KotlinDeclaration
import dev.ide.kotlin.classfile.KotlinMetadata
import dev.ide.kotlin.classfile.KotlinModality
import dev.ide.kotlin.classfile.KotlinType
import dev.ide.kotlin.classfile.KotlinTypeParameter
import dev.ide.kotlin.classfile.KotlinVariance
import dev.ide.kotlin.classfile.KotlinVisibility

/**
 * `@kotlin.Metadata` to symbols, ported off kotlin-metadata-jvm.
 *
 * The other half of the index's binary reader, and the half that recovers what bytecode ERASES: extension
 * functions, properties that are properties rather than a getter and a setter, nullability, default
 * arguments, and type parameters kept as references so the inference engine can substitute them.
 *
 * A faithful port of `:lang-kotlin-index`'s `KotlinMetadata`, including the parts that look odd. Checked
 * against it declaration for declaration over `kotlin-stdlib` and this build's own output, because the
 * decoder agreeing with kotlin-metadata-jvm about the protobuf is a different claim from the CONSUMER
 * agreeing about the symbols.
 */
object KotlinSymbols {

    /** A decoded unit: a class with its own members, or a file or multi-file facade. */
    class Decoded(
        val classFqn: String?,
        /** Supertypes WITH their arguments, so a member inherited through a generic supertype substitutes. */
        val supertypes: List<TypeName>,
        val typeParameters: List<String>,
        /** Each parameter's DECLARATION-site variance, positional with [typeParameters]. */
        val typeParameterVariances: List<String>,
        val ownMembers: List<Symbol>,
        val topLevel: List<Symbol>,
        val extensions: List<Symbol>,
        /**
         * For a multi-file class PART, the public FACADE the part's top-level functions are invoked
         * through, which is not the part's own `...__...Kt` name.
         */
        val facadeClassFqn: String? = null,
        val companionObjectName: String? = null,
        val isObject: Boolean = false,
        val isInterface: Boolean = false,
        val isAbstractClass: Boolean = false,
        val isFinalClass: Boolean = false,
        val sealedSubclasses: List<String> = emptyList(),
        /** A facade's top-level `typealias` declarations. A typealias has no `.class` of its own. */
        val typeAliases: List<TypeAliasDecl> = emptyList(),
    )

    /** A `typealias` and the classifier its expansion names, or null when the expansion is a type parameter. */
    class TypeAliasDecl(val name: String, val expandedFqn: String?)

    private const val KIND_CLASS = 1
    private const val KIND_FILE_FACADE = 2
    private const val KIND_MULTIFILE_CLASS_PART = 5

    private const val COMPOSABLE_ANNOTATION = "Landroidx/compose/runtime/Composable;"
    private const val COMPOSER_TYPE = "Landroidx/compose/runtime/Composer;"
    private const val EXTENSION_FUNCTION_TYPE = "kotlin/ExtensionFunctionType"
    private const val COMPOSABLE_TYPE = "androidx/compose/runtime/Composable"

    fun decode(bytes: ByteArray): Decoded? = ClassFile.read(bytes)?.let(::decode)

    fun decode(classFile: ClassFile): Decoded? {
        val annotation = classFile.metadata ?: return null
        val info = KotlinMetadata.read(annotation) ?: return null
        // `@Composable` is NOT in the metadata blob. It is an annotation on the JVM method, so it has to
        // come from the bytecode even though everything else here comes from the protobuf.
        val composable = composableMethodNames(classFile)
        return when (annotation.kind) {
            KIND_CLASS -> decodeClass(info, composable)
            KIND_FILE_FACADE -> decodePackage(info, null, composable)
            KIND_MULTIFILE_CLASS_PART ->
                decodePackage(info, annotation.extraString?.replace('/', '.'), composable)

            else -> null
        }
    }

    /**
     * JVM method names that are composable: they carry the annotation, or they take a `Composer`.
     *
     * The second half matters because the Compose compiler rewrites a composable function to thread a
     * `Composer` through, and an inline or lambda-lifted one can end up with the parameter and without the
     * annotation.
     */
    private fun composableMethodNames(classFile: ClassFile): Set<String> = buildSet {
        for (method in classFile.methods) {
            if (COMPOSER_TYPE in method.descriptor || COMPOSABLE_ANNOTATION in method.annotations) {
                add(method.name)
            }
        }
    }

    private fun decodeClass(info: KotlinClassInfo, composable: Set<String>): Decoded {
        val classFqn = info.name ?: ""
        val owner = Symbol(classFqn.substringAfterLast('.'), SymbolKind.CLASS)
        val scope = info.typeParameters.associate { it.id to it.name }
        val own = ArrayList<Symbol>()
        val extensions = ArrayList<Symbol>()

        // Grouped by kind rather than taken in protobuf order, because the original iterates functions,
        // then properties, then constructors, and the protobuf writes constructors first.
        for (declaration in info.declarations) {
            if (declaration.kind != KotlinDeclaration.Kind.FUNCTION) continue
            functionSymbol(declaration, owner, scope, classFqn, composable)
                .let { if (it.isExtension) extensions.add(it) else own.add(it) }
        }
        for (declaration in info.declarations) {
            if (declaration.kind != KotlinDeclaration.Kind.PROPERTY) continue
            propertySymbol(declaration, owner, scope, classFqn)
                .let { if (it.isExtension) extensions.add(it) else own.add(it) }
        }
        for (declaration in info.declarations) {
            if (declaration.kind != KotlinDeclaration.Kind.CONSTRUCTOR) continue
            own.add(constructorSymbol(declaration, owner, scope, classFqn))
        }

        // An enum entry is reached statically off the enum type, so it is surfaced as a static field typed
        // to the enum: that is what lets expected-type completion offer it.
        for (entry in info.enumEntryNames) {
            own.add(
                Symbol(
                    name = entry,
                    kind = SymbolKind.ENUM_CONSTANT,
                    type = TypeName(classFqn),
                    owner = owner,
                    modifiers = setOf(Modifier.STATIC),
                ),
            )
        }
        for (nested in info.nestedClassNames) {
            if (nested == info.companionObjectName) continue
            own.add(
                Symbol(
                    name = nested,
                    kind = SymbolKind.CLASS,
                    type = TypeName("$classFqn.$nested"),
                    owner = owner,
                    // A nested type is reached statically through the outer one.
                    modifiers = setOf(Modifier.STATIC),
                ),
            )
        }

        return Decoded(
            classFqn = classFqn,
            supertypes = info.supertypes.mapNotNull { typeRef(it, scope) },
            typeParameters = info.typeParameters.map { it.name },
            typeParameterVariances = info.typeParameters.map { varianceString(it.variance) },
            ownMembers = own,
            topLevel = emptyList(),
            extensions = extensions,
            companionObjectName = info.companionObjectName,
            isObject = info.classKind?.name == "OBJECT",
            isInterface = info.classKind?.name == "INTERFACE",
            // A sealed class cannot be instantiated directly either, so it counts as abstract here.
            isAbstractClass = info.modality == KotlinModality.ABSTRACT || info.modality == KotlinModality.SEALED,
            // A plain final class is the only shape a `: Super()` supertype is illegal to extend. Enum,
            // annotation and object kinds are excluded, and so is open, abstract or sealed modality.
            isFinalClass = info.classKind?.name == "CLASS" && info.modality == KotlinModality.FINAL,
            sealedSubclasses = info.sealedSubclassNames,
        )
    }

    private fun decodePackage(
        info: KotlinClassInfo,
        facadeFqn: String?,
        composable: Set<String>,
    ): Decoded {
        val topLevel = ArrayList<Symbol>()
        val extensions = ArrayList<Symbol>()
        for (declaration in info.declarations) {
            if (declaration.kind != KotlinDeclaration.Kind.FUNCTION) continue
            functionSymbol(declaration, null, emptyMap(), facadeFqn, composable)
                .let { if (it.isExtension) extensions.add(it) else topLevel.add(it) }
        }
        for (declaration in info.declarations) {
            if (declaration.kind != KotlinDeclaration.Kind.PROPERTY) continue
            propertySymbol(declaration, null, emptyMap(), facadeFqn)
                .let { if (it.isExtension) extensions.add(it) else topLevel.add(it) }
        }
        return Decoded(
            classFqn = null,
            supertypes = emptyList(),
            typeParameters = emptyList(),
            typeParameterVariances = emptyList(),
            ownMembers = emptyList(),
            topLevel = topLevel,
            extensions = extensions,
            facadeClassFqn = facadeFqn,
            typeAliases = info.declarations
                .filter { it.kind == KotlinDeclaration.Kind.TYPE_ALIAS }
                .map { TypeAliasDecl(it.name, classifierFqn(it.expandedType)) },
        )
    }

    private fun functionSymbol(
        declaration: KotlinDeclaration,
        owner: Symbol?,
        outerScope: Map<Int, String>,
        declaringFqn: String?,
        composable: Set<String>,
    ): Symbol {
        val scope = outerScope + declaration.typeParameters.associate { it.id to it.name }
        val receiver = declaration.receiverType
        val receiverFqn = receiver?.let { receiverInfo(it, declaration.typeParameters) }
        val parameters = declaration.parameters.joinToString(", ") {
            "${it.name}: ${typeText(it.varargElementType ?: it.type, scope)}"
        }
        return Symbol(
            name = declaration.name,
            kind = SymbolKind.METHOD,
            type = typeRef(declaration.returnType, scope),
            owner = owner,
            modifiers = visibilityModifiers(declaration.visibility) + abstractModifier(declaration.modality),
            signature = "($parameters): ${typeText(declaration.returnType, scope)}",
            typeParameters = declaration.typeParameters.map { it.name },
            typeParameterBounds = emptyList(),
            typeParamBoundNames = declaration.typeParameters.map { siblingBoundName(it, scope) },
            paramTypes = declaration.parameters.map { typeRef(it.varargElementType ?: it.type, scope) },
            paramNames = declaration.parameters.map { it.name },
            paramHasDefault = declaration.parameters.map { it.declaresDefaultValue },
            receiverTypeFqn = receiverFqn?.first,
            receiverTypeArgs = receiver?.arguments.orEmpty().map { argument ->
                argument.type?.let { typeRef(it, scope) } ?: TypeName("kotlin.Any")
            },
            receiverTypeParam = receiverFqn?.second,
            declaringClassFqn = declaringFqn,
            isInternal = declaration.visibility == KotlinVisibility.INTERNAL,
            isComposable = declaration.name in composable,
            isInline = declaration.isInline,
            isInfix = declaration.isInfix,
            isSuspend = declaration.isSuspend,
            varargParamIndex = declaration.parameters.indexOfFirst { it.varargElementType != null },
        )
    }

    private fun propertySymbol(
        declaration: KotlinDeclaration,
        owner: Symbol?,
        outerScope: Map<Int, String>,
        declaringFqn: String?,
    ): Symbol {
        val scope = outerScope + declaration.typeParameters.associate { it.id to it.name }
        val receiver = declaration.receiverType
        val receiverFqn = receiver?.let { receiverInfo(it, declaration.typeParameters) }
        return Symbol(
            name = declaration.name,
            kind = SymbolKind.FIELD,
            type = typeRef(declaration.returnType, scope),
            owner = owner,
            modifiers = visibilityModifiers(declaration.visibility) + abstractModifier(declaration.modality),
            signature = ": ${typeText(declaration.returnType, scope)}",
            receiverTypeFqn = receiverFqn?.first,
            receiverTypeArgs = receiver?.arguments.orEmpty().map { argument ->
                argument.type?.let { typeRef(it, scope) } ?: TypeName("kotlin.Any")
            },
            receiverTypeParam = receiverFqn?.second,
            declaringClassFqn = declaringFqn,
            isInternal = declaration.visibility == KotlinVisibility.INTERNAL,
        )
    }

    private fun constructorSymbol(
        declaration: KotlinDeclaration,
        owner: Symbol,
        scope: Map<Int, String>,
        classFqn: String,
    ): Symbol = Symbol(
        // Keyed by the simple class name, and typed to the class, so a call site can check its arguments.
        name = classFqn.substringAfterLast('.'),
        kind = SymbolKind.CONSTRUCTOR,
        type = TypeName(classFqn),
        owner = owner,
        signature = "(" + declaration.parameters.joinToString(", ") {
            "${it.name}: ${typeText(it.type, scope)}"
        } + ")",
        paramTypes = declaration.parameters.map { typeRef(it.varargElementType ?: it.type, scope) },
        paramNames = declaration.parameters.map { it.name },
        declaringClassFqn = classFqn,
        varargParamIndex = declaration.parameters.indexOfFirst { it.varargElementType != null },
    )

    /**
     * For an extension receiver: the FQN it is keyed by, and the parameter name when the receiver IS a bare
     * type parameter.
     *
     * `fun <T> T.also()` is keyed by T's upper bound, `kotlin.Any` when unbounded, and remembers `T` so the
     * whole actual receiver can be bound to it.
     */
    private fun receiverInfo(
        receiver: KotlinType,
        declared: List<KotlinTypeParameter>,
    ): Pair<String?, String?> {
        if (!receiver.isTypeParameter) return receiver.classifier to null
        // Matched by id when the metadata numbered the reference and by name when it named it. Matching on
        // one only leaves `fun <T> T.runCatching` with no receiver parameter, and an extension whose
        // receiver is a bare parameter then cannot be bound to the actual receiver at all.
        val parameter = declared.firstOrNull { it.id == receiver.typeParameterId }
            ?: declared.firstOrNull { it.name == receiver.typeParameterName }
        val bound = parameter?.upperBounds?.firstNotNullOfOrNull { classifierFqn(it) } ?: "kotlin.Any"
        return bound to parameter?.name
    }

    /**
     * When a parameter's upper bound is a SIBLING parameter (`fun <R, T : R>`), that parameter's name.
     *
     * Drives `T : R` constraint propagation: a receiver-bound `T` makes its bound `R` a lower bound.
     */
    private fun siblingBoundName(parameter: KotlinTypeParameter, scope: Map<Int, String>): String? =
        parameter.upperBounds.firstNotNullOfOrNull { bound ->
            if (bound.isTypeParameter) resolvedTypeParameterName(bound, scope) else null
        }

    /**
     * What a type parameter reference is called.
     *
     * The metadata refers to one either by id or by name, and both forms come out of the same compiler. By
     * id needs the enclosing declaration's scope; by name is already the answer. Handling only the first
     * turns every `V` written the second way into the fallback `T`, silently.
     */
    private fun typeParameterName(type: KotlinType, scope: Map<Int, String>): String =
        resolvedTypeParameterName(type, scope) ?: "T"

    private fun resolvedTypeParameterName(type: KotlinType, scope: Map<Int, String>): String? =
        type.typeParameterId?.let { scope[it] } ?: type.typeParameterName

    private fun classifierFqn(type: KotlinType?): String? =
        if (type == null || type.isTypeParameter) null else type.classifier

    private fun typeRef(type: KotlinType?, scope: Map<Int, String>): TypeName? {
        if (type == null) return null
        if (type.isTypeParameter) {
            return TypeName(
                qualifiedName = typeParameterName(type, scope),
                isTypeParameter = true,
                nullable = type.isNullable,
            )
        }
        val fqn = type.classifier
        val arguments = type.arguments.map { argument ->
            // The USE-SITE projection goes onto the argument: `Array<out T>`, `Comparator<in T>`, `List<*>`.
            val projection = when {
                argument.type == null -> "*"
                argument.variance == KotlinVariance.OUT -> "out"
                argument.variance == KotlinVariance.IN -> "in"
                else -> ""
            }
            val base = argument.type?.let { typeRef(it, scope) } ?: TypeName("kotlin.Any")
            if (projection.isEmpty()) base else base.withProjection(projection)
        }
        // `T.() -> R` carries @kotlin.ExtensionFunctionType ON the type, and a Compose content slot carries
        // @androidx.compose.runtime.Composable the same way. Neither is a flag.
        val annotations = if (TypeRendering.isFunctionType(fqn)) type.annotations else emptyList()
        val isExtensionFunctionType = EXTENSION_FUNCTION_TYPE in annotations
        val isComposable = COMPOSABLE_TYPE in annotations
        // A `suspend (...) -> R` is flagged suspend but STORED in its JVM-lowered shape: classifier
        // `FunctionN`, a trailing `Continuation<R>` parameter, and an erased `Any` return. Rewriting it back
        // to the source shape is what makes a binary suspend type compare equal to a source one.
        if (type.isSuspend && TypeRendering.isFunctionType(fqn) && arguments.size >= 2) {
            return desugarSuspendFunctionType(arguments, type.isNullable, isExtensionFunctionType)
        }
        return TypeName(
            qualifiedName = fqn,
            typeArguments = arguments,
            nullable = type.isNullable,
            isExtensionFunctionType = isExtensionFunctionType,
            isComposable = isComposable,
        )
    }

    /**
     * `[p0, ..., p{k-1}, Continuation<R>, Any]` back into `kotlin.SuspendFunction{k}` with `[p0, ..., R]`.
     *
     * The continuation is the last value parameter, at `size - 2`, because the final entry is the erased
     * `Any` return; the real return is its sole type argument.
     */
    private fun desugarSuspendFunctionType(
        loweredArguments: List<TypeName>,
        nullable: Boolean,
        isExtensionFunctionType: Boolean,
    ): TypeName {
        val continuation = loweredArguments[loweredArguments.size - 2]
        val realReturn = continuation.typeArguments.firstOrNull() ?: TypeName("kotlin.Unit")
        val realParameters = loweredArguments.subList(0, loweredArguments.size - 2)
        return TypeName(
            qualifiedName = "kotlin.SuspendFunction${realParameters.size}",
            typeArguments = realParameters + realReturn,
            nullable = nullable,
            isExtensionFunctionType = isExtensionFunctionType,
        )
    }

    private fun typeText(type: KotlinType?, scope: Map<Int, String>): String {
        if (type == null) return "?"
        if (type.isTypeParameter) {
            return typeParameterName(type, scope) + if (type.isNullable) "?" else ""
        }
        val arguments = type.arguments.map { it.type?.let { inner -> typeText(inner, scope) } ?: "*" }
        return TypeRendering.render(type.classifier, arguments, type.isNullable)
    }

    private fun varianceString(variance: KotlinVariance): String = when (variance) {
        KotlinVariance.OUT -> "out"
        KotlinVariance.IN -> "in"
        KotlinVariance.INVARIANT -> ""
    }

    private fun visibilityModifiers(visibility: KotlinVisibility?): Set<Modifier> = when (visibility) {
        KotlinVisibility.PRIVATE, KotlinVisibility.PRIVATE_TO_THIS, KotlinVisibility.LOCAL ->
            setOf(Modifier.PRIVATE)

        KotlinVisibility.PROTECTED -> setOf(Modifier.PROTECTED)
        else -> emptySet()
    }

    /**
     * An ABSTRACT member carries the modifier so that the "must implement abstract member" check can
     * require an override. An interface member WITH a default body decodes as OPEN, so it is correctly not
     * required.
     */
    private fun abstractModifier(modality: KotlinModality?): Set<Modifier> =
        if (modality == KotlinModality.ABSTRACT) setOf(Modifier.ABSTRACT) else emptySet()
}
