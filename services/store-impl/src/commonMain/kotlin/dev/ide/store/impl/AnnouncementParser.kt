package dev.ide.store.impl

import dev.ide.platform.JsonReader
import dev.ide.store.Announcement
import dev.ide.store.AnnouncementAccent
import dev.ide.store.AnnouncementAction
import dev.ide.store.AnnouncementAlign
import dev.ide.store.AnnouncementArrange
import dev.ide.store.AnnouncementAt
import dev.ide.store.AnnouncementBlock
import dev.ide.store.AnnouncementBorder
import dev.ide.store.AnnouncementButton
import dev.ide.store.AnnouncementButtonStyle
import dev.ide.store.AnnouncementColor
import dev.ide.store.AnnouncementDesign
import dev.ide.store.AnnouncementFeed
import dev.ide.store.AnnouncementFit
import dev.ide.store.AnnouncementFontWeight
import dev.ide.store.AnnouncementHideOn
import dev.ide.store.AnnouncementIcons
import dev.ide.store.AnnouncementLayout
import dev.ide.store.AnnouncementPaint
import dev.ide.store.AnnouncementProps
import dev.ide.store.AnnouncementStyle
import dev.ide.store.AnnouncementText
import dev.ide.store.AnnouncementTextStyle
import dev.ide.store.AnnouncementVAlign
import dev.ide.store.AnnouncementWidth

/**
 * Turns the `home_announcements(...)` document into an [AnnouncementFeed].
 *
 * The forward-compatibility rules are enforced here, once:
 *
 *  - An unknown key is ignored and an unknown block type is skipped; the rest of the card is still drawn.
 *  - An unknown action type is dropped. On the card body that leaves the body inert; on a button it drops the
 *    button, because a button that does nothing is worse than no button.
 *  - A `url` action and an external image must be `https`. Anything else is dropped as if it were unknown.
 *  - A number outside its range is clamped into it; a value of the wrong kind is ignored (the default stands).
 *  - Containers nest at most [MAX_DEPTH] deep and a layout holds at most [MAX_BLOCKS] blocks, counted in
 *    document order with nested ones; what is past either limit is dropped.
 *  - An announcement left with nothing to draw is dropped.
 *
 * A layout without `"v"` is version 1, a subset of version 2, and keeps the first card styles' look.
 *
 * Nothing here throws. A malformed document degrades to fewer announcements.
 */
object AnnouncementParser {

    fun parse(raw: String): AnnouncementFeed? = JsonReader.parseOrNull(raw)?.let(::parseFeed)

    fun parseFeed(root: Any?): AnnouncementFeed? {
        val obj = JsonReader.obj(root) ?: return null
        return AnnouncementFeed(
            version = JsonReader.int(obj, "version", 1),
            announcements = JsonReader.arr(obj["announcements"]).mapNotNull(::parseAnnouncement),
        )
    }

    private fun parseAnnouncement(v: Any?): Announcement? {
        val id = JsonReader.str(v, "id")?.takeIf { it.isNotBlank() } ?: return null
        val layout = parseLayout(JsonReader.obj(v)?.get("layout")) ?: return null
        if (!layout.blocks.any(::draws) && layout.design?.background == null) return null
        return Announcement(
            id = id,
            revision = JsonReader.int(v, "revision", 1),
            priority = JsonReader.int(v, "priority"),
            dismissible = JsonReader.bool(v, "dismissible", default = true),
            endsAt = JsonReader.str(v, "endsAt"),
            layout = layout,
        )
    }

    /** Whether a block shows content of its own (or holds a block that does). */
    private fun draws(b: AnnouncementBlock): Boolean = when (b) {
        is AnnouncementBlock.Title, is AnnouncementBlock.Text, is AnnouncementBlock.Image,
        is AnnouncementBlock.Icon, is AnnouncementBlock.Badge -> true
        is AnnouncementBlock.Row -> b.children.any(::draws)
        is AnnouncementBlock.Column -> b.children.any(::draws)
        is AnnouncementBlock.Stack -> b.children.any(::draws)
        else -> false
    }

