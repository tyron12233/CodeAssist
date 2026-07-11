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
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.diagnostics.impl.BaseDiagnosticsCollector

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
 * Threading: [parse]/registration are serialized under [parseLock]. PSI's standalone core is not thread-safe
 * for concurrent file creation, and — crucially on ART — two concurrent `buildTree` calls corrupt its
 * internals (a native SIGSEGV), so every parse fully materializes its tree ([forceFullParse]) while holding
 * the lock; later unlocked traversal only ever reads a built, immutable tree.
 */
object IntellijPsiHost {

    /** The coarse lock every parse (Kotlin, XML, …) and every language registration serializes under. */
    val parseLock = Any()

    // Held for the JVM lifetime; createForProduction roots an application-level environment kept alive.
    private val disposable = Disposer.newDisposable("intellij-psi-host")

    private val registeredLanguages = HashSet<String>()

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
        val configuration = CompilerConfiguration.create(
            diagnosticsCollector = BaseDiagnosticsCollector.DoNothing,
            messageCollector = MessageCollector.NONE,
        ).apply { put(CommonConfigurationKeys.MODULE_NAME, "intellij-psi-host") }
        KotlinCoreEnvironment.createForProduction(
            disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
    }

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
        synchronized(parseLock) {
            warmUp() // ensure the application environment exists before touching its extension points
            if (!registeredLanguages.add(language.id)) return
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

    /**
     * Register an application-level service (the standalone-core analog of a plugin.xml `<applicationService>`)
     * some PSI paths resolve lazily — e.g. XML's `BasicXmlElementFactory`. Serialized under [parseLock].
     */
    fun <T : Any> registerAppService(serviceInterface: Class<T>, implementation: T) {
        synchronized(parseLock) {
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
        synchronized(parseLock) {
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
        synchronized(parseLock) {
            val file = fileFactory.createFileFromText(name, language, text)
            forceFullParse(file)
            file
        }

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
