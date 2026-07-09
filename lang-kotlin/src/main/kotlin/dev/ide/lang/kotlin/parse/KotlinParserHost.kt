package dev.ide.lang.kotlin.parse

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.diagnostics.impl.BaseDiagnosticsCollector
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtFile
// The unshaded IntelliJ platform (:kotlin-compiler-deps), the same com.intellij.* world the K2 Analysis
// API runs on, now that the relocated kotlin-compiler-embeddable is gone.
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFileFactory

/**
 * The resolution-free PSI host. It stands up the Kotlin frontend's parsing layer exactly once and reuses
 * it for every file; its only job is `text -> KtFile`.
 *
 * It never builds a `BindingContext` or runs the analyzer/FIR: all semantic work (symbols, resolution,
 * inference, completion) is done on the neutral DOM. The PSI parser is error-tolerant: it emits
 * `PsiErrorElement`s and recovers on broken input, so a `KtFile` always covers the whole file, satisfying
 * the DOM's error-tolerance contract (essential because completion fires mid-edit).
 *
 * Lifetime: a process-wide singleton. Standing up the [KotlinCoreEnvironment] is the expensive (cold-start)
 * step, so it is created lazily, once, off the UI thread, and shared by every module's analyzer. Parsing is
 * cheap and amortized after that.
 *
 * Threading: `createFileFromText` is serialized under [lock]. PSI's standalone core is not guaranteed
 * thread-safe for concurrent file creation, and a single parse is fast, so a coarse lock is the simplest
 * correct choice. Crucially, `createFileFromText` only builds the tree the top-level parse eagerly demands;
 * `ILazyParseableElementType` nodes (KDoc comments above all) defer building their subtree until first
 * access. That deferred build would otherwise fire during DOM/index TRAVERSAL, which runs UNLOCKED, and
 * during a parallel index build (`KotlinSourceDocIndex` reads each declaration's `docComment.text` on the
 * bounded `Dispatchers.IO` fan-out) on several threads at once. Two concurrent `buildTree` calls on the
 * shared standalone PSI corrupt its non-thread-safe internals: a native SIGSEGV in `artInstanceOfFromCode`.
 * So every parse fully materializes its tree ([forceFullParse]) while still holding [lock]; after that, all
 * `buildTree` has happened under the lock, and later traversal only ever READS a built, immutable tree.
 */
object KotlinParserHost {

    private val lock = Any()

    // Held for the JVM lifetime; createForProduction roots an application-level environment kept alive.
    private val disposable = Disposer.newDisposable("lang-kotlin-parser")

    // createForProduction is the K1 standalone-parsing entry point. K2/the Analysis API is for resolution,
    // which is not needed here; pure parsing via the K1 core environment is correct and supported, just
    // opt-in-gated. Internals: CompilerConfiguration.put.
    @OptIn(
        CompilerConfiguration.Internals::class,
        org.jetbrains.kotlin.K1Deprecation::class,
    )
    private val environment: KotlinCoreEnvironment by lazy {
        // Keep the application environment alive across compiles too — must be set before this first creation.
        dev.ide.lang.kotlin.compile.KotlinEnvironmentKeepAlive.ensure()
        // CompilerConfiguration.create is what the CLI itself builds configurations with: it registers the
        // compiler-extensions storage createForProduction now REQUIRES ("Extensions storage is not
        // registered" otherwise), and installs the two collectors. Both are silenced here: compiler
        // diagnostics are discarded entirely (editor diagnostics come from PsiErrorElements), and the no-op
        // diagnostics collector matters on ART, where ProjectEnvironment.<init> reports a "JDK doesn't
        // support mapped buffer unmapping" warning through it (its getter throws when never set).
        val configuration = CompilerConfiguration.create(
            diagnosticsCollector = BaseDiagnosticsCollector.DoNothing,
            messageCollector = MessageCollector.NONE,
        ).apply {
            put(CommonConfigurationKeys.MODULE_NAME, "lang-kotlin-parser")
        }
        // JVM_CONFIG_FILES registers the JVM parser definitions (so the KtFile actually parses). No
        // classpath is configured: parsing only, never resolution.
        KotlinCoreEnvironment.createForProduction(
            disposable,
            configuration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
    }

    private val fileFactory: PsiFileFactory by lazy { PsiFileFactory.getInstance(environment.project) }

    /**
     * Parse [text] into a [KtFile] named [name]. Never throws on syntactically invalid input — broken
     * regions become `PsiErrorElement`s in the returned tree. [name] should end in `.kt` so PSI picks the
     * Kotlin file type; it need not correspond to a real file.
     */
    fun parse(name: String, text: CharSequence): KtFile = synchronized(lock) {
        val fileName = if (name.endsWith(".kt") || name.endsWith(".kts")) name else "$name.kt"
        val file = fileFactory.createFileFromText(fileName, KotlinLanguage.INSTANCE, text) as KtFile
        forceFullParse(file)
        file
    }

    /**
     * Incrementally reparse [file] in place so its text becomes [newText], reusing the unchanged subtrees of
     * the existing PSI (only the changed span is re-lexed/re-parsed). Returns the same (now-mutated) [file] on
     * success, or null when incremental reparse isn't applicable / failed — the caller then [parse]s fresh
     * (a failed reparse may leave [file] partially mutated, so it must be discarded). Serialized under the
     * same [lock] as [parse], so it never races a parse on a background index thread. See [KotlinPsiMutation].
     */
    fun tryReparse(file: KtFile, newText: CharSequence): KtFile? = synchronized(lock) {
        KotlinPsiMutation.reparse(file, newText)?.also { forceFullParse(it) }
    }

    /**
     * Materialize [file]'s entire AST now, under [lock]. `createFileFromText`/incremental reparse leave
     * `ILazyParseableElementType` nodes (KDoc, some string templates) unexpanded; the first access to such a
     * node builds its subtree. Walking every [ASTNode] and touching its `firstChildNode` forces each lazy
     * node to parse here, while the lock is held, so no `buildTree` ever runs during the unlocked, possibly
     * concurrent traversal that follows (see the threading note above). Iterative (an explicit stack) to
     * avoid a deep-recursion overflow on large source files. Reading an already-built node's `firstChildNode`
     * is a cheap field access, so a re-walk of an already-materialized tree is nearly free.
     */
    private fun forceFullParse(file: KtFile) {
        val stack = ArrayDeque<ASTNode>()
        file.node?.let { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            var child = stack.removeLast().firstChildNode // forces this node's lazy subtree to build
            while (child != null) {
                stack.addLast(child)
                child = child.treeNext
            }
        }
    }

    /** Force the (expensive) environment up now — call off the UI thread at startup to hide cold-start. */
    fun warmUp() {
        environment // touch the lazy
    }
}
