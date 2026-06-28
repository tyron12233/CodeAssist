package dev.ide.lang.kotlin.symbols

import dev.ide.lang.dom.DomNode
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.vfs.VirtualFile
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

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
    /** A `suspend` function. */
    val isSuspend: Boolean = false,
    /** The index of the `vararg` value parameter, or -1 if none. */
    val varargParamIndex: Int = -1,
    /** The function's own type-parameter names (`fun <T> items(…)` → `["T"]`) — so a param/return type that
     *  references one is marked a type parameter (enabling generic inference: binding `T` from an argument). */
    val typeParameterNames: List<String> = emptyList(),
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
        return SourceFile(ctx, topLevel, extensions, classes, typeAliases)
    }

    private fun rawClass(c: KtClassOrObject, ctx: FileContext, parsed: KotlinParsedFile): RawClass? {
        val fqn = c.fqName?.asString() ?: return null
        val supers = c.superTypeListEntries.mapNotNull { it.typeReference?.text }
        val members = ArrayList<RawCallable>()
        val ctors = ArrayList<RawCallable>()
        // Primary-constructor `val/var` params are member properties.
        c.primaryConstructorParameters.filter { it.hasValOrVar() }.forEach { p ->
            members += RawCallable(p.name ?: "_", false, null, p.typeReference?.text, null, emptyList(), ctx, node(parsed, p), visOf(p),
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
        }
        val companion = c.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtObjectDeclaration>().firstOrNull { it.isCompanion() }
        val asClass = c as? org.jetbrains.kotlin.psi.KtClass
        return RawClass(fqn, c.name ?: fqn.substringAfterLast('.'), c is org.jetbrains.kotlin.psi.KtObjectDeclaration,
            supers, members, ctors, ctx, node(parsed, c), enumEntries,
            companionObjectName = companion?.let { it.name ?: "Companion" },
            isCompanion = (c as? org.jetbrains.kotlin.psi.KtObjectDeclaration)?.isCompanion() == true,
            isInterface = asClass?.isInterface() == true,
            isEnum = asClass?.isEnum() == true,
            isAnnotation = asClass?.isAnnotation() == true)
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
            if (d is KtClassOrObject && !(d is org.jetbrains.kotlin.psi.KtObjectDeclaration && d.isCompanion())) {
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
        isSuspend = f.hasModifier(KtTokens.SUSPEND_KEYWORD),
        varargParamIndex = f.valueParameters.indexOfFirst { it.isVarArg },
        paramHasDefault = f.valueParameters.map { it.hasDefaultValue() },
        typeParameterNames = f.typeParameters.mapNotNull { it.name },
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

    private fun node(parsed: KotlinParsedFile, psi: org.jetbrains.kotlin.com.intellij.psi.PsiElement): DomNode? =
        runCatching { parsed.adapt(psi) }.getOrNull()

    private fun walk(file: VirtualFile, onFile: (VirtualFile) -> Unit) {
        if (file.isDirectory) file.children().forEach { walk(it, onFile) } else onFile(file)
    }
}
