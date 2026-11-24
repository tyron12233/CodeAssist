package dev.ide.ui.backend

/**
 * One of Home's server-driven announcements, ready to draw: its text already resolved for the language the
 * UI asked in, its unknown parts already dropped (see [StoreService.homeAnnouncements]).
 *
 * [dismissKey] is what the UI stores when the user closes it. It carries the [revision], so the admin can
 * show an edited announcement again to the people who closed the old one by bumping it.
 */
data class UiAnnouncement(
    val id: String,
    val revision: Int,
    val priority: Int,
    val dismissible: Boolean,
    /** When it stops showing, in epoch milliseconds, or null for open-ended. */
    val endsAtMillis: Long?,
    val style: UiAnnouncementStyle,
    val accent: UiAnnouncementAccent,
    /** A name from the announcement icon set, or null for none. */
    val icon: String?,
    /** What tapping the card body does, or null when the body is not a target. */
    val action: UiAnnouncementAction?,
    val blocks: List<UiAnnouncementBlock>,
    /** 1: drawn exactly as the first card styles; 2: drawn from [design] and the blocks' own properties. */
    val version: Int = 1,
    /** The card's own design (version 2). Null fields take the [style] preset's value. */
    val design: UiAnnouncementDesign? = null,
) {
    val dismissKey: String get() = "$id@$revision"
}

/** A colour as the admin wrote it, resolved against the theme when drawn. */
sealed interface UiAnnouncementColor {
    /** ARGB values for the light and the dark theme (the same value twice when one was written). */
    data class Fixed(val light: Long, val dark: Long) : UiAnnouncementColor
    /** A theme role by its wire name (`primary`, `onSurface`, `accent`, ...). */
    data class Token(val name: String) : UiAnnouncementColor
    /** Readable on whatever is behind it. */
    data object Auto : UiAnnouncementColor
}

enum class UiAnnouncementFit { COVER, CONTAIN }

sealed interface UiAnnouncementPaint {
    data class Solid(val color: UiAnnouncementColor) : UiAnnouncementPaint
    /** [angle] in degrees: 0 runs start to end, 90 top to bottom. */
    data class Linear(val colors: List<UiAnnouncementColor>, val angle: Float) : UiAnnouncementPaint
    /** Centre as fractions of the size (x from the start edge); [radius] as a fraction of the larger side. */
    data class Radial(val colors: List<UiAnnouncementColor>, val centerX: Float, val centerY: Float, val radius: Float) : UiAnnouncementPaint
    /** [src] as for an image block; [scrim] is the opacity of [scrimColor] (black when null) drawn over it. */
    data class Image(val src: String, val fit: UiAnnouncementFit, val scrim: Float, val scrimColor: UiAnnouncementColor?) : UiAnnouncementPaint
}

data class UiAnnouncementBorder(val color: UiAnnouncementColor, val width: Float)

data class UiAnnouncementDesign(
    val background: UiAnnouncementPaint? = null,
    val content: UiAnnouncementColor? = null,
    val corner: Float? = null,
    val padding: Float? = null,
    val border: UiAnnouncementBorder? = null,
    val minHeight: Float? = null,
    val fullBleed: Boolean = false,
    val elevation: Float? = null,
)

enum class UiAnnouncementAlign { START, CENTER, END }
enum class UiAnnouncementWidth { FILL, WRAP }
enum class UiAnnouncementHideOn { PHONE, WIDE }
enum class UiAnnouncementVAlign { TOP, CENTER, BOTTOM }
enum class UiAnnouncementAt { TOP_START, TOP, TOP_END, START, CENTER, END, BOTTOM_START, BOTTOM, BOTTOM_END }
enum class UiAnnouncementArrange { START, CENTER, END, SPACE_BETWEEN, SPACED, STACKED }
enum class UiAnnouncementFontWeight { REGULAR, MEDIUM, SEMIBOLD, BOLD }

/** What every block may carry; null means the block's default. [pad] is top, end, bottom, start, in dp. */
data class UiAnnouncementProps(
    val pad: List<Float>? = null,
    val align: UiAnnouncementAlign? = null,
    val width: UiAnnouncementWidth? = null,
    val weight: Float? = null,
    val background: UiAnnouncementPaint? = null,
    val corner: Float? = null,
    val hideOn: UiAnnouncementHideOn? = null,
    val at: UiAnnouncementAt? = null,
) {
    companion object {
        val NONE = UiAnnouncementProps()
    }
}

