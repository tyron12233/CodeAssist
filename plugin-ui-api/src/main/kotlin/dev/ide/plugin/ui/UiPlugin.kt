// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin.ui

/**
 * The UI facet of a plugin: what it implements to contribute Compose-bearing UI.
 *
 * A plugin has up to two facets, and they are separate objects only because a `@Composable` body cannot live
 * in the engine module. The **engine facet** (`dev.ide.plugin.Plugin`) contributes data: services, analyzers,
 * actions, settings pages. This one contributes UI that renders itself: a tool window, a screen, an overlay.
 *
 * Both are named by the same packaged manifest and loaded off the same APK, so **they share a classloader**:
 * the two facets of one plugin can hold a common `object`, read each other's state and call each other
 * directly, with no bridge and nothing crossing a boundary as data. (Two *different* plugins cannot: each
 * gets its own classloader over its own APK.)
 *
 * ```toml
 * # res/raw/codeassist_plugin.toml
 * entryPoints = ["com.example.hello.HelloPlugin"]      # the engine facet
 * uiEntryPoints = ["com.example.hello.HelloUiPlugin"]  # this one
 * ```
 *
 * Either list may be empty, so a plugin can be engine-only, UI-only, or both. The engine facet's
 * enable/disable and consent decision gates both: a plugin the user has not allowed contributes no UI, and a
 * plugin whose engine facet failed to load contributes none either (its UI would be talking to services that
 * never registered).
 *
 * [contribute] runs once, at IDE startup, on the main thread, before the first frame. It should only
 * register; the work belongs in the bodies, which run when something is actually shown.
 */
interface UiPlugin {

    /**
     * This plugin's id. It must equal the `id` in the packaged manifest, which is what the IDE attributes
     * every contribution to.
     */
    val id: String

    /** Register this plugin's UI. Called once; see the note on [UiPlugin]. */
    fun contribute(ui: UiRegistration)
}

/**
 * What a [UiPlugin] registers through. Each call returns a [UiHandle] that removes the contribution again;
 * holding on to one is optional, since unloading the plugin disposes them all.
 */
interface UiRegistration {

    /** The contributing plugin's id, as the IDE knows it (the packaged manifest's `id`). */
    val pluginId: String

    /** Add a dockable panel. See [ToolWindow]. */
    fun toolWindow(toolWindow: ToolWindow): UiHandle

    /** Add a full screen, reachable by its id. See [Screen]. */
    fun screen(screen: Screen): UiHandle

    /** Add a floating layer drawn above every screen. See [Overlay]. */
    fun overlay(overlay: Overlay): UiHandle
}

/** Removes one contribution. */
fun interface UiHandle {
    fun dispose()
}
