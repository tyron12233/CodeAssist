// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin.editor

/**
 * Action ids for a [GutterMark] that the editor carries out itself, rather than dispatching to an
 * `IdeAction` the plugin registered.
 *
 * Since SPI 3.7.0. The ids are plain strings and [RUN_TASK_PREFIX] is a compile-time constant, so a plugin
 * that builds its id from the constant carries the text itself and works on an older host as well: every
 * host that draws plugin gutter marks starts a run for an id of this form.
 */
object GutterActions {

    /** Prefixes a Run task id: tapping the mark starts that task and makes it the Run button's task. */
    const val RUN_TASK_PREFIX: String = "ca.run:"

    /**
     * The action id that starts the Run task [taskId], the id of a `RunTaskSpec` a
     * `dev.ide.build.RunTaskProvider` lists. The Run button then shows that task under the label the
     * provider gave it.
     */
    fun runTask(taskId: String): String = RUN_TASK_PREFIX + taskId
}
