package dev.ide.plugin.impl

import dev.ide.platform.ExtensionRegistry
import dev.ide.plugin.keymap.KEY_BINDING_EP
import dev.ide.plugin.keymap.KeyBinding
import dev.ide.plugin.keymap.KeyContext
import dev.ide.plugin.keymap.KeyPress
import dev.ide.plugin.keymap.KeyStroke
import dev.ide.plugin.keymap.Shortcut
import dev.ide.plugin.keymap.matches

/**
 * Resolves a key press to the command the user meant: the single consumer of [KEY_BINDING_EP], and the
 * counterpart of [ActionManager] for the keyboard.
 *
 * Three layers, in decreasing authority:
 *
 *  1. the user's own bindings, from settings;
 *  2. what plugins contributed to [KEY_BINDING_EP], highest [KeyBinding.order] first;
 *  3. nothing, in which case the press was not a command and the caller handles it as text or navigation.
 *
 * A user's rebinding wins outright. That is the whole point of a keymap: if a plugin could take a shortcut
 * back from the user, rebinding it would be advisory.
 *
 * Chords are handled by holding state. A press that is a prefix of some binding but not a whole binding
 * leaves the resolver [pending], and the next press is matched against the strokes that follow it. Anything
 * that is not a continuation clears the pending state and is re-matched from scratch, so a mistyped chord
 * costs one key rather than wedging the keyboard.
 */
class KeymapResolver(
    private val registry: ExtensionRegistry,
    /**
     * The user's bindings, as `actionId -> shortcut spec`, read fresh on every resolution so a rebinding
     * applies without restarting anything. A spec that does not parse is ignored, which is what keeps a
     * hand-edited settings file from disabling the keyboard.
     *
     * An entry mapping an action to a BLANK spec is meaningful: it means the user removed the shortcut, and
     * the contributed default must not come back.
     */
    private val userBindings: () -> Map<String, String> = { emptyMap() },
) {

    /** What a press produced. */
    sealed interface Result {
        /** The press completed a binding: run [actionId]. */
        class Command(val actionId: String) : Result

        /** The press began a chord and the resolver is waiting for the next stroke. The caller must consume
         *  the key (so it does not also reach the buffer) and show the pending prefix if it shows anything. */
        class Pending(val prefix: List<KeyPress>) : Result

        /** The press is not part of any binding. The caller handles it however it would have anyway. */
        object None : Result
    }

    private var pending: List<KeyPress> = emptyList()

    /** The presses typed so far in a chord, empty when none is in progress. */
    val pendingStrokes: List<KeyPress> get() = pending

    /** Abandon a chord in progress: called when focus moves, or a dialog opens, so it cannot leak into it. */
    fun clearPending() {
        pending = emptyList()
    }

    /**
     * Resolve [press] in [context].
     *
     * [context] is what the caller can see about focus, and a [KeyContext.Editor] press also matches a
     * [KeyContext.Global] binding: the editor is inside the IDE, so an app-wide shortcut still applies there.
     * The reverse is not true, which is the point of the distinction.
     */
    fun resolve(press: KeyPress, context: KeyContext): Result {
        val sequence = pending + press
        val bindings = effectiveBindings().filter { it.context == context || it.context == KeyContext.Global }

        val exact = bindings.firstOrNull {
            it.shortcut.strokes.size == sequence.size && it.shortcut.strokes.matchAll(sequence)
        }
        if (exact != null) {
            pending = emptyList()
            return Result.Command(exact.actionId)
        }
        // A prefix of a longer binding: hold the sequence and wait. Checked AFTER the exact match, so a
        // one-stroke binding is never swallowed by a chord that happens to start with the same key.
        if (bindings.any { it.shortcut.strokes.size > sequence.size && it.shortcut.strokes.matchAll(sequence) }) {
            pending = sequence
            return Result.Pending(sequence)
        }
        // Not a continuation. Clear and re-try this press on its own, so the second key of a mistyped chord
        // still works as its own shortcut instead of being eaten.
        if (pending.isNotEmpty()) {
            pending = emptyList()
            return resolve(press, context)
        }
        return Result.None
    }

    /** The shortcut in force for [actionId], or null when it has none (including one the user removed). */
    fun shortcutFor(actionId: String): Shortcut? =
        effectiveBindings().firstOrNull { it.actionId == actionId }?.shortcut

    /** Every binding in force, user overrides applied, highest-authority first. */
    fun bindings(): List<KeyBinding> = effectiveBindings()

    /**
     * Shortcuts that more than one action claims, as `shortcut -> the actions claiming it, winner first`.
     *
     * Worth surfacing rather than resolving silently: the loser looks broken to the user (they press the key
     * and the wrong thing happens), and only they can say which one they meant.
     */
    fun conflicts(): Map<String, List<String>> =
        effectiveBindings()
            .groupBy { it.shortcut.toString() }
            .filterValues { it.size > 1 }
            .mapValues { (_, group) -> group.map { it.actionId } }

    /**
     * The user's overrides laid over the contributed defaults.
     *
     * Rebuilt per call rather than cached: this is driven by a key press, so it runs at human speed over a
     * list of a few dozen, and a cache would need invalidating from both the extension registry and the
     * settings store.
     */
    private fun effectiveBindings(): List<KeyBinding> {
        val contributed = registry.extensions(KEY_BINDING_EP).sortedByDescending { it.order }
        val overrides = userBindings()
        if (overrides.isEmpty()) return contributed

        val out = ArrayList<KeyBinding>(contributed.size + overrides.size)
        // A user binding for an action replaces that action's contributed default entirely, including
        // replacing it with nothing (a blank spec means "no shortcut").
        for ((actionId, spec) in overrides) {
            val shortcut = Shortcut.parse(spec) ?: continue
            val default = contributed.firstOrNull { it.actionId == actionId }
            out.add(KeyBinding(actionId, shortcut, default?.context ?: KeyContext.Global, order = Int.MAX_VALUE))
        }
        for (binding in contributed) if (binding.actionId !in overrides) out.add(binding)
        return out
    }

    /** Whether this binding's strokes match [presses] as far as [presses] goes. */
    private fun List<KeyStroke>.matchAll(presses: List<KeyPress>): Boolean {
        if (presses.size > size) return false
        for (i in presses.indices) if (!this[i].matches(presses[i])) return false
        return true
    }
}
