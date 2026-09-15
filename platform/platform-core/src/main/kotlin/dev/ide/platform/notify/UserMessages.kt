// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform.notify

import dev.ide.platform.ServiceKey

/** How loudly a [UserMessage] is presented, and what it is colored as. */
enum class MessageSeverity {
    /** Something finished, or something the user should know. Dismisses itself. */
    INFO,

    /** Something is off but the work continued. Stays a little longer. */
    WARNING,

    /** Something failed. Stays until dismissed, so it cannot scroll past unread. */
    ERROR,
}

/**
 * One thing to tell the user, with at most one thing they can do about it.
 *
 * [actionLabel] and [onAction] go together: neither alone does anything. The action runs on the UI thread,
 * so it should hand off rather than work, and it is the plugin's own code, so it can do anything the plugin
 * can, opening one of its own screens included.
 */
class UserMessage(
    val text: String,
    val severity: MessageSeverity = MessageSeverity.INFO,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/**
 * A piece of long work the user can see while it runs. Obtained from [UserMessages.startProgress] and
 * finished exactly once; a handle that is never finished leaves a row on screen forever, so finish it from a
 * `finally`.
 *
 * Both [detail] and [fraction] may be set as often as the work has something new to say: the host samples
 * them for display rather than redrawing per write, so a tight loop does not have to rate-limit itself.
 */
interface ProgressHandle {
    /** What the work is doing right now ("Linking libfoo.so"), or null to show only the title. */
    var detail: String?

    /** How far along, 0f to 1f, or null while that is not known (the bar runs indeterminate). */
    var fraction: Float?

    /** Take the row off the screen. Idempotent: a second call does nothing. */
    fun finish()
}

/**
 * How a plugin's ENGINE facet tells the user something.
 *
 * The engine facet has no Compose and no screen of its own, so before this it could report only through the
 * build console (if it happened to be running inside a build) or the log (which nobody has open). A plugin
 * that unpacks a toolchain on first use, or fails to find one, had no way to say so.
 *
 * ```
 * private lateinit var services: ServiceLookup
 *
 * override fun register(reg: PluginRegistration) {
 *     services = reg.appServices
 * }
 *
 * private fun unpack() {
 *     val ui = services.getServiceOrNull(USER_MESSAGES)
 *     val progress = ui?.startProgress("Unpacking the NDK toolchain")
 *     try {
 *         …
 *     } finally {
 *         progress?.finish()
 *     }
 * }
 * ```
 *
 * A **question** is deliberately not here. A plugin that needs an answer contributes a
 * `dev.ide.plugin.ui.Overlay` from its UI facet and renders its own prompt, which is what overlays are for
 * and gives the plugin its own wording, layout and validation rather than one the host imposes.
 *
 * Registered at APPLICATION scope, so it is there whether or not a project is open. Resolve it with
 * `getServiceOrNull` and carry on without it when absent: a headless build, a test harness and the desktop
 * bootstrap all run with no UI to show anything on, and a plugin must not fail because nobody is watching.
 */
interface UserMessages {
    /** Show [message]. Returns at once; nothing about it blocks on the user. */
    fun show(message: UserMessage)

    /**
     * Start showing long work named [title], and answer the handle that updates and ends it.
     *
     * For work measured in seconds that the user did not explicitly start. Work they DID start has somewhere
     * better to report: a build task writes to the build console, which is already open in front of them.
     */
    fun startProgress(title: String): ProgressHandle
}

/** Show a plain informational message. */
fun UserMessages.info(text: String): Unit = show(UserMessage(text, MessageSeverity.INFO))

/** Show a warning: the work continued, but not as asked. */
fun UserMessages.warn(text: String): Unit = show(UserMessage(text, MessageSeverity.WARNING))

/** Show a failure. It stays until the user dismisses it. */
fun UserMessages.error(text: String): Unit = show(UserMessage(text, MessageSeverity.ERROR))

/** The host's [UserMessages], registered at application scope. Absent when there is no UI. */
val USER_MESSAGES = ServiceKey<UserMessages>("platform.userMessages")
