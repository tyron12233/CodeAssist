package dev.ide.lang.kotlin.symbols

import dev.ide.lang.dom.DomNode
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef

/**
 * Backend-neutral [Symbol]/[TypeRef] implementations for Kotlin.
 * The editor only sees these neutral types; whether a symbol came from project source, decoded `@Metadata`,
 * or the Java bytecode index is invisible above the SPI.
 */

/** What a [KotlinType] needs from the symbol service to enumerate members/supertypes lazily (avoids a cycle). */
interface KotlinTypeContext {
    /** Members of [typeFqn], with the receiver's [typeArgs] substituted into generic member signatures. */
    fun membersOf(typeFqn: String, typeArgs: List<TypeRef>, accessibleFrom: Symbol?): List<Symbol>
    fun supertypesOf(typeFqn: String): List<TypeRef>

    /** A user-facing display name for [typeFqn] when it is the synthetic key of a local/anonymous type
     *  ([SourceIndexBuilder.localTypeFqn] appends a `$L<ordinal>` last segment that must never be shown): a
     *  local class's real name, or `<anonymous>` / `<anonymous : Super>` for an anonymous object. Null when
     *  [typeFqn] isn't a synthetic local type known here — the caller then renders it normally. */
    fun localTypeDisplayName(typeFqn: String): String? = null
}

