@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.ide.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.ide.ui.itemsKeyed
import dev.ide.ui.joinMeta
import dev.ide.ui.theme.LocalTonalCardFills
import dev.ide.ui.theme.LocalExpressiveShapeCycling
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.IdeBackend
import androidx.compose.runtime.collectAsState
import dev.ide.ui.backend.UiInstallState
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import dev.ide.ui.components.AdSlot
import dev.ide.ui.components.CodeMotifColors
import dev.ide.ui.components.Eyebrow
import dev.ide.ui.components.LinearProgressIndicator
import dev.ide.ui.components.MonoChip
import dev.ide.ui.components.PillChip
import dev.ide.ui.components.PrimaryActionButton
import dev.ide.ui.components.SupportingOnContainer
import dev.ide.ui.components.TemplateGlyph
import dev.ide.ui.components.installLabel
import dev.ide.ui.components.motifFor
import dev.ide.ui.components.pressScale
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.store_coming_soon
import dev.ide.ui.generated.resources.store_create
import dev.ide.ui.generated.resources.store_hero_download
import dev.ide.ui.generated.resources.store_hero_installs
import dev.ide.ui.generated.resources.store_install_project
import dev.ide.ui.generated.resources.store_open_in_editor
import dev.ide.ui.generated.resources.store_installing
import dev.ide.ui.generated.resources.store_item_about
import dev.ide.ui.generated.resources.store_item_details
import dev.ide.ui.generated.resources.store_item_downloads
import dev.ide.ui.generated.resources.store_item_language
import dev.ide.ui.generated.resources.store_item_reviews
import dev.ide.ui.generated.resources.store_item_size
import dev.ide.ui.generated.resources.store_item_tab_changelog
import dev.ide.ui.generated.resources.store_item_tab_overview
import dev.ide.ui.generated.resources.store_item_tab_readme
import dev.ide.ui.generated.resources.store_item_tab_reviews
import dev.ide.ui.generated.resources.store_item_type
import dev.ide.ui.generated.resources.store_item_version
import dev.ide.ui.generated.resources.store_item_whats_included
import dev.ide.ui.generated.resources.store_kind_community
import dev.ide.ui.generated.resources.store_kind_sample
import dev.ide.ui.generated.resources.store_kind_template
import dev.ide.ui.generated.resources.store_use_template
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.CaShapes
import dev.ide.ui.theme.Symbol
import dev.ide.ui.theme.TonalPair
import dev.ide.ui.theme.cardShape
import dev.ide.ui.theme.tonalPair
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.ide.ui.editor.preview.decodeImageBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.ide.ui.components.RatingSummary
import dev.ide.ui.components.ReviewCard
import dev.ide.ui.components.TranslateToggle
import dev.ide.ui.generated.resources.notif_days
import dev.ide.ui.generated.resources.notif_hours
import dev.ide.ui.generated.resources.notif_just_now
import dev.ide.ui.generated.resources.notif_minutes
import dev.ide.ui.generated.resources.reviews_edit
import dev.ide.ui.generated.resources.reviews_none
import dev.ide.ui.generated.resources.reviews_none_body
import dev.ide.ui.generated.resources.reviews_signin_to_write
import dev.ide.ui.generated.resources.reviews_sort_helpful
import dev.ide.ui.generated.resources.reviews_sort_recent
import dev.ide.ui.generated.resources.reviews_title
import dev.ide.ui.generated.resources.reviews_unavailable
import dev.ide.ui.generated.resources.reviews_loading_more
import dev.ide.ui.generated.resources.reviews_show_more
import dev.ide.ui.generated.resources.reviews_write
import dev.ide.ui.generated.resources.review_hidden_done
import dev.ide.ui.generated.resources.review_reported
import dev.ide.ui.generated.resources.profile_likes
import org.jetbrains.compose.resources.stringResource
import dev.ide.ui.backend.UiStoreUpdate
import dev.ide.ui.backend.UiLocalChanges
import androidx.compose.material3.AlertDialog
import dev.ide.ui.generated.resources.store_update_to
import dev.ide.ui.generated.resources.store_update_versions
import dev.ide.ui.generated.resources.store_update_conflict_title
import dev.ide.ui.generated.resources.store_update_conflict_body
import dev.ide.ui.generated.resources.store_update_conflict_more
import dev.ide.ui.generated.resources.store_update_conflict_unknown
import dev.ide.ui.generated.resources.store_update_conflict_keep
import dev.ide.ui.generated.resources.store_update_as_copy
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.backend.UiStoreMergeResult
import androidx.compose.material3.FilledTonalButton
import dev.ide.ui.generated.resources.store_update_merge
import dev.ide.ui.generated.resources.store_update_changed_files
import dev.ide.ui.generated.resources.store_update_merge_note
import dev.ide.ui.generated.resources.store_merge_done_title
import dev.ide.ui.generated.resources.store_merge_conflicts_body
import dev.ide.ui.generated.resources.store_merge_open
import dev.ide.ui.generated.resources.close

/**
 * The full-screen detail page for a store item, in the Material 3 Expressive language.
 *
 * Structure follows the design: a plain top bar carrying back / bookmark / share, then a **tonal hero**
 * (an inverted icon tile, the title, the publisher, and a three-up figure row) with an oversized watermark
 * bled off its corner, the install CTA beside a square rate button, and a row of **pill tabs** rather than
 * a `TabRow`.
 *
 * **Tabs appear only when their content exists.** README, Reviews and Changelog are backed by fields the
 * remote catalog fills; a bundled template has none of them, so it shows Overview alone rather than three
 * empty tabs. That is why the tab row is built from the item and not from a fixed list.
 */
