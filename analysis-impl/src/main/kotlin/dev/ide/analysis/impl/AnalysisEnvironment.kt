package dev.ide.analysis.impl

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.ProjectAnalysisScope
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.LanguageId
import dev.ide.vfs.VirtualFile

/**
 * The host-provided seam the [AnalysisEngine] pulls per-file context from and pushes edits to. The host
 * (e.g. ide-desktop) implements it over a language backend (`SourceAnalyzer` → DOM + resolver), the
 * project model (`Module`), and the index — so analysis-impl itself depends only on analysis-api +
 * coroutines and never on a concrete backend.
 */
interface AnalysisEnvironment {
    /**
     * Per-file context (DOM, resolver, index, module, current document version), or null if [file] isn't
     * analyzable. [needsBindings] is the engine's tier signal: false for a SYNTAX-only pass (the host may
     * build a cheap syntax-only tree, with binding-level diagnostics sourced separately); true when a
     * SEMANTIC+ analyzer will run, so the tree must carry resolved bindings (and that one pass can also
     * yield the compiler diagnostics). The host is free to ignore it if its backend has no cheaper mode.
     */
    suspend fun targetFor(file: VirtualFile, needsBindings: Boolean = false): AnalysisTarget?

    /** The language of [file] (drives per-analyzer language filtering); null = unknown ⇒ no filtering. */
    fun languageOf(file: VirtualFile): LanguageId?

    /** The whole-project view used by the coalesced project-analyzer sweep. */
    fun projectScope(): ProjectAnalysisScope

    /**
     * Apply [edit] atomically under the model write lock, recomputed against a fresh snapshot. Returns
     * the edit actually applied, or [WorkspaceEdit.EMPTY] if it was rejected as stale.
     */
    suspend fun applyEdit(edit: WorkspaceEdit): WorkspaceEdit
}