class KotlinSymbol(
    override val name: String,
    override val kind: SymbolKind,
    override val type: TypeRef? = null,
    override val owner: Symbol? = null,
    override val modifiers: Set<Modifier> = emptySet(),
    override val origin: SymbolOrigin = SymbolOrigin(fromSource = false, file = null),
    /** The receiver type FQN when this is an extension function/property; null otherwise. */
    val receiverTypeFqn: String? = null,
    /** Display signature, e.g. `(name: String): String` for a function. */
    val signature: String? = null,
    /** Own declared type parameters (a generic function's `<T, R>`), for call-site type inference. */
    val typeParameters: List<String> = emptyList(),
    /** Each own type parameter's erased upper bound (positional with [typeParameters]); used to erase a
     *  parameter that argument inference couldn't bind (a raw `findViewById` → its `View` bound). */
    val typeParameterBounds: List<TypeRef> = emptyList(),
    /** When a type parameter's upper bound is a SIBLING type parameter (`fun <R, T : R> …` → T's bound is R),
     *  the bound parameter's NAME (positional with [typeParameters]); null when the bound is concrete/absent.
     *  Drives `T : R` constraint propagation: a receiver-bound `T` makes its bound `R` a lower bound (see
     *  [KotlinSymbolService.bindExtensionReceiver] / [typeParamLowerBounds]). `Result<T>.getOrElse` → String?. */
    val typeParamBoundNames: List<String?> = emptyList(),
    /** TRANSIENT (not persisted): lower bounds inferred for this callable's OWN type parameters from a `X : P`
     *  constraint whose `X` was bound at the receiver (`Result<String>.getOrElse`: T = String, T : R ⇒ R ≥
     *  String). The call-site inference widens these with the actual argument/lambda result. */
    val typeParamLowerBounds: Map<String, TypeRef> = emptyMap(),
    /** Value-parameter types (vararg → element type), for inferring type arguments from call arguments. */
    val paramTypes: List<TypeRef?> = emptyList(),
    /** Value-parameter NAMES (positional with [paramTypes]), for named-argument completion. Empty when the
     *  source doesn't carry real names (Java bytecode strips them, surfacing only `p0`/`p1`). */
    val paramNames: List<String> = emptyList(),
    /** Type arguments of the extension receiver type (e.g. `[T]` for `Iterable<T>.first`), for binding from
     *  the actual receiver's arguments. */
    val receiverTypeArgs: List<TypeRef> = emptyList(),
    /** When the extension's receiver IS a bare type parameter (`fun <T> T.also(): T`), its name — so it can
     *  be bound to the whole actual receiver type (keyed by its upper bound / `kotlin.Any`). */
    val receiverTypeParam: String? = null,
    /** Declaring package, for a TOP-LEVEL callable (`kotlin.math` for `ln`); drives import visibility. Null
     *  for members. */
    val packageName: String? = null,
    /** The declaring JVM class FQN of a BINARY callable: the owning class for a member/constructor, or the
     *  `…Kt` file facade for a top-level function / extension (extensions compile to static facade methods).
     *  This is what the interpreter reflects into; null when source or not yet known. */
    val declaringClassFqn: String? = null,
    /** Kotlin `internal` visibility (PRIVATE/PROTECTED go in [modifiers]). A binary `internal` member isn't
     *  accessible from another module, so it's hidden on member access. */
    val isInternal: Boolean = false,
    /** A `@Composable` function — the interpreter's Compose bridge must thread a `Composer` into its calls. */
    val isComposable: Boolean = false,
    /** An `inline` function (so a composable content lambda is inlined into the caller's group). */
    val isInline: Boolean = false,
    /** An `infix` function — so completion offers it in the infix-operator slot (`a foo b`), resolved against
     *  the left operand's type. */
    val isInfix: Boolean = false,
    /** A `suspend` function — for semantic highlighting (suspension points) and future coroutine checks. */
    val isSuspend: Boolean = false,
    /** A `@Deprecated` declaration — for semantic highlighting (strikethrough on its uses). */
    val isDeprecated: Boolean = false,
    /** The index of the `vararg` value parameter (`listOf`, `mutableStateListOf(vararg …)`), or -1 if none.
     *  A vararg parameter absorbs any number of trailing positional arguments at a call site. */
    val varargParamIndex: Int = -1,
    /** Whether each value parameter declares a DEFAULT value (positional with [paramTypes]) — so a call site
     *  may omit it. EMPTY means "unknown" (Java bytecode, an old cache): callers that validate required
     *  arguments must back off rather than guess. When non-empty its size matches [paramTypes]. */
    val paramHasDefault: List<Boolean> = emptyList(),
    private val declarationNode: DomNode? = null,
    private val doc: String? = null,
) : Symbol {
    val isExtension: Boolean get() = receiverTypeFqn != null
    override fun declaration(): DomNode? = declarationNode
    override fun documentation(): String? = doc

    /** A copy with source-recovered facts spliced in (real parameter [names], rebuilt [sig], [docText]) — used
     *  to enrich a binary symbol from attached sources without re-reading its shape. Other fields are preserved. */
    fun withSourceDoc(
        names: List<String> = paramNames,
        sig: String? = signature,
        docText: String? = doc
    ): KotlinSymbol =
        KotlinSymbol(
            name = name,
            kind = kind,
            type = type,
            owner = owner,
            modifiers = modifiers,
            origin = origin,
            receiverTypeFqn = receiverTypeFqn,
            signature = sig,
            typeParameters = typeParameters,
            typeParameterBounds = typeParameterBounds,
            typeParamBoundNames = typeParamBoundNames,
            typeParamLowerBounds = typeParamLowerBounds,
            paramTypes = paramTypes,
            paramNames = names,
            receiverTypeArgs = receiverTypeArgs,
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
            paramHasDefault = paramHasDefault,
            declarationNode = declarationNode,
            doc = docText,
        )

    /** A copy carrying [typeParamLowerBounds] (the `T : R` lower bounds derived at receiver binding). */
    fun withTypeParamLowerBounds(lower: Map<String, TypeRef>): KotlinSymbol =
        KotlinSymbol(
            name = name,
            kind = kind,
            type = type,
            owner = owner,
            modifiers = modifiers,
            origin = origin,
            receiverTypeFqn = receiverTypeFqn,
            signature = signature,
            typeParameters = typeParameters,
            typeParameterBounds = typeParameterBounds,
            typeParamBoundNames = typeParamBoundNames,
            typeParamLowerBounds = lower,
            paramTypes = paramTypes,
            paramNames = paramNames,
            receiverTypeArgs = receiverTypeArgs,
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
            paramHasDefault = paramHasDefault,
            declarationNode = declarationNode,
            doc = doc,
        )
}

/**
 * A resolved Kotlin type: classifier FQN + type arguments + a nullability flag (populated from
 * metadata). [isTypeParameter] marks an unresolved type-parameter reference (`T`), substituted to a
 * concrete type during generic inference. Member/supertype enumeration delegates to [KotlinTypeContext]
 * so the union of the class's own members with applicable extensions is computed lazily.
 */