@Composable
fun StoreItemScreen(
    backend: IdeBackend,
    item: UiStoreItem?,
    onBack: () -> Unit,
    onCreateFromTemplate: (templateId: String) -> Unit,
    modifier: Modifier = Modifier,
    onRate: (() -> Unit)? = null,
    onShare: ((UiStoreItem) -> Unit)? = null,
    isSaved: Boolean = false,
    onToggleSaved: (() -> Unit)? = null,
    /** Opens a URL in a browser, for signing in from the reviews tab. Null hides sign-in. */
    onOpenUrl: ((String) -> Unit)? = null,
    /** Opens the publisher's profile by handle. Null leaves the author line as plain text. */
    onOpenPublisher: ((String) -> Unit)? = null,
    /** Opens an already-installed project by its root path. Null on a host that cannot open projects. */
    onOpenInstalled: ((String) -> Unit)? = null,
) {
    if (item == null) { onBack(); return }
    val scope = rememberCoroutineScope()
    // The install's state belongs to the engine, not to this screen: leaving the page mid-download and
    // coming back has to find the same download still running, and the byte progress is only knowable
    // where the bytes are.
    val progress = backend.store.installProgress().collectAsState().value[item.id]
    val install = when (progress?.state) {
        UiInstallState.DOWNLOADING -> InstallState.Downloading(progress.fraction)
        // Unpacking has no meaningful fraction of its own, and it is brief; the bar stays full.
        UiInstallState.IMPORTING -> InstallState.Downloading(1f)
        UiInstallState.INSTALLED -> InstallState.Installed(progress.rootPath)
        else -> InstallState.Idle
    }
    var message by remember(item.id) { mutableStateOf<String?>(null) }
    var tab by remember(item.id) { mutableStateOf(StoreTab.Overview) }
    var reviewSheet by remember(item.id) { mutableStateOf(false) }
    var signInForReview by remember(item.id) { mutableStateOf(false) }
    // Bumped after a write so the panel refetches; the aggregate changes too, so re-reading is the point.
    var reviewsRefresh by remember(item.id) { mutableStateOf(0) }
    var myReview by remember(item.id) { mutableStateOf<dev.ide.ui.backend.UiStoreReview?>(null) }
    var lightbox by remember(item.id) { mutableStateOf(-1) }
    // A newer version of this project, when it is installed here and the publisher has moved on. Re-asked
    // after an update attempt (the epoch) and whenever it becomes installed.
    var updateEpoch by remember(item.id) { mutableStateOf(0) }
    val installed = install is InstallState.Installed
    val update by produceState<UiStoreUpdate?>(null, item.id, installed, updateEpoch) {
        value = if (installed) runCatching { backend.store.updateFor(item.id) }.getOrNull() else null
    }
    // The user's changes, when they stand in the way of updating in place: shows the choice.
    var conflict by remember(item.id) { mutableStateOf<UiLocalChanges?>(null) }
    // A merge that left files with both sides in them: listed so the user knows where to look.
    var merged by remember(item.id) { mutableStateOf<UiStoreMergeResult?>(null) }
    val shots = rememberResolvedScreenshots(backend, item)

    val tabs = remember(item) { availableTabs(item) }

    // Neutral, like every other surface: the hero used to hash the item id to pick one of three tints.
    val pair = tonalPair(item.id.hashCode())
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            DetailTopBar(
                title = item.title,
                isSaved = isSaved,
                onBack = onBack,
                onToggleSaved = onToggleSaved,
                onShare = onShare?.let { { it(item) } },
            )
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
                item("hero") {
                    // The app's own icon, blurred into a wash of its colours: icons are made to be colourful,
                    // and the first try (the first screenshot) went near-black on every project with a dark UI,
                    // which is most of them. A plugin's generated icon gives it a colour of its own the same way.
                    // Only a published item gets one: a bundled template's card stays tonal.
                    val icon = rememberItemIcon(backend, item)
                    val first = shots.firstOrNull { !hasSamplePreview(it) }
                    DetailHero(
                        backend, item, pair, onOpenPublisher,
                        backdrop = when {
                            icon != null -> icon
                            first != null -> { m: Modifier -> ShotImage(backend, first, ContentScale.Crop, m) }
                            else -> null
                        },
                    )
                }
                item("cta") {
                    InstallRow(
                        item = item,
                        state = install,
                        update = update,
                        onOpen = (install as? InstallState.Installed)?.rootPath?.let { path ->
                            onOpenInstalled?.let { open -> { open(path) } }
                        },
                        onRate = onRate,
                        onPrimary = {
                            when {
                                item.templateId != null -> onCreateFromTemplate(item.templateId!!)
                                // An update: in place when nothing the user did would be lost, otherwise the
                                // choice. The check is the project on disk now, not what the page last saw.
                                update != null && install is InstallState.Installed -> scope.launch {
                                    message = null
                                    val changes = runCatching { backend.store.localChanges(item.id) }.getOrNull()
                                    if (changes != null && changes.blocksInPlace) {
                                        conflict = changes
                                    } else {
                                        message = backend.store.update(item.id, asCopy = false).message
                                        updateEpoch++
                                    }
                                }
                                // Already on the device: open it. This branch used to be a literal no-op,
                                // so the button that said "Open in editor" did nothing at all.
                                install is InstallState.Installed ->
                                    install.rootPath?.let { path -> onOpenInstalled?.invoke(path) }
                                !item.available -> message = null
                                else -> scope.launch {
                                    message = null
                                    val result = runCatching { backend.store.install(item.id) }.getOrNull()
                                    message = result?.message
                                }
                            }
                        },
                    )
                }
                item("hint") {
                    val versions = update?.let {
                        stringResource(Res.string.store_update_versions, it.installedVersion ?: "?", it.availableVersion ?: "?")
                    }
                    val shown = message ?: progress?.message ?: versions
                    InstallHint(item, shown, versionSaid = shown != null && shown == versions)
                }

                if (tabs.size > 1) {
                    item("tabs") { TabRowPills(tabs, tab) { tab = it } }
                }

                when (tab) {
                    StoreTab.Overview -> overview(backend, item, shots) { lightbox = it }
                    StoreTab.Readme -> item("readme") { ProseCard(item.readme.orEmpty()) }
                    StoreTab.Changelog -> item("changelog") { ChangelogCard(item) }
                    StoreTab.Reviews -> item("reviews") {
                        ReviewsPanel(
                            backend = backend,
                            item = item,
                            onWrite = { reviewSheet = true },
                            onNeedSignIn = { signInForReview = true },
                            refresh = reviewsRefresh,
                            onPage = { myReview = it.mine },
                        )
                    }
                }

                // At the foot of whichever tab is open, rather than inside the overview builder: the
                // listing is one page with four views of it, and an ad that appears on one view and
                // vanishes on the next reads as a glitch. Below the install CTA either way, so it never
                // stands between a reader and the button they came for.
                item("ad") {
                    AdSlot(AdPlacement.STORE_ITEM, Modifier.padding(horizontal = 20.dp).padding(top = 24.dp))
                }
            }
        }
        if (lightbox >= 0) {
            ScreenshotLightbox(
                backend,
                item,
                shots,
                lightbox,
                onClose = { lightbox = -1 },
                onIndex = { lightbox = it },
            )
        }
        conflict?.let { changes ->
            UpdateConflictDialog(
                changes = changes,
                onMerge = {
                    conflict = null
                    scope.launch {
                        val result = backend.store.mergeUpdate(item.id)
                        message = result.message
                        if (result.success && result.conflicts.isNotEmpty()) merged = result
                        updateEpoch++
                    }
                },
                onCopy = {
                    conflict = null
                    scope.launch {
                        message = backend.store.update(item.id, asCopy = true).message
                        updateEpoch++
                    }
                },
                onDismiss = { conflict = null },
            )
        }
        merged?.let { result ->
            MergeConflictsDialog(
                result = result,
                onOpen = result.rootPath?.let { path -> onOpenInstalled?.let { open -> { merged = null; open(path) } } },
                onDismiss = { merged = null },
            )
        }
        if (reviewSheet) {
            ReviewSheet(
                backend = backend,
                itemId = item.id,
                existing = myReview,
                onDismiss = { reviewSheet = false },
                onDone = { reviewSheet = false; reviewsRefresh++ },
            )
        }
        if (signInForReview) {
            StoreSignInSheet(
                backend = backend,
                onDismiss = { signInForReview = false },
                // Signing in from here should land back on the review, so the sheet opens once it succeeds.
                onOpenUrl = onOpenUrl,
            )
        }
    }
}

