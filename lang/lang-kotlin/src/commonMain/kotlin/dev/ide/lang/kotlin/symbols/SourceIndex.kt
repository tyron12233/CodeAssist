package dev.ide.lang.kotlin.symbols

import dev.ide.kotlin.syntax.psi.KtAnnotated
import dev.ide.kotlin.syntax.psi.KtAnnotationEntry
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtEnumEntry
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtModifierListOwner
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtSuperTypeCallEntry
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeAlias
import dev.ide.kotlin.syntax.psi.RelativeElement
import dev.ide.kotlin.syntax.psi.Variance
import dev.ide.kotlin.syntax.psi.collectDescendantsOfType
import dev.ide.kotlin.syntax.psi.containingClassOrObject
import dev.ide.kotlin.syntax.psi.elementAt
import dev.ide.kotlin.syntax.psi.getParentOfType
import dev.ide.kotlin.syntax.psi.hasElementAt
import dev.ide.kotlin.syntax.psi.relativeTo
import dev.ide.lang.dom.DomNode
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.parse.TextDiff
import dev.ide.platform.Lock
import dev.ide.vfs.VirtualFile
import kotlin.concurrent.Volatile

/**
 * The project-source declaration index. Every `.kt` in the module is parsed to PSI (no resolution) and its
 * declarations captured as raw, type-resolution-free data: top-level functions/properties/classes, class
 * members, constructors, imports, and (since this is source) extension functions as first-class. Type
 * references are kept as TEXT here; the [KotlinSymbolService] resolves them to FQNs lazily once the whole
 * module is known (avoiding a build-order cycle).
 */

data class ImportInfo(val fqn: String, val alias: String?, val isStar: Boolean) {
    // Stored, not computed per read: resolution scans a file's imports by simple name for every type name and
    // alias probe, and recomputing the substring each time was the analyzer's single hottest frame (a third of
    // a diagnostics pass's CPU, measured with JFR over this module's own sources).
    /** The last segment of [fqn]: the member a non-star import names, whatever alias it is bound to. */
    val lastSegment: String = fqn.substringAfterLast('.')
    val simpleName: String = alias ?: lastSegment
    val packageName: String = if (isStar) fqn else fqn.substringBeforeLast('.', "")
    /** What the import reaches INTO: the star import's own target, else everything before the last dot. */
    val container: String get() = packageName
    /** Whether [container] looks like a class (its last segment capitalized), the cheap gate the
     *  import-through-an-object lookups apply per import on every resolution. */
    val containerCapitalized: Boolean = packageName.substringAfterLast('.').firstOrNull()?.isUpperCase() == true
}

/** Per-file resolution context: the package + imports a type-text is resolved against. Shared by its decls. */
class FileContext(val path: String, val packageName: String, val imports: List<ImportInfo>) {
    /**
     * A stable VALUE key for the name-resolution scope this context provides (its package + import surface) —
     * everything [dev.ide.lang.kotlin.symbols.KotlinSymbolService.resolveTypeName] reads off a `FileContext`.
     * Computed once per instance, so keying that memo by it costs one field read per lookup, and two contexts
     * built over the same snapshot by DIFFERENT passes (each editor pass constructs its own `KotlinResolver`,
     * hence its own `FileContext`) produce the SAME key and share the memo instead of each re-resolving every
     * name in the file. The path is excluded on purpose: it names the file, not the scope.
     */
    /** The explicit (non-star) imports by the simple name they bind, the first one winning as in a scan. */
    val explicitImportsBySimpleName: Map<String, ImportInfo> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val m = HashMap<String, ImportInfo>()
        for (i in imports) if (!i.isStar) m.getOrPut(i.simpleName) { i }
        m
    }

    /** The packages this file star-imports. */
    val starImportPackages: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        imports.filter { it.isStar }.mapTo(HashSet()) { it.fqn }
    }

    val scopeKey: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        buildString {
            append(packageName)
            for (i in imports) {
                append('\u0000').append(i.fqn)
                i.alias?.let { append(' ').append(it) }
                if (i.isStar) append('*')
            }
        }
    }
}

/**
 * The declared type text of a `vararg x: E` parameter as it is seen from INSIDE the body / as a `val` property
 * — i.e. the ARRAY, not the element `E`. A non-null primitive `E` maps to its specialized array (`Int` ->
 * `IntArray`, …); any other `E` (incl. a nullable primitive like `Int?`) boxes into `Array<E>`. Returns null
 * only when [elementText] is null.
 */
fun varargArrayText(elementText: String?): String? = when (val e = elementText?.trim()) {
    null -> null
    "Int" -> "IntArray"
    "Long" -> "LongArray"
    "Short" -> "ShortArray"
    "Byte" -> "ByteArray"
    "Char" -> "CharArray"
    "Boolean" -> "BooleanArray"
    "Float" -> "FloatArray"
    "Double" -> "DoubleArray"
    else -> "Array<$e>"
}

class RawCallable(
    val name: String,
    val isFunction: Boolean,
    /** Extension receiver type text, e.g. "String" / "List<T>"; null ⇒ not an extension. */
    val receiverText: String?,
    /** Declared return (function) / declared type (property) text; null ⇒ inferred. */
    val returnText: String?,
    /** `= expr` body / property initializer text, for inferring an absent declared type. */
    val initializerText: String?,
    val paramTexts: List<Pair<String, String?>>,
    val ctx: FileContext,
    node: DomNode?,
    /** `"private"` / `"protected"` / `"internal"` / null (public). */
    val visibility: String? = null,
    /** A `@Composable`-annotated function (detected by annotation simple name — no need to resolve the type). */
    val isComposable: Boolean = false,
    /** An `inline` function. */
    val isInline: Boolean = false,
    /** An `infix` function — completed in the infix-operator slot (`a foo b`). */
    val isInfix: Boolean = false,
    /** A `suspend` function. */
    val isSuspend: Boolean = false,
    /** A `@Deprecated` declaration (detected by annotation simple name) — for strikethrough highlighting. */
    val isDeprecated: Boolean = false,
    /** An ABSTRACT member: an explicit `abstract` modifier, or an interface member with no body — so a concrete
     *  subtype must override it. An interface member WITH a default body is NOT abstract. */
    val isAbstract: Boolean = false,
    /** The index of the `vararg` value parameter, or -1 if none. */
    val varargParamIndex: Int = -1,
    /** The function's own type-parameter names (`fun <T> items(…)` → `["T"]`) — so a param/return type that
     *  references one is marked a type parameter (enabling generic inference: binding `T` from an argument). */
    val typeParameterNames: List<String> = emptyList(),
    /** Each type parameter's declared upper-bound TEXT (positional with [typeParameterNames]); "" if unbounded.
     *  Drives the explicit-type-argument bound check and erasing an unbound parameter to its bound. */
    val typeParameterBounds: List<String> = emptyList(),
    /** Whether each value parameter declares a default (positional with [paramTexts]) — so a required-argument
     *  check can tell which the call may omit. Empty ⇒ not a function / unknown. */
    val paramHasDefault: List<Boolean> = emptyList(),
    /** A mutable (`var`) property — gets a `set<Name>` accessor in the Java facade as well as `get<Name>`. */
    val isVar: Boolean = false,
    /** `@JvmStatic` — in an `object`/companion, also exposed to Java as a `static` member of the (enclosing) class. */
    val jvmStatic: Boolean = false,
    /** `@JvmField` — a property exposed to Java as a public field instead of `get`/`set` accessors. */
    val jvmField: Boolean = false,
    /** `@JvmOverloads` — generate Java overloads dropping trailing defaulted parameters (uses [paramHasDefault]). */
    val jvmOverloads: Boolean = false,
    /** `@JvmName("x")` value — the Java-visible name override for a function (null ⇒ use [name]). */
    val jvmName: String? = null,
    /** FQNs of the annotations on this declaration, resolved against the declaring file's imports (best-effort;
     *  an unresolvable star-imported name is dropped — a miss, never a wrong FQN). Drives the opt-in usage check
     *  (which of these are `@RequiresOptIn` markers is decided at check time by [KotlinSymbolService]). */
    val annotationFqns: List<String> = emptyList(),
    /** A `const val`: a compile-time constant, which Java sees as a static field rather than a getter. */
    val isConst: Boolean = false,
    /** A `lateinit var`: Java sees its backing field as well as its accessors. */
    val isLateinit: Boolean = false,
) {
    // The declaration's node, or a [CarriedNode] that finds it in the current tree on first read.
    @Volatile
    internal var nodeSource: Any? = node

    val node: DomNode? get() = resolveNode(nodeSource) { nodeSource = it }
}

