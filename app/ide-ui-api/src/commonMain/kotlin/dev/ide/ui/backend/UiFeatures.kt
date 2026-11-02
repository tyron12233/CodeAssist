package dev.ide.ui.backend

/**
 * The feature ids [DiagnosticsService.countFeature] takes: stable snake_case names, so renaming one splits its
 * count in the backend. Every id is a constant here or built by a helper from a closed set of the app's own (a
 * screen, a tool window, a navigation kind), never from anything a user typed or named.
 */
object UiFeatures {
    /** The latency event a preview render reports through [DiagnosticsService.recordPerf] (mirrors the analytics
     *  module's `Events.PREVIEW_PERF`, which the shared UI can't link): dims `kind`, `first`, `ok`. */
    const val PREVIEW_PERF_EVENT = "preview_perf"

    const val PREVIEW_COMPOSE = "preview_compose"      // the Preview view on a Compose file
    const val PREVIEW_LAYOUT = "preview_layout"        // the Preview view on an XML layout
    const val PREVIEW_RESOURCE = "preview_resource"    // the Preview view on any other resource (drawable, colors)
    const val VIEW_BLOCKS = "view_blocks"              // the block editor
    const val VIEW_SPLIT = "view_split"                // code and preview side by side
    const val COMPLETION_ACCEPT = "completion_accept"
    const val QUICK_FIX = "quick_fix"                  // a quick fix or intention applied from the lightbulb
    const val RENAME = "rename"
    const val FORMAT = "format"
    const val QUICK_DOC = "quick_doc"
    const val COMMAND_PALETTE = "command_palette"
    const val AI_MESSAGE = "ai_message"
    const val GIT_COMMIT = "git_commit"

    /** Go to Declaration / Implementation / Type Declaration / Usages, by the navigation kind's own name. */
    fun navigation(kind: String): String = "nav_" + snake(kind)

    /** A screen opened, by the app's screen name (`IconManager` -> `screen_icon_manager`). */
    fun screen(name: String): String = "screen_" + snake(name)

    /** A tool window shown, by its built-in id. */
    fun toolWindow(id: String): String = "tool_" + snake(id)

    /** `IconManager` and `TYPE_DECLARATION` alike to `icon_manager` / `type_declaration`: a new word starts at
     *  an upper-case letter that follows a lower-case one or a digit, and any other character separates words. */
    fun snake(name: String): String = buildString {
        var previous = ' '
        for (c in name) {
            when {
                c.isUpperCase() -> {
                    if ((previous.isLowerCase() || previous.isDigit()) && !endsWith('_')) append('_')
                    append(c.lowercaseChar())
                }
                c.isLetterOrDigit() -> append(c)
                isNotEmpty() && !endsWith('_') -> append('_')
            }
            previous = c
        }
    }.trimEnd('_')
}
