package dev.ide.store.bridge

import dev.ide.platform.JsonReader
import dev.ide.platform.JsonWriter
import dev.ide.store.Announcement
import dev.ide.store.AnnouncementColor
import dev.ide.store.AnnouncementFit
import dev.ide.store.AnnouncementPaint
import dev.ide.store.AnnouncementProps
import dev.ide.store.AnnouncementTextStyle
import dev.ide.store.AnnouncementAction
import dev.ide.store.AnnouncementBlock
import dev.ide.store.AnnouncementFeed
import dev.ide.store.StoreCatalogSource
import dev.ide.store.StoreResult
import dev.ide.store.impl.AnnouncementParser
import dev.ide.store.impl.platform.StoreFs
import dev.ide.store.impl.platform.nowMillis
import dev.ide.store.impl.platform.parentPath
import dev.ide.ui.backend.UiAnnouncement
import dev.ide.ui.backend.UiAnnouncementAlign
import dev.ide.ui.backend.UiAnnouncementArrange
import dev.ide.ui.backend.UiAnnouncementAt
import dev.ide.ui.backend.UiAnnouncementBorder
import dev.ide.ui.backend.UiAnnouncementColor
import dev.ide.ui.backend.UiAnnouncementDesign
import dev.ide.ui.backend.UiAnnouncementFit
import dev.ide.ui.backend.UiAnnouncementFontWeight
import dev.ide.ui.backend.UiAnnouncementHideOn
import dev.ide.ui.backend.UiAnnouncementPaint
import dev.ide.ui.backend.UiAnnouncementProps
import dev.ide.ui.backend.UiAnnouncementTextStyle
import dev.ide.ui.backend.UiAnnouncementVAlign
import dev.ide.ui.backend.UiAnnouncementWidth
import dev.ide.ui.backend.UiAnnouncementAccent
import dev.ide.ui.backend.UiAnnouncementAction
import dev.ide.ui.backend.UiAnnouncementBlock
import dev.ide.ui.backend.UiAnnouncementButton
import dev.ide.ui.backend.UiAnnouncementButtonStyle
import dev.ide.ui.backend.UiAnnouncementStyle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Home's announcements: fetched rarely, kept on disk, and mapped for the UI.
 *
 * Home is the screen every launch lands on, so a request per visit would be a request per launch per user.
 * The rules that keep it to a few a day per device:
 *
 *  - The last answer is kept on disk with the time it was asked for. [announcements] without the network
 *    answers from that alone, so Home draws it on its first frame.
 *  - With the network allowed, the server is asked only when the kept answer is older than [freshMs], or was
 *    asked for a different language or sign-in state (the server targets by both). An attempt that could not
 *    reach the server is retried after [retryMs] rather than on the next visit; one the server refused (the
 *    function missing, say) waits the full [freshMs] like a success.
 *  - Concurrent callers share one request.
 *
 * An announcement past its `endsAt` is dropped at read time, so an expired one disappears before the next
 * refresh would have removed it.
 */
