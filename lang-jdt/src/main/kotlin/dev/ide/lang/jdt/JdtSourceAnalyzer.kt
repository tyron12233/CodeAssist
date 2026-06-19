package dev.ide.lang.jdt

import dev.ide.lang.AnalysisResult
import dev.ide.lang.CompilationContext
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.completion.CompletionService
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.incremental.IncrementalParser
import dev.ide.lang.incremental.ReparseResult
import dev.ide.lang.dom.TextRange
import dev.ide.lang.jdt.completion.JdtCompletionService
import dev.ide.lang.jdt.dom.JdtDomNode
import dev.ide.lang.jdt.dom.JdtParsedFile
import dev.ide.lang.jdt.resolve.JdtScope
import dev.ide.lang.jdt.resolve.JdtSymbol
import dev.ide.lang.jdt.resolve.JdtTypeRef
import dev.ide.lang.jdt.resolve.collectFields
import dev.ide.lang.jdt.resolve.collectMethods
import dev.ide.lang.resolve.ResolveResult
import dev.ide.lang.resolve.Scope
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.TypeRef
import dev.ide.model.LanguageLevel
import dev.ide.platform.Disposable
import dev.ide.vfs.VirtualFile
import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.Expression
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.Name
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.Paths

/**
 * Editor-time analyzer on Eclipse JDT. Each parse builds a fresh, error-tolerant [ASTParser]
 * (statement + binding recovery) configured from the model's [CompilationContext]: library jars on
 * the classpath, source roots (this module + its dependencies) on the sourcepath, and the platform
 * from the boot classpath. Re-reading the sourcepath each parse means edits saved to dependency files
 * are picked up immediately.
 */
class JdtSourceAnalyzer(ctx: CompilationContext) : SourceAnalyzer, Disposable {

    /** Project source roots only (this module + dependencies) — for unit-name/FQCN derivation. */
    val sourceRootPaths: List<Path> =
        ctx.sourceRoots.mapNotNull { runCatching { Paths.get(it.path) }.getOrNull() }.filter { Files.isDirectory(it) }

    /** Source roots for the name environment: project roots + any synthetic-stub platform dir. */
    val completionSourceRoots: List<Path>
    val classpathJarPaths: List<Path>
    val jdkHome: Path?
    val complianceLevel: Long

    /**
     * Recovers method parameter names + javadoc from source, for inlay hints and completion. Reads project
     * source dirs, library `-sources.jar`s ([CompilationContext.sourceAttachments]), the JDK `src.zip` (derived
     * from the boot JDK image), and the Android platform `sources/` dir (derived from `android.jar`). Shared so
     * the parse caches are reused across completion and hints.
     */
    var sourceMethodResolver: SourceMethodResolver
        private set
    private val baseSourceDirs: List<Path>
    private val baseSourceJars: List<Path>

    /** Immutable library/SDK SOURCE archives + dirs (`-sources.jar`, JDK `src.zip`, Android `sources/`) — no
     *  project source. The host feeds these to the source-doc index ([IndexScope.sourceArchives]). */
    var librarySourceArchives: List<Path> = emptyList()
        private set

    /** Live editor buffers (FQCN -> source) for in-memory completion; set by the host (e.g. IdeServices). */
    var overlayProvider: () -> Map<String, CharArray> = { emptyMap() }

    /** The workspace index, for unimported-class + package completion; set by the host. */
    var indexService: dev.ide.index.IndexService? = null

    private val sourcepath: Array<String>
    private val classpath: Array<String>
    private val includeVmBootclasspath: Boolean
    private val compilerOptions: Map<String, String>