class KotlinType(
    override val qualifiedName: String,
    override val typeArguments: List<TypeRef> = emptyList(),
    val nullable: Boolean = false,
    private val context: KotlinTypeContext? = null,
    val isTypeParameter: Boolean = false,
    /** A `kotlin.FunctionN` that is a RECEIVER function type (`T.() -> R`, `@ExtensionFunctionType`): its
     *  first type argument is the receiver, not a value parameter. Drives implicit-`this` (apply/with/run). */
    val isExtensionFunctionType: Boolean = false,
    /** A `@Composable` function type (`@Composable () -> Unit`, a Compose content slot). A lambda passed to a
     *  parameter of this type must be invoked with a threaded `Composer` by the interpreter's Compose bridge —
     *  even when the function it's passed to (e.g. `LazyListScope.items`) is itself NOT `@Composable`. */
    val isComposable: Boolean = false,
    /** This type's USE-SITE projection WHEN it appears as a type argument: `"out"` (`Array<out Number>`),
     *  `"in"` (`Comparator<in T>`... a `MutableList<in E>`), `"*"` (star, `List<*>`), or `""` (no projection).
     *  Combined with the classifier's declaration-site variance to decide subtyping direction per argument. */
    val projection: String = "",
) : TypeRef {

    /** A copy carrying the use-site [p]rojection (`out`/`in`/`*`/``) for when this type sits as a type argument. */
    fun withProjection(p: String): KotlinType =
        if (p == projection) this
        else KotlinType(qualifiedName, typeArguments, nullable, context, isTypeParameter, isExtensionFunctionType, isComposable, p)

    override fun isAssignableFrom(other: TypeRef): Boolean {
        if (other.qualifiedName == qualifiedName) return true
        if (qualifiedName == "kotlin.Any" || qualifiedName == "java.lang.Object") return true
        val seen = HashSet<String>()
        val stack = ArrayDeque(other.supertypes())
        while (stack.isNotEmpty()) {
            val s = stack.removeLast()
            if (!seen.add(s.qualifiedName)) continue
            if (s.qualifiedName == qualifiedName) return true
            stack.addAll(s.supertypes())
        }
        return false
    }

    override fun supertypes(): List<TypeRef> = context?.supertypesOf(qualifiedName) ?: emptyList()

    override fun members(accessibleFrom: Symbol?): List<Symbol> =
        context?.membersOf(qualifiedName, typeArguments, accessibleFrom) ?: emptyList()

    fun withNullable(n: Boolean): KotlinType =
        if (n == nullable) this else KotlinType(
            qualifiedName,
            typeArguments,
            n,
            context,
            isTypeParameter,
            isExtensionFunctionType,
            isComposable,
            projection
        )

    /** A copy with the classifier [fqn] (same arguments, nullability, context) — for canonicalizing a JVM
     *  type to its Kotlin classifier (`java.lang.String` → `kotlin.String`) before a comparison, keeping the
     *  resolution context so the supertype walk still works. */
    fun withClassifier(fqn: String): KotlinType =
        if (fqn == qualifiedName) this
        else KotlinType(
            fqn,
            typeArguments,
            nullable,
            context,
            isTypeParameter,
            isExtensionFunctionType,
            isComposable,
            projection
        )

    /** Rebind the resolution [context] through the whole tree (used when reloading a context-free cache). */
    fun withContext(ctx: KotlinTypeContext?): KotlinType =
        KotlinType(
            qualifiedName,
            typeArguments.map { (it as? KotlinType)?.withContext(ctx) ?: it },
            nullable,
            ctx,
            isTypeParameter,
            isExtensionFunctionType,
            isComposable,
            projection
        )

    override fun toString(): String {
        // A synthetic local/anonymous type key must never leak into display; prefer the context's friendly name
        // (a local class's real name, or `<anonymous : Super>` for an anonymous object). The cheap prefix check
        // keeps normal types off the context lookup; [TypeRendering] renders `<anonymous>` when there's no context.
        if (projection == "*") return "*" // a star projection renders as `*`, regardless of the carrier type
        if (!isTypeParameter && TypeRendering.isSyntheticLocalName(qualifiedName.substringAfterLast('.'))) {
            context?.localTypeDisplayName(qualifiedName)?.let { return it + if (nullable) "?" else "" }
        }
        val base = TypeRendering.render(
            qualifiedName,
            typeArguments.map { it.toString() },
            nullable,
            isTypeParameter,
            isExtensionFunctionType
        )
        // A use-site projection prefixes the argument: `out Number`, `in T`.
        return if (projection.isEmpty()) base else "$projection $base"
    }
}