class RawClass(
    val fqn: String,
    val simpleName: String,
    val isObject: Boolean,
    val superTypeTexts: List<String>,
    val members: List<RawCallable>,
    val constructors: List<RawCallable>,
    val ctx: FileContext,
    node: DomNode?,
    /** The class's own type-parameter names (`class Box<T>` → `["T"]`) — so a member type that references one
     *  (`val value: T`) is marked a type parameter and binds from the receiver's type arguments. */
    val typeParameterNames: List<String> = emptyList(),
    /** Each type parameter's declared upper-bound TEXT (positional with [typeParameterNames]), from the inline
     *  `<T : Bound>` form or a `where T : Bound` clause; "" when unbounded. Drives the type-argument bound check. */
    val typeParameterBounds: List<String> = emptyList(),
    /** Each type parameter's declaration-site variance (positional with [typeParameterNames]): `"out"`,
     *  `"in"`, or `""` for invariant. Drives the variance-conflict and use-site-projection checks. */
    val typeParameterVariance: List<String> = emptyList(),
    /** Entry names when this is an `enum class` (`RED`, `GREEN`); empty otherwise. */
    val enumEntries: List<String> = emptyList(),
    /** The companion object's simple name (`"Companion"` by default), or null if none. A bare `Type.`
     *  reference resolves to the companion instance, so its members + applicable extensions are in scope
     *  (Compose's `Color.Black`, `Modifier.padding`). The companion is registered as its own [RawClass]. */
    val companionObjectName: String? = null,
    /** True when this RawClass IS a companion object — kept out of type-name completion (a `Companion`
     *  suggestion is noise) while still resolvable as `Outer.Companion` for member lookup. */
    val isCompanion: Boolean = false,
    /** Declaration kind, so the Java facade renders the right form (`interface`/`enum`/`@interface`). An
     *  `object` is signalled by [isObject]; a plain class has all three false. */
    val isInterface: Boolean = false,
    val isEnum: Boolean = false,
    val isAnnotation: Boolean = false,
    /** True for an `abstract` or `sealed` class — it cannot be instantiated directly (the abstract-instantiation
     *  check). An interface is signalled by [isInterface]; both block `Type()`. */
    val isAbstract: Boolean = false,
    /** True for a `sealed` class/interface — its subclasses are exhaustively enumerable (same-module), driving
     *  the cross-file `when`-exhaustiveness check. */
    val isSealed: Boolean = false,
    /** True for a plain FINAL class — a concrete class that is not `open`/`abstract`/`sealed` and is not an
     *  interface/enum/annotation/object. Such a class cannot be extended (Kotlin classes are final by default),
     *  driving the final-supertype check. An `object` (which also can't be extended) is signalled by [isObject]. */
    val isFinalClass: Boolean = false,
    /** True for an `expect class`: a declaration with no bodies, whose `actual` in a platform source set is the one
     *  to resolve members against and to run. [ModuleSourceModel.classByFqn] never lets it displace an `actual`,
     *  whatever order the files were listed in. */
    val isExpect: Boolean = false,
    /** True for a LOCAL type: an anonymous object (`object : T { }` / `object { }`) or a class/object declared
     *  inside a function/initializer body. Its [fqn] is a synthetic, deterministic key ([SourceIndexBuilder.
     *  localTypeFqn]) that the resolver recomputes from the same PSI, so member enumeration / diagnostics flow
     *  through the normal FQN machinery. Kept OUT of type-name completion (no one references it by that name). */
    val isLocal: Boolean = false,
    /** True when the declaration carries the `private` modifier. A `private` TOP-LEVEL class is FILE-private —
     *  visible only within its own declaring file — so it must not be offered as a cross-file auto-import
     *  candidate (the `androidx` `updateTransition` sample's `private enum class BoxState` was surfacing in
     *  other files in the same package, labelled "same package", with no usable import). Compared against the
     *  completion's current file ([ctx].path) to keep same-file references working. */
    val isPrivate: Boolean = false,
    /** Simple names of the annotations on the class declaration (`@Serializable class Foo` → `["Serializable"]`),
     *  detected by annotation simple name the same way [RawCallable.isComposable] is — no type resolution. Lets a
     *  [KotlinSyntheticMemberProvider] recognize its marker annotation (kotlinx.serialization's `@Serializable`,
     *  Parcelize's `@Parcelize`) off the source model to contribute the members that plugin would generate. */
    val annotationNames: List<String> = emptyList(),
    /** FQNs of the annotations on the class declaration, resolved against the declaring file's imports
     *  (best-effort). Drives the opt-in usage check for experimental TYPES (`@ExperimentalFoo class Bar`). */
    val annotationFqns: List<String> = emptyList(),
    /** When THIS class is a `@RequiresOptIn` marker annotation, its declared level (`"ERROR"` / `"WARNING"`,
     *  default `"ERROR"`); null when it is not a marker. Lets a source-defined opt-in marker be recognized. */
    val optInLevel: String? = null,
    /** The supertype written with a constructor call (`class A : Base()`), i.e. the superclass, as its type
     *  text; null when the class extends no class (only interfaces, or nothing). */
    val superClassText: String? = null,
) {
    // The declaration's node, or a [CarriedNode] that finds it in the current tree on first read.
    @Volatile
    internal var nodeSource: Any? = node

    val node: DomNode? get() = resolveNode(nodeSource) { nodeSource = it }
}

/**
 * A declaration node carried over from the previous build of the file: the declaration that holds it in the
 * current tree, and where it sits under that declaration. A rebuild carries hundreds of declarations per
 * keystroke and their nodes are rarely read before the next one, so a node is found and wrapped only when
 * something asks for it. Holds no part of an older tree, and [rel] is computed once and reused by every later
 * carry of the same declaration.
 */
internal class CarriedNode(val decl: KtDeclaration, val parsed: KotlinParsedFile, val rel: RelativeElement) {
    fun resolve(): DomNode? = decl.elementAt(rel)?.let { runCatching { parsed.adapt(it) }.getOrNull() }
}

private inline fun resolveNode(source: Any?, store: (DomNode?) -> Unit): DomNode? = when (source) {
    is CarriedNode -> source.resolve().also(store)
    else -> source as DomNode?
}

/** This callable in an identical declaration of a newer tree, its node given by [nodeSource] (a node or a
 *  [CarriedNode]), in [ctx]: every other field as it is. Any field added to [RawCallable] must be passed on
 *  here; `SourceDeclarationsEqualityTest` counts them. */
