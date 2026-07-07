package dev.ide.lang.kotlin.parse

import com.intellij.core.CoreApplicationEnvironment
import com.intellij.lang.ASTNode
import com.intellij.mock.MockComponentManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.pom.PomModel
import com.intellij.pom.PomModelAspect
import com.intellij.pom.PomTransaction
import com.intellij.pom.event.PomModelListener
import com.intellij.pom.tree.TreeAspect
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiTreeChangeListener
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.Indent
import com.intellij.psi.impl.BlockSupportImpl
import com.intellij.psi.impl.PsiTreeChangePreprocessor
import com.intellij.util.ThrowableRunnable
import org.jetbrains.kotlin.psi.KtFile

/**
 * Incremental PSI reparse for the editor's live Kotlin files.
 *
 * The standalone parse-only [KotlinCoreEnvironment] that [KotlinParserHost] stands up never mutates PSI, so
 * it omits the platform services IntelliJ's incremental reparse pipeline needs. This object registers the
 * minimal set lazily (a no-op [CodeStyleManager] + a transaction-running [PomModel] + the two PSI tree-change
 * extension points), then drives `BlockSupportImpl.reparseRange` + `DiffLog.performActualPsiChange` to splice
 * only the changed subtree into the existing [KtFile], reusing every unchanged node (verified by node-identity
 * in `IncrementalReparseTest`).
 *
 * The registration is purely ADDITIVE: parse-only callers (compilation, the background source index) never
 * trigger a reparse, so they never touch these services and their behaviour is unchanged. Reparse mutates a
 * file in place, so callers MUST serialize it under [KotlinParserHost]'s global parse lock (as [reparse]'s
 * sole caller does) and treat a null result as "fall back to a full parse" — a failed reparse may leave the
 * file partially mutated, so the caller discards it and parses fresh.
 */
internal object KotlinPsiMutation {

    @Volatile private var ready = false
    @Volatile private var disabled = false
    private val blockSupport = BlockSupportImpl()

    /** Whether `java.lang.ClassValue` exists (JVM: yes; ART/Android: no). The IntelliJ message bus needs it to
     *  publish the PSI-change events [reparse] triggers, so its absence means incremental reparse can't work. */
    private val classValueAvailable: Boolean =
        runCatching { Class.forName("java.lang.ClassValue", false, KotlinPsiMutation::class.java.classLoader) }.isSuccess

    /** Stand up the PSI-mutation services/EPs on [project] once. Returns false (permanently) if the infra
     *  can't be registered on this platform — then [reparse] no-ops and the caller's full parse is used.
     *  Idempotent; the caller holds the parse lock so registration is serialized. */
    private fun ensureReady(project: Project): Boolean {
        if (ready) return true
        if (disabled) return false
        // IntelliJ's MessageBus (fired by performActualPsiChange -> PsiManagerImpl.beforeChange) caches its
        // listener method handles through java.lang.ClassValue, which does NOT exist on ART. On Android every
        // reparse would then throw NoClassDefFoundError, be caught below, and fall back to a full parse — but
        // the caller would re-attempt on the NEXT keystroke too, so each edit pays an exception + a ~60-line
        // ART-logged stack for a path that can never succeed here. Detect the missing class ONCE and disable
        // incremental reparse permanently, so the caller goes straight to a (working) full parse with no churn.
        // Desktop has ClassValue, so incremental reparse stays on there.
        if (!classValueAvailable) { disabled = true; return false }
        return try {
            if (CodeStyleManager.getInstance(project) == null) {
                val cm = project as MockComponentManager
                cm.registerService(CodeStyleManager::class.java, NoopCodeStyleManager(project))
                cm.registerService(PomModel::class.java, TransactionPomModel())
            }
            val area = project.extensionArea
            // PsiManagerImpl fires before/after-change events through these EPs while applying the diff; with
            // none registered the lookup throws. Register them empty (no listeners) so the firing is a no-op.
            for ((name, klass) in arrayOf(
                "com.intellij.psi.treeChangeListener" to PsiTreeChangeListener::class.java,
                "com.intellij.psi.treeChangePreprocessor" to PsiTreeChangePreprocessor::class.java,
            )) if (!area.hasExtensionPoint(name)) CoreApplicationEnvironment.registerExtensionPoint(area, name, klass)
            ready = true
            true
        } catch (t: Throwable) {
            disabled = true // the infra didn't stand up on this platform — stop re-attempting every keystroke
            false
        }
    }

