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

    companion object {

        /**
         * A context for a `@Preview`, so a panel can be composed without installing the plugin.
         *
         * A body takes a context, so previewing one means having a context outside the IDE, and writing that
         * by hand is four members of boilerplate in every plugin. What a preview actually wants to vary is
         * the two properties: how the panel looks with a file open and how it looks with none is the whole of
         * what there is to see. [openFile] and [openScreen] do nothing here, because a preview has no editor
         * to open a file in and no screen to go to.
         *
         * ```kotlin
         * @Preview
         * @Composable
         * fun HelloPanelPreview() {
         *     HelloPanel(UiContext.preview(activeFilePath = "App.kt"))
         * }
         * ```
         */
        fun preview(
            projectPath: String? = PREVIEW_PROJECT_PATH,
            activeFilePath: String? = null,
        ): UiContext = PreviewContext(projectPath, activeFilePath)
    }
}

/** [UiContext] plus the way back, for a body that occupies the whole screen. */
interface ScreenUiContext : UiContext {

    /** Return to wherever this screen was opened from. */
    fun back()

    companion object {

        /** A context for a `@Preview` of a [Screen]; see [UiContext.preview]. [back] does nothing. */
        fun preview(
            projectPath: String? = PREVIEW_PROJECT_PATH,
            activeFilePath: String? = null,
        ): ScreenUiContext = PreviewContext(projectPath, activeFilePath)
    }
}

/** Stands in for the open project in a preview, so a body that shows the project's name has one to show. */
private const val PREVIEW_PROJECT_PATH = "/Projects/Sample"

/** The one implementation behind both `preview` factories: it answers, and does nothing else. */
private class PreviewContext(
    override val projectPath: String?,
    override val activeFilePath: String?,
) : ScreenUiContext {
    override fun openFile(path: String, offset: Int) = Unit
    override fun openScreen(id: String) = Unit
    override fun back() = Unit
}