internal fun RawCallable.carried(nodeSource: Any?, ctx: FileContext) = RawCallable(
    name = name, isFunction = isFunction, receiverText = receiverText, returnText = returnText,
    initializerText = initializerText, paramTexts = paramTexts, ctx = ctx, node = null, visibility = visibility,
    isComposable = isComposable, isInline = isInline, isInfix = isInfix, isSuspend = isSuspend,
    isDeprecated = isDeprecated, isAbstract = isAbstract, varargParamIndex = varargParamIndex,
    typeParameterNames = typeParameterNames, typeParameterBounds = typeParameterBounds,
    paramHasDefault = paramHasDefault, isVar = isVar, jvmStatic = jvmStatic, jvmField = jvmField,
    jvmOverloads = jvmOverloads, jvmName = jvmName, annotationFqns = annotationFqns,
    isConst = isConst, isLateinit = isLateinit,
).also { it.nodeSource = nodeSource }

/** This class in an identical declaration of a newer tree, its node given by [nodeSource] (a node or a
 *  [CarriedNode]), in [ctx], with its members and constructors already carried: every other field as it is.
 *  Any field added to [RawClass] must be passed on here; `SourceDeclarationsEqualityTest` counts them. */
internal fun RawClass.carried(
    nodeSource: Any?,
    ctx: FileContext,
    members: List<RawCallable>,
    constructors: List<RawCallable>,
) = RawClass(
    fqn = fqn, simpleName = simpleName, isObject = isObject, superTypeTexts = superTypeTexts, members = members,
    constructors = constructors, ctx = ctx, node = null, typeParameterNames = typeParameterNames,
    typeParameterBounds = typeParameterBounds, typeParameterVariance = typeParameterVariance,
    enumEntries = enumEntries, companionObjectName = companionObjectName, isCompanion = isCompanion,
    isInterface = isInterface, isEnum = isEnum, isAnnotation = isAnnotation, isAbstract = isAbstract,
    isSealed = isSealed, isFinalClass = isFinalClass, isExpect = isExpect, isLocal = isLocal, isPrivate = isPrivate,
    annotationNames = annotationNames, annotationFqns = annotationFqns, optInLevel = optInLevel,
    superClassText = superClassText,
).also { it.nodeSource = nodeSource }

/** A source `typealias Name<…> = Target`: its FQN, the target type reference text, the number of its own type
 *  parameters, and the file context to resolve [targetText] in. Lets the resolver EXPAND a (non-generic) alias
 *  to its real type for member/extension resolution (`Board = List<Tile>` → `plan.settled.any { }` resolves). */
class TypeAliasDecl(
    val fqn: String, val targetText: String, val typeParamCount: Int, val ctx: FileContext,
    /** Declared inside a class (`class Outer { typealias TA = … }`): reached by simple name only from inside it. */
    val isNested: Boolean = false,
) {
    val simpleName: String get() = fqn.substringAfterLast('.')
}

class SourceFile(
    val ctx: FileContext,
    val topLevel: List<RawCallable>,
    val extensions: List<RawCallable>,
    val classes: List<RawClass>,
    /** `typealias` declarations in the file — type references to them must not be flagged unresolved (the model
     *  resolves classes, not aliases), and a non-generic one expands to its target for member resolution. */
    val typeAliases: List<TypeAliasDecl> = emptyList(),
    /** `@file:JvmName("X")`: the name of the file's Java facade class, in place of `<File>Kt`. */
    val jvmName: String? = null,
)

/**
 * Whether [a] and [b] declare exactly the same things: every field the symbol model reads from a declaration,
 * compared by value, leaving out only where each declaration sits in its tree ([RawCallable.node],
 * [RawClass.node]). A keystroke inside a function body produces a new [SourceFile] that is the same in this
 * sense, and the symbol service keeps what it resolved against the old one instead of starting cold.
 *
 * Compares fields one by one on purpose, not by hash: two different names can share a hash, and a false
 * "same" here serves a stale resolution. Any field added to [RawCallable], [RawClass] or [TypeAliasDecl] must
 * be added here too; `SourceDeclarationsEqualityTest` fails until it is.
 */
fun sameDeclarations(a: SourceFile, b: SourceFile): Boolean {
    if (a.ctx.path != b.ctx.path || a.ctx.packageName != b.ctx.packageName || a.ctx.imports != b.ctx.imports) return false
    if (a.jvmName != b.jvmName) return false
    return sameCallables(a.topLevel, b.topLevel) && sameCallables(a.extensions, b.extensions) &&
        sameList(a.classes, b.classes, ::sameClass) && sameList(a.typeAliases, b.typeAliases, ::sameAlias)
}

private inline fun <T> sameList(a: List<T>, b: List<T>, same: (T, T) -> Boolean): Boolean {
    if (a.size != b.size) return false
    for (i in a.indices) if (!same(a[i], b[i])) return false
    return true
}

private fun sameCallables(a: List<RawCallable>, b: List<RawCallable>): Boolean = sameList(a, b, ::sameCallable)

private fun sameCallable(a: RawCallable, b: RawCallable): Boolean =
    a.name == b.name && a.isFunction == b.isFunction && a.receiverText == b.receiverText &&
        a.returnText == b.returnText && a.initializerText == b.initializerText && a.paramTexts == b.paramTexts &&
        a.ctx.path == b.ctx.path && a.visibility == b.visibility && a.isComposable == b.isComposable &&
        a.isInline == b.isInline && a.isInfix == b.isInfix && a.isSuspend == b.isSuspend &&
        a.isDeprecated == b.isDeprecated && a.isAbstract == b.isAbstract && a.varargParamIndex == b.varargParamIndex &&
        a.typeParameterNames == b.typeParameterNames && a.typeParameterBounds == b.typeParameterBounds &&
        a.paramHasDefault == b.paramHasDefault && a.isVar == b.isVar && a.jvmStatic == b.jvmStatic &&
        a.jvmField == b.jvmField && a.jvmOverloads == b.jvmOverloads && a.jvmName == b.jvmName &&
        a.annotationFqns == b.annotationFqns && a.isConst == b.isConst && a.isLateinit == b.isLateinit

private fun sameClass(a: RawClass, b: RawClass): Boolean =
    a.fqn == b.fqn && a.simpleName == b.simpleName && a.isObject == b.isObject &&
        a.superTypeTexts == b.superTypeTexts && sameCallables(a.members, b.members) &&
        sameCallables(a.constructors, b.constructors) && a.ctx.path == b.ctx.path &&
        a.typeParameterNames == b.typeParameterNames && a.typeParameterBounds == b.typeParameterBounds &&
        a.typeParameterVariance == b.typeParameterVariance && a.enumEntries == b.enumEntries &&
        a.companionObjectName == b.companionObjectName && a.isCompanion == b.isCompanion &&
        a.isInterface == b.isInterface && a.isEnum == b.isEnum && a.isAnnotation == b.isAnnotation &&
        a.isAbstract == b.isAbstract && a.isSealed == b.isSealed && a.isFinalClass == b.isFinalClass && a.isExpect == b.isExpect &&
        a.isLocal == b.isLocal && a.isPrivate == b.isPrivate && a.annotationNames == b.annotationNames &&
        a.annotationFqns == b.annotationFqns && a.optInLevel == b.optInLevel && a.superClassText == b.superClassText

private fun sameAlias(a: TypeAliasDecl, b: TypeAliasDecl): Boolean =
    a.fqn == b.fqn && a.targetText == b.targetText && a.typeParamCount == b.typeParamCount && a.ctx.path == b.ctx.path &&
        a.isNested == b.isNested

