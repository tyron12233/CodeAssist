package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ide.ui.theme.colors.AttributeStyle
import dev.ide.ui.theme.colors.ColorAttribute
import dev.ide.ui.theme.colors.ColorAttributes
import dev.ide.ui.theme.colors.ColorGroup
import dev.ide.ui.theme.colors.ColorSchemeJson
import dev.ide.ui.theme.colors.ColorSchemeStore
import dev.ide.ui.theme.colors.EditorColorScheme

/**
 * The color scheme editor's state: which scheme is being edited, which variant, and every mutation.
 *
 * Edits apply and persist immediately rather than behind a Save button. A color is judged by looking at it,
 * so the preview has to be live; and once it is live, a Save button only adds a way to lose the work. The
 * risk that buys — an experiment that cannot be undone — is covered by the presets being read-only and
 * "Reset" always being one tap away.
 *
 * Editing a built-in preset silently duplicates it first ([editable]). Asking first would be the desktop
 * convention, but here the alternative is a dialog between the user and every single color change; the
 * screen says which copy it made instead.
 */
@Stable
class ColorSchemeScreenState(
    private val store: ColorSchemeStore,
    /** Persist and apply — `CodeAssistAppState.applyColorScheme`, which re-renders the whole theme. */
    private val onApply: (EditorColorScheme) -> Unit,
    /** Which variant is being edited: the one the app is currently showing. */
    val isDark: Boolean,
) {
    var scheme: EditorColorScheme by mutableStateOf(store.active())
        private set

    /** Every scheme offered in the picker, presets first. Re-read after a create/delete/import. */
    var schemes: List<EditorColorScheme> by mutableStateOf(store.all())
        private set

    var sample: PreviewSample by mutableStateOf(ColorSchemeSamples.KOTLIN)
        private set

    /** The attribute whose editor sheet is open, or null. */
    var editing: ColorAttribute? by mutableStateOf(null)
        private set

    /** Set when an edit forked a preset, so the screen can say which copy it is now writing to. */
    var forkedInto: String? by mutableStateOf(null)
        private set

    /**
     * The attribute groups to render, straight from the registry — so a language's own attributes appear on
     * this screen with no change to it.
     */
    val groups: List<Pair<ColorGroup, List<ColorAttribute>>>
        get() = ColorAttributes.grouped()

    // ---- scheme-level ---------------------------------------------------------------------------------

    fun select(id: String) {
        val picked = store.byId(id) ?: return
        scheme = picked
        forkedInto = null
        store.setActiveId(picked.id)
        onApply(picked)
    }

    fun duplicate(name: String) {
        val copy = store.duplicate(scheme, name.trim().ifEmpty { "${scheme.name} copy" })
        schemes = store.all()
        scheme = copy
        forkedInto = null
        onApply(copy)
    }

    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || scheme.builtIn) return
        apply(scheme.copy(name = trimmed))
        schemes = store.all()
    }

    fun delete() {
        if (scheme.builtIn) return
        store.delete(scheme.id)
        schemes = store.all()
        val next = store.active()
        scheme = next
        forkedInto = null
        onApply(next)
    }

    /** Drop every override in the edited variant, returning it to the attribute defaults. */
    fun resetVariant() {
        if (scheme.builtIn) return
        apply(scheme.resetVariant(isDark))
    }

    fun exportJson(): String = ColorSchemeJson.encode(scheme)

    /** Returns the imported scheme's name, or null when the text was not a scheme. */
    fun import(json: String): String? {
        val imported = store.import(json) ?: return null
        schemes = store.all()
        scheme = imported
        forkedInto = null
        store.setActiveId(imported.id)
        onApply(imported)
        return imported.name
    }

    // ---- attribute-level ------------------------------------------------------------------------------

    fun openEditor(key: String) {
        val attribute = ColorAttributes.byKey(key) ?: return
        editing = attribute
        // Jump the preview to a sample that actually shows this attribute, so the change is visible while
        // it is being made rather than on some other tab.
        if (key !in ColorSchemeSamples.CHROME_KEYS) sample = ColorSchemeSamples.bestFor(key)
    }

    fun closeEditor() {
        editing = null
    }

    fun showSample(next: PreviewSample) {
        sample = next
    }

    /** The user's override for [key] in the edited variant, empty when they have not touched it. */
    fun overrideFor(key: String): AttributeStyle = scheme.variant(isDark)[key] ?: AttributeStyle.EMPTY

    fun setOverride(key: String, style: AttributeStyle) {
        apply(editable().withStyle(isDark, key, style))
    }

    fun resetAttribute(key: String) = setOverride(key, AttributeStyle.EMPTY)

    /**
     * The scheme an edit is written to: this one, or a fresh copy when this one is a shipped preset.
     * The presets stay as shipped, which is what makes "Reset" mean something.
     */
    private fun editable(): EditorColorScheme {
        if (!scheme.builtIn) return scheme
        val copy = store.duplicate(scheme, "${scheme.name} (custom)")
        schemes = store.all()
        forkedInto = copy.name
        return copy
    }

    private fun apply(next: EditorColorScheme) {
        scheme = next
        store.save(next)
        store.setActiveId(next.id)
        schemes = store.all()
        onApply(next)
    }
}

@Composable
fun rememberColorSchemeScreenState(
    store: ColorSchemeStore,
    isDark: Boolean,
    onApply: (EditorColorScheme) -> Unit,
): ColorSchemeScreenState = remember(store, isDark) {
    ColorSchemeScreenState(store, onApply, isDark)
}