    internal fun parseLayout(v: Any?): AnnouncementLayout? {
        val obj = JsonReader.obj(v) ?: return null
        val version = if (number(obj["v"])?.toInt() == 2) 2 else 1
        val budget = Budget()
        return AnnouncementLayout(
            style = AnnouncementStyle.of(JsonReader.str(obj, "style")),
            accent = AnnouncementAccent.of(JsonReader.str(obj, "accent")),
            icon = icon(obj["icon"]),
            action = parseAction(obj["action"]),
            blocks = parseBlocks(obj["blocks"], depth = 0, budget = budget),
            version = version,
            design = if (version == 2) parseDesign(obj["design"]) else null,
        )
    }

    /** How many more blocks this layout may hold. */
    private class Budget(var left: Int = MAX_BLOCKS)

    private fun parseBlocks(v: Any?, depth: Int, budget: Budget): List<AnnouncementBlock> {
        val out = ArrayList<AnnouncementBlock>()
        for (item in JsonReader.arr(v)) {
            if (budget.left <= 0) break
            parseBlock(item, depth, budget)?.let(out::add)
        }
        return out
    }

    /** Null for an unknown type, a block that would draw nothing, or a container past [MAX_DEPTH]. */
    private fun parseBlock(v: Any?, depth: Int, budget: Budget): AnnouncementBlock? {
        val obj = JsonReader.obj(v) ?: return null
        val type = JsonReader.str(obj, "type")
        val container = type == "row" || type == "column" || type == "stack"
        if (container && depth >= MAX_DEPTH) return null
        if (type !in KNOWN_BLOCKS) return null
        // A block takes its place in the budget before its children, so the count runs in document order.
        budget.left--
        val props = parseProps(obj)
        return when (type) {
            "image" -> {
                val src = JsonReader.str(obj, "src")?.trim()?.takeIf(::isAllowedImage) ?: return null
                AnnouncementBlock.Image(
                    src = src,
                    aspect = clamp(obj["aspect"], 0.5f, 4f) ?: DEFAULT_ASPECT,
                    alt = parseText(obj["alt"]),
                    height = clamp(obj["height"], 40f, 400f),
                    fit = fit(obj["fit"]),
                    props = props,
                )
            }
            "title" -> parseText(obj["text"])?.let { AnnouncementBlock.Title(it, parseTextStyle(obj), props) }
            "text" -> parseText(obj["text"])?.let { AnnouncementBlock.Text(it, parseTextStyle(obj), props) }
            "icon" -> icon(obj["name"])?.let {
                AnnouncementBlock.Icon(it, clamp(obj["size"], 16f, 96f), parseColor(obj["color"], allowAuto = true), props)
            }
            "badge" -> parseText(obj["text"])?.let {
                AnnouncementBlock.Badge(it, parseColor(obj["color"], allowAuto = true), props)
            }
            "chips" -> JsonReader.arr(obj["items"])
                .mapNotNull { parseText(JsonReader.obj(it)?.get("text")) }
                .takeIf { it.isNotEmpty() }
                ?.let { AnnouncementBlock.Chips(it, parseColor(obj["color"], allowAuto = true), props) }
            "buttons" -> JsonReader.arr(obj["items"])
                .mapNotNull(::parseButton)
                .takeIf { it.isNotEmpty() }
                ?.let {
                    val arrange = when (JsonReader.str(obj, "arrange")) {
                        "start" -> AnnouncementArrange.START
                        "center" -> AnnouncementArrange.CENTER
                        "end" -> AnnouncementArrange.END
                        "spaced" -> AnnouncementArrange.SPACED
                        "stacked" -> AnnouncementArrange.STACKED
                        else -> null
                    }
                    AnnouncementBlock.Buttons(it, arrange, props)
                }
            "divider" -> AnnouncementBlock.Divider(parseColor(obj["color"]), clamp(obj["thickness"], 0.5f, 4f), props)
            "spacer" -> AnnouncementBlock.Spacer(clamp(obj["size"], 0f, 64f), props)
            "row" -> AnnouncementBlock.Row(
                children = parseBlocks(obj["children"], depth + 1, budget),
                gap = clamp(obj["gap"], 0f, 32f),
                valign = when (JsonReader.str(obj, "valign")) {
                    "top" -> AnnouncementVAlign.TOP
                    "center" -> AnnouncementVAlign.CENTER
                    "bottom" -> AnnouncementVAlign.BOTTOM
                    else -> null
                },
                arrange = when (JsonReader.str(obj, "arrange")) {
                    "start" -> AnnouncementArrange.START
                    "center" -> AnnouncementArrange.CENTER
                    "end" -> AnnouncementArrange.END
                    "spaceBetween" -> AnnouncementArrange.SPACE_BETWEEN
                    else -> null
                },
                props = props,
            ).takeIf { it.children.isNotEmpty() }
            "column" -> AnnouncementBlock.Column(parseBlocks(obj["children"], depth + 1, budget), clamp(obj["gap"], 0f, 32f), props)
                .takeIf { it.children.isNotEmpty() }
            "stack" -> AnnouncementBlock.Stack(parseBlocks(obj["children"], depth + 1, budget), props)
                .takeIf { it.children.isNotEmpty() }
            else -> null
        }
    }

