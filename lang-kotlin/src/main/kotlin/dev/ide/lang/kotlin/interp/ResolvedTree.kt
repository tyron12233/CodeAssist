package dev.ide.lang.kotlin.interp

import dev.ide.lang.kotlin.symbols.KotlinType

/**
 * The resolver → interpreter contract (see `docs/compose-interpreter.md`). A [ResolvedTree] is the **total**
 * lowering of a Kotlin function body: every node the resolver accepts carries an *exact* answer (the
 * overload-resolved callee, the slot a name binds to, conventions already desugared), and anything outside
 * the supported subset becomes an [RNode.Unsupported] — never a wrong guess. The interpreter walks this tree
 * and never re-resolves names, dispatch, or operators.
 *
 * This is deliberately separate from the editor's `KotlinResolver`, which is first-candidate-by-arity and
 * degrades to null — fine for completion, unsound for execution.
 *
 * v0 node set. It grows with the interpreter milestones; the resolver ([KotlinTreeResolver]) currently
 * produces a subset and marks the rest [RNode.Unsupported].
 */

/** A source character range, for diagnostics and as the natural positional-memoization key for Compose. */
data class SourceSpan(val start: Int, val end: Int)

/** A unique local/parameter slot within one [ResolvedFunction]; the interpreter's environment is slot-indexed. */
@JvmInline value class SlotId(val value: Int)

/** Stable per-call-site key (the call's start offset) — Compose positional memoization keys off this. */
@JvmInline value class CallSiteKey(val value: Int)

/** How a call dispatches — decided once, here, so the interpreter doesn't. */
enum class DispatchKind {
    MEMBER, EXTENSION, TOP_LEVEL, OPERATOR, INVOKE, CONSTRUCTOR,
    /** A MEMBER extension dispatched on an implicit-receiver scope: `RowScope.weight` called as
     *  `Modifier.weight(1f)` inside `Row { }`. [RNode.Call.dispatchReceiver] is the scope instance (the
     *  content lambda's receiver), [RNode.Call.receiver] is the extension receiver (the `Modifier`). */
    MEMBER_EXTENSION,
}

/** What a name resolves to. Locals/params carry their slot; the rest carry enough to read/invoke. */
sealed interface Binding {
    val name: String
    data class Local(val slot: SlotId, override val name: String, val mutable: Boolean) : Binding
    data class Param(val slot: SlotId, override val name: String) : Binding
    /** A local `val/var x by <delegate>` whose value is read/written through the delegate's `.value` — the
     *  `State`/`MutableState`/`Lazy` convention. [slot] holds the **delegate object** (`remember { … }`'s
     *  result); the resolver expands each read to `delegate.value` ([RNode.PropertyGet]) and each write to
     *  `delegate.value = …` ([RNode.PropertySet]) via [valueProperty], so the interpreter never sees this
     *  binding directly (it only walks plain [Local]-rooted get/set nodes). */
    data class DelegatedLocal(
        val slot: SlotId,
        override val name: String,
        val mutable: Boolean,
        val valueProperty: Property,
    ) : Binding
    /** A member or top-level property (read/written through [RNode.PropertyGet]/[RNode.PropertySet]).
     *  When [isExtension] is true this is an extension property: [ownerFqn] is the declaring `…Kt` facade and
     *  its getter is a STATIC method taking the receiver as its first argument (`16.dp` → `DpKt.getDp(int)`),
     *  not an instance getter on the receiver. Otherwise [ownerFqn] is the receiver/declaring type (informational)
     *  and the getter is an instance method. */
    data class Property(
        override val name: String,
        val ownerFqn: String?,
        val backingField: Boolean,
        val isExtension: Boolean = false,
    ) : Binding
    /** An `object`/companion singleton referenced by name. */
    data class ObjectRef(val fqn: String, override val name: String) : Binding
    data class EnumEntry(val enumFqn: String, override val name: String) : Binding
    /** An implicit/explicit `this` receiver of [type] (innermost when [depth] == 0). */
    data class Receiver(val type: KotlinType?, val depth: Int = 0, override val name: String = "this") : Binding
}

