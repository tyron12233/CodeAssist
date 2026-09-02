// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin.ui

/**
 * What the IDE hands a contributed body when it renders: where the user is, and the few host operations a
 * panel cannot perform for itself.
 *
 * Deliberately small. A plugin's UI is not meant to reach into the IDE from here. It reaches into its own
 * **engine facet**, which shares its classloader (see [UiPlugin]) and has the whole engine SPI: the virtual
 * file system, the project model, indexes, analysis, the message bus. This interface is only for the things
 * that are properties of the *running UI*, which no amount of engine access can answer.
 *
 * The values are read during composition, so a body that reads one recomposes when it changes.
 */
interface UiContext {

    /** Absolute path of the open project's root, or null when no project is open. */
    val projectPath: String?

    /** Absolute path of the focused editor tab, or null when no file is open. */
    val activeFilePath: String?

    /**
     * Open [path] in the editor and put the caret at [offset]. A path that is not in the open project, or
     * cannot be read, is ignored.
     */
    fun openFile(path: String, offset: Int = 0)

    /** Show the [Screen] registered under [id] (this plugin's, or any other's). Unknown ids are ignored. */
    fun openScreen(id: String)
}

/** [UiContext] plus the way back, for a body that occupies the whole screen. */
interface ScreenUiContext : UiContext {

    /** Return to wherever this screen was opened from. */
    fun back()
}