class AnnouncementCache(
    private val source: StoreCatalogSource,
    /** Where the last answer is kept, or null on a host with nowhere to write. */
    private val cachePath: () -> String?,
    /**
     * Whether the user is signed in, as far as can be told without the network: a stored session counts.
     * Read on every call, and part of what an answer is kept for.
     */
    private val signedIn: () -> Boolean,
    private val now: () -> Long = ::nowMillis,
    private val freshMs: Long = FRESH_MS,
    private val retryMs: Long = RETRY_MS,
) {
    private val lock = Mutex()

    /** What is on disk, read once and then kept in step with every write. */
    private var held: Held? = null
    private var loaded = false

    internal class Held(
        val attemptedAt: Long,
        val key: String,
        /** False when the last attempt never reached the server, which is retried sooner. */
        val reached: Boolean,
        val document: String?,
        val feed: AnnouncementFeed?,
    )

    suspend fun announcements(localeTag: String, allowNetwork: Boolean): List<UiAnnouncement> {
        if (!source.configured()) return emptyList()
        return withContext(storeIo) {
            lock.withLock {
                val key = keyFor(localeTag, signedIn())
                if (!loaded) {
                    held = load()
                    loaded = true
                }
                if (allowNetwork && shouldFetch(held, key)) held = fetch(localeTag, key, held)
                val state = held?.takeIf { it.key == key } ?: return@withLock emptyList()
                state.feed?.let { AnnouncementMapper.toUi(it, localeTag, now()) }.orEmpty()
            }
        }
    }

    internal fun shouldFetch(state: Held?, key: String): Boolean {
        if (state == null || state.key != key) return true
        val age = now() - state.attemptedAt
        // A clock that went backwards says nothing about freshness; ask again rather than never.
        if (age < 0) return true
        return age >= if (state.reached) freshMs else retryMs
    }

    private fun fetch(localeTag: String, key: String, previous: Held?): Held {
        val at = now()
        // The previous answer is kept through a failed attempt only when it was for the same audience.
        val kept = previous?.takeIf { it.key == key }
        val next = when (val r = source.homeAnnouncementsDocument(localeTag, signedIn = key.endsWith("|1"))) {
            is StoreResult.Ok -> {
                val parsed = AnnouncementParser.parse(r.value)
                if (parsed != null) {
                    Held(at, key, reached = true, document = r.value, feed = parsed)
                } else {
                    Held(at, key, reached = true, document = kept?.document, feed = kept?.feed)
                }
            }
            is StoreResult.Failed -> Held(at, key, reached = true, document = kept?.document, feed = kept?.feed)
            is StoreResult.Unavailable -> Held(at, key, reached = false, document = kept?.document, feed = kept?.feed)
        }
        write(next)
        return next
    }

    private fun load(): Held? {
        val path = cachePath() ?: return null
        if (!StoreFs.isFile(path)) return null
        val root = StoreFs.readText(path)?.let(JsonReader::parseOrNull) ?: return null
        val key = JsonReader.str(root, "key") ?: return null
        val document = JsonReader.str(root, "document")
        return Held(
            attemptedAt = JsonReader.long(root, "attemptedAt"),
            key = key,
            reached = JsonReader.bool(root, "reached", default = true),
            document = document,
            feed = document?.let(AnnouncementParser::parse),
        )
    }

    /**
     * Best effort: an answer that cannot be written is still shown. The server's document is kept as the
     * exact string it sent, so what a cold start reads is what was parsed when it arrived.
     */
    private fun write(state: Held) {
        val path = cachePath() ?: return
        val text = JsonWriter.obj(
            "v" to 1,
            "attemptedAt" to state.attemptedAt,
            "key" to state.key,
            "reached" to state.reached,
            "document" to state.document,
        )
        runCatching {
            parentPath(path)?.let { StoreFs.mkdirs(it) }
            StoreFs.writeText(path, text)
        }
    }

    companion object {
        /** How long an answer from the server is trusted before asking again. */
        const val FRESH_MS = 6L * 60 * 60 * 1000

        /** How long after an attempt that never reached the server to try again. */
        const val RETRY_MS = 30L * 60 * 1000

        /** The server targets by language and sign-in state, so an answer is kept for exactly that pair. */
        fun keyFor(localeTag: String, signedIn: Boolean): String {
            val language = localeTag.trim().replace('_', '-').substringBefore('-').lowercase()
            return "$language|${if (signedIn) 1 else 0}"
        }
    }
}

/** Maps a parsed [AnnouncementFeed] onto what Home draws, resolving every text for one language. */
object AnnouncementMapper {

    /**
     * The announcements still showing at [nowMs], in the server's order, with text for [localeTag]. One
     * whose `endsAt` cannot be read is kept: the server already filtered by time when it answered.
     */
    fun toUi(feed: AnnouncementFeed, localeTag: String, nowMs: Long): List<UiAnnouncement> =
        feed.announcements.mapNotNull { a ->
            val endsAt = parseIsoMillis(a.endsAt).takeIf { it > 0L }
            if (endsAt != null && endsAt <= nowMs) null else toUi(a, localeTag, endsAt)
        }

    private fun toUi(a: Announcement, localeTag: String, endsAt: Long?): UiAnnouncement = UiAnnouncement(
        id = a.id,
        revision = a.revision,
        priority = a.priority,
        dismissible = a.dismissible,
        endsAtMillis = endsAt,
        style = UiAnnouncementStyle.valueOf(a.layout.style.name),
        accent = UiAnnouncementAccent.valueOf(a.layout.accent.name),
        icon = a.layout.icon,
        action = a.layout.action?.let(::action),
        blocks = a.layout.blocks.map { block(it, localeTag) },
        version = a.layout.version,
        design = a.layout.design?.let { d ->
            UiAnnouncementDesign(
                background = d.background?.let(::paint),
                content = d.content?.let(::color),
                corner = d.corner,
                padding = d.padding,
                border = d.border?.let { UiAnnouncementBorder(color(it.color), it.width) },
                minHeight = d.minHeight,
                fullBleed = d.fullBleed,
                elevation = d.elevation,
            )
        },
    )

