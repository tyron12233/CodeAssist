package dev.ide.psi

import com.intellij.core.CoreApplicationEnvironment
import com.intellij.lang.ASTFactory
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.LanguageASTFactory
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.ParserDefinition
import com.intellij.mock.MockApplication
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import dev.ide.platform.log.Log
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.diagnostics.impl.BaseDiagnosticsCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * The single, process-wide IntelliJ platform host every language backend parses against.
 *
 * IntelliJ's `ApplicationManager` holds ONE global application, so the process can stand up exactly one
 * `CoreApplicationEnvironment`; and the module graph forbids `:lang-xml` depending on `:lang-kotlin`. This
 * leaf module therefore owns the environment boot and the shared parse machinery, and both
 * `dev.ide.lang.kotlin.parse.KotlinParserHost` and the XML backend register their language onto it (see
 * [registerLanguage]) and parse through it (see [parse]).
 *
 * The environment is created via the Kotlin CLI's `KotlinCoreEnvironment.createForProduction`, which is the
 * proven, ART-hardened way to stand up the generic IntelliJ core here (it runs `setupIdeaStandaloneExecution`,
 * installs the collectors, and registers the JVM parser definitions). No classpath is configured: this host
 * only ever parses (`text -> PsiFile`); all resolution/semantics live in each backend's own model.
 *
 * Lifetime: a lazy singleton; standing up the environment is the cold-start cost, paid once off the UI thread.
 * Threading: [parse]/registration serialize under the parse lock (via [withParseLock]). PSI's standalone core
 * is not thread-safe for concurrent file creation, and — crucially on ART — two concurrent `buildTree` calls
 * corrupt its internals (a native SIGSEGV), so every parse fully materializes its tree ([forceFullParse])
 * while holding the lock; later unlocked traversal only ever reads a built, immutable tree.
 *
 * The lock is **fair** on purpose. It is one global lock shared by the interactive editor (folding, highlight,
 * completion, per-keystroke reparse) AND the background index build + symbol-model warm-up, which parse many
 * files back-to-back on several threads. An unfair monitor let those background parsers barge repeatedly and
 * starve a single editor parse for tens of seconds on cold open (a `folds` pass measured at 25s while the
 * library-source indexing held the lock). Fairness serves waiters FIFO, so an editor parse waits at most for
 * the one in-flight file parse, not the whole background storm.
 */
object IntellijPsiHost {

    // ONE fair read/write lock, modeling IntelliJ's read-action concurrency:
    //   • concurrent READS  — structural index parses ([parseStructural]/[parseConcurrent], shared read lock);
    //   • exclusive WRITES  — full editor parse ([parse]), language registration, incremental reparse (via
    //                         [withParseLock]/[writeAction]).
    // Reads and writes are on the SAME lock, so an index read never overlaps a full parse / registration /
    // reparse. Concurrent reads are safe on ART only AFTER a single-threaded prime per language (the ART
    // concurrent-`buildTree` SIGSEGV is first-touch lazy init; validated safe post-prime on device by
    // JavaPsiConcurrentArtSpikeTest — 300 concurrent structural parses, 0 errors, Android 8.0). Full parses
    // stay exclusive because concurrent full parse (body materialization) is NOT validated on ART; the win is
    // that the many structural index parses now overlap each other (and binary indexing was already parallel).
    private val rwLock = ReentrantReadWriteLock(/* fair = */ true)

    /** Exclusive action (fair). Full parse, language registration, incremental reparse (PSI mutation) all use
     *  this, so none of them overlap a concurrent structural read. Reentrant. */
    fun <T> withParseLock(block: () -> T): T = rwLock.write(block)

    /** Alias of [withParseLock] — the exclusive (write) action, by its read/write-model name. */
    fun <T> writeAction(block: () -> T): T = rwLock.write(block)

    /** Shared (concurrent) read action — structural index parses run concurrently under it. */
    fun <T> readAction(block: () -> T): T = rwLock.read(block)

    /** Languages already primed (one single-threaded full parse) so their concurrent reads are ART-safe. */
    private val primedLanguages = ConcurrentHashMap.newKeySet<String>()

    // Held for the JVM lifetime; createForProduction roots an application-level environment kept alive.
    private val disposable = Disposer.newDisposable("intellij-psi-host")

    private val registeredLanguages = HashSet<String>()

