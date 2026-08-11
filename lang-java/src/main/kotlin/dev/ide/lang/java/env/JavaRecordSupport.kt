package dev.ide.lang.java.env

import com.intellij.core.CoreApplicationEnvironment
import com.intellij.lang.java.JavaLanguage
import com.intellij.mock.MockComponentManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.pom.PomModel
import com.intellij.pom.PomModelAspect
import com.intellij.pom.PomTransaction
import com.intellij.pom.event.PomModelListener
import com.intellij.pom.tree.TreeAspect
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiTreeChangeListener
import com.intellij.psi.augment.PsiAugmentProvider
import com.intellij.psi.impl.PsiTreeChangePreprocessor
import com.intellij.psi.impl.RecordAugmentProvider
import dev.ide.platform.log.Log
import dev.ide.psi.IntellijPsiHost

/**
 * Turns on IntelliJ's real record augmentation for the editor's Java resolution environments, so a SOURCE
 * `record Point(int x, int y)` gains its synthesized members — the component accessors (`x()`/`y()`), the
 * canonical constructor, and the backing fields. The minimal [KotlinCoreEnvironment] the backends parse against
 * omits the machinery `com.intellij.psi.impl.RecordAugmentProvider` needs, so without this a source record has
 * EMPTY `methods`/`constructors`/`fields` for those implicit members — `p.x()` neither completes nor resolves,
 * `new Point(…)` isn't arity-checked, and every accessor/component use is flagged "Cannot resolve".
 *
 * ## What it registers
 * - The app-level `PsiAugmentProvider.EP_NAME` extension `RecordAugmentProvider` (ONCE, process-wide).
 * - A minimal [PomModel] (a [TreeAspect]-backed transaction runner — the same shape
 *   `dev.ide.lang.kotlin.parse.KotlinPsiMutation` uses) on EACH resolution [Project], which the augment's
 *   light-member creation requires.
 *
 * ## Capability-gated, with a graceful fallback
 * The whole thing is stood up ONCE and probed on a throwaway record ([verify]); if any piece is missing on this
 * platform (a real risk on ART, where some platform services differ), it is torn down and permanently
 * [disabled], and the hand-rolled record support in `JavaCompletion` / `JavaScope` / `JavaSemanticDiagnostics`
 * carries records instead (those degrade cleanly — they dedup against the real members when the augment IS on).
 *
 * ## Scope: editor only, never the shared index host
 * Only per-module [JavaEnvironment] projects get a [PomModel]; the shared classpath-free `IntellijPsiHost`
 * project (the structural index host) deliberately does NOT — the app-wide provider would otherwise throw on
 * `PsiClass.getMethods()` there. The Java source indexer sidesteps this by reading OWN members
 * (`getOwnMethods()`/…), which never trigger augmentation, so indexing is unaffected. The app-wide provider is
 * inert for Kotlin/XML PSI (it only augments a Java `PsiClass` that `isRecord`).
 *
 * ## Threading
 * Everything serializes under the ONE [IntellijPsiHost.withParseLock] (the reentrant, process-global PSI lock),
 * so registration, the verify parse, and the per-project [PomModel] setup never race a parse. [ensureFor] is
 * called only from [JavaEnvironment.create] (already under that lock), so the extra locking is paid once per
 * environment, not on any hot path.
 */
internal object JavaRecordSupport {

    @Volatile private var enabled = false
    @Volatile private var disabled = false

    /** Projects already given a [PomModel]; guarded by the parse lock (all access is under [ensureFor]). */
    private val pommed = HashSet<Project>()

    /** Owns the app-level provider registration, so a failed stand-up can unregister it cleanly. */
    private val augmentDisposable: Disposable = Disposer.newDisposable("java-record-augment")

    private val log = Log.logger("JavaRecordSupport")