/** [size] in sp. */
data class UiAnnouncementTextStyle(
    val size: Float? = null,
    val weight: UiAnnouncementFontWeight? = null,
    val color: UiAnnouncementColor? = null,
    val textAlign: UiAnnouncementAlign? = null,
    val maxLines: Int? = null,
    val italic: Boolean = false,
) {
    companion object {
        val NONE = UiAnnouncementTextStyle()
    }
}

enum class UiAnnouncementStyle { CARD, BANNER, HERO }

enum class UiAnnouncementAccent { LIME, TEAL, VIOLET, AMBER, RED, NEUTRAL }

enum class UiAnnouncementButtonStyle { PRIMARY, SECONDARY, TEXT }

sealed interface UiAnnouncementBlock {
    val props: UiAnnouncementProps

    /**
     * [src] is a path in the store's public media bucket or an `https` URL; either way the UI turns it into a
     * file through [StoreService.announcementImage]. [aspect] is width over height; [height] (dp) wins over it.
     */
    data class Image(
        val src: String,
        val aspect: Float,
        val alt: String?,
        val height: Float? = null,
        val fit: UiAnnouncementFit? = null,
        override val props: UiAnnouncementProps = UiAnnouncementProps.NONE,
    ) : UiAnnouncementBlock
    data class Title(
        val text: String,
        val style: UiAnnouncementTextStyle = UiAnnouncementTextStyle.NONE,
        override val props: UiAnnouncementProps = UiAnnouncementProps.NONE,
    ) : UiAnnouncementBlock
    data class Text(
        val text: String,
        val style: UiAnnouncementTextStyle = UiAnnouncementTextStyle.NONE,
        override val props: UiAnnouncementProps = UiAnnouncementProps.NONE,
    ) : UiAnnouncementBlock
    data class Icon(
        val name: String,
        val size: Float? = null,
        val color: UiAnnouncementColor? = null,
        override val props: UiAnnouncementProps = UiAnnouncementProps.NONE,
    ) : UiAnnouncementBlock
    data class Badge(
        val text: String,
        val color: UiAnnouncementColor? = null,
        override val props: UiAnnouncementProps = UiAnnouncementProps.NONE,
    ) : UiAnnouncementBlock
    data class Chips(
        val items: List<String>,
        val color: UiAnnouncementColor? = null,
        override val props: UiAnnouncementProps = UiAnnouncementProps.NONE,
    ) : UiAnnouncementBlock
    data class Buttons(
        val items: List<UiAnnouncementButton>,
        val arrange: UiAnnouncementArrange? = null,
        override val props: UiAnnouncementProps = UiAnnouncementProps.NONE,
    ) : UiAnnouncementBlock
    data class Divider(
        val color: UiAnnouncementColor? = null,
        val thickness: Float? = null,
        override val props: UiAnnouncementProps = UiAnnouncementProps.NONE,
    ) : UiAnnouncementBlock
    data class Spacer(
        val size: Float? = null,
        override val props: UiAnnouncementProps = UiAnnouncementProps.NONE,
    ) : UiAnnouncementBlock
    data class Row(
        val children: List<UiAnnouncementBlock>,
        val gap: Float? = null,
        val valign: UiAnnouncementVAlign? = null,
        val arrange: UiAnnouncementArrange? = null,
        override val props: UiAnnouncementProps = UiAnnouncementProps.NONE,
    ) : UiAnnouncementBlock
    data class Column(
        val children: List<UiAnnouncementBlock>,
        val gap: Float? = null,
        override val props: UiAnnouncementProps = UiAnnouncementProps.NONE,
    ) : UiAnnouncementBlock
    data class Stack(
        val children: List<UiAnnouncementBlock>,
        override val props: UiAnnouncementProps = UiAnnouncementProps.NONE,
    ) : UiAnnouncementBlock
}

data class UiAnnouncementButton(
    val label: String,
    val style: UiAnnouncementButtonStyle,
    val action: UiAnnouncementAction,
    val color: UiAnnouncementColor? = null,
    val content: UiAnnouncementColor? = null,
    val fullWidth: Boolean = false,
    val icon: String? = null,
)

sealed interface UiAnnouncementAction {
    /** An `https` link, opened by the platform. */
    data class Url(val url: String) : UiAnnouncementAction
    /** A plugin-contributed screen, by its registered id. */
    data class Screen(val id: String) : UiAnnouncementAction
    /** A store listing, by slug. */
    data class StoreItem(val id: String) : UiAnnouncementAction
    /** A settings page, by id. */
    data class Settings(val id: String) : UiAnnouncementAction
    /** A Learn track, by id. */
    data class Learn(val id: String) : UiAnnouncementAction
    /** Close the announcement, as its close button does. */
    data object Dismiss : UiAnnouncementAction
}
