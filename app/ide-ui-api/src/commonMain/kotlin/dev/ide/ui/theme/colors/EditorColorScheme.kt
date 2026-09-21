package dev.ide.ui.theme.colors

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * A user- or preset-defined set of editor colors: two variants of the same scheme, one for each theme mode.
 *
 * A scheme carries both because the app's theme can follow the OS, and a single palette cannot be right on
 * both sides of that switch. Editing touches the variant for the mode currently in use; the other one keeps
 * whatever the preset it came from said, which is why duplicating a built-in and recoloring two entries in
 * the dark still leaves a usable light theme behind.
 *
 * Both maps are SPARSE. An absent key is not "no color", it is "no opinion" — resolution falls through to
 * the attribute's own default and then up its [ColorAttribute.parent] chain (see [ResolvedColorScheme]). A
 * scheme the user built by changing four things is four entries, and it keeps working as the IDE learns to
 * distinguish constructs it had never heard of.
 */
@Immutable
data class EditorColorScheme(
    val id: String,
    val name: String,
    /** A shipped preset: read-only, and the editor offers to duplicate it rather than refusing the edit. */
    val builtIn: Boolean = false,
    val dark: Map<String, AttributeStyle> = emptyMap(),
    val light: Map<String, AttributeStyle> = emptyMap(),
    /** The preset this was duplicated from, so "Reset" has somewhere to reset to. */
    val basedOn: String? = null,
) {
    fun variant(isDark: Boolean): Map<String, AttributeStyle> = if (isDark) dark else light

    /** [style] replaces the entry for [key] in the [isDark] variant; an empty style clears the override. */
    fun withStyle(isDark: Boolean, key: String, style: AttributeStyle): EditorColorScheme {
        val updated = variant(isDark).toMutableMap()
        if (style.isEmpty) updated.remove(key) else updated[key] = style
        return if (isDark) copy(dark = updated) else copy(light = updated)
    }

    /** Drop every override in the [isDark] variant, returning it to the attribute defaults. */
    fun resetVariant(isDark: Boolean): EditorColorScheme =
        if (isDark) copy(dark = emptyMap()) else copy(light = emptyMap())

    val overrideCount: Int get() = dark.size + light.size
}

/**
 * The theme-derived value for a chrome attribute a scheme says nothing about.
 *
 * The editor's caret, selection, gutter and diagnostic colors track the active Material scheme today, which
 * includes Material You wallpaper color — so making them scheme attributes with fixed defaults would have
 * quietly severed that. They are attributes with NO default instead, and the live theme supplies the value
 * until a scheme pins one. Built by `CodeAssistTheme`, where the resolved Material colors are in hand.
 */
@Immutable
class SchemeDefaults(val byKey: Map<String, AttributeStyle>) {
    operator fun get(key: String): AttributeStyle? = byKey[key]

    companion object {
        val EMPTY = SchemeDefaults(emptyMap())
    }
}

/**
 * One [EditorColorScheme] flattened for one theme mode: every attribute resolved through its overrides,
 * defaults and parent chain exactly once, up front.
 *
 * Resolution is eager because the alternative is a walk up the fallback chain inside the editor's draw path.
 * The registry is small (tens of entries) and this is rebuilt only when the scheme, the mode, the theme's
 * chrome defaults or the registry itself changes — so the editor's per-line styling is a map lookup.
 */