    init {
        val bootDirs = ctx.bootClasspath.entries.mapNotNull { runCatching { Paths.get(it.root.path) }.getOrNull() }.filter { Files.isDirectory(it) }
        val isJdkImage = { p: Path -> Files.exists(p.resolve("lib/modules")) || Files.isDirectory(p.resolve("jmods")) }
        val syntheticStubDirs = bootDirs.filter { !isJdkImage(it) } // a dir of .java platform stubs
        val jars = (ctx.classpath.entries + ctx.bootClasspath.entries)
            .mapNotNull { runCatching { Paths.get(it.root.path) }.getOrNull() }
            .filter { it.toString().endsWith(".jar") && Files.isRegularFile(it) }

        completionSourceRoots = sourceRootPaths + syntheticStubDirs
        classpathJarPaths = jars
        jdkHome = bootDirs.firstOrNull(isJdkImage)
            ?: if (jars.isEmpty() && syntheticStubDirs.isEmpty()) runCatching { Paths.get(System.getProperty("java.home")) }.getOrNull() else null
        complianceLevel = complianceLevelOf(ctx.languageLevel)

        sourcepath = (sourceRootPaths + syntheticStubDirs).map { it.toString() }.toTypedArray()
        classpath = jars.map { it.toString() }.toTypedArray()
        // Whether `android.jar` is the platform (no real modular JDK underneath) — true on-device (ART) and
        // for an Android project on desktop.
        val isAndroidPlatform = jars.any { it.fileName?.toString() == "android.jar" }
        // On a real JDK, the public DOM ASTParser needs a recognized system library or it throws "Missing
        // system library"; a plain classpath jar isn't one, so it must be handed the running VM's modular JRE.
        // On an android.jar platform, DON'T include the VM bootclasspath:
        //   - On-device the host VM is ART, whose "bootclasspath" enumerates native `.so` files under
        //     /apex/com.android.art/lib; ecj opens each as a jar → noisy per-parse `ZipException`s.
        //   - android.jar itself ships java.* (377 java.util classes, etc.), so the VM's `java.base` module
        //     plus android.jar's classpath copy make java.* "accessible from more than one module" — `Locale`
        //     and friends then fail to resolve (the split).
        // android.jar is already on the classpath and supplies java.*, so omitting the VM bootclasspath
        // resolves java.* from android.jar alone — no .so scan, no module split — matching the low-level
        // compiler path used for diagnostics/completion (see [diagnose]).
        includeVmBootclasspath = !isAndroidPlatform

        @Suppress("UNCHECKED_CAST")
        compilerOptions = (JavaCore.getOptions() as MutableMap<String, String>).also {
            JavaCore.setComplianceOptions(complianceOf(ctx.languageLevel), it)
        }

        // Source attachments for names/javadoc: library -sources.jars (+ exploded source dirs), the JDK
        // src.zip (under the boot JDK image), and the Android platform sources dir (sibling of android.jar).
        val attachments = ctx.sourceAttachments.mapNotNull { runCatching { Paths.get(it.path) }.getOrNull() }
        val attachmentJars = attachments.filter { val s = it.toString(); (s.endsWith(".jar") || s.endsWith(".zip")) && Files.isRegularFile(it) }
        val attachmentDirs = attachments.filter { Files.isDirectory(it) }
        val jdkSrcZip = jdkHome?.resolve("lib")?.resolve("src.zip")?.takeIf { Files.isRegularFile(it) }
        val androidSources = (ctx.classpath.entries + ctx.bootClasspath.entries)
            .mapNotNull { runCatching { Paths.get(it.root.path) }.getOrNull() }
            .firstOrNull { it.fileName?.toString() == "android.jar" }
            ?.let { jar -> androidPlatformSources(jar) }
        baseSourceDirs = sourceRootPaths + attachmentDirs + listOfNotNull(androidSources)
        baseSourceJars = attachmentJars + listOfNotNull(jdkSrcZip)
        librarySourceArchives = (baseSourceJars + attachmentDirs + listOfNotNull(androidSources)).distinct()
        sourceMethodResolver = SourceMethodResolver(baseSourceDirs, baseSourceJars)
    }

