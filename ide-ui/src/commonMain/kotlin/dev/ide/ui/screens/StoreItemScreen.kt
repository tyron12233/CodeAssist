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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.IdeBackend
import androidx.compose.runtime.collectAsState
import dev.ide.ui.backend.UiInstallState
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import dev.ide.ui.components.CodeMotifColors
import dev.ide.ui.components.Eyebrow
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
import kotlinx.coroutines.launch
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.produceState
import dev.ide.ui.components.RatingSummary
import dev.ide.ui.components.ReviewCard
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
import dev.ide.ui.generated.resources.reviews_write
import dev.ide.ui.generated.resources.review_hidden_done
import dev.ide.ui.generated.resources.review_reported
import org.jetbrains.compose.resources.stringResource

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
        UiInstallState.INSTALLED -> InstallState.Installed
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

    // The hero's tint is stable per item rather than per list position: this screen shows one card, so
    // there is no run to rotate through. Hashing the id keeps a given item the same colour every visit.
    val pair = tonalPair(item.id.hashCode())
    val tabs = remember(item) { availableTabs(item) }

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
                item("hero") { DetailHero(item, pair) }
                item("cta") {
                    InstallRow(
                        item = item,
                        state = install,
                        onRate = onRate,
                        onPrimary = {
                            when {
                                item.templateId != null -> onCreateFromTemplate(item.templateId!!)
                                install is InstallState.Installed -> Unit
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
                item("hint") { InstallHint(item, message ?: progress?.message) }

                if (tabs.size > 1) {
                    item("tabs") { TabRowPills(tabs, tab) { tab = it } }
                }

                when (tab) {
                    StoreTab.Overview -> overview(item) { lightbox = it }
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
            }
        }
        if (lightbox >= 0) {
            ScreenshotLightbox(item, lightbox, onClose = { lightbox = -1 }, onIndex = { lightbox = it })
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

/**
 * Fullscreen screenshot viewer.
 *
 * Fills the fixed dark editor ground rather than the theme's surface: the thing being shown is either a
 * screenshot of a running app or a code panel, and both were composed against dark. Prev/next buttons sit
 * beside the dot indicator rather than relying on the swipe alone, so the control is reachable without a
 * gesture.
 */
@Composable
private fun ScreenshotLightbox(
    item: UiStoreItem,
    index: Int,
    onClose: () -> Unit,
    onIndex: (Int) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val real = remember(item) { item.screenshots.filter { hasSamplePreview(it) } }
    val shots: List<String?> = when {
        real.isNotEmpty() -> real
        hasSamplePreview(item.previewKey) -> listOf(item.previewKey!!)
        else -> listOf(null, null)
    }
    val current = index.coerceIn(0, shots.lastIndex)
    Box(
        Modifier.fillMaxSize().background(CodeMotifColors.Chrome)
            // Swallow taps so the content behind the overlay cannot be reached.
            .clickable(enabled = true, onClick = {}),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.1f))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Symbol(CaSymbols.close, contentDescription = stringResource(Res.string.back), size = 23.dp, tint = Color.White)
                }
                Text(
                    "${current + 1} / ${shots.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(46.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CodeMotifColors.Chrome)) {
                    val key = shots[current]
                    if (key != null) {
                        SamplePreview(key, Modifier.fillMaxWidth().height(340.dp))
                    } else {
                        CodePanel(item, current)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                LightboxNav(CaSymbols.chevronLeft, Color.White.copy(alpha = 0.1f), Color.White) {
                    onIndex((current - 1 + shots.size) % shots.size)
                }
                Row(
                    Modifier.padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    shots.indices.forEach { i ->
                        // The active dot stretches into a bar rather than changing colour alone.
                        val width by animateDpAsState(if (i == current) 22.dp else 7.dp, label = "lightboxDot")
                        Box(
                            Modifier.width(width).height(7.dp).clip(CircleShape)
                                .background(if (i == current) Color.White else Color.White.copy(alpha = 0.35f)),
                        )
                    }
                }
                LightboxNav(CaSymbols.chevronRight, c.primaryContainer, c.onPrimaryContainer) {
                    onIndex((current + 1) % shots.size)
                }
            }
        }
    }
}

@Composable
private fun LightboxNav(glyph: Char, container: Color, content: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(container).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(glyph, contentDescription = null, size = 22.dp, tint = content)
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
 * The tonal hero.
 *
 * The icon tile is **inverted** against the card — the container's `on*` role as its background — so the
 * one element that has to read as an app icon does, against a field of the same hue.
 */
@Composable
private fun DetailHero(item: UiStoreItem, pair: TonalPair) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = pair.container,
        contentColor = pair.onContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Box(Modifier.clipToBounds()) {
            Symbol(
                glyph = CaSymbols.forIconId(item.iconId),
                contentDescription = null,
                size = 170.dp,
                tint = pair.onContainer.copy(alpha = 0.13f),
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 22.dp, y = 34.dp),
            )
            Column(Modifier.padding(20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(
                        Modifier.size(64.dp)
                            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 8.dp, bottomEnd = 22.dp, bottomStart = 8.dp))
                            .background(pair.onContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        // The template's OWN mark, forced to the tile's colour: the inverted tile is
                        // already carrying the identity, and a brand-coloured glyph on it would clash.
                        TemplateGlyph(
                            iconId = item.iconId,
                            size = 32.dp,
                            fallbackTint = pair.container,
                            forceTint = pair.container,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = pair.onContainer,
                        )
                        if (item.author != null) {
                            Row(
                                Modifier.padding(top = 6.dp),
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
                            }
                        }
                    }
                }
                // A bundled item has no rating, installs or payload size, so the figure row is skipped
                // entirely rather than left as an empty band of padding under the title.
                val hasStats = item.rating >= 0f || item.installs >= 0 || item.downloadBytes > 0
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

/** The install state machine, as the design draws it: the button's shape changes with the state. */
private sealed interface InstallState {
    data object Idle : InstallState
    data class Downloading(val progress: Float) : InstallState
    data object Installed : InstallState
}

@Composable
private fun InstallRow(
    item: UiStoreItem,
    state: InstallState,
    onRate: (() -> Unit)?,
    onPrimary: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val busy = state is InstallState.Downloading
    val label = when {
        item.templateId != null -> stringResource(Res.string.store_create)
        !item.available -> stringResource(Res.string.store_coming_soon)
        busy -> stringResource(Res.string.store_installing)
        state is InstallState.Installed -> stringResource(Res.string.store_open_in_editor)
        else -> stringResource(Res.string.store_install_project)
    }
    val glyph = when {
        item.templateId != null -> CaSymbols.add
        busy -> CaSymbols.progressActivity
        state is InstallState.Installed -> CaSymbols.playArrow
        else -> CaSymbols.download
    }
    // Idle → busy → done each get their own silhouette, and the transition between them animates, which
    // is the one shape morph in the app.
    val targetShape = when {
        busy -> CaShapes.InstallBusy
        state is InstallState.Installed -> CaShapes.InstallDone
        else -> CaShapes.InstallIdle
    }
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
private fun InstallHint(item: UiStoreItem, message: String?) {
    val parts = listOfNotNull(
        message,
        item.version?.let { "v$it" },
        item.language,
        item.downloadBytes.takeIf { it > 0 }?.let { formatSize(it) },
    )
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
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
        items(tabs, key = { it.name }) { t ->
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
private fun LazyListScope.overview(item: UiStoreItem, onOpenShot: (Int) -> Unit) {
    // Only offered when the item actually has artwork; see ScreenshotCarousel.
    if (item.screenshots.isNotEmpty() || item.previewKey != null) {
        item("shots") { ScreenshotCarousel(item, onOpenShot) }
    }
    item("about") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 22.dp)) {
            Eyebrow(stringResource(Res.string.store_item_about))
            Text(
                item.description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp),
            )
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
        item("ratings") { RatingsPanel(item) }
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
private fun ScreenshotCarousel(item: UiStoreItem, onOpen: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    val real = remember(item) { item.screenshots.filter { hasSamplePreview(it) } }
    val shots: List<String?> = when {
        real.isNotEmpty() -> real
        hasSamplePreview(item.previewKey) -> listOf(item.previewKey!!)
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
            Box(
                Modifier.width(218.dp).height(154.dp)
                    .clip(cardShape(i))
                    .background(CodeMotifColors.Chrome)
                    .clickable { onOpen(i) },
            ) {
                if (key != null) {
                    SamplePreview(key, Modifier.fillMaxSize())
                } else {
                    CodePanel(item, i)
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

/** The abstract editor stand-in: chrome bar, file-tree gutter, syntax-coloured bars. */
@Composable
private fun CodePanel(item: UiStoreItem, index: Int) {
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
    val rows = buildList {
        add(Triple(CaSymbols.extension, stringResource(Res.string.store_item_type), kindLabel(item.kind)))
        item.language?.let { add(Triple(CaSymbols.codeBlocks, stringResource(Res.string.store_item_language), it)) }
        item.version?.let { add(Triple(CaSymbols.update, stringResource(Res.string.store_item_version), "v$it")) }
        if (item.downloadBytes > 0) {
            add(Triple(CaSymbols.download, stringResource(Res.string.store_item_size), formatSize(item.downloadBytes)))
        }
        if (item.installs >= 0) {
            add(Triple(CaSymbols.cloudDownload, stringResource(Res.string.store_item_downloads), installLabel(item.installs)))
        }
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
 * The histogram is derived from the average and the count, not from real per-star buckets — the catalog
 * reports only a mean today. It is drawn only when there are ratings at all.
 */
@Composable
private fun RatingsPanel(item: UiStoreItem) {
    val c = MaterialTheme.colorScheme
    if (item.rating < 0f || item.ratingCount <= 0) return
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
                            Box(
                                Modifier.fillMaxWidth(histogramShare(item.rating, star))
                                    .height(9.dp).clip(CircleShape).background(c.primary),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A README or changelog rendered as plain prose on a tonal card. */
@Composable
private fun ProseCard(text: String) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = c.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 18.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = c.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
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

private fun compactCount(n: Int): String = when {
    n >= 1_000_000 -> "${n / 1_000_000}M"
    n >= 1_000 -> "${n / 1_000}K"
    else -> n.toString()
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    if (mb >= 1.0) {
        val tenths = (mb * 10).toLong()
        return "${tenths / 10}.${tenths % 10} MB"
    }
    return "${(bytes / 1024).coerceAtLeast(1)} KB"
}

/**
 * A plausible distribution around [average] for the histogram bars.
 *
 * Presentation, not data: the catalog reports a mean and a count, never per-star buckets. The bars are a
 * shape around the mean so the panel reads correctly, and they are only ever drawn next to the real
 * average and the real count.
 */
private fun histogramShare(average: Float, star: Int): Float {
    val distance = kotlin.math.abs(star - average)
    return (1f - (distance / 2.2f)).coerceIn(0.02f, 1f)
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

    val page by produceState(
        dev.ide.ui.backend.UiReviewPage(loading = true),
        item.id, sort, refresh, voteEpoch, backend,
    ) {
        value = dev.ide.ui.backend.UiReviewPage(loading = true)
        val fetched = runCatching { backend.store.reviews(item.id, sort) }.getOrNull()
            ?: dev.ide.ui.backend.UiReviewPage(error = "Could not load reviews")
        value = fetched
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
            ReviewCard(
                review = review,
                relativeTime = relativeAge(review.postedAtMs, now),
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
