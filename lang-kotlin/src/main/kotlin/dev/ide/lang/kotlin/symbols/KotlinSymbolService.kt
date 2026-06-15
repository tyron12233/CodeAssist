package dev.ide.lang.kotlin.symbols

import dev.ide.index.ClassNameValue
import dev.ide.index.IndexId
import dev.ide.index.IndexService
import dev.ide.index.MemberValue
import dev.ide.lang.dom.DomNode
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef
import dev.ide.lang.kotlin.compile.BundledKotlinStdlib
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

    private fun ownAndInherited(fqn: String, typeArgs: List<TypeRef>, visited: MutableSet<String>): List<KotlinSymbol> {
        if (!visited.add(fqn)) return emptyList()
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
        reader.decoded(fqn, this)?.let { d ->
            val bindings = bindingsFor(d.typeParameters, typeArgs) // List<T>.get() -> get(): String when args=[String]
            val own = d.ownMembers.map { substituteSymbol(it, bindings) }
            // Supertype args are erased by the decode (raw FQNs), so inherited members aren't re-substituted.
            val inherited = d.supertypeFqns.flatMap { ownAndInherited(it, emptyList(), visited) }
            return own + inherited
        }
        val javaFqn = Builtins.javaTypeFor(fqn) ?: fqn
        javaShape(javaFqn)?.let { js ->
            val supers = (listOfNotNull(js.superFqn) + js.interfaceFqns)
            return js.members + supers.flatMap { ownAndInherited(it, emptyList(), visited) }
        }
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

    fun extensionsFor(fqn: String, typeArgs: List<TypeRef> = emptyList()): List<KotlinSymbol> {
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

    private fun kotlinSupertypes(fqn: String, visited: MutableSet<String>): List<String> {
        if (!visited.add(fqn)) return emptyList()
        val direct = LinkedHashSet<String>()
        Builtins.builtinSupertypes(fqn).forEach { direct += it }
        builtins.lookup(fqn, null)?.supertypeFqns?.forEach { direct += it }
        model().classByFqn[fqn]?.superTypeTexts?.forEach { t -> resolveTypeName(t, model().classByFqn[fqn]!!.ctx)?.let { direct += it } }
        reader.decoded(fqn, this)?.supertypeFqns?.forEach { direct += it }
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

    /** Type-name candidates by prefix: source classes + defaults + the classpath `classNames` index. */
    fun typeNamesByPrefix(prefix: String, limit: Int = 100): List<KotlinSymbol> {
        val out = LinkedHashMap<String, KotlinSymbol>()
        model().classByFqn.values.filter { it.simpleName.startsWith(prefix, ignoreCase = true) }
            .forEach { out[it.fqn] = KotlinSymbol(it.simpleName, SymbolKind.CLASS, typeByFqn(it.fqn), origin = SOURCE, declarationNode = it.node) }
        Builtins.DEFAULT_SIMPLE_TYPES.filter { it.key.startsWith(prefix, ignoreCase = true) }
            .forEach { (s, fqn) -> out.getOrPut(fqn) { KotlinSymbol(s, SymbolKind.CLASS, typeByFqn(fqn), origin = BINARY) } }
        index?.prefix<ClassNameValue>(CLASS_NAMES, prefix, limit)?.forEach { hit ->
            out.getOrPut(hit.value.fqn) {
                KotlinSymbol(hit.value.fqn.substringAfterLast('.'), classNameKind(hit.value.kind), typeByFqn(hit.value.fqn), origin = BINARY)
            }
        }
        return out.values.take(limit)
    }

    /** A source class's declaration node, for go-to-definition on a type. */
    fun classDeclaration(fqn: String): DomNode? = model().classByFqn[fqn]?.node

    fun isSourceClass(fqn: String): Boolean = fqn in model().classByFqn
    fun sourceClass(fqn: String): RawClass? = model().classByFqn[fqn]

    /** Whether [fqn] names a real class/object (source, a default stdlib type, or on the classpath). Used to
     *  tell a TYPE receiver (`String.`, `Locale.`) from a value receiver (`listOf("").`). */
    fun isKnownType(fqn: String): Boolean {
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
        private val CLASS_NAMES = IndexId("java.classNames")
        private val MEMBERS_BY_OWNER = IndexId("java.membersByOwner")
        private val SOURCE = SymbolOrigin(fromSource = true, file = null)
        private val BINARY = SymbolOrigin(fromSource = false, file = null)
    }
}