    private fun parseButton(v: Any?): AnnouncementButton? {
        val obj = JsonReader.obj(v) ?: return null
        val label = parseText(obj["label"]) ?: return null
        val action = parseAction(obj["action"]) ?: return null
        return AnnouncementButton(
            label = label,
            style = AnnouncementButtonStyle.of(JsonReader.str(obj, "style")),
            action = action,
            color = parseColor(obj["color"]),
            content = parseColor(obj["content"], allowAuto = true),
            fullWidth = obj["fullWidth"] as? Boolean ?: false,
            icon = icon(obj["icon"]),
        )
    }

    private fun parseProps(obj: Map<String, Any?>): AnnouncementProps {
        val props = AnnouncementProps(
            pad = parsePad(obj["pad"]),
            align = align(obj["align"]),
            width = when (obj["width"]) {
                "fill" -> AnnouncementWidth.FILL
                "wrap" -> AnnouncementWidth.WRAP
                else -> null
            },
            weight = clamp(obj["weight"], 0.1f, 10f),
            background = parsePaint(obj["background"]),
            corner = clamp(obj["corner"], 0f, 48f),
            hideOn = when (obj["hideOn"]) {
                "phone" -> AnnouncementHideOn.PHONE
                "wide" -> AnnouncementHideOn.WIDE
                else -> null
            },
            at = when (obj["at"]) {
                "topStart" -> AnnouncementAt.TOP_START
                "top" -> AnnouncementAt.TOP
                "topEnd" -> AnnouncementAt.TOP_END
                "start" -> AnnouncementAt.START
                "center" -> AnnouncementAt.CENTER
                "end" -> AnnouncementAt.END
                "bottomStart" -> AnnouncementAt.BOTTOM_START
                "bottom" -> AnnouncementAt.BOTTOM
                "bottomEnd" -> AnnouncementAt.BOTTOM_END
                else -> null
            },
        )
        return if (props == AnnouncementProps.NONE) AnnouncementProps.NONE else props
    }

    /** A number for all four sides, or `[top, end, bottom, start]`; each clamped to 0..48. */
    internal fun parsePad(v: Any?): List<Float>? = when (v) {
        is Number -> List(4) { v.toFloat().coerceIn(0f, 48f) }
        is List<*> -> v.takeIf { it.size == 4 && it.all { n -> n is Number } }
            ?.map { (it as Number).toFloat().coerceIn(0f, 48f) }
        else -> null
    }