class ModuleSourceModel(val files: List<SourceFile>) {
    /** A classifier one FILE declares twice under one name (CLASSIFIER_REDECLARATION) resolves to its first
     *  declaration there, as K2 does: `sealed interface SE` then `abstract class SE` leaves `: SE` naming the
     *  interface. Across files the later file's declaration is kept, as before, except that an `expect class` never
     *  replaces its `actual` (nor is kept over one): which of the two a directory listing yields last is an accident
     *  of the file system (sorted on APFS, hash order on ext4), and only the `actual` has members with bodies. */
    val classByFqn: Map<String, RawClass> = LinkedHashMap<String, RawClass>().also { m ->
        for (f in files) {
            val seen = HashSet<String>()
            for (c in f.classes) {
                if (!seen.add(c.fqn)) continue
                val kept = m[c.fqn]
                if (c.isExpect && kept != null && !kept.isExpect) continue
                m[c.fqn] = c
            }
        }
    }
    val topLevel: List<RawCallable> = files.flatMap { it.topLevel }
    val extensions: List<RawCallable> = files.flatMap { it.extensions }
    val typeAliasNames: Set<String> = files.flatMapTo(HashSet()) { f -> f.typeAliases.filter { !it.isNested }.map { it.simpleName } }

    /**
     * The NAMED local classes, keyed by `<owner>#<simple name>`, where `<owner>` is the prefix of their positional
     * `$L` key (the named declaration they are local to). Lets a local class's supertype name another local class
     * of the same owner (`class DerivedLocal : BaseLocal()` in one function body), which no FQN can spell.
     */
    /** The names of the member extension PROPERTIES classes declare (`val String.x` in a class body): the cheap gate
     *  before a bare name is looked up as one. */
    val memberExtensionPropertyNames: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        classByFqn.values.flatMapTo(HashSet()) { rc -> rc.members.filter { !it.isFunction && it.receiverText != null }.map { it.name } }
    }

    val localClassesByOwnerAndName: Map<String, String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val out = HashMap<String, String>()
        for (rc in classByFqn.values) {
            if (!rc.isLocal || rc.simpleName.isEmpty()) continue
            val at = rc.fqn.lastIndexOf(".\$L")
            if (at < 0 || rc.fqn.indexOf('.', at + 1) >= 0) continue
            out.getOrPut(rc.fqn.substring(0, at) + "#" + rc.simpleName) { rc.fqn }
        }
        out
    }

    /**
     * Every member extension declared in a SINGLETON container, an `object` (incl. a nested one) or a
     * `companion object`, paired with that container. This is the ONLY member-extension shape an `import`
     * can bring into scope (a regular class's needs a dispatch receiver), so it is what completion offers
     * with an auto-import: `object StringUtils { fun String.twice() … }` → `import util.StringUtils.twice`.
     * Private containers/members are excluded (not importable from another file), as are local objects.
     * Computed once per model, since an edit rebuilds the model.
     */
    val singletonExtensions: List<Pair<RawClass, RawCallable>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        files.flatMap { it.classes }
            .filter { it.isObject && !it.isLocal && !it.isPrivate }
            .flatMap { rc ->
                rc.members.filter { it.receiverText != null && it.visibility != "private" }.map { rc to it }
            }
    }

    /**
     * The SIMPLE names of every NESTED class in the model — one whose owner FQN is itself a known class.
     * Guards the enclosing-chain step of `KotlinSymbolService.resolveTypeName`: that step sits ahead of the
     * file-level steps (Kotlin scopes a classifier innermost-out), so it runs on EVERY simple-name
     * resolution, and resolution is the hottest path in completion and diagnostics. One set probe rules out
     * the overwhelming majority of names before any string concatenation or map lookup.
     */
    val nestedSimpleNames: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val all = classByFqn
        all.keys.mapNotNullTo(HashSet()) { fqn ->
            val owner = fqn.substringBeforeLast('.', "")
            if (owner.isNotEmpty() && owner in all) fqn.substringAfterLast('.') else null
        }
    }

    /** Project typealiases keyed by SIMPLE name, for alias expansion. A cross-package simple-name collision keeps
     *  the first — project aliases are effectively unique by simple name in practice. */
    val typeAliasBySimpleName: Map<String, TypeAliasDecl> = files.flatMap { it.typeAliases }.filter { !it.isNested }.associateBy { it.simpleName }

    /** Every project typealias of a simple name (several packages may each declare one), and each by its FQN,
     *  for deciding which one, if any, a file actually sees. */
    val typeAliasesBySimpleName: Map<String, List<TypeAliasDecl>> = files.flatMap { it.typeAliases }.filter { !it.isNested }.groupBy { it.simpleName }
    val typeAliasByFqn: Map<String, TypeAliasDecl> = files.flatMap { it.typeAliases }.associateBy { it.fqn }

    companion object {
        val EMPTY = ModuleSourceModel(emptyList())
    }
}

object SourceIndexBuilder {

    fun build(sourceRoots: List<VirtualFile>, currentOverlay: Map<String, String> = emptyMap()): ModuleSourceModel {
        val files = ArrayList<SourceFile>()
        val seen = HashSet<String>()
        for (root in sourceRoots) walk(root) { vf ->
            if (vf.name.endsWith(".kt") && seen.add(vf.path)) {
                val text = currentOverlay[vf.path] ?: runCatching { vf.readText().toString() }.getOrNull()
                if (text != null) extract(vf, text)?.let(files::add)
            }
        }
        return ModuleSourceModel(files)
    }

    fun extract(vf: VirtualFile, text: String): SourceFile? {
        val kt = runCatching { KotlinParserHost.parse(vf.name, text) }.getOrNull() ?: return null
        val parsed = KotlinParsedFile(kt, vf, 0)
        return extractFrom(kt, parsed, vf.path)
    }

    fun extractFrom(kt: KtFile, parsed: KotlinParsedFile, path: String): SourceFile {
        val pkg = kt.packageFqName.asString().replace("`", "")
        val imports = kt.importDirectives.mapNotNull { imp ->
            val fq = imp.importedFqName?.asString()?.replace("`", "") ?: return@mapNotNull null
            ImportInfo(fq, imp.aliasName, imp.isAllUnder)
        }
        val text = kt.text
        val decls = kt.declarations
        lastCarriedCount = 0

        // The editor rebuilds the focal file on every keystroke, and a keystroke almost always leaves every
        // top-level declaration but one as it was. Those are carried over from the last build of this file
        // instead of being read again, which on a long file is most of the work.
        val previous = lastExtractsLock.withLock { lastExtracts[path] }?.takeIf { it.pkg == pkg && it.imports == imports }
        // The same scope as last time keeps its context, and with it the scope key the symbol service's memos use.
        val ctx = previous?.ctx ?: FileContext(path, pkg, imports)
        val last = previous?.takeIf { it.decls.size == decls.size }
        val edit = last?.let { TextDiff.between(it.text, text) }
        val entries = ArrayList<DeclEntries>(decls.size)
        for (i in decls.indices) {
            val decl = decls[i]
            val carried = if (last != null && edit != null) {
                carriedOver(last.decls[i], last.entries[i], decl, edit, ctx, parsed)
            } else null
            entries += carried ?: entriesOf(decl, pkg, ctx, parsed)
        }
        lastExtractsLock.withLock {
            lastExtracts.remove(path)
            lastExtracts[path] = LastExtract(text, pkg, imports, ctx, decls, entries)
            while (lastExtracts.size > MAX_LAST_EXTRACTS) lastExtracts.remove(lastExtracts.keys.first())
        }

        val topLevel = ArrayList<RawCallable>()
        val extensions = ArrayList<RawCallable>()
        val classes = ArrayList<RawClass>()
        val typeAliases = ArrayList<TypeAliasDecl>()
        for (e in entries) {
            topLevel += e.topLevel
            extensions += e.extensions
            classes += e.classes
            typeAliases += e.typeAliases
        }
        // Local + anonymous types (`object : T { }`, `object { }`, a `class`/`object` declared in a body) live
        // inside expression/statement positions the top-level walk above never enters. Capture each under a
        // synthetic FQN so its members enumerate + are checked through the normal machinery; the resolver
        // recomputes the SAME FQN from the same PSI (see [localTypeFqn]).
        classes += collectLocalTypes(kt, ctx, parsed)
        return SourceFile(ctx, topLevel, extensions, classes, typeAliases, jvmName = annoArg(kt, "JvmName"))
    }

