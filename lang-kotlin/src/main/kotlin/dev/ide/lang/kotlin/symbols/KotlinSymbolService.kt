package dev.ide.lang.kotlin.symbols

import dev.ide.index.ClassNameValue
import dev.ide.index.IndexId
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexService
import dev.ide.index.MemberValue
import dev.ide.lang.dom.DomNode
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef
import dev.ide.lang.kotlin.compile.BundledKotlinStdlib
import dev.ide.lang.kotlin.index.CallableShape
import dev.ide.lang.kotlin.index.KotlinCallableIndex
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
    /** Optional: real parameter names + javadoc/KDoc from attached SOURCES, used to enrich binary symbols
     *  (Java bytecode strips parameter names; neither bytecode nor `@Metadata` carries doc comments). */
    private val sourceDoc: dev.ide.lang.resolve.SourceDocProvider = dev.ide.lang.resolve.SourceDocProvider.NONE,
) : KotlinTypeContext, Closeable {

    // Kotlin's stdlib is an IMPLICIT dependency of every Kotlin file (like java.lang for Java). Always
    // include it so println/listOf/let/String extensions resolve even when the project never declared a
    // kotlin-stdlib dependency (e.g. an editor-only Kotlin project). Sourced from the bundled jar, never the
    // host runtime; see [BundledKotlinStdlib]. When an [index] is wired (the IDE path) the host adds this same
    // jar to the index scope, so the stdlib's callables/type-shapes come from the persistent index like any
    // other library; [reader] is only consulted when NO index is wired (standalone / tests).
    private val stdlibJar = BundledKotlinStdlib.jar()
    private val allJars = (classpathJars + listOfNotNull(stdlibJar)).distinct()
    private val reader = ClasspathReader(allJars, cacheDir)
    // The real Kotlin built-ins (List/Int/String/…) from .kotlin_builtins, preferred over the java mapping.
    // These are NOT `.class` files, so the `kotlin.typeShape` index can't carry them — this stays a (lazy,
    // one-time, content-cached) read of the stdlib jar's `.kotlin_builtins` resources, the language intrinsics.
    private val builtins = BuiltinsReader(allJars)
    private val javaShapeCache = ConcurrentHashMap<String, Holder<JavaShape>>()

    private class Holder<T>(val value: T?)

    /** A functional type's value-parameter types + result type (see [functionalShape]). */
    class FunctionalShape(val parameterTypes: List<TypeRef?>, val returnType: TypeRef?, val isExtension: Boolean)

    @Volatile private var overlay: Map<String, String> = emptyMap()
    @Volatile private var cachedModel: ModuleSourceModel? = null
    // The file currently being edited, as an already-parsed [SourceFile] (built from the live PSI, no re-parse),
    // OVERRIDING disk/overlay for its path in the model — so a class declared in the buffer being edited resolves
    // its members immediately (same-file freshness), before the file is saved and reindexed. Keyed path+textHash.
    @Volatile private var focalSource: SourceFile? = null
    @Volatile private var focalKey: Pair<String, Int>? = null
    // A monotonic content version per source file, bumped whenever its effective (overlay/focal) text changes.
    // Lets a file's analyze cache detect that a DIFFERENT file it depends on changed — cross-file invalidation
    // (editing a class in one file must refresh a dependent file's diagnostics). Only edited files are tracked.
    private val fileVersions = HashMap<String, Long>()
    private var versionClock = 0L
    // Path -> VirtualFile for every source `.kt` walked into the model, so a cross-file consumer (the Compose
    // preview's reachable-declaration lowering) can re-open the file declaring a reached type/function. Rebuilt
    // alongside the model, so it tracks files added/removed since the last build.
    @Volatile private var sourceVfByPath: Map<String, VirtualFile> = emptyMap()

    // Per-file parse cache so a model rebuild reparses ONLY the files whose effective text changed (the one
    // being edited) and reuses every other file's prior parse. Parsing is the dominant cost, so this keeps a
    // cross-file overlay refresh at O(one reparse) instead of O(whole module). Keyed by VirtualFile path; the
    // value is the content hash it was parsed from + the resulting SourceFile (null = parse failed/unreadable).
    private class CachedFile(val hash: Int, val file: SourceFile?)
    private val fileCache = ConcurrentHashMap<String, CachedFile>()

    // Inferred return type of an expression-body declaration with no explicit type (`fun f() = expr`,
    // `val p = expr`) whose initializer the cheap text heuristic [inferInitializerType] couldn't type —
    // computed lazily by resolving the body with a real [KotlinResolver]. Memoized by declaration identity and
    // cleared on a source-model rebuild ([buildModel]); a changed file produces fresh RawCallables, so a stale
    // entry can't survive an edit. [inferringBody] (per-thread) breaks the recursion when a body's type depends
    // (transitively) on its own — self/mutual recursion — which Kotlin itself rejects, so null is safe there.
    private val inferredBodyTypeMemo = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<RawCallable, Holder<KotlinType>>())
    private val inferringBody = ThreadLocal.withInitial { java.util.Collections.newSetFromMap(java.util.IdentityHashMap<RawCallable, Boolean>()) }

    // Per-receiver-FQN memo of the (recursively-walked) Kotlin supertype chain — the expensive part of
    // `extensionsFor`/`supertypesOf`, recomputed on every member-access keystroke otherwise. Split by origin:
    // a classpath/builtin type's chain CANNOT depend on project source (the classpath can't extend your code),
    // so it's stable for the session; only a SOURCE type's chain can change on an edit. Keeping the classpath
    // memo across edits is the win for Compose (its deep `Modifier`/`MaterialTheme`/… chains stay warm).
    private val classpathSupertypeMemo = ConcurrentHashMap<String, List<String>>()
    @Volatile private var sourceSupertypeMemo = ConcurrentHashMap<String, List<String>>()

    // Per-(receiver-target, name-prefix) memo of the classpath extension-index query. Like the classpath
    // supertype memo, this is session-stable: the persistent `kotlin.callables` index can't gain a project
    // extension (the classpath can't extend your code), and any re-index rebuilds this whole service. The
    // bare-dot (empty-prefix) query pulls the entire `kotlin.Any` bucket — ~0.6ms at Compose scale — and was
    // re-run on every keystroke; caching it makes a repeat query free. Holds the index portion only (the
    // stdlib scan + source extensions + per-receiver type-arg binding are applied fresh by the caller).
    private val classpathExtMemo = ConcurrentHashMap<String, List<KotlinSymbol>>()
    // Per-(receiver-fqn, member-name) memo of the same-named-member lookup for a CLASSPATH receiver type,
    // session-stable for the same reason. The diagnostics pass's unresolved-member check probes the same
    // receiver+name repeatedly (every `s.uppercase()`, `modifier.padding`, …), and it only needs the members'
    // existence + isExtension + import identity (all type-argument-independent), so the unbound result is cached.
    private val checkMembersMemo = ConcurrentHashMap<String, List<KotlinSymbol>>()
    // Per-(type-fqn, name-prefix) memo of a TYPE's companion-object members — `MaterialTheme.colorScheme`,
    // `Arrangement.spacedBy`, `Color.Transparent`. The diagnostics pass + member/expected-type completion probe
    // the same `Type.` companion repeatedly; for a CLASSPATH type the companion's shape is session-stable.
    private val companionMembersMemo = ConcurrentHashMap<String, List<KotlinSymbol>>()
    // Per-fqn memo of "does this classpath BINARY type exist?" (the [typeShape] presence half of [isKnownType]),
    // probed per name reference by the unresolved-member/-type checks. Binary existence is session-stable; the
    // SOURCE-class half stays uncached (a Java class added mid-edit must resolve without a rebuild).
    private val classpathTypeExistsMemo = ConcurrentHashMap<String, Boolean>()
    // Tracks the index's last-seen build state so the classpath memos above are dropped the moment a (re)build
    // STARTS — a rebuilt index can carry different members/extensions (a dependency was added), and a query
    // mid-build sees only a partial index, so partial results must never be cached.
    @Volatile private var extMemoBuilding = false

    /** True when the classpath memos are safe to use: clears them on a (re)build start, never caches mid-build. */
    private fun classpathCacheUsable(idx: IndexService): Boolean {
        val building = idx.status.building
        if (building && !extMemoBuilding) {
            classpathExtMemo.clear(); checkMembersMemo.clear(); companionMembersMemo.clear(); classpathTypeExistsMemo.clear()
        }
        extMemoBuilding = building
        return !building
    }

    /**
     * "Dumb mode" gate for classpath/library symbols. When an [index] is wired (the IDE path), classpath
     * lookups must wait for it to be [IndexStatus.ready]: before the first build finishes (and while a rebuild
     * runs) we return NOTHING for classpath binaries rather than falling back to a live jar scan / `@Metadata`
     * decode — so a freshly opened project never pays the bulk-scan cost, it just offers buffer-words + same-file
     * symbols until the index is ready. With no index wired (standalone / tests) there is no other source, so
     * the live reader IS the classpath and this is always true.
     */
    fun classpathReady(): Boolean = index?.status?.ready ?: true

    /**
     * Replace the live editor buffers (VirtualFile path → text) the source model overlays on top of disk, so
     * cross-file completion/resolution/diagnostics see UNSAVED edits in other open files. Diffs against the
     * current overlay and invalidates only the files whose effective text actually changed (a no-op when none
     * did), so the steady-state per-keystroke cost is one file's reparse, not the whole module's.
     */
    fun setOverlay(map: Map<String, String>) {
        synchronized(this) {
            val old = overlay
            if (map == old) return // identical content → the model already reflects it
            // Files whose effective text changed: present on only one side, or on both with differing text. A
            // path that LEFT the overlay reverts to its disk content, so drop its cache too (model() re-reads).
            val changed = HashSet<String>()
            for ((p, t) in map) if (old[p] != t) changed += p
            for (p in old.keys) if (p !in map) changed += p
            overlay = map
            if (changed.isEmpty()) return
            changed.forEach { fileCache.remove(it); fileVersions[it] = ++versionClock }
            cachedModel = null
            sourceSupertypeMemo = ConcurrentHashMap() // only SOURCE chains can change; classpath memo stays warm
        }
    }

    /**
     * Register the file currently being analyzed as the model's FOCAL source — built from its live PSI (via
     * [build], called only when the path+hash key changed, so it reuses the parse the editor already has). It
     * OVERRIDES the disk/overlay version of its path in the model, so a class/member declared in the buffer
     * being edited resolves immediately (same-file freshness), even before the file is saved/indexed. Keyed by
     * (path, textHash): a repeated query on unchanged text is a no-op.
     */
    fun syncFocal(path: String, textHash: Int, build: () -> SourceFile?) {
        synchronized(this) {
            if (focalKey == path to textHash) return
            focalSource = runCatching { build() }.getOrNull()
            focalKey = path to textHash
            fileVersions[path] = ++versionClock // the focal file's content changed (matters to files depending on it)
            cachedModel = null
            sourceSupertypeMemo = ConcurrentHashMap() // only SOURCE chains can change; classpath memo stays warm
        }
    }

    /** A stamp over the content versions of every edited source file EXCEPT [exceptPath] — so a file's analyze
     *  cache can tell that a DIFFERENT file (a cross-file dependency) changed and invalidate itself, while its
     *  OWN edits (excluded here, handled by the per-declaration text diff) don't force a full re-analyze. */
    fun externalContentStamp(exceptPath: String): Long = synchronized(this) {
        var h = 0L
        for ((p, v) in fileVersions) if (p != exceptPath) h = h * 1_000_003L + (p.hashCode().toLong() * 31L + v)
        h
    }

    private fun model(): ModuleSourceModel =
        cachedModel ?: synchronized(this) {
            cachedModel ?: buildModel().also { cachedModel = it }
        }

    /** Aggregate the per-file [SourceFile]s into the module model, reusing unchanged files' cached parses and
     *  reparsing only those whose effective (overlay-or-disk) text changed since the last build. */
    private fun buildModel(): ModuleSourceModel {
        inferredBodyTypeMemo.clear() // a rebuilt model may carry edited bodies; re-infer on demand
        val ov = overlay
        val focal = focalSource
        val focalPath = focalKey?.first
        val files = ArrayList<SourceFile>()
        val vfByPath = HashMap<String, VirtualFile>()
        val seen = HashSet<String>()
        // The focal file's LIVE version is appended below — skip its disk/overlay copy so it isn't read twice
        // (and so a not-yet-saved focal file, absent from disk, still contributes its declarations).
        if (focal != null && focalPath != null) seen.add(focalPath)
        for (root in sourceRoots) walkKt(root) { vf ->
            if (seen.add(vf.path)) {
                vfByPath[vf.path] = vf
                sourceFileFor(vf, ov)?.let(files::add)
            }
        }
        if (focal != null) files.add(focal)
        sourceVfByPath = vfByPath
        return ModuleSourceModel(files)
    }

    /** A project-source `.kt` file plus the text the preview should lower (the live overlay buffer when the file
     *  is open and edited, else its disk content) — what cross-file Compose-preview lowering re-opens. */
    class PreviewSourceFile(val file: VirtualFile, val text: String)

    private fun previewSourceFile(path: String?): PreviewSourceFile? {
        if (path == null) return null
        model() // ensure sourceVfByPath is populated for this overlay generation
        val vf = sourceVfByPath[path] ?: return null
        val text = overlay[path] ?: runCatching { vf.readText().toString() }.getOrNull() ?: return null
        return PreviewSourceFile(vf, text)
    }

    /** The source file declaring top-level type [fqn] (or, failing an exact FQN match, one whose SIMPLE name
     *  matches — a constructor callee carries only the simple name), or null when no project source declares it
     *  (a library/synthetic type). For cross-file Compose-preview lowering. */
    fun sourceFileDeclaringType(fqn: String): PreviewSourceFile? {
        val m = model()
        val raw = m.classByFqn[fqn]
            ?: m.classByFqn.values.firstOrNull { it.simpleName == fqn.substringAfterLast('.') }
        return previewSourceFile(raw?.ctx?.path)
    }

    /** The source files declaring a top-level function named [name] — for cross-file preview lowering of a call
     *  to a function defined in another file. Distinct by path. */
    fun sourceFilesDeclaringFunction(name: String): List<PreviewSourceFile> =
        model().topLevel.asSequence()
            .filter { it.isFunction && it.receiverText == null && it.name == name }
            .mapNotNull { previewSourceFile(it.ctx.path) }
            .distinctBy { it.file.path }
            .toList()

    private fun sourceFileFor(vf: VirtualFile, ov: Map<String, String>): SourceFile? {
        val path = vf.path
        val overlayText = ov[path]
        val cached = fileCache[path]
        if (overlayText != null) {
            val h = overlayText.hashCode()
            cached?.let { if (it.hash == h) return it.file }
            val sf = SourceIndexBuilder.extract(vf, overlayText)
            fileCache[path] = CachedFile(h, sf)
            return sf
        }
        // No overlay → disk content. A prior parse is reused without touching disk: disk is stable during a
        // session, and a save routes the file through the overlay (which invalidates this entry via setOverlay).
        cached?.let { return it.file }
        val text = runCatching { vf.readText().toString() }.getOrNull() ?: return null
        val sf = SourceIndexBuilder.extract(vf, text)
        fileCache[path] = CachedFile(text.hashCode(), sf)
        return sf
    }

    private fun walkKt(file: VirtualFile, onFile: (VirtualFile) -> Unit) {
        if (file.isDirectory) file.children().forEach { walkKt(it, onFile) }
        else if (file.name.endsWith(".kt")) onFile(file)
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
        paramTypes = m.parameters.map { syntheticType(it.type) },
        paramNames = m.parameters.map { it.name },
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
        // A function type (`(A) -> B`, `T.() -> R`, `suspend (…) -> …`, `@Composable (…) -> …`) maps to a
        // `kotlin.FunctionN` carrying the extension-receiver / composable flags — so a content-lambda parameter
        // (`Row(content: RowScope.() -> Unit)`) establishes its implicit `this` receiver during resolution.
        functionTypeFromText(trimmed, ctx)?.let { return it }
        val nullable = trimmed.endsWith("?")
        val core = trimmed.removeSuffix("?").trim()
        val head = core.substringBefore('<').trim()
        val argText = core.substringAfter('<', "").substringBeforeLast('>', "")
        val fqn = resolveTypeName(head, ctx) ?: return null
        val args = if (argText.isBlank()) emptyList()
        else splitTopLevel(argText).mapNotNull { typeFromText(it, ctx) }
        return KotlinType(fqn, args, nullable, this)
    }

    /**
     * Parse a Kotlin function type from source text into a `kotlin.FunctionN` (the shape resolution expects).
     * Handles a leading `suspend` / `@Composable` (and any other `@Anno`) prefix, an optional extension
     * receiver (`Receiver.(params) -> R`, possibly generic), value parameters, the result type, and an outer
     * `?`. Returns null when [text] is not a function type, so the caller falls through to plain-type parsing.
     */
    private fun functionTypeFromText(text: String, ctx: FileContext?): KotlinType? {
        var s = text.trim()
        val nullable = s.endsWith("?") && s.startsWith("(") // only a fully-parenthesized `(…)?` is a nullable fn type
        if (nullable) s = s.removeSuffix("?").trim().removePrefix("(").removeSuffix(")").trim()
        // Strip leading annotations (`@Composable`, `@ExtensionFunctionType`, …), noting `@Composable`. Only an
        // `(…)` IMMEDIATELY following the name is annotation arguments (`@Anno(x)`); a space before it (`@Composable
        // () -> Unit`) is the function type's own parameter list, which must NOT be consumed.
        var isComposable = false
        while (s.startsWith("@")) {
            val rest = s.drop(1)
            val anno = rest.takeWhile { it.isLetterOrDigit() || it == '.' || it == '_' }
            if (anno.substringAfterLast('.') == "Composable") isComposable = true
            var after = rest.drop(anno.length)
            if (after.startsWith("(")) {
                val close = matchingParen(after, 0)
                if (close >= 0) after = after.substring(close + 1)
            }
            s = after.trim()
        }
        val suspend = s.startsWith("suspend") && s.getOrNull(7)?.isWhitespace() == true
        if (suspend) s = s.removePrefix("suspend").trim()
        val arrow = topLevelArrowIndex(s) ?: return null
        val left = s.take(arrow).trim()
        val returnText = s.substring(arrow + 2).trim()
        // The value-parameter group is the final top-level `(…)`; anything before it (minus the `.`) is the
        // extension receiver (`Map<K, V>.() -> …`).
        val open = if (left.endsWith(")")) openOfFinalGroup(left) else return null
        if (open < 0) return null
        val receiverText = left.take(open).trim().removeSuffix(".").trim()
        val paramsInner = left.substring(open + 1, left.length - 1).trim()
        val params = if (paramsInner.isBlank()) emptyList() else splitTopLevelParens(paramsInner).mapNotNull { typeFromText(it, ctx) }
        val returnType = typeFromText(returnText, ctx) ?: return null
        val isExtension = receiverText.isNotEmpty()
        val receiverType = if (isExtension) typeFromText(receiverText, ctx) else null
        if (isExtension && receiverType == null) return null
        val arity = (if (isExtension) 1 else 0) + params.size
        val fqn = "kotlin." + (if (suspend) "SuspendFunction" else "Function") + arity
        val args = (listOfNotNull(receiverType) + params + returnType)
        return KotlinType(fqn, args, nullable = false, context = this, isExtensionFunctionType = isExtension, isComposable = isComposable)
    }

    /** Index of the top-level `->` (depth 0 across `()`/`<>`), or null if there is none. */
    private fun topLevelArrowIndex(s: String): Int? {
        var depth = 0
        var i = 0
        while (i < s.length - 1) {
            when (s[i]) {
                '(', '<' -> depth++
                ')', '>' -> depth--
                '-' -> if (depth == 0 && s[i + 1] == '>') return i
            }
            i++
        }
        return null
    }

    /** The index of the `(` opening the LAST top-level parenthesized group of [s] (which must end with `)`). */
    private fun openOfFinalGroup(s: String): Int {
        var depth = 0
        for (i in s.indices.reversed()) {
            when (s[i]) {
                ')' -> depth++
                '(' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }

    /** The index of the `)` matching the `(` at [open]. */
    private fun matchingParen(s: String, open: Int): Int {
        var depth = 0
        for (i in open until s.length) {
            when (s[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }

    /** Split top-level comma-separated parts, tracking BOTH `<>` and `()` depth (function-type aware). */
    private fun splitTopLevelParens(args: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        val sb = StringBuilder()
        for (ch in args) {
            when (ch) {
                '<', '(' -> { depth++; sb.append(ch) }
                '>', ')' -> { depth--; sb.append(ch) }
                ',' -> if (depth == 0) { out += sb.toString(); sb.clear() } else sb.append(ch)
                else -> sb.append(ch)
            }
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out.map { it.trim() }
    }

    /**
     * Simple/qualified type name -> FQN, resolved via imports / same package / defaults / classpath. STRICT: an
     * unimported classpath type does NOT resolve (its bare name is returned verbatim) — matching Kotlin/IntelliJ
     * (a type must be imported), so the unresolved-TYPE diagnostic flags the missing import and completion on a
     * value of an unresolved type yields nothing. The bare name returned lets callers detect "unresolved" via
     * [isKnownType] (false for a simple name).
     */
    fun resolveTypeName(name: String, ctx: FileContext?): String? {
        val simple = name.trim().removeSuffix("?").substringBefore('<').trim()
        if (simple.isEmpty()) return null
        if ('.' in simple) {
            // A qualified name. It may already be a full FQN (`java.util.Locale`) or an `Outer.Inner` whose
            // outer is reached by its simple name (`Icons.AutoMirrored.Filled`, with `Icons` imported). Resolve
            // the head segment; if that yields a different FQN and the re-attached nested name is real, use it
            // — so a source extension's nested receiver type keys to the same FQN the use site infers.
            val head = simple.substringBefore('.')
            val headFqn = resolveTypeName(head, ctx)
            if (headFqn != null && headFqn != head) {
                val cand = "$headFqn.${simple.substringAfter('.')}"
                if (isKnownType(cand)) return cand
            }
            return simple // already a full FQN (or unresolvable) → verbatim
        }

        // 1. Kotlin built-in simple types (String/Int/List/Map/…) are intrinsic — they are ALWAYS in scope
        //    via kotlin.*/kotlin.collections.* implicit imports and cannot be shadowed by an explicit import
        //    that happens to share the simple name (e.g. `import icons.automirrored.filled.List` → the icon
        //    object must not displace `kotlin.collections.List` in type-annotation position).
        Builtins.DEFAULT_SIMPLE_TYPES[simple]?.let { return it }
        // 2. An explicit (non-star) import.
        ctx?.imports?.firstOrNull { !it.isStar && it.simpleName == simple }?.let { return it.fqn }
        // 3. The file's own package (source, then classpath) — a same-package type needs no import.
        ctx?.packageName?.takeIf { it.isNotEmpty() }?.let { pkg ->
            "$pkg.$simple".let { cand -> if (cand in model().classByFqn || typeShape(cand) != null) return cand }
        }
        // 4. Any project SOURCE class by simple name: the editor stays lenient about a same-module type the
        //    user hasn't imported yet (its members still resolve). Kotlin sources come from the model; Java
        //    sources from the index (SOURCE origin only — a LIBRARY type must NOT resolve bare, so an unimported
        //    classpath type like `ComponentActivity` stays unresolved and is flagged by the unresolved-TYPE check).
        model().classByFqn.keys.firstOrNull { it.substringAfterLast('.') == simple }?.let { return it }
        index?.exact<ClassNameValue>(CLASS_NAMES, simple)?.firstOrNull { it.origin == IndexOrigin.SOURCE }?.let { return it.fqn }
        // 5. A top-level synthetic class by simple name (e.g. `R` → `com.example.R`); nested types (`R.layout`)
        //    are reached through their outer, never resolved bare.
        synthetic().topLevelFqns.firstOrNull { it.substringAfterLast('.') == simple }?.let { return it }
        // 6. A star-imported package, then Kotlin's implicit default star imports (kotlin.*, java.lang, …):
        //    a simple name is visible if it lives in one of these packages.
        val starPackages = (ctx?.imports?.filter { it.isStar }?.map { it.packageName } ?: emptyList()) +
            DefaultImports.STAR_PACKAGES
        for (pkg in starPackages) { // existence via the type-shape index (self-gates in dumb mode); no live probe when wired
            val cand = "$pkg.$simple"
            if (typeShape(cand) != null) return cand
        }
        // NOT brought into scope by any import. Deliberately NO blind classpath lookup by simple name — that
        // fallback masked missing-import errors (e.g. `ComponentActivity` silently resolving to
        // `androidx.activity.ComponentActivity`, then its members/extensions completing as if imported). The
        // name is returned verbatim so callers can detect "unresolved" via `isKnownType` (false for a bare name).
        return simple
    }

    // --- members / supertypes / extensions (KotlinTypeContext) ---

    override fun membersOf(typeFqn: String, typeArgs: List<TypeRef>, accessibleFrom: Symbol?): List<Symbol> =
        ownAndInherited(typeFqn, typeArgs, HashSet()) + extensionsFor(typeFqn, typeArgs)

    /**
     * Members of [typeFqn] whose name starts with [namePrefix] — the completion path. Pushing the prefix down
     * matters with large classpaths (Compose): a type's extension set (esp. the `kotlin.Any` bucket, which
     * grows with every library) is filtered BEFORE the per-symbol receiver binding, so only the handful the
     * user is actually typing toward gets materialized, not hundreds per keystroke. An empty prefix = all
     * (same as [membersOf]).
     */
    fun membersForCompletion(typeFqn: String, typeArgs: List<TypeRef>, namePrefix: String): List<KotlinSymbol> {
        val own = dev.ide.lang.kotlin.KotlinPerf.span("own") { ownAndInherited(typeFqn, typeArgs, HashSet()) }
        val ownMatched = if (namePrefix.isEmpty()) own else own.filter { it.name.startsWith(namePrefix, ignoreCase = true) }
        return ownMatched + dev.ide.lang.kotlin.KotlinPerf.span("ext") { extensionsFor(typeFqn, typeArgs, namePrefix) }
    }

    /**
     * Members of [typeFqn] named EXACTLY [name] — the diagnostic path's "does this member exist on the receiver?"
     * probe (unresolved-member checking). Pushes the name through the same prefix machinery as
     * [membersForCompletion], so only same-named members + extensions are materialized and receiver-bound —
     * NOT the type's entire extension set (the `kotlin.Any` bucket alone is thousands on a Compose classpath),
     * which `membersOf(…)` with no prefix enumerated and allocated just to keep the one matching name.
     * Empty [name] returns nothing (an incomplete `recv.` is not a real member reference) rather than everything.
     */
    fun membersNamed(typeFqn: String, typeArgs: List<TypeRef>, name: String): List<KotlinSymbol> {
        if (name.isEmpty()) return emptyList()
        return membersForCompletion(typeFqn, typeArgs, name).filter { it.name == name }
    }

    /** Same-named members for the diagnostics existence / extension-in-scope check. Cached for a CLASSPATH
     *  receiver (session-stable, index-invalidated); a source receiver — whose members change on edit — goes
     *  uncached. Type arguments don't affect a member's existence / isExtension / import identity, so the
     *  unbound (no-type-args) result is what's cached and reused. */
    fun membersNamedForCheck(typeFqn: String, typeArgs: List<TypeRef>, name: String): List<KotlinSymbol> {
        if (name.isEmpty()) return emptyList()
        val idx = index
        if (idx == null || sourceClass(typeFqn) != null || !classpathCacheUsable(idx))
            return membersNamed(typeFqn, typeArgs, name)
        return checkMembersMemo.getOrPut("$typeFqn $name") { membersNamed(typeFqn, emptyList(), name) }
    }

    override fun supertypesOf(typeFqn: String): List<TypeRef> =
        kotlinSupertypesMemo(typeFqn).map { typeByFqn(it) }

    /** [kotlinSupertypes] memoized per FQN; the walk is the hot cost. A SOURCE type's chain (its FQN is in the
     *  project model) goes in the edit-dropped memo, everything else in the session-stable classpath memo. */
    private fun kotlinSupertypesMemo(fqn: String): List<String> {
        val memo = if (model().classByFqn.containsKey(fqn)) sourceSupertypeMemo else classpathSupertypeMemo
        return memo.getOrPut(fqn) { kotlinSupertypes(fqn, HashSet()) }
    }

    private fun ownAndInherited(fqnRaw: String, typeArgs: List<TypeRef>, visited: MutableSet<String>): List<KotlinSymbol> {
        // A JVM type maps to its Kotlin classifier (`java.lang.String` → `kotlin.String`), so member
        // enumeration uses the Kotlin built-in's real members + the Kotlin-keyed supertype chain.
        val fqn = Builtins.kotlinTypeFor(fqnRaw) ?: fqnRaw
        if (!visited.add(fqn)) return emptyList()
        syntheticMembers(fqn)?.let { return it }
        model().classByFqn[fqn]?.let { rc ->
            // Bind the source class's type parameters from the receiver's type arguments (`Box<String>` →
            // `value: T` becomes `value: String`). toSymbol marks the class's `T` as a type parameter, then
            // substituteSymbol replaces it. No type arguments (a raw use) → members keep their `T` (unbound).
            val classBindings = rc.typeParameterNames.zip(typeArgs).toMap()
            val own = rc.members.map {
                val sym = toSymbol(it, fqn, rc.typeParameterNames)
                if (classBindings.isEmpty()) sym else substituteSymbol(sym, classBindings)
            }
            // An enum's `values()`/`valueOf()`/`entries` are compiler-synthesized (not written in source), so
            // the source model would otherwise miss them and `Color.values()` would flag unresolved.
            val synthetic = if (rc.isEnum) enumSyntheticMembers(fqn) else emptyList()
            val inherited = rc.superTypeTexts.mapNotNull { resolveTypeName(it, rc.ctx) }
                .flatMap { ownAndInherited(it, emptyList(), visited) }
            return own + synthetic + inherited
        }
        // `kotlin.Throwable` is a mapped built-in whose `.kotlin_builtins` shape is intentionally minimal
        // (`message`, `cause`); the rest of its API — `stackTrace`, `printStackTrace`, `localizedMessage`,
        // `addSuppressed`, `fillInStackTrace`, … — lives on `java.lang.Throwable`, and Kotlin surfaces it
        // through the mapping. Enumerate the JVM type's members WITH bean synthesis (so `getStackTrace()` →
        // the `stackTrace` property) instead of the stub, so `e.stackTrace`/`e.message` complete on any
        // `Throwable`/`Exception` (an exception caught as `java.lang.Exception` inherits this via its supertype
        // chain). Unlike `Any`/`String`, whose Kotlin built-in IS the full API, Throwable's is deliberately a
        // subset — so this is a Throwable-specific augmentation, not a general "read the java type" rule.
        if (fqn == "kotlin.Throwable") {
            typeShape(Builtins.javaTypeFor(fqn) ?: "java.lang.Throwable")
                ?.let { return membersFromShape(it, typeArgs, visited, synthesizeBeanProps = true) }
        }
        // Kotlin built-ins (List/Int/String/…): the real members, preferred over the java.* approximation.
        // These ARE Kotlin types (even though their bytecode is java.lang.String etc.), so NO synthetic Java
        // bean properties — `"".bytes` is not a Kotlin property of String.
        builtinShape(fqn)?.let { return membersFromShape(it, typeArgs, visited, synthesizeBeanProps = false) }
        // Classpath BINARY (@Metadata Kotlin or plain Java/Android): the type's shape comes from the
        // persistent `kotlin.typeShape` index when built, else a live decode/bytecode read (graceful degrade
        // while indexing). Either way the generic shape is enumerated + bound the same way. Synthesize Java bean
        // properties (getX/isX/setX → x) ONLY for a genuine Java type — not a Kotlin `@Metadata` binary
        // (`shape.isKotlin`) and not a mapped type (its `fqn` was rewritten to `kotlin.*` above).
        typeShape(fqn)?.let { return membersFromShape(it, typeArgs, visited, synthesizeBeanProps = !it.isKotlin && !fqn.startsWith("kotlin.")) }
        // Cross-language: a same-project Java SOURCE class (no .class, no metadata) — its members come from
        // the `java.membersByOwner` index (public, keyed by owner FQN).
        index?.exact<MemberValue>(MEMBERS_BY_OWNER, fqn)?.map { memberFromIndex(it) }?.toList()
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        return emptyList()
    }

    /** The compiler-synthesized static members of a (source) enum [fqn]: `values(): Array<E>`,
     *  `valueOf(value: String): E`, and `entries: List<E>` (the `EnumEntries<E>`, modeled as a `List` for member
     *  access). STATIC, so they surface on type access (`Color.values()`) and resolve like a Java enum's. */
    private fun enumSyntheticMembers(fqn: String): List<KotlinSymbol> {
        val simple = fqn.substringAfterLast('.')
        val enumType = typeByFqn(fqn)
        val owner = KotlinSymbol(simple, SymbolKind.CLASS, origin = SOURCE)
        val static = setOf(Modifier.STATIC)
        return listOf(
            KotlinSymbol("values", SymbolKind.METHOD, type = typeByFqn("kotlin.Array", listOf(enumType)),
                owner = owner, modifiers = static, origin = SOURCE, signature = "(): Array<$simple>"),
            KotlinSymbol("valueOf", SymbolKind.METHOD, type = enumType, owner = owner, modifiers = static, origin = SOURCE,
                signature = "(value: String): $simple", paramTypes = listOf(typeByFqn("kotlin.String")), paramNames = listOf("value")),
            KotlinSymbol("entries", SymbolKind.FIELD, type = typeByFqn("kotlin.collections.List", listOf(enumType)),
                owner = owner, modifiers = static, origin = SOURCE, signature = ": EnumEntries<$simple>"),
        )
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

    fun extensionsFor(fqnRaw: String, typeArgs: List<TypeRef> = emptyList(), namePrefix: String = ""): List<KotlinSymbol> {
        // A JVM receiver type maps to its Kotlin classifier so the Kotlin-keyed extensions apply (a value typed
        // `java.lang.String` still gets `String.uppercase`, a `java.util.List` gets `Iterable.map`).
        val fqn = Builtins.kotlinTypeFor(fqnRaw) ?: fqnRaw
        // Always include kotlin.Any: `T.let`/`also`/`run`/`apply`/… (unbounded type-param receiver) are keyed
        // there and apply to every instance, and a builtin supertype chain may not list Any explicitly.
        val targets = (listOf(fqn, "kotlin.Any") + kotlinSupertypesMemo(fqn)).toHashSet()
        fun matches(name: String) = namePrefix.isEmpty() || name.startsWith(namePrefix, ignoreCase = true)
        // Classpath extensions: from the persistent `kotlin.callables` index when wired (prefix-queried per
        // receiver, so the large per-receiver + `kotlin.Any` buckets stay on disk and only the prefix matches
        // load), else the in-memory scan fallback (standalone / tests with no index). Either way the prefix
        // is applied BEFORE bindExtensionReceiver, which allocates a fresh symbol per generic receiver.
        val idx = index
        val fromClasspath = if (idx != null) {
            val cacheable = classpathCacheUsable(idx) // clears memos on a build start; false while building
            if (!idx.status.ready) emptyList() // dumb: no index yet → no classpath extensions (no scan)
            else {
                // The stdlib's extensions (`Iterable.map`, `String.trim`) are in the index too — the host adds
                // the bundled stdlib jar to the index scope — so a single prefix query per receiver covers them.
                fun query(t: String) = idx.prefix<CallableShape>(KotlinCallableIndex.id, KotlinCallableIndex.extPrefix(t, namePrefix), EXTENSION_QUERY_LIMIT)
                    .map { it.value.toSymbol(this) }.toList()
                dev.ide.lang.kotlin.KotlinPerf.span("ext.index") { targets.flatMap { t ->
                    if (cacheable) classpathExtMemo.getOrPut(t + ' ' + namePrefix) { query(t) } else query(t)
                } }
            }
        } else {
            val scan = reader.scan(this)
            targets.flatMap { scan.extensionsByReceiver[it].orEmpty() }.filter { matches(it.name) }
        }
        val fromSource = model().extensions
            .filter { matches(it.name) && resolveTypeName(it.receiverText ?: return@filter false, it.ctx) in targets }
            .map { toSymbol(it, null) }
        // Drop compiler/runtime-implementation callables (e.g. `kotlin.jvm.internal.PrimitiveSpreadBuilder`'s
        // `getSize`, mis-keyed as a `kotlin.Any` extension) — never user-visible. Then bind the extension
        // receiver's type params (Iterable<T>.map, T.also) from the actual receiver.
        return (fromClasspath + fromSource)
            .filterNot { isImplementationCallable(it) }
            .map { bindExtensionReceiver(it, fqn, typeArgs) }
    }

    /** True for a callable in a Kotlin compiler/runtime *implementation* package (`kotlin.jvm.internal`,
     *  `kotlin.coroutines.jvm.internal`, `kotlin.internal`, `kotlin.reflect.jvm.internal`). These are public in
     *  bytecode (so not flagged `internal`) but are never user-facing API, so they must not appear in
     *  completion / member resolution. */
    private fun isImplementationCallable(s: KotlinSymbol): Boolean {
        val pkg = s.packageName ?: s.declaringClassFqn?.substringBeforeLast('.', "")?.takeIf { it.isNotEmpty() } ?: return false
        return IMPLEMENTATION_PACKAGES.any { pkg == it || pkg.startsWith("$it.") }
    }

    /**
     * What a bare `Type.` reference can see through [typeFqnRaw]'s companion object: the companion behaves as
     * an instance, so its own members (Compose's `Color.Black`/`White`), its inherited members, AND the
     * extensions applicable to it (`Modifier.Companion : Modifier` → `Modifier.padding`/`background`) are all
     * in scope. Empty when the type declares no companion object. Built-ins already flatten companion members
     * as statics, so this is the classpath-binary / project-source equivalent. Object methods are dropped as
     * noise (a `Type.` reference shouldn't surface `equals`/`hashCode`/`toString`).
     */
    fun companionMembersFor(typeFqnRaw: String, namePrefix: String = ""): List<KotlinSymbol> {
        val idx = index
        val fqn = Builtins.kotlinTypeFor(typeFqnRaw) ?: typeFqnRaw
        // Cache for a CLASSPATH type (session-stable); a source type — whose companion changes on edit — and a
        // mid-build index go uncached (mirrors [membersNamedForCheck]).
        if (idx == null || sourceClass(fqn) != null || !classpathCacheUsable(idx))
            return computeCompanionMembers(typeFqnRaw, namePrefix)
        return companionMembersMemo.getOrPut("$typeFqnRaw $namePrefix") { computeCompanionMembers(typeFqnRaw, namePrefix) }
    }

    private fun computeCompanionMembers(typeFqnRaw: String, namePrefix: String): List<KotlinSymbol> {
        val companionFqn = companionObjectFqn(typeFqnRaw) ?: return emptyList()
        return membersForCompletion(companionFqn, emptyList(), namePrefix)
            .filter { it.name !in OBJECT_METHODS }
    }

    /**
     * Whether [typeFqnRaw] is a Kotlin `object` singleton (`CardDefaults`, `MaterialTheme`). A bare reference
     * to it denotes the INSTANCE, so completion off `Obj.` lists its members like an instance's rather than
     * statics off a type. Checks the project source model first, then decoded classpath metadata.
     */
    fun isObject(typeFqnRaw: String): Boolean {
        val fqn = Builtins.kotlinTypeFor(typeFqnRaw) ?: typeFqnRaw
        model().classByFqn[fqn]?.let { return it.isObject && !it.isCompanion }
        // From the type-shape index (or live decode when no index is wired); null/false in dumb mode.
        return typeShape(fqn)?.isObject == true
    }

    /** The companion object's FQN (`androidx…Color.Companion`) for [typeFqnRaw], or null if it has none. */
    private fun companionObjectFqn(typeFqnRaw: String): String? {
        val fqn = Builtins.kotlinTypeFor(typeFqnRaw) ?: typeFqnRaw
        model().classByFqn[fqn]?.let { return it.companionObjectName?.let { name -> "$fqn.$name" } }
        return typeShape(fqn)?.companionObjectName?.let { "$fqn.$it" }
    }

    /**
     * The shape of a classpath BINARY type [fqn]: from the persistent `kotlin.typeShape` index when it has
     * been built (cross-launch, survives analyzer eviction), else a live decode/bytecode read so completion
     * degrades gracefully while the index is still building. Kotlin mapped types are routed to their JVM type
     * (`kotlin.collections.List` → `java.util.List`) for both, matching the live `javaShape` lookup. Returns
     * null for a non-classpath type (handled elsewhere: source model, built-ins, synthetic, Java source index).
     */
    private fun typeShape(fqn: String): TypeShape? {
        // The type-shape index is keyed by dot-form FQNs, but a binary's supertype FQN arrives in bytecode
        // `$`-nested form (e.g. `FrameLayout.LayoutParams`'s super is `android.view.ViewGroup$MarginLayoutParams`).
        // Normalize so an inherited-member walk up a nested-class chain doesn't miss the index — otherwise it
        // used to drop everything inherited through a nested supertype (e.g. `FrameLayout.LayoutParams.MATCH_PARENT`,
        // which is declared two levels up on `ViewGroup.LayoutParams`). The live reader path already tolerates
        // `$` via classBytes's `.`↔`$` retry; only the index's exact-key lookup needs the dot form.
        val dotFqn = if ('$' in fqn) fqn.replace('$', '.') else fqn
        val lookupFqn = Builtins.javaTypeFor(dotFqn) ?: dotFqn
        index?.let { idx ->
            // The persistent `kotlin.typeShape` index is the SOLE source for classpath binaries once wired —
            // no live decode/bytecode read, ever (that's the index's job; it decoded these at build time).
            // Empty until the index is ready ("dumb mode"); a genuine post-ready miss simply doesn't resolve
            // until the next re-index rather than triggering a jar read.
            if (!idx.status.ready) return null
            return idx.exact<TypeShape>(TYPE_SHAPE, lookupFqn).firstOrNull()?.withContext(this)
        }
        // No index wired (standalone / tests): live decode/bytecode is the only classpath source.
        reader.decoded(dotFqn, this)?.let { return TypeShape.of(it, this) }
        return javaShape(lookupFqn)?.let { TypeShape.of(it) }
    }

    /**
     * The shape of a Kotlin BUILT-IN type [fqn] (`kotlin.collections.List`, `kotlin.Int`, …): from the
     * `kotlin.builtins` index when wired (built once from the stdlib's `.kotlin_builtins`), else the live
     * [BuiltinsReader] decode (standalone / tests). Null in dumb mode (index not ready) and for a non-built-in.
     * Like [typeShape], the index is the sole source once wired — no live `.kotlin_builtins` read.
     */
    private fun builtinShape(fqn: String): TypeShape? {
        index?.let { idx ->
            if (!idx.status.ready) return null
            return idx.exact<TypeShape>(BUILTINS, fqn).firstOrNull()?.withContext(this)
        }
        return builtins.lookup(fqn, this)
    }

    /** Enumerate one type level from its [shape]: own members (with the receiver's [typeArgs] bound into their
     *  generic signatures) plus members inherited from each generic supertype (the binding substituted into the
     *  supertype's arguments first, so `List<String> : Collection<String>` keeps `stream()` typed). */
    /**
     * Splice source-recovered parameter NAMES + javadoc/KDoc into a binary member symbol when sources are
     * attached (a no-op when none, or for source symbols). Java bytecode strips parameter names (only `p0`/`p1`
     * survive) and neither bytecode nor `@Metadata` carries doc comments, so the editor would otherwise show
     * `p0: View` and no docs; this fills the real names (rebuilding the display signature) and the doc text.
     * Provider lookups are cached per-type on the provider side, so calling per-member is cheap.
     */
    private fun enrich(s: KotlinSymbol): KotlinSymbol {
        if (sourceDoc === dev.ide.lang.resolve.SourceDocProvider.NONE || s.origin.fromSource) return s
        if (s.kind != SymbolKind.METHOD && s.kind != SymbolKind.CONSTRUCTOR) return s
        val owner = s.declaringClassFqn ?: return s
        val md = sourceDoc.method(owner, s.name, s.paramTypes.size) ?: return s
        val needNames = s.paramNames.isEmpty() || s.paramNames.all { isSyntheticParamName(it) }
        val names = if (needNames && md.names.size == s.paramTypes.size) md.names else s.paramNames
        val sig = if (names !== s.paramNames) rewriteParamNames(s.signature, names) else s.signature
        val doc = s.documentation() ?: md.doc
        return if (names === s.paramNames && doc == s.documentation()) s
        else s.withSourceDoc(names = names, sig = sig, docText = doc)
    }

    /** `p0`/`p1`/… — the placeholder names Java bytecode surfaces when real ones were stripped. */
    private fun isSyntheticParamName(n: String): Boolean = n.length >= 2 && n[0] == 'p' && n.drop(1).all { it.isDigit() }

    /** Swap the parameter NAMES in a `(p0: View, p1: Int): T` display for real ones, keeping the rendered type
     *  tokens (and the return type) byte-for-byte. Left unchanged on an arity mismatch or a malformed signature. */
    private fun rewriteParamNames(signature: String?, names: List<String>): String? {
        if (signature == null) return null
        val open = signature.indexOf('('); val close = signature.indexOf(')')
        if (open < 0 || close <= open) return signature
        val inner = signature.substring(open + 1, close)
        if (inner.isBlank()) return signature
        val parts = inner.split(", ")
        if (parts.size != names.size) return signature
        val rebuilt = parts.mapIndexed { i, p ->
            val colon = p.indexOf(": ")
            if (colon < 0) p else names[i] + p.substring(colon)
        }.joinToString(", ")
        return signature.substring(0, open + 1) + rebuilt + signature.substring(close)
    }

    private fun membersFromShape(shape: TypeShape, typeArgs: List<TypeRef>, visited: MutableSet<String>, synthesizeBeanProps: Boolean): List<KotlinSymbol> {
        val bindings = classBindings(shape, typeArgs)
        // A member that re-declares a class type-parameter name (a static `<E> of(E)` on `List<E>`) shadows it,
        // so its own parameters are excluded from the class binding (bound later from the call site).
        val own = shape.members.map { m ->
            enrich(substituteSymbol(m, if (m.typeParameters.isEmpty()) bindings else bindings - m.typeParameters.toSet()))
        }
        // Kotlin exposes a Java class's bean accessors (`getText`/`isVisible`/`setText`) as synthetic properties
        // (`view.text`). The accessor methods stay too (both forms work; [KotlinSemanticChecks.usePropertyAccess]
        // nudges call sites toward the property). Gated by the caller to a GENUINE Java type — never a Kotlin
        // built-in or mapped type (else `"".bytes` etc. would appear, and every builtin enumeration would pay it).
        val synthetic = if (synthesizeBeanProps) syntheticAccessorProperties(own) else emptyList()
        val inherited = shape.supertypes.flatMap { st ->
            val sub = substitute(st, bindings) as? KotlinType ?: return@flatMap emptyList()
            ownAndInherited(sub.qualifiedName, sub.typeArguments, visited)
        }
        return own + synthetic + inherited
    }

    /**
     * The synthetic Kotlin properties a Java type exposes from its bean accessors (Kotlin's
     * `SyntheticJavaPropertyDescriptor`): a `getX()`/`isX()` getter (no parameters) becomes a readable property,
     * a `setX(v)` setter (one parameter) a writable one. The property name follows Kotlin's rule — `getText` →
     * `text`, `isVisible` → `isVisible` (the `is` is kept), `getURL` → `URL` (no decap when the 2nd char is
     * upper). A getter defines the type (the read side); a lone setter (no matching getter) defines a
     * write-only property. Skips names that already exist as a real field and static accessors.
     */
    private fun syntheticAccessorProperties(methods: List<KotlinSymbol>): List<KotlinSymbol> {
        val existingFields = methods.filterTo(HashSet()) { it.kind == SymbolKind.FIELD }.mapTo(HashSet()) { it.name }
        val getters = LinkedHashMap<String, KotlinSymbol>() // propName -> the getter method
        val setterNames = HashSet<String>()                  // candidate property names from setX (decap form)
        for (m in methods) {
            if (m.kind != SymbolKind.METHOD || Modifier.STATIC in m.modifiers) continue
            val n = m.name
            when {
                n.length > 3 && n.startsWith("get") && n[3].isUpperCase() && m.paramTypes.isEmpty() &&
                    (m.type as? KotlinType)?.qualifiedName != "kotlin.Unit" ->
                    getters.putIfAbsent(decapitalizeAccessor(n.substring(3)), m)
                n.length > 2 && n.startsWith("is") && n[2].isUpperCase() && m.paramTypes.isEmpty() &&
                    (m.type as? KotlinType)?.qualifiedName == "kotlin.Boolean" ->
                    getters.putIfAbsent(n, m) // isVisible -> property `isVisible`
                n.length > 3 && n.startsWith("set") && n[3].isUpperCase() && m.paramTypes.size == 1 ->
                    setterNames += decapitalizeAccessor(n.substring(3))
            }
        }
        val out = ArrayList<KotlinSymbol>()
        for ((prop, getter) in getters) {
            if (prop in existingFields) continue
            out += KotlinSymbol(prop, SymbolKind.FIELD, type = getter.type, origin = BINARY,
                signature = (getter.type as? KotlinType)?.let { ": ${it.qualifiedName.substringAfterLast('.')}" },
                declaringClassFqn = getter.declaringClassFqn)
        }
        // A write-only property (a `setX` with no `getX`/`isX` getter) — typed from the setter parameter.
        for (m in methods) {
            if (m.kind != SymbolKind.METHOD || Modifier.STATIC in m.modifiers) continue
            if (!(m.name.length > 3 && m.name.startsWith("set") && m.name[3].isUpperCase() && m.paramTypes.size == 1)) continue
            val prop = decapitalizeAccessor(m.name.substring(3))
            // Skip if a getter already produced it (decap form OR the `is<Suffix>` form), or a real field exists.
            if (prop in getters || ("is" + m.name.substring(3)) in getters || prop in existingFields) continue
            out += KotlinSymbol(prop, SymbolKind.FIELD, type = m.paramTypes.firstOrNull(), origin = BINARY,
                signature = (m.paramTypes.firstOrNull() as? KotlinType)?.let { ": ${it.qualifiedName.substringAfterLast('.')}" },
                declaringClassFqn = m.declaringClassFqn)
        }
        return out
    }

    /** Kotlin's accessor-name decapitalization: drop the `get`/`set` prefix already removed, then lowercase the
     *  first char UNLESS the second is also uppercase (`URL` stays `URL`, `Text` → `text`). */
    private fun decapitalizeAccessor(suffix: String): String =
        if (suffix.length > 1 && suffix[1].isUpperCase()) suffix
        else suffix.replaceFirstChar { it.lowercaseChar() }

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
        if (bindings.isEmpty()) return ext
        // `T : R` propagation: a receiver-bound param `T` whose declared upper bound is a sibling param `R`
        // makes R a lower bound = T's binding (`Result<String>.getOrElse`, T : R, T = String ⇒ R ≥ String). The
        // call site (typeOfCall) widens R with the lambda result, so `getOrElse { null }` resolves to String?.
        val lowerBounds = HashMap<String, TypeRef>()
        for ((p, bound) in bindings) {
            val sibling = ext.typeParameters.indexOf(p).takeIf { it >= 0 }?.let { ext.typeParamBoundNames.getOrNull(it) }
            if (sibling != null) lowerBounds[sibling] = bound
        }
        val sub = substituteSymbol(ext, bindings)
        return if (lowerBounds.isEmpty()) sub else sub.withTypeParamLowerBounds(lowerBounds)
    }

    /** Apply type-parameter [bindings] to a symbol's return/param/receiver-arg types. */
    fun substituteSymbol(s: KotlinSymbol, bindings: Map<String, TypeRef>): KotlinSymbol {
        if (bindings.isEmpty()) return s
        return KotlinSymbol(
            name = s.name, kind = s.kind, type = s.type?.let { substitute(it, bindings) },
            owner = s.owner, modifiers = s.modifiers, origin = s.origin,
            receiverTypeFqn = s.receiverTypeFqn, signature = s.signature, typeParameters = s.typeParameters,
            typeParameterBounds = s.typeParameterBounds,
            typeParamBoundNames = s.typeParamBoundNames,
            typeParamLowerBounds = s.typeParamLowerBounds.mapValues { substitute(it.value, bindings) },
            paramTypes = s.paramTypes.map { it?.let { p -> substitute(p, bindings) } },
            paramNames = s.paramNames,
            receiverTypeArgs = s.receiverTypeArgs.map { substitute(it, bindings) },
            receiverTypeParam = s.receiverTypeParam,
            packageName = s.packageName,
            declaringClassFqn = s.declaringClassFqn,
            isInternal = s.isInternal,
            isComposable = s.isComposable,
            isInline = s.isInline,
            isSuspend = s.isSuspend,
            isDeprecated = s.isDeprecated,
            varargParamIndex = s.varargParamIndex,
            paramHasDefault = s.paramHasDefault,
            declarationNode = s.declaration(), doc = s.documentation(),
        )
    }

    /**
     * Mark any reference to one of the declaration's own type-parameter [names] in [type] (recursing into type
     * arguments) as `isTypeParameter`. A type parsed from SOURCE text (`(T) -> Any`, `List<T>`) doesn't know
     * `T` is a type parameter — `resolveTypeName` returns it verbatim — so without this, generic inference
     * (`unify`/`substitute`) can't bind it and `it` in `key = { it.id }` types as a bogus concrete `T`.
     */
    private fun markTypeParameters(type: KotlinType?, names: Set<String>): KotlinType? {
        if (type == null || names.isEmpty()) return type
        val args = type.typeArguments.map { (it as? KotlinType)?.let { a -> markTypeParameters(a, names) } ?: it }
        val isTp = type.isTypeParameter || type.qualifiedName in names
        return KotlinType(type.qualifiedName, args, type.nullable, this, isTp, type.isExtensionFunctionType, type.isComposable)
    }

    /** Recursively replace type-parameter references in [type] per [bindings]. */
    fun substitute(type: TypeRef, bindings: Map<String, TypeRef>): TypeRef {
        if (bindings.isEmpty()) return type
        val kt = type as? KotlinType ?: return type
        if (kt.isTypeParameter) return bindings[kt.qualifiedName] ?: kt
        if (kt.typeArguments.isEmpty()) return kt
        return KotlinType(kt.qualifiedName, kt.typeArguments.map { substitute(it, bindings) }, kt.nullable, this, kt.isTypeParameter, kt.isExtensionFunctionType, kt.isComposable)
    }

    private fun kotlinSupertypes(fqnRaw: String, visited: MutableSet<String>): List<String> {
        val fqn = Builtins.kotlinTypeFor(fqnRaw) ?: fqnRaw // walk the Kotlin hierarchy for a JVM type too
        if (!visited.add(fqn)) return emptyList()
        val direct = LinkedHashSet<String>()
        Builtins.builtinSupertypes(fqn).forEach { direct += it }
        builtinShape(fqn)?.supertypes?.forEach { (it as? KotlinType)?.let { k -> direct += k.qualifiedName } }
        model().classByFqn[fqn]?.superTypeTexts?.forEach { t -> resolveTypeName(t, model().classByFqn[fqn]!!.ctx)?.let { direct += it } }
        // Classpath supertypes (@Metadata Kotlin AND plain Java bytecode) via the type-shape index, or a live
        // decode when no index is wired — null in dumb mode, so the chain is empty until the index is ready.
        typeShape(fqn)?.supertypes?.forEach { (it as? KotlinType)?.let { k -> direct += k.qualifiedName } }
        val out = LinkedHashSet(direct)
        direct.forEach { out += kotlinSupertypes(it, visited) }
        return out.toList()
    }

    private fun javaShape(fqn: String): JavaShape? =
        javaShapeCache.getOrPut(fqn) {
            Holder(reader.classBytes(fqn)?.let { JavaBytecode.read(it, this) })
        }.value

    // --- scope feeds: top-level callables + type names ---

    /**
     * Top-level callables visible by simple name (source + stdlib/classpath), filtered to those starting with
     * [prefix] (empty = all), for NAME completion. The prefix is pushed down so a keystroke doesn't flatten the
     * whole classpath's top-level universe (thousands of decls with Compose/AndroidX) only to drop all but a
     * few downstream — it walks the by-name map and keeps the matching buckets.
     */
    fun topLevelCallables(prefix: String = ""): List<KotlinSymbol> {
        val src = model().topLevel
            .filter { prefix.isEmpty() || it.name.startsWith(prefix, ignoreCase = true) }
            .map { toSymbol(it, null) }
        val idx = index
        val cp = if (idx != null) {
            if (!idx.status.ready) emptyList() // dumb: no index yet → no classpath/stdlib top-level callables
            else {
                // Prefix-query the persistent index; an empty prefix (the explicit "show all" / resolution path,
                // not per-keystroke) is uncapped so it stays complete, while a typed prefix is bounded by matches.
                val limit = if (prefix.isEmpty()) Int.MAX_VALUE else CALLABLE_QUERY_LIMIT
                // The stdlib's top-level callables (`println`, `listOf`) are in the index too (the host adds the
                // bundled stdlib jar to the index scope), so the prefix query covers them.
                idx.prefix<CallableShape>(KotlinCallableIndex.id, KotlinCallableIndex.topKey(prefix), limit)
                    .map { it.value.toSymbol(this) }.toList()
            }
        } else {
            val byName = reader.scan(this).topLevelByName
            if (prefix.isEmpty()) byName.values.flatten()
            else {
                val acc = ArrayList<KotlinSymbol>()
                for ((name, syms) in byName) if (name.startsWith(prefix, ignoreCase = true)) acc += syms
                acc
            }
        }
        return src + cp
    }

    /**
     * Fully-qualified names worth importing for an unresolved simple [name]: top-level callables
     * (functions/properties — `androidx.compose.runtime.remember`, `…mutableStateOf`) plus types
     * (classes/objects/interfaces). De-duplicated and sorted; powers the "Import …" quick-fix on a
     * `kt.unresolved` reference. Best-effort — a candidate without a derivable package is dropped.
     */
    fun importCandidates(name: String): List<String> {
        if (name.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        topLevelByName(name).forEach { s ->
            val pkg = s.packageName ?: s.declaringClassFqn?.substringBeforeLast('.', "")?.ifEmpty { null }
            if (pkg != null) out += "$pkg.$name"
        }
        typeNamesByPrefix(name).forEach { s ->
            val fqn = s.type?.qualifiedName
            if (fqn != null && '.' in fqn && fqn.substringAfterLast('.') == name) out += fqn
        }
        return out.sorted()
    }

    fun topLevelByName(name: String): List<KotlinSymbol> {
        val src = model().topLevel.filter { it.name == name }.map { toSymbol(it, null) }
        val idx = index
        val cp = if (idx != null) {
            if (!idx.status.ready) emptyList() // dumb: no index yet → no classpath/stdlib callables (no scan)
            // Index only — the stdlib (`println`, `listOf`) is indexed alongside every other library jar.
            else idx.exact<CallableShape>(KotlinCallableIndex.id, KotlinCallableIndex.topKey(name)).map { it.toSymbol(this) }.toList()
        } else reader.scan(this).topLevelByName[name].orEmpty()
        return src + cp
    }

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
                if (!simple.startsWith(prefix, ignoreCase = true)) return@forEach
                if (v.origin != IndexOrigin.SOURCE && isKotlinFacade(v.fqn, simple)) return@forEach
                out.getOrPut(v.fqn) { KotlinSymbol(simple, classNameKind(v.kind), typeByFqn(v.fqn), origin = BINARY) }
            }
        }
        // Same-project source classes declared in this package (the index lags the live buffer).
        model().classByFqn.values
            .filter { it.fqn.substringBeforeLast('.', "") == packageFqn && it.simpleName.startsWith(prefix, ignoreCase = true) }
            .forEach { out.getOrPut(it.fqn) { KotlinSymbol(it.simpleName, SymbolKind.CLASS, typeByFqn(it.fqn), origin = SOURCE, declarationNode = it.node) } }
        return out.values.take(limit)
    }

    /**
     * Whether a BINARY classpath class [fqn] is a Kotlin file/multi-file **facade** (`FooKt`, `StringsKt`,
     * `StringsKt__StringsJVMKt`) — the synthetic class top-level functions/properties compile into. It is not a
     * referenceable type, so it must never surface as a class-completion candidate. The bytecode-name-only
     * `java.classNames` index can't tell (it reads only the public flag), but `kotlin.typeShape` holds every
     * real classpath type and excludes facades by construction — so a `…Kt`-named binary class with no shape
     * entry is a facade. A persisted index lookup (no live decode), gated by the naming convention so only the
     * handful of suspects pay it. (Caller restricts this to BINARY hits: a project SOURCE class also has no
     * shape, but isn't a facade.)
     */
    private fun isKotlinFacade(fqn: String, simpleName: String): Boolean {
        if (!simpleName.endsWith("Kt") && "__" !in simpleName) return false
        return index?.exact<TypeShape>(TYPE_SHAPE, fqn)?.firstOrNull() == null
    }

    /** Type-name candidates by prefix: source classes + defaults + the classpath `classNames` index. */
    fun typeNamesByPrefix(prefix: String, limit: Int = 100): List<KotlinSymbol> {
        val out = LinkedHashMap<String, KotlinSymbol>()
        model().classByFqn.values.filter { !it.isCompanion && it.simpleName.startsWith(prefix, ignoreCase = true) }
            .forEach { out[it.fqn] = KotlinSymbol(it.simpleName, SymbolKind.CLASS, typeByFqn(it.fqn), origin = SOURCE, declarationNode = it.node) }
        // Top-level synthetic classes (Android `R`/`BuildConfig`, …) complete by simple name like any type.
        synthetic().let { idx ->
            idx.topLevelFqns.filter { it.substringAfterLast('.').startsWith(prefix, ignoreCase = true) }
                .forEach { fqn -> out.getOrPut(fqn) { KotlinSymbol(fqn.substringAfterLast('.'), syntheticKind(idx.byFqn.getValue(fqn).kind), typeByFqn(fqn), origin = SOURCE) } }
        }
        Builtins.DEFAULT_SIMPLE_TYPES.filter { it.key.startsWith(prefix, ignoreCase = true) }
            .forEach { (s, fqn) -> out.getOrPut(fqn) { KotlinSymbol(s, SymbolKind.CLASS, typeByFqn(fqn), origin = BINARY) } }
        index?.prefix<ClassNameValue>(CLASS_NAMES, prefix, limit)?.forEach { hit ->
            val v = hit.value
            val simple = v.fqn.substringAfterLast('.')
            if (v.origin != IndexOrigin.SOURCE && isKotlinFacade(v.fqn, simple)) return@forEach
            out.getOrPut(v.fqn) {
                KotlinSymbol(simple, classNameKind(v.kind), typeByFqn(v.fqn), origin = BINARY)
            }
        }
        return out.values.take(limit)
    }

    /** The constructors declared by a classpath type [fqn] (own shape only — constructors aren't inherited),
     *  for validating a constructor call's arguments. Served from the `kotlin.typeShape` index when built. */
    fun constructorsOf(fqn: String): List<KotlinSymbol> =
        typeShape(fqn)?.members?.filter { it.kind == SymbolKind.CONSTRUCTOR } ?: emptyList()

    /** A class's own type-parameter names (`Box<T>` → `["T"]`), from the project source model or a classpath
     *  binary's shape. Empty for a non-generic or unknown type. Drives constructor type-argument inference. */
    fun classTypeParameters(fqn: String): List<String> {
        model().classByFqn[fqn]?.let { return it.typeParameterNames }
        return typeShape(fqn)?.typeParameters ?: emptyList()
    }

    /**
     * The parameter types of the constructor of [fqn] whose arity accepts [argCount] (the unique one when a
     * source class has several), with the CLASS's type parameters marked so a call site can bind them from the
     * argument types (`Box(value: T)` → its `T` is bindable). Null when [fqn] has no such constructor or its
     * shape is unknown. Source classes come from the primary/secondary constructors in the model; binary
     * classes from the type-shape index (whose constructor param types already carry the marked parameters).
     */
    fun constructorParamTypes(fqn: String, argCount: Int): List<TypeRef?>? {
        model().classByFqn[fqn]?.let { rc ->
            if (rc.typeParameterNames.isEmpty()) return null // non-generic: nothing to infer
            val tps = rc.typeParameterNames.toHashSet()
            // Prefer the constructor whose required..max arity accepts the call (defaults make the upper end), then
            // an exact match, then the first — positional unification only reads the supplied arguments anyway.
            fun accepts(c: RawCallable) = argCount in c.paramTexts.indices.count { c.paramHasDefault.getOrElse(it) { false } }
                .let { defaults -> (c.paramTexts.size - defaults)..c.paramTexts.size }
            val ctor = rc.constructors.firstOrNull(::accepts)
                ?: rc.constructors.firstOrNull { it.paramTexts.size == argCount }
                ?: rc.constructors.firstOrNull()
                ?: return if (argCount == 0) emptyList() else null
            return ctor.paramTexts.map { (_, t) -> markTypeParameters(typeFromText(t, ctor.ctx), tps) }
        }
        val ctors = constructorsOf(fqn)
        val ctor = ctors.firstOrNull { it.paramTypes.size == argCount } ?: ctors.firstOrNull() ?: return null
        return ctor.paramTypes
    }

    /** The enum constants of [fqn] — source `enum class` entries and binary enums (`@Metadata` Kotlin enums
     *  surface entries as ENUM_CONSTANT members; Java enums as static fields typed to the enum). Empty when
     *  [fqn] is not an enum. Powers expected-type completion offering `Enum.CONSTANT`. */
    fun enumConstantsOf(fqn: String): List<KotlinSymbol> {
        model().classByFqn[fqn]?.let { rc ->
            if (rc.enumEntries.isEmpty()) return emptyList()
            return rc.enumEntries.map {
                KotlinSymbol(it, SymbolKind.ENUM_CONSTANT, type = typeByFqn(fqn), modifiers = setOf(Modifier.STATIC), origin = SOURCE)
            }
        }
        return membersOf(fqn, emptyList(), null).filterIsInstance<KotlinSymbol>().filter {
            it.kind == SymbolKind.ENUM_CONSTANT ||
                (it.kind == SymbolKind.FIELD && Modifier.STATIC in it.modifiers && (it.type as? KotlinType)?.qualifiedName == fqn)
        }
    }

    /**
     * The DIRECT subclasses (FQNs) of the SOURCE `sealed` class/interface [fqn] — gathered across the whole
     * project model, so it is COMPLETE (a sealed type's subclasses must be in the same module, hence all in the
     * source model). Returns null when [fqn] isn't a known source sealed type (a library sealed class would need
     * its metadata `sealedSubclasses`, which isn't decoded — the caller then backs off). Drives cross-file
     * `when`-exhaustiveness.
     */
    fun sealedSubclassesOf(fqn: String): List<String>? {
        val m = model()
        val sealed = m.classByFqn[fqn]
        if (sealed != null) {
            if (!sealed.isSealed) return null
            val out = ArrayList<String>()
            for (rc in m.classByFqn.values) {
                if (rc.fqn == fqn) continue
                if (rc.superTypeTexts.any { superHeadFqn(it, rc.ctx) == fqn }) out += rc.fqn
            }
            return out
        }
        // A LIBRARY (classpath) sealed type: its direct subclasses come from the `@Metadata` `sealedSubclasses`
        // (decoded into the type shape). Non-empty ⟹ it is sealed (only sealed types carry the list).
        return typeShape(fqn)?.sealedSubclasses?.takeIf { it.isNotEmpty() }
    }

    /** The resolved classifier FQN of a supertype-list entry's text (`State`, `State<T>`, `State()`,
     *  `pkg.State`); falls back to the raw head when it can't be resolved (so an already-qualified text still
     *  compares equal to a candidate FQN, and an unresolvable simple name just won't match → safe miss). */
    private fun superHeadFqn(superText: String, ctx: FileContext?): String? {
        val head = superText.substringBefore('<').substringBefore('(').trim()
        return resolveTypeName(head, ctx) ?: head
    }

    /** Whether [fqn] is a Kotlin BINARY (`@Metadata`) class. Its constructors may have default arguments that
     *  the metadata decode doesn't surface, so an argument-count check against them would be unsound. */
    fun hasKotlinMetadata(fqn: String): Boolean = typeShape(fqn)?.isKotlin == true

    /** A source class's declaration node, for go-to-definition on a type. */
    fun classDeclaration(fqn: String): DomNode? = model().classByFqn[fqn]?.node

    fun isSourceClass(fqn: String): Boolean = fqn in model().classByFqn
    fun sourceClass(fqn: String): RawClass? = model().classByFqn[fqn]

    /**
     * Whether [fqn] is a type that CANNOT be created with a constructor call (`Foo()`) — an `interface` or an
     * `abstract`/`sealed` class. Returns `null` when it can't be decided (the type doesn't resolve in any
     * source/classpath/built-in source, or the classpath index is in dumb mode), so the caller MUST back off
     * rather than flag — the abstract-instantiation check never fires on an unknown type. Project source first
     * (a freshly-edited class resolves before the index catches up), then classpath binaries + built-ins.
     */
    fun isNonInstantiableType(fqn: String): Boolean? {
        sourceClass(fqn)?.let { return it.isInterface || it.isAbstract }
        (typeShape(fqn) ?: builtinShape(fqn))?.let { return it.isInterface || it.isAbstract }
        return null
    }

    /** Whether [fqn] declares a companion object — where an `operator fun invoke` could make `Type()` a valid
     *  CALL rather than a (forbidden) constructor invocation. The abstract-instantiation check backs off when
     *  this is true, so it never false-positives on the companion-invoke factory pattern. */
    fun typeHasCompanionObject(fqn: String): Boolean =
        sourceClass(fqn)?.companionObjectName != null || (typeShape(fqn) ?: builtinShape(fqn))?.companionObjectName != null

    /** Whether [fqn] is a plain JAVA type (classpath binary, not a Kotlin `@Metadata` class, not a project
     *  Kotlin source class, not a mapped built-in). Java types expose synthetic bean properties + drive the
     *  use-property-access inspection; a Kotlin `fun getX()` is a real function, so its type is excluded. */
    fun isJavaType(fqn: String): Boolean {
        if (fqn.startsWith("kotlin.") || Builtins.kotlinTypeFor(fqn) != null) return false
        if (isSourceClass(fqn) || hasKotlinMetadata(fqn)) return false
        return isKnownType(fqn)
    }

    /** Whether [fqn] names a real class/object (source, a default stdlib type, or on the classpath). Used to
     *  tell a TYPE receiver (`String.`, `Locale.`) from a value receiver (`listOf("").`). */
    fun isKnownType(fqn: String): Boolean {
        if (synthetic().byFqn.containsKey(fqn)) return true
        if (isSourceClass(fqn)) return true
        if (fqn in Builtins.DEFAULT_SIMPLE_TYPES.values) return true
        if (builtinShape(fqn) != null) return true
        // Classpath BINARY existence (type-shape index / live read) — session-stable, so memoized per fqn.
        if (classpathTypeExists(fqn)) return true
        // A project Java SOURCE class (no `.class` on disk while editing) — known via the index, SOURCE origin.
        // Left UNCACHED: a class added mid-edit must resolve without waiting for an index rebuild.
        return index?.exact<ClassNameValue>(CLASS_NAMES, fqn.substringAfterLast('.'))
            ?.any { it.fqn == fqn && it.origin == IndexOrigin.SOURCE } == true
    }

    /** Classpath-binary existence of [fqn] (the [typeShape] presence test), memoized for a wired+ready index. */
    private fun classpathTypeExists(fqn: String): Boolean {
        val idx = index ?: return typeShape(fqn) != null
        if (!classpathCacheUsable(idx)) return typeShape(fqn) != null
        return classpathTypeExistsMemo.getOrPut(fqn) { typeShape(fqn) != null }
    }

    /** Whether [simpleName] is a `typealias` declared anywhere in the project source — the unresolved-TYPE
     *  diagnostic backs off on these (the source model resolves classes, not aliases, so an alias use would
     *  otherwise be a false positive). Same-file aliases are also checked against the live buffer at the call site. */
    fun isProjectTypeAlias(simpleName: String): Boolean = simpleName in model().typeAliasNames

    // --- raw -> neutral symbol ---

    private fun toSymbol(rc: RawCallable, ownerFqn: String?, ownerTypeParams: List<String> = emptyList()): KotlinSymbol {
        // Mark BOTH the callable's own type parameters AND the enclosing class's (`class Box<T> { val value: T }`):
        // a member type that references the class's `T` must be a type parameter so a `Box<String>` receiver can
        // bind it (see [ownAndInherited]'s source-class substitution). The callable's own params win on a clash.
        val tps = (rc.typeParameterNames + ownerTypeParams).toHashSet()
        val type = markTypeParameters(
            typeFromText(rc.returnText, rc.ctx)
                ?: inferInitializerType(rc.initializerText, rc.ctx)
                ?: inferReturnFromBody(rc),
            tps,
        )
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
            typeParameters = rc.typeParameterNames,
            paramTypes = if (rc.isFunction) rc.paramTexts.map { (_, t) -> markTypeParameters(typeFromText(t, rc.ctx), tps) } else emptyList(),
            paramNames = if (rc.isFunction) rc.paramTexts.map { (n, _) -> n } else emptyList(),
            modifiers = when (rc.visibility) {
                "private" -> setOf(dev.ide.lang.resolve.Modifier.PRIVATE)
                "protected" -> setOf(dev.ide.lang.resolve.Modifier.PROTECTED)
                else -> emptySet()
            } + if (rc.isAbstract) setOf(dev.ide.lang.resolve.Modifier.ABSTRACT) else emptySet(),
            isInternal = rc.visibility == "internal",
            isComposable = rc.isComposable,
            isInline = rc.isInline,
            isSuspend = rc.isSuspend,
            isDeprecated = rc.isDeprecated,
            varargParamIndex = if (rc.isFunction) rc.varargParamIndex else -1,
            paramHasDefault = if (rc.isFunction) rc.paramHasDefault else emptyList(),
            // Top-level callables (no owner) carry their package for import-visibility; members don't.
            packageName = if (ownerFqn == null) rc.ctx.packageName.ifEmpty { null } else null,
            declarationNode = rc.node,
        )
    }

    /**
     * The return type of an expression-body declaration with no explicit type (`fun String.trimmed() =
     * this.trim().toString()`, `val x = something()`) that [inferInitializerType]'s text heuristic couldn't
     * type — resolved by running the real [KotlinResolver] over the body PSI. This is what lets a chain off
     * such a function (`s.trimmed().uppercase()`) resolve and the function show a return type in the editor,
     * instead of degrading to no-type. Only EXPRESSION bodies are inferred (a block body's return type needs
     * return-statement analysis Kotlin requires to be declared anyway); non-source nodes are skipped. The
     * result is memoized; a re-entrant request (a body whose own type is needed to type it — self/mutual
     * recursion, which Kotlin rejects) returns null to break the cycle without caching a misleading value.
     */
    private fun inferReturnFromBody(rc: RawCallable): KotlinType? {
        val dom = rc.node as? dev.ide.lang.kotlin.parse.KotlinDomNode ?: return null
        val body: org.jetbrains.kotlin.psi.KtExpression = when (val psi = dom.psi) {
            is org.jetbrains.kotlin.psi.KtNamedFunction -> if (psi.hasBlockBody()) return null else psi.bodyExpression ?: return null
            is org.jetbrains.kotlin.psi.KtProperty -> psi.initializer ?: return null
            else -> return null
        }
        inferredBodyTypeMemo[rc]?.let { return it.value }
        val guard = inferringBody.get()
        if (!guard.add(rc)) return null // re-entrant (self/mutual recursion) → break the cycle, don't cache
        val result = try {
            dev.ide.lang.kotlin.resolve.KotlinResolver(dom.owner.ktFile, dom.owner, this).inferType(body)
        } catch (t: Throwable) {
            null
        } finally {
            guard.remove(rc)
        }
        inferredBodyTypeMemo[rc] = Holder(result)
        return result
    }

    /**
     * Cheap, SOUND typing of an initializer — the fast path before the resolver-based [inferReturnFromBody].
     * Only returns a type it is confident about: a constructor call `Foo(...)` whose name is a KNOWN type, or a
     * pure literal that IS the whole initializer. Anything else (`this.trim()`, `make()`, `"x".length`, `a.b()`)
     * returns null so real resolution runs — earlier this branch returned a bogus type for any `name(...)` text
     * (e.g. `this.trim` parsed as a type), which masked the real return type.
     */
    private fun inferInitializerType(text: String?, ctx: FileContext?): KotlinType? {
        val e = text?.trim() ?: return null
        return when {
            // `Foo(...)` / `pkg.Foo(...)`: only when the callee before `(` resolves to a real type — not a plain
            // function call (`make()`) or a member call on a value (`this.trim()`), which aren't constructors.
            e.endsWith(")") && e.first().isLetter() ->
                e.substringBefore('(').takeIf { it.isNotEmpty() }
                    ?.let { typeFromText(it, ctx) }?.takeIf { isKnownType(it.qualifiedName) }
            // A pure string literal/template that is the ENTIRE initializer (`= "x"`, `= "v=$v"`) — not `"x".y`
            // (doesn't end in `"`) nor `"a" + "b"` (an inner quote), which would mistype as String.
            e.startsWith("\"") && e.indexOf('"', 1) == e.length - 1 -> typeByFqn("kotlin.String")
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
        fileCache.clear()
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
        // Kotlin compiler/runtime implementation packages: public in bytecode but never user-facing API, so
        // their callables (e.g. `kotlin.jvm.internal.PrimitiveSpreadBuilder.getSize`) are hidden from completion.
        private val IMPLEMENTATION_PACKAGES = listOf(
            "kotlin.jvm.internal", "kotlin.coroutines.jvm.internal", "kotlin.internal", "kotlin.reflect.jvm.internal",
        )
        // Per-receiver extension query cap and per-prefix top-level cap (the consumer ranks + takes ~100).
        // Generous so a non-trivial bucket isn't truncated below what ranking needs; a prefix query is bounded
        // by matches anyway. The empty-prefix top-level path is uncapped (see topLevelCallables).
        private const val EXTENSION_QUERY_LIMIT = 2000
        private const val CALLABLE_QUERY_LIMIT = 2000
        private val CLASS_NAMES = IndexId("java.classNames")
        private val TYPE_SHAPE = IndexId("kotlin.typeShape")
        private val BUILTINS = IndexId("kotlin.builtins")
        private val PACKAGES = IndexId("java.packages")
        private val PACKAGE_TYPES = IndexId("java.packageTypes")
        private val MEMBERS_BY_OWNER = IndexId("java.membersByOwner")
        private val SOURCE = SymbolOrigin(fromSource = true, file = null)
        private val BINARY = SymbolOrigin(fromSource = false, file = null)
    }
}
