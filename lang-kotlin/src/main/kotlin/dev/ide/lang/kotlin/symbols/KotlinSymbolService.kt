package dev.ide.lang.kotlin.symbols

import dev.ide.index.ClassNameValue
import dev.ide.index.IndexId
import dev.ide.index.IndexService
import dev.ide.index.MemberValue
import dev.ide.lang.dom.DomNode
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef
import dev.ide.lang.kotlin.compile.BundledKotlinStdlib
import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticField
import dev.ide.lang.synthetic.SyntheticMethod
import dev.ide.lang.synthetic.SyntheticModifier
import dev.ide.lang.synthetic.SyntheticTypeKind
import dev.ide.vfs.VirtualFile
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The symbol/type hub. It unifies the two sources, project source (full fidelity, incl. extensions) and
 * classpath binaries (Kotlin via `@Metadata`, Java via bytecode), behind one neutral model, and resolves
 * type names against imports/defaults/same-package/classpath. Implements [KotlinTypeContext] so a
 * [KotlinType]'s `members()`/`supertypes()` route back here.
 *
 * The expensive bits (the source-model build, the classpath extension scan, per-type bytecode reads) are
 * lazy and cached; an edit invalidates the source model via [setOverlay].
 */
class KotlinSymbolService(
    private val sourceRoots: List<VirtualFile>,
    classpathJars: List<Path>,
    /** Optional: powers type-NAME completion (the `java.classNames` index). Members come from bytecode. */
    val index: IndexService? = null,
    /** Optional: persists the classpath extension scan across launches. In-memory only if null. */
    cacheDir: Path? = null,
    /** Synthetic ("light") classes this module should see (Android `R`/`BuildConfig`, ViewBinding, …),
     *  contributed via `platform.syntheticClass`. The host MUST exclude the Kotlin `<File>Kt` facades — a
     *  Kotlin file references its own top-level declarations directly, not through their Java-facade shape. */
    private val syntheticProvider: () -> List<SyntheticClass> = { emptyList() },
) : KotlinTypeContext, Closeable {

    // Kotlin's stdlib is an IMPLICIT dependency of every Kotlin file (like java.lang for Java). Always
    // include it so println/listOf/let/String extensions resolve even when the project never declared a
    // kotlin-stdlib dependency (e.g. an editor-only Kotlin project). Sourced from the bundled jar, never the
    // host runtime; see [BundledKotlinStdlib].
    private val allJars = (classpathJars + listOfNotNull(BundledKotlinStdlib.jar())).distinct()
    private val reader = ClasspathReader(allJars, cacheDir)
    // The real Kotlin built-ins (List/Int/String/…) from .kotlin_builtins, preferred over the java mapping.
    private val builtins = BuiltinsReader(allJars)
    private val javaShapeCache = ConcurrentHashMap<String, Holder<JavaShape>>()

    private class Holder<T>(val value: T?)

    /** A functional type's value-parameter types + result type (see [functionalShape]). */
    class FunctionalShape(val parameterTypes: List<TypeRef?>, val returnType: TypeRef?, val isExtension: Boolean)

    @Volatile private var overlay: Map<String, String> = emptyMap()
    @Volatile private var cachedModel: ModuleSourceModel? = null

    /** Replace the live text of edited files; invalidates the source model so the next query reparses. */
    fun setOverlay(map: Map<String, String>) {
        overlay = map
        cachedModel = null
    }

    private fun model(): ModuleSourceModel =
        cachedModel ?: synchronized(this) {
            cachedModel ?: SourceIndexBuilder.build(sourceRoots, overlay).also { cachedModel = it }
        }

    // --- synthetic ("light") classes (Android R/BuildConfig, ViewBinding, …) ---

    /** Every contributed synthetic class flattened by FQN (nested types included), plus the set of top-level
     *  FQNs (resolvable by simple name; a nested type is reached only through its outer, e.g. `R.layout`). */
    private class SyntheticIndex(val byFqn: Map<String, SyntheticClass>, val topLevelFqns: Set<String>) {
        companion object { val EMPTY = SyntheticIndex(emptyMap(), emptySet()) }
    }

    // Cached by the identity of the provider's result: the host returns a stable list per cache epoch and
    // swaps it (a fresh instance) when resources change, so an identity check both reuses and self-invalidates.
    @Volatile private var syntheticCacheKey: List<SyntheticClass>? = null
    @Volatile private var syntheticCache: SyntheticIndex? = null

    private fun synthetic(): SyntheticIndex {
        val current = runCatching { syntheticProvider() }.getOrDefault(emptyList())
        syntheticCache?.let { if (current === syntheticCacheKey) return it }
        if (current.isEmpty()) return SyntheticIndex.EMPTY.also { syntheticCache = it; syntheticCacheKey = current }
        val byFqn = HashMap<String, SyntheticClass>()
        fun add(sc: SyntheticClass) { byFqn[sc.fqName] = sc; sc.nestedClasses.forEach(::add) }
        current.forEach(::add)
        return SyntheticIndex(byFqn, current.map { it.fqName }.toHashSet())
            .also { syntheticCache = it; syntheticCacheKey = current }
    }

    /** Members of a synthetic class: fields + methods (with their declared modifiers) + nested types (the
     *  navigation `R.layout` → `R.layout.activity_main`). Null if [fqn] is not a synthetic class. */
    private fun syntheticMembers(fqn: String): List<KotlinSymbol>? {
        val sc = synthetic().byFqn[fqn] ?: return null
        val out = ArrayList<KotlinSymbol>(sc.fields.size + sc.methods.size + sc.nestedClasses.size)
        sc.fields.forEach { out += syntheticField(it) }
        sc.methods.forEach { out += syntheticMethod(it) }
        sc.nestedClasses.forEach { out += syntheticNestedType(it) }
        return out
    }

    private fun syntheticField(f: SyntheticField): KotlinSymbol = KotlinSymbol(
        name = f.name, kind = SymbolKind.FIELD, type = syntheticType(f.type),
        modifiers = syntheticModifiers(f.modifiers), origin = SOURCE,
        signature = ": ${f.type.substringAfterLast('.')}",
    )

    private fun syntheticMethod(m: SyntheticMethod): KotlinSymbol = KotlinSymbol(
        name = m.name, kind = SymbolKind.METHOD, type = syntheticType(m.returnType),
        modifiers = syntheticModifiers(m.modifiers), origin = SOURCE,
        signature = "(" + m.parameters.joinToString(", ") { "${it.name}: ${it.type.substringAfterLast('.')}" } +
            "): " + m.returnType.substringAfterLast('.'),
    )

    private fun syntheticNestedType(nested: SyntheticClass): KotlinSymbol = KotlinSymbol(
        name = nested.fqName.substringAfterLast('.'), kind = syntheticKind(nested.kind),
        type = typeByFqn(nested.fqName), modifiers = setOf(Modifier.STATIC), origin = SOURCE,
    )

    private fun syntheticModifiers(mods: Set<SyntheticModifier>): Set<Modifier> = buildSet {
        if (SyntheticModifier.STATIC in mods) add(Modifier.STATIC)
        if (SyntheticModifier.PRIVATE in mods) add(Modifier.PRIVATE)
        if (SyntheticModifier.PROTECTED in mods) add(Modifier.PROTECTED)
    }

    private fun syntheticKind(kind: SyntheticTypeKind): SymbolKind = when (kind) {
        SyntheticTypeKind.INTERFACE -> SymbolKind.INTERFACE
        SyntheticTypeKind.ENUM -> SymbolKind.ENUM
        SyntheticTypeKind.ANNOTATION -> SymbolKind.ANNOTATION_TYPE
        SyntheticTypeKind.CLASS -> SymbolKind.CLASS
    }

    /** A synthetic field/return type string (a primitive or a Java FQN) → a [KotlinType]. */
    private fun syntheticType(t: String): KotlinType? = when (t) {
        "int" -> typeByFqn("kotlin.Int")
        "long" -> typeByFqn("kotlin.Long")
        "short" -> typeByFqn("kotlin.Short")
        "byte" -> typeByFqn("kotlin.Byte")
        "boolean" -> typeByFqn("kotlin.Boolean")
        "char" -> typeByFqn("kotlin.Char")
        "float" -> typeByFqn("kotlin.Float")
        "double" -> typeByFqn("kotlin.Double")
        "void", "" -> null
        else -> typeByFqn(t)
    }

    // --- type construction & name resolution ---

    fun typeByFqn(fqn: String, args: List<TypeRef> = emptyList(), nullable: Boolean = false): KotlinType =
        KotlinType(fqn, args, nullable, this)

    /** Resolve a (possibly generic / nullable / qualified) type TEXT to a [KotlinType]. */
    fun typeFromText(text: String?, ctx: FileContext?): KotlinType? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        val nullable = trimmed.endsWith("?")
        val core = trimmed.removeSuffix("?").trim()
        val head = core.substringBefore('<').trim()
        val argText = core.substringAfter('<', "").substringBeforeLast('>', "")
        val fqn = resolveTypeName(head, ctx) ?: return null
        val args = if (argText.isBlank()) emptyList()
        else splitTopLevel(argText).mapNotNull { typeFromText(it, ctx) }
        return KotlinType(fqn, args, nullable, this)
    }

    /** Simple/qualified type name -> FQN (resolved via imports, same package, defaults, classpath). */
    fun resolveTypeName(name: String, ctx: FileContext?): String? {
        val simple = name.trim().removeSuffix("?").substringBefore('<').trim()
        if (simple.isEmpty()) return null
        if ('.' in simple) return simple // already qualified (FQN or Outer.Inner)

        ctx?.imports?.firstOrNull { !it.isStar && it.simpleName == simple }?.let { return it.fqn }
        ctx?.let { c -> "${c.packageName}.$simple".takeIf { it in model().classByFqn }?.let { return it } }
        model().classByFqn.keys.firstOrNull { it.substringAfterLast('.') == simple }?.let { return it }
        // A top-level synthetic class by simple name (e.g. `R` → `com.example.R`); nested types (`R.layout`)
        // are reached through their outer, never resolved bare.
        synthetic().topLevelFqns.firstOrNull { it.substringAfterLast('.') == simple }?.let { return it }
        Builtins.DEFAULT_SIMPLE_TYPES[simple]?.let { return it }
        ctx?.imports?.filter { it.isStar }?.forEach { star ->
            val cand = "${star.packageName}.$simple"
            if (reader.classBytes(cand) != null) return cand
        }
        index?.exact<ClassNameValue>(CLASS_NAMES, simple)?.firstOrNull()?.let { return it.fqn }
        return simple
    }

    // --- members / supertypes / extensions (KotlinTypeContext) ---

    override fun membersOf(typeFqn: String, typeArgs: List<TypeRef>, accessibleFrom: Symbol?): List<Symbol> =
        ownAndInherited(typeFqn, typeArgs, HashSet()) + extensionsFor(typeFqn, typeArgs)

    override fun supertypesOf(typeFqn: String): List<TypeRef> =
        kotlinSupertypes(typeFqn, HashSet()).map { typeByFqn(it) }

    private fun ownAndInherited(fqnRaw: String, typeArgs: List<TypeRef>, visited: MutableSet<String>): List<KotlinSymbol> {
        // A JVM type maps to its Kotlin classifier (`java.lang.String` → `kotlin.String`), so member
        // enumeration uses the Kotlin built-in's real members + the Kotlin-keyed supertype chain.
        val fqn = Builtins.kotlinTypeFor(fqnRaw) ?: fqnRaw
        if (!visited.add(fqn)) return emptyList()
        syntheticMembers(fqn)?.let { return it }
        model().classByFqn[fqn]?.let { rc ->
            val own = rc.members.map { toSymbol(it, fqn) } // source generics deferred (no <T> parse yet)
            val inherited = rc.superTypeTexts.mapNotNull { resolveTypeName(it, rc.ctx) }
                .flatMap { ownAndInherited(it, emptyList(), visited) }
            return own + inherited
        }
        // Kotlin built-ins (List/Int/String/…): the real members, preferred over the java.* approximation.
        builtins.lookup(fqn, this)?.let { d ->
            val bindings = bindingsFor(d.typeParameters, typeArgs)
            val own = d.ownMembers.map { substituteSymbol(it, bindings) }
            return own + d.supertypeFqns.flatMap { ownAndInherited(it, emptyList(), visited) }
        }
        // Classpath BINARY (@Metadata Kotlin or plain Java/Android): the type's shape comes from the
        // persistent `kotlin.typeShape` index when built, else a live decode/bytecode read (graceful degrade
        // while indexing). Either way the generic shape is enumerated + bound the same way.
        typeShape(fqn)?.let { return membersFromShape(it, typeArgs, visited) }
        // Cross-language: a same-project Java SOURCE class (no .class, no metadata) — its members come from
        // the `java.membersByOwner` index (public, keyed by owner FQN).
        index?.exact<MemberValue>(MEMBERS_BY_OWNER, fqn)?.map { memberFromIndex(it) }?.toList()
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        return emptyList()
    }

    private fun memberFromIndex(mv: MemberValue): KotlinSymbol {
        val isMethod = mv.kind == "method"
        return KotlinSymbol(
            name = mv.name,
            kind = if (isMethod) SymbolKind.METHOD else SymbolKind.FIELD,
            origin = SOURCE, // a Java SOURCE member
            signature = if (isMethod) "()" else null,
        )
    }

    fun extensionsFor(fqnRaw: String, typeArgs: List<TypeRef> = emptyList()): List<KotlinSymbol> {
        // A JVM receiver type maps to its Kotlin classifier so the Kotlin-keyed extensions apply (a value typed
        // `java.lang.String` still gets `String.uppercase`, a `java.util.List` gets `Iterable.map`).
        val fqn = Builtins.kotlinTypeFor(fqnRaw) ?: fqnRaw
        // Always include kotlin.Any: `T.let`/`also`/`run`/`apply`/… (unbounded type-param receiver) are keyed
        // there and apply to every instance, and a builtin supertype chain may not list Any explicitly.
        val targets = (listOf(fqn, "kotlin.Any") + kotlinSupertypes(fqn, HashSet())).toHashSet()
        val scan = reader.scan(this)
        val fromClasspath = targets.flatMap { scan.extensionsByReceiver[it].orEmpty() }
        val fromSource = model().extensions
            .filter { resolveTypeName(it.receiverText ?: return@filter false, it.ctx) in targets }
            .map { toSymbol(it, null) }
        // Bind the extension receiver's type params (Iterable<T>.map, T.also) from the actual receiver.
        return (fromClasspath + fromSource).map { bindExtensionReceiver(it, fqn, typeArgs) }
    }

    /** Map a generic decl's type-parameter names to the receiver's actual type arguments, positionally. */
    private fun bindingsFor(names: List<String>, args: List<TypeRef>): Map<String, TypeRef> =
        if (names.isEmpty() || args.isEmpty()) emptyMap() else names.zip(args).toMap()

    /**
     * The shape of a classpath BINARY type [fqn]: from the persistent `kotlin.typeShape` index when it has
     * been built (cross-launch, survives analyzer eviction), else a live decode/bytecode read so completion
     * degrades gracefully while the index is still building. Kotlin mapped types are routed to their JVM type
     * (`kotlin.collections.List` → `java.util.List`) for both, matching the live `javaShape` lookup. Returns
     * null for a non-classpath type (handled elsewhere: source model, built-ins, synthetic, Java source index).
     */
    private fun typeShape(fqn: String): TypeShape? {
        val lookupFqn = Builtins.javaTypeFor(fqn) ?: fqn
        index?.exact<TypeShape>(TYPE_SHAPE, lookupFqn)?.firstOrNull()?.let { return it.withContext(this) }
        reader.decoded(fqn, this)?.let { return TypeShape.of(it, this) }
        return javaShape(lookupFqn)?.let { TypeShape.of(it) }
    }

    /** Enumerate one type level from its [shape]: own members (with the receiver's [typeArgs] bound into their
     *  generic signatures) plus members inherited from each generic supertype (the binding substituted into the
     *  supertype's arguments first, so `List<String> : Collection<String>` keeps `stream()` typed). */
    private fun membersFromShape(shape: TypeShape, typeArgs: List<TypeRef>, visited: MutableSet<String>): List<KotlinSymbol> {
        val bindings = classBindings(shape, typeArgs)
        // A member that re-declares a class type-parameter name (a static `<E> of(E)` on `List<E>`) shadows it,
        // so its own parameters are excluded from the class binding (bound later from the call site).
        val own = shape.members.map { m ->
            substituteSymbol(m, if (m.typeParameters.isEmpty()) bindings else bindings - m.typeParameters.toSet())
        }
        val inherited = shape.supertypes.flatMap { st ->
            val sub = substitute(st, bindings) as? KotlinType ?: return@flatMap emptyList()
            ownAndInherited(sub.qualifiedName, sub.typeArguments, visited)
        }
        return own + inherited
    }

    /** Bind a type's parameters from the receiver's [typeArgs]; a parameter the receiver doesn't supply (a raw
     *  use) falls back to its erased bound when known (Java bytecode), so a member's generic return type still
     *  resolves to something concrete (`findViewById(): T` → its `View` bound). The metadata decode carries no
     *  bounds, so an uncovered parameter there is simply left unbound (its old behavior). */
    private fun classBindings(shape: TypeShape, typeArgs: List<TypeRef>): Map<String, TypeRef> {
        if (shape.typeParameters.isEmpty()) return emptyMap()
        val out = HashMap<String, TypeRef>(shape.typeParameters.size)
        shape.typeParameters.forEachIndexed { i, name ->
            (typeArgs.getOrNull(i) ?: shape.typeParameterBounds.getOrNull(i))?.let { out[name] = it }
        }
        return out
    }

    /** The lambda-relevant signature of a functional [type]: its value-parameter types + result type, with the
     *  type's arguments substituted. Handles a Kotlin `FunctionN` and a Java single-abstract-method interface
     *  (`java.util.function.Function`, `Comparator`, …) — so a lambda passed where either is expected can be
     *  typed. Null when [type] isn't functional. */
    fun functionalShape(type: KotlinType): FunctionalShape? {
        if (TypeRendering.isFunctionType(type.qualifiedName) && type.typeArguments.isNotEmpty()) {
            val params = type.typeArguments.dropLast(1)
            val inputs = if (type.isExtensionFunctionType) params.drop(1) else params
            return FunctionalShape(inputs, type.typeArguments.last(), type.isExtensionFunctionType)
        }
        // A Java SAM: the unique abstract, non-static instance method (Object's methods don't count).
        val abstracts = membersOf(type.qualifiedName, type.typeArguments, null).filterIsInstance<KotlinSymbol>()
            .filter {
                it.kind == SymbolKind.METHOD && Modifier.ABSTRACT in it.modifiers &&
                    Modifier.STATIC !in it.modifiers && it.name !in OBJECT_METHODS
            }
        val sam = abstracts.singleOrNull() ?: return null
        return FunctionalShape(sam.paramTypes, sam.type, isExtension = false)
    }

    /** Bind an extension's receiver type params from the actual receiver: `T.also` → T = the receiver type;
     *  `Iterable<T>.first()` on `List<String>` → T = String (positional from the receiver's args). */
    private fun bindExtensionReceiver(ext: KotlinSymbol, receiverFqn: String, receiverArgs: List<TypeRef>): KotlinSymbol {
        val bindings = HashMap<String, TypeRef>()
        ext.receiverTypeParam?.let { bindings[it] = typeByFqn(receiverFqn, receiverArgs) } // T.also(): T -> receiver
        ext.receiverTypeArgs.forEachIndexed { i, ra ->
            val k = ra as? KotlinType ?: return@forEachIndexed
            if (k.isTypeParameter && i < receiverArgs.size) bindings[k.qualifiedName] = receiverArgs[i]
        }
        return if (bindings.isEmpty()) ext else substituteSymbol(ext, bindings)
    }

    /** Apply type-parameter [bindings] to a symbol's return/param/receiver-arg types. */
    fun substituteSymbol(s: KotlinSymbol, bindings: Map<String, TypeRef>): KotlinSymbol {
        if (bindings.isEmpty()) return s
        return KotlinSymbol(
            name = s.name, kind = s.kind, type = s.type?.let { substitute(it, bindings) },
            owner = s.owner, modifiers = s.modifiers, origin = s.origin,
            receiverTypeFqn = s.receiverTypeFqn, signature = s.signature, typeParameters = s.typeParameters,
            typeParameterBounds = s.typeParameterBounds,
            paramTypes = s.paramTypes.map { it?.let { p -> substitute(p, bindings) } },
            receiverTypeArgs = s.receiverTypeArgs.map { substitute(it, bindings) },
            receiverTypeParam = s.receiverTypeParam,
            packageName = s.packageName,
            isInternal = s.isInternal,
            declarationNode = s.declaration(), doc = s.documentation(),
        )
    }

    /** Recursively replace type-parameter references in [type] per [bindings]. */
    fun substitute(type: TypeRef, bindings: Map<String, TypeRef>): TypeRef {
        if (bindings.isEmpty()) return type
        val kt = type as? KotlinType ?: return type
        if (kt.isTypeParameter) return bindings[kt.qualifiedName] ?: kt
        if (kt.typeArguments.isEmpty()) return kt
        return KotlinType(kt.qualifiedName, kt.typeArguments.map { substitute(it, bindings) }, kt.nullable, this, kt.isTypeParameter, kt.isExtensionFunctionType)
    }

    private fun kotlinSupertypes(fqnRaw: String, visited: MutableSet<String>): List<String> {
        val fqn = Builtins.kotlinTypeFor(fqnRaw) ?: fqnRaw // walk the Kotlin hierarchy for a JVM type too
        if (!visited.add(fqn)) return emptyList()
        val direct = LinkedHashSet<String>()
        Builtins.builtinSupertypes(fqn).forEach { direct += it }
        builtins.lookup(fqn, null)?.supertypeFqns?.forEach { direct += it }
        model().classByFqn[fqn]?.superTypeTexts?.forEach { t -> resolveTypeName(t, model().classByFqn[fqn]!!.ctx)?.let { direct += it } }
        reader.decoded(fqn, this)?.supertypeFqns?.forEach { direct += it }
        // A plain Java type's supertypes (its generic-erased classifier names), so the chain is complete for
        // assignability + extension lookup even when there's no Kotlin built-in/metadata entry.
        if (direct.isEmpty()) typeShape(fqn)?.supertypes?.forEach { (it as? KotlinType)?.let { k -> direct += k.qualifiedName } }
        val out = LinkedHashSet(direct)
        direct.forEach { out += kotlinSupertypes(it, visited) }
        return out.toList()
    }

    private fun javaShape(fqn: String): JavaShape? =
        javaShapeCache.getOrPut(fqn) {
            Holder(reader.classBytes(fqn)?.let { JavaBytecode.read(it, this) })
        }.value

    // --- scope feeds: top-level callables + type names ---

    /** All top-level callables visible by simple name (source + stdlib/classpath), for NAME completion. */
    fun topLevelCallables(): List<KotlinSymbol> {
        val src = model().topLevel.map { toSymbol(it, null) }
        val cp = reader.scan(this).topLevelByName.values.flatten()
        return src + cp
    }

    fun topLevelByName(name: String): List<KotlinSymbol> =
        model().topLevel.filter { it.name == name }.map { toSymbol(it, null) } +
            reader.scan(this).topLevelByName[name].orEmpty()

    /**
     * Completion candidates under a dotted package prefix [packageFqn]: its immediate sub-packages + the
     * public types declared directly in it (classpath via the `java.packages`/`java.packageTypes` indices,
     * plus same-project source classes), each filtered by [prefix]. Powers `java.<caret>` / `java.ut<caret>`.
     */
    fun packageMembers(packageFqn: String, prefix: String, limit: Int = 100): List<KotlinSymbol> {
        val out = LinkedHashMap<String, KotlinSymbol>()
        index?.let { idx ->
            // Sub-packages: query everything under `packageFqn.` and keep the next path segment.
            val full = if (prefix.isEmpty()) "$packageFqn." else "$packageFqn.$prefix"
            idx.prefix<String>(PACKAGES, full, 500).forEach { hit ->
                if (!hit.value.startsWith("$packageFqn.")) return@forEach
                val seg = hit.value.removePrefix("$packageFqn.").substringBefore('.')
                if (seg.isNotEmpty() && seg.startsWith(prefix, ignoreCase = true)) {
                    out.getOrPut("pkg:$seg") { KotlinSymbol(seg, SymbolKind.PACKAGE, origin = BINARY) }
                }
            }
            // Public types directly in the package.
            idx.exact<ClassNameValue>(PACKAGE_TYPES, packageFqn).forEach { v ->
                val simple = v.fqn.substringAfterLast('.')
                if (simple.startsWith(prefix, ignoreCase = true)) {
                    out.getOrPut(v.fqn) { KotlinSymbol(simple, classNameKind(v.kind), typeByFqn(v.fqn), origin = BINARY) }
                }
            }
        }
        // Same-project source classes declared in this package (the index lags the live buffer).
        model().classByFqn.values
            .filter { it.fqn.substringBeforeLast('.', "") == packageFqn && it.simpleName.startsWith(prefix, ignoreCase = true) }
            .forEach { out.getOrPut(it.fqn) { KotlinSymbol(it.simpleName, SymbolKind.CLASS, typeByFqn(it.fqn), origin = SOURCE, declarationNode = it.node) } }
        return out.values.take(limit)
    }

    /** Type-name candidates by prefix: source classes + defaults + the classpath `classNames` index. */
    fun typeNamesByPrefix(prefix: String, limit: Int = 100): List<KotlinSymbol> {
        val out = LinkedHashMap<String, KotlinSymbol>()
        model().classByFqn.values.filter { it.simpleName.startsWith(prefix, ignoreCase = true) }
            .forEach { out[it.fqn] = KotlinSymbol(it.simpleName, SymbolKind.CLASS, typeByFqn(it.fqn), origin = SOURCE, declarationNode = it.node) }
        // Top-level synthetic classes (Android `R`/`BuildConfig`, …) complete by simple name like any type.
        synthetic().let { idx ->
            idx.topLevelFqns.filter { it.substringAfterLast('.').startsWith(prefix, ignoreCase = true) }
                .forEach { fqn -> out.getOrPut(fqn) { KotlinSymbol(fqn.substringAfterLast('.'), syntheticKind(idx.byFqn.getValue(fqn).kind), typeByFqn(fqn), origin = SOURCE) } }
        }
        Builtins.DEFAULT_SIMPLE_TYPES.filter { it.key.startsWith(prefix, ignoreCase = true) }
            .forEach { (s, fqn) -> out.getOrPut(fqn) { KotlinSymbol(s, SymbolKind.CLASS, typeByFqn(fqn), origin = BINARY) } }
        index?.prefix<ClassNameValue>(CLASS_NAMES, prefix, limit)?.forEach { hit ->
            out.getOrPut(hit.value.fqn) {
                KotlinSymbol(hit.value.fqn.substringAfterLast('.'), classNameKind(hit.value.kind), typeByFqn(hit.value.fqn), origin = BINARY)
            }
        }
        return out.values.take(limit)
    }

    /** The constructors declared by a classpath type [fqn] (own shape only — constructors aren't inherited),
     *  for validating a constructor call's arguments. Served from the `kotlin.typeShape` index when built. */
    fun constructorsOf(fqn: String): List<KotlinSymbol> =
        typeShape(fqn)?.members?.filter { it.kind == SymbolKind.CONSTRUCTOR } ?: emptyList()

    /** Whether [fqn] is a Kotlin BINARY (`@Metadata`) class. Its constructors may have default arguments that
     *  the metadata decode doesn't surface, so an argument-count check against them would be unsound. */
    fun hasKotlinMetadata(fqn: String): Boolean = reader.decoded(fqn, null) != null

    /** A source class's declaration node, for go-to-definition on a type. */
    fun classDeclaration(fqn: String): DomNode? = model().classByFqn[fqn]?.node

    fun isSourceClass(fqn: String): Boolean = fqn in model().classByFqn
    fun sourceClass(fqn: String): RawClass? = model().classByFqn[fqn]

    /** Whether [fqn] names a real class/object (source, a default stdlib type, or on the classpath). Used to
     *  tell a TYPE receiver (`String.`, `Locale.`) from a value receiver (`listOf("").`). */
    fun isKnownType(fqn: String): Boolean {
        if (synthetic().byFqn.containsKey(fqn)) return true
        if (isSourceClass(fqn)) return true
        if (fqn in Builtins.DEFAULT_SIMPLE_TYPES.values) return true
        if (builtins.isBuiltin(fqn)) return true
        val java = Builtins.javaTypeFor(fqn) ?: fqn
        return reader.classBytes(fqn) != null || reader.classBytes(java) != null
    }

    // --- raw -> neutral symbol ---

    private fun toSymbol(rc: RawCallable, ownerFqn: String?): KotlinSymbol {
        val type = typeFromText(rc.returnText, rc.ctx) ?: inferInitializerType(rc.initializerText, rc.ctx)
        val receiverFqn = rc.receiverText?.let { resolveTypeName(it, rc.ctx) }
        val sig = if (rc.isFunction) {
            "(" + rc.paramTexts.joinToString(", ") { (n, t) -> "$n: ${t ?: "?"}" } + ")" +
                (rc.returnText?.let { ": $it" } ?: "")
        } else {
            rc.returnText?.let { ": $it" } ?: ""
        }
        return KotlinSymbol(
            name = rc.name,
            kind = if (rc.isFunction) SymbolKind.METHOD else SymbolKind.FIELD,
            type = type,
            owner = ownerFqn?.let { KotlinSymbol(it.substringAfterLast('.'), SymbolKind.CLASS, origin = SOURCE) },
            origin = SOURCE,
            receiverTypeFqn = receiverFqn,
            signature = sig,
            modifiers = when (rc.visibility) {
                "private" -> setOf(dev.ide.lang.resolve.Modifier.PRIVATE)
                "protected" -> setOf(dev.ide.lang.resolve.Modifier.PROTECTED)
                else -> emptySet()
            },
            isInternal = rc.visibility == "internal",
            // Top-level callables (no owner) carry their package for import-visibility; members don't.
            packageName = if (ownerFqn == null) rc.ctx.packageName.ifEmpty { null } else null,
            declarationNode = rc.node,
        )
    }

    /** Approximate typing of an initializer (constructor call / literal), for inferred member types. */
    private fun inferInitializerType(text: String?, ctx: FileContext?): KotlinType? {
        val e = text?.trim() ?: return null
        return when {
            e.endsWith(")") && e.substringBefore('(').isNotEmpty() && e.first().isLetter() ->
                typeFromText(e.substringBefore('('), ctx)
            e.startsWith("\"") -> typeByFqn("kotlin.String")
            e.toIntOrNull() != null -> typeByFqn("kotlin.Int")
            e.toLongOrNull() != null -> typeByFqn("kotlin.Long")
            e.toDoubleOrNull() != null -> typeByFqn("kotlin.Double")
            e == "true" || e == "false" -> typeByFqn("kotlin.Boolean")
            else -> null
        }
    }

    override fun close() {
        reader.close()
        javaShapeCache.clear()
    }

    private fun classNameKind(kind: String): SymbolKind = when (kind.uppercase()) {
        "INTERFACE" -> SymbolKind.INTERFACE
        "ENUM" -> SymbolKind.ENUM
        "ANNOTATION" -> SymbolKind.ANNOTATION_TYPE
        "RECORD" -> SymbolKind.RECORD
        else -> SymbolKind.CLASS
    }

    private fun splitTopLevel(args: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        val sb = StringBuilder()
        for (ch in args) {
            when (ch) {
                '<' -> { depth++; sb.append(ch) }
                '>' -> { depth--; sb.append(ch) }
                ',' -> if (depth == 0) { out += sb.toString(); sb.clear() } else sb.append(ch)
                else -> sb.append(ch)
            }
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out.map { it.trim() }
    }

    companion object {
        // Public Object methods don't count toward a functional interface's single abstract method.
        private val OBJECT_METHODS = setOf("equals", "hashCode", "toString")
        private val CLASS_NAMES = IndexId("java.classNames")
        private val TYPE_SHAPE = IndexId("kotlin.typeShape")
        private val PACKAGES = IndexId("java.packages")
        private val PACKAGE_TYPES = IndexId("java.packageTypes")
        private val MEMBERS_BY_OWNER = IndexId("java.membersByOwner")
        private val SOURCE = SymbolOrigin(fromSource = true, file = null)
        private val BINARY = SymbolOrigin(fromSource = false, file = null)
    }
}
