package dev.ide.ui.ext

import androidx.compose.runtime.mutableStateOf

/**
 * The UI's view of the keymap: what command a key press means, and what shortcut to show beside a command.
 *
 * The keymap itself is engine-tier (`dev.ide.plugin.keymap`), because a binding names an action id and
 * actions are engine-tier. This module's common code cannot see those types, so the currency here is
 * primitives: a key name and four booleans in, an action id out. The host installs a [Resolver] over the real
 * keymap through the jvmShared bridge, and the editor asks this.
 *
 * Nothing is installed in a preview, a test or a headless composition. The answer then comes from
 * [EditorKeyFallback], the editor's own default table, so such a host keeps the shortcuts it had before any of
 * this existed. Only the parts that need the engine are missing there: user overrides, plugin bindings,
 * chords and conflict reporting.
 */
object KeymapHost {

    /** What a press meant. */
    sealed interface Outcome {
        /** Run this command. The caller consumes the key. */
        class Command(val actionId: String) : Outcome

        /** The press opened a chord; the next press completes it. The caller must consume the key so the
         *  first half of a chord does not also land in the buffer. */
        class Pending(val label: String) : Outcome

        /** Not a command. The caller handles the key however it would have anyway. */
        object None : Outcome
    }

    /** What the host installs: the real keymap, behind a signature this module can express. */
    fun interface Resolver {
        fun resolve(
            key: String,
            ctrl: Boolean,
            shift: Boolean,
            alt: Boolean,
            meta: Boolean,
            inEditor: Boolean,
        ): Outcome
    }

    /** Renders the shortcut for an action id, for a menu row or a settings list. Null when it has none. */
    fun interface Labeler {
        fun label(actionId: String): String?
    }

    // Observable so a UI that shows a shortcut re-reads it when the user rebinds one. The resolver itself is
    // called from a key handler rather than from composition, but the labeler is read while composing.
    private val resolverState = mutableStateOf<Resolver?>(null)
    private val labelerState = mutableStateOf<Labeler?>(null)

    /** Install the keymap. Called once by the host at startup; replacing it is how a test drives one. */
    fun install(resolver: Resolver, labeler: Labeler): Registration {
        resolverState.value = resolver
        labelerState.value = labeler
        return Registration {
            resolverState.value = null
            labelerState.value = null
        }
    }

    /** Whether a keymap is installed at all. */
    val isInstalled: Boolean get() = resolverState.value != null

    /**
     * The command [key] means with those modifiers held, or [Outcome.None].
     *
     * [inEditor] is the context: an editor press also matches an app-wide binding, but not the other way
     * round. A resolver that throws answers [Outcome.None], so a broken keymap costs the user their shortcuts
     * and not their keyboard.
     */
    fun resolve(
        key: String,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
        inEditor: Boolean = false,
    ): Outcome {
        val resolver = resolverState.value ?: return fallback(key, ctrl, shift, alt, meta, inEditor)
        return runCatching { resolver.resolve(key, ctrl, shift, alt, meta, inEditor) }
            .getOrElse { fallback(key, ctrl, shift, alt, meta, inEditor) }
    }

    /**
     * The shortcut to show for [actionId] (e.g. `Ctrl/Cmd+Alt+L`), or null when it has none.
     *
     * The fallback applies only when NO keymap is installed. An installed one answering null means the
     * command has no shortcut, which is a real answer: the user may have removed it, and showing the
     * default they deleted would be worse than showing nothing.
     */
    fun shortcutLabel(actionId: String): String? {
        val labeler = labelerState.value
            ?: return EditorKeyFallback.specFor(actionId)?.let(::displayShortcut)
        return runCatching { labeler.label(actionId) }
            .getOrElse { EditorKeyFallback.specFor(actionId)?.let(::displayShortcut) }
    }

    /**
     * The editor's own defaults, used when no keymap is installed and when an installed one throws.
     *
     * Editor commands only, so a press outside the editor answers nothing: an app-wide binding is contributed
     * by the engine tier, and with no engine there is nothing to fall back to.
     */
    private fun fallback(
        key: String,
        ctrl: Boolean,
        shift: Boolean,
        alt: Boolean,
        meta: Boolean,
        inEditor: Boolean,
    ): Outcome {
        if (!inEditor) return Outcome.None
        val actionId = EditorKeyFallback.resolve(key, ctrl, shift, alt, meta) ?: return Outcome.None
        return Outcome.Command(actionId)
    }
}