    /**
     * The Android platform `sources/android-NN` dir for a `platforms/android-NN/android.jar`, so framework
     * APIs complete with real parameter names + javadoc. Prefers the exact platform-dir name, but falls back
     * to any installed `sources/android-NN…` with the same MAJOR API level: the SDK ships framework sources
     * keyed by base level (`android-36`) while the platform jar may be a minor/extension revision (`android-36.1`)
     * — an exact-name-only match would silently miss the sources whenever the two don't line up.
     */
    private fun androidPlatformSources(jar: Path): Path? {
        val platformDir = jar.parent ?: return null
        val sourcesRoot = platformDir.parent?.parent?.resolve("sources") ?: return null
        val exact = sourcesRoot.resolve(platformDir.fileName.toString())
        if (Files.isDirectory(exact)) return exact
        if (!Files.isDirectory(sourcesRoot)) return null
        val major = androidMajor(platformDir.fileName.toString()) ?: return null
        return Files.list(sourcesRoot).use { stream ->
            stream.filter { Files.isDirectory(it) && androidMajor(it.fileName.toString()) == major }
                .sorted(compareByDescending { it.fileName.toString() })
                .findFirst().orElse(null)
        }
    }

    /** `android-36` / `android-36.1` → 36 (the base API level, ignoring the minor/extension revision). */
    private fun androidMajor(dirName: String): Int? =
        dirName.removePrefix("android-").substringBefore('.').toIntOrNull()

    /** Add extra source archives (e.g. a downloaded JDK `src.zip`) for names/javadoc, rebuilding the resolver. */
    fun addSourceJars(extra: List<Path>) {
        val present = extra.filter { Files.isRegularFile(it) }
        val jars = (baseSourceJars + present).distinct()
        if (jars.size != baseSourceJars.size) {
            sourceMethodResolver = SourceMethodResolver(baseSourceDirs, jars)
            librarySourceArchives = (librarySourceArchives + present).distinct()
        }
    }

    override val incrementalParser: IncrementalParser = JdtIncrementalParser(this)

    /** Completion runs on the custom name environment (in-memory), not the DOM/disk path. */
    override val completion: CompletionService = JdtCompletionService(this)

    /** Inlay hints (var/lambda types, parameter names, chaining) over the binding DOM. */
    override val inlayHints: dev.ide.lang.hints.InlayHintService = JdtInlayHintService(this)

    /** Type-aware semantic coloring (fields vs locals, real types, static/final) over the binding DOM. */
    override val semanticHighlighter: dev.ide.lang.highlight.SemanticHighlightService =
        dev.ide.lang.jdt.highlight.JdtSemanticHighlighter(this)

    /** Structural code folding (imports, type/method bodies, comments) over the syntactic AST. */
    override val folding: dev.ide.lang.folding.FoldingService =
        dev.ide.lang.jdt.folding.JdtCodeFolder(this)

    /**
     * Release the per-module environment cache (open library-jar handles). The host registers each analyzer
     * with the platform disposer, so this runs when the workspace closes; the shared platform jrt image is
     * process-lived and is not affected.
     */
    override fun dispose() {
        (completion as? JdtCompletionService)?.dispose()
    }

    /** FQCN of a file from its unit name ("com/example/Main.java" -> "com.example.Main"). */
    fun fqcnFor(file: VirtualFile): String = unitNameFor(file).removeSuffix(".java").replace('/', '.')

    private fun complianceLevelOf(level: LanguageLevel): Long = when (level) {
        LanguageLevel.JAVA_8 -> ClassFileConstants.JDK1_8
        LanguageLevel.JAVA_11 -> ClassFileConstants.JDK11
        LanguageLevel.JAVA_17 -> ClassFileConstants.JDK17
        LanguageLevel.JAVA_21 -> ClassFileConstants.JDK21
    }