/** Which tabs the detail screen offers, given what this item actually carries. */
private enum class StoreTab { Overview, Readme, Reviews, Changelog }

private fun availableTabs(item: UiStoreItem): List<StoreTab> = buildList {
    add(StoreTab.Overview)
    if (!item.readme.isNullOrBlank()) add(StoreTab.Readme)
    // Always offered, not only when reviews exist: an item with none needs somewhere for the first one to
    // be written, and the panel draws its own empty state.
    add(StoreTab.Reviews)
    if (!item.changelog.isNullOrBlank()) add(StoreTab.Changelog)
}

@Composable
internal fun DetailTopBar(
    title: String,
    isSaved: Boolean,
    onBack: () -> Unit,
    onToggleSaved: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(CaSymbols.arrowBack, stringResource(Res.string.back), size = 24.dp, tint = c.onSurface)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = c.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onToggleSaved != null) {
            // The bookmark fills AND gains a container when saved: the fill alone is too small a change
            // at 22 dp to read as a state at a glance.
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(if (isSaved) c.primaryContainer else Color.Transparent)
                    .clickable(onClick = onToggleSaved),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(
                    CaSymbols.bookmark,
                    contentDescription = null,
                    size = 22.dp,
                    filled = isSaved,
                    tint = if (isSaved) c.onPrimaryContainer else c.onSurface,
                )
            }
        }
        if (onShare != null) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onShare),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(CaSymbols.share, contentDescription = null, size = 21.dp, tint = c.onSurface)
            }
        }
    }
}

/**
 * The listing's header: icon, name, publisher and the figures.
 *
 * For a published project it carries the project's own colour: its icon (or, without one, its first
 * screenshot) fills the card, blurred and darkened, and everything on it is drawn in white. Every listing
 * used to open on the same neutral card, which is most of why the page read as a form.
 * The blur is unavailable before Android 12, so the scrim alone has to keep the text legible; it is set
 * dark enough to.
 *
 * Without one it is the tonal card it always was, with the item's glyph as a watermark. The icon tile is
 * **inverted** against the card (the container's `on*` role as its background) so the one element that has
 * to read as an app icon does, against a field of the same hue.
 */
