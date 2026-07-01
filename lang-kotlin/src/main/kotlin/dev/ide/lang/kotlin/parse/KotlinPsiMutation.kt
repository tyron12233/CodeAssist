package dev.ide.lang.kotlin.parse

import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.mock.MockComponentManager
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType
import org.jetbrains.kotlin.com.intellij.openapi.progress.EmptyProgressIndicator
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolderBase
import org.jetbrains.kotlin.com.intellij.pom.PomModel
import org.jetbrains.kotlin.com.intellij.pom.PomModelAspect
import org.jetbrains.kotlin.com.intellij.pom.PomTransaction
import org.jetbrains.kotlin.com.intellij.pom.tree.TreeAspect
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiTreeChangeListener
import org.jetbrains.kotlin.com.intellij.psi.codeStyle.CodeStyleManager
import org.jetbrains.kotlin.com.intellij.psi.codeStyle.Indent
import org.jetbrains.kotlin.com.intellij.psi.impl.BlockSupportImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiTreeChangePreprocessor
import org.jetbrains.kotlin.com.intellij.util.ThrowableRunnable
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

    /** Stand up the PSI-mutation services/EPs on [project] once. Returns false (permanently) if the infra
     *  can't be registered on this platform — then [reparse] no-ops and the caller's full parse is used.
     *  Idempotent; the caller holds the parse lock so registration is serialized. */
    private fun ensureReady(project: Project): Boolean {
        if (ready) return true
        if (disabled) return false
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
                "org.jetbrains.kotlin.com.intellij.psi.treeChangeListener" to PsiTreeChangeListener::class.java,
                "org.jetbrains.kotlin.com.intellij.psi.treeChangePreprocessor" to PsiTreeChangePreprocessor::class.java,
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