    /** What one top-level declaration contributes to its [SourceFile]. */
    private class DeclEntries(
        val topLevel: List<RawCallable> = emptyList(),
        val extensions: List<RawCallable> = emptyList(),
        val classes: List<RawClass> = emptyList(),
        val typeAliases: List<TypeAliasDecl> = emptyList(),
    )

    private val NO_ENTRIES = DeclEntries()

    private fun entriesOf(decl: KtDeclaration, pkg: String, ctx: FileContext, parsed: KotlinParsedFile): DeclEntries =
        when (decl) {
            is KtNamedFunction -> callable(decl, ctx, parsed).let {
                if (it.receiverText != null) DeclEntries(extensions = listOf(it)) else DeclEntries(topLevel = listOf(it))
            }
            is KtProperty -> property(decl, ctx, parsed).let {
                if (it.receiverText != null) DeclEntries(extensions = listOf(it)) else DeclEntries(topLevel = listOf(it))
            }
            is KtClassOrObject -> DeclEntries(classes = collectClasses(decl, ctx, parsed), typeAliases = nestedTypeAliases(decl, ctx))
            is KtTypeAlias -> decl.name?.let { name ->
                DeclEntries(
                    typeAliases = listOf(
                        TypeAliasDecl(
                            fqn = if (pkg.isEmpty()) name else "$pkg.$name",
                            targetText = decl.typeReference?.text ?: "",
                            typeParamCount = decl.typeParameters.size,
                            ctx = ctx,
                        )
                    )
                )
            } ?: NO_ENTRIES
            else -> NO_ENTRIES
        }

    /** The typealiases declared INSIDE [cls] and its named nested classes (Kotlin 2.3's nested type aliases,
     *  `class Outer { typealias TA = Inner }`), each under its owner's FQN: `Outer.TA`. */
    private fun nestedTypeAliases(cls: KtClassOrObject, ctx: FileContext): List<TypeAliasDecl> {
        val owner = cls.fqName?.asString() ?: return emptyList()
        val out = ArrayList<TypeAliasDecl>()
        for (d in cls.declarations) when (d) {
            is KtTypeAlias -> d.name?.let { name ->
                out += TypeAliasDecl("$owner.$name", d.typeReference?.text ?: "", d.typeParameters.size, ctx, isNested = true)
            }
            is KtClassOrObject -> out += nestedTypeAliases(d, ctx)
            else -> {}
        }
        return out
    }

    /** A file's last [extractFrom] build: its text, header, and what each top-level declaration contributed. */
    private class LastExtract(
        val text: String,
        val pkg: String,
        val imports: List<ImportInfo>,
        val ctx: FileContext,
        val decls: List<KtDeclaration>,
        val entries: List<DeclEntries>,
    )

    // The last build of each of the few most recently built files, oldest first. More than one, because a
    // completion first brings the other open buffers up to date, and each of those builds would otherwise push
    // the focal file out between two of its keystrokes.
    private val lastExtracts = LinkedHashMap<String, LastExtract>()
    private val lastExtractsLock = Lock()

    private const val MAX_LAST_EXTRACTS = 4

    /** Forget the remembered builds (memory pressure); the next build of each file reads it in full. */
    fun forgetLastBuilds() = lastExtractsLock.withLock { lastExtracts.clear() }

    /** How many declarations the last [extractFrom] carried over rather than read; for tests. */
    @Volatile
    internal var lastCarriedCount = 0
        private set

    /**
     * [oldEntries], the contribution of [oldDecl] in the last build, moved onto [newDecl] when the edit left the
     * declaration's text and position-relative shape alone: every node re-pointed at the same element of the new
     * tree, and every entry given the new file context. Null when the declaration must be read again, which
     * includes any node that does not carry over exactly.
     */
    private fun carriedOver(
        oldDecl: KtDeclaration,
        oldEntries: DeclEntries,
        newDecl: KtDeclaration,
        edit: TextDiff,
        ctx: FileContext,
        parsed: KotlinParsedFile,
    ): DeclEntries? {
        if (oldDecl::class != newDecl::class) return null
        val newStart = newDecl.textRange.startOffset
        val newEnd = newDecl.textRange.endOffset
        val oldStart = edit.oldStartOf(newStart, newEnd) ?: return null // the edit is inside this declaration
        if (oldDecl.textRange.startOffset != oldStart || oldDecl.textRange.length != newEnd - newStart) return null

        // Each node is checked against the new tree now (a mismatch reads the declaration again, as before) but
        // only wrapped when something asks for it; see [CarriedNode]. Reads the old entries' node SOURCES, so an
        // old node no one asked for stays unresolved.
        var failed = false
        fun move(source: Any?): Any? {
            if (source == null || failed) return null
            val rel = when (source) {
                is CarriedNode -> source.rel
                is KotlinDomNode -> source.psi.relativeTo(oldDecl)
                else -> null
            }
            if (rel == null || !newDecl.hasElementAt(rel)) {
                failed = true
                return null
            }
            return CarriedNode(newDecl, parsed, rel)
        }
        fun RawCallable.moved() = carried(move(nodeSource), ctx)
        fun TypeAliasDecl.moved() = if (this.ctx === ctx) this else TypeAliasDecl(fqn, targetText, typeParamCount, ctx, isNested)
        val entries = DeclEntries(
            topLevel = oldEntries.topLevel.map { it.moved() },
            extensions = oldEntries.extensions.map { it.moved() },
            classes = oldEntries.classes.map { c ->
                c.carried(move(c.nodeSource), ctx, c.members.map { it.moved() }, c.constructors.map { it.moved() })
            },
            typeAliases = oldEntries.typeAliases.map { it.moved() },
        )
        if (failed) return null
        lastCarriedCount++
        return entries
    }

    /** Every local/anonymous [KtClassOrObject] in [kt] (has no reachable `fqName`, and isn't an enum entry) —
     *  an anonymous object's `object`-declaration, or a class/object declared inside a body. Pre-order
     *  (document order), so an ordinal over this list is a stable, marker-splice-invariant key ([localTypeFqn]). */
    private fun localTypeDecls(kt: KtFile): List<KtClassOrObject> {
        localTypesMemo?.let { if (it.file === kt) return it.decls }
        val keywords = typeKeywordOffsets(kt.text)
        val out = ArrayList<KtClassOrObject>()
        // Pre-order like `collectDescendantsOfType`, but an element whose text names neither `class` nor
        // `object` cannot declare a type, so it is not entered. That prunes almost the whole tree: a named
        // class's own keyword sits in its header, so its parameter lists, members and bodies are skipped
        // unless one of them names a type too. In a lazily parsed file it also keeps this walk from parsing
        // every body: it reads the element's range, not its tree.
        fun visit(e: KtElement) {
            if (!keywords.anyIn(e.textRange.startOffset, e.textRange.endOffset)) return
            if (e is KtClassOrObject && e !is KtEnumEntry && !e.hasQualifiedName) out += e
            for (c in e.children) visit(c)
        }
        visit(kt)
        localTypesMemo = LocalTypes(kt, out)
        return out
    }