    /** Ensure record augmentation is available for [project]. First call stands up + verifies the app-level
     *  provider (else disables permanently); every call registers a [PomModel] on [project] if it lacks one. */
    fun ensureFor(project: Project) {
        if (disabled) return
        IntellijPsiHost.withParseLock {
            if (disabled) return@withParseLock
            if (!enabled) {
                runCatching {
                    registerPom(project)
                    registerAppProvider()
                    if (verify(project)) enabled = true else tearDown("record accessors did not materialize")
                }.onFailure { tearDown("record augmentation could not be initialized: ${it.message}") }
                return@withParseLock // first attempt covers THIS project (pommed inside the try)
            }
            // Enabled: make sure THIS project has a PomModel. If it can't (it never should — every resolution
            // project is the same MockProject shape), disable globally so records fall back rather than throw on
            // this project's `getMethods()` under the now-active app-wide provider.
            runCatching { registerPom(project) }
                .onFailure { tearDown("PomModel setup failed for a resolution project: ${it.message}") }
        }
    }

    private fun tearDown(reason: String) {
        disabled = true
        enabled = false
        runCatching { Disposer.dispose(augmentDisposable) }
        log.info("record augmentation disabled ($reason); falling back to hand-rolled record support")
    }

    /** Register [RecordAugmentProvider] on the app-level augment EP once (the EP already exists in this core). */
    private fun registerAppProvider() {
        val app = ApplicationManager.getApplication()
        val epName = PsiAugmentProvider.EP_NAME.name
        if (!app.extensionArea.hasExtensionPoint(epName)) {
            CoreApplicationEnvironment.registerApplicationExtensionPoint(PsiAugmentProvider.EP_NAME, PsiAugmentProvider::class.java)
        }
        PsiAugmentProvider.EP_NAME.point.registerExtension(RecordAugmentProvider(), augmentDisposable)
    }

    /** Register a minimal [PomModel] (+ the PSI tree-change EPs the platform looks up) on [project], once. */
    private fun registerPom(project: Project) {
        if (!pommed.add(project)) return
        val cm = project as MockComponentManager
        if (project.getServiceIfCreated(PomModel::class.java) == null) {
            cm.registerService(PomModel::class.java, TransactionPomModel())
        }
        val area = project.extensionArea
        for ((name, klass) in TREE_CHANGE_EPS) {
            if (!area.hasExtensionPoint(name)) CoreApplicationEnvironment.registerExtensionPoint(area, name, klass)
        }
    }

    /** Probe the augment on a throwaway record in [project]: its component accessor must materialize. */
    private fun verify(project: Project): Boolean = runCatching {
        val file = PsiFileFactory.getInstance(project).createFileFromText(
            "RecordAugmentProbe.java", JavaLanguage.INSTANCE,
            "record RecordAugmentProbe(int component0) {}",
            /* eventSystemEnabled = */ false, /* markAsCopy = */ false,
        ) as PsiJavaFile
        IntellijPsiHost.forceFullParse(file)
        file.classes.firstOrNull()?.methods?.any { it.name == "component0" } == true
    }.getOrDefault(false)

    private val TREE_CHANGE_EPS = arrayOf(
        "com.intellij.psi.treeChangeListener" to PsiTreeChangeListener::class.java,
        "com.intellij.psi.treeChangePreprocessor" to PsiTreeChangePreprocessor::class.java,
    )

    /** Minimal [PomModel]: exposes the [TreeAspect] the light-member path needs and runs a transaction inline
     *  (nothing in this headless core publishes or consumes POM events). Same shape as Kotlin's reparse host. */
    private class TransactionPomModel : UserDataHolderBase(), PomModel {
        private val treeAspect = TreeAspect()
        @Suppress("UNCHECKED_CAST")
        override fun <T : PomModelAspect> getModelAspect(aspectClass: Class<T>): T? =
            if (aspectClass == TreeAspect::class.java) treeAspect as T else null
        override fun runTransaction(transaction: PomTransaction) = transaction.run()
        override fun addModelListener(listener: PomModelListener) {}
        override fun addModelListener(listener: PomModelListener, parentDisposable: Disposable) {}
        override fun removeModelListener(listener: PomModelListener) {}
    }
}