@Composable
private fun DetailHero(
    backend: IdeBackend,
    item: UiStoreItem,
    tonal: TonalPair,
    onOpenPublisher: ((String) -> Unit)? = null,
    /** The screenshot behind the header, or null for the tonal card. */
    backdrop: (@Composable (Modifier) -> Unit)? = null,
) {
    // Text and figures on a darkened photograph are white whatever the theme; on the tonal card they are
    // the card's own on-colour. One pair, so no element can use the wrong half of it.
    val pair = if (backdrop != null) TonalPair(Color.Black, Color.White) else tonal
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = if (backdrop != null) CodeMotifColors.Chrome else pair.container,
        contentColor = pair.onContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Box(Modifier.clipToBounds()) {
            if (backdrop != null) {
                // Scaled past the card's edges before the blur, so the blur has colour to spread at the
                // rim instead of fading into the card's own background there.
                backdrop(Modifier.matchParentSize().graphicsLayer { scaleX = 1.6f; scaleY = 1.6f }.blur(36.dp))
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.30f),
                            1f to Color.Black.copy(alpha = 0.72f),
                        ),
                    ),
                )
            } else {
                Symbol(
                    glyph = CaSymbols.forIconId(item.iconId),
                    contentDescription = null,
                    size = 170.dp,
                    tint = pair.onContainer.copy(alpha = 0.13f),
                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = 22.dp, y = 34.dp),
                )
            }
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = if (backdrop != null) 28.dp else 20.dp, bottom = 20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val appIcon = rememberItemIcon(backend, item)
                    Box(
                        Modifier.size(64.dp)
                            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 8.dp, bottomEnd = 22.dp, bottomStart = 8.dp))
                            // A real app icon brings its own ground; the inverted plate is for the glyph.
                            .then(if (appIcon != null) Modifier else Modifier.background(pair.onContainer)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (appIcon != null) {
                            appIcon(Modifier.size(64.dp))
                        } else {
                            // The template's OWN mark, forced to the tile's colour: the inverted tile is
                            // already carrying the identity, and a brand-coloured glyph on it would clash.
                            TemplateGlyph(
                                iconId = item.iconId,
                                size = 32.dp,
                                fallbackTint = pair.container,
                                forceTint = pair.container,
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = pair.onContainer,
                        )
                        if (item.author != null) {
                            // Tappable only when there is a handle to open: a bundled item's "author" is
                            // CodeAssist itself, which has no profile, and a dead tap target is worse than
                            // plain text.
                            val handle = item.authorHandle
                            Row(
                                Modifier.padding(top = 6.dp)
                                    .then(
                                        if (handle != null && onOpenPublisher != null) {
                                            Modifier.clickable { onOpenPublisher(handle) }
                                        } else {
                                            Modifier
                                        },
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Text(
                                    item.author!!,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = pair.onContainer.copy(alpha = 0.9f),
                                )
                                if (item.verified) {
                                    Symbol(CaSymbols.verified, contentDescription = null, size = 15.dp, tint = pair.onContainer)
                                }
                                if (handle != null && onOpenPublisher != null) {
                                    Symbol(
                                        CaSymbols.chevronRight,
                                        contentDescription = null,
                                        size = 14.dp,
                                        tint = pair.onContainer.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }
                    }
                }
                // A bundled item has no rating, installs or payload size, so the figure row is skipped
                // entirely rather than left as an empty band of padding under the title.
                val hasStats = item.rating >= 0f || item.installs >= 0 || item.downloadBytes > 0 ||
                    item.likes >= 0
                if (hasStats) Row(Modifier.fillMaxWidth().padding(top = 18.dp)) {
                    if (item.rating >= 0f) {
                        HeroStat(
                            value = formatRating(item.rating),
                            label = stringResource(Res.string.store_item_reviews, item.ratingCount),
                            pair = pair,
                            trailingGlyph = CaSymbols.star,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (item.likes > 0) {
                        HeroStat(
                            value = compactCount(item.likes),
                            label = stringResource(Res.string.profile_likes),
                            pair = pair,
                            trailingGlyph = CaSymbols.bookmark,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (item.installs >= 0) {
                        HeroStat(
                            value = compactCount(item.installs),
                            label = stringResource(Res.string.store_hero_installs),
                            pair = pair,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (item.downloadBytes > 0) {
                        HeroStat(
                            value = formatSize(item.downloadBytes),
                            label = stringResource(Res.string.store_hero_download),
                            pair = pair,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroStat(
    value: String,
    label: String,
    pair: TonalPair,
    modifier: Modifier = Modifier,
    trailingGlyph: Char? = null,
) = Column(modifier) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
            color = pair.onContainer,
        )
        if (trailingGlyph != null) {
            Symbol(trailingGlyph, contentDescription = null, size = 15.dp, filled = true, tint = pair.onContainer)
        }
    }
    SupportingOnContainer(label, pair.onContainer, Modifier.padding(top = 1.dp))
}

/**
 * The user has changed the installed project, so replacing its files would throw their work away.
 *
 * Three ways on, safest-for-their-work first. **Update and keep my changes** merges the new version into
 * their copy (offered when the installed version's files were kept, which is every install from this
 * version on). **Install as a new copy** leaves their copy exactly as it is. **Cancel** does nothing. The
 * changed files are named, up to a handful, so "you changed this project" is something they can check.
 */
@Composable
private fun UpdateConflictDialog(
    changes: UiLocalChanges,
    onMerge: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val touched = changes.modified + changes.deleted
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Symbol(CaSymbols.update, contentDescription = null, size = 24.dp) },
        title = { Text(stringResource(Res.string.store_update_conflict_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!changes.known) {
                    Text(stringResource(Res.string.store_update_conflict_unknown))
                } else {
                    Text(
                        stringResource(
                            if (changes.canMerge) Res.string.store_update_changed_files else Res.string.store_update_conflict_body,
                        ),
                    )
                    ChangedFileList(touched)
                }
                Text(
                    stringResource(if (changes.canMerge) Res.string.store_update_merge_note else Res.string.store_update_conflict_keep),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        // Stacked, not side by side: three labels this long do not fit one row on a phone.
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (changes.canMerge) {
                    FilledTonalButton(onClick = onMerge) { Text(stringResource(Res.string.store_update_merge)) }
                }
                TextButton(onClick = onCopy) { Text(stringResource(Res.string.store_update_as_copy)) }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
            }
        },
    )
}

/** After a merge: the files where both sides changed the same lines, which now hold both versions. */
@Composable
private fun MergeConflictsDialog(result: UiStoreMergeResult, onOpen: (() -> Unit)?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Symbol(CaSymbols.update, contentDescription = null, size = 24.dp) },
        title = { Text(stringResource(Res.string.store_merge_done_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.store_merge_conflicts_body))
                ChangedFileList(result.conflicts)
            }
        },
        confirmButton = {
            if (onOpen != null) {
                TextButton(onClick = onOpen) { Text(stringResource(Res.string.store_merge_open)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) }
            }
        },
        dismissButton = if (onOpen != null) {
            { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) } }
        } else {
            null
        },
    )
}

/** Up to a handful of project paths, in the code face, cut from the middle so the file name stays visible. */
@Composable
private fun ChangedFileList(paths: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        paths.take(CONFLICT_LIST_MAX).forEach { path ->
            Text(
                compactPath(path),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = dev.ide.ui.theme.Ca.type.codeFamily),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (paths.size > CONFLICT_LIST_MAX) {
            Text(
                stringResource(Res.string.store_update_conflict_more, paths.size - CONFLICT_LIST_MAX),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * A project path short enough for one line of a dialog, keeping the end: `…/utils/Helper.kt` rather than
 * `plugin/src/main/kotlin/com/…`. Done here rather than with a middle ellipsis, which not every platform's
 * text layout honours: desktop cut the end anyway, hiding the file name, the one part that says what changed.
 */
internal fun compactPath(path: String, max: Int = 34): String {
    if (path.length <= max) return path
    val parts = path.split('/')
    for (keep in (parts.size - 1) downTo 1) {
        val tail = parts.takeLast(keep).joinToString("/")
        if (tail.length + 2 <= max || keep == 1) return "…/$tail"
    }
    return path
}

/** How many changed files the conflict dialog names before "and N more". */
private const val CONFLICT_LIST_MAX = 6

/** The install state machine, as the design draws it: the button's shape changes with the state. */
private sealed interface InstallState {
    data object Idle : InstallState
    data class Downloading(val progress: Float) : InstallState
    /** [rootPath] is where the project landed, which is what the button's "Open" opens. */
    data class Installed(val rootPath: String?) : InstallState
}

@Composable
private fun InstallRow(
    item: UiStoreItem,
    state: InstallState,
    onRate: (() -> Unit)?,
    onPrimary: () -> Unit,
    /** A newer version, when there is one: the primary action becomes the update. */
    update: UiStoreUpdate? = null,
    /** Opens the installed project. Shown beside the button only while the button is the update. */
    onOpen: (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    val busy = state is InstallState.Downloading
    val label = when {
        item.templateId != null -> stringResource(Res.string.store_create)
        !item.available -> stringResource(Res.string.store_coming_soon)
        busy -> stringResource(Res.string.store_installing)
        state is InstallState.Installed && update != null ->
            stringResource(Res.string.store_update_to, update.availableVersion ?: "")
        state is InstallState.Installed -> stringResource(Res.string.store_open_in_editor)
        else -> stringResource(Res.string.store_install_project)
    }
    val glyph = when {
        item.templateId != null -> CaSymbols.add
        busy -> CaSymbols.progressActivity
        state is InstallState.Installed && update != null -> CaSymbols.update
        state is InstallState.Installed -> CaSymbols.playArrow
        else -> CaSymbols.download
    }
    // One pill in every state. The three diagonal-notch silhouettes this used to morph between were a
    // shape animation carrying information the label and colour already carry, and a notched install
    // button is the odd one out next to the pill actions on every row of the feed.
    val targetShape = RoundedCornerShape(percent = 50)
    val enabled = item.available || item.templateId != null

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val interaction = remember { MutableInteractionSource() }
        Surface(
            onClick = onPrimary,
            enabled = enabled,
            shape = targetShape,
            color = if (busy) c.secondaryContainer else c.primary,
            contentColor = if (busy) c.onSecondaryContainer else c.onPrimary,
            interactionSource = interaction,
            modifier = Modifier.weight(1f).height(56.dp).pressScale(interaction, pressed = 0.985f),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                SpinningSymbol(glyph, spinning = busy)
                Spacer(Modifier.width(9.dp))
                Text(label, style = MaterialTheme.typography.titleSmall)
            }
        }
        if (update != null && onOpen != null && !busy) {
            Surface(
                onClick = onOpen,
                shape = RoundedCornerShape(percent = 50),
                color = c.secondaryContainer,
                contentColor = c.onSecondaryContainer,
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Symbol(CaSymbols.playArrow, contentDescription = stringResource(Res.string.store_open_in_editor), size = 22.dp)
                }
            }
        }
        if (onRate != null) {
            val rateInteraction = remember { MutableInteractionSource() }
            Surface(
                onClick = onRate,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 28.dp, bottomEnd = 16.dp, bottomStart = 28.dp),
                color = c.secondaryContainer,
                contentColor = c.onSecondaryContainer,
                interactionSource = rateInteraction,
                modifier = Modifier.size(56.dp).pressScale(rateInteraction, pressed = 0.96f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Symbol(CaSymbols.rateReview, contentDescription = null, size = 22.dp)
                }
            }
        }
    }
}

/** The install glyph, rotating while a download is in flight. */
@Composable
private fun SpinningSymbol(glyph: Char, spinning: Boolean) {
    if (!spinning) {
        Symbol(glyph, contentDescription = null, size = 21.dp)
        return
    }
    val transition = rememberInfiniteTransition(label = "installSpin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "installSpinAngle",
    )
    Symbol(glyph, contentDescription = null, size = 21.dp, modifier = Modifier.rotate(angle))
}

/** The one-line requirements note under the CTA — the only centred prose on the screen. */
@Composable
private fun InstallHint(item: UiStoreItem, message: String?, versionSaid: Boolean = false) {
    val parts = listOfNotNull(
        message,
        // Left out when the message already names the versions, as an update's does.
        item.version?.takeIf { !versionSaid }?.let { "v$it" },
        item.language,
        item.downloadBytes.takeIf { it > 0 }?.let { formatSize(it) },
    )
    if (parts.isEmpty()) return
    Text(
        parts.joinMeta(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 9.dp),
    )
}

/** Scrollable pill chips, deliberately not a `TabRow`. */
@Composable
private fun TabRowPills(tabs: List<StoreTab>, selected: StoreTab, onSelect: (StoreTab) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsKeyed(tabs, key = { it.name }) { t ->
            PillChip(
                label = stringResource(
                    when (t) {
                        StoreTab.Overview -> Res.string.store_item_tab_overview
                        StoreTab.Readme -> Res.string.store_item_tab_readme
                        StoreTab.Reviews -> Res.string.store_item_tab_reviews
                        StoreTab.Changelog -> Res.string.store_item_tab_changelog
                    },
                ),
                selected = t == selected,
                onClick = { onSelect(t) },
                height = 38.dp,
            )
        }
    }
}

// ---- Overview tab ----

/**
 * The Overview shelf: screenshots, about, tech-stack chips, the spec table, and the ratings panel.
 *
 * Emitted as [LazyColumn] items rather than one composable so a long description does not force the
 * whole tab to compose before the first pixel.
 */
private fun LazyListScope.overview(
    backend: IdeBackend,
    item: UiStoreItem,
    shots: List<String>,
    onOpenShot: (Int) -> Unit,
) {
    // Only offered when the item actually has artwork; see ScreenshotCarousel.
    if (item.screenshots.isNotEmpty() || item.previewKey != null) {
        item("shots") { ScreenshotCarousel(backend, item, onOpenShot, shots) }
    }
    item("about") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 22.dp)) {
            Eyebrow(stringResource(Res.string.store_item_about))
            // The translation lives as long as this listing is open: flipping back and forth is free, and
            // reopening the listing asks again (the server answers that from its cache).
            val translation = remember(item.id) { TranslationState() }
            val target = remember { translationTarget() }
            val scope = rememberCoroutineScope()
            // Markdown rather than a plain Text: this is publisher-written prose. See ListingProse. A
            // translation keeps the Markdown, so it is drawn the same way.
            ListingProse(translation.shown("description") ?: item.description, Modifier.padding(top = 8.dp))
            if (backend.store.translationAvailable() && offerTranslation(item.description, target)) {
                TranslateToggle(
                    showingTranslation = translation.showing,
                    busy = translation.busy,
                    error = translation.error,
                    onToggle = {
                        scope.launch { translation.toggle { backend.store.translateListing(item.id, target) } }
                    },
                )
            }
        }
    }
    if (item.tags.isNotEmpty()) {
        item("stack") {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.tags.forEach { MonoChip(it, container = MaterialTheme.colorScheme.surfaceContainer) }
            }
        }
    }
    if (item.highlights.isNotEmpty()) {
        item("highlights") { Highlights(item) }
    }
    item("specs") { SpecTable(item) }
    if (item.rating >= 0f && item.ratingCount > 0) {
        item("ratings") { RatingsPanel(backend, item) }
    }
}

