package dev.ide.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiAnnouncement
import dev.ide.ui.backend.UiAnnouncementAccent
import dev.ide.ui.backend.UiAnnouncementAction
import dev.ide.ui.backend.UiAnnouncementBlock
import dev.ide.ui.backend.UiAnnouncementButton
import dev.ide.ui.backend.UiAnnouncementButtonStyle
import dev.ide.ui.backend.UiAnnouncementStyle
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.dismiss
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import dev.ide.ui.theme.Symbol
import org.jetbrains.compose.resources.stringResource

/**
 * One of Home's server-driven announcements, drawn in the style it asks for:
 *
 *  - [UiAnnouncementStyle.BANNER]: one compact row on the accent's container: icon, title, one button.
 *  - [UiAnnouncementStyle.CARD]: the shape of Home's other announcement cards: an icon tile beside the blocks.
 *  - [UiAnnouncementStyle.HERO]: the first image across the top, then a larger title and the other blocks.
 *
 * The accent recolours the primary roles inside the card, so its buttons and tiles follow it. [onAction] gets
 * every action but [UiAnnouncementAction.Dismiss], which (like the close button) calls [onDismiss]. [loadImage]
 * turns an image block's source into a bitmap, off the composition; it answers null when it cannot.
 */
@Composable
fun AnnouncementCard(
    announcement: UiAnnouncement,
    onAction: (UiAnnouncementAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    loadImage: suspend (String) -> ImageBitmap? = { null },
) {
    val run: (UiAnnouncementAction) -> Unit = { if (it == UiAnnouncementAction.Dismiss) onDismiss() else onAction(it) }
    val base = MaterialTheme.colorScheme
    val dark = Ide.colors.isDark
    val scheme = remember(base, dark, announcement.accent) { accentScheme(base, announcement.accent, dark) }
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        if (announcement.version >= 2) {
            DesignedAnnouncement(announcement, run, onDismiss, modifier, loadImage, app = base)
            return@MaterialTheme
        }
        when (announcement.style) {
            UiAnnouncementStyle.BANNER -> Banner(announcement, run, onDismiss, modifier)
            UiAnnouncementStyle.CARD -> CardStyle(announcement, run, onDismiss, modifier, loadImage)
            UiAnnouncementStyle.HERO -> Hero(announcement, run, onDismiss, modifier, loadImage)
        }
    }
}

@Composable
private fun Banner(a: UiAnnouncement, run: (UiAnnouncementAction) -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Ca.radius.lg)
    val title = a.blocks.firstNotNullOfOrNull { (it as? UiAnnouncementBlock.Title)?.text }
        ?: a.blocks.firstNotNullOfOrNull { (it as? UiAnnouncementBlock.Text)?.text }
        ?: ""
    val button = a.blocks.firstNotNullOfOrNull { (it as? UiAnnouncementBlock.Buttons)?.items?.firstOrNull() }
    Row(
        modifier.fillMaxWidth().clip(shape).background(scheme.primaryContainer)
            .bodyClick(a.action, run)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = if (a.dismissible) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        a.icon?.let { AnnouncementIcon(it, scheme.onPrimaryContainer, 20.dp) }
        Text(
            title, Modifier.weight(1f), color = scheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        button?.let { AnnouncementButton(it, run, compact = true) }
        if (a.dismissible) CloseButton(onDismiss, scheme.onPrimaryContainer)
    }
}