    /**
     * Reparse [file] in place so its text becomes [newText], reusing unchanged subtrees. Returns the same
     * (now-mutated) [file] on success, or null if reparse isn't applicable / failed — in which case [file]
     * may be partially mutated and the caller must parse fresh. The caller guarantees serialization (the
     * parse lock). A failed reparse is always safe: the caller's full parse yields the correct tree.
     */
    fun reparse(file: KtFile, newText: CharSequence): KtFile? {
        if (disabled) return null
        val old = file.text
        if (old.contentEquals(newText)) return file
        if (!ensureReady(file.project)) return null
        return try {
            val (start, oldEnd) = changedRange(old, newText)
            val diff = blockSupport.reparseRange(
                file, file.node, TextRange(start, oldEnd), newText, EmptyProgressIndicator(), old,
            )
            diff.performActualPsiChange(file)
            // BlockSupport guarantees the result equals a full reparse; verify text as a cheap safety net and
            // fall back (null) on any mismatch rather than serving a divergent tree.
            if (file.text.contentEquals(newText)) file else null
        } catch (t: LinkageError) {
            // A missing/incompatible platform class (e.g. java.lang.ClassValue on ART) can NEVER succeed:
            // disable incremental reparse so we stop re-throwing + logging it on every subsequent keystroke.
            disabled = true
            null
        } catch (t: Throwable) {
            null // a one-off reparse failure: fall back to a full parse for this edit (infra stays enabled)
        }
    }

    /** The single replaced span in OLD coordinates: [prefix, old.length - suffix). */
    private fun changedRange(old: CharSequence, new: CharSequence): Pair<Int, Int> {
        var p = 0
        val min = minOf(old.length, new.length)
        while (p < min && old[p] == new[p]) p++
        var s = 0
        while (s < min - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
        return p to (old.length - s)
    }

    /** Minimal [PomModel]: runs the reparse transaction (no listeners in this headless core to notify). */
    private class TransactionPomModel : UserDataHolderBase(), PomModel {
        private val treeAspect = TreeAspect()
        @Suppress("UNCHECKED_CAST")
        override fun <T : PomModelAspect> getModelAspect(aspectClass: Class<T>): T? =
            if (aspectClass == TreeAspect::class.java) treeAspect as T else null
        override fun runTransaction(transaction: PomTransaction) { transaction.run() }
        // Listener registration is a no-op: nothing in this headless core publishes or consumes POM events.
        override fun addModelListener(listener: PomModelListener) {}
        override fun addModelListener(listener: PomModelListener, parentDisposable: Disposable) {}
        override fun removeModelListener(listener: PomModelListener) {}
    }

    /** No-op [CodeStyleManager]: the reparse only needs `performActionWithFormatterDisabled` to run its action
     *  (no reformatting on ART). Every other entry point is unreachable from the reparse path. */
    private class NoopCodeStyleManager(private val project: Project) : CodeStyleManager() {
        override fun getProject(): Project = project
        override fun performActionWithFormatterDisabled(r: Runnable) = r.run()
        override fun <T : Throwable> performActionWithFormatterDisabled(r: ThrowableRunnable<T>) = r.run()
        override fun <T> performActionWithFormatterDisabled(c: Computable<T>): T = c.compute()
        private fun no(): Nothing = throw UnsupportedOperationException("no-op CodeStyleManager")
        override fun reformat(e: PsiElement): PsiElement = no()
        override fun reformat(e: PsiElement, b: Boolean): PsiElement = no()
        override fun reformatRange(e: PsiElement, s: Int, en: Int): PsiElement = no()
        override fun reformatRange(e: PsiElement, s: Int, en: Int, b: Boolean): PsiElement = no()
        override fun reformatText(f: PsiFile, s: Int, en: Int) = no()
        override fun reformatText(f: PsiFile, ranges: MutableCollection<out TextRange>) = no()
        override fun adjustLineIndent(f: PsiFile, r: TextRange) = no()
        override fun adjustLineIndent(f: PsiFile, o: Int): Int = no()
        override fun adjustLineIndent(d: Document, o: Int): Int = no()
        override fun isLineToBeIndented(f: PsiFile, o: Int): Boolean = no()
        override fun getLineIndent(f: PsiFile, o: Int): String? = no()
        override fun getLineIndent(d: Document, o: Int): String? = no()
        override fun getIndent(s: String?, t: FileType?): Indent = no()
        override fun fillIndent(i: Indent?, t: FileType?): String = no()
        override fun zeroIndent(): Indent = no()
        override fun reformatNewlyAddedElement(b: ASTNode, a: ASTNode) = no()
        override fun isSequentialProcessingAllowed(): Boolean = true
    }
}
