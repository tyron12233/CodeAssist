package dev.ide.lang.java

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil
import dev.ide.index.ClassNameValue
import dev.ide.index.IndexId
import dev.ide.index.IndexService
import dev.ide.lang.AnalysisResult
import dev.ide.lang.JvmIndexScopeProvider
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.completion.CompletionContribution
import dev.ide.lang.folding.FoldingService
import dev.ide.lang.highlight.SemanticHighlightService
import dev.ide.lang.hints.InlayHintService
import dev.ide.lang.signature.SignatureHelpService
import dev.ide.lang.java.completion.JavaCompletion
import dev.ide.lang.java.services.JavaFolder
import dev.ide.lang.java.services.JavaInlayHints
import dev.ide.lang.java.services.JavaSemanticHighlighter
import dev.ide.lang.java.services.JavaSignatureHelp
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.incremental.IncrementalParser
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.lang.java.parse.JavaDomNode
import dev.ide.lang.java.parse.JavaIncrementalParser
import dev.ide.lang.java.parse.JavaParsedFile
import dev.ide.lang.java.resolve.JavaScope
import dev.ide.lang.java.resolve.JavaSymbol
import dev.ide.lang.java.resolve.JavaTypeRef
import dev.ide.lang.java.resolve.symbolKindOf
import dev.ide.lang.resolve.DocFormat
import dev.ide.lang.resolve.QuickDocInfo
import dev.ide.lang.resolve.ResolveResult
import dev.ide.lang.resolve.Scope
import dev.ide.lang.resolve.StructureItem
import dev.ide.lang.resolve.TypeRef
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The Java [SourceAnalyzer] over IntelliJ's native resolution engine (held by [env]). Reference resolution,
 * expression typing, and member enumeration all delegate to PSI (`resolve()`, `getType()`, the Cls/source
 * supertype graph), so the backend inherits IntelliJ's Java semantics rather than re-deriving them.
 *
 * This first cut lands parse + DOM + resolution + structure/quick-doc. Semantic diagnostics beyond syntax,
 * completion, and the editor-QoL services (folding, highlight, signature, formatting) layer on next.
 */
class JavaSourceAnalyzer(private val env: JavaEnvironment) : SourceAnalyzer, JvmIndexScopeProvider {

    private val javaIncrementalParser = JavaIncrementalParser(env)
    override val incrementalParser: IncrementalParser get() = javaIncrementalParser

    // --- JvmIndexScopeProvider: the roots this analyzer contributes to the workspace index scope ----------

    /** Library jars on the classpath (dirs / non-jar entries are excluded — those index via source roots). */
    override val classpathJarPaths: List<java.nio.file.Path> =
        env.classpath.filter { it.isFile && it.name.endsWith(".jar") }.map { it.toPath() }

    override val sourceRootPaths: List<java.nio.file.Path> =
        env.sourceRoots.filter { it.isDirectory }.map { it.toPath() }

    override val jdkHome: java.nio.file.Path? = env.jdkHome?.toPath()

    /** Attached library/SDK SOURCE archives. Seeded from the compilation context and grown by the host via
     *  [addSourceJars] / [addSourceDirs] (JDK `src.zip`, Android `sources/`) — mirrors the JDT analyzer. */
    override var librarySourceArchives: List<java.nio.file.Path> = emptyList()
        private set

    /** Host hook: attach library/JDK source archives (parity with `JdtSourceAnalyzer.addSourceJars`). */
    fun addSourceJars(extra: List<java.nio.file.Path>) {
        librarySourceArchives = (librarySourceArchives + extra.filter { java.nio.file.Files.exists(it) }).distinct()
    }

    /** Host hook: attach SDK source dirs (parity with `JdtSourceAnalyzer.addSourceDirs`). */
    fun addSourceDirs(extra: List<java.nio.file.Path>) {
        librarySourceArchives = (librarySourceArchives + extra.filter { java.nio.file.Files.exists(it) }).distinct()
    }

    /** The workspace index, injected by the host (ide-core) after construction; powers unimported-type /
     *  auto-import completion via the `java.classNames` index (mirrors the JDT/Kotlin analyzers). */
    var indexService: IndexService? = null

    /** Synthetic classes (Android R/BuildConfig/…) the facade should resolve, injected by the host. Forwarded
     *  to the env's injected element finder. */
    var syntheticClassProvider: () -> List<dev.ide.lang.synthetic.SyntheticClass>
        get() = env.syntheticProvider
        set(value) { env.syntheticProvider = value }

    /** Open-buffer overlay (FQN → live editor text) so a dependent resolves a not-yet-saved edit, injected by
     *  the host. Forwarded to the env's injected element finder. */
    var overlayProvider: () -> Map<String, CharArray>
        get() = env.overlayProvider
        set(value) { env.overlayProvider = value }

