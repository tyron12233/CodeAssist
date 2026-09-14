// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin.editor

import dev.ide.platform.Topic

/**
 * Editor-session transitions, on the application message bus.
 *
 * A plugin subscribes through its registrar (`reg.busConnection().subscribe(EditorTopics.EDITOR,
 * listener)`); that connection is tracked, so the subscription is removed when the plugin unloads.
 *
 * This is the push counterpart of [EditorDecorationProvider]. A decoration provider is pulled, per file, on
 * the highlighting pass, and is the right layer for anything drawn on the text. These events say which files
 * the user has open and where the caret is, which is what a plugin keeping its own view of the session needs
 * and what no pulled provider can tell it.
 *
 * The UI drives these, and the IDE republishes them: a host with no editor (a headless launcher, or one
 * that opens no files) publishes none. The publish is guarded, so a listener that throws cannot disturb the
 * editor. Delivery is synchronous, in subscription order, on the thread reporting the transition. These
 * come from the UI side rather than from an engine worker, so a listener must not block on the call.
 */
object EditorTopics {
    val EDITOR: Topic<EditorEventListener> = Topic("ide.editor", EditorEventListener::class.java)
}

/** An editor-session transition. Paths are workspace paths; offsets index the editor's current text. */
sealed interface EditorEvent {
    data class FileOpened(val path: String) : EditorEvent
    data class FileClosed(val path: String) : EditorEvent
    /** The focused editor changed; [path] is null when the last editor closed, leaving nothing focused. */
    data class ActiveEditorChanged(val path: String?) : EditorEvent
    /** The selection/caret in [path] moved to `[start, end)`; a bare caret has `start == end`. The UI
     *  debounces these, so they fire on settle rather than on every keystroke. */
    data class SelectionChanged(val path: String, val start: Int, val end: Int) : EditorEvent
}

fun interface EditorEventListener {
    fun onEditorEvent(event: EditorEvent)
}
