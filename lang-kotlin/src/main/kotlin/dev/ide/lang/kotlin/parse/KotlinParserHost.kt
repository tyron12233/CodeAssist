package dev.ide.lang.kotlin.parse

import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.diagnostics.impl.BaseDiagnosticsCollector
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtFile
// kotlin-compiler-embeddable relocates the bundled IntelliJ platform under org.jetbrains.kotlin.com.intellij.*
// (the Kotlin PSI classes, org.jetbrains.kotlin.psi.*, are NOT relocated). Import the relocated names.
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory

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
 * correct choice.
 */
object KotlinParserHost {

    private val lock = Any()

    // Held for the JVM lifetime; createForProduction roots an application-level environment kept alive.
    private val disposable = Disposer.newDisposable("lang-kotlin-parser")

    // createForProduction is the K1 standalone-parsing entry point. K2/the Analysis API is for resolution,
    // which is not needed here; pure parsing via the K1 core environment is correct and supported, just
    // opt-in-gated. Internals: CompilerConfiguration.put.
    @OptIn(CompilerConfiguration.Internals::class, org.jetbrains.kotlin.K1Deprecation::class)
    private val environment: KotlinCoreEnvironment by lazy {
        // Keep the application environment alive across compiles too — must be set before this first creation.
        dev.ide.lang.kotlin.compile.KotlinEnvironmentKeepAlive.ensure()
        val configuration = CompilerConfiguration().apply {
            put(CommonConfigurationKeys.MODULE_NAME, "lang-kotlin-parser")
            // Compiler diagnostics are discarded entirely (diagnostics come from PsiErrorElements), so silence it.
            put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
            // ProjectEnvironment.<init> reports through the CLI diagnostics collector — e.g. the "JDK doesn't
            // support mapped buffer unmapping" warning, which fires on ART but never on a desktop JDK (hence
            // this only broke on device). Its getter throws "diagnostic collector is not initialized" when the
            // config never set one, so install a no-op collector. Without it, standing up the parse host throws
            // and every Kotlin completion silently returns nothing.
            put(CLIConfigurationKeys.DIAGNOSTICS_COLLECTOR, BaseDiagnosticsCollector.DoNothing)
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
        fileFactory.createFileFromText(fileName, KotlinLanguage.INSTANCE, text) as KtFile
    }

    /** Force the (expensive) environment up now — call off the UI thread at startup to hide cold-start. */
    fun warmUp() {
        environment // touch the lazy
    }
}