    /**
     * Error-tolerant parse with resolved bindings. Fresh parser per call (re-reads the sourcepath, so
     * saved edits to dependencies are seen). The in-memory [text] is the focal unit; if the same file
     * also exists on the sourcepath it is briefly shadowed aside so JDT does not see a duplicate type
     * definition (which would abort binding resolution) — then restored.
     */
    /**
     * Single-entry, content-keyed cache of the last binding-resolved parse. A settled buffer is parsed for
     * MULTIPLE editor features in close succession — inlay hints, semantic highlighting, and any caret query
     * (resolve/scopeAt/expectedTypeAt) — all on the same text; without this each pays the full binding parse
     * (a fresh ASTParser + classpath/sourcepath environment scan + the shadow-file move). Reused while the
     * focal (path, text) is unchanged; the focal edit changes the text → a miss, and a disk write to any
     * dependency invalidates it via [invalidateBindingCache] (binding resolution reads dependencies from disk,
     * so a same-text focal parse is otherwise sound). The engine runs single-threaded; [Volatile] is cheap insurance.
     */
    private class BindingParseCacheEntry(val path: String, val text: String, val parsed: JdtParsedFile)

    @Volatile
    private var bindingCache: BindingParseCacheEntry? = null

    /** Drop the cached binding parse — call when disk changed under us (a save of this or any dependency). */
    fun invalidateBindingCache() {
        bindingCache = null
    }

    fun parse(file: VirtualFile, text: CharSequence): JdtParsedFile {
        val t = text.toString()
        bindingCache?.let { if (it.path == file.path && it.text == t) return it.parsed }
        val parsed = parseUncached(file, t)
        bindingCache = BindingParseCacheEntry(file.path, t, parsed)
        return parsed
    }

    private fun parseUncached(file: VirtualFile, text: CharSequence): JdtParsedFile {
        val onDisk = onSourcepathFile(file) ?: return doParse(file, text)
        val backup = onDisk.resolveSibling("${onDisk.fileName}.codeassist-shadow")
        val moved = runCatching { Files.move(onDisk, backup, StandardCopyOption.REPLACE_EXISTING); true }.getOrDefault(false)
        try {
            return doParse(file, text)
        } finally {
            if (moved) runCatching { Files.move(backup, onDisk, StandardCopyOption.REPLACE_EXISTING) }
        }
    }

    private fun doParse(file: VirtualFile, text: CharSequence): JdtParsedFile {
        val parser = ASTParser.newParser(AST.getJLSLatest())
        parser.setKind(ASTParser.K_COMPILATION_UNIT)
        parser.setCompilerOptions(HashMap(compilerOptions))
        parser.setUnitName(unitNameFor(file))
        parser.setEnvironment(classpath, sourcepath, null, includeVmBootclasspath)
        parser.setResolveBindings(true)
        parser.setStatementsRecovery(true)
        parser.setBindingsRecovery(true)
        parser.setSource(text.toString().toCharArray())
        val cu = parser.createAST(null) as CompilationUnit
        return JdtParsedFile(file, 0L, cu, text)
    }

    /**
     * A **syntax-only** tolerant parse: the error-recovering DOM tree with bindings *off*. Because no
     * bindings are resolved, JDT does no classpath/sourcepath environment scan and there is no duplicate-type
     * concern, so this skips both the disk re-scan and the shadow-file move `doParse` pays — it is several
     * times cheaper. It is what the editor's per-edit work (analyzers walking node kinds, block projection,
     * suppression scanning, structural `nodeAt`) actually needs; binding-level diagnostics come separately
     * from [diagnose] (the cached in-memory compiler), and the on-demand binding queries ([resolve],
     * [scopeAt], [expectedTypeAt]) still use the full binding-resolving [parse].
     */
    fun parseSyntactic(file: VirtualFile, text: CharSequence): JdtParsedFile {
        val parser = ASTParser.newParser(AST.getJLSLatest())
        parser.setKind(ASTParser.K_COMPILATION_UNIT)
        parser.setCompilerOptions(HashMap(compilerOptions))
        parser.setResolveBindings(false)
        parser.setStatementsRecovery(true)
        parser.setSource(text.toString().toCharArray())
        val cu = parser.createAST(null) as CompilationUnit
        return JdtParsedFile(file, 0L, cu, text)
    }