/**
 * The exact target of a call — enough for the interpreter to either invoke reflectively ([Library]) or
 * interpret the body ([Source]). [isComposable] drives the Compose bridge (composer threading / mangled
 * signature); the metadata decoder does not yet surface `@Composable`, so it is `false` for now.
 */
sealed interface ResolvedCallable {
    val displayName: String
    val isComposable: Boolean

    /** A precompiled (binary) target — invoked reflectively. [ownerFqn] is the declaring class (a synthetic
     *  `…Kt` facade for a top-level callable); [descriptorPrecise] is false when the resolver could not pin
     *  the exact JVM owner/descriptor yet (the symbol model doesn't carry it for every binary member). */
    data class Library(
        override val displayName: String,
        val ownerFqn: String?,
        val methodName: String,
        val paramTypes: List<KotlinType?>,
        val isStatic: Boolean,
        val isConstructor: Boolean,
        val isInline: Boolean,
        override val isComposable: Boolean = false,
        val descriptorPrecise: Boolean = false,
        /** The callee's declared value-parameter names, in declaration order — used to bind named arguments
         *  (`Text(text = …, modifier = …)`) back to their parameter positions before dispatch. Empty when the
         *  symbol model didn't carry them (then only positional binding is possible). */
        val paramNames: List<String> = emptyList(),
    ) : ResolvedCallable

    /** A project-source target — its body is available to interpret. [declId] locates the declaration. */
    data class Source(
        override val displayName: String,
        val declId: String,
        val paramNames: List<String>,
        val isConstructor: Boolean = false,
        override val isComposable: Boolean = false,
    ) : ResolvedCallable
}

/** One argument of a call. [name] is set for a named argument; [spread] for a `*array` vararg spread. */
data class RArg(val value: RNode, val name: String? = null, val spread: Boolean = false)

/** A lambda/function parameter slot. */
data class RParam(val slot: SlotId, val name: String, val type: KotlinType?)

/** A node of the resolved tree. Sealed + total: the only "I can't" is [Unsupported]. */
sealed interface RNode {
    val source: SourceSpan

    // --- expressions ---
    data class Const(val value: Any?, val type: KotlinType?, override val source: SourceSpan) : RNode
    data class Name(val binding: Binding, override val source: SourceSpan) : RNode
    data class This(val receiver: Binding.Receiver, override val source: SourceSpan) : RNode
    data class Call(
        val callee: ResolvedCallable,
        val dispatch: DispatchKind,
        val receiver: RNode?,
        val args: List<RArg>,
        val callSiteKey: CallSiteKey,
        override val source: SourceSpan,
        /** For [DispatchKind.MEMBER_EXTENSION]: the scope instance the member extension is invoked on (the
         *  enclosing receiver-lambda's `this`). Null for every other dispatch kind. */
        val dispatchReceiver: RNode? = null,
    ) : RNode
    data class PropertyGet(val receiver: RNode?, val binding: Binding, override val source: SourceSpan) : RNode
    data class PropertySet(val receiver: RNode?, val binding: Binding, val value: RNode, override val source: SourceSpan) : RNode
    data class If(val condition: RNode, val then: RNode, val otherwise: RNode?, override val source: SourceSpan) : RNode
    data class Block(val statements: List<RNode>, val isExpression: Boolean, override val source: SourceSpan) : RNode
    data class Lambda(val params: List<RParam>, val body: RNode, val captures: List<Binding>, override val source: SourceSpan) : RNode
    /** A string template: `"a${x}b"` → the concatenation of its parts (literals + interpolated expressions,
     *  each stringified at runtime). A plain string literal stays an [Const]. */
    data class StringConcat(val parts: List<RNode>, override val source: SourceSpan) : RNode
    /** `value!!` — the not-null assertion. Evaluates [value]; throws a `NullPointerException` if it is null,
     *  otherwise yields it unchanged. */
    data class NotNull(val value: RNode, override val source: SourceSpan) : RNode
    /** `value is T` (or `!is` when [negated]) — a runtime type test against the classifier [typeFqn]. The
     *  interpreter matches a [SourceObject] by walking its class hierarchy and a reflectable value by
     *  `Class.isInstance`. */
    data class TypeCheck(val value: RNode, val typeFqn: String, val negated: Boolean, override val source: SourceSpan) : RNode