    /** Inheritor lookup for `new`-position subtype completion, injected by the host (a subtype-index BFS over
     *  a supertype FQN). Default empty keeps completion working index-free. */
    var subtypeIndexQuery: (String) -> List<JavaCompletion.IndexedType> = { emptyList() }

    private val completion: JavaCompletion = JavaCompletion(
        env,
        typeSearch = { prefix ->
            // Read `indexService` lazily (host sets it after construction). Simple-name prefix → candidate types.
            indexService?.prefix<ClassNameValue>(IndexId("java.classNames"), prefix, 50)
                ?.map { JavaCompletion.IndexedType(it.value.fqn, it.value.kind) }
                ?.toList()
                ?: emptyList()
        },
        subtypeSearch = { superFqn -> subtypeIndexQuery(superFqn) },
    )

    override fun completionContributions(): List<CompletionContribution> =
        listOf(CompletionContribution(completion))

    /** The current parsed PSI for a file, for the editor-QoL services (folding / highlight / inlay hints).
     *  Prefers the host's most recent LIVE-buffer parse (via the incremental parser) so tokens land on the
     *  unsaved text the editor shows; falls back to the on-disk content-hashed parse when none exists yet. */
    internal fun psiFor(file: VirtualFile) =
        javaIncrementalParser.latestFor(file)?.javaFile ?: cachedParse(file).javaFile

    /** Rename support for the host's RefactorService — kept behind neutral types (no PSI leaks to ide-core).
     *  [renameTargetAt] resolves the symbol under the caret; [renameReferencesIn] finds its occurrences in a
     *  file, given the target from a (possibly different) file. Both parse [text] in the resolution env. */
    fun renameTargetAt(name: String, text: CharSequence, offset: Int): dev.ide.lang.java.rename.JavaRenameTarget? =
        dev.ide.lang.java.rename.JavaRename.targetAt(env.parse(name, text), offset)

    fun renameReferencesIn(
        name: String, text: CharSequence, target: dev.ide.lang.java.rename.JavaRenameTarget,
    ): List<dev.ide.lang.dom.TextRange> =
        dev.ide.lang.java.rename.JavaRename.referencesIn(env.parse(name, text), target)

    override val importOrganizer: dev.ide.lang.imports.ImportOrganizerService = JavaImportOrganizer(env::parse)
    override val folding: FoldingService = JavaFolder(::psiFor)
    override val semanticHighlighter: SemanticHighlightService = JavaSemanticHighlighter(::psiFor)
    override val signatureHelp: SignatureHelpService = JavaSignatureHelp(env)
    override val inlayHints: InlayHintService = JavaInlayHints(::psiFor)

    private val version = AtomicLong(0)
    private val cache = ConcurrentHashMap<String, Pair<ContentHash, JavaParsedFile>>()

    /**
     * Drop cached synthetic/overlay class resolution (e.g. an Android `R` regenerated after a resource edit).
     * Without this the facade keeps resolving the STALE `R`, so a just-added `R.string.foo` stays unresolved in
     * code even though the resource exists. The host calls this on a synthetic/resource change (which does NOT
     * dispose the analyzer, to keep the warm classpath env). Also drops the per-file parse cache, whose
     * diagnostics were computed against the old `R`.
     */
    fun invalidateSyntheticClasses() {
        cache.clear()
        env.dropCaches()
    }

    /** Parse (and cache) [file] from its current on-disk bytes; re-parses when the content hash changes. */
    private fun cachedParse(file: VirtualFile): JavaParsedFile {
        val hash = file.contentHash()
        cache[file.path]?.let { if (it.first == hash) return it.second }
        val tree = JavaParsedFile(env.parse(file.name, file.readText()), file, version.incrementAndGet())
        cache[file.path] = hash to tree
        return tree
    }

    override suspend fun parsedFile(file: VirtualFile): ParsedFile = cachedParse(file)

    override suspend fun analyze(file: VirtualFile): AnalysisResult {
        val tree = cachedParse(file)
        // First cut: syntax diagnostics (PsiErrorElements, collected by the DOM). Semantic diagnostics
        // (unresolved symbol / type errors) require care to avoid false positives and land in a later step.
        return AnalysisResult(file, tree.diagnostics)
    }

    // --- resolution ---------------------------------------------------------------------------------------

    override fun resolve(node: DomNode): ResolveResult {
        val psi = (node as? JavaDomNode)?.psi ?: return ResolveResult.Unresolved
        val target = resolveTarget(psi) ?: return ResolveResult.Unresolved
        return ResolveResult.Resolved(JavaSymbol(target, node.owner))
    }