/**
 * The screenshot strip.
 *
 * Real screenshots are not available for a bundled template, so each card draws the same abstract code
 * panel the featured hero uses — a deliberate stand-in rather than invented source. The dark editor
 * chrome is fixed and does not follow the theme, because code only reads as code against it.
 */
/**
 * The screenshot strip.
 *
 * A real bundled screenshot wins wherever one exists (the sample games ship PNGs). Everything else draws
 * the abstract code panel — a visible stand-in, not invented source. That is why the strip is only shown
 * at all for an item that has *some* artwork: a shelf of pure placeholders would read as "these projects
 * have no screenshots", which is exactly what it would be saying.
 */
@Composable
private fun ScreenshotCarousel(
    backend: IdeBackend,
    item: UiStoreItem,
    onOpen: (Int) -> Unit,
    resolved: List<String>,
) {
    val c = MaterialTheme.colorScheme
    val real = resolved
    val shots: List<String?> = when {
        real.isNotEmpty() -> real
        hasSamplePreview(item.previewKey) -> listOf(item.previewKey!!)
        // Remote shots that have not been fetched yet: hold their places so the strip does not change
        // length under the reader when the images land.
        item.screenshots.isNotEmpty() -> List(item.screenshots.size) { null }
        else -> listOf(null, null)  // motif stand-ins
    }
    val listState = rememberLazyListState()
    LazyRow(
        state = listState,
        flingBehavior = rememberSnapFlingBehavior(listState),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
    ) {
        itemsIndexed(shots) { i, key ->
            // Each tile takes its image's own shape at a fixed height. These used to be landscape 218 x 154
            // tiles, which cropped a portrait phone screenshot (nearly every screenshot the store has) down to a
            // strip of its middle. Until an image's size is known, a tile is phone-shaped.
            val bitmap = if (key != null && !hasSamplePreview(key)) rememberShotBitmap(backend, key) else null
            val aspect = bitmap?.let { (it.width.toFloat() / it.height).coerceIn(0.42f, 1.9f) } ?: PHONE_ASPECT
            Box(
                Modifier.height(SHOT_TILE_HEIGHT).width(SHOT_TILE_HEIGHT * aspect)
                    .clip(RoundedCornerShape(20.dp))
                    .background(CodeMotifColors.Chrome)
                    .clickable { onOpen(i) },
            ) {
                when {
                    key == null -> CodePanel(item, i)
                    hasSamplePreview(key) -> SamplePreview(key, Modifier.fillMaxSize())
                    bitmap != null -> androidx.compose.foundation.Image(
                        bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(9.dp)
                        .size(28.dp).clip(CircleShape).background(c.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Symbol(CaSymbols.openInFull, contentDescription = null, size = 16.dp, tint = c.onPrimaryContainer)
                }
            }
        }
    }
}

/** How tall a screenshot is in the listing's strip. Tall enough to see a phone screen as one. */
private val SHOT_TILE_HEIGHT = 300.dp

/** A phone screenshot's width over its height, used until a tile knows its image's real shape. */
private const val PHONE_ASPECT = 0.48f

/** The decoded image behind a cached screenshot file, or null until it lands (and when it cannot be read). */
@Composable
private fun rememberShotBitmap(backend: IdeBackend, path: String): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        val bytes = backend.projects.imageBytes(path)
        value = bytes?.let { withContext(Dispatchers.Default) { decodeImageBytes(it) } }
    }
    return bitmap
}

