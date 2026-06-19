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
    /** A `suspend` function — for semantic highlighting (suspension points) and future coroutine checks. */
    val isSuspend: Boolean = false,
    /** The index of the `vararg` value parameter (`listOf`, `mutableStateListOf(vararg …)`), or -1 if none.
     *  A vararg parameter absorbs any number of trailing positional arguments at a call site. */
    val varargParamIndex: Int = -1,
    private val declarationNode: DomNode? = null,
    private val doc: String? = null,
) : Symbol {
    val isExtension: Boolean get() = receiverTypeFqn != null
    override fun declaration(): DomNode? = declarationNode
    override fun documentation(): String? = doc

    /** A copy with source-recovered facts spliced in (real parameter [names], rebuilt [sig], [docText]) — used
     *  to enrich a binary symbol from attached sources without re-reading its shape. Other fields are preserved. */
    fun withSourceDoc(names: List<String> = paramNames, sig: String? = signature, docText: String? = doc): KotlinSymbol =
        KotlinSymbol(
            name, kind, type, owner, modifiers, origin, receiverTypeFqn, sig, typeParameters, typeParameterBounds,
            paramTypes, names, receiverTypeArgs, receiverTypeParam, packageName, declaringClassFqn, isInternal,
            isComposable, isInline, isSuspend, varargParamIndex, declarationNode, docText,
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
) : TypeRef {

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
        if (n == nullable) this else KotlinType(qualifiedName, typeArguments, n, context, isTypeParameter, isExtensionFunctionType, isComposable)

    /** Rebind the resolution [context] through the whole tree (used when reloading a context-free cache). */
    fun withContext(ctx: KotlinTypeContext?): KotlinType =
        KotlinType(qualifiedName, typeArguments.map { (it as? KotlinType)?.withContext(ctx) ?: it }, nullable, ctx, isTypeParameter, isExtensionFunctionType, isComposable)

    override fun toString(): String =
        TypeRendering.render(qualifiedName, typeArguments.map { it.toString() }, nullable, isTypeParameter, isExtensionFunctionType)
}
