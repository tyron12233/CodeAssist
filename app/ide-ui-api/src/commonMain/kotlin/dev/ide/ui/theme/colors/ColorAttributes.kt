package dev.ide.ui.theme.colors

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.ide.ui.concurrent.UiLock
import dev.ide.ui.ext.Registration

/**
 * The editor's color model: what a scheme can color, and how one entry falls back to another.
 *
 * The editor used to color from [dev.ide.ui.theme.SyntaxColors] — eighteen fields fixed in the theme source,
 * so "keyword" was a compile-time fact and XML tags, Markdown headings and Kotlin's own distinctions all had
 * to borrow whichever of the eighteen was closest. The set is data now: an attribute is a string [key] with a
 * display name, a group, and a [parent] it falls back to. Everything downstream — the lexical palette, the
 * semantic overlay, the editor's own chrome, and the screen that edits all of it — is generated from the
 * registry, so registering `mylang.directive` yields a colorable, user-editable attribute with no change to
 * the editor or to its settings UI.
 *
 * This model lives in `ide-ui-api` rather than beside the theme it feeds, and that is the whole reason a
 * plugin shipped as its own APK can take part: the contribution model and the bridge from `plugin-ui-api`
 * are here, below the UI, so an installed plugin registers an attribute through
 * `UiContributionScope.colorAttribute` on exactly the same registry the shell uses. The two pieces that
 * genuinely belong above stayed above — `ResolvedColorScheme.toSyntaxColors()` with the theme tokens, and
 * `tokenColorKey` beside the scanner whose token types it reads.
 *
 * The [parent] chain is why the set can be large without a scheme having to answer for all of it: a scheme
 * that says nothing about `kotlin.function.extension` still colors one, through `function` and then `text`.
 * That is what lets a scheme imported from somewhere else, or one a user built by touching four entries,
 * stay complete as the IDE learns to distinguish more constructs.
 */
@Immutable
data class AttributeStyle(
    val foreground: Color? = null,
    val background: Color? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strikethrough: Boolean? = null,
    /**
     * Skip the attribute's own built-in default and take everything unset here from [ColorAttribute.parent].
     *
     * Without this there is no way to say "color an extension function like any other function": clearing
     * the override just falls back to the attribute's default, which is a distinct color by design. This is
     * the user choosing the fallback chain over the default, and it is per-field — inherit the color and
     * still add italic.
     */
    val inheritParent: Boolean = false,
) {
    val isEmpty: Boolean
        get() = foreground == null && background == null && bold == null &&
            italic == null && underline == null && strikethrough == null && !inheritParent

    /** This style wins; [base] supplies every field this one leaves unset. */
    fun mergedOnto(base: AttributeStyle): AttributeStyle = AttributeStyle(
        foreground = foreground ?: base.foreground,
        background = background ?: base.background,
        bold = bold ?: base.bold,
        italic = italic ?: base.italic,
        underline = underline ?: base.underline,
        strikethrough = strikethrough ?: base.strikethrough,
    )

    companion object {
        val EMPTY = AttributeStyle()
        fun fg(color: Color) = AttributeStyle(foreground = color)
        fun bg(color: Color) = AttributeStyle(background = color)
    }
}

/** A group of related attributes, rendered as one section of the color scheme editor. */
@Immutable
class ColorGroup(val id: String, val title: String, val order: Int)

/**
 * One colorable thing.
 *
 * [defaultDark]/[defaultLight] are the attribute's own opinion of how it should look, which is what makes
 * the shipped scheme an EMPTY map rather than a table of sixty entries repeated per preset — and what makes
 * a newly added attribute look right in a scheme built before it existed, which could not have named it.
 */
@Immutable
class ColorAttribute(
    val key: String,
    val title: String,
    val group: String,
    /** Fallen back to when neither the scheme nor this attribute's own default answers for a field. */
    val parent: String? = null,
    val defaultDark: AttributeStyle = AttributeStyle.EMPTY,
    val defaultLight: AttributeStyle = AttributeStyle.EMPTY,
    /** Only a fill makes sense (a selection band, the current-line tint): the editor hides the rest. */
    val backgroundOnly: Boolean = false,
    /** Only a foreground makes sense (the caret, a gutter glyph, an indent guide). */
    val foregroundOnly: Boolean = false,
    /**
     * Whether bold/italic/underline mean anything here. False for the attributes that are not text at all —
     * a caret, a selection band, a squiggle — so the editor does not offer to italicize a rectangle.
     */
    val fontStyled: Boolean = true,
    /**
     * Not a style of its own but a modification of one — `static` adds italic to whatever the symbol already
     * is, `deprecated` adds a strikethrough. An overlay never inherits and never falls back to `text`; its
     * unset fields simply leave the base alone.
     */
    val overlay: Boolean = false,
    val order: Int = 0,
) {
    fun defaultFor(dark: Boolean): AttributeStyle = if (dark) defaultDark else defaultLight
}