    /** Resolve the reference at (or just above) [psi] to its declaration, via IntelliJ's resolver. */
    private fun resolveTarget(psi: PsiElement): PsiElement? {
        var e: PsiElement? = psi
        var hops = 0
        while (e != null && hops++ < 4) {
            when (e) {
                is PsiMethodCallExpression -> return e.resolveMethod()
                is PsiNewExpression -> return e.classReference?.resolve() ?: e.resolveConstructor()
                is PsiReferenceExpression -> return e.resolve()
                is PsiJavaCodeReferenceElement -> return e.resolve()
            }
            e = e.parent
        }
        return null
    }

    override fun resolveType(node: DomNode): TypeRef? {
        val psi = (node as? JavaDomNode)?.psi ?: return null
        val expr = psi as? PsiExpression
            ?: PsiTreeUtil.getParentOfType(psi, PsiExpression::class.java, false)
        return expr?.type?.let { JavaTypeRef(it) }
    }

    override fun scopeAt(file: VirtualFile, offset: Int): Scope {
        val tree = cachedParse(file)
        val len = tree.javaFile.textLength
        val leaf = if (len == 0) null
        else tree.javaFile.findElementAt(offset.coerceIn(0, (len - 1).coerceAtLeast(0)))
        return JavaScope(leaf, offset, tree, env.facade, env.project)
    }

    override fun expectedTypeAt(file: VirtualFile, offset: Int): TypeRef? {
        val tree = cachedParse(file)
        val len = tree.javaFile.textLength
        if (len == 0) return null
        val leaf = tree.javaFile.findElementAt(offset.coerceIn(0, len - 1)) ?: return null
        // Variable initializer -> the declared type; a `return` -> the enclosing method's return type.
        PsiTreeUtil.getParentOfType(leaf, PsiVariable::class.java, false)?.let { v ->
            if (v.initializer != null) return JavaTypeRef(v.type)
        }
        PsiTreeUtil.getParentOfType(leaf, PsiReturnStatement::class.java, false)?.let {
            PsiTreeUtil.getParentOfType(it, PsiMethod::class.java)?.returnType?.let { rt ->
                return JavaTypeRef(rt)
            }
        }
        return null
    }

    // --- structure & quick-doc ----------------------------------------------------------------------------

    override fun fileStructure(file: VirtualFile, text: CharSequence): List<StructureItem> {
        val psi = env.parse(file.name, text)
        val out = ArrayList<StructureItem>()
        fun nameOffset(e: PsiElement): Int =
            (e as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: e.textOffset
        fun add(e: PsiElement, name: String, detail: String?, depth: Int) {
            out += StructureItem(
                name = name,
                detail = detail,
                kind = symbolKindOf(e),
                nameOffset = nameOffset(e),
                endOffset = e.textRange.endOffset,
                depth = depth,
            )
        }
        fun visit(cls: PsiClass, depth: Int) {
            add(cls, cls.name ?: "<anonymous>", null, depth)
            cls.fields.forEach { f -> add(f, f.name, f.type.presentableText, depth + 1) }
            cls.methods.forEach { m ->
                val params = m.parameterList.parameters.joinToString(", ") { it.type.presentableText }
                add(m, m.name, "($params)", depth + 1)
            }
            cls.innerClasses.forEach { visit(it, depth + 1) }
        }
        psi.classes.forEach { visit(it, 0) }
        return out
    }

    override fun quickDoc(file: VirtualFile, text: CharSequence, offset: Int): QuickDocInfo? {
        val psi = env.parse(file.name, text)
        val len = psi.textLength
        if (len == 0) return null
        val leaf = psi.findElementAt(offset.coerceIn(0, len - 1)) ?: return null
        val target = resolveTarget(leaf) ?: return null
        val sym = JavaSymbol(target)
        val owner = (target.parent as? PsiClass)?.qualifiedName
            ?: PsiTreeUtil.getParentOfType(target, PsiClass::class.java)?.qualifiedName
        return QuickDocInfo(
            signature = signatureOf(target),
            name = sym.name,
            kind = sym.kind,
            container = owner,
            doc = sym.documentation(),
            docFormat = DocFormat.JAVADOC,
        )
    }

    private fun signatureOf(psi: PsiElement): String = when (psi) {
        is PsiMethod -> {
            val params = psi.parameterList.parameters.joinToString(", ") {
                "${it.type.presentableText} ${it.name}"
            }
            "${psi.returnType?.presentableText ?: "void"} ${psi.name}($params)"
        }
        is PsiField -> "${psi.type.presentableText} ${psi.name}"
        is PsiLocalVariable -> "${psi.type.presentableText} ${psi.name}"
        is PsiClass -> psi.qualifiedName ?: (psi.name ?: "<anonymous>")
        else -> (psi as? com.intellij.psi.PsiNamedElement)?.name ?: psi.text
    }
}
