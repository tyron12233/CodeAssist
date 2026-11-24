package dev.ide.store

/**
 * Home's server-driven announcements, as `home_announcements(...)` returns them.
 *
 * The server owns which announcements are live, for whom, and what they say; the client owns drawing them.
 * The same two rules as the Explore feed make that safe across app versions, and both live in the parser:
 *
 *  1. An unknown block type, action type, style or accent is skipped or defaulted, never an error. The
 *     server can ship a new block before the app understands it, and an old client draws the rest.
 *  2. Nothing that could reach outside the app unchecked survives parsing: a `url` action or an external
 *     image must be `https`, and a button whose action is unknown is dropped rather than drawn dead.
 *
 * Text stays unresolved ([AnnouncementText]) until it is mapped for the screen, because the language to
 * pick is the UI's, not the transport's.
 */
data class AnnouncementFeed(
    val version: Int,
    val announcements: List<Announcement>,
)

data class Announcement(
    /** The admin's slug; with [revision] it is the dismissal key (`id@revision`). */
    val id: String,
    val revision: Int,
    val priority: Int,
    val dismissible: Boolean,
    /** The raw `timestamptz` it stops showing at, or null for open-ended. */
    val endsAt: String?,
    val layout: AnnouncementLayout,
)

/** How the card is drawn. An unknown wire value falls back to [CARD]. */
enum class AnnouncementStyle {
    CARD, BANNER, HERO;

    companion object {
        fun of(raw: String?): AnnouncementStyle = when (raw?.lowercase()) {
            "banner" -> BANNER
            "hero" -> HERO
            else -> CARD
        }
    }
}

/** The card's colour family. An unknown wire value falls back to [NEUTRAL]. */
enum class AnnouncementAccent {
    LIME, TEAL, VIOLET, AMBER, RED, NEUTRAL;

    companion object {
        fun of(raw: String?): AnnouncementAccent =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: NEUTRAL
    }
}

/** A button's emphasis. An unknown wire value falls back to [SECONDARY]. */
enum class AnnouncementButtonStyle {
    PRIMARY, SECONDARY, TEXT;

    companion object {
        fun of(raw: String?): AnnouncementButtonStyle = when (raw?.lowercase()) {
            "primary" -> PRIMARY
            "text" -> TEXT
            else -> SECONDARY
        }
    }
}

data class AnnouncementLayout(
    val style: AnnouncementStyle,
    val accent: AnnouncementAccent,
    /** One of [AnnouncementIcons.KNOWN], or null. */
    val icon: String?,
    /** What tapping the card body does, or null when the body is not a target. */
    val action: AnnouncementAction?,
    val blocks: List<AnnouncementBlock>,
    /** 1 for a layout with no `"v"`, which draws exactly as the first card styles did; 2 for a designed one. */
    val version: Int = 1,
    /** The card's own design (version 2). Null fields take the [style] preset's value. */
    val design: AnnouncementDesign? = null,
)

/** A colour as written: a fixed pair (light, dark) of ARGB values, a theme token, or `auto`. */
sealed interface AnnouncementColor {
    data class Fixed(val light: Long, val dark: Long) : AnnouncementColor
    /** One of [TOKENS], as written. */
    data class Token(val name: String) : AnnouncementColor
    /** Readable on whatever is behind it. */
    data object Auto : AnnouncementColor

    companion object {
        val TOKENS: Set<String> = setOf(
            "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "secondary", "secondaryContainer",
            "tertiary", "tertiaryContainer", "surface", "surfaceVariant", "onSurface", "onSurfaceVariant", "outline",
            "error", "accent", "onAccent",
        )
    }
}

enum class AnnouncementFit { COVER, CONTAIN }

/** A background: a solid colour, a linear or radial gradient, or an image with an optional scrim. */
sealed interface AnnouncementPaint {
    data class Solid(val color: AnnouncementColor) : AnnouncementPaint
    /** [angle] in degrees: 0 runs start to end, 90 top to bottom. */
    data class Linear(val colors: List<AnnouncementColor>, val angle: Float) : AnnouncementPaint
    /** Centre as fractions of the size; [radius] as a fraction of the larger side. */
    data class Radial(val colors: List<AnnouncementColor>, val centerX: Float, val centerY: Float, val radius: Float) : AnnouncementPaint
    data class Image(val src: String, val fit: AnnouncementFit, val scrim: Float, val scrimColor: AnnouncementColor?) : AnnouncementPaint
}