/**
 * The process-global registry of colorable attributes.
 *
 * Mirrors [dev.ide.ui.ext.EditorLanguageRegistry]: the shell registers what it ships (in
 * [BuiltInColorAttributes]) and anything else in the app registers its own, getting a [Registration] to drop
 * them again. [version] is bumped on every change so a composable holding a resolved scheme can key its
 * `remember` on it and pick up a late arrival.
 */
object ColorAttributes {
    // A stack per key rather than one entry, so an override is undone by removing THIS registration and
    // letting whatever is left win. Storing the displaced value and putting it back instead looks simpler
    // and is wrong the moment two registrations for one key are disposed out of order: the first one to go
    // would restore a value the second had already replaced.
    private val attributes = LinkedHashMap<String, MutableList<ColorAttribute>>()
    private val groups = LinkedHashMap<String, MutableList<ColorGroup>>()
    private val lock = UiLock()

    /** Bumped on every registration/removal; a cache over [all] keys its invalidation on this. */
    var version: Int = 0
        private set

    init {
        BuiltInColorAttributes.groups.forEach { registerGroup(it) }
        BuiltInColorAttributes.attributes.forEach { register(it) }
    }

    /**
     * Register [group]; the most recent registration for an id is the one in force, and disposing this
     * handle hands the id back to whatever else still claims it.
     *
     * That is what makes an override safe to undo. A plugin that recolors a built-in and is then turned off
     * would otherwise take the built-in out of the registry with it, and the construct it renamed would go
     * uncolored and uneditable until a restart.
     */
    fun registerGroup(group: ColorGroup): Registration {
        lock.withLock { groups.getOrPut(group.id) { ArrayList(1) }.add(group); version++ }
        return Registration { lock.withLock { drop(groups, group.id, group) } }
    }

    /** Register [attribute]; see [registerGroup] for what overriding an existing key means. */
    fun register(attribute: ColorAttribute): Registration {
        lock.withLock { attributes.getOrPut(attribute.key) { ArrayList(1) }.add(attribute); version++ }
        return Registration { lock.withLock { drop(attributes, attribute.key, attribute) } }
    }

    /** Remove one registration by identity, and the key with it once nothing claims it. A handle disposed
     *  twice finds nothing to remove and does nothing. */
    private fun <T> drop(from: MutableMap<String, MutableList<T>>, key: String, value: T) {
        val stack = from[key] ?: return
        if (!stack.removeAll { it === value }) return
        if (stack.isEmpty()) from.remove(key)
        version++
    }

    /** Register several at once; disposing the handle removes all of them. */
    fun registerAll(list: List<ColorAttribute>): Registration {
        val handles = list.map { register(it) }
        return Registration { handles.forEach { it.dispose() } }
    }

    fun byKey(key: String): ColorAttribute? = lock.withLock { attributes[key]?.lastOrNull() }

    fun all(): List<ColorAttribute> = lock.withLock { attributes.values.mapNotNull { it.lastOrNull() } }

    fun group(id: String): ColorGroup? = lock.withLock { groups[id]?.lastOrNull() }

    /**
     * Every group that has attributes, in display order, each with its own in order.
     *
     * A group id no one declared is synthesized from the id itself and sorted after the declared ones. That
     * is what lets a contributed language name its section in one string (`group = "GLSL"`) instead of
     * registering a group first: the alternative was that forgetting the group registration dropped the
     * attribute out of the settings screen entirely, with nothing to say why.
     */
    fun grouped(): List<Pair<ColorGroup, List<ColorAttribute>>> = lock.withLock {
        val byGroup = attributes.values.mapNotNull { it.lastOrNull() }.groupBy { it.group }
        val declared = groups.values.mapNotNull { it.lastOrNull() }.sortedBy { it.order }
        val undeclared = byGroup.keys.filter { it !in groups }
            .sorted()
            .map { ColorGroup(it, it, UNDECLARED_GROUP_ORDER) }
        (declared + undeclared).mapNotNull { g ->
            val items = byGroup[g.id]?.sortedWith(compareBy({ it.order }, { it.title })) ?: return@mapNotNull null
            if (items.isEmpty()) null else g to items
        }
    }

    /** Where a synthesized group sorts: after everything the shell declares. */
    private const val UNDECLARED_GROUP_ORDER = 1000
}