    // --- statements ---
    /** `try { … } catch (e: T) { … } … finally { … }`. The interpreter runs [body]; if it throws, the first
     *  [catches] entry whose [RCatch.typeFqn] matches the thrown value handles it (the value bound to the
     *  catch slot); [finallyBlock] always runs last. */
    data class Try(val body: RNode, val catches: List<RCatch>, val finallyBlock: RNode?, override val source: SourceSpan) : RNode
    data class LocalVar(val slot: SlotId, val name: String, val mutable: Boolean, val initializer: RNode?, override val source: SourceSpan) : RNode
    data class Assign(val target: RNode, val value: RNode, override val source: SourceSpan) : RNode
    data class Return(val value: RNode?, override val source: SourceSpan) : RNode
    data class Throw(val value: RNode, override val source: SourceSpan) : RNode
    data class While(val condition: RNode, val body: RNode, val doWhile: Boolean, override val source: SourceSpan) : RNode
    /** `for (x in xs)` with its convention methods pre-resolved (the interpreter just invokes them). */
    data class ForEach(
        val loopVar: RParam,
        val iterable: RNode,
        val iterator: ResolvedCallable?,
        val hasNext: ResolvedCallable?,
        val next: ResolvedCallable?,
        val body: RNode,
        override val source: SourceSpan,
    ) : RNode

    /** The escape hatch that keeps the contract total. The interpreter refuses to run a tree containing one. */
    data class Unsupported(val reason: String, val text: String, override val source: SourceSpan) : RNode
}

/** One `catch (slot: typeFqn) { body }` arm of an [RNode.Try]. A null [typeFqn] catches anything (it couldn't
 *  be resolved); the slot binds the caught value for the handler body. */
data class RCatch(val slot: SlotId, val name: String, val typeFqn: String?, val body: RNode)

/** A lowered function: its parameter slots + body, plus any [diagnostics] (one per [RNode.Unsupported]).
 *  When [receiverSlot] is set the function is a class member: the interpreter binds the receiver object to
 *  that slot before running the body, so `this`/implicit-member access reads it.
 *
 *  [returnsUnit] marks a function whose result is `Unit` (a block body with no declared return type, or one
 *  declared `: Unit`). The Compose bridge only makes a `Unit`-returning `@Composable` restartable (skippable);
 *  a value-returning composable must always re-run so its result is fresh, so this gates `$changed` skipping. */
data class ResolvedFunction(
    val name: String,
    val params: List<RParam>,
    val body: RNode,
    val diagnostics: List<LoweringDiagnostic>,
    val receiverSlot: SlotId? = null,
    val returnsUnit: Boolean = false,
) {
    val isComplete: Boolean get() = diagnostics.isEmpty()
}

/** What kind of source type a [ResolvedClass] is. */
enum class ClassFlavor { CLASS, INTERFACE, OBJECT, COMPANION, ENUM, ANNOTATION }

/** A primary-constructor parameter. [isProperty] is true for a `val`/`var` parameter (which is also a
 *  property, stored in the instance's field map); [default] is its lowered default-value expression, if any. */
data class RClassParam(
    val slot: SlotId,
    val name: String,
    val type: KotlinType?,
    val isProperty: Boolean,
    val mutable: Boolean,
    val default: RNode?,
)