@Composable
private fun CardStyle(
    a: UiAnnouncement,
    run: (UiAnnouncementAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
    loadImage: suspend (String) -> ImageBitmap?,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Ca.radius.lg)
    Row(
        modifier.fillMaxWidth().clip(shape).background(scheme.surfaceContainerHigh)
            .border(1.dp, scheme.outlineVariant, shape)
            .bodyClick(a.action, run)
            .padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = if (a.dismissible) 6.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        a.icon?.let { name ->
            Box(
                Modifier.size(40.dp).background(scheme.primaryContainer, RoundedCornerShape(Ca.radius.md)),
                contentAlignment = Alignment.Center,
            ) { AnnouncementIcon(name, scheme.onPrimaryContainer, 20.dp) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            a.blocks.forEach { Block(it, run, loadImage, hero = false) }
        }
        if (a.dismissible) CloseButton(onDismiss, scheme.onSurfaceVariant)
    }
}

@Composable
private fun Hero(
    a: UiAnnouncement,
    run: (UiAnnouncementAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
    loadImage: suspend (String) -> ImageBitmap?,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Ca.radius.xl)
    val cover = a.blocks.firstOrNull { it is UiAnnouncementBlock.Image } as UiAnnouncementBlock.Image?
    Column(
        modifier.fillMaxWidth().clip(shape).background(scheme.surfaceContainerHigh)
            .border(1.dp, scheme.outlineVariant, shape)
            .bodyClick(a.action, run),
    ) {
        if (cover != null) {
            Box {
                AnnouncementImage(cover, loadImage, RectangleCorners)
                if (a.dismissible) {
                    // Over a picture the button needs its own plate to be seen on any image.
                    Box(Modifier.align(Alignment.TopEnd).padding(8.dp).clip(CircleShape).background(scheme.scrim.copy(alpha = 0.45f))) {
                        CloseButton(onDismiss, Color.White)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, top = 16.dp, bottom = 18.dp, end = if (cover == null && a.dismissible) 6.dp else 18.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                a.icon?.let { AnnouncementIcon(it, scheme.primary, 26.dp) }
                a.blocks.forEach { if (it !== cover) Block(it, run, loadImage, hero = true) }
            }
            if (cover == null && a.dismissible) CloseButton(onDismiss, scheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Block(
    block: UiAnnouncementBlock,
    run: (UiAnnouncementAction) -> Unit,
    loadImage: suspend (String) -> ImageBitmap?,
    hero: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    when (block) {
        is UiAnnouncementBlock.Title -> Text(
            block.text, color = scheme.onSurface,
            style = if (hero) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        is UiAnnouncementBlock.Text -> Text(
            block.text, color = scheme.onSurfaceVariant,
            style = if (hero) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
        )
        is UiAnnouncementBlock.Image -> AnnouncementImage(block, loadImage, RoundedCornerShape(Ca.radius.md))
        is UiAnnouncementBlock.Chips -> FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            block.items.forEach { Chip(it, fill = scheme.secondaryContainer, textColor = scheme.onSecondaryContainer) }
        }
        is UiAnnouncementBlock.Buttons -> FlowRow(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            block.items.forEach { AnnouncementButton(it, run, compact = false) }
        }
        is UiAnnouncementBlock.Divider -> HorizontalDivider(Modifier.padding(vertical = 2.dp), color = scheme.outlineVariant)
        is UiAnnouncementBlock.Spacer -> Spacer(Modifier.height(8.dp))
        // Blocks the first card styles never had draw as a designed card would, so a layout without "v" that
        // uses one still shows it.
        else -> V1Fallback(block, run, loadImage)
    }
}

@Composable
private fun AnnouncementButton(button: UiAnnouncementButton, run: (UiAnnouncementAction) -> Unit, compact: Boolean) {
    val height = if (compact) 34.dp else 38.dp
    val padding = PaddingValues(horizontal = if (compact) 12.dp else 16.dp)
    val shape = MaterialTheme.shapes.small
    val label: @Composable () -> Unit = {
        Text(button.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val onClick = { run(button.action) }
    when (button.style) {
        UiAnnouncementButtonStyle.PRIMARY ->
            Button(onClick, Modifier.height(height), shape = shape, contentPadding = padding) { label() }
        UiAnnouncementButtonStyle.SECONDARY ->
            FilledTonalButton(onClick, Modifier.height(height), shape = shape, contentPadding = padding) { label() }
        UiAnnouncementButtonStyle.TEXT ->
            TextButton(onClick, Modifier.height(height), shape = shape, contentPadding = padding) { label() }
    }
}

@Composable
private fun AnnouncementImage(
    block: UiAnnouncementBlock.Image,
    loadImage: suspend (String) -> ImageBitmap?,
    shape: androidx.compose.ui.graphics.Shape,
) {
    val bitmap by produceState<ImageBitmap?>(null, block.src) {
        value = runCatching { loadImage(block.src) }.getOrNull()
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Full width at its own aspect on a phone; on a wide window the height stops growing and the picture
        // is cropped instead, so a banner image does not fill the screen.
        val frame = Modifier.fillMaxWidth().height((maxWidth / block.aspect).coerceAtMost(MAX_IMAGE_HEIGHT)).clip(shape)
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp, contentDescription = block.alt, modifier = frame, contentScale = ContentScale.Crop)
        } else {
            // The space is held while the image loads, so the card does not jump when it lands.
            Box(frame.background(MaterialTheme.colorScheme.surfaceContainerHighest))
        }
    }
}

private val MAX_IMAGE_HEIGHT = 220.dp

@Composable
internal fun CloseButton(onDismiss: () -> Unit, tint: Color) {
    IconButtonCa(CaIcons.close, stringResource(Res.string.dismiss), onDismiss, iconSize = 16, boxSize = 30, tint = tint)
}

@Composable
internal fun AnnouncementIcon(name: String, tint: Color, size: Dp) {
    val vector = vectorIcon(name)
    if (vector != null) {
        Icon(vector, null, Modifier.size(size), tint = tint)
        return
    }
    val glyph = symbolIcon(name) ?: return
    Symbol(glyph, contentDescription = null, size = size, tint = tint)
}

private fun Modifier.bodyClick(action: UiAnnouncementAction?, run: (UiAnnouncementAction) -> Unit): Modifier =
    if (action == null) this else clickable { run(action) }

private val RectangleCorners = RoundedCornerShape(0.dp)

/** The names from the announcement icon set that are drawn as vectors. */
private fun vectorIcon(name: String): ImageVector? = when (name) {
    "sparkle" -> CaIcons.sparkle
    "campaign", "bell" -> CaIcons.bell
    "lightbulb" -> CaIcons.lightbulb
    "heart" -> CaIcons.heart
    else -> null
}

/** The names from the announcement icon set that are Material Symbols glyphs. */
private fun symbolIcon(name: String): Char? = when (name) {
    "bolt" -> CaSymbols.bolt
    "rocket" -> CaSymbols.rocketLaunch
    "info" -> CaSymbols.info
    "star" -> CaSymbols.star
    "school" -> CaSymbols.school
    "store" -> CaSymbols.explore
    "extension" -> CaSymbols.extension
    "code" -> CaSymbols.codeBlocks
    "android" -> CaSymbols.phoneAndroid
    "chat" -> CaSymbols.forum
    "event" -> CaSymbols.schedule
    "fire" -> CaSymbols.localFireDepartment
    "premium" -> CaSymbols.workspacePremium
    "verified" -> CaSymbols.verified
    "warning" -> CaSymbols.warning
    "update" -> CaSymbols.update
    "download" -> CaSymbols.download
    "palette" -> CaSymbols.palette
    "link" -> CaSymbols.link
    else -> null
}

/**
 * [base] with its primary roles taken from [accent]'s family, light or dark. The families are the app's own
 * accent presets (lime, teal, violet, orange for amber) and the error ramp for red; neutral keeps [base].
 */
internal fun accentScheme(base: ColorScheme, accent: UiAnnouncementAccent, dark: Boolean): ColorScheme {
    val roles = when (accent) {
        UiAnnouncementAccent.NEUTRAL -> return base
        UiAnnouncementAccent.LIME -> if (dark) Roles(0xFFCDF14A, 0xFF232D00, 0xFF344200, 0xFFE4FF74) else Roles(0xFF465700, 0xFFFFFFFF, 0xFFC7EE45, 0xFF131F00)
        UiAnnouncementAccent.TEAL -> if (dark) Roles(0xFF55D6F5, 0xFF003641, 0xFF004E5D, 0xFFACEDFF) else Roles(0xFF00687A, 0xFFFFFFFF, 0xFFACEDFF, 0xFF001F27)
        UiAnnouncementAccent.VIOLET -> if (dark) Roles(0xFFCFBCFF, 0xFF38158A, 0xFF5335C0, 0xFFE9DDFF) else Roles(0xFF6E4CE0, 0xFFFFFFFF, 0xFFE9DDFF, 0xFF22005C)
        UiAnnouncementAccent.AMBER -> if (dark) Roles(0xFFFFB77C, 0xFF4D2700, 0xFF6D3900, 0xFFFFDCBE) else Roles(0xFF9A4A00, 0xFFFFFFFF, 0xFFFFDCBE, 0xFF2E1500)
        UiAnnouncementAccent.RED -> if (dark) Roles(0xFFFFB4AB, 0xFF690005, 0xFF93000A, 0xFFFFDAD6) else Roles(0xFFBA1A1A, 0xFFFFFFFF, 0xFFFFDAD6, 0xFF410002)
    }
    return base.copy(
        primary = Color(roles.primary), onPrimary = Color(roles.onPrimary),
        primaryContainer = Color(roles.container), onPrimaryContainer = Color(roles.onContainer),
        // The tonal button and the chips read the secondary container; tie them to the accent too.
        secondaryContainer = Color(roles.container), onSecondaryContainer = Color(roles.onContainer),
    )
}

private class Roles(val primary: Long, val onPrimary: Long, val container: Long, val onContainer: Long)

/** How many server announcements Home shows at once. */
const val HOME_ANNOUNCEMENT_LIMIT: Int = 2

/** The preference that holds the dismissed announcements' keys (`id@revision`), comma separated. */
const val ANNOUNCEMENTS_DISMISSED_PREF: String = "announce.dismissed"

/**
 * Which of [served] Home draws: not dismissed (by [UiAnnouncement.dismissKey]), not past their end at [nowMs],
 * in the server's order, at most [limit].
 */
fun visibleAnnouncements(
    served: List<UiAnnouncement>,
    dismissed: Set<String>,
    nowMs: Long,
    limit: Int = HOME_ANNOUNCEMENT_LIMIT,
): List<UiAnnouncement> = served.asSequence()
    .filter { it.dismissKey !in dismissed }
    .filter { a -> a.endsAtMillis.let { it == null || it > nowMs } }
    .distinctBy { it.id }
    .take(limit)
    .toList()

/** The dismissed keys stored in [pref]. */
fun parseDismissedAnnouncements(pref: String?): Set<String> =
    pref.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

/**
 * [pref] with [key] added, keeping only the newest [keep] keys: the list is only ever read for live
 * announcements, so an old key is worth nothing once its announcement has gone.
 */
fun addDismissedAnnouncement(pref: String?, key: String, keep: Int = 50): String =
    (parseDismissedAnnouncements(pref).toList().filter { it != key } + key).takeLast(keep).joinToString(",")