data class AnnouncementBorder(val color: AnnouncementColor, val width: Float)

/** The card itself. A null field takes the style preset's value. */
data class AnnouncementDesign(
    val background: AnnouncementPaint? = null,
    val content: AnnouncementColor? = null,
    val corner: Float? = null,
    val padding: Float? = null,
    val border: AnnouncementBorder? = null,
    val minHeight: Float? = null,
    val fullBleed: Boolean = false,
    val elevation: Float? = null,
)

enum class AnnouncementAlign { START, CENTER, END }
enum class AnnouncementWidth { FILL, WRAP }
enum class AnnouncementHideOn { PHONE, WIDE }
enum class AnnouncementVAlign { TOP, CENTER, BOTTOM }
enum class AnnouncementAt { TOP_START, TOP, TOP_END, START, CENTER, END, BOTTOM_START, BOTTOM, BOTTOM_END }

/** A row's `arrange` uses START..SPACE_BETWEEN; a button row's uses START, CENTER, END, SPACED, STACKED. */
enum class AnnouncementArrange { START, CENTER, END, SPACE_BETWEEN, SPACED, STACKED }

enum class AnnouncementFontWeight { REGULAR, MEDIUM, SEMIBOLD, BOLD }

/** What every block may carry. Null means "the block's default". [pad] is top, end, bottom, start. */
data class AnnouncementProps(
    val pad: List<Float>? = null,
    val align: AnnouncementAlign? = null,
    val width: AnnouncementWidth? = null,
    val weight: Float? = null,
    val background: AnnouncementPaint? = null,
    val corner: Float? = null,
    val hideOn: AnnouncementHideOn? = null,
    /** Where a stack's child sits; ignored elsewhere. */
    val at: AnnouncementAt? = null,
) {
    companion object {
        val NONE = AnnouncementProps()
    }
}

/** [size] in sp. */
data class AnnouncementTextStyle(
    val size: Float? = null,
    val weight: AnnouncementFontWeight? = null,
    val color: AnnouncementColor? = null,
    val textAlign: AnnouncementAlign? = null,
    val maxLines: Int? = null,
    val italic: Boolean = false,
) {
    companion object {
        val NONE = AnnouncementTextStyle()
    }
}

/**
 * A piece of text in one or more languages. A plain string on the wire is a single entry under the empty
 * key, which every lookup falls back to.
 */
data class AnnouncementText(val values: Map<String, String>) {

    /**
     * The best string for [localeTag] (`pt-BR`, `es`, `ar-EG`): the exact tag, then its language, then
     * English, then the first value written. Tags compare case-insensitively and `_` reads as `-`.
     */
    fun resolve(localeTag: String): String {
        if (values.isEmpty()) return ""
        val wanted = normalize(localeTag)
        val byKey = values.entries.associate { normalize(it.key) to it.value }
        byKey[wanted]?.let { return it }
        val language = wanted.substringBefore('-')
        byKey[language]?.let { return it }
        // A regional variant written without the plain language ("pt-BR" only, asked for "pt-PT").
        byKey.entries.firstOrNull { it.key.substringBefore('-') == language && language.isNotEmpty() }?.let { return it.value }
        byKey["en"]?.let { return it }
        byKey[""]?.let { return it }
        return values.values.first()
    }

    private fun normalize(tag: String): String = tag.trim().replace('_', '-').lowercase()

    companion object {
        fun plain(text: String) = AnnouncementText(mapOf("" to text))
    }
}

sealed interface AnnouncementBlock {
    val props: AnnouncementProps

