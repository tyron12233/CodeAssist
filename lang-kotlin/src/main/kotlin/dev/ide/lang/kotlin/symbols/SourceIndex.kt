package dev.ide.lang.kotlin.symbols

import dev.ide.lang.dom.DomNode
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.vfs.VirtualFile
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/**
 * The project-source declaration index. Every `.kt` in the module is parsed to PSI (no resolution) and its
 * declarations captured as raw, type-resolution-free data: top-level functions/properties/classes, class
 * members, constructors, imports, and (since this is source) extension functions as first-class. Type
 * references are kept as TEXT here; the [KotlinSymbolService] resolves them to FQNs lazily once the whole
 * module is known (avoiding a build-order cycle).
 */

data class ImportInfo(val fqn: String, val alias: String?, val isStar: Boolean) {
    val simpleName: String get() = alias ?: fqn.substringAfterLast('.')
    val packageName: String get() = if (isStar) fqn else fqn.substringBeforeLast('.', "")
}

/** Per-file resolution context: the package + imports a type-text is resolved against. Shared by its decls. */
class FileContext(val path: String, val packageName: String, val imports: List<ImportInfo>)

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
    val node: DomNode?,
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
)

class RawClass(
    val fqn: String,
    val simpleName: String,
    val isObject: Boolean,
    val superTypeTexts: List<String>,
    val members: List<RawCallable>,
    val constructors: List<RawCallable>,
    val ctx: FileContext,
    val node: DomNode?,
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
    /** True for a LOCAL type: an anonymous object (`object : T { }` / `object { }`) or a class/object declared
     *  inside a function/initializer body. Its [fqn] is a synthetic, deterministic key ([SourceIndexBuilder.
     *  localTypeFqn]) that the resolver recomputes from the same PSI, so member enumeration / diagnostics flow
     *  through the normal FQN machinery. Kept OUT of type-name completion (no one references it by that name). */
    val isLocal: Boolean = false,
)

class SourceFile(
    val ctx: FileContext,
    val topLevel: List<RawCallable>,
    val extensions: List<RawCallable>,
    val classes: List<RawClass>,
    /** Simple names of `typealias` declarations in the file — type references to them must not be flagged
     *  unresolved (the model resolves classes, not aliases). */
    val typeAliases: List<String> = emptyList(),
)

class ModuleSourceModel(val files: List<SourceFile>) {
    val classByFqn: Map<String, RawClass> = files.flatMap { it.classes }.associateBy { it.fqn }
    val topLevel: List<RawCallable> = files.flatMap { it.topLevel }
    val extensions: List<RawCallable> = files.flatMap { it.extensions }
    val typeAliasNames: Set<String> = files.flatMapTo(HashSet()) { it.typeAliases }

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
        val pkg = kt.packageFqName.asString()
        val imports = kt.importDirectives.mapNotNull { imp ->
            val fq = imp.importedFqName?.asString() ?: return@mapNotNull null
            ImportInfo(fq, imp.aliasName, imp.isAllUnder)
        }
        val ctx = FileContext(path, pkg, imports)

        val topLevel = ArrayList<RawCallable>()
        val extensions = ArrayList<RawCallable>()
        val classes = ArrayList<RawClass>()
        val typeAliases = ArrayList<String>()