@Immutable
class ResolvedColorScheme(
    val scheme: EditorColorScheme,
    val isDark: Boolean,
    private val defaults: SchemeDefaults = SchemeDefaults.EMPTY,
    /** [ColorAttributes.version] this was built at; a composable keys its `remember` on it. */
    val registryVersion: Int = ColorAttributes.version,
) {
    private val resolved: Map<String, AttributeStyle>
    private val spanStyles: Map<String, SpanStyle>

    init {
        val attrs = ColorAttributes.all()
        val out = HashMap<String, AttributeStyle>(attrs.size * 2)
        for (a in attrs) out[a.key] = walk(a.key)
        // The root's foreground is the floor for anything whose fallback chain reaches it, applied after
        // the walk so a chain that never names a color still renders as text rather than as nothing.
        // Deliberately NOT applied to a rootless attribute: an overlay has no color of its own by design,
        // and a chrome attribute left unset must stay unset so the live theme's value shows through.
        val rootFg = out[ColorKeys.TEXT]?.foreground ?: FALLBACK_TEXT
        for (a in attrs) {
            if (a.parent == null && a.key != ColorKeys.TEXT) continue
            val style = out.getValue(a.key)
            if (style.foreground == null) out[a.key] = style.copy(foreground = rootFg)
        }
        resolved = out
        spanStyles = out.mapValues { (_, style) -> style.toSpanStyle() }
    }

    /** Walk [key] → its default → its parent → …, each level filling only what the levels below left unset. */
    private fun walk(key: String): AttributeStyle {
        var acc = AttributeStyle.EMPTY
        var current: String? = key
        val seen = HashSet<String>()
        while (current != null && seen.add(current)) {
            val attribute = ColorAttributes.byKey(current)
            val override = scheme.variant(isDark)[current]
            if (override != null) acc = acc.mergedOnto(override)
            // `inheritParent` is the user asking for the fallback chain INSTEAD of this attribute's own
            // idea of itself — the only way to say "color an extension function like any other function".
            if (override?.inheritParent != true) {
                defaults[current]?.let { acc = acc.mergedOnto(it) }
                attribute?.let { acc = acc.mergedOnto(it.defaultFor(isDark)) }
            }
            current = if (attribute?.overlay == true) null else attribute?.parent
        }
        return acc
    }

    /** The fully resolved style for [key]; an unregistered key resolves through the same rules. */
    fun styleOf(key: String): AttributeStyle = resolved[key] ?: walk(key)

    /** The foreground for [key], or the default text color when the attribute names none. */
    fun colorOf(key: String): Color = styleOf(key).foreground ?: textColor

    /** The fill for [key], or [fallback] when the attribute names none. */
    fun fillOf(key: String, fallback: Color = Color.Transparent): Color = styleOf(key).background ?: fallback

    /** [key] as a text [SpanStyle] — precomputed for every registered attribute, so the editor's per-line
     *  styling is a map lookup rather than a walk up the fallback chain. */
    fun spanStyleOf(key: String): SpanStyle = spanStyles[key] ?: styleOf(key).toSpanStyle()

    // ---- derived views -------------------------------------------------------------------------------

    val textColor: Color = resolved[ColorKeys.TEXT]?.foreground ?: FALLBACK_TEXT

    // ---- editor chrome -------------------------------------------------------------------------------
    // Each is the scheme's value where it has one and the live theme's where it does not, which is what
    // keeps an untouched install tracking the Material accent (Material You included).

    fun background(fallback: Color): Color = fillOf(ColorKeys.EDITOR_BACKGROUND, fallback)
    fun caret(fallback: Color): Color = styleOf(ColorKeys.EDITOR_CARET).foreground ?: fallback
    fun selection(fallback: Color): Color = fillOf(ColorKeys.EDITOR_SELECTION, fallback)
    fun currentLine(fallback: Color): Color = fillOf(ColorKeys.EDITOR_CURRENT_LINE, fallback)
    fun indentGuide(fallback: Color): Color = styleOf(ColorKeys.EDITOR_INDENT_GUIDE).foreground ?: fallback
    fun findMatch(fallback: Color): Color = fillOf(ColorKeys.EDITOR_FIND_MATCH, fallback)
    fun findCurrent(fallback: Color): Color = fillOf(ColorKeys.EDITOR_FIND_CURRENT, fallback)
    fun occurrence(fallback: Color): Color = fillOf(ColorKeys.EDITOR_OCCURRENCE, fallback)
    fun templateField(fallback: Color): Color = fillOf(ColorKeys.EDITOR_TEMPLATE_FIELD, fallback)
    fun composing(fallback: Color): Color = styleOf(ColorKeys.EDITOR_COMPOSING).foreground ?: fallback
    fun gutterText(fallback: Color): Color = styleOf(ColorKeys.GUTTER_TEXT).foreground ?: fallback
    fun gutterCurrent(fallback: Color): Color = styleOf(ColorKeys.GUTTER_CURRENT).foreground ?: fallback
    fun gutterBorder(fallback: Color): Color = styleOf(ColorKeys.GUTTER_BORDER).foreground ?: fallback
    fun error(fallback: Color): Color = styleOf(ColorKeys.DIAGNOSTIC_ERROR).foreground ?: fallback
    fun warning(fallback: Color): Color = styleOf(ColorKeys.DIAGNOSTIC_WARNING).foreground ?: fallback
    fun info(fallback: Color): Color = styleOf(ColorKeys.DIAGNOSTIC_INFO).foreground ?: fallback

    /** Inlay hints and the folded-region chip: a style rather than a color (both carry a font/fill). */
    fun inlayHintStyle(fallbackColor: Color): SpanStyle =
        styleOf(ColorKeys.EDITOR_INLAY_HINT).let { it.copy(foreground = it.foreground ?: fallbackColor) }.toSpanStyle()

    fun foldPlaceholderStyle(fallbackColor: Color, fallbackFill: Color): SpanStyle =
        styleOf(ColorKeys.EDITOR_FOLD_PLACEHOLDER)
            .let { it.copy(foreground = it.foreground ?: fallbackColor, background = it.background ?: fallbackFill) }
            .toSpanStyle()

    companion object {
        /** Last resort when a scheme, its defaults and the registry all fail to name a text color. */
        val FALLBACK_TEXT = Color(0xFFC7C8CF)
    }
}

/** A text style with the font flags resolved; [AttributeStyle.background] becomes the span's background. */
fun AttributeStyle.toSpanStyle(): SpanStyle = SpanStyle(
    color = foreground ?: Color.Unspecified,
    background = background ?: Color.Unspecified,
    fontStyle = if (italic == true) FontStyle.Italic else null,
    fontWeight = if (bold == true) FontWeight.Medium else null,
    textDecoration = textDecoration(),
)

private fun AttributeStyle.textDecoration(): TextDecoration? = when {
    underline == true && strikethrough == true ->
        TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
    underline == true -> TextDecoration.Underline
    strikethrough == true -> TextDecoration.LineThrough
    else -> null
}