    internal fun parseTextStyle(obj: Map<String, Any?>): AnnouncementTextStyle {
        val style = AnnouncementTextStyle(
            size = when (val s = obj["size"]) {
                is String -> TEXT_SIZES[s]
                is Number -> s.toFloat().coerceIn(10f, 48f)
                else -> null
            },
            weight = when (obj["fontWeight"]) {
                "regular" -> AnnouncementFontWeight.REGULAR
                "medium" -> AnnouncementFontWeight.MEDIUM
                "semibold" -> AnnouncementFontWeight.SEMIBOLD
                "bold" -> AnnouncementFontWeight.BOLD
                else -> null
            },
            color = parseColor(obj["color"], allowAuto = true),
            textAlign = align(obj["textAlign"]),
            maxLines = number(obj["maxLines"])?.toInt()?.coerceIn(1, 10),
            italic = obj["italic"] as? Boolean ?: false,
        )
        return if (style == AnnouncementTextStyle.NONE) AnnouncementTextStyle.NONE else style
    }

    internal fun parseDesign(v: Any?): AnnouncementDesign? {
        val obj = JsonReader.obj(v) ?: return null
        val border = JsonReader.obj(obj["border"])?.let { b ->
            val color = parseColor(b["color"]) ?: return@let null
            AnnouncementBorder(color, clamp(b["width"], 0f, 4f) ?: 1f)
        }
        return AnnouncementDesign(
            background = parsePaint(obj["background"]),
            content = parseColor(obj["content"], allowAuto = true),
            corner = clamp(obj["corner"], 0f, 48f),
            padding = clamp(obj["padding"], 0f, 32f),
            border = border,
            minHeight = clamp(obj["minHeight"], 0f, 480f),
            fullBleed = obj["fullBleed"] as? Boolean ?: false,
            elevation = clamp(obj["elevation"], 0f, 8f),
        )
    }

    /** A colour, a gradient or an image. `auto` is not a background. */
    internal fun parsePaint(v: Any?): AnnouncementPaint? {
        if (v is String || (v is Map<*, *> && ("light" in v || "dark" in v))) {
            return parseColor(v)?.let { AnnouncementPaint.Solid(it) }
        }
        val obj = JsonReader.obj(v) ?: return null
        val image = JsonReader.str(obj, "image")
        if (image != null) {
            val src = image.trim().takeIf(::isAllowedImage) ?: return null
            return AnnouncementPaint.Image(
                src = src,
                fit = fit(obj["fit"]) ?: AnnouncementFit.COVER,
                scrim = clamp(obj["scrim"], 0f, 0.9f) ?: 0f,
                scrimColor = parseColor(obj["scrimColor"]),
            )
        }
        val colors = JsonReader.arr(obj["colors"]).mapNotNull { parseColor(it) }.take(5)
        if (colors.size < 2) return null
        return when (JsonReader.str(obj, "gradient")) {
            "linear" -> AnnouncementPaint.Linear(colors, clamp(obj["angle"], 0f, 360f) ?: 0f)
            "radial" -> {
                val center = (obj["center"] as? List<*>)?.takeIf { it.size == 2 && it.all { n -> n is Number } }
                AnnouncementPaint.Radial(
                    colors = colors,
                    centerX = (center?.get(0) as? Number)?.toFloat()?.coerceIn(0f, 1f) ?: 0.5f,
                    centerY = (center?.get(1) as? Number)?.toFloat()?.coerceIn(0f, 1f) ?: 0.5f,
                    radius = clamp(obj["radius"], 0.1f, 2f) ?: DEFAULT_RADIAL_RADIUS,
                )
            }
            else -> null
        }
    }

    /**
     * `#RRGGBB`, `#RRGGBBAA`, `{"light": hex, "dark": hex}`, a theme token, or (where [allowAuto]) `auto`.
     * Anything else is null, so the default stands.
     */
    internal fun parseColor(v: Any?, allowAuto: Boolean = false): AnnouncementColor? = when (v) {
        is String -> when {
            v == "auto" -> if (allowAuto) AnnouncementColor.Auto else null
            v in AnnouncementColor.TOKENS -> AnnouncementColor.Token(v)
            else -> hex(v)?.let { AnnouncementColor.Fixed(it, it) }
        }
        is Map<*, *> -> {
            val light = (v["light"] as? String)?.let(::hex)
            val dark = (v["dark"] as? String)?.let(::hex)
            if (light == null && dark == null) null else AnnouncementColor.Fixed(light ?: dark!!, dark ?: light!!)
        }
        else -> null
    }