    /** [src] is a `store-media` path (no scheme) or an `https` URL. [aspect] is width over height; [height] wins. */
    data class Image(
        val src: String,
        val aspect: Float,
        val alt: AnnouncementText?,
        val height: Float? = null,
        val fit: AnnouncementFit? = null,
        override val props: AnnouncementProps = AnnouncementProps.NONE,
    ) : AnnouncementBlock
    data class Title(
        val text: AnnouncementText,
        val style: AnnouncementTextStyle = AnnouncementTextStyle.NONE,
        override val props: AnnouncementProps = AnnouncementProps.NONE,
    ) : AnnouncementBlock
    data class Text(
        val text: AnnouncementText,
        val style: AnnouncementTextStyle = AnnouncementTextStyle.NONE,
        override val props: AnnouncementProps = AnnouncementProps.NONE,
    ) : AnnouncementBlock
    /** [name] is one of [AnnouncementIcons.KNOWN]; [size] in dp. */
    data class Icon(
        val name: String,
        val size: Float? = null,
        val color: AnnouncementColor? = null,
        override val props: AnnouncementProps = AnnouncementProps.NONE,
    ) : AnnouncementBlock
    /** A small pill label; its fill is [props]' background. */
    data class Badge(
        val text: AnnouncementText,
        val color: AnnouncementColor? = null,
        override val props: AnnouncementProps = AnnouncementProps.NONE,
    ) : AnnouncementBlock
    /** Each chip's fill is [props]' background. */
    data class Chips(
        val items: List<AnnouncementText>,
        val color: AnnouncementColor? = null,
        override val props: AnnouncementProps = AnnouncementProps.NONE,
    ) : AnnouncementBlock
    data class Buttons(
        val items: List<AnnouncementButton>,
        val arrange: AnnouncementArrange? = null,
        override val props: AnnouncementProps = AnnouncementProps.NONE,
    ) : AnnouncementBlock
    data class Divider(
        val color: AnnouncementColor? = null,
        val thickness: Float? = null,
        override val props: AnnouncementProps = AnnouncementProps.NONE,
    ) : AnnouncementBlock
    data class Spacer(
        val size: Float? = null,
        override val props: AnnouncementProps = AnnouncementProps.NONE,
    ) : AnnouncementBlock
    data class Row(
        val children: List<AnnouncementBlock>,
        val gap: Float? = null,
        val valign: AnnouncementVAlign? = null,
        val arrange: AnnouncementArrange? = null,
        override val props: AnnouncementProps = AnnouncementProps.NONE,
    ) : AnnouncementBlock
    data class Column(
        val children: List<AnnouncementBlock>,
        val gap: Float? = null,
        override val props: AnnouncementProps = AnnouncementProps.NONE,
    ) : AnnouncementBlock
    /** Children drawn over each other, each placed by its [AnnouncementProps.at]. */
    data class Stack(
        val children: List<AnnouncementBlock>,
        override val props: AnnouncementProps = AnnouncementProps.NONE,
    ) : AnnouncementBlock
}

data class AnnouncementButton(
    val label: AnnouncementText,
    val style: AnnouncementButtonStyle,
    val action: AnnouncementAction,
    /** The button's container colour. */
    val color: AnnouncementColor? = null,
    /** The label's colour. */
    val content: AnnouncementColor? = null,
    val fullWidth: Boolean = false,
    /** One of [AnnouncementIcons.KNOWN], drawn before the label. */
    val icon: String? = null,
)

sealed interface AnnouncementAction {
    /** Always `https`; anything else is dropped by the parser. */
    data class Url(val url: String) : AnnouncementAction
    /** A screen a plugin registered, by id (`agent.claude`). */
    data class Screen(val id: String) : AnnouncementAction
    /** A store listing, by slug. */
    data class StoreItem(val id: String) : AnnouncementAction
    /** A settings page, by id. */
    data class Settings(val id: String) : AnnouncementAction
    /** A Learn track, by id. */
    data class Learn(val id: String) : AnnouncementAction
    /** Dismiss the announcement, as its close button does. */
    data object Dismiss : AnnouncementAction
}

/**
 * The icon names a layout may use. Each maps to a glyph the app already draws; a name outside this set is
 * read as no icon, so the admin page can offer a new one before every client knows it.
 */
object AnnouncementIcons {
    val KNOWN: Set<String> = setOf(
        "campaign", "bell", "sparkle", "lightbulb", "heart", "bolt", "rocket", "info", "star", "school",
        "store", "extension", "code", "android", "chat", "event", "fire", "premium", "verified", "warning",
        "update", "download", "palette", "link",
    )
}
