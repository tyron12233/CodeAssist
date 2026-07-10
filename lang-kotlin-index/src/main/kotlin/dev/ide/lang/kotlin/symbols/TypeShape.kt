package dev.ide.lang.kotlin.symbols

import dev.ide.lang.resolve.TypeRef

/**
 * The decoded shape of one classpath type, independent of HOW it was decoded (Java bytecode via
 * [JavaBytecode], a Kotlin `@Metadata` class via [KotlinMetadata], or a persisted entry of the
 * `kotlin.typeShape` index). It is everything [KotlinSymbolService.ownAndInherited] needs for one level of
 * the type hierarchy: the type's own declared members, its generic supertypes, and its type parameters
 * (with erased bounds, for raw-use fallback). Type-argument binding stays at query time, so the shape is
 * cached/indexed once and reused for every receiver instantiation.
 */
class TypeShape(
    val typeParameters: List<String>,
    /** Each type parameter's erased upper bound (positional with [typeParameters]); empty when unknown
     *  (the Kotlin-metadata decode doesn't carry bounds — an uncovered parameter is left unbound there). */
    val typeParameterBounds: List<TypeRef>,
    /** Generic supertypes carrying their type arguments (Java bytecode AND the Kotlin `@Metadata` decode), so
     *  a member inherited through a generic supertype substitutes (`CompositionLocal<T>` → `current: TextStyle`). */
    val supertypes: List<TypeRef>,
    val members: List<KotlinSymbol>,
    /** This classpath type is a Kotlin `object` singleton (a bare reference denotes the instance). False for a
     *  Java type or a non-object Kotlin class. Indexed so the consumer needn't re-decode the `@Metadata`. */
    val isObject: Boolean = false,
    /** The simple name of this type's companion object (`Companion`/a custom name), or null when it has none —
     *  so `Type.` can surface the companion's members without a live decode. */
    val companionObjectName: String? = null,
    /** True when this shape came from a Kotlin `@Metadata` class (vs plain Java bytecode). Lets the consumer
     *  answer "is this a Kotlin binary?" (e.g. constructor-default-argument soundness) from the index. */
    val isKotlin: Boolean = false,
    /** True when this type is an `interface` — it cannot be instantiated directly (the abstract-instantiation
     *  check). Indexed so the consumer needn't re-decode. */
    val isInterface: Boolean = false,
    /** True when this type is an `abstract`/`sealed` class — it cannot be instantiated directly. */
    val isAbstract: Boolean = false,
    /** A `sealed` class/interface's DIRECT subclass FQNs (from `@Metadata` `sealedSubclasses`); empty otherwise.
     *  Drives `when`-exhaustiveness over a LIBRARY sealed type (a project sealed type uses the source model). */
    val sealedSubclasses: List<String> = emptyList(),
) {
    /** Rebind every contained [KotlinType] to [ctx] (used after reading a context-free index entry). */
    fun withContext(ctx: KotlinTypeContext?): TypeShape = TypeShape(
        typeParameters,
        typeParameterBounds.map { it.rebind(ctx) },
        supertypes.map { it.rebind(ctx) },
        members.map { it.rebindTypes(ctx) },
        isObject, companionObjectName, isKotlin, isInterface, isAbstract, sealedSubclasses,
    )

    companion object {
        fun of(js: JavaShape): TypeShape = TypeShape(
            js.typeParameters,
            js.typeParameterBounds,
            js.superTypes,
            js.members,
            isKotlin = false,
            isInterface = js.isInterface,
            isAbstract = js.isAbstract
        )

        /** A Kotlin `@Metadata` class: supertypes carry their type arguments (so inherited generic members
         *  substitute); metadata carries no type-parameter bounds. The class's MEMBER extensions (`RowScope`'s
         *  `fun Modifier.weight()`) are kept as members — they carry their extension `receiverTypeFqn`, so a
         *  resolver enumerating an implicit receiver's members (`scopeMemberExtensions`) can apply them to a
         *  matching receiver while in scope, and they don't pollute plain member lookups (which ignore them). */
        fun of(d: KotlinMetadata.Decoded, ctx: KotlinTypeContext?): TypeShape = TypeShape(
            d.typeParameters,
            emptyList(),
            d.supertypes.map { it.rebind(ctx) },
            d.ownMembers + d.extensions,
            isObject = d.isObject,
            companionObjectName = d.companionObjectName,
            isKotlin = true,
            isInterface = d.isInterface,
            isAbstract = d.isAbstractClass,
            sealedSubclasses = d.sealedSubclasses,
        )
    }
}

private fun TypeRef.rebind(ctx: KotlinTypeContext?): TypeRef =
    (this as? KotlinType)?.withContext(ctx) ?: this

/** A copy of this symbol with every [KotlinType] in its signature rebound to [ctx]. */
fun KotlinSymbol.rebindTypes(ctx: KotlinTypeContext?): KotlinSymbol = KotlinSymbol(
    name = name,
    kind = kind,
    type = (type as? KotlinType)?.withContext(ctx) ?: type,
    owner = owner,
    modifiers = modifiers,
    origin = origin,
    receiverTypeFqn = receiverTypeFqn,
    signature = signature,
    typeParameters = typeParameters,
    typeParameterBounds = typeParameterBounds.map { it.rebind(ctx) },
    paramTypes = paramTypes.map { (it as? KotlinType)?.withContext(ctx) ?: it },
    paramNames = paramNames,
    receiverTypeArgs = receiverTypeArgs.map { it.rebind(ctx) },
    receiverTypeParam = receiverTypeParam,
    packageName = packageName,
    declaringClassFqn = declaringClassFqn,
    isInternal = isInternal,
    isComposable = isComposable,
    isInline = isInline,
    isInfix = isInfix,
    isSuspend = isSuspend,
    isDeprecated = isDeprecated,
    varargParamIndex = varargParamIndex,
)