        for (decl in kt.declarations) {
            when (decl) {
                is KtNamedFunction -> callable(decl, ctx, parsed).let { if (it.receiverText != null) extensions += it else topLevel += it }
                is KtProperty -> property(decl, ctx, parsed).let { if (it.receiverText != null) extensions += it else topLevel += it }
                is KtClassOrObject -> classes += collectClasses(decl, ctx, parsed)
                is org.jetbrains.kotlin.psi.KtTypeAlias -> decl.name?.let { typeAliases += it }
                else -> {}
            }
        }
        // Local + anonymous types (`object : T { }`, `object { }`, a `class`/`object` declared in a body) live
        // inside expression/statement positions the top-level walk above never enters. Capture each under a
        // synthetic FQN so its members enumerate + are checked through the normal machinery; the resolver
        // recomputes the SAME FQN from the same PSI (see [localTypeFqn]).
        classes += collectLocalTypes(kt, ctx, parsed)
        return SourceFile(ctx, topLevel, extensions, classes, typeAliases)
    }

    /** Every local/anonymous [KtClassOrObject] in [kt] (has no reachable `fqName`, and isn't an enum entry) —
     *  an anonymous object's `object`-declaration, or a class/object declared inside a body. Pre-order
     *  (document order), so an ordinal over this list is a stable, marker-splice-invariant key ([localTypeFqn]). */
    private fun localTypeDecls(kt: KtFile): List<KtClassOrObject> =
        com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(kt, KtClassOrObject::class.java)
            .filter { it.fqName == null && it !is org.jetbrains.kotlin.psi.KtEnumEntry }

    /** The synthetic, deterministic FQN a local/anonymous type [decl] is registered under. Independent of
     *  absolute offsets (which the completion marker shifts): it is the nearest NAMED enclosing type's FQN (or
     *  the file facade when top-level) plus the decl's ordinal among the local types sharing that owner. Both
     *  this builder and the resolver call it over the same PSI, so the keys match. */
    fun localTypeFqn(decl: KtClassOrObject): String {
        val owner = enclosingNamedOwnerFqn(decl)
        val ordinal = localTypeDecls(decl.containingKtFile)
            .filter { enclosingNamedOwnerFqn(it) == owner }
            .indexOfFirst { it === decl }.coerceAtLeast(0)
        return "$owner.\$L$ordinal"
    }

    /** The FQN of the nearest enclosing type that HAS one (a normally-named class/object), or the Kotlin file
     *  facade (`pkg.FileKt`) when [decl] sits at top level of a file — the owner an ordinal is scoped to. */
    private fun enclosingNamedOwnerFqn(decl: KtClassOrObject): String {
        var p: com.intellij.psi.PsiElement? = decl.parent
        while (p != null) {
            if (p is KtClassOrObject) p.fqName?.asString()?.let { return it }
            p = p.parent
        }
        val file = decl.containingKtFile
        val pkg = file.packageFqName.asString()
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
            val k = c as? org.jetbrains.kotlin.psi.KtClass
            val instantiable = c !is org.jetbrains.kotlin.psi.KtObjectDeclaration && k != null &&
                !k.isInterface() && !k.isEnum() && !k.isAnnotation() &&
                !k.hasModifier(KtTokens.ABSTRACT_KEYWORD) && !k.hasModifier(KtTokens.SEALED_KEYWORD)
            if (instantiable) {
                ctors += RawCallable(c.name ?: "", true, null, fqn, null, emptyList(), ctx, node(parsed, c), paramHasDefault = emptyList())
            }
        }
        val enumEntries = ArrayList<String>()
        for (d in c.declarations) {
            when (d) {
                is org.jetbrains.kotlin.psi.KtEnumEntry -> d.name?.let { enumEntries += it } // before KtClassOrObject
                is KtNamedFunction -> members += callable(d, ctx, parsed)
                is KtProperty -> members += property(d, ctx, parsed)
                is KtClassOrObject -> {} // nested handled as their own top-level entries via fqName elsewhere
                else -> {}
            }
        }
        // A `data class`'s `copy(...)` is compiler-synthesized (not written in source), so the index would
        // otherwise miss it — every primary-constructor property becomes a defaulted `copy` parameter, and the
        // function returns the class itself. Without this, `style.copy(fontSize = …)` on a project data class
        // lowers to `unresolved/ambiguous call` and a Compose preview can't interpret it.
        if ((c as? org.jetbrains.kotlin.psi.KtClass)?.isData() == true) {
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
        val companion = c.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtObjectDeclaration>().firstOrNull { it.isCompanion() }
        val asClass = c as? org.jetbrains.kotlin.psi.KtClass
        return RawClass(fqn, c.name ?: fqn.substringAfterLast('.'), c is org.jetbrains.kotlin.psi.KtObjectDeclaration,
            supers, members, ctors, ctx, node(parsed, c),
            typeParameterNames = asClass?.typeParameters?.mapNotNull { it.name } ?: emptyList(),
            typeParameterBounds = asClass?.let { cls ->
                val whereBounds = cls.typeConstraints.mapNotNull { tc ->
                    tc.subjectTypeParameterName?.getReferencedName()?.let { n -> n to (tc.boundTypeReference?.text ?: "") }
                }.toMap()
                cls.typeParameters.map { tp -> tp.extendsBound?.text ?: whereBounds[tp.name] ?: "" }
            } ?: emptyList(),
            typeParameterVariance = asClass?.typeParameters?.map { tp ->
                when (tp.variance) {
                    org.jetbrains.kotlin.types.Variance.OUT_VARIANCE -> "out"
                    org.jetbrains.kotlin.types.Variance.IN_VARIANCE -> "in"
                    else -> ""
                }
            } ?: emptyList(),
            enumEntries = enumEntries,
            companionObjectName = companion?.let { it.name ?: "Companion" },
            isCompanion = (c as? org.jetbrains.kotlin.psi.KtObjectDeclaration)?.isCompanion() == true,
            isInterface = asClass?.isInterface() == true,
            isEnum = asClass?.isEnum() == true,
            isAnnotation = asClass?.isAnnotation() == true,
            isAbstract = asClass?.let { it.hasModifier(KtTokens.ABSTRACT_KEYWORD) || it.hasModifier(KtTokens.SEALED_KEYWORD) } == true,
            isSealed = asClass?.isSealed() == true,
            isLocal = isLocal)
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

    /** [c] plus its companion object(s) and every (transitively) nested class/object, each as its own
     *  [RawClass] keyed by dotted FQN — so a nested type/object (`Icons.Filled`, `Icons.AutoMirrored.Filled`)
     *  is a known type whose members enumerate and which a `Owner.Nested` selector resolves against, matching
     *  the binary-classpath behavior. Companions are added by [companionClasses] and skipped in the recursion
     *  so each is registered exactly once. */
    private fun collectClasses(c: KtClassOrObject, ctx: FileContext, parsed: KotlinParsedFile): List<RawClass> {
        val out = ArrayList<RawClass>()
        rawClass(c, ctx, parsed)?.let { out += it }
        out += companionClasses(c, ctx, parsed)
        c.declarations.forEach { d ->
            // A `KtEnumEntry` IS a `KtClassOrObject` (it extends `KtClass`), but an enum CONSTANT is a value of
            // the enum type, not a nested type — registering `Test.A` as a class made `isKnownType("Test.A")`
            // true, so `Test.A` mis-resolved to a classifier `A` instead of the enum type (its constants are
            // surfaced via [RawClass.enumEntries] / `enumConstantsOf`).
            if (d is KtClassOrObject && d !is org.jetbrains.kotlin.psi.KtEnumEntry &&
                !(d is org.jetbrains.kotlin.psi.KtObjectDeclaration && d.isCompanion())) {
                out += collectClasses(d, ctx, parsed)
            }
        }
        return out
    }

    /** A class's companion object(s) registered as their own [RawClass] (`Outer.Companion`), so a bare
     *  `Outer.` reference can enumerate the companion's members + applicable extensions via the symbol service. */
    private fun companionClasses(c: KtClassOrObject, ctx: FileContext, parsed: KotlinParsedFile): List<RawClass> =
        c.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtObjectDeclaration>()
            .filter { it.isCompanion() }
            .mapNotNull { rawClass(it, ctx, parsed) }

    private fun callable(f: KtNamedFunction, ctx: FileContext, parsed: KotlinParsedFile) = RawCallable(
        name = f.name ?: "_",
        isFunction = true,
        receiverText = f.receiverTypeReference?.text,
        returnText = f.typeReference?.text,
        initializerText = if (f.typeReference == null) f.bodyExpression?.text else null,
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
    )

    private fun property(p: KtProperty, ctx: FileContext, parsed: KotlinParsedFile) = RawCallable(
        name = p.name ?: "_",
        isFunction = false,
        receiverText = p.receiverTypeReference?.text,
        returnText = p.typeReference?.text,
        initializerText = if (p.typeReference == null) p.initializer?.text else null,
        paramTexts = emptyList(),
        ctx = ctx,
        node = node(parsed, p),
        visibility = visOf(p),
        isVar = p.isVar,
        isDeprecated = hasAnno(p, "Deprecated"),
        isAbstract = isAbstractProperty(p),
        jvmStatic = hasAnno(p, "JvmStatic"),
        jvmField = hasAnno(p, "JvmField"),
    )

    /** True if [decl] carries an annotation whose simple name is [simpleName] (matches `@X` and `@pkg.X`). */
    private fun hasAnno(decl: org.jetbrains.kotlin.psi.KtAnnotated, simpleName: String): Boolean =
        decl.annotationEntries.any { it.shortName?.asString() == simpleName }

    /** The first string-literal argument of `@[simpleName]("…")` on [decl] (for `@JvmName`), or null. */
    private fun annoArg(decl: org.jetbrains.kotlin.psi.KtAnnotated, simpleName: String): String? =
        decl.annotationEntries.firstOrNull { it.shortName?.asString() == simpleName }
            ?.valueArguments?.firstOrNull()?.getArgumentExpression()?.text?.trim('"')?.takeIf { it.isNotEmpty() }

    private fun visOf(decl: KtModifierListOwner): String? = when {
        decl.hasModifier(KtTokens.PRIVATE_KEYWORD) -> "private"
        decl.hasModifier(KtTokens.PROTECTED_KEYWORD) -> "protected"
        decl.hasModifier(KtTokens.INTERNAL_KEYWORD) -> "internal"
        else -> null
    }

    private fun node(parsed: KotlinParsedFile, psi: com.intellij.psi.PsiElement): DomNode? =
        runCatching { parsed.adapt(psi) }.getOrNull()

    private fun walk(file: VirtualFile, onFile: (VirtualFile) -> Unit) {
        if (file.isDirectory) file.children().forEach { walk(it, onFile) } else onFile(file)
    }
}