/** The abstract editor stand-in: chrome bar, file-tree gutter, syntax-coloured bars. */
@Composable
internal fun CodePanel(item: UiStoreItem, index: Int) {
    Column {
        Row(
            Modifier.fillMaxWidth().height(24.dp).background(CodeMotifColors.ChromeBar)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(2) { Box(Modifier.size(7.dp).clip(CircleShape).background(CodeMotifColors.Dot)) }
            Text(
                fileNameFor(item, index),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = dev.ide.ui.theme.Ca.type.codeFamily,
                    fontWeight = FontWeight.Normal,
                ),
                color = CodeMotifColors.FileName,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Row(Modifier.fillMaxWidth().height(130.dp)) {
            Column(
                Modifier.width(46.dp).background(CodeMotifColors.Gutter)
                    .padding(horizontal = 7.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                motifFor("${item.id}-$index-tree", lines = 4).forEach { l ->
                    Box(
                        Modifier.fillMaxWidth(l.widthFraction).height(5.dp)
                            .clip(CircleShape).background(CodeMotifColors.TreeBar),
                    )
                }
            }
            Column(
                Modifier.weight(1f).padding(horizontal = 11.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                motifFor("${item.id}-$index", lines = 8).forEach { l ->
                    Box(
                        Modifier.padding(start = l.indent).fillMaxWidth(l.widthFraction)
                            .height(5.dp).clip(CircleShape).background(l.color),
                    )
                }
            }
        }
    }
}

@Composable
private fun Highlights(item: UiStoreItem) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 22.dp)) {
        Eyebrow(stringResource(Res.string.store_item_whats_included))
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item.highlights.forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Symbol(CaSymbols.checkCircle, contentDescription = null, size = 18.dp, filled = true, tint = c.primary)
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                }
            }
        }
    }
}

/** The compatibility table: one divided row per fact, only for facts this item actually carries. */
@Composable
private fun SpecTable(item: UiStoreItem) {
    val c = MaterialTheme.colorScheme
    // Only what the hero has NOT already said. Rating, installs and download size are the hero's three
    // figures, and the line under the install button repeats the version and language on top of that, so
    // this table was the third telling of the same five numbers.
    val rows = buildList {
        add(Triple(CaSymbols.extension, stringResource(Res.string.store_item_type), kindLabel(item.kind)))
        item.version?.let { add(Triple(CaSymbols.update, stringResource(Res.string.store_item_version), "v$it")) }
    }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = c.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 22.dp),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
            rows.forEachIndexed { i, (glyph, key, value) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Symbol(glyph, contentDescription = null, size = 20.dp, tint = c.onSurfaceVariant)
                    Text(key, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = c.onSurface,
                        textAlign = TextAlign.End,
                    )
                }
                if (i < rows.lastIndex) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.outlineVariant))
                }
            }
        }
    }
}

/**
 * The ratings block: a big light average on a filled panel, beside the star histogram.
 *
 * The bars are the real per-star counts, from the same reviews call the Ratings tab makes (one row asked
 * for: the distribution comes with every page). They used to be a shape drawn around the average, which
 * showed 4- and 3-star ratings on a project whose only review gave it 5. Until the counts arrive, or when
 * they cannot be fetched, the tracks stay empty: an empty bar is unknown, a drawn one is a claim.
 *
 * Bars are proportional to the most common rating, as [dev.ide.ui.components.RatingHistogram] draws them.
 */
