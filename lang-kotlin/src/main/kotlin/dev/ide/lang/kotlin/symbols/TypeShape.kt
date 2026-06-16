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
    /** Generic supertypes carrying their type arguments (Java bytecode); the metadata decode erases the
     *  arguments to bare classifier types. */
    val supertypes: List<TypeRef>,
    val members: List<KotlinSymbol>,
) {
    /** Rebind every contained [KotlinType] to [ctx] (used after reading a context-free index entry). */
    fun withContext(ctx: KotlinTypeContext?): TypeShape = TypeShape(
        typeParameters,
        typeParameterBounds.map { it.rebind(ctx) },
        supertypes.map { it.rebind(ctx) },
        members.map { it.rebindTypes(ctx) },
    )

    companion object {
        fun of(js: JavaShape): TypeShape = TypeShape(js.typeParameters, js.typeParameterBounds, js.superTypes, js.members)

        /** A Kotlin `@Metadata` class: supertype arguments are erased by the decode (bare classifier FQNs),
         *  and metadata carries no type-parameter bounds. */
        fun of(d: KotlinMetadata.Decoded, ctx: KotlinTypeContext?): TypeShape = TypeShape(
            d.typeParameters, emptyList(),
            d.supertypeFqns.map { KotlinType(it, context = ctx) }, d.ownMembers,
        )
    }
}

private fun TypeRef.rebind(ctx: KotlinTypeContext?): TypeRef = (this as? KotlinType)?.withContext(ctx) ?: this

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
    receiverTypeArgs = receiverTypeArgs.map { it.rebind(ctx) },
    receiverTypeParam = receiverTypeParam,
    packageName = packageName,
    isInternal = isInternal,
)
