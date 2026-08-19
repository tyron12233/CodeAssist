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
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.registry.StandaloneRegistryKeys
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
 * Threading: EVERY parse and every registration serializes under the parse lock (via [withParseLock]). PSI's
 * standalone core is not thread-safe for concurrent file creation, and — crucially on ART — two concurrent
 * `buildTree` calls corrupt its internals (a native SIGSEGV), so a tree is only ever built while holding the
 * lock; later unlocked traversal only ever reads a built tree. Holding that second half also depends on the
 * file being created non-event-system, so the platform keeps the tree by a hard reference and cannot collect
 * and silently reparse it behind our back — see [createFile].
 *
 * The lock is **fair** on purpose. It is one global lock shared by the interactive editor (folding, highlight,
 * completion, per-keystroke reparse) AND the background index build + symbol-model warm-up, which parse many
 * files back-to-back on several threads. An unfair monitor let those background parsers barge repeatedly and
 * starve a single editor parse for tens of seconds on cold open (a `folds` pass measured at 25s while the
 * library-source indexing held the lock). Fairness serves waiters FIFO, so an editor parse waits at most for
 * the one in-flight file parse, not the whole background storm.
 */
object IntellijPsiHost {

    // ONE fair, reentrant, EXCLUSIVE lock: no two threads may build a PSI tree at once.
    //
    // This was briefly a read/write lock, with the structural index parses ([parseStructural]) running
    // concurrently under a shared read lock after a single-threaded per-language prime — on the theory that
    // the ART concurrent-`buildTree` SIGSEGV was only first-touch lazy init (a 300-parse device spike, Android
    // 8.0, was clean). The field disproved it: a 32-bit ART tombstone (Infinix X6823C, Android 12) caught the
    // crash inside a concurrent index parse — `markGenerated` → `LazyParseableElement.ensureParsed` →
    // `ICodeBlockElementType.parseContents` → `PsiBuilderImpl.buildTree/bind` → SIGSEGV on a garbage
    // reference. So concurrent `buildTree` is back to being forbidden, and the win it was chasing is bought
    // instead by [parseStructural] no longer materializing method bodies at all (see there).
    private val parseLock = ReentrantLock(/* fair = */ true)

    /** The one exclusive action (fair, reentrant). Every parse, language registration and incremental reparse
     *  runs under it, so no two PSI trees are ever built concurrently. */
    fun <T> withParseLock(block: () -> T): T = parseLock.withLock(block)

    /** Whether the calling thread currently holds the parse lock. A diagnostic + test seam: it pins the
     *  ART-safety invariant that PSI resolution (which can lazily build a tree) runs under the lock — a test
     *  probes it from a resolution callback and fails if a resolving path forgot to take the lock. */
    fun isParseLockHeldByCurrentThread(): Boolean = parseLock.isHeldByCurrentThread

    // Held for the JVM lifetime; createForProduction roots an application-level environment kept alive.
    private val disposable = Disposer.newDisposable("intellij-psi-host")

    private val registeredLanguages = HashSet<String>()

    private val perf = Log.logger("psi.perf")

