package dev.ide.lang.java.env

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import dev.ide.psi.IntellijPsiHost
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.addJavaSourceRoots
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.config.configureJdkClasspathRoots
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.diagnostics.impl.BaseDiagnosticsCollector
import java.io.Closeable
import java.io.File

/**
 * A per-module IntelliJ Java resolution environment: a classpath-configured [KotlinCoreEnvironment] whose
 * [project] hosts a working [JavaPsiFacade]. This is what makes the "use IntelliJ's native engine" strategy
 * real — [facade] resolves classpath binaries (via the Cls decompiler) and project sources (via the
 * CoreJavaFileManager over [sourceRoots]), and `PsiReferenceExpression.resolve()` / `PsiExpression.getType()`
 * do full scope-walking, overload resolution, and generic inference.
 *
 * ## Why per module, and shared application env
 * The classpath is module-scoped, so each module gets its own project-level environment; the caller caches one
 * per [dev.ide.model.ClasspathSnapshot.fingerprint]. All of these share the ONE process-wide IntelliJ
 * application environment owned by [IntellijPsiHost] (only one `MockApplication` may exist), which is why
 * [create] warms that host up first and creates the project env against it.
 *
 * ## Threading
 * PSI tree-building is process-globally unsafe on ART (concurrent `buildTree` → native SIGSEGV), so BOTH env
 * creation and every [parse] serialize under [IntellijPsiHost.withParseLock] — the same lock the Kotlin/XML
 * parse hosts use — and each parse fully materializes its tree while holding it.
 *
 * ## Classpath vs boot classpath
 * On desktop a real JDK is available, so [jdkHome] is set and `configureJdkClasspathRoots()` mounts its
 * modules. On ART there is no JDK; the platform is `android.jar`, passed in [classpath] (the model's boot
 * classpath), and `-no-jdk` is set. Mirrors `KotlinJvmCompiler`.
 */
