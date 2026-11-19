// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin.action

/**
 * A read-only snapshot of what an action acts on, passed to [IdeAction.isEnabled] / [IdeAction.isVisible] /
 * [IdeAction.perform]. Everything is neutral data so the snapshot can cross the UI boundary as a DTO and be
 * reconstructed host-side.
 *
 * Built-in actions (registered in `ide-core`) capture whatever host capability they need at construction.
 * A future revision adds a permission-gated host facade here for third-party plugins (the trust model in
 * `docs/ui-extensibility-and-plugin-api.md`); Phase A does not need it.
 */
interface ActionContext {
    /** The place the action is being resolved/invoked for. */
    val place: ActionPlace

    /** The open workspace root, or null when none is open. */
    val projectRoot: String?

    /** The file open in the active editor, or null. */
    val activeFilePath: String?

    /** The active editor selection `[selectionStart, selectionEnd)`, both null when there is no editor. */
    val selectionStart: Int?
    val selectionEnd: Int?

    /**
     * The tree/tab node the action was invoked on, when the place is a context menu (e.g. the file-tree row
     * for [ActionPlaces.FILE_CONTEXT]). Null for global places like the toolbar or palette.
     */
    val contextPath: String?

    /**
     * What the caret is on, for the places that resolve against editor content ([ActionPlaces.EDITOR], and
     * the command palette when an editor is focused). Null when there is no editor, when the file has not
     * been parsed yet, or for a place that carries no caret (the file tree).
     *
     * Defaulted so an action written before editor places existed still compiles, and so a host that has no
     * editor concept can implement [ActionContext] without one.
     */
    val caret: CaretContext? get() = null

    /**
     * The live text of [activeFilePath], for an action that rewrites it. Null when there is no editor, or
     * for a place that acts on something other than editor content.
     *
     * An action computes its [ActionEffect.TextEdit]s against exactly this string, and the host applies them
     * to the same buffer, so offsets cannot drift between deciding and applying. Read it rather than the
     * file on disk: the buffer may hold unsaved changes.
     */
    val documentText: String? get() = null

    /**
     * The open workspace's services, for an action that changes the project rather than a file: resolve
     * `dev.ide.model.WORKSPACE_SERVICE` for the `Workspace` (find the module under [contextPath], open a
     * modification, set a facet, add a source root) or `dev.ide.model.MODULE_SOURCES`. Read-only resolution:
     * it can look services up, not define them.
     *
     * [dev.ide.platform.ServiceLookup.Empty] when no workspace is open. Since SPI 3.1.0; a plugin that runs on
     * an older host reads it inside `catch (LinkageError)`.
     */
    val workspaceServices: dev.ide.platform.ServiceLookup get() = dev.ide.platform.ServiceLookup.Empty
}