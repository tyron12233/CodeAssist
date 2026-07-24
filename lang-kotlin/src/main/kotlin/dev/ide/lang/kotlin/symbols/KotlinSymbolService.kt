package dev.ide.lang.kotlin.symbols

import dev.ide.lang.kotlin.resolve.approximateEscapingLocalType
import dev.ide.lang.kotlin.resolve.delegatedValueType
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.index.ClassNameValue
import dev.ide.index.IndexId
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexService
import dev.ide.index.MemberValue
import dev.ide.lang.completion.PrefixMatcher
import dev.ide.lang.dom.DomNode
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef
import dev.ide.lang.kotlin.compile.BundledKotlinStdlib
import dev.ide.lang.kotlin.index.CallableShape
import dev.ide.lang.kotlin.index.KotlinBuiltinCallableIndex
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinPackageDeclIndex
import dev.ide.lang.kotlin.index.KotlinSourceCallableIndex
import dev.ide.lang.kotlin.index.PkgDecl
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
    /** FQN prefixes hidden from index-backed type-NAME completion — the Android namespaces for a non-Android
     *  (JVM/console) module, so the shared `java.classNames` index never offers `android.*` to auto-import. */
    private val excludedTypePrefixes: List<String> = emptyList(),
    /** Editor synthetic-member providers (kotlinx.serialization's `serializer()`, …): compiler-plugin-generated
     *  members the parse-only model can't see, folded into a SOURCE class's member enumeration. Contributed via
     *  `platform.kotlinSyntheticMember`; queried lazily so a re-registration is picked up. See
     *  [KotlinSyntheticMemberProvider]. */
    private val syntheticMemberProviders: () -> List<KotlinSyntheticMemberProvider> = { emptyList() },
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

    // FQNs of source classes with a member whose type is currently being inferred ([inferReturnFromBody]), as a
    // per-thread reference count (a member inference nests into its class's other members). While a class is in
    // here, enumerating ITS OWN members ([ownAndInheritedCached]) sees the in-flight member come back
    // re-entrant-null, so that (partial) list must not be pinned in the session cache — but OTHER classes
    // enumerated during the same inference are complete and stay cached. Scoping the bypass to the owner class
    // (not every source class) keeps member enumeration O(members), not O(members × delegates).
    private val inferringMemberOwners = ThreadLocal.withInitial { HashMap<String, Int>() }

    private fun pushInferringOwner(fqn: String) {
        val m = inferringMemberOwners.get(); m[fqn] = (m[fqn] ?: 0) + 1
    }

    private fun popInferringOwner(fqn: String) {
        val m = inferringMemberOwners.get(); val n = (m[fqn] ?: 0) - 1
        if (n <= 0) m.remove(fqn) else m[fqn] = n
    }

    private fun isInferringOwner(fqn: String): Boolean = inferringMemberOwners.get().containsKey(fqn)

    // Per-receiver-FQN memo of the (recursively-walked) Kotlin supertype chain — the expensive part of
    // `extensionsFor`/`supertypesOf`, recomputed on every member-access keystroke otherwise. Split by origin:
    // a classpath/builtin type's chain CANNOT depend on project source (the classpath can't extend your code),
    // so it's stable for the session; only a SOURCE type's chain can change on an edit. Keeping the classpath
    // memo across edits is the win for Compose (its deep `Modifier`/`MaterialTheme`/… chains stay warm).
    private val classpathSupertypeMemo = ConcurrentHashMap<String, List<String>>()
    @Volatile private var sourceSupertypeMemo = ConcurrentHashMap<String, List<String>>()

    // Per-(sub → super) memo of a type's parameterized instantiation of a supertype, expressed as a TEMPLATE
    // in the sub type's own type-parameter refs (arg-independent, so a receiver's actual args substitute in per
    // call). Keyed "sub super"; a genuine non-supertype is not cached (rare — the receiver's extension set
    // only carries extensions whose declared receiver IS a supertype). Powers [receiverSupertypeArgs].
    private val supertypeArgTemplateMemo = ConcurrentHashMap<String, List<TypeRef>>()

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
    // Per-fqn memo of a type's FULL own+inherited member list (the recursive [ownAndInherited] walk) — the
    // dominant editor cost on a member-heavy file (every `view.x`, `canvas.drawBitmap`, `bmp.width` otherwise
    // re-walks the whole View/Canvas/Bitmap member+supertype closure). Only the UNBOUND (no-type-args) list is
    // cached, keyed by fqn: a member's name/kind/existence is type-argument-independent (same rationale as
    // [checkMembersMemo]); a generic receiver (`Box<String>`) bypasses the memo and binds fresh. Split by
    // origin like the supertype memo — a classpath type's members can't change without a re-index (session-
    // stable, dropped on build-start via [classpathCacheUsable]); a SOURCE type's change on edit.
    private val classpathOwnMembersMemo = ConcurrentHashMap<String, List<KotlinSymbol>>()
    @Volatile private var sourceOwnMembersMemo = ConcurrentHashMap<String, List<KotlinSymbol>>()
    // Per-name memo of [topLevelByName]'s two DISK-SEGMENT-backed callable-index scans (library + Kotlin
    // builtins). These are the `Segment.exact` queries a CPU trace showed being re-run once per node of the
    // deep call/overload-inference recursion on a Compose file (every `Text`/`Column`/`remember` re-queried
    // per inference step) — a segment-read storm that pegged CPU and churned the heap. Session-stable exactly
    // like the sibling classpath memos (dropped on a (re)build via [classpathCacheUsable]); the PROJECT-SOURCE
    // callable query stays live (in-memory + edit-sensitive), so a source top-level edit is still seen at once.
    private val topLevelLibMemo = ConcurrentHashMap<String, List<KotlinSymbol>>()
    private val topLevelBuiltinMemo = ConcurrentHashMap<String, List<KotlinSymbol>>()
    // Tracks the index's last-seen build state so the classpath memos above are dropped the moment a (re)build
    // STARTS — a rebuilt index can carry different members/extensions (a dependency was added), and a query
    // mid-build sees only a partial index, so partial results must never be cached.
    @Volatile private var extMemoBuilding = false

    /** True when the classpath memos are safe to use: clears them on a (re)build start, never caches mid-build. */
    private fun classpathCacheUsable(idx: IndexService): Boolean {
        val status = idx.status
        val building = status.building
        if (building && !extMemoBuilding) {
            classpathExtMemo.clear(); checkMembersMemo.clear(); companionMembersMemo.clear(); classpathTypeExistsMemo.clear(); classpathOwnMembersMemo.clear(); classpathSupertypeMemo.clear(); topLevelLibMemo.clear(); topLevelBuiltinMemo.clear()
        }
        extMemoBuilding = building
        // Not ready ⇒ queries return PARTIAL results (whatever segments are open) for progressive completion;
        // those must never enter the session memos or they'd keep serving the partial view after the build.
        return !building && status.ready
    }

    /**
     * "Dumb mode" gate for RESOLUTION-driven negative conclusions (the unresolved-symbol diagnostics): until
     * the classpath index is [IndexStatus.ready] a miss can't be told apart from a not-yet-indexed symbol.
     * COMPLETION no longer blacks out behind this — the list queries ([extensionsFor], [topLevelCallables],
     * [typeNamesByPrefix]) answer progressively from whatever segments are already open while the build runs
     * (never a live jar scan; partial answers stay out of the session memos via [classpathCacheUsable]).
     * The type-SHAPE lookups ([typeShape]/[builtinShape]) stay gated: they feed inference whose consumers
     * would turn a partial supertype chain into false mismatch errors. With no index wired (standalone /
     * tests) there is no other source, so the live reader IS the classpath and this is always true.
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
            sourceOwnMembersMemo = ConcurrentHashMap() // a source type's members change on edit; classpath memo stays warm
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
            sourceOwnMembersMemo = ConcurrentHashMap() // a source type's members change on edit; classpath memo stays warm
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

    /** The source files declaring a top-level CALLABLE named [name] — a function (INCLUDING a top-level
     *  extension function, which a preview may call cross-file: `"x".shout()` where `fun String.shout()` lives in
     *  a sibling file) OR a non-extension top-level property — for cross-file preview lowering. A property read
     *  lowers to a `name/0` TOP_LEVEL source call (its synthetic zero-arg getter), so a preview referencing a
     *  `val` defined in another file (e.g. a theme color `Purple80`) must follow it here too. An extension
     *  PROPERTY stays excluded (its read routes through its receiver type, not this by-name lookup). Distinct by
     *  path. */
    fun sourceFilesDeclaringFunction(name: String): List<PreviewSourceFile> {
        val m = model()
        // `topLevel` holds NON-extension callables (functions + properties); extension callables live in a
        // separate `extensions` list, so an extension FUNCTION must be looked up there too (an extension property
        // stays excluded — its read routes through its receiver type, not this by-name lookup).
        val candidates = m.topLevel.asSequence().filter { it.name == name } +
            m.extensions.asSequence().filter { it.name == name && it.isFunction }
        return candidates
            .mapNotNull { previewSourceFile(it.ctx.path) }
            .distinctBy { it.file.path }
            .toList()
    }

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

    /** Whether [fqn] is a contributed synthetic class (Android `R`/`BuildConfig`, ViewBinding). Its members are
     *  volatile (resource-driven) and cheap, so they must NOT enter the session-stable classpath member memos —
     *  they are enumerated fresh so a resource edit is reflected at once. [synthetic] is identity-cached. */
    private fun isSyntheticType(fqn: String): Boolean =
        synthetic().byFqn.containsKey(Builtins.kotlinTypeFor(fqn) ?: fqn)

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

    /** The gate a [KotlinSyntheticMemberProvider] uses to require its runtime before contributing (a plugin's
     *  generated members are only real when the plugin actually ran, i.e. its runtime is on the classpath). */
    private val syntheticMemberContext = object : KotlinSyntheticMemberProvider.Context {
        override fun hasType(fqn: String): Boolean = isKnownType(fqn)
    }

    /** Compiler-plugin STATIC (type-/companion-accessible) synthetic members for [fqn] (kotlinx.serialization's
     *  `serializer()`), surfaced at a `Foo.` reference alongside the real companion members. Empty unless [fqn]
     *  is a PROJECT SOURCE class a registered provider targets — so it is a cheap map miss for every other type
     *  (a binary `@Serializable` class already carries its real `serializer()` in bytecode). */
    private fun syntheticStaticMembers(fqn: String, namePrefix: String): List<KotlinSymbol> {
        val rc = sourceClass(fqn) ?: return emptyList()
        val providers = runCatching { syntheticMemberProviders() }.getOrDefault(emptyList())
        if (providers.isEmpty()) return emptyList()
        val m = PrefixMatcher(namePrefix)
        return providers
            .flatMap { runCatching { it.staticMembers(rc, syntheticMemberContext) }.getOrDefault(emptyList()) }
            .filter { namePrefix.isEmpty() || m.matches(it.name) }
            .map { toSymbol(it, fqn, rc.typeParameterNames) }
    }

    /** Compiler-plugin INSTANCE synthetic members for source class [rc] (a future Parcelize provider's
     *  `writeToParcel`/`describeContents`), folded into the class's own members. Empty unless a registered
     *  provider targets [rc]. */
    private fun syntheticInstanceMembers(rc: RawClass): List<KotlinSymbol> {
        val providers = runCatching { syntheticMemberProviders() }.getOrDefault(emptyList())
        if (providers.isEmpty()) return emptyList()
        return providers
            .flatMap { runCatching { it.instanceMembers(rc, syntheticMemberContext) }.getOrDefault(emptyList()) }
            .map { toSymbol(it, rc.fqn, rc.typeParameterNames) }
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
        // 2. An explicit (non-star) import. It wins when it resolves to a KNOWN type. When the imported FQN is
        //    NOT known (a stale/typo'd import whose target doesn't exist — `import com.foo.Food` alongside a
        //    same-file `data class Food`), it must NOT shadow a real same-file/same-package declaration of the
        //    same simple name: Kotlin resolves the name to that local type and flags the import, rather than
        //    treating every use as the missing import's (unknown) type — which suppresses member checks on the
        //    real, known local type (an unknown receiver backs off). So an unknown import is remembered and used
        //    only as the LAST resort below, preserving the "return the intended FQN so the unresolved-type
        //    diagnostic points at it" behaviour when nothing local shadows it.
        val explicitImportFqn = ctx?.imports?.firstOrNull { !it.isStar && it.simpleName == simple }?.fqn
        explicitImportFqn?.let { if (isKnownType(it)) return it }
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
        // 7. An explicit import whose target is not a known type and that nothing local shadowed: return its FQN
        //    so the unresolved-type/import diagnostic still points at the intended name (the pre-fix behaviour
        //    for the no-conflict case — a genuinely missing import with no same-named local declaration).
        explicitImportFqn?.let { return it }
        // NOT brought into scope by any import. Deliberately NO blind classpath lookup by simple name — that
        // fallback masked missing-import errors (e.g. `ComponentActivity` silently resolving to
        // `androidx.activity.ComponentActivity`, then its members/extensions completing as if imported). The
        // name is returned verbatim so callers can detect "unresolved" via `isKnownType` (false for a bare name).
        return simple
    }

    // --- members / supertypes / extensions (KotlinTypeContext) ---

    override fun membersOf(typeFqn: String, typeArgs: List<TypeRef>, accessibleFrom: Symbol?): List<Symbol> =
        ownAndInheritedCached(typeFqn, typeArgs) + extensionsFor(typeFqn, typeArgs)

    /**
     * Members of [typeFqn] whose name starts with [namePrefix] — the completion path. Pushing the prefix down
     * matters with large classpaths (Compose): a type's extension set (esp. the `kotlin.Any` bucket, which
     * grows with every library) is filtered BEFORE the per-symbol receiver binding, so only the handful the
     * user is actually typing toward gets materialized, not hundreds per keystroke. An empty prefix = all
     * (same as [membersOf]).
     */
    fun membersForCompletion(
        typeFqn: String,
        typeArgs: List<TypeRef>,
        namePrefix: String,
        /** EXACT-name mode for the resolution probes ([membersNamed]): the graded matcher would admit
         *  hump/substring matches ("first" nets `indexOfFirst`, `firstOrNull`, …) only for the caller to
         *  discard them — per inference step, the dominant chain-completion allocation. */
        exactName: Boolean = false,
    ): List<KotlinSymbol> {
        val m = PrefixMatcher(namePrefix)
        val own = dev.ide.lang.kotlin.KotlinPerf.span("own") { ownAndInheritedCached(typeFqn, typeArgs) }
        val ownMatched = when {
            namePrefix.isEmpty() -> own
            exactName -> own.filter { it.name == namePrefix }
            else -> own.filter { m.matches(it.name) }
        }
        return ownMatched + dev.ide.lang.kotlin.KotlinPerf.span("ext") { extensionsFor(typeFqn, typeArgs, namePrefix, exactName) }
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
        return membersForCompletion(typeFqn, typeArgs, name, exactName = true)
    }

    /** Same-named members for the diagnostics existence / extension-in-scope check. Cached for a CLASSPATH
     *  receiver (session-stable, index-invalidated); a source receiver — whose members change on edit — goes
     *  uncached. Type arguments don't affect a member's existence / isExtension / import identity, so the
     *  unbound (no-type-args) result is what's cached and reused. */
    fun membersNamedForCheck(typeFqn: String, typeArgs: List<TypeRef>, name: String): List<KotlinSymbol> {
        if (name.isEmpty()) return emptyList()
        val idx = index
        // A synthetic class's members are volatile (resource-driven) — never pin them in the session-stable
        // check memo, or a just-added `strings.xml` string would be flagged unresolved until a full rebuild.
        if (idx == null || sourceClass(typeFqn) != null || isSyntheticType(typeFqn) || !classpathCacheUsable(idx))
            return membersNamed(typeFqn, typeArgs, name)
        return checkMembersMemo.getOrPut("$typeFqn $name") { membersNamed(typeFqn, emptyList(), name) }
    }

    override fun supertypesOf(typeFqn: String): List<TypeRef> =
        kotlinSupertypesMemo(typeFqn).map { typeByFqn(it) }

    /** [kotlinSupertypes] memoized per FQN; the walk is the hot cost. A SOURCE type's chain (its FQN is in the
     *  project model) goes in the edit-dropped memo, everything else in the session-stable classpath memo.
     *  While the index is (re)BUILDING a classpath chain is INCOMPLETE (shapes gated / segments still opening),
     *  so it is computed uncached via [classpathCacheUsable] — otherwise a mid-build walk pins a partial chain
     *  (e.g. `SolidColor` with `Brush` missing) for the whole session, and since `isKnownType` recomputes fresh
     *  the two disagree → a false "SolidColor but Color expected" mismatch. Gated exactly like
     *  [ownAndInheritedCached]/`classpathTypeExists`; the memo is also cleared on a build start (see
     *  [classpathCacheUsable]), which the ready-only gate below previously left it out of. */
    private fun kotlinSupertypesMemo(fqn: String): List<String> {
        val memo = if (model().classByFqn.containsKey(fqn)) sourceSupertypeMemo else {
            val idx = index
            if (idx != null && !classpathCacheUsable(idx)) return kotlinSupertypes(fqn, HashSet())
            classpathSupertypeMemo
        }
        return memo.getOrPut(fqn) { kotlinSupertypes(fqn, HashSet()) }
    }

    /**
     * [ownAndInherited] memoized at the top level (its callers start a fresh `visited` set, so this never sees
     * the recursion's shared guard — the internal supertype recursion stays on the raw function). Only the
     * UNBOUND case (no type arguments) is cached: the walk's member names/kinds don't depend on type arguments
     * (same rationale as [checkMembersMemo]/[membersNamedForCheck]), while a generic receiver (`Box<String>`,
     * `List<Foo>`) still binds fresh — rare next to the flood of non-generic framework receivers a file hits.
     * Classpath types cache session-stable (dropped on build-start / withheld mid-build, like the other
     * classpath memos); source types cache into the edit-dropped memo.
     */
    private fun ownAndInheritedCached(fqn: String, typeArgs: List<TypeRef>): List<KotlinSymbol> {
        if (typeArgs.isNotEmpty()) return ownAndInherited(fqn, typeArgs, HashSet()) // generic receiver → bind fresh
        val kfqn = Builtins.kotlinTypeFor(fqn) ?: fqn
        val idx = index
        return when {
            // While THIS class has a member whose type is mid-inference ([isInferringOwner]), enumerating its own
            // members returns that member re-entrant-null; pinning the partial list would leave it stuck untyped
            // for the session (`var x by mutableStateOf(emptyList<T>())`). Compute uncached in that window (only
            // for the owner class — other classes stay cached), like the mid-build partial-shape guard below.
            model().classByFqn.containsKey(kfqn) ->
                if (isInferringOwner(kfqn)) ownAndInherited(fqn, emptyList(), HashSet())
                else sourceOwnMembersMemo.getOrPut(kfqn) { ownAndInherited(fqn, emptyList(), HashSet()) }
            // A synthetic class (Android `R`/`BuildConfig`, ViewBinding) is VOLATILE — its members change when
            // resources change — and cheap to enumerate (in-memory). Pinning it in the session-stable classpath
            // memo (dropped only on a (re)build) would make an added/edited `strings.xml` string never appear in
            // `R.string.` / `stringResource` completion until a full rebuild. Compute fresh: `synthetic()`
            // self-invalidates by list identity when the host swaps the resource-driven list.
            isSyntheticType(kfqn) -> ownAndInherited(fqn, emptyList(), HashSet())
            idx != null && !classpathCacheUsable(idx) -> ownAndInherited(fqn, emptyList(), HashSet()) // mid-build: don't pin a partial shape
            else -> classpathOwnMembersMemo.getOrPut(kfqn) { ownAndInherited(fqn, emptyList(), HashSet()) }
        }
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
            // A compiler plugin's generated INSTANCE members (a future Parcelize provider's writeToParcel/…), for
            // the same reason: the parse-only model never runs the plugin. Cheap — gated on the class's annotations.
            val syntheticPlugin = syntheticInstanceMembers(rc)
            val inherited = rc.superTypeTexts.mapNotNull { resolveTypeName(it, rc.ctx) }
                .flatMap { ownAndInherited(it, emptyList(), visited) }
            return own + synthetic + syntheticPlugin + inherited
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
        // bean properties — `"".bytes` is not a Kotlin property of String. A mapped COLLECTION built-in also
        // gets the JDK methods Kotlin grafts on from its `java.util.*` type but leaves out of `.kotlin_builtins`
        // (`MutableList.replaceAll`/`sort`, `Map.getOrDefault`, …); see [additionalJvmMembers].
        builtinShape(fqn)?.let { shape ->
            val members = membersFromShape(shape, typeArgs, visited, synthesizeBeanProps = false)
            val extraNames = Builtins.ADDITIONAL_JVM_MEMBERS[fqn] ?: return members
            return members + additionalJvmMembers(fqn, extraNames, typeArgs, members)
        }
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

    fun extensionsFor(
        fqnRaw: String,
        typeArgs: List<TypeRef> = emptyList(),
        namePrefix: String = "",
        /** EXACT-name mode for resolution probes; see [membersForCompletion]. */
        exactName: Boolean = false,
    ): List<KotlinSymbol> {
        // A JVM receiver type maps to its Kotlin classifier so the Kotlin-keyed extensions apply (a value typed
        // `java.lang.String` still gets `String.uppercase`, a `java.util.List` gets `Iterable.map`).
        val fqn = Builtins.kotlinTypeFor(fqnRaw) ?: fqnRaw
        // Always include kotlin.Any: `T.let`/`also`/`run`/`apply`/… (unbounded type-param receiver) are keyed
        // there and apply to every instance, and a builtin supertype chain may not list Any explicitly.
        val targets = (listOf(fqn, "kotlin.Any") + kotlinSupertypesMemo(fqn)).toHashSet()
        val m = PrefixMatcher(namePrefix)
        fun matches(name: String) = when {
            namePrefix.isEmpty() -> true
            exactName -> name == namePrefix
            else -> m.matches(name)
        }
        // Classpath extensions: from the persistent `kotlin.callables` index when wired (prefix-queried per
        // receiver, so the large per-receiver + `kotlin.Any` buckets stay on disk and only the prefix matches
        // load), else the in-memory scan fallback (standalone / tests with no index). Either way the prefix
        // is applied BEFORE bindExtensionReceiver, which allocates a fresh symbol per generic receiver.
        // A camel-hump prefix pushes only its guaranteed first character into the packed key
        // ([PrefixMatcher.indexPrefix]); the matcher then narrows the bucket before symbols materialize.
        val idx = index
        val fromClasspath = if (idx != null) {
            // Progressive while indexing ("dumb mode"): the query runs over whatever segments are already
            // open — partial results instead of a blackout — and [classpathCacheUsable] keeps those partial
            // answers OUT of the session memos until the index is ready. Never a live jar scan.
            val cacheable = classpathCacheUsable(idx) // false while building / before ready
            run {
                // The stdlib's extensions (`Iterable.map`, `String.trim`) are in the index too — the host adds
                // the bundled stdlib jar to the index scope — so a single prefix query per receiver covers them.
                fun query(t: String) = idx.prefix<CallableShape>(KotlinCallableIndex.id, KotlinCallableIndex.extPrefix(t, m.indexPrefix), EXTENSION_QUERY_LIMIT)
                    .filter { matches(it.value.name) }
                    .map { it.value.toSymbol(this) }.toList()
                dev.ide.lang.kotlin.KotlinPerf.span("ext.index") { targets.flatMap { t ->
                    // The mode rides the key: an exact probe's narrow result must never serve a lenient query.
                    val key = (if (exactName) "=" else "~") + t + ' ' + namePrefix
                    if (cacheable) classpathExtMemo.getOrPut(key) { query(t) } else query(t)
                } }
            }
        } else {
            val scan = reader.scan(this)
            targets.flatMap { scan.extensionsByReceiver[it].orEmpty() }.filter { matches(it.name) }
        }
        val fromSource = model().extensions
            .filter { matches(it.name) && resolveTypeName(it.receiverText ?: return@filter false, it.ctx) in targets }
            .map { toSymbol(it, null) }
        // Cross-file source extensions from the persistent `kotlin.callables.source` index — available as
        // soon as the file is index-synced, without waiting on the in-memory source model to warm. The
        // completion dedup folds an entry together with its model twin once both exist.
        val fromSourceIndex = if (idx != null) {
            targets.flatMap { t ->
                idx.prefix<CallableShape>(KotlinSourceCallableIndex.id, KotlinCallableIndex.extPrefix(t, m.indexPrefix), EXTENSION_QUERY_LIMIT)
                    .filter { matches(it.value.name) }
                    .map { it.value.toSymbol(this, SOURCE) }
            }
        } else emptyList()
        // Drop compiler/runtime-implementation callables (e.g. `kotlin.jvm.internal.PrimitiveSpreadBuilder`'s
        // `getSize`, mis-keyed as a `kotlin.Any` extension) — never user-visible. Then bind the extension
        // receiver's type params (Iterable<T>.map, T.also) from the actual receiver.
        return (fromClasspath + fromSource + fromSourceIndex)
            .filterNot { isImplementationCallable(it) }
            .map { bindExtensionReceiver(it, fqn, typeArgs) }
    }

    /** True for a callable in a Kotlin compiler/runtime *implementation* package (`kotlin.jvm.internal`,
     *  `kotlin.coroutines.jvm.internal`, `kotlin.internal`, `kotlin.reflect.jvm.internal`). These are public in
     *  bytecode (so not flagged `internal`) but are never user-facing API, so they must not appear in
     *  completion / member resolution. */
    private fun isImplementationCallable(s: KotlinSymbol): Boolean {
        // Perf counter: this is the CPU trace's #1 app frame — cheap per call but called per scored
        // extension/overload candidate, so its count is a direct readout of an overload-scoring blowup
        // (surfaced as `resolveOps=N` in the kotlin-perf trace lines). No-op unless timing is enabled.
        dev.ide.lang.kotlin.KotlinPerf.bump()
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
        val fqn = Builtins.kotlinTypeFor(typeFqnRaw) ?: typeFqnRaw
        val companion = companionObjectFqn(typeFqnRaw)?.let { companionFqn ->
            membersForCompletion(companionFqn, emptyList(), namePrefix).filter { it.name !in OBJECT_METHODS }
        } ?: emptyList()
        // A compiler-plugin's type-accessible synthetics (kotlinx.serialization's `Foo.serializer()`) surface here
        // too — even for a `@Serializable` class with NO explicit companion, whose companion the plugin synthesizes.
        return companion + syntheticStaticMembers(fqn, namePrefix)
    }

    /** The companion object of [typeFqnRaw] as a completion candidate — its declared simple name (`Companion`
     *  by default, or a named companion's name), reached statically through the type (`Test.Companion`). Null
     *  when the type has no companion object. Offered at a `Type.` reference alongside the companion's members. */
    fun companionObjectSymbol(typeFqnRaw: String): KotlinSymbol? {
        val companionFqn = companionObjectFqn(typeFqnRaw) ?: return null
        val fqn = Builtins.kotlinTypeFor(typeFqnRaw) ?: typeFqnRaw
        return KotlinSymbol(
            name = companionFqn.substringAfterLast('.'),
            kind = SymbolKind.CLASS,
            type = typeByFqn(companionFqn),
            modifiers = setOf(Modifier.STATIC),
            origin = if (sourceClass(fqn) != null) SOURCE else BINARY,
        )
    }

    /**
     * A member reachable by a fully-qualified import path — a companion-object member
     * (`import …MainActivity.Companion.TAG`), a plain `object` member (`import …Config.DEBUG`), or a companion
     * member imported through its enclosing class (`import …MainActivity.TAG`, which Kotlin also permits) —
     * resolved to its symbol so a bare reference to the imported simple name gets a type. Null when the
     * container declares no such member. The declared type/inference machinery treats the result exactly like a
     * top-level property, so a chain off it (`TAG.length`) resolves.
     */
    fun importedMemberSymbol(memberFqn: String): KotlinSymbol? {
        val container = memberFqn.substringBeforeLast('.', "")
        val name = memberFqn.substringAfterLast('.')
        if (container.isEmpty() || name.isEmpty()) return null
        // The container is itself an `object` / companion-object FQN (`…MainActivity.Companion`, `…Config`):
        // the member is declared directly on it.
        membersNamed(container, emptyList(), name).firstOrNull { !it.isExtension && it.name == name }
            ?.let { return it }
        // The container is the ENCLOSING class of a companion member (`import Outer.member`): companion members
        // are also accessible statically through the class name.
        return companionMembersFor(container, name).firstOrNull { it.name == name }
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
     * The JDK methods Kotlin's `JvmBuiltInsCustomizer` grafts onto the mapped collection built-in [fqn]
     * (`MutableList.replaceAll`/`sort`, `MutableMap.putIfAbsent`, `Collection.stream`, …): callable Kotlin, yet
     * absent from the `.kotlin_builtins` shape, so completion/resolution would otherwise miss them. Pulled by
     * name ([names], from [Builtins.ADDITIONAL_JVM_MEMBERS]) off the mapped `java.util.*` bytecode shape, bound
     * to the receiver's [typeArgs] like any inherited member, and deduped by (name, arity) against the built-in
     * [existing] members so an overload the built-in already declares (`MutableMap.remove(key)`) isn't doubled.
     * Empty when the java type isn't on the classpath (dumb mode / no SDK) — a graceful degrade, not an error.
     */
    private fun additionalJvmMembers(
        fqn: String,
        names: Set<String>,
        typeArgs: List<TypeRef>,
        existing: List<KotlinSymbol>,
    ): List<KotlinSymbol> {
        val shape = typeShape(Builtins.javaTypeFor(fqn) ?: return emptyList()) ?: return emptyList()
        val bindings = classBindings(shape, typeArgs)
        val taken = existing.mapTo(HashSet()) { it.name to it.paramTypes.size }
        return shape.members
            .filter { it.kind == SymbolKind.METHOD && it.name in names }
            .map { substituteSymbol(it, if (it.typeParameters.isEmpty()) bindings else bindings - it.typeParameters.toSet()) }
            .filter { (it.name to it.paramTypes.size) !in taken }
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
     *  `Iterable<T>.first()` on `List<String>` → T = String (positional from the receiver's args); a NESTED
     *  receiver arg (`Iterable<Iterable<T>>.flatten()` on `List<List<Int>>` → T = Int) binds by unifying the
     *  declared arg against the actual one structurally. */
    private fun bindExtensionReceiver(ext: KotlinSymbol, receiverFqn: String, receiverArgs: List<TypeRef>): KotlinSymbol {
        val bindings = HashMap<String, TypeRef>()
        ext.receiverTypeParam?.let { bindings[it] = typeByFqn(receiverFqn, receiverArgs) } // T.also(): T -> receiver
        // When the extension is declared on a SUPERTYPE of the actual receiver (`Iterable<T>.forEach` on an
        // `IntRange` or a `List<String>`), bind its receiver args from the receiver's INSTANTIATION of that
        // supertype — so a type parameter the receiver's OWN args don't supply positionally (a range carries
        // none; `IntRange : Iterable<Int>`) still resolves. Falls back to the receiver's own args when the
        // instantiation is unavailable (dumb mode) or the extension is on the receiver's exact type.
        val extRecvFqn = ext.receiverTypeFqn?.let { Builtins.kotlinTypeFor(it) ?: it }
        val recvArgs = if (extRecvFqn != null && extRecvFqn != receiverFqn && ext.receiverTypeArgs.isNotEmpty())
            receiverSupertypeArgs(receiverFqn, receiverArgs, extRecvFqn) ?: receiverArgs
        else receiverArgs
        ext.receiverTypeArgs.forEachIndexed { i, ra ->
            val k = ra as? KotlinType ?: return@forEachIndexed
            val actual = recvArgs.getOrNull(i) ?: return@forEachIndexed
            if (k.isTypeParameter) bindings[k.qualifiedName] = actual       // Iterable<T> on List<String> -> T = String
            else unifyReceiverArg(k, actual, bindings)                      // Iterable<Iterable<T>> on List<List<Int>> -> T = Int
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

    /** Structurally unify a declared extension-receiver argument [declared] (which may nest type parameters,
     *  e.g. `Iterable<T>`) against the [actual] type argument at that position, recording any type-param
     *  bindings. Positional over type arguments, so `Iterable<T>` vs `List<String>` binds `T = String` and
     *  `Iterable<Iterable<T>>` vs `List<List<Int>>` binds `T = Int`. First binding wins (putIfAbsent). */
    private fun unifyReceiverArg(declared: KotlinType, actual: TypeRef, out: MutableMap<String, TypeRef>) {
        if (declared.isTypeParameter) { out.putIfAbsent(declared.qualifiedName, actual); return }
        val a = actual as? KotlinType ?: return
        declared.typeArguments.forEachIndexed { i, d ->
            (d as? KotlinType)?.let { dk -> a.typeArguments.getOrNull(i)?.let { av -> unifyReceiverArg(dk, av, out) } }
        }
    }

    /**
     * The type arguments `[subFqn]<[subArgs]>` supplies to its supertype [superFqn] — `IntRange`→`Iterable`
     * is `[Int]` (via `IntProgression : Iterable<Int>`), `ArrayList<String>`→`Iterable` is `[String]`. Null
     * when [superFqn] isn't a (transitive) supertype or its shape is unavailable (dumb mode). The walk is
     * memoized per (sub, super) as a template in [subFqn]'s type parameters; only the final substitution of
     * [subArgs] varies per receiver, keeping the completion hot path cheap.
     */
    internal fun receiverSupertypeArgs(subFqn: String, subArgs: List<TypeRef>, superFqn: String): List<TypeRef>? {
        val params = (builtinShape(subFqn) ?: typeShape(subFqn))?.typeParameters ?: return null
        val template = supertypeArgTemplateMemo.getOrPut("$subFqn $superFqn") {
            val paramRefs = params.map { KotlinType(it, isTypeParameter = true, context = this) }
            walkSupertypeArgs(subFqn, paramRefs, superFqn, HashSet()) ?: return null // don't cache a non-supertype
        }
        if (subArgs.isEmpty() || params.isEmpty()) return template
        val subst = params.zip(subArgs).toMap()
        return template.map { substitute(it, subst) }
    }

    /** DFS over the supertype graph, substituting each level's declared type arguments through the running
     *  binding, until [superFqn] is reached (then its arguments in terms of the start type's parameters). */
    private fun walkSupertypeArgs(subFqn: String, subArgs: List<TypeRef>, superFqn: String, visited: MutableSet<String>): List<TypeRef>? {
        if (subFqn == superFqn) return subArgs
        if (!visited.add(subFqn)) return null
        val shape = builtinShape(subFqn) ?: typeShape(subFqn) ?: return null
        val subst = if (subArgs.isEmpty() || shape.typeParameters.isEmpty()) emptyMap()
            else shape.typeParameters.zip(subArgs).toMap()
        for (sup in shape.supertypes) {
            val supK = sup as? KotlinType ?: continue
            val supFqn = Builtins.kotlinTypeFor(supK.qualifiedName) ?: supK.qualifiedName
            val supArgs = if (subst.isEmpty()) supK.typeArguments else supK.typeArguments.map { substitute(it, subst) }
            walkSupertypeArgs(supFqn, supArgs, superFqn, visited)?.let { return it }
        }
        return null
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
            isInfix = s.isInfix,
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
        // Substituting a type parameter keeps the USE-SITE projection of its position: `List<out T>` with
        // `T = Int` is `List<out Int>` — the argument stays `out`, not bare.
        if (kt.isTypeParameter) {
            val bound = bindings[kt.qualifiedName] ?: return kt
            return if (kt.projection.isEmpty()) bound else (bound as? KotlinType)?.withProjection(kt.projection) ?: bound
        }
        if (kt.typeArguments.isEmpty()) return kt
        return KotlinType(kt.qualifiedName, kt.typeArguments.map { substitute(it, bindings) }, kt.nullable, this, kt.isTypeParameter, kt.isExtensionFunctionType, kt.isComposable, kt.projection)
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
        val m = PrefixMatcher(prefix)
        val src = model().topLevel
            .filter { prefix.isEmpty() || m.matches(it.name) }
            .map { toSymbol(it, null) }
        val idx = index
        val cp = if (idx != null) {
            run {
                // Prefix-query the persistent index; an empty prefix (the explicit "show all" / resolution path,
                // not per-keystroke) is uncapped so it stays complete, while a typed prefix is bounded by matches.
                // A camel-hump prefix pushes its first character AND the full prefix into the packed keys (see
                // [PrefixMatcher.indexPrefixes]): the first-char query's result cap could otherwise truncate a
                // plain-prefix match — `listOf`, once the caret passes the capital `O` — before it's reached, so
                // the narrow full-prefix query rescues the typed-in-full name; [distinctBy] folds the overlap.
                // While the index is still building this returns the already-open segments' callables —
                // progressive completion instead of a dumb-mode blackout.
                val limit = if (prefix.isEmpty()) Int.MAX_VALUE else CALLABLE_QUERY_LIMIT
                // The stdlib's top-level callables (`println`, `listOf`) are in the index too (the host adds the
                // bundled stdlib jar to the index scope), so the prefix query covers them.
                fun scan(id: IndexId, origin: SymbolOrigin): List<KotlinSymbol> {
                    val hits = m.indexPrefixes.flatMap { p ->
                        idx.prefix<CallableShape>(id, KotlinCallableIndex.topKey(p), limit)
                            .filter { prefix.isEmpty() || m.matches(it.value.name) }
                            .map { it.value.toSymbol(this, origin) }
                    }
                    return if (m.indexPrefixes.size > 1) hits.distinctBy { it.name + "|" + it.signature } else hits
                }
                scan(KotlinCallableIndex.id, BINARY) +
                    // Cross-file source top-levels straight from the source index (available before the
                    // in-memory model warms; the completion dedup folds them with their model twins).
                    scan(KotlinSourceCallableIndex.id, SOURCE) +
                    // The builtin intrinsics (`arrayOf`/`intArrayOf`/…) — top-level functions in `.kotlin_builtins`
                    // with no `.class` facade, so absent from the `.class`-scanning KotlinCallableIndex above.
                    scan(KotlinBuiltinCallableIndex.id, BINARY)
            }
        } else {
            val byName = reader.scan(this).topLevelByName
            val fromReader = if (prefix.isEmpty()) byName.values.flatten()
            else {
                val acc = ArrayList<KotlinSymbol>()
                for ((name, syms) in byName) if (m.matches(name)) acc += syms
                acc
            }
            // `reader` scans only `.class`; the builtin intrinsics live in `.kotlin_builtins`, so add them
            // from the live decode (the IDE path reads them from KotlinBuiltinCallableIndex above).
            fromReader + builtins.topLevelCallables()
                .filter { it.receiverTypeFqn == null && (prefix.isEmpty() || m.matches(it.name)) }
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
        // Extensions named [name] regardless of receiver (the receiver-blind `name:` keys) — so an
        // unresolved `x.shout()` can offer `import demo.shout` even though extensions are receiver-keyed.
        index?.let { idx ->
            (idx.exact<CallableShape>(KotlinCallableIndex.id, KotlinCallableIndex.nameKey(name)) +
                idx.exact<CallableShape>(KotlinSourceCallableIndex.id, KotlinCallableIndex.nameKey(name)))
                .forEach { shape -> shape.packageName?.let { out += "$it.$name" } }
        }
        typeNamesByPrefix(name).forEach { s ->
            val fqn = s.type?.qualifiedName
            if (fqn != null && '.' in fqn && fqn.substringAfterLast('.') == name) out += fqn
        }
        // Members of a project companion object, importable by their simple name through the enclosing type
        // (`import …MainActivity.Companion.TAG`) — companion members are accessible statically, so a bare
        // unresolved `TAG` can offer its companion import (mirrors an `object` member's static import).
        model().classByFqn.values.forEach { rc ->
            if (rc.isCompanion && rc.members.any { it.name == name }) out += "${rc.fqn}.$name"
        }
        return out.sorted()
    }

    /**
     * Whether the classpath carries a LIBRARY (binary) type with simple [name] — one a file could `import` but
     * may not have. Distinguishes a genuine missing-import reference (`FontWeight.Bold` with no
     * `import androidx.compose.ui.text.font.FontWeight`) from a package segment (`androidx.…` — no such type) or
     * a same-module SOURCE / synthetic class (Android `R`/`BuildConfig`), which resolve without an import and so
     * must never be flagged unresolved. Exact (not fuzzy), so it needs no built trigram dictionary.
     */
    fun hasLibraryType(name: String): Boolean =
        name.isNotEmpty() &&
            index?.exact<ClassNameValue>(CLASS_NAMES, name)?.any { it.origin == IndexOrigin.LIBRARY } == true

    /**
     * Whether the classpath/source knows a type with exactly this [fqn], resolved by NAME (index-backed). Unlike
     * [isKnownType] this is satisfied by a name-only class-name index entry (no type shape / bytecode needed), so
     * it stays reliable for a library type the index holds by name only — the existence signal the import check
     * needs (an explicitly-imported `androidx.activity.ComponentActivity` is real even when its shape isn't read).
     */
    fun typeFqnKnown(fqn: String): Boolean {
        if (isKnownType(fqn)) return true
        return index?.exact<ClassNameValue>(CLASS_NAMES, fqn.substringAfterLast('.'))?.any { it.fqn == fqn } == true
    }

    /**
     * Packages declaring a top-level OR extension callable (function/property) named [name] — the existence
     * signal an unresolved-callable-IMPORT check needs: `import a.b.c.foo` names something real iff `a.b.c` is
     * in this set. Package-PRECISE (unlike the name-only [typeFqnKnown]/[topLevelByName]), so
     * `import kotlin.collections.println` — `println` lives in `kotlin.io` — is correctly seen as dead. Unions
     * the project source model (top-levels + extensions), the library/source/builtin callable indexes (top-level
     * `top:` keys via [topLevelByName] + the receiver-blind extension `name:` keys), each carrying its package.
     * Callable MEMBERS of a type are deliberately absent (they import through the owning type, checked by the
     * caller via [typeFqnKnown]); this only answers "is there a package-level callable [name] in package X".
     */
    fun callablePackages(name: String): Set<String> {
        if (name.isEmpty()) return emptySet()
        val out = HashSet<String>()
        // Top-level callables (functions + properties) — `topLevelByName` queries only the `top:` keys, so its
        // results are exactly the non-extension top-levels; each carries its package (or its facade FQN).
        topLevelByName(name).forEach { s ->
            (s.packageName ?: s.declaringClassFqn?.substringBeforeLast('.', "")?.ifEmpty { null })?.let { out += it }
        }
        // Project-source EXTENSIONS (receiver-keyed; absent from `topLevelByName`'s `top:`-only query).
        model().extensions.filter { it.name == name }.forEach { rc -> rc.ctx.packageName.ifEmpty { null }?.let { out += it } }
        // Library + cross-file-source + builtin extensions via the receiver-blind name key.
        index?.let { idx ->
            for (id in listOf(KotlinCallableIndex.id, KotlinSourceCallableIndex.id, KotlinBuiltinCallableIndex.id)) {
                idx.exact<CallableShape>(id, KotlinCallableIndex.nameKey(name)).forEach { shape ->
                    shape.packageName?.let { out += it }
                }
            }
        }
        return out
    }

    /** Whether a WIRED classpath index has finished building — the gate for negative conclusions that need a
     *  complete classpath view (the unresolved-import check). False when no index is wired or it's still in
     *  "dumb mode", so those runs never draw a false conclusion. Distinct from [classpathReady], which treats a
     *  missing index as ready (for checks that degrade gracefully without one). */
    fun classpathIndexReady(): Boolean = index?.status?.ready == true

    fun topLevelByName(name: String): List<KotlinSymbol> {
        val src = model().topLevel.filter { it.name == name }.map { toSymbol(it, null) }
        val idx = index
        val cp = if (idx != null) {
            // Index only — the stdlib (`println`, `listOf`) is indexed alongside every other library jar.
            // While building this sees the already-open segments (partial, progressive); resolution-driven
            // NEGATIVE conclusions gate on [classpathReady] separately. The library + builtin scans hit disk
            // segments and are session-stable, so memoize them (the hot re-query under inference recursion);
            // the project-source scan is in-memory + edit-sensitive, so it stays live. Order is preserved.
            val usable = classpathCacheUsable(idx)
            val lib = if (usable) topLevelLibMemo.getOrPut(name) { topLevelLibScan(idx, name) } else topLevelLibScan(idx, name)
            val srcIdx = idx.exact<CallableShape>(KotlinSourceCallableIndex.id, KotlinCallableIndex.topKey(name)).map { it.toSymbol(this, SOURCE) }.toList()
            val bi = if (usable) topLevelBuiltinMemo.getOrPut(name) { topLevelBuiltinScan(idx, name) } else topLevelBuiltinScan(idx, name)
            lib + srcIdx + bi
        } else reader.scan(this).topLevelByName[name].orEmpty() +
            builtins.topLevelCallables().filter { it.receiverTypeFqn == null && it.name == name }
        return src + cp
    }

    /** The library callable-index (`kotlin.callables`) half of [topLevelByName] — a disk-segment `exact` scan. */
    private fun topLevelLibScan(idx: IndexService, name: String): List<KotlinSymbol> =
        idx.exact<CallableShape>(KotlinCallableIndex.id, KotlinCallableIndex.topKey(name)).map { it.toSymbol(this) }.toList()

    /** The Kotlin-builtins callable-index half of [topLevelByName] — a disk-segment `exact` scan. */
    private fun topLevelBuiltinScan(idx: IndexService, name: String): List<KotlinSymbol> =
        idx.exact<CallableShape>(KotlinBuiltinCallableIndex.id, KotlinCallableIndex.topKey(name)).map { it.toSymbol(this) }.toList()

    /**
     * Completion candidates under a dotted package prefix [packageFqn]: its immediate sub-packages + the
     * public types declared directly in it (classpath via the `java.packages`/`java.packageTypes` indices,
     * plus same-project source classes), each filtered by [prefix]. Powers `java.<caret>` / `java.ut<caret>`.
     */
    fun packageMembers(packageFqn: String, prefix: String, limit: Int = 100): List<KotlinSymbol> {
        val m = PrefixMatcher(prefix)
        val out = LinkedHashMap<String, KotlinSymbol>()
        index?.let { idx ->
            // Sub-packages: query everything under `packageFqn.` and keep the next path segment (only the
            // matcher-guaranteed first characters ride the packed key, so camel-hump prefixes still match).
            val full = if (prefix.isEmpty()) "$packageFqn." else "$packageFqn.${m.indexPrefix}"
            idx.prefix<String>(PACKAGES, full, 500).forEach { hit ->
                if (!hit.value.startsWith("$packageFqn.")) return@forEach
                val seg = hit.value.removePrefix("$packageFqn.").substringBefore('.')
                if (seg.isNotEmpty() && (prefix.isEmpty() || m.matches(seg))) {
                    out.getOrPut("pkg:$seg") { KotlinSymbol(seg, SymbolKind.PACKAGE, origin = BINARY) }
                }
            }
            // Public types directly in the package.
            idx.exact<ClassNameValue>(PACKAGE_TYPES, packageFqn).forEach { v ->
                val simple = v.fqn.substringAfterLast('.')
                if (prefix.isNotEmpty() && !m.matches(simple)) return@forEach
                if (v.origin != IndexOrigin.SOURCE && isKotlinFacade(v.fqn, simple)) return@forEach
                out.getOrPut(v.fqn) { KotlinSymbol(simple, classNameKind(v.kind), typeByFqn(v.fqn), origin = BINARY) }
            }
            // Classpath (library/SDK) top-level callables + extensions declared in the package — so import
            // completion after a package dot offers `map`/`collect`/`stateIn`, not just types (same-project
            // source callables are added from the live model below).
            classpathPackageCallables(idx, packageFqn, m, limit).forEach { s -> out.getOrPut("cbl:" + s.name) { s } }
        }
        // Same-project source classes declared in this package (the index lags the live buffer).
        model().classByFqn.values
            .filter { it.fqn.substringBeforeLast('.', "") == packageFqn && (prefix.isEmpty() || m.matches(it.simpleName)) }
            .forEach { out.getOrPut(it.fqn) { KotlinSymbol(it.simpleName, SymbolKind.CLASS, typeByFqn(it.fqn), origin = SOURCE, declarationNode = it.node) } }
        // Same-project source TOP-LEVEL CALLABLES (functions + properties, extensions included) declared in this
        // package: they are importable by name (`import com.foo.Test`) and callable fully-qualified, so they
        // belong here alongside the types — a package-member completion that offered only types omitted them.
        (model().topLevel.asSequence() + model().extensions.asSequence())
            .filter { it.ctx.packageName == packageFqn && (prefix.isEmpty() || m.matches(it.name)) }
            .forEach { rc -> out.getOrPut("cbl:" + rc.name) { toSymbol(rc, null) } }
        return out.values.take(limit)
    }

    /**
     * Classpath (library/SDK) top-level callables + extensions declared directly in [packageFqn], matching
     * [m] — the classpath half of import completion after a package dot. The candidate NAMES come from the
     * package-keyed [KotlinPackageDeclIndex] (the only per-package enumeration); each is then SHAPED and
     * VISIBILITY-FILTERED through the callable index (its public `top:`/`name:` entries — a private/internal
     * library callable isn't indexed there, so it's dropped, matching completion's visibility rule that
     * `kotlin.pkgDecls` itself doesn't apply), keeping only an entry actually declared in [packageFqn].
     */
    private fun classpathPackageCallables(idx: IndexService, packageFqn: String, m: PrefixMatcher, limit: Int): List<KotlinSymbol> {
        val out = ArrayList<KotlinSymbol>()
        val seen = HashSet<String>()
        for (decl in idx.exact<PkgDecl>(KotlinPackageDeclIndex.id, packageFqn)) {
            if (decl.classifier) continue // types are served by the PACKAGE_TYPES scan
            val name = decl.name
            if ((m.prefix.isNotEmpty() && !m.matches(name)) || !seen.add(name)) continue
            val sym = (idx.exact<CallableShape>(KotlinCallableIndex.id, KotlinCallableIndex.topKey(name)) +
                idx.exact<CallableShape>(KotlinCallableIndex.id, KotlinCallableIndex.nameKey(name)))
                .firstOrNull { it.packageName == packageFqn }?.toSymbol(this)
            if (sym != null) {
                out += sym
                if (out.size >= limit) break
            }
        }
        return out
    }

    /** Top-level package segments (`androidx`, `kotlin`, `com`, `java`, …) matching [prefix] — the candidates
     *  for a bare `import <caret>` (before any dot). Drilling into one (`androidx.<caret>`) then routes through
     *  [packageMembers]. */
    fun rootPackages(prefix: String, limit: Int = 200): List<KotlinSymbol> {
        val m = PrefixMatcher(prefix)
        val out = LinkedHashMap<String, KotlinSymbol>()
        index?.prefix<String>(PACKAGES, m.indexPrefix, 1000)?.forEach { hit ->
            val seg = hit.value.substringBefore('.')
            if (seg.isNotEmpty() && (prefix.isEmpty() || m.matches(seg)))
                out.getOrPut(seg) { KotlinSymbol(seg, SymbolKind.PACKAGE, origin = BINARY) }
        }
        // Same-project source packages (the index lags the live buffer).
        model().classByFqn.keys.forEach { fqn ->
            val seg = fqn.substringBefore('.')
            if (seg.isNotEmpty() && seg != fqn && (prefix.isEmpty() || m.matches(seg)))
                out.getOrPut(seg) { KotlinSymbol(seg, SymbolKind.PACKAGE, origin = SOURCE) }
        }
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

    /** A type-name completion page plus whether it was capped (more matches exist than [symbols] holds). */
    class TypeNameCandidates(val symbols: List<KotlinSymbol>, val capped: Boolean)

    /** Type-name candidates by prefix: source classes + defaults + the classpath `classNames` index. */
    fun typeNamesByPrefix(prefix: String, limit: Int = 100): List<KotlinSymbol> =
        typeNameCandidates(prefix, limit).symbols

    /** [typeNamesByPrefix] plus whether the classpath `classNames` query was TRUNCATED at [limit] — i.e. more
     *  types match the prefix than were returned. Completion needs this: the index answers a prefix with the
     *  top-[limit] fuzzy-scored hits, so a broad prefix (`S`, `St`) returns a capped page that a type like
     *  `StringBuilder` only scores into at a longer prefix. Marking such a result incomplete makes the engine
     *  RE-QUERY as the prefix narrows instead of client-side-narrowing the stale page (which permanently hides
     *  those types until the completion session is restarted). */
    fun typeNameCandidates(prefix: String, limit: Int = 100): TypeNameCandidates {
        val m = PrefixMatcher(prefix)
        val out = LinkedHashMap<String, KotlinSymbol>()
        model().classByFqn.values.filter { !it.isCompanion && !it.isLocal && (prefix.isEmpty() || m.matches(it.simpleName)) }
            .forEach { out[it.fqn] = KotlinSymbol(it.simpleName, rawClassKind(it), typeByFqn(it.fqn), origin = SOURCE, declarationNode = it.node) }
        // Top-level synthetic classes (Android `R`/`BuildConfig`, …) complete by simple name like any type.
        synthetic().let { idx ->
            idx.topLevelFqns.filter { prefix.isEmpty() || m.matches(it.substringAfterLast('.')) }
                .forEach { fqn -> out.getOrPut(fqn) { KotlinSymbol(fqn.substringAfterLast('.'), syntheticKind(idx.byFqn.getValue(fqn).kind), typeByFqn(fqn), origin = SOURCE) } }
        }
        Builtins.DEFAULT_SIMPLE_TYPES.filter { prefix.isEmpty() || m.matches(it.key) }
            .forEach { (s, fqn) -> out.getOrPut(fqn) { KotlinSymbol(s, SymbolKind.CLASS, typeByFqn(fqn), origin = BINARY) } }
        // The classNames segments carry a trigram dictionary, so the fuzzy path answers camel-hump
        // (`NPE`, `mDL`) and case-insensitive queries the byte-prefix scan cannot; the matcher then drops
        // the loose subsequence tier the fuzzy scorer keeps.
        // Materialized (not a lazy Sequence) so the hit count is available for the cap check below AND the
        // query runs once, not again per traversal.
        val classHits = index?.let { idx ->
            if (prefix.isEmpty()) idx.prefix<ClassNameValue>(CLASS_NAMES, prefix, limit)
            else idx.fuzzy<ClassNameValue>(CLASS_NAMES, prefix, limit)
        }?.toList()
        classHits?.forEach { hit ->
            val v = hit.value
            val simple = v.fqn.substringAfterLast('.')
            if (prefix.isNotEmpty() && !m.matches(simple)) return@forEach
            // Don't offer `android.*` to auto-import in a non-Android module (the shared index holds it).
            if (excludedTypePrefixes.any { v.fqn.startsWith(it) }) return@forEach
            if (v.origin != IndexOrigin.SOURCE && isKotlinFacade(v.fqn, simple)) return@forEach
            out.getOrPut(v.fqn) {
                KotlinSymbol(simple, classNameKind(v.kind), typeByFqn(v.fqn), origin = BINARY)
            }
        }
        // Capped when the index page filled OR the merged set overflowed the take — either way a matching type
        // may have been dropped, so the caller must not treat the page as the complete match set.
        val capped = (classHits?.size ?: 0) >= limit || out.size > limit
        return TypeNameCandidates(out.values.take(limit), capped)
    }

    /** The [SymbolKind] a project-source [rc] completes as — so type-name completion (and its annotation-only
     *  filter for `@…`) and the item icon reflect enum/interface/annotation instead of a blanket CLASS. */
    private fun rawClassKind(rc: RawClass): SymbolKind = when {
        rc.isAnnotation -> SymbolKind.ANNOTATION_TYPE
        rc.isEnum -> SymbolKind.ENUM
        rc.isInterface -> SymbolKind.INTERFACE
        else -> SymbolKind.CLASS
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

    /** Each type parameter's resolved upper bound (positional with [classTypeParameters]), or null where the bound
     *  is absent/unknown. Source classes parse the declared bound text; classpath classes use the type-shape's
     *  erased bounds (empty for a Kotlin-metadata class, whose decode doesn't carry them). */
    fun classTypeParameterBounds(fqn: String): List<KotlinType?> {
        model().classByFqn[fqn]?.let { rc ->
            return rc.typeParameterBounds.map { b -> if (b.isBlank()) null else typeFromText(b, rc.ctx) }
        }
        return typeShape(fqn)?.typeParameterBounds?.map { it as? KotlinType } ?: emptyList()
    }

    /** Each type parameter's declaration-site variance (positional with [classTypeParameters]): `"out"`, `"in"`,
     *  or `""` for invariant. From the project source model (PSI), else the Kotlin built-in shape (`List<out E>`,
     *  `Comparator<in T>` — decoded from `.kotlin_builtins`), else a classpath binary's `@Metadata`/type-shape
     *  variance. An empty list for a class WITH type arguments means "unknown" (a plain-Java type — Java has no
     *  declaration-site variance), distinct from an invariant Kotlin class (which returns `["", ...]`); the
     *  variance-aware subtyping treats "unknown" conservatively (see [KotlinConstraintSystem]). */
    fun classTypeParameterVariance(fqn: String): List<String> {
        model().classByFqn[fqn]?.let { return it.typeParameterVariance }
        // A function type is `FunctionN<in P1..Pn, out R>` (params contravariant, result covariant); its arity
        // is variable, so it's computed rather than tabled.
        functionTypeVariance(fqn)?.let { return it }
        Builtins.DECLARATION_VARIANCE[fqn]?.let { return it }             // JVM-erased variance (Comparator)
        builtinShape(fqn)?.typeParameterVariances?.takeIf { it.isNotEmpty() }?.let { return it }
        return typeShape(fqn)?.typeParameterVariances ?: emptyList()
    }

    /** `kotlin.FunctionN`'s declaration-site variance: `n` contravariant parameter positions then one covariant
     *  result (`Function1<in P1, out R>`). Null when [fqn] isn't a function type. */
    private fun functionTypeVariance(fqn: String): List<String>? {
        val tail = fqn.substringAfterLast('.')
        if (!tail.startsWith("Function")) return null
        val n = tail.removePrefix("Function").toIntOrNull() ?: return null
        return List(n) { "in" } + "out"
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
     * The DIRECT nested types of the SOURCE type [fqn] (`Outer.Nested` — a nested `class`/`object`/`interface`,
     * a sealed type's nested subclasses), prefix-filtered, as STATIC type candidates. `SourceIndex` keeps a
     * nested type OUT of its outer's member list (registering it as its own top-level [RawClass] by FQN), so
     * — unlike a classpath binary, whose nested types arrive as STATIC members of its shape ([KotlinMetadata])
     * and thus flow through [membersForCompletion] — a source type's nested types would otherwise never appear
     * at `Outer.`. This surfaces them there, matching the binary behavior. Companion objects (offered via
     * [companionObjectSymbol]) and local/anonymous types are excluded; empty for a non-source / unknown [fqn].
     */
    fun nestedTypesOf(fqn: String, namePrefix: String = ""): List<KotlinSymbol> {
        val m = model()
        m.classByFqn[fqn] ?: return emptyList()
        val matcher = PrefixMatcher(namePrefix)
        val outerPrefix = "$fqn."
        return m.classByFqn.values.mapNotNull { rc ->
            if (rc.isCompanion || rc.isLocal) return@mapNotNull null
            if (!rc.fqn.startsWith(outerPrefix)) return@mapNotNull null
            if ('.' in rc.fqn.substring(outerPrefix.length)) return@mapNotNull null // direct child only
            if (namePrefix.isNotEmpty() && !matcher.matches(rc.simpleName)) return@mapNotNull null
            KotlinSymbol(
                rc.simpleName, rawClassKind(rc), typeByFqn(rc.fqn),
                modifiers = setOf(Modifier.STATIC), origin = SOURCE, declarationNode = rc.node,
            )
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

    /** The sealed subtypes of [fqn] as type-name completion candidates — name/kind/type/import shape identical
     *  to [typeNameCandidates] (source classes carry their declaration node + real kind, library ones a plain
     *  CLASS), prefix-filtered. Null when [fqn] is not a known sealed type (mirrors [sealedSubclassesOf]). Drives
     *  `when (subject) { is <caret> }` on a sealed subject. */
    fun sealedSubtypeCandidates(fqn: String, prefix: String): List<KotlinSymbol>? {
        val subs = sealedSubclassesOf(fqn) ?: return null
        val m = model()
        val matcher = PrefixMatcher(prefix)
        return subs.mapNotNull { sub ->
            val simple = sub.substringAfterLast('.')
            if (prefix.isNotEmpty() && !matcher.matches(simple)) return@mapNotNull null
            val rc = m.classByFqn[sub]
            if (rc != null) KotlinSymbol(rc.simpleName, rawClassKind(rc), typeByFqn(rc.fqn), origin = SOURCE, declarationNode = rc.node)
            else KotlinSymbol(simple, SymbolKind.CLASS, typeByFqn(sub), origin = BINARY)
        }
    }

    /**
     * The DIRECT inheritors (subtypes) of ANY type [superFqn] — the reverse direction of [supertypesOf] and the
     * generalization of [sealedSubclassesOf] to non-sealed types. Reads the [dev.ide.index.SubtypeIndex] family
     * (its binary / Java-source / Kotlin-source producers, all already built + registered), merged and
     * de-duplicated by subtype FQN. The index is keyed by the supertype's SHORT name (a resolution-free source
     * parse can't reliably qualify `: Base`), so this filters the bucket by the recorded
     * [dev.ide.index.SubtypeValue.supertype]: an exact FQN match (binary / resolved source), the JVM⇄Kotlin
     * mapped alias (`kotlin.Throwable`⇄`java.lang.Throwable`), or a bare short-name match when the source
     * producer left it unresolved (an unqualified `: Base` still matches — a possible homonym the caller can
     * confirm through resolution). Empty when no index is wired. Reflects the LAST-BUILT index (a just-typed
     * subclass appears after the source side reindexes), so it suits navigation, not exhaustiveness (which
     * stays on the always-complete [sealedSubclassesOf] model walk).
     */
    fun directInheritors(superFqn: String): List<dev.ide.index.SubtypeValue> {
        val idx = index ?: return emptyList()
        val short = superFqn.substringAfterLast('.')
        // Match either FQN form: bytecode records `java.lang.Throwable`, source may resolve to `kotlin.Throwable`.
        val targets = setOfNotNull(superFqn, Builtins.javaTypeFor(superFqn), Builtins.kotlinTypeFor(superFqn))
        val seen = HashSet<String>()
        val out = ArrayList<dev.ide.index.SubtypeValue>()
        for (id in dev.ide.index.SubtypeIndex.ALL) {
            for (v in idx.exact<dev.ide.index.SubtypeValue>(id, dev.ide.index.SubtypeIndex.key(superFqn))) {
                val sup = v.supertype.substringBefore('<').trim()
                val matches = sup in targets || ('.' !in sup && sup == short)
                if (matches && seen.add(v.fqn)) out += v
            }
        }
        return out
    }

    /**
     * The TRANSITIVE inheritor closure of [superFqn] (direct + indirect subtypes), breadth-first from
     * [superFqn] with a visited guard (cycle-safe) and a [limit] cap (a pathological hierarchy can't spin).
     * De-duplicated by FQN; deterministic BFS order. Built on [directInheritors], so the same freshness caveat
     * applies.
     */
    fun allInheritors(superFqn: String, limit: Int = 500): List<dev.ide.index.SubtypeValue> {
        val seen = hashSetOf(superFqn)
        val out = ArrayList<dev.ide.index.SubtypeValue>()
        val queue = ArrayDeque<String>().apply { add(superFqn) }
        while (queue.isNotEmpty() && out.size < limit) {
            for (v in directInheritors(queue.removeFirst())) {
                if (seen.add(v.fqn)) { out += v; queue.add(v.fqn) }
            }
        }
        return out
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
     * A user-facing display name for a synthetic local/anonymous type key (`$L<ordinal>`): a local
     * `class`/`object`'s real name, or `<anonymous>` (with its single declared supertype — `<anonymous :
     * Doc>` — when it has one) for an anonymous `object`. Null when [typeFqn] isn't a known synthetic local
     * type, so the renderer falls back to its own handling. Keeps the internal ordinal key out of type-mismatch
     * messages / inlay hints while still naming a `class Local` as `Local`.
     */
    override fun localTypeDisplayName(typeFqn: String): String? {
        val rc = model().classByFqn[typeFqn]?.takeIf { it.isLocal } ?: return null
        // A local NAMED class/object carries its real simple name; an anonymous object's is the `$L` key.
        if (rc.simpleName.isNotEmpty() && !TypeRendering.isSyntheticLocalName(rc.simpleName)) return rc.simpleName
        val superName = rc.superTypeTexts.firstOrNull()
            ?.substringBefore('<')?.substringAfterLast('.')?.trim()?.removeSuffix("()")?.trim()
        return if (superName.isNullOrEmpty()) "<anonymous>" else "<anonymous : $superName>"
    }

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

    /** Whether [fqn] is an INTERFACE (as opposed to a class) — an interface is bare in a supertype list, a class
     *  (even abstract) has a constructor that must be initialized. Null when [fqn] is unknown (→ back off). */
    fun isInterfaceType(fqn: String): Boolean? {
        sourceClass(fqn)?.let { return it.isInterface }
        (typeShape(fqn) ?: builtinShape(fqn))?.let { return it.isInterface }
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
                ?: inferReturnFromBody(rc, ownerFqn),
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
            // Each type parameter's upper bound (unbounded → `Any`, matching the Java-bytecode convention), for the
            // explicit-type-argument bound check and unbound-parameter erasure. Only when bounds were captured.
            typeParameterBounds = if (rc.typeParameterBounds.any { it.isNotBlank() })
                rc.typeParameterBounds.map { b -> (if (b.isBlank()) null else typeFromText(b, rc.ctx)) ?: typeByFqn("kotlin.Any") ?: KotlinType("kotlin.Any") }
            else emptyList(),
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
            isInfix = rc.isInfix,
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
    private fun inferReturnFromBody(rc: RawCallable, ownerFqn: String? = null): KotlinType? {
        val dom = rc.node as? dev.ide.lang.kotlin.parse.KotlinDomNode ?: return null
        // What to type: an expression body / property initializer (inferred directly), or a `by` delegate
        // (resolved through its `value` member — the State/Lazy convention, matching [KotlinResolver.localVar]
        // and [sameFileProperty]; the initializer is null on a delegated property, the value lives in `by`).
        val body: org.jetbrains.kotlin.psi.KtExpression
        val delegate: org.jetbrains.kotlin.psi.KtExpression?
        when (val psi = dom.psi) {
            is org.jetbrains.kotlin.psi.KtNamedFunction -> {
                if (psi.hasBlockBody()) return null
                body = psi.bodyExpression ?: return null; delegate = null
            }
            is org.jetbrains.kotlin.psi.KtProperty -> {
                val init = psi.initializer
                if (init != null) { body = init; delegate = null }
                else { delegate = psi.delegateExpression ?: return null; body = delegate }
            }
            else -> return null
        }
        inferredBodyTypeMemo[rc]?.let { return it.value }
        val guard = inferringBody.get()
        if (!guard.add(rc)) return null // re-entrant (self/mutual recursion) → break the cycle, don't cache
        // Mark this member's owner in-flight so enumerating that class's OWN members ([ownAndInheritedCached])
        // doesn't pin a partial list in which this member is re-entrant-null (the generic-delegate case).
        if (ownerFqn != null) pushInferringOwner(ownerFqn)
        val result = try {
            val resolver = dev.ide.lang.kotlin.resolve.KotlinResolver(dom.owner.ktFile, dom.owner, this)
            val inferred = if (delegate != null) resolver.delegatedValueType(delegate) else resolver.inferType(body)
            // An anonymous-object body escaping via a NON-local, NON-private declaration is approximated to its
            // denotable supertype (Kotlin's rule — the anonymous type isn't nameable outside its scope), so
            // `fun giveMe() = object { val player = … }` returns `Any` and `giveMe().player` is unresolved.
            val decl = dom.psi as? org.jetbrains.kotlin.psi.KtDeclaration
            if (inferred != null && decl != null) resolver.approximateEscapingLocalType(inferred, decl) else inferred
        } catch (t: Throwable) {
            null
        } finally {
            guard.remove(rc)
            if (ownerFqn != null) popInferringOwner(ownerFqn)
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