class JavaEnvironment private constructor(
    private val kotlinEnv: KotlinCoreEnvironment,
    private val disposable: Disposable,
    /** The resolution roots this env was configured with — surfaced so the analyzer can contribute them to
     *  the workspace index scope ([dev.ide.lang.JvmIndexScopeProvider]). */
    val classpath: List<File>,
    val sourceRoots: List<File>,
    val jdkHome: File?,
) : Closeable {

    val project: Project get() = kotlinEnv.project

    val facade: JavaPsiFacade get() = JavaPsiFacade.getInstance(project)

    private val fileFactory: PsiFileFactory by lazy { PsiFileFactory.getInstance(project) }

    /** Synthetic classes (Android R/BuildConfig/…) + open-buffer overlay (FQN → live text) the injected
     *  element finder serves; the host (ide-core) sets these on the analyzer, which forwards them here. */
    var syntheticProvider: () -> List<dev.ide.lang.synthetic.SyntheticClass> = { emptyList() }
    var overlayProvider: () -> Map<String, CharArray> = { emptyMap() }

    /** The injected finder (kept so its parsed-class cache can be cleared on a synthetic/resource change). */
    private var injectedFinder: JavaInjectedElementFinder? = null

    /**
     * Drop cached resolution of synthetic + overlay classes after they change (an Android `R` regenerated on a
     * resource edit, an open buffer edited). The facade caches `findClass` results keyed on the PSI modification
     * count, so it must be bumped ([PsiManager.dropPsiCaches]) or a code file keeps resolving the STALE `R`
     * (e.g. a just-added `R.string.foo` stays unresolved); the finder's own content-keyed cache is cleared too.
     */
    fun dropCaches() {
        runCatching { injectedFinder?.clearCache() }
        val pm = com.intellij.psi.PsiManager.getInstance(project)
        runCatching { pm.dropResolveCaches() }
        runCatching { pm.dropPsiCaches() }
        // The facade caches `findClass` on the PSI modification count; `dropPsiCaches` doesn't reliably bump it
        // in this minimal core, so a re-resolved `R` kept returning the stale class. Bump it explicitly so the
        // facade's cached synthetic-class lookups recompute (re-consulting the injected finder → fresh `R`).
        runCatching {
            (com.intellij.psi.util.PsiModificationTracker.getInstance(project) as? com.intellij.psi.impl.PsiModificationTrackerImpl)
                ?.incCounter()
        }
    }

    /**
     * Parse [text] into a [PsiJavaFile] belonging to THIS project (so [facade] resolution sees the classpath +
     * source roots), named [name]. Never throws on invalid input — broken regions become `PsiErrorElement`s.
     * Fully materialized under the shared parse lock, so no `buildTree` runs during the unlocked traversal that
     * follows. [name] should end in `.java`; it need not correspond to a real file on disk.
     */
    fun parse(name: String, text: CharSequence): PsiJavaFile = IntellijPsiHost.withParseLock {
        val fileName = if (name.endsWith(".java")) name else "$name.java"
        val file =
            fileFactory.createFileFromText(fileName, JavaLanguage.INSTANCE, text) as PsiJavaFile
        IntellijPsiHost.forceFullParse(file)
        file
    }

    override fun close() = Disposer.dispose(disposable)

    companion object {
        /**
         * Stand up an environment resolving against [classpath] (jars + class dirs, INCLUDING the boot
         * classpath / android.jar) with project [sourceRoots] mounted for cross-file source resolution.
         * When [jdkHome] is non-null its modules are mounted and the classpath is JDK-backed; otherwise
         * `-no-jdk` is set and the platform must be present in [classpath].
         */
        @OptIn(CompilerConfiguration.Internals::class, org.jetbrains.kotlin.K1Deprecation::class)
        fun create(
            classpath: List<File>,
            sourceRoots: List<File>,
            jdkHome: File?,
            moduleName: String = "java-editor",
        ): JavaEnvironment = IntellijPsiHost.withParseLock {
            // Establish the shared application environment FIRST so this project env is created against it,
            // rather than racing to create a second application env.
            IntellijPsiHost.warmUp()

            val configuration = CompilerConfiguration.create(
                diagnosticsCollector = BaseDiagnosticsCollector.DoNothing,
                messageCollector = MessageCollector.NONE,
            ).apply {
                put(CommonConfigurationKeys.MODULE_NAME, moduleName)
                if (jdkHome != null) {
                    put(JVMConfigurationKeys.JDK_HOME, jdkHome)
                    configureJdkClasspathRoots()
                } else {
                    put(JVMConfigurationKeys.NO_JDK, true)
                }
                addJvmClasspathRoots(classpath)
                addJavaSourceRoots(sourceRoots)
            }
            val disposable = Disposer.newDisposable("java-env-$moduleName")
            val env = KotlinCoreEnvironment.createForProduction(
                disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES,
            )
            JavaEnvironment(env, disposable, classpath, sourceRoots, jdkHome).also { it.installInjectedFinder() }
        }
    }

    /**
     * Register the [JavaInjectedElementFinder] on this project so the facade resolves synthetic + overlay
     * classes. FIRST order so the overlay (unsaved edits) wins over the disk copy; synthetic classes have no
     * disk copy so any order serves them. Best-effort: if the standalone core lacks the element-finder EP the
     * finder simply isn't installed (synthetic/overlay resolution degrades, real classes are unaffected).
     */
    private fun installInjectedFinder() {
        runCatching {
            val finder = JavaInjectedElementFinder(
                synthetic = { syntheticProvider() },
                overlay = { overlayProvider() },
                parse = { name, text -> parse(name, text) },
            )
            injectedFinder = finder
            com.intellij.psi.PsiElementFinder.EP.getPoint(project)
                .registerExtension(finder, com.intellij.openapi.extensions.LoadingOrder.FIRST, disposable)
        }
    }
}
