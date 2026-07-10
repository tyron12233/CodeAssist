package dev.ide.plugin.action

import dev.ide.platform.ExtensionPoint

/**
 * The lean, IntelliJ-style action model. An [IdeAction] is one invocable command (a toolbar button, a menu
 * row, a command-palette entry); an [ActionGroup] nests actions and other groups for menus; an [ActionPlace]
 * names where they appear. Actions and groups are contributed through [UI_ACTION_EP] / [ACTION_GROUP_EP], so
 * adding a button is a registration rather than a host edit, the same pattern the language backends, indexes,
 * and settings pages already use.
 *
 * This module knows nothing about Compose or the engine. The host (`ide-core`) registers built-ins and runs
 * the resolved actions; the UI renders them through neutral DTOs over the `IdeBackend` port. A third-party
 * plugin contributes to the same extension points the bundled actions use.
 */

/** Plugins (and the host's built-ins) contribute invocable actions here. */
val UI_ACTION_EP = ExtensionPoint<IdeAction>("platform.uiAction")

/** Plugins (and the host's built-ins) contribute menu nesting here. */
val ACTION_GROUP_EP = ExtensionPoint<ActionGroup>("platform.actionGroup")