@Composable
private fun RatingsPanel(backend: IdeBackend, item: UiStoreItem) {
    val c = MaterialTheme.colorScheme
    if (item.rating < 0f || item.ratingCount <= 0) return
    val distribution by produceState<Map<Int, Int>>(emptyMap(), item.id) {
        if (!backend.store.reviewsAvailable()) return@produceState
        value = runCatching { backend.store.reviews(item.id, limit = 1).distribution }.getOrDefault(emptyMap())
    }
    val peak = (distribution.values.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 26.dp)) {
        Text(
            stringResource(Res.string.store_item_tab_reviews),
            style = MaterialTheme.typography.headlineSmall,
            color = c.onSurface,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Surface(shape = RoundedCornerShape(26.dp), color = c.primaryContainer, contentColor = c.onPrimaryContainer) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        formatRating(item.rating),
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = 46.sp, lineHeight = 48.sp),
                        color = c.onPrimaryContainer,
                    )
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        repeat(5) { i ->
                            Symbol(
                                CaSymbols.star,
                                contentDescription = null,
                                size = 13.dp,
                                filled = i < item.rating.toInt(),
                                tint = c.onPrimaryContainer,
                            )
                        }
                    }
                    SupportingOnContainer(
                        item.ratingCount.toString(),
                        c.onPrimaryContainer,
                        Modifier.padding(top = 5.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                (5 downTo 1).forEach { star ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            star.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = c.onSurfaceVariant,
                            modifier = Modifier.width(8.dp),
                        )
                        Box(Modifier.weight(1f).height(9.dp).clip(CircleShape).background(c.surfaceContainerHighest)) {
                            val n = distribution[star] ?: 0
                            if (n > 0) {
                                Box(
                                    Modifier.fillMaxWidth(n.toFloat() / peak)
                                        .height(9.dp).clip(CircleShape).background(c.primary),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A README rendered on a tonal card. Markdown, since a README is a `.md` file wherever it came from. */
@Composable
private fun ProseCard(text: String) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = c.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 18.dp),
    ) {
        ListingProse(
            text,
            Modifier.padding(20.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = c.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChangelogCard(item: UiStoreItem) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(11.dp).clip(RoundedCornerShape(3.dp)).background(c.primary))
            Text(
                "v${item.version.orEmpty()}",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = c.onSurface,
            )
        }
        Text(
            item.changelog.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = c.onSurfaceVariant,
            modifier = Modifier.padding(start = 19.dp, top = 8.dp),
        )
    }
}

// ---- formatting ----

@Composable
private fun kindLabel(kind: UiStoreItemKind): String = stringResource(
    when (kind) {
        UiStoreItemKind.Template -> Res.string.store_kind_template
        UiStoreItemKind.Sample -> Res.string.store_kind_sample
        UiStoreItemKind.Community -> Res.string.store_kind_community
    },
)

private fun formatRating(rating: Float): String {
    val tenths = (rating.coerceIn(0f, 5f) * 10).toInt()
    return "${tenths / 10}.${tenths % 10}"
}

internal fun compactCount(n: Int): String = dev.ide.ui.components.compactNumber(n)

private fun formatSize(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    if (mb >= 1.0) {
        val tenths = (mb * 10).toLong()
        return "${tenths / 10}.${tenths % 10} MB"
    }
    return "${(bytes / 1024).coerceAtLeast(1)} KB"
}

private fun fileNameFor(item: UiStoreItem, index: Int): String {
    val ext = if (item.language.equals("Java", ignoreCase = true)) "java" else "kt"
    return listOf("App.$ext", "build.gradle.kts", "Main.$ext", "README.md")[index % 4]
}

/**
 * The reviews tab.
 *
 * One fetch fills everything: the average, the distribution, the reader's own review pinned on top, and the
 * rest of the list. Writing is gated on a session, and the gate is checked here rather than in the sheet so
 * the button can say "sign in" instead of opening a form that will be refused.
 */
@Composable
private fun ReviewsPanel(
    backend: IdeBackend,
    item: UiStoreItem,
    onWrite: () -> Unit,
    onNeedSignIn: () -> Unit,
    refresh: Int,
    onPage: (dev.ide.ui.backend.UiReviewPage) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var sort by remember(item.id) { mutableStateOf(dev.ide.ui.backend.UiReviewSort.HELPFUL) }
    // Bumped when a vote, report, reply or hide lands, so the list refetches rather than guessing.
    var voteEpoch by remember(item.id) { mutableStateOf(0) }
    var reportTarget by remember(item.id) { mutableStateOf<dev.ide.ui.backend.UiStoreReview?>(null) }
    var replyTarget by remember(item.id) { mutableStateOf<dev.ide.ui.backend.UiStoreReview?>(null) }
    // What just happened, said once. Not an error, so it does not belong in the page's error slot.
    var actionNote by remember(item.id) { mutableStateOf<String?>(null) }
    val signedIn = backend.store.authState().collectAsState().value.signedIn
    val scope = rememberCoroutineScope()
    val now = remember(refresh) { dev.ide.ui.platform.nowMillis() }
    // Per review, by author (one review per author per project). Kept across re-sorts and refetches: the
    // same review keeps its translation however the list around it moves.
    val translations = remember(item.id) { mutableStateMapOf<String, TranslationState>() }
    val translateTarget = remember { translationTarget() }
    val translatable = backend.store.translationAvailable()
    val reportedNote = stringResource(Res.string.review_reported)
    val hiddenNote = stringResource(Res.string.review_hidden_done)

    if (!backend.store.reviewsAvailable()) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                stringResource(Res.string.reviews_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurfaceVariant,
            )
        }
        return
    }

    // Held rather than produced, because the panel appends to it: a second page keeps the reviews already
    // read and replaces everything that describes the item as a whole (the average, the distribution).
    var page by remember(item.id, sort, refresh, voteEpoch) {
        mutableStateOf(dev.ide.ui.backend.UiReviewPage(loading = true))
    }
    var loadingMore by remember(item.id, sort, refresh, voteEpoch) { mutableStateOf(false) }
    LaunchedEffect(item.id, sort, refresh, voteEpoch, backend) {
        page = dev.ide.ui.backend.UiReviewPage(loading = true)
        val fetched = runCatching { backend.store.reviews(item.id, sort) }.getOrNull()
            ?: dev.ide.ui.backend.UiReviewPage(error = "Could not load reviews")
        page = fetched
        onPage(fetched)
    }
    val current = page

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 24.dp)) {
        Text(
            stringResource(Res.string.reviews_title),
            style = MaterialTheme.typography.headlineSmall,
            color = c.onSurface,
        )

        if (current.loading) {
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        current.error?.let {
            Spacer(Modifier.height(10.dp))
            // The backend's own words: offline reads differently from refused, and the reader can act on it.
            Text(it, style = MaterialTheme.typography.bodyMedium, color = c.error)
        }

        if (current.hasAny) {
            Spacer(Modifier.height(16.dp))
            RatingSummary(current.average, current.count, current.distribution)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillChip(
                    label = stringResource(Res.string.reviews_sort_helpful),
                    selected = sort == dev.ide.ui.backend.UiReviewSort.HELPFUL,
                    onClick = { sort = dev.ide.ui.backend.UiReviewSort.HELPFUL },
                )
                PillChip(
                    label = stringResource(Res.string.reviews_sort_recent),
                    selected = sort == dev.ide.ui.backend.UiReviewSort.RECENT,
                    onClick = { sort = dev.ide.ui.backend.UiReviewSort.RECENT },
                )
            }
        } else if (current.error == null) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(Res.string.reviews_none),
                style = MaterialTheme.typography.titleMedium,
                color = c.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(Res.string.reviews_none_body),
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
        PrimaryActionButton(
            label = stringResource(
                when {
                    !signedIn -> Res.string.reviews_signin_to_write
                    current.mine != null -> Res.string.reviews_edit
                    else -> Res.string.reviews_write
                },
            ),
            glyph = CaSymbols.rateReview,
            onClick = { if (signedIn) onWrite() else onNeedSignIn() },
            modifier = Modifier.fillMaxWidth(),
        )

        // The reader's own review first, then everyone else's.
        current.mine?.let { mine ->
            Spacer(Modifier.height(16.dp))
            ReviewCard(mine, relativeTime = relativeAge(mine.postedAtMs, now), onVote = null)
        }
        current.reviews.forEach { review ->
            Spacer(Modifier.height(12.dp))
            val translation = translations.getOrPut(review.authorId) { TranslationState() }
            val canTranslate = translatable &&
                (offerTranslation(review.review, translateTarget) || offerTranslation(review.reply, translateTarget))
            ReviewCard(
                review = review,
                relativeTime = relativeAge(review.postedAtMs, now),
                reviewText = translation.shown("review") ?: review.review,
                replyText = translation.shown("reply") ?: review.reply,
                translate = if (canTranslate) {
                    {
                        TranslateToggle(
                            showingTranslation = translation.showing,
                            busy = translation.busy,
                            error = translation.error,
                            onToggle = {
                                scope.launch {
                                    translation.toggle {
                                        backend.store.translateReview(item.id, review.authorId, translateTarget)
                                    }
                                }
                            },
                        )
                    }
                } else {
                    null
                },
                onVote = if (signedIn) {
                    { helpful ->
                        scope.launch {
                            runCatching { backend.store.voteReview(item.id, review.authorId, helpful) }
                            // Refetch rather than flipping locally: the count is the interesting part and
                            // only the server knows it, so guessing would show a number that could be wrong.
                            voteEpoch++
                        }
                        Unit
                    }
                } else {
                    { onNeedSignIn() }
                },
                // Signed out, the backend refuses a report, so the action asks for sign-in instead of
                // offering something that cannot work.
                onReport = if (signedIn) ({ reportTarget = review }) else ({ onNeedSignIn() }),
                onReply = if (current.canReply) ({ replyTarget = review }) else null,
                onDeleteReply = if (current.canReply && review.reply != null) {
                    {
                        scope.launch {
                            backend.store.deleteReply(item.id, review.authorId)
                            voteEpoch++
                        }
                        Unit
                    }
                } else {
                    null
                },
                onSetHidden = if (current.canModerate) {
                    { hidden ->
                        scope.launch {
                            val error = backend.store.setReviewHidden(item.id, review.authorId, hidden)
                            actionNote = error ?: hiddenNote
                            voteEpoch++
                        }
                        Unit
                    }
                } else {
                    null
                },
            )
        }
        // A tap rather than an endless scroll: this panel is the foot of a page the reader came to for
        // the project, and a list that grows as they try to leave it is the wrong shape here.
        if (current.hasMore || loadingMore) {
            Spacer(Modifier.height(12.dp))
            TextButton(
                enabled = !loadingMore,
                onClick = {
                    scope.launch {
                        loadingMore = true
                        val next = runCatching {
                            backend.store.reviews(item.id, sort, offset = current.reviews.size)
                        }.getOrNull()
                        if (next != null) {
                            page = next.copy(
                                // Deduplicated by author, who may review a project only once: a review
                                // posted between the two requests would otherwise shift the page and
                                // arrive twice.
                                reviews = (current.reviews + next.reviews).distinctBy { it.authorId },
                            )
                        }
                        loadingMore = false
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (loadingMore) Res.string.reviews_loading_more else Res.string.reviews_show_more,
                    ),
                )
            }
        }
        actionNote?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        Spacer(Modifier.height(28.dp))
    }

    reportTarget?.let { target ->
        ReportReviewSheet(
            backend = backend,
            itemId = item.id,
            authorId = target.authorId,
            onDismiss = { reportTarget = null },
            onReported = { reportTarget = null; actionNote = reportedNote },
        )
    }
    replyTarget?.let { target ->
        ReplyToReviewSheet(
            backend = backend,
            itemId = item.id,
            authorId = target.authorId,
            existing = target.reply,
            onDismiss = { replyTarget = null },
            onReplied = { replyTarget = null; voteEpoch++ },
        )
    }
}