    /** ARGB from `#RRGGBB` (opaque) or `#RRGGBBAA` (CSS order, alpha last); null for anything else. */
    internal fun hex(s: String): Long? {
        val t = s.trim()
        if (!t.startsWith("#")) return null
        val digits = t.substring(1)
        if (digits.length != 6 && digits.length != 8) return null
        if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val value = digits.toLong(16)
        return if (digits.length == 6) 0xFF000000L or value else ((value and 0xFFL) shl 24) or (value shr 8)
    }

    /** Null for an unknown type, a missing id, or a `url` that is not `https`. */
    internal fun parseAction(v: Any?): AnnouncementAction? {
        val obj = JsonReader.obj(v) ?: return null
        val id = JsonReader.str(obj, "id")?.trim()?.takeIf { it.isNotEmpty() }
        return when (JsonReader.str(obj, "type")) {
            "url" -> JsonReader.str(obj, "url")?.trim()?.takeIf(::isHttps)?.let { AnnouncementAction.Url(it) }
            "screen" -> id?.let { AnnouncementAction.Screen(it) }
            "store_item" -> id?.let { AnnouncementAction.StoreItem(it) }
            "settings" -> id?.let { AnnouncementAction.Settings(it) }
            "learn" -> id?.let { AnnouncementAction.Learn(it) }
            "dismiss" -> AnnouncementAction.Dismiss
            else -> null
        }
    }

    /** A plain string, or an object of language tag to string. Blank values are left out. */
    internal fun parseText(v: Any?): AnnouncementText? = when (v) {
        is String -> v.takeIf { it.isNotBlank() }?.let { AnnouncementText.plain(it) }
        is Map<*, *> -> v.entries
            .mapNotNull { (k, value) -> if (k is String && value is String && value.isNotBlank()) k to value else null }
            .takeIf { it.isNotEmpty() }
            ?.let { AnnouncementText(it.toMap()) }
        else -> null
    }

    /**
     * A `store-media` path (no scheme, no `..`, no leading slash) or an `https` URL. A path is what the admin
     * page uploads to, and is fetched through the store's media mirror rather than from a hard-coded host.
     */
    internal fun isAllowedImage(src: String): Boolean = when {
        src.isEmpty() -> false
        "://" in src || src.startsWith("//") || src.contains(':') -> isHttps(src)
        src.startsWith("/") || src.split('/').any { it == ".." || it.isEmpty() } -> false
        else -> true
    }

    internal fun isHttps(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) && url.length > "https://".length && url.none { it.isWhitespace() }

    private fun icon(v: Any?): String? = (v as? String)?.takeIf { it in AnnouncementIcons.KNOWN }

    private fun align(v: Any?): AnnouncementAlign? = when (v) {
        "start" -> AnnouncementAlign.START
        "center" -> AnnouncementAlign.CENTER
        "end" -> AnnouncementAlign.END
        else -> null
    }

    private fun fit(v: Any?): AnnouncementFit? = when (v) {
        "cover" -> AnnouncementFit.COVER
        "contain" -> AnnouncementFit.CONTAIN
        else -> null
    }

    private fun number(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { it.isFinite() }

    /** [v] as a float clamped into [lo]..[hi], or null when it is not a number. */
    private fun clamp(v: Any?, lo: Float, hi: Float): Float? = number(v)?.toFloat()?.coerceIn(lo, hi)

    private val KNOWN_BLOCKS = setOf(
        "image", "title", "text", "icon", "badge", "chips", "buttons", "divider", "spacer", "row", "column", "stack",
    )

    /** The named text sizes, in sp. */
    val TEXT_SIZES: Map<String, Float> = mapOf("xs" to 11f, "s" to 13f, "m" to 15f, "l" to 18f, "xl" to 22f, "xxl" to 28f)

    const val DEFAULT_ASPECT = 1.91f
    const val DEFAULT_RADIAL_RADIUS = 1f
    const val MAX_DEPTH = 4
    const val MAX_BLOCKS = 40
}