/** An enum entry: its [name]/[ordinal] and the lowered arguments to the enum's constructor (e.g.
 *  `RED("#f00")`). [body] is a non-null anonymous-class body for an entry that overrides members. */
data class REnumEntry(val name: String, val ordinal: Int, val args: List<RArg>)

/** A primary-constructor superclass invocation (`class B(x: Int) : A(x)`): the superclass [fqn] and the
 *  lowered constructor [args] (which may reference the subclass's constructor params). The interpreter runs
 *  the super constructor on the same instance before the subclass's own initializers. */
data class SuperCall(val fqn: String, val args: List<RArg>)

/**
 * A project-source class/object/enum lowered for interpretation — the type-level analogue of
 * [ResolvedFunction]. Source types aren't compiled at preview/run time, so the interpreter materializes them
 * as `SourceObject`s and runs their members from this model rather than reflecting bytecode.
 *
 * Construction runs [primaryParams] (bound, and the `val`/`var` ones stored as fields) then [initSteps] in
 * source order (each a [RNode.PropertySet] for a body-property initializer or an `init { }` block body), with
 * the receiver bound to [receiverSlot]. [methods] are member functions (keyed `"name/arity"`); the
 * data-class members (`copy`/`componentN`/`equals`/`hashCode`/`toString`) are synthesized by the interpreter
 * from [componentNames] rather than appearing here.
 */
data class ResolvedClass(
    val fqn: String,
    val simpleName: String,
    val flavor: ClassFlavor,
    val isData: Boolean,
    val isSealed: Boolean,
    val isAbstract: Boolean,
    val primaryParams: List<RClassParam>,
    val initSteps: List<RNode>,
    val methods: Map<String, ResolvedFunction>,
    val receiverSlot: SlotId,
    /** Direct supertype FQNs (for `is` checks, inherited-member lookup, and override resolution). */
    val supertypes: List<String>,
    /** The primary-constructor superclass invocation, if any — run on the same instance before this class's
     *  own initializers (so inherited properties are populated). Null for an `object`/enum/interface or a
     *  class with no explicit super constructor. */
    val superCall: SuperCall? = null,
    /** Enum entries in declaration order (empty for non-enums). */
    val enumEntries: List<REnumEntry>,
    val diagnostics: List<LoweringDiagnostic>,
) {
    /** The data-class component property names, in constructor order. */
    val componentNames: List<String> get() = primaryParams.filter { it.isProperty }.map { it.name }
    val isComplete: Boolean get() = diagnostics.isEmpty() && methods.values.all { it.isComplete }
}

/** Where lowering had to give up, and why. */
data class LoweringDiagnostic(val reason: String, val source: SourceSpan)

/** Walks a resolved node tree (pre-order). */
fun RNode.walk(visit: (RNode) -> Unit) {
    visit(this)
    children().forEach { it.walk(visit) }
}

/** Direct child nodes, for traversal. */
fun RNode.children(): List<RNode> = when (this) {
    is RNode.Call -> listOfNotNull(dispatchReceiver, receiver) + args.map { it.value }
    is RNode.PropertyGet -> listOfNotNull(receiver)
    is RNode.PropertySet -> listOfNotNull(receiver) + value
    is RNode.If -> listOfNotNull(condition, then, otherwise)
    is RNode.Block -> statements
    is RNode.Lambda -> listOf(body)
    is RNode.StringConcat -> parts
    is RNode.NotNull -> listOf(value)
    is RNode.TypeCheck -> listOf(value)
    is RNode.Try -> listOf(body) + catches.map { it.body } + listOfNotNull(finallyBlock)
    is RNode.LocalVar -> listOfNotNull(initializer)
    is RNode.Assign -> listOf(target, value)
    is RNode.Return -> listOfNotNull(value)
    is RNode.Throw -> listOf(value)
    is RNode.While -> listOf(condition, body)
    is RNode.ForEach -> listOf(iterable, body)
    is RNode.Const, is RNode.Name, is RNode.This, is RNode.Unsupported -> emptyList()
}