    /**
     * Binding-level diagnostics for [file]'s buffer [text], produced by the cached low-level compiler
     * (shared in-memory environment) rather than a disk-scanning binding DOM parse. [dom] is an
     * already-parsed syntactic tree to reuse for the broken-statement noise filtering (the analysis engine
     * passes its target's tree); when null, a syntactic parse is done here. The compiler runs with the same
     * `JavaCore` options as the DOM analyzer, so the problem set matches — see [JdtParsedFile.diagnosticsFrom].
     */
    fun diagnose(file: VirtualFile, text: CharSequence, dom: ParsedFile? = null): List<dev.ide.lang.dom.Diagnostic> {
        // Diagnostics ALWAYS come from the low-level compiler over the custom name environment — never from a
        // DOM binding parse's `cu.problems`. The DOM ASTParser is disk-only and, on an android.jar platform,
        // forces a java.base/<unnamed> split that the low-level path avoids (it resolves java.* from
        // android.jar alone). The [dom] tree, if supplied, is reused only for the broken-statement noise
        // filtering (statement ranges — structural, so a syntactic or binding tree both work); otherwise a
        // cheap syntactic parse is done here.
        val service = completion as? JdtCompletionService ?: return (dom as? JdtParsedFile)?.diagnostics ?: emptyList()
        val problems = service.resolveProblems(fqcnFor(file), text.toString(), overlayProvider(), compilerOptions)
        val tree = (dom as? JdtParsedFile) ?: parseSyntactic(file, text)
        return tree.diagnosticsFrom(problems)
    }

    private fun onSourcepathFile(file: VirtualFile): Path? {
        val p = runCatching { Paths.get(file.path).toAbsolutePath().normalize() }.getOrNull() ?: return null
        val underRoot = sourceRootPaths.any { p.startsWith(it.toAbsolutePath().normalize()) }
        return if (underRoot && Files.isRegularFile(p)) p else null
    }

    override suspend fun parsedFile(file: VirtualFile): ParsedFile = parse(file, file.readText())

    override suspend fun analyze(file: VirtualFile): AnalysisResult =
        AnalysisResult(file, diagnose(file, file.readText()))

    /**
     * The enclosing declarations at [offset], outermost→innermost (e.g. `["Outer", "Inner", "method"]`) —
     * for a cursor-tracking breadcrumb. Walks the DOM parent chain from the node under the caret, collecting
     * type and method/constructor names. Empty when the caret sits outside any type (imports, package line).
     */
    fun enclosingStructure(file: VirtualFile, text: CharSequence, offset: Int): List<String> {
        // Structure only (enclosing type/method names) — no bindings needed, so use the cheap syntactic parse
        // that skips the classpath/sourcepath environment scan and the shadow-file move. This runs per caret
        // move (breadcrumb), so the binding parse's cost (and, on android, its classpath work) isn't warranted.
        val pf = parseSyntactic(file, text)
        var node: ASTNode? = (pf.nodeAt(offset) as? JdtDomNode)?.node
        val out = ArrayDeque<String>()
        while (node != null) {
            when (node) {
                is MethodDeclaration -> node.name?.identifier?.takeIf { it.isNotEmpty() }?.let { out.addFirst(it) }
                is AbstractTypeDeclaration -> node.name?.identifier?.takeIf { it.isNotEmpty() }?.let { out.addFirst(it) }
            }
            node = node.parent
        }
        return out.toList()
    }

    override fun resolve(node: DomNode): ResolveResult {
        val jp = node as? JdtDomNode ?: return ResolveResult.Unresolved
        val target = (jp.node as? Name) ?: return ResolveResult.Unresolved
        val binding = target.resolveBinding() ?: return ResolveResult.Unresolved
        return ResolveResult.Resolved(JdtSymbol(binding))
    }