    private val perf = Log.logger("psi.perf")

    @OptIn(
        CompilerConfiguration.Internals::class,
        org.jetbrains.kotlin.K1Deprecation::class,
    )
    private val environment: KotlinCoreEnvironment by lazy {
        // Keep the IntelliJ-core application environment (file types, parser defs, the mmapped classpath jar FS)
        // alive across a refcount of zero, so it is not torn down and re-stood-up. Must be set before the first
        // environment creation. Mirrors dev.ide.lang.kotlin.compile.KotlinEnvironmentKeepAlive (which the build
        // compiler also calls); both set the same property idempotently and respect a host-chosen value.
        if (System.getProperty(KEEPALIVE) == null) System.setProperty(KEEPALIVE, "true")

        // CompilerConfiguration.create registers the compiler-extensions storage createForProduction requires
        // and installs the collectors. Both are silenced: editor diagnostics come from PsiErrorElements, and the
        // no-op diagnostics collector matters on ART, where ProjectEnvironment.<init> reports a warning through
        // it (its getter throws when never set).
        val t0 = System.nanoTime()
        val configuration = CompilerConfiguration.create(
            diagnosticsCollector = BaseDiagnosticsCollector.DoNothing,
            messageCollector = MessageCollector.NONE,
        ).apply { put(CommonConfigurationKeys.MODULE_NAME, "intellij-psi-host") }
        val t1 = System.nanoTime()
        val env = KotlinCoreEnvironment.createForProduction(
            disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
        val t2 = System.nanoTime()
        // The ONE-TIME cold standup. On desktop this is ~180ms; on ART it can be tens of seconds because it
        // loads+verifies+initializes the whole IntelliJ platform + Kotlin frontend from dex with no AOT (no
        // baseline profile) — no I/O (this env configures no classpath). It gates the first Kotlin parse, so it
        // surfaces as a stalled first editor pass (folding/highlight). Logged so the on-device split is visible.
        perf.info(
            "KotlinCoreEnvironment standup: config=${(t1 - t0) / 1_000_000}ms " +
                "createForProduction=${(t2 - t1) / 1_000_000}ms total=${(t2 - t0) / 1_000_000}ms"
        )
        env
    }

    /** One-shot so only the FIRST (cold) parse logs its create/materialize split; later parses are silent. */
    private val firstParseLogged = AtomicBoolean(false)

    /** The shared project every parsed [PsiFile] belongs to. */
    val project: Project get() = environment.project

    private val fileFactory: PsiFileFactory by lazy { PsiFileFactory.getInstance(project) }

    /** Force the (expensive) environment up now — call off the UI thread at startup to hide cold-start. */
    fun warmUp() {
        // init environment
        environment
    }

    /**
     * Register a language's parsing onto the shared application environment, once. [parserDefinition] is what
     * makes `createFileFromText(name, language, …)` produce that language's PSI; [astFactory] (when non-null)
     * supplies the language's AST node types. Idempotent per [language]; serialized under [parseLock].
     */
    fun registerLanguage(
        language: Language,
        parserDefinition: ParserDefinition,
        astFactory: ASTFactory? = null
    ) {
        withParseLock {
            warmUp() // ensure the application environment exists before touching its extension points
            if (registeredLanguages.add(language.id)) {
                LanguageParserDefinitions.INSTANCE.addExplicitExtension(
                    language,
                    parserDefinition,
                    disposable
                )
                if (astFactory != null) LanguageASTFactory.INSTANCE.addExplicitExtension(
                    language,
                    astFactory,
                    disposable
                )
            }
        }
    }

    /**
     * Register an application-level service (the standalone-core analog of a plugin.xml `<applicationService>`)
     * some PSI paths resolve lazily — e.g. XML's `BasicXmlElementFactory`. Serialized under [parseLock].
     */
    fun <T : Any> registerAppService(serviceInterface: Class<T>, implementation: T) {
        withParseLock {
            warmUp()
            (ApplicationManager.getApplication() as MockApplication)
                .registerService(serviceInterface, implementation)
        }
    }

    /**
     * Register an application-level extension point, empty, so a PSI path that looks it up gets an empty list
     * instead of "Missing extension point" — the standalone-core analog of a plugin.xml `<extensionPoint>`
     * (e.g. XML's `com.intellij.xml.startTagEndToken`, consulted by `XmlTag.getValue()`). Idempotent.
     */
    fun registerApplicationExtensionPoint(name: String, extensionClass: Class<*>) {
        withParseLock {
            warmUp()
            val area = ApplicationManager.getApplication().extensionArea
            if (!area.hasExtensionPoint(name)) CoreApplicationEnvironment.registerExtensionPoint(
                area,
                name,
                extensionClass
            )
        }
    }

    /**
     * Parse [text] into a [PsiFile] of [language], named [name]. Never throws on invalid input — broken
     * regions become `PsiErrorElement`s (the DOM's error-tolerance contract). Serialized + fully materialized
     * under [parseLock]. Callers cast to the concrete file type (KtFile / XmlFile). [language] must have been
     * registered via [registerLanguage] first.
     */
    fun parse(name: String, language: Language, text: CharSequence): PsiFile =
        withParseLock {
            // The FIRST parse also triggers the lazy [environment] standup (logged separately). Split its
            // create-vs-materialize cost once, so a device log shows whether a slow first parse is the env
            // standup (createFileFromText, which forces the lazy env up) or tree materialization.
            if (firstParseLogged.compareAndSet(false, true)) {
                val a = System.nanoTime()
                val file = fileFactory.createFileFromText(name, language, text)
                val b = System.nanoTime()
                forceFullParse(file)
                val c = System.nanoTime()
                perf.info(
                    "first parse: createFileFromText=${(b - a) / 1_000_000}ms " +
                        "forceFullParse=${(c - b) / 1_000_000}ms (${text.length} chars)"
                )
                file
            } else {
                val file = fileFactory.createFileFromText(name, language, text)
                forceFullParse(file)
                file
            }
        }

    /**
     * The light, CONCURRENT path for INDEXING: parse [text] and run [extract] over the [PsiFile] under a
     * SHARED read lock (so many index parses overlap), WITHOUT [forceFullParse]. An index reads only the
     * declaration structure (types/methods/fields/imports/supertypes), never statement bodies, and Java/Kotlin
     * bodies are lazy (`CODE_BLOCK` reparseable) — skipping full materialization avoids parsing every method
     * body (the bulk of the cost, worst for library source like the JDK `src.zip`).
     *
     * [extract] MUST return plain data — no PSI element may escape, because the tree isn't fully materialized
     * and must not be traversed after the read lock is released. Concurrent `buildTree` here is ART-safe only
     * after a single-threaded PRIME of [language] (one full parse forcing first-touch lazy init); this method
     * does that prime once per language under the write lock before switching the language to the concurrent
     * read path. Full parses / registration / reparse take the write lock, so none overlap these reads.
     */
    fun <T> parseStructural(name: String, language: Language, text: CharSequence, extract: (PsiFile) -> T): T {
        if (language.id !in primedLanguages) {
            // First parse of this language: prime lazy init single-threaded (exclusive) before any concurrent
            // read. The double-check under the write lock makes concurrent first callers prime exactly once.
            writeAction {
                if (primedLanguages.add(language.id)) {
                    forceFullParse(fileFactory.createFileFromText(name, language, text))
                }
            }
        }
        return readAction { extract(fileFactory.createFileFromText(name, language, text)) }
    }

    /** Alias of [parseStructural] — the concurrent structural read path, by its parallelism-focused name. */
    fun <T> parseConcurrent(name: String, language: Language, text: CharSequence, extract: (PsiFile) -> T): T =
        parseStructural(name, language, text, extract)

    /**
     * Materialize [file]'s entire AST now, under [parseLock]. `createFileFromText`/incremental reparse leave
     * `ILazyParseableElementType` nodes unexpanded; the first access builds their subtree. Walking every
     * [ASTNode] and touching its `firstChildNode` forces each lazy node to parse here, while the lock is held,
     * so no `buildTree` ever runs during the unlocked, possibly-concurrent traversal that follows. Iterative
     * (explicit stack) to avoid deep-recursion overflow on large files; a re-walk of a built tree is near-free.
     */
    fun forceFullParse(file: PsiFile) {
        val stack = ArrayDeque<ASTNode>()
        file.node?.let { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            var child = stack.removeLast().firstChildNode
            while (child != null) {
                stack.addLast(child)
                child = child.treeNext
            }
        }
    }

    private const val KEEPALIVE = "kotlin.environment.keepalive"
}
