package dev.ide.core.settings

import dev.ide.platform.Topic

/**
 * A settings value changed (a generic settings-page control, or an engine-level per-project choice like
 * the active build variant). Published on the app message bus so every consumer (the engine's
 * [dev.ide.core.WorkspaceEventHub] config-stamp/invalidation, the settings UI's own apply logic, and
 * the out-of-process engines' hint fan-out) observes the same stream instead of being hand-called.
 *
 * [key] is the page-local control key (e.g. `conflictPolicy`), or an engine-scoped pref name for
 * non-page changes (the active-variant change publishes `variant.<moduleName>` under the `build` page).
 */
data class SettingChanged(
    val pageId: String,
    val key: String,
    /** True when the value lives in the per-project store (`.platform/settings.properties`). */
    val projectScoped: Boolean,
)

/** Listener for [SettingChanged] batches (one publish per user action today). */
fun interface SettingsListener {
    fun onSettingsChanged(events: List<SettingChanged>)
}

object SettingsTopics {
    val CHANGES: Topic<SettingsListener> = Topic("settings.changes", SettingsListener::class.java)
}