    @OptIn(
        CompilerConfiguration.Internals::class,
        org.jetbrains.kotlin.K1Deprecation::class,
    )
    private val environment: KotlinCoreEnvironment by lazy {
        // Before anything can resolve, supply the registry keys the platform expects a plugin descriptor to
        // have declared. See [contributeJavaPsiRegistryKeys].
        contributeJavaPsiRegistryKeys()

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

    /**
     * Registry keys IntelliJ's Java PSI reads through `Registry.is`, with the defaults IntelliJ declares for
     * them. They are declared in the Java PLUGIN's descriptor, not in the platform's bundled
     * `misc/registry.properties`; this host embeds the platform jars and loads no plugin descriptors, so
     * `RegistryKeyBean` never contributes them and `Registry.is` throws `MissingResourceException` on an
     * undefined key.
     *
     * That throw escapes whatever resolution asked for the key. `javac.fresh.variables.for.captured.wildcards.only`
     * is read by `InferenceSession`, which any generic call with a lambda argument goes through
     * (`xs.stream().map(s -> ...)`), so a single such call used to take down the whole diagnostics pass for the
     * file: no unresolved references, no type errors, nothing.
     */
    private val JAVA_PSI_REGISTRY_KEYS = mapOf(
        // InferenceSession + InferenceIncorporationPhase (generic inference).
        "javac.fresh.variables.for.captured.wildcards.only" to "true",
        "javac.unchecked.subtyping.during.incorporation" to "true",
        // PsiClassImplUtil (a class type's resolve scope).
        "java.correct.class.type.by.place.resolve.scope" to "true",
        // JavaFoldingBuilderBase.
        "java.folding.icons.for.control.flow" to "true",
    )

    /**
     * Contribute [JAVA_PSI_REGISTRY_KEYS] to the registry, skipping any key something else already supplied so
     * a real descriptor always wins over this fallback. Best effort by design: the keys only restore the
     * platform's own defaults, so failing to install them can leave resolution no worse off than not trying.
     */
    private fun contributeJavaPsiRegistryKeys() {
        runCatching {
            Registry.mutateContributedKeys { existing ->
                existing + JAVA_PSI_REGISTRY_KEYS
                    .filterKeys { it !in existing }
                    .mapValues { (name, default) -> StandaloneRegistryKeys.of(name, default, PLUGIN_ID) }
            }
        }.onFailure { perf.warn("registry key contribution failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** Attribution for the contributed keys; the platform records a descriptor's origin. */
    private const val PLUGIN_ID = "com.intellij.java"

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
     *
     * `eventSystemEnabled = false` is what makes the "materialized under the lock, then traversed unlocked"
     * contract actually hold: `PsiFileImpl` keeps its `FileElement` by a HARD reference only for a
     * non-event-system file, and by a `SoftReference` otherwise. With a soft reference the tree can be
     * collected under memory pressure and the next node access silently REPARSES it — a `buildTree` outside
     * the parse lock, concurrent with whatever else is parsing, which is the corruption this host exists to
     * prevent (and a live risk on a tight-heap device). Nothing resolves against files from this host (it has
     * no classpath; every backend resolves in its own model), so being non-physical costs nothing here.
     */
    fun parse(name: String, language: Language, text: CharSequence): PsiFile =
        withParseLock {
            // The FIRST parse also triggers the lazy [environment] standup (logged separately). Split its
            // create-vs-materialize cost once, so a device log shows whether a slow first parse is the env
            // standup (createFileFromText, which forces the lazy env up) or tree materialization.
            if (firstParseLogged.compareAndSet(false, true)) {
                val a = System.nanoTime()
                val file = createFile(name, language, text)
                val b = System.nanoTime()
                forceFullParse(file)
                val c = System.nanoTime()
                perf.info(
                    "first parse: createFileFromText=${(b - a) / 1_000_000}ms " +
                        "forceFullParse=${(c - b) / 1_000_000}ms (${text.length} chars)"
                )
                file
            } else {
                val file = createFile(name, language, text)
                forceFullParse(file)
                file
            }
        }

    /**
     * The one `createFileFromText` call shape this host uses: `eventSystemEnabled = false` (the tree is held
     * by a hard reference, so it is never collected and reparsed behind our back) and `markAsCopy = false`
     * (no `GeneratedMarkerVisitor` pass — it would walk the whole tree, expanding every lazy chameleon and
     * marking every node "generated", which is both wasteful and untrue of a file that mirrors user text).
     */
    private fun createFile(name: String, language: Language, text: CharSequence): PsiFile =
        fileFactory.createFileFromText(
            name, language, text, /* eventSystemEnabled = */ false, /* markAsCopy = */ false,
        )

    /**
     * The LIGHT path for INDEXING: parse [text] and run [extract] over the [PsiFile], WITHOUT materializing
     * statement bodies. An index reads only the declaration structure (types/methods/fields/imports/
     * supertypes/doc comments), and those bodies are lazy chameleons (`CODE_BLOCK`, `DOC_COMMENT` are
     * `ILazyParseableElementType`), so leaving them unexpanded skips the bulk of the parse cost — worst for
     * library source like the JDK `src.zip` or the Android framework sources.
     *
     * Getting that laziness requires [createFile]'s `markAsCopy = false`, NOT the 3-arg `createFileFromText`,
     * whose `markAsCopy = true` makes `PsiFileFactoryImpl.trySetupPsiForFile` run `GeneratedMarkerVisitor` over
     * the whole tree — which expands every chameleon, so the "light" path used to parse every method body
     * after all (and did so on several index threads at once; that is the crash in the [parseLock] note).
     *
     * [extract] MUST return plain data — no PSI element may escape, because the tree isn't fully materialized
     * and must not be traversed after the lock is released. Serialized under [withParseLock] like every other
     * parse: `buildTree` must never run on two threads at once (see the [parseLock] note).
     */
    fun <T> parseStructural(name: String, language: Language, text: CharSequence, extract: (PsiFile) -> T): T =
        withParseLock { extract(createFile(name, language, text)) }

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
