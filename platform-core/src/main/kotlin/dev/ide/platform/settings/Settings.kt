package dev.ide.platform.settings

import dev.ide.platform.ExtensionPoint

/**
 * The extensible settings/preferences framework. A **page** is one entry in the Settings screen's category
 * sidebar; it declares a list of typed **controls** the UI renders generically — exactly like the module
 * config screen renders facet fields, so a contributed page needs no UI or host change to appear.
 *
 * Built-ins (Appearance, Editor, Completion, Analysis, Build, Privacy) and third-party plugins register
 * alike on [SETTINGS_PAGE_EP]. Values persist as strings in a host-provided [PreferenceStore] scoped by
 * [SettingsPage.scope]; a page reads its own values back through a page-scoped [PreferenceReader] (the host
 * namespaces keys by page id, so control keys are page-local and can't collide across plugins). A page may
 * react to a change in [SettingsPage.onChanged] (re-apply an effect) and handle button presses in
 * [SettingsPage.onAction].
 *
 * Gate third-party pages behind the plugin trust model — a page runs host code on change/action.
 */
val SETTINGS_PAGE_EP = ExtensionPoint<SettingsPage>("platform.settingsPage")

/** Where a page's values persist and how broadly they apply. */
enum class SettingsScope {
    /** Shared across every project (the IDE-wide prefs file) — appearance, editor, completion behaviour. */
    APPLICATION,

    /** Scoped to the open project (`.platform/`) — inspection profile, dependency policy, repositories. */
    PROJECT,
}

/**
 * A category in the Settings screen. [iconId] is resolved by the UI's icon registry (e.g. `"gear"`,
 * `"code"`, `"pkg"`). [order] sorts pages in the sidebar (built-ins occupy 0..99; plugins default to 1000
 * so they list after the built-ins).
 */
interface SettingsPage {
    val id: String
    val title: String
    val iconId: String
    val scope: SettingsScope get() = SettingsScope.APPLICATION
    val order: Int get() = 1000

    /** The controls to render, in display order. May depend on current state (re-queried when shown). */
    fun controls(): List<SettingControl>

    /** Called after one of this page's values changed; [values] reads the page's (already-updated) values. */
    fun onChanged(key: String, values: PreferenceReader) {}

    /** Handle a [SettingControl.Action] press for [key]; returns a short status message, or null. */
    fun onAction(key: String, values: PreferenceReader): String? = null
}

/**
 * One configurable control on a page. [key] is page-local and stable (the storage id). [advanced] tucks the
 * control into the page's collapsible "Advanced" group; [group] is an optional sub-heading controls share.
 */
sealed interface SettingControl {
    val key: String
    val title: String
    val description: String?
    val advanced: Boolean
    val group: String?

    /** A boolean on/off switch. */
    data class Toggle(
        override val key: String,
        override val title: String,
        override val description: String? = null,
        val default: Boolean = false,
        override val advanced: Boolean = false,
        override val group: String? = null,
    ) : SettingControl

    /** An integer chosen on a slider over `[min, max]` stepped by [step]; [unit] is a trailing label (e.g. "ms"). */
    data class IntSlider(
        override val key: String,
        override val title: String,
        override val description: String? = null,
        val default: Int = 0,
        val min: Int = 0,
        val max: Int = 100,
        val step: Int = 1,
        val unit: String? = null,
        override val advanced: Boolean = false,
        override val group: String? = null,
    ) : SettingControl

    /** A one-of-N choice rendered as a segmented control / chips. */
    data class Choice(
        override val key: String,
        override val title: String,
        override val description: String? = null,
        val default: String = "",
        val options: List<Option> = emptyList(),
        override val advanced: Boolean = false,
        override val group: String? = null,
    ) : SettingControl {
        data class Option(val value: String, val label: String)
    }

    /** A free-text field. */
    data class Text(
        override val key: String,
        override val title: String,
        override val description: String? = null,
        val default: String = "",
        val placeholder: String = "",
        override val advanced: Boolean = false,
        override val group: String? = null,
    ) : SettingControl

    /** A button rather than a value (e.g. "Clear caches"). Pressing it routes [key] to [SettingsPage.onAction]. */
    data class Action(
        override val key: String,
        override val title: String,
        override val description: String? = null,
        val buttonLabel: String = "Run",
        val destructive: Boolean = false,
        override val advanced: Boolean = false,
        override val group: String? = null,
    ) : SettingControl
}

/** Read-only view of a page's stored values, with typed accessors that fall back to a default. */
interface PreferenceReader {
    fun raw(key: String): String?
    fun bool(key: String, default: Boolean): Boolean = raw(key)?.toBooleanStrictOrNull() ?: default
    fun int(key: String, default: Int): Int = raw(key)?.trim()?.toIntOrNull() ?: default
    fun float(key: String, default: Float): Float = raw(key)?.trim()?.toFloatOrNull() ?: default
    fun string(key: String, default: String): String = raw(key) ?: default
}

/** A writable [PreferenceReader]; setting a key to null clears it (reverts to the control default). */
interface PreferenceStore : PreferenceReader {
    fun set(key: String, value: String?)
}
