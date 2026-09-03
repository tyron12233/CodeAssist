// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin

/**
 * The vocabulary of [PluginManifest.capabilities]: what a plugin says it does, shown to the user at the
 * consent gate before it is allowed to run.
 *
 * Declared rather than enforced. An installed plugin runs in the IDE's process under its UID, so this is a
 * description, not a sandbox, and the manifest's list is only as honest as its author. What it can be held to
 * is consistency: a capability the plugin has no facet to deliver is one the user was shown for nothing, and
 * a spelling the IDE does not know is shown verbatim, which is worse than useless on a consent screen.
 * [KNOWN], [NEEDS_UI_FACET] and [NEEDS_ENGINE_FACET] are what the editor checks a manifest against.
 *
 * The ids are strings in TOML, so the constants here exist for the code that reads them, not for the manifest
 * that writes them.
 */
object PluginCapabilities {

    /** Contributes a command to the palette or a menu. Engine facet. */
    const val UI_ACTION = "ui.action"

    /** Contributes a category to Settings. Engine facet. */
    const val UI_SETTINGS_PAGE = "ui.settingsPage"

    /** Contributes an action at the caret in the editor. Engine facet. */
    const val UI_EDITOR_ACTION = "ui.editorAction"

    /** Contributes a dockable panel. UI facet. */
    const val UI_TOOL_WINDOW = "ui.toolWindow"

    /** Contributes a full screen. UI facet. */
    const val UI_SCREEN = "ui.screen"

    /** Contributes a layer drawn over every screen. UI facet. */
    const val UI_OVERLAY = "ui.overlay"

    /** Adds steps to the user's builds (a build plugin, or a build system of its own). Engine facet. */
    const val BUILD_TASK = "build.task"

    /** Generates source code into the user's modules at build time. Engine facet. */
    const val BUILD_SOURCE_GENERATOR = "build.sourceGenerator"

    /** Adds a row to the Run picker, and runs it. Engine facet. */
    const val BUILD_RUN_TASK = "build.runTask"

    /** Reads the files in the user's projects. */
    const val FS_READ = "fs.read"

    /** Changes the files in the user's projects. */
    const val FS_WRITE = "fs.write"

    /** Makes network requests. */
    const val NET = "net"

    /** Every capability this build understands. A manifest naming anything else is flagged as a typo. */
    val KNOWN: Set<String> = linkedSetOf(
        UI_ACTION, UI_SETTINGS_PAGE, UI_EDITOR_ACTION,
        UI_TOOL_WINDOW, UI_SCREEN, UI_OVERLAY,
        BUILD_TASK, BUILD_SOURCE_GENERATOR, BUILD_RUN_TASK,
        FS_READ, FS_WRITE, NET,
    )

    /** Capabilities only a [PluginManifest.uiEntryPoints] class can deliver: they are Compose contributions. */
    val NEEDS_UI_FACET: Set<String> = linkedSetOf(UI_TOOL_WINDOW, UI_SCREEN, UI_OVERLAY)

    /**
     * Capabilities only a [PluginManifest.entryPoints] class can deliver: they are registrations against
     * extension points, which is the engine facet's half of the SPI.
     *
     * The file and network ones are in neither set on purpose. Either facet can read a file or open a socket,
     * so there is nothing about the manifest that makes declaring one inconsistent.
     */
    val NEEDS_ENGINE_FACET: Set<String> = linkedSetOf(
        UI_ACTION, UI_SETTINGS_PAGE, UI_EDITOR_ACTION,
        BUILD_TASK, BUILD_SOURCE_GENERATOR, BUILD_RUN_TASK,
    )
}