    private fun block(b: AnnouncementBlock, tag: String): UiAnnouncementBlock {
        val p = props(b.props)
        return when (b) {
            is AnnouncementBlock.Image -> UiAnnouncementBlock.Image(b.src, b.aspect, b.alt?.resolve(tag), b.height, b.fit?.let(::fit), p)
            is AnnouncementBlock.Title -> UiAnnouncementBlock.Title(b.text.resolve(tag), textStyle(b.style), p)
            is AnnouncementBlock.Text -> UiAnnouncementBlock.Text(b.text.resolve(tag), textStyle(b.style), p)
            is AnnouncementBlock.Icon -> UiAnnouncementBlock.Icon(b.name, b.size, b.color?.let(::color), p)
            is AnnouncementBlock.Badge -> UiAnnouncementBlock.Badge(b.text.resolve(tag), b.color?.let(::color), p)
            is AnnouncementBlock.Chips -> UiAnnouncementBlock.Chips(b.items.map { it.resolve(tag) }, b.color?.let(::color), p)
            is AnnouncementBlock.Buttons -> UiAnnouncementBlock.Buttons(
                items = b.items.map { button ->
                    UiAnnouncementButton(
                        label = button.label.resolve(tag),
                        style = UiAnnouncementButtonStyle.valueOf(button.style.name),
                        action = action(button.action),
                        color = button.color?.let(::color),
                        content = button.content?.let(::color),
                        fullWidth = button.fullWidth,
                        icon = button.icon,
                    )
                },
                arrange = b.arrange?.let { UiAnnouncementArrange.valueOf(it.name) },
                props = p,
            )
            is AnnouncementBlock.Divider -> UiAnnouncementBlock.Divider(b.color?.let(::color), b.thickness, p)
            is AnnouncementBlock.Spacer -> UiAnnouncementBlock.Spacer(b.size, p)
            is AnnouncementBlock.Row -> UiAnnouncementBlock.Row(
                children = b.children.map { block(it, tag) },
                gap = b.gap,
                valign = b.valign?.let { UiAnnouncementVAlign.valueOf(it.name) },
                arrange = b.arrange?.let { UiAnnouncementArrange.valueOf(it.name) },
                props = p,
            )
            is AnnouncementBlock.Column -> UiAnnouncementBlock.Column(b.children.map { block(it, tag) }, b.gap, p)
            is AnnouncementBlock.Stack -> UiAnnouncementBlock.Stack(b.children.map { block(it, tag) }, p)
        }
    }

    private fun props(p: AnnouncementProps): UiAnnouncementProps =
        if (p == AnnouncementProps.NONE) UiAnnouncementProps.NONE else UiAnnouncementProps(
            pad = p.pad,
            align = p.align?.let { UiAnnouncementAlign.valueOf(it.name) },
            width = p.width?.let { UiAnnouncementWidth.valueOf(it.name) },
            weight = p.weight,
            background = p.background?.let(::paint),
            corner = p.corner,
            hideOn = p.hideOn?.let { UiAnnouncementHideOn.valueOf(it.name) },
            at = p.at?.let { UiAnnouncementAt.valueOf(it.name) },
        )

    private fun textStyle(s: AnnouncementTextStyle): UiAnnouncementTextStyle =
        if (s == AnnouncementTextStyle.NONE) UiAnnouncementTextStyle.NONE else UiAnnouncementTextStyle(
            size = s.size,
            weight = s.weight?.let { UiAnnouncementFontWeight.valueOf(it.name) },
            color = s.color?.let(::color),
            textAlign = s.textAlign?.let { UiAnnouncementAlign.valueOf(it.name) },
            maxLines = s.maxLines,
            italic = s.italic,
        )

    private fun color(c: AnnouncementColor): UiAnnouncementColor = when (c) {
        is AnnouncementColor.Fixed -> UiAnnouncementColor.Fixed(c.light, c.dark)
        is AnnouncementColor.Token -> UiAnnouncementColor.Token(c.name)
        AnnouncementColor.Auto -> UiAnnouncementColor.Auto
    }

    private fun fit(f: AnnouncementFit): UiAnnouncementFit = UiAnnouncementFit.valueOf(f.name)

    private fun paint(p: AnnouncementPaint): UiAnnouncementPaint = when (p) {
        is AnnouncementPaint.Solid -> UiAnnouncementPaint.Solid(color(p.color))
        is AnnouncementPaint.Linear -> UiAnnouncementPaint.Linear(p.colors.map(::color), p.angle)
        is AnnouncementPaint.Radial -> UiAnnouncementPaint.Radial(p.colors.map(::color), p.centerX, p.centerY, p.radius)
        is AnnouncementPaint.Image -> UiAnnouncementPaint.Image(p.src, fit(p.fit), p.scrim, p.scrimColor?.let(::color))
    }

    private fun action(a: AnnouncementAction): UiAnnouncementAction = when (a) {
        is AnnouncementAction.Url -> UiAnnouncementAction.Url(a.url)
        is AnnouncementAction.Screen -> UiAnnouncementAction.Screen(a.id)
        is AnnouncementAction.StoreItem -> UiAnnouncementAction.StoreItem(a.id)
        is AnnouncementAction.Settings -> UiAnnouncementAction.Settings(a.id)
        is AnnouncementAction.Learn -> UiAnnouncementAction.Learn(a.id)
        AnnouncementAction.Dismiss -> UiAnnouncementAction.Dismiss
    }
}