/** "3 d ago" style age, or null when the backend sent no usable timestamp. */
@Composable
private fun relativeAge(postedAtMs: Long, now: Long): String? {
    if (postedAtMs <= 0L) return null
    val minutes = ((now - postedAtMs).coerceAtLeast(0)) / 60_000
    return when {
        minutes < 1 -> stringResource(Res.string.notif_just_now)
        minutes < 60 -> stringResource(Res.string.notif_minutes, minutes.toInt())
        minutes < 60 * 24 -> stringResource(Res.string.notif_hours, (minutes / 60).toInt())
        else -> stringResource(Res.string.notif_days, (minutes / (60 * 24)).toInt())
    }
}

/**
 * The screenshots this item can actually draw, as either a built-in preview key or a local file path.
 *
 * A published project's screenshots arrive as storage paths, and both galleries decode files rather than
 * URLs, so each remote path is fetched once and cached (see `StoreService.screenshotFile`). Anything that
 * fails to fetch is dropped rather than left as a gap: the strip shows what it has.
 *
 * Fetched **concurrently**, and in their published order: a listing may carry six, and one after another
 * meant six round trips end to end before the strip had anything in it. The engine caps how many media
 * downloads actually run at once, so asking for all of them here cannot flood the connection.
 */
@Composable
private fun rememberResolvedScreenshots(backend: IdeBackend, item: UiStoreItem): List<String> {
    val resolved by produceState(emptyList<String>(), item.id, backend) {
        // Built-ins are already renderable and need no round trip.
        val builtIn = item.screenshots.filter { hasSamplePreview(it) }
        val remote = item.screenshots.filterNot { hasSamplePreview(it) }
        value = builtIn
        if (remote.isEmpty()) return@produceState
        val cached = coroutineScope {
            remote.map { path -> async { runCatching { backend.store.screenshotFile(path) }.getOrNull() } }
                .awaitAll()
                .filterNotNull()
        }
        value = builtIn + cached
    }
    return resolved
}

/**
 * A screenshot that lives on disk, decoded off the composition thread.
 *
 * Published screenshots reach the galleries as cached files (see [rememberResolvedScreenshots]), so this
 * is the file-backed twin of [SamplePreview]. Until the decode lands, and if it fails outright, the space
 * stays the flat chrome the surrounding cards already use rather than flashing a spinner.
 */
@Composable
internal fun ShotImage(
    backend: IdeBackend,
    path: String,
    scale: ContentScale,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        val bytes = backend.projects.imageBytes(path)
        value = bytes?.let { withContext(Dispatchers.Default) { decodeImageBytes(it) } }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(bmp, contentDescription = null, modifier = modifier, contentScale = scale)
    } else {
        Box(modifier.background(CodeMotifColors.Chrome))
    }
}
