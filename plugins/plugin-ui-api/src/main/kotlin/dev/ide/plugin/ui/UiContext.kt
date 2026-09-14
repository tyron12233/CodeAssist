// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin.ui

import dev.ide.platform.ServiceKey

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

    /**
     * The service registered under [key], or null when nothing registered one.
     *
     * This is how a panel reaches state its **own** engine facet owns, and it is the shape to prefer over a
     * shared `object`: the container holds the instance, so it is scoped and disposed rather than living as a
     * static for as long as the plugin's classloader does. Declare the key and the service type in your own
     * module, register it from [dev.ide.plugin.Plugin.register], and resolve it here:
     *
     * ```kotlin
     * val MY_CHAT = ServiceKey<MyChatState>("com.example.chat")
     *
     * @Composable
     * fun Panel(ctx: UiContext) {
     *     val chat = ctx.service(MY_CHAT) ?: return
     *     val state by chat.state.collectAsState()
     * }
     * ```
     *
     * The host never names your service's type: it resolves the key and hands the instance back, so nothing
     * about your service becomes part of this SPI. Resolution runs against the same container the engine tier
     * uses, so a key registered at a narrower scope answers for the open project and is disposed with it.
     *
     * Null is the normal answer, not an error: the plugin that registers the key may be disabled, or may be
     * an older version that predates it. A panel that cannot proceed without one should render its empty
     * state rather than throw.
     *
     * Defaulted so a host that wires no container (a preview, a test) needs nothing, and so adding a lookup
     * to an existing host is not a breaking change.
     */
    fun <T : Any> service(key: ServiceKey<T>): T? = null

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