    override fun scopeAt(file: VirtualFile, offset: Int): Scope {
        val pf = parse(file, file.readText())
        val node = (pf.nodeAt(offset) as? JdtDomNode)?.node ?: return JdtScope(emptyList())
        val type = enclosingType(node) ?: return JdtScope(emptyList())
        val symbols = collectMethods(type).map { JdtSymbol(it) } + collectFields(type).map { JdtSymbol(it) }
        return JdtScope(symbols)
    }

    override fun expectedTypeAt(file: VirtualFile, offset: Int): TypeRef? {
        val pf = parse(file, file.readText())
        val node = (pf.nodeAt(offset) as? JdtDomNode)?.node
        val type = (node as? Expression)?.resolveTypeBinding()
        return type?.let { JdtTypeRef(it) }
    }

    override fun resolveType(node: DomNode): TypeRef? {
        val jp = node as? JdtDomNode ?: return null
        // Fast path: the node already comes from a binding-resolved parse.
        (jp.node as? Expression)?.resolveTypeBinding()?.let { return JdtTypeRef(it) }
        // Slow path: editor actions hand us a node from the cheap syntax-only tree (bindings off). Re-parse
        // the node's OWN file + text with bindings — same text ⇒ same structure, so no disk/buffer staleness —
        // and relocate the expression by its range. Only paid when a refactoring actually asks for the type.
        val pf = parse(jp.pf.file, jp.pf.text())
        val expr = expressionCovering(pf, node.range.start, node.range.end) ?: return null
        return expr.resolveTypeBinding()?.let { JdtTypeRef(it) }
    }

    /** Smallest JDT [Expression] in [pf] whose range covers [[start], [end]). */
    private fun expressionCovering(pf: JdtParsedFile, start: Int, end: Int): Expression? {
        var n: ASTNode? = (pf.nodeAt(start) as? JdtDomNode)?.node
        while (n != null) {
            if (n is Expression && n.startPosition <= start && n.startPosition + n.length >= end) return n
            n = n.parent
        }
        return null
    }

    private fun enclosingType(node: ASTNode): ITypeBinding? {
        var n: ASTNode? = node
        while (n != null) {
            if (n is AbstractTypeDeclaration) return n.resolveBinding()
            n = n.parent
        }
        return null
    }

    private fun unitNameFor(file: VirtualFile): String {
        val p = runCatching { Paths.get(file.path).toAbsolutePath().normalize() }.getOrNull() ?: return file.name
        for (root in sourceRootPaths) {
            val r = root.toAbsolutePath().normalize()
            if (p.startsWith(r)) return r.relativize(p).toString().replace(File.separatorChar, '/')
        }
        return file.name
    }

    private fun complianceOf(level: LanguageLevel): String = when (level) {
        LanguageLevel.JAVA_8 -> JavaCore.VERSION_1_8
        LanguageLevel.JAVA_11 -> JavaCore.VERSION_11
        LanguageLevel.JAVA_17 -> JavaCore.VERSION_17
        LanguageLevel.JAVA_21 -> JavaCore.VERSION_21
    }
}

/**
 * Full-reparse incremental parser (JDT recovers broken code, so a full reparse is always usable). Uses the
 * **syntactic** parse: the editor-time consumers of the tree (analyzers, block projection, suppression,
 * structural navigation) do not need resolved bindings, and avoiding them skips the disk-environment scan
 * and shadow-file move. Binding-level diagnostics come from [JdtSourceAnalyzer.diagnose]; on-demand binding
 * queries use the full [JdtSourceAnalyzer.parse].
 */
class JdtIncrementalParser(private val analyzer: JdtSourceAnalyzer) : IncrementalParser {
    override fun parseFull(snapshot: DocumentSnapshot): ParsedFile = analyzer.parseSyntactic(snapshot.file, snapshot.text)
    override fun reparse(previous: ParsedFile, newSnapshot: DocumentSnapshot, edits: List<DocumentEdit>): ReparseResult =
        ReparseResult(analyzer.parseSyntactic(newSnapshot.file, newSnapshot.text), TextRange(0, newSnapshot.length()), reusedSubtrees = 0)
}