    /** The last file's local types: [localTypeFqn] asks once per local type, and the resolver per reference. */
    private class LocalTypes(val file: KtFile, val decls: List<KtClassOrObject>)

    @Volatile
    private var localTypesMemo: LocalTypes? = null

    /** The ascending offsets at which `class` or `object` occurs in [src]: a cheap over-approximation of where
     *  a type may be declared (it also matches `subclass`, comments and strings, which only prunes less). */
    private fun typeKeywordOffsets(src: String): IntArray {
        var out = IntArray(16)
        var n = 0
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if ((c == 'c' && src.startsWith("class", i)) || (c == 'o' && src.startsWith("object", i))) {
                if (n == out.size) out = out.copyOf(n * 2)
                out[n++] = i
            }
            i++
        }
        return out.copyOf(n)
    }

    /** Whether any offset in this ascending array lies in `[start, end)`. */
    private fun IntArray.anyIn(start: Int, end: Int): Boolean {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (this[mid] < start) lo = mid + 1 else hi = mid
        }
        return lo < size && this[lo] < end
    }

    /** The synthetic, deterministic FQN a local/anonymous type [decl] is registered under. Independent of
     *  absolute offsets (which the completion marker shifts): it is the nearest NAMED enclosing type's FQN (or
     *  the file facade when top-level) plus the decl's ordinal among the local types sharing that owner. Both
     *  this builder and the resolver call it over the same PSI, so the keys match. */
    fun localTypeFqn(decl: KtClassOrObject): String {
        // A type declared INSIDE a local/anonymous one keys by its own simple name under that outer, exactly as
        // a nested type of a named class does. Only a type with no name (an anonymous `object`) or one declared
        // straight in a code block needs the positional `$L` key.
        //
        // Without this they were registered as SIBLINGS of their outer under the file facade, so nothing could
        // address them by the name the code uses: `companionObjectFqn` builds "<outer>.Companion" and a nested
        // type is probed as "<outer>.N", and both missed. That is why every `L.CONST` / `L.make()` / `L.N` on a
        // LOCAL class read as an unresolved reference while the same code on a top-level class was fine.
        val outer = decl.getParentOfType<KtClassOrObject>(strict = true)
        if (outer != null && outer.fqName == null) {
            // A companion has no name of its own but is addressed as `Companion`, the same name [rawClass]
            // records in `companionObjectName`.
            val simple = decl.name
                ?: (decl as? KtObjectDeclaration)
                    ?.takeIf { it.isCompanion() }?.let { "Companion" }
            if (simple != null) return "${localTypeFqn(outer)}.$simple"
        }
        val owner = enclosingNamedOwnerFqn(decl)
        val ordinal = localTypeDecls(decl.containingKtFile)
            .filter { enclosingNamedOwnerFqn(it) == owner }
            .indexOfFirst { it === decl }.coerceAtLeast(0)
        return "$owner.\$L$ordinal"
    }

    /** The FQN of the nearest enclosing type that HAS one (a normally-named class/object), or the Kotlin file
     *  facade (`pkg.FileKt`) when [decl] sits at top level of a file — the owner an ordinal is scoped to. */
    private fun enclosingNamedOwnerFqn(decl: KtClassOrObject): String {
        var p: KtElement? = decl.parent
        while (p != null) {
            if (p is KtClassOrObject) p.fqName?.asString()?.let { return it }
            p = p.parent
        }
        val file = decl.containingKtFile
        val pkg = file.packageFqName.asString().replace("`", "")
        val facade = file.name.removeSuffix(".kt").replaceFirstChar { it.uppercase() } + "Kt"
        return if (pkg.isEmpty()) facade else "$pkg.$facade"
    }

    private fun collectLocalTypes(kt: KtFile, ctx: FileContext, parsed: KotlinParsedFile): List<RawClass> =
        localTypeDecls(kt).mapNotNull { rawClass(it, ctx, parsed, fqnOverride = localTypeFqn(it), isLocal = true) }

    private fun rawClass(
        c: KtClassOrObject,
        ctx: FileContext,
        parsed: KotlinParsedFile,
        /** For a local/anonymous type (no reachable `fqName`): the synthetic key to register it under. */
        fqnOverride: String? = null,
        isLocal: Boolean = false,
    ): RawClass? {
        val fqn = fqnOverride ?: c.fqName?.asString() ?: return null
        val supers = c.superTypeListEntries.mapNotNull { it.typeReference?.text }
        val members = ArrayList<RawCallable>()
        val ctors = ArrayList<RawCallable>()
        // Primary-constructor `val/var` params are member properties. A `vararg val` property's type is the
        // array (`vararg val xs: Int` -> `IntArray`), not the element, so `c.xs.sum()` resolves.
        c.primaryConstructorParameters.filter { it.hasValOrVar() }.forEach { p ->
            val typeText = if (p.isVarArg) varargArrayText(p.typeReference?.text) else p.typeReference?.text
            members += RawCallable(p.name ?: "_", false, null, typeText, null, emptyList(), ctx, node(parsed, p), visOf(p),
                isVar = p.valOrVarKeyword?.text == "var", jvmField = hasAnno(p, "JvmField"))
        }
        if (c.primaryConstructorParameters.isNotEmpty() || c.hasExplicitPrimaryConstructor()) {
            ctors += RawCallable(c.name ?: "", true, null, fqn, null,
                c.primaryConstructorParameters.map { (it.name ?: "_") to it.typeReference?.text }, ctx, node(parsed, c),
                c.primaryConstructor?.let { visOf(it) },
                varargParamIndex = c.primaryConstructorParameters.indexOfFirst { it.isVarArg },
                paramHasDefault = c.primaryConstructorParameters.map { it.hasDefaultValue() },
                jvmOverloads = c.primaryConstructor?.let { hasAnno(it, "JvmOverloads") } == true)
        }
        // Secondary constructors (`constructor(...)`) — so Java sees those overloads too.
        for (sc in c.secondaryConstructors) {
            ctors += RawCallable(c.name ?: "", true, null, fqn, null,
                sc.valueParameters.map { (it.name ?: "_") to it.typeReference?.text }, ctx, node(parsed, sc), visOf(sc),
                varargParamIndex = sc.valueParameters.indexOfFirst { it.isVarArg },
                paramHasDefault = sc.valueParameters.map { it.hasDefaultValue() },
                jvmOverloads = hasAnno(sc, "JvmOverloads"))
        }
        // A class with NO declared constructor has a compiler-synthesized no-arg primary constructor, so `Foo()`
        // resolves to a callable (cross-file too, where there's no PSI fallback). Only for instantiable kinds —
        // an interface/object/enum/annotation/abstract/sealed class can't be created with `Foo()`.
        if (ctors.isEmpty()) {
            val k = c as? KtClass
            val instantiable = c !is KtObjectDeclaration && k != null &&
                !k.isInterface() && !k.isEnum() && !k.isAnnotation() &&
                !k.hasModifier(KtTokens.ABSTRACT_KEYWORD) && !k.hasModifier(KtTokens.SEALED_KEYWORD)
            if (instantiable) {
                ctors += RawCallable(c.name ?: "", true, null, fqn, null, emptyList(), ctx, node(parsed, c), paramHasDefault = emptyList())
            }
        }
        val enumEntries = ArrayList<String>()
        for (d in c.declarations) {
            when (d) {
                is KtEnumEntry -> d.name?.let { enumEntries += it } // before KtClassOrObject
                is KtNamedFunction -> members += callable(d, ctx, parsed)
                is KtProperty -> members += property(d, ctx, parsed)
                // `val (a, b) = …` in a class body is an error (NON_LOCAL_DESTRUCTURING_DECLARATION), after which
                // K2 still declares `a` and `b` as the class's properties, so `o.b` resolves.
                is dev.ide.kotlin.syntax.psi.KtDestructuringDeclaration -> for (e in d.entries) {
                    members += RawCallable(
                        name = e.name ?: continue, isFunction = false, receiverText = null,
                        returnText = e.typeReference?.text, initializerText = null, paramTexts = emptyList(),
                        ctx = ctx, node = node(parsed, e),
                    )
                }
                is KtClassOrObject -> {} // nested handled as their own top-level entries via fqName elsewhere
                else -> {}
            }
        }
        // A `data class`'s `copy(...)` is compiler-synthesized (not written in source), so the index would
        // otherwise miss it — every primary-constructor property becomes a defaulted `copy` parameter, and the
        // function returns the class itself. Without this, `style.copy(fontSize = …)` on a project data class
        // lowers to `unresolved/ambiguous call` and a Compose preview can't interpret it.
        if ((c as? KtClass)?.isData() == true) {
            members += RawCallable(
                name = "copy", isFunction = true, receiverText = null, returnText = fqn, initializerText = null,
                paramTexts = c.primaryConstructorParameters.map { (it.name ?: "_") to it.typeReference?.text },
                ctx = ctx, node = node(parsed, c),
                paramHasDefault = c.primaryConstructorParameters.map { true }, // copy() defaults every param to the current value
            )
            // `componentN()` operators are compiler-synthesized for a data class (the Nth primary-constructor
            // property's type), so destructuring `val (a, b) = point` types `a`/`b` from them. Not in source, so
            // the index would otherwise miss them.
            c.primaryConstructorParameters.forEachIndexed { i, p ->
                members += RawCallable(
                    name = "component${i + 1}", isFunction = true, receiverText = null,
                    returnText = p.typeReference?.text, initializerText = null, paramTexts = emptyList(),
                    ctx = ctx, node = node(parsed, p),
                )
            }
        }
        val companion = c.declarations.filterIsInstance<KtObjectDeclaration>().firstOrNull { it.isCompanion() }
        val asClass = c as? KtClass
        return RawClass(fqn, c.name ?: fqn.substringAfterLast('.'), c is KtObjectDeclaration,
            supers, members, ctors, ctx, node(parsed, c),
            superClassText = c.superTypeListEntries.firstOrNull { it is KtSuperTypeCallEntry }?.typeReference?.text,
            typeParameterNames = asClass?.typeParameters?.mapNotNull { it.name } ?: emptyList(),
            typeParameterBounds = asClass?.let { cls ->
                val whereBounds = cls.typeConstraints.mapNotNull { tc ->
                    tc.subjectTypeParameterName?.getReferencedName()?.let { n -> n to (tc.boundTypeReference?.text ?: "") }
                }.toMap()
                cls.typeParameters.map { tp -> tp.extendsBound?.text ?: whereBounds[tp.name] ?: "" }
            } ?: emptyList(),
            typeParameterVariance = asClass?.typeParameters?.map { tp ->
                when (tp.variance) {
                    Variance.OUT_VARIANCE -> "out"
                    Variance.IN_VARIANCE -> "in"
                    else -> ""
                }
            } ?: emptyList(),
            enumEntries = enumEntries,
            companionObjectName = companion?.let { it.name ?: "Companion" },
            isCompanion = (c as? KtObjectDeclaration)?.isCompanion() == true,
            isInterface = asClass?.isInterface() == true,
            isEnum = asClass?.isEnum() == true,
            isAnnotation = asClass?.isAnnotation() == true,
            isAbstract = asClass?.let { it.hasModifier(KtTokens.ABSTRACT_KEYWORD) || it.hasModifier(KtTokens.SEALED_KEYWORD) } == true,
            isSealed = asClass?.isSealed() == true,
            // A plain final class: a KtClass that is not an interface/enum/annotation and carries no
            // open/abstract/sealed modifier. An `object` has `asClass == null` here, so it stays false (an
            // object can't be extended either, but that is a different error than FINAL_SUPERTYPE).
            isFinalClass = asClass?.let {
                !it.isInterface() && !it.isEnum() && !it.isAnnotation() &&
                    !it.hasModifier(KtTokens.OPEN_KEYWORD) && !it.hasModifier(KtTokens.ABSTRACT_KEYWORD) &&
                    !it.hasModifier(KtTokens.SEALED_KEYWORD)
            } == true,
            isExpect = c.hasModifier(KtTokens.EXPECT_KEYWORD),
            isLocal = isLocal,
            isPrivate = c.hasModifier(KtTokens.PRIVATE_KEYWORD),
            annotationNames = c.annotationEntries.mapNotNull { it.shortName?.asString() },
            annotationFqns = annoFqns(c, ctx),
            optInLevel = sourceOptInLevel(c))
    }

    /** FQNs of [decl]'s annotations, resolved against the declaring file's [ctx] (imports + same package). An
     *  annotation whose simple name can't be resolved (a star-import) is dropped — a miss, never a wrong FQN. */
    private fun annoFqns(decl: KtAnnotated, ctx: FileContext): List<String> =
        decl.annotationEntries.mapNotNull { resolveAnnoFqn(it, ctx) }

    private fun resolveAnnoFqn(entry: KtAnnotationEntry, ctx: FileContext): String? {
        val typeText = entry.typeReference?.text?.substringBefore('<')?.trim() ?: return null
        if ('.' in typeText) return typeText // written fully-qualified in source
        val name = entry.shortName?.asString() ?: return null
        ctx.imports.firstOrNull { !it.isStar && it.simpleName == name }?.let { return it.fqn }
        // Same package (a source-defined marker, or a same-package annotation). Wrong only for a star-imported
        // name — which then simply won't match a real marker (a miss), so this is sound (never a false positive).
        return if (ctx.packageName.isNotEmpty()) "${ctx.packageName}.$name" else name
    }

    /** When [c] is a `@RequiresOptIn` marker annotation, its declared level (`"ERROR"`/`"WARNING"`, default
     *  `"ERROR"`); null otherwise. Read from the annotation's `level = …` argument text. */
    private fun sourceOptInLevel(c: KtClassOrObject): String? {
        val entry = c.annotationEntries.firstOrNull { it.shortName?.asString() == "RequiresOptIn" } ?: return null
        val levelText = entry.valueArguments.firstOrNull { it.getArgumentName()?.asName?.asString() == "level" }
            ?.getArgumentExpression()?.text
            ?: entry.valueArguments.firstOrNull { it.getArgumentName() == null }?.getArgumentExpression()?.text
        return if (levelText != null && "WARNING" in levelText) "WARNING" else "ERROR"
    }

    /** ABSTRACT member detection: an explicit `abstract` modifier, or an interface member with no implementation
     *  (a function with no body / a property with no initializer, delegate or accessor body). A top-level
     *  declaration (no enclosing class) is never abstract. */
    private fun isAbstractFunction(f: KtNamedFunction): Boolean {
        if (f.hasModifier(KtTokens.ABSTRACT_KEYWORD)) return true
        val cls = f.containingClassOrObject as? KtClass ?: return false
        return cls.isInterface() && !f.hasBody()
    }

    private fun isAbstractProperty(p: KtProperty): Boolean {
        if (p.hasModifier(KtTokens.ABSTRACT_KEYWORD)) return true
        val cls = p.containingClassOrObject as? KtClass ?: return false
        return cls.isInterface() && p.initializer == null && p.delegate == null && p.accessors.none { it.hasBody() }
    }

    /** [c] plus every (transitively) nested class/object, each as its own [RawClass] keyed by dotted FQN — so
     *  a nested type/object (`Icons.Filled`, `Icons.AutoMirrored.Filled`) is a known type whose members
     *  enumerate and which a `Owner.Nested` selector resolves against, matching the binary-classpath behavior.
     *
     *  A companion object is just one more nested object here (`Outer.Companion`, named by
     *  [KtObjectDeclaration.name]), so it is recursed into like any other. It used to be registered by a
     *  separate pass and SKIPPED in the recursion, which registered the companion itself but nothing declared
     *  inside it: `private class Atom` in `KotlinFormatter`'s companion was not a known type at all, so
     *  `ArrayList<Atom>` and an `Atom` parameter read as unresolved references while `Atom(…)` -- resolved
     *  from live PSI, not the model -- was fine. */
    private fun collectClasses(c: KtClassOrObject, ctx: FileContext, parsed: KotlinParsedFile): List<RawClass> {
        val out = ArrayList<RawClass>()
        rawClass(c, ctx, parsed)?.let { out += it }
        c.declarations.forEach { d ->
            // A `KtEnumEntry` IS a `KtClassOrObject` (it extends `KtClass`), but an enum CONSTANT is a value of
            // the enum type, not a nested type — registering `Test.A` as a class made `isKnownType("Test.A")`
            // true, so `Test.A` mis-resolved to a classifier `A` instead of the enum type (its constants are
            // surfaced via [RawClass.enumEntries] / `enumConstantsOf`).
            if (d is KtClassOrObject && d !is KtEnumEntry) out += collectClasses(d, ctx, parsed)
        }
        return out
    }

    private fun callable(f: KtNamedFunction, ctx: FileContext, parsed: KotlinParsedFile) = RawCallable(
        name = f.name ?: "_",
        isFunction = true,
        receiverText = f.receiverTypeReference?.text,
        returnText = f.typeReference?.text,
        // An expression body only: it is what types an untyped function. A block body infers nothing from its
        // text, and carrying it would make every keystroke inside the body look like a declaration change.
        initializerText = if (f.typeReference == null && !f.hasBlockBody()) f.bodyExpression?.text else null,
        paramTexts = f.valueParameters.map { (it.name ?: "_") to it.typeReference?.text },
        ctx = ctx,
        node = node(parsed, f),
        visibility = visOf(f),
        isComposable = f.annotationEntries.any { it.shortName?.asString() == "Composable" },
        isInline = f.hasModifier(KtTokens.INLINE_KEYWORD),
        isInfix = f.hasModifier(KtTokens.INFIX_KEYWORD),
        isSuspend = f.hasModifier(KtTokens.SUSPEND_KEYWORD),
        isDeprecated = hasAnno(f, "Deprecated"),
        isAbstract = isAbstractFunction(f),
        varargParamIndex = f.valueParameters.indexOfFirst { it.isVarArg },
        paramHasDefault = f.valueParameters.map { it.hasDefaultValue() },
        typeParameterNames = f.typeParameters.mapNotNull { it.name },
        typeParameterBounds = f.let { fn ->
            val whereBounds = fn.typeConstraints.mapNotNull { tc ->
                tc.subjectTypeParameterName?.getReferencedName()?.let { n -> n to (tc.boundTypeReference?.text ?: "") }
            }.toMap()
            fn.typeParameters.map { tp -> tp.extendsBound?.text ?: whereBounds[tp.name] ?: "" }
        },
        jvmStatic = hasAnno(f, "JvmStatic"),
        jvmOverloads = hasAnno(f, "JvmOverloads"),
        jvmName = annoArg(f, "JvmName"),
        annotationFqns = annoFqns(f, ctx),
    )

    private fun property(p: KtProperty, ctx: FileContext, parsed: KotlinParsedFile) = RawCallable(
        name = p.name ?: "_",
        isFunction = false,
        receiverText = p.receiverTypeReference?.text,
        returnText = p.typeReference?.text,
        // With no declared type, the type comes from the initializer -- or, for a getter-only property
        // (`val cache get() = caches.callTargets`), from the GETTER's expression body, which is where the
        // value actually comes from. Reading only `initializer` left such a property with no recorded type at
        // all, so every use of it was unknown and everything downstream silently backed off.
        initializerText = if (p.typeReference == null) {
            (p.initializer ?: p.getter?.takeIf { !it.hasBlockBody() }?.bodyExpression)?.text
        } else null,
        paramTexts = emptyList(),
        ctx = ctx,
        node = node(parsed, p),
        visibility = visOf(p),
        isVar = p.isVar,
        isDeprecated = hasAnno(p, "Deprecated"),
        isAbstract = isAbstractProperty(p),
        jvmStatic = hasAnno(p, "JvmStatic"),
        jvmField = hasAnno(p, "JvmField"),
        annotationFqns = annoFqns(p, ctx),
        isConst = p.hasModifier(KtTokens.CONST_KEYWORD),
        isLateinit = p.hasModifier(KtTokens.LATEINIT_KEYWORD),
        // A generic extension property (`val <R> R.boxed: Box<R>`) declares type parameters as a function does;
        // its receiver is keyed under their bound (`sourceReceiverFqn`), so `a.boxed` finds it on any receiver,
        // and its types bind from the receiver.
        typeParameterNames = if (p.typeParameterList == null) emptyList() else p.typeParameters.mapNotNull { it.name },
        typeParameterBounds = if (p.typeParameterList == null) emptyList() else p.let { prop ->
            val whereBounds = prop.typeConstraints.mapNotNull { tc ->
                tc.subjectTypeParameterName?.getReferencedName()?.let { n -> n to (tc.boundTypeReference?.text ?: "") }
            }.toMap()
            prop.typeParameters.map { tp -> tp.extendsBound?.text ?: whereBounds[tp.name] ?: "" }
        },
    )

    /** True if [decl] carries an annotation whose simple name is [simpleName] (matches `@X` and `@pkg.X`). */
    private fun hasAnno(decl: KtAnnotated, simpleName: String): Boolean =
        decl.annotationEntries.any { it.shortName?.asString() == simpleName }

    /** The first string-literal argument of `@[simpleName]("…")` on [decl] (for `@JvmName`), or null. */
    private fun annoArg(decl: KtAnnotated, simpleName: String): String? =
        decl.annotationEntries.firstOrNull { it.shortName?.asString() == simpleName }
            ?.valueArguments?.firstOrNull()?.getArgumentExpression()?.text?.trim('"')?.takeIf { it.isNotEmpty() }

    private fun visOf(decl: KtModifierListOwner): String? = when {
        decl.hasModifier(KtTokens.PRIVATE_KEYWORD) -> "private"
        decl.hasModifier(KtTokens.PROTECTED_KEYWORD) -> "protected"
        decl.hasModifier(KtTokens.INTERNAL_KEYWORD) -> "internal"
        else -> null
    }

    private fun node(parsed: KotlinParsedFile, psi: KtElement): DomNode? =
        runCatching { parsed.adapt(psi) }.getOrNull()

    private fun walk(file: VirtualFile, onFile: (VirtualFile) -> Unit) {
        if (file.isDirectory) file.children().forEach { walk(it, onFile) } else onFile(file)
    }
}
