package dev.ide.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiIconLayer
import dev.ide.ui.components.CaSwitch
import dev.ide.ui.components.CircularProgressIndicator
import dev.ide.ui.components.ColorPickerDialog
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.editor.preview.AppIconRaster.drawAppIcon
import dev.ide.ui.editor.preview.IconMask
import dev.ide.ui.editor.preview.decodeImageBytes
import dev.ide.ui.editor.preview.drawCheckerboard
import dev.ide.ui.editor.preview.drawUiDrawable
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.appicon_advanced
import dev.ide.ui.generated.resources.appicon_apply
import dev.ide.ui.generated.resources.appicon_applied
import dev.ide.ui.generated.resources.appicon_background
import dev.ide.ui.generated.resources.appicon_bg_colour
import dev.ide.ui.generated.resources.appicon_bg_gradient
import dev.ide.ui.generated.resources.appicon_bg_image
import dev.ide.ui.generated.resources.appicon_choose_icon
import dev.ide.ui.generated.resources.appicon_choose_image
import dev.ide.ui.generated.resources.appicon_current
import dev.ide.ui.generated.resources.appicon_custom_colour
import dev.ide.ui.generated.resources.appicon_files
import dev.ide.ui.generated.resources.appicon_foreground
import dev.ide.ui.generated.resources.appicon_gen_playstore
import dev.ide.ui.generated.resources.appicon_gen_rasters
import dev.ide.ui.generated.resources.appicon_gen_round
import dev.ide.ui.generated.resources.appicon_gradient_angle
import dev.ide.ui.generated.resources.appicon_gradient_end
import dev.ide.ui.generated.resources.appicon_gradient_start
import dev.ide.ui.generated.resources.appicon_manifest
import dev.ide.ui.generated.resources.appicon_mask_circle
import dev.ide.ui.generated.resources.appicon_mask_rounded
import dev.ide.ui.generated.resources.appicon_mask_square
import dev.ide.ui.generated.resources.appicon_mask_squircle
import dev.ide.ui.generated.resources.appicon_module
import dev.ide.ui.generated.resources.appicon_monochrome
import dev.ide.ui.generated.resources.appicon_more_images
import dev.ide.ui.generated.resources.appicon_no_module
import dev.ide.ui.generated.resources.appicon_none
import dev.ide.ui.generated.resources.appicon_offset_x
import dev.ide.ui.generated.resources.appicon_offset_y
import dev.ide.ui.generated.resources.appicon_pick_background
import dev.ide.ui.generated.resources.appicon_pick_foreground
import dev.ide.ui.generated.resources.appicon_preview
import dev.ide.ui.generated.resources.appicon_replacing
import dev.ide.ui.generated.resources.appicon_run
import dev.ide.ui.generated.resources.appicon_same_as_foreground
import dev.ide.ui.generated.resources.appicon_scale
import dev.ide.ui.generated.resources.appicon_themed
import dev.ide.ui.generated.resources.appicon_title
import dev.ide.ui.generated.resources.appicon_use_project_drawable
import dev.ide.ui.generated.resources.appicon_written
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Symbol
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** Cards stay this wide at most and centre in the window, matching the Settings hub. */
private val CARD_MAX = 620.dp

/** From this width the preview sits beside the controls instead of above them. */
private val WIDE_LAYOUT = 840.dp

/** The preview column's width in the wide layout: room for the four mask labels at full length. */
private val PREVIEW_PANE = 420.dp

/** A colour the user is choosing: where the picker starts, and what to do with the result. */
private class ColourRequest(val initial: Long, val onPick: (Long) -> Unit)

/** Which layer the icon picker is choosing for. */
private enum class StudioPick { Foreground, Background }

/** How the background is made, which picks the controls the background card shows. */
private enum class BackgroundMode { Colour, Gradient, Image }

private fun modeOf(layer: UiIconLayer): BackgroundMode = when (layer) {
    is UiIconLayer.Gradient -> BackgroundMode.Gradient
    is UiIconLayer.ImageFile, is UiIconLayer.Resource, is UiIconLayer.RepoIcon -> BackgroundMode.Image
    UiIconLayer.None, is UiIconLayer.Color -> BackgroundMode.Colour
}

/**
 * The app-icon studio: compose a launcher icon from a background and a foreground layer, see it under every
 * launcher mask (and as an Android 13+ themed icon), then write the adaptive XML, the density PNGs, the round
 * variant and the Play Store image, and point the manifest at them.
 *
 * The preview stays in view while the controls scroll: pinned above them on a phone, beside them on a wide
 * screen. It draws the exact models the generator will write, through the same code path that rasterises the
 * PNGs, so what is shown here is what lands in the project. Icons are chosen in a picker over the studio, so
 * choosing one never throws away the rest of the icon.
 */
@Composable
fun AppIconStudioScreen(
    backend: IdeBackend,
    onBack: () -> Unit,
    /** The foreground to start with, when the user picked an icon for it in the Icon Manager. */
    seed: UiIconLayer? = null,
    fileActions: FileActions = FileActions.None,
    /** Open a written file in the editor; null where there is no editor to open it in. */
    onOpenFile: ((String) -> Unit)? = null,
    /** Build and run the app to see the icon on a device; null where the project cannot be run. */
    onRunApp: (() -> Unit)? = null,
) {
    val state = rememberAppIconStudioState(backend, seed)
    // Held here rather than in the sheet, so the picker reopens on the section, search and style it was left on.
    val picker = rememberIconManagerState(backend)
    var picking by remember { mutableStateOf<StudioPick?>(null) }
    // The colour picker is an overlay that has to sit over the whole screen, so the cards only ask for it.
    var colour by remember { mutableStateOf<ColourRequest?>(null) }
    val snackbar = remember { SnackbarHostState() }

    state.message?.let { text ->
        LaunchedEffect(text) {
            snackbar.showSnackbar(text)
            state.dismissMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        ExpressiveScaffold(
            title = stringResource(Res.string.appicon_title),
            onBack = onBack,
            large = false,
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            when {
                state.loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                    CircularProgressIndicator()
                }

                !state.hasModule() -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                    Text(
                        stringResource(Res.string.appicon_no_module),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(Ca.spacing.s8),
                    )
                }

                else -> BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                    val controls: @Composable (Modifier) -> Unit = { modifier ->
                        StudioControls(
                            state = state,
                            modifier = modifier,
                            fileActions = fileActions,
                            onPick = { picking = it },
                            onPickColour = { initial, onPick -> colour = ColourRequest(initial, onPick) },
                            onOpenFile = onOpenFile,
                            onRunApp = onRunApp,
                        )
                    }
                    if (maxWidth >= WIDE_LAYOUT) {
                        Row(
                            Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2, Alignment.CenterHorizontally),
                        ) {
                            // Spaced like the controls column, so the two first cards line up.
                            Column(
                                Modifier.width(PREVIEW_PANE).fillMaxHeight().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
                            ) {
                                Spacer(Modifier.height(Ca.spacing.s2))
                                PreviewCard(state)
                            }
                            controls(Modifier.widthIn(max = CARD_MAX).weight(1f, fill = false).fillMaxHeight())
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            PinnedPreview(state)
                            controls(Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                }
            }
        }

        ColorPickerDialog(colour != null, colour?.initial ?: 0xFFFFFFFFL, onDismiss = { colour = null }) {
            colour?.onPick?.invoke(it)
            colour = null
        }
    }

    picking?.let { target ->
        IconPickerSheet(
            state = picker,
            title = stringResource(
                if (target == StudioPick.Foreground) Res.string.appicon_pick_foreground else Res.string.appicon_pick_background,
            ),
            onDismiss = { picking = null },
            onPick = { layer ->
                if (target == StudioPick.Foreground) state.setForegroundLayer(layer) else state.setBackgroundLayer(layer)
                picking = null
            },
        )
    }
}

@Composable
private fun StudioControls(
    state: AppIconStudioState,
    modifier: Modifier,
    fileActions: FileActions,
    onPick: (StudioPick) -> Unit,
    onPickColour: (initial: Long, onPick: (Long) -> Unit) -> Unit,
    onOpenFile: ((String) -> Unit)?,
    onRunApp: (() -> Unit)?,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
    ) {
        Spacer(Modifier.height(Ca.spacing.s2))
        ForegroundCard(state, { onPick(StudioPick.Foreground) }, fileActions)
        BackgroundCard(state, { onPick(StudioPick.Background) }, onPickColour, fileActions)
        MonochromeCard(state)
        AdvancedCard(state)
        WarningsCard(state)
        ApplyRow(state)
        if (state.applied && state.written.isNotEmpty()) WrittenCard(state, onOpenFile, onRunApp)
        Spacer(Modifier.height(Ca.spacing.s8))
    }
}

// ---------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------

/** The preview for the wide layout: both tiles, the masks, and the module, in a card of its own. */
@Composable
private fun PreviewCard(state: AppIconStudioState) {
    StudioCard {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Ca.spacing.s4),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s6),
                verticalAlignment = Alignment.Bottom,
            ) {
                CurrentTile(state, 64.dp)
                PreviewTile(state, 144.dp)
            }
            MaskRow(state)
            ThemedChip(state)
            state.current?.moduleName?.let {
                Caption("${stringResource(Res.string.appicon_module)}: $it")
            }
        }
    }
}

/**
 * The phone layout's preview: a compact band pinned above the scrolling controls, so dragging a slider or
 * picking a colour is seen as it happens instead of after scrolling back up.
 */
@Composable
private fun PinnedPreview(state: AppIconStudioState) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Ca.spacing.s4, vertical = Ca.spacing.s3),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CurrentTile(state, 48.dp)
                PreviewTile(state, 96.dp)
                ThemedChip(state)
            }
            MaskRow(state)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** What the module ships today, so a replacement is a comparison rather than a leap. */
@Composable
private fun CurrentTile(state: AppIconStudioState, size: Dp) {
    val current = state.current
    val bytes = current?.currentBytes
    var bitmap by remember(bytes) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bytes) { bitmap = bytes?.let(::decodeImageBytes) }
    LabelledTile(stringResource(Res.string.appicon_current), size = size) {
        Canvas(Modifier.fillMaxSize()) { drawCheckerboard(cell = 6f) }
        val drawable = current?.current
        when {
            drawable != null -> Canvas(Modifier.fillMaxSize()) { drawUiDrawable(drawable, Offset.Zero, this.size) }
            // A launcher icon that is only a PNG has no drawable model, but it still has a picture.
            bitmap != null -> Image(bitmap!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

@Composable
private fun PreviewTile(state: AppIconStudioState, size: Dp) {
    LabelledTile(stringResource(Res.string.appicon_preview), size = size) {
        Canvas(Modifier.fillMaxSize()) { drawCheckerboard(cell = 8f) }
        state.preview?.let { preview ->
            val images = state.images
            Canvas(Modifier.fillMaxSize()) {
                drawAppIcon(preview, state.mask, monochrome = state.showThemed, images = images)
            }
        }
    }
}

@Composable
private fun MaskRow(state: AppIconStudioState) {
    val masks = listOf(
        IconMask.CIRCLE to stringResource(Res.string.appicon_mask_circle),
        IconMask.SQUIRCLE to stringResource(Res.string.appicon_mask_squircle),
        IconMask.ROUNDED_SQUARE to stringResource(Res.string.appicon_mask_rounded),
        IconMask.SQUARE to stringResource(Res.string.appicon_mask_square),
    )
    SingleChoiceSegmentedButtonRow {
        masks.forEachIndexed { index, (mask, label) ->
            SegmentedButton(
                selected = state.mask == mask,
                onClick = { state.selectMask(mask) },
                shape = SegmentedButtonDefaults.itemShape(index, masks.size),
                icon = {},
                label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

@Composable
private fun ThemedChip(state: AppIconStudioState) {
    FilterChip(
        selected = state.showThemed,
        onClick = { state.toggleThemed() },
        label = { Text(stringResource(Res.string.appicon_themed)) },
        leadingIcon = { Icon(CaIcons.moon, null, Modifier.size(16.dp)) },
    )
}

/** A preview tile with its caption underneath, both centred on the tile's own width. */
@Composable
private fun LabelledTile(label: String, size: Dp, content: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
    ) {
        Surface(shape = MaterialTheme.shapes.large, color = Color.Transparent, modifier = Modifier.size(size)) {
            Box(contentAlignment = Alignment.Center) { content() }
        }
        Caption(label)
    }
}

// ---------------------------------------------------------------------------
// Layers
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ForegroundCard(state: AppIconStudioState, onChooseIcon: () -> Unit, fileActions: FileActions) {
    StudioCard {
        SectionHeader(stringResource(Res.string.appicon_foreground), layerLabel(state.spec.foreground))

        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
            verticalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
        ) {
            FilledTonalButton(onClick = onChooseIcon) {
                Icon(CaIcons.search, null, Modifier.size(16.dp))
                Spacer(Modifier.size(Ca.spacing.s2))
                Text(stringResource(Res.string.appicon_choose_icon))
            }
            if (fileActions.canPickFile) {
                OutlinedButton(
                    onClick = { fileActions.pickFile { path -> if (path != null) state.setForegroundImage(path) } },
                ) {
                    Icon(CaIcons.image, null, Modifier.size(16.dp))
                    Spacer(Modifier.size(Ca.spacing.s2))
                    Text(stringResource(Res.string.appicon_choose_image))
                }
            }
            if (state.spec.foreground != UiIconLayer.None) {
                OutlinedButton(onClick = { state.clearForeground() }) {
                    Text(stringResource(Res.string.appicon_none))
                }
            }
        }

        // Placement only means something for vector artwork; a bitmap layer is used as authored.
        if (state.spec.foreground is UiIconLayer.RepoIcon || state.spec.foreground is UiIconLayer.Resource) {
            val (scale, offsetX, offsetY) = state.placement()
            LabelledSlider(stringResource(Res.string.appicon_scale), scale, 0.3f, 1.5f, state::setScale)
            LabelledSlider(stringResource(Res.string.appicon_offset_x), offsetX, -0.25f, 0.25f, state::setOffsetX)
            LabelledSlider(stringResource(Res.string.appicon_offset_y), offsetY, -0.25f, 0.25f, state::setOffsetY)

            SwatchRow(
                selected = state.foregroundTint(),
                swatches = APP_ICON_TINTS,
                onNone = { state.setForegroundTint(null) },
                onPick = { state.setForegroundTint(it) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackgroundCard(
    state: AppIconStudioState,
    onChooseDrawable: () -> Unit,
    onPickColour: (initial: Long, onPick: (Long) -> Unit) -> Unit,
    fileActions: FileActions,
) {
    val background = state.spec.background
    // Held apart from the spec: choosing "Image" shows its buttons before there is an image to show.
    var mode by remember { mutableStateOf(modeOf(background)) }
    StudioCard {
        SectionHeader(stringResource(Res.string.appicon_background), layerLabel(background))

        val modes = listOf(
            BackgroundMode.Colour to stringResource(Res.string.appicon_bg_colour),
            BackgroundMode.Gradient to stringResource(Res.string.appicon_bg_gradient),
            BackgroundMode.Image to stringResource(Res.string.appicon_bg_image),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, (option, label) ->
                SegmentedButton(
                    selected = mode == option,
                    onClick = {
                        mode = option
                        when (option) {
                            // Switching keeps the colour the user had, as the flat fill or the gradient's start.
                            BackgroundMode.Colour -> if (background !is UiIconLayer.Color) {
                                state.setBackgroundColor((background as? UiIconLayer.Gradient)?.startArgb ?: 0xFFFFFFFFL)
                            }

                            BackgroundMode.Gradient -> if (background !is UiIconLayer.Gradient) {
                                // A colour becomes a gradient into a darker shade of itself; white or black
                                // have no useful shade (white to grey reads as dirty), so they take a preset.
                                val (start, end) = (background as? UiIconLayer.Color)
                                    ?.takeIf { !isNeutralExtreme(it.argb) }
                                    ?.let { it.argb to darken(it.argb) }
                                    ?: APP_ICON_GRADIENTS.first()
                                state.setBackgroundGradient(start, end, 135)
                            }

                            BackgroundMode.Image -> Unit
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    icon = {},
                    label = { Text(label, maxLines = 1) },
                )
            }
        }

        when (mode) {
            BackgroundMode.Colour -> ColourControls(state, onPickColour)
            BackgroundMode.Gradient -> GradientControls(state, onPickColour)
            BackgroundMode.Image -> FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
                verticalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
            ) {
                if (fileActions.canPickFile) {
                    OutlinedButton(
                        onClick = { fileActions.pickFile { path -> if (path != null) state.setBackgroundImage(path) } },
                    ) {
                        Icon(CaIcons.image, null, Modifier.size(16.dp))
                        Spacer(Modifier.size(Ca.spacing.s2))
                        Text(stringResource(Res.string.appicon_choose_image))
                    }
                }
                OutlinedButton(onClick = onChooseDrawable) {
                    Icon(CaIcons.search, null, Modifier.size(16.dp))
                    Spacer(Modifier.size(Ca.spacing.s2))
                    Text(stringResource(Res.string.appicon_use_project_drawable))
                }
            }
        }
    }
}

/** The flat-colour swatches, plus any colour at all through the picker (the current one shown when custom). */
@Composable
private fun ColourControls(state: AppIconStudioState, onPickColour: (Long, (Long) -> Unit) -> Unit) {
    val selected = (state.spec.background as? UiIconLayer.Color)?.argb
    val swatches = if (selected != null && selected !in APP_ICON_BACKGROUNDS) listOf(selected) + APP_ICON_BACKGROUNDS else APP_ICON_BACKGROUNDS
    SwatchRow(
        selected = selected,
        swatches = swatches,
        onNone = { state.clearBackground() },
        onPick = { state.setBackgroundColor(it) },
        onCustom = { onPickColour(selected ?: 0xFFFFFFFFL) { state.setBackgroundColor(it) } },
    )
}

/** Gradient presets, the two ends (each through the colour picker), and the direction in 45° steps. */
@Composable
private fun GradientControls(state: AppIconStudioState, onPickColour: (Long, (Long) -> Unit) -> Unit) {
    val gradient = state.spec.background as? UiIconLayer.Gradient ?: return
    val scheme = MaterialTheme.colorScheme

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
        contentPadding = PaddingValues(vertical = Ca.spacing.s1),
    ) {
        items(APP_ICON_GRADIENTS) { (start, end) ->
            val chosen = gradient.startArgb == start && gradient.endArgb == end
            Surface(
                onClick = { state.setBackgroundGradient(start, end, gradient.angle) },
                shape = CircleShape,
                color = Color.Transparent,
                border = BorderStroke(if (chosen) 3.dp else 1.dp, if (chosen) scheme.primary else scheme.outlineVariant),
                modifier = Modifier.size(32.dp),
            ) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(listOf(Color(start.toInt()), Color(end.toInt()))),
                        CircleShape,
                    ),
                )
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s3)) {
        // Each end reads the gradient as it is when its colour comes back, not as it was when the picker opened.
        ColourWell(stringResource(Res.string.appicon_gradient_start), gradient.startArgb) {
            onPickColour(gradient.startArgb) { picked ->
                (state.spec.background as? UiIconLayer.Gradient)?.let { state.setBackgroundGradient(picked, it.endArgb, it.angle) }
            }
        }
        ColourWell(stringResource(Res.string.appicon_gradient_end), gradient.endArgb) {
            onPickColour(gradient.endArgb) { picked ->
                (state.spec.background as? UiIconLayer.Gradient)?.let { state.setBackgroundGradient(it.startArgb, picked, it.angle) }
            }
        }
    }

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            FieldLabel(stringResource(Res.string.appicon_gradient_angle))
            Text(
                "${gradient.angle}°",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // A <gradient> only takes multiples of 45 on every Android version, so the slider only offers those.
        Slider(
            value = gradient.angle.toFloat(),
            onValueChange = { state.setBackgroundGradient(gradient.startArgb, gradient.endArgb, (it / 45f).roundToInt() * 45) },
            valueRange = 0f..315f,
            steps = 6,
        )
    }
}

/** A labelled colour swatch that opens the picker. */
@Composable
private fun ColourWell(label: String, argb: Long, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            Modifier.padding(horizontal = Ca.spacing.s3, vertical = Ca.spacing.s2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
        ) {
            Surface(
                shape = CircleShape,
                color = Color(argb.toInt()),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.size(20.dp),
                content = {},
            )
            Column {
                FieldLabel(label)
                Text(hexOf(argb), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun MonochromeCard(state: AppIconStudioState) {
    StudioCard {
        SectionHeader(stringResource(Res.string.appicon_monochrome), layerLabel(state.spec.monochrome))
        Row(horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2)) {
            FilterChip(
                selected = state.spec.monochrome != UiIconLayer.None,
                onClick = { state.monochromeFromForeground() },
                label = { Text(stringResource(Res.string.appicon_same_as_foreground)) },
            )
            FilterChip(
                selected = state.spec.monochrome == UiIconLayer.None,
                onClick = { state.clearMonochrome() },
                label = { Text(stringResource(Res.string.appicon_none)) },
            )
        }
    }
}

/**
 * Which outputs to write and the files that come of it, folded into one line: almost everyone keeps the
 * defaults, and the file list is long. What it would overwrite stays in the summary, folded or not.
 */
@Composable
private fun AdvancedCard(state: AppIconStudioState) {
    var open by remember { mutableStateOf(false) }
    val plan = state.plan
    val summary = listOfNotNull(
        plan?.let { stringResource(Res.string.appicon_files, it.files.size) },
        plan?.replacing?.takeIf { it.isNotEmpty() }?.let { stringResource(Res.string.appicon_replacing, it.size) },
    ).joinToString(" · ")
    StudioCard(contentPadding = PaddingValues(vertical = Ca.spacing.s1)) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(stringResource(Res.string.appicon_advanced), style = MaterialTheme.typography.titleMedium) },
            supportingContent = if (summary.isNotEmpty()) {
                { Text(summary, style = MaterialTheme.typography.bodySmall) }
            } else {
                null
            },
            trailingContent = { Icon(if (open) CaIcons.chevronUp else CaIcons.chevronDown, null, Modifier.size(18.dp)) },
            // The card clips to its shape, so the row's ripple stays inside the rounded corners.
            modifier = Modifier.fillMaxWidth().clickable { open = !open },
        )
        if (open) {
            ToggleItem(stringResource(Res.string.appicon_gen_rasters), state.spec.generateRasters) {
                state.toggleRasters()
            }
            HorizontalDivider(Modifier.padding(start = Ca.spacing.s4), color = MaterialTheme.colorScheme.outlineVariant)
            ToggleItem(stringResource(Res.string.appicon_gen_round), state.spec.generateRoundIcon) {
                state.toggleRoundIcon()
            }
            HorizontalDivider(Modifier.padding(start = Ca.spacing.s4), color = MaterialTheme.colorScheme.outlineVariant)
            ToggleItem(stringResource(Res.string.appicon_gen_playstore), state.spec.generatePlayStoreIcon) {
                state.togglePlayStoreIcon()
            }
            if (plan != null) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Ca.spacing.s4, vertical = Ca.spacing.s2),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    plan.files.forEach { file ->
                        Text(
                            file,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (file in plan.replacing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    plan.manifestChange?.let { Caption(stringResource(Res.string.appicon_manifest, it)) }
                }
            }
        }
    }
}

/** What the plan warns about, outside the folded card so a warning is never hidden behind a tap. */
@Composable
private fun WarningsCard(state: AppIconStudioState) {
    val warnings = state.plan?.warnings.orEmpty()
    if (warnings.isEmpty()) return
    StudioCard {
        warnings.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ApplyRow(state: AppIconStudioState) {
    Column(
        Modifier.widthIn(max = CARD_MAX).fillMaxWidth().padding(horizontal = Ca.spacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(
            onClick = { state.apply() },
            enabled = !state.applying,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.applying) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(CaIcons.check, null, Modifier.size(18.dp))
            }
            Spacer(Modifier.size(Ca.spacing.s2))
            Text(stringResource(Res.string.appicon_apply))
        }
    }
}

/**
 * What Apply just wrote, and the two things worth doing next: look at a file, or run the app and see the icon
 * on a launcher. The XML files are listed (and open in the editor); the density PNGs are counted.
 */
@Composable
private fun WrittenCard(state: AppIconStudioState, onOpenFile: ((String) -> Unit)?, onRunApp: (() -> Unit)?) {
    val (images, documents) = state.written.partition { it.endsWith(".png") || it.endsWith(".webp") }
    StudioCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2)) {
            Symbol(CaSymbols.checkCircle, null, size = 20.dp, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(
                    stringResource(Res.string.appicon_applied),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Caption(stringResource(Res.string.appicon_written, state.written.size))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            documents.forEach { path ->
                Surface(
                    onClick = { onOpenFile?.invoke(path) },
                    enabled = onOpenFile != null,
                    shape = MaterialTheme.shapes.small,
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(vertical = Ca.spacing.s2, horizontal = Ca.spacing.s1),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
                    ) {
                        Icon(CaIcons.file, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            shortPath(path),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (onOpenFile != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (images.isNotEmpty()) Caption(stringResource(Res.string.appicon_more_images, images.size))
        }
        if (onRunApp != null) {
            FilledTonalButton(onClick = onRunApp, modifier = Modifier.fillMaxWidth()) {
                Icon(CaIcons.play, null, Modifier.size(18.dp))
                Spacer(Modifier.size(Ca.spacing.s2))
                Text(stringResource(Res.string.appicon_run))
            }
        }
    }
}

/** `…/mipmap-anydpi-v26/ic_launcher.xml`: the folder says what each file is for, the rest is noise. */
private fun shortPath(path: String): String {
    val parts = path.split('/', '\\')
    return parts.takeLast(2).joinToString("/")
}

// ---------------------------------------------------------------------------
// The icon picker
// ---------------------------------------------------------------------------

/**
 * Choose a layer's artwork over the studio, with the same browser the Icon Manager uses: a tap picks it and
 * closes the sheet. Being a sheet rather than a trip to another screen is what keeps the rest of the icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IconPickerSheet(
    state: IconManagerState,
    title: String,
    onDismiss: () -> Unit,
    onPick: (UiIconLayer) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = Ca.spacing.s4).padding(bottom = Ca.spacing.s2),
            )
            IconBrowser(
                state = state,
                onClick = { item -> state.appIconLayerFor(item)?.let(onPick) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Small shared pieces
// ---------------------------------------------------------------------------

@Composable
private fun StudioCard(
    contentPadding: PaddingValues = PaddingValues(Ca.spacing.s4),
    content: @Composable () -> Unit,
) {
    Card(
        Modifier.widthIn(max = CARD_MAX).fillMaxWidth().padding(horizontal = Ca.spacing.s4),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
        ) {
            content()
        }
    }
}

@Composable
private fun SectionHeader(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ToggleItem(label: String, on: Boolean, onToggle: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        trailingContent = { CaSwitch(on) { onToggle() } },
    )
}

@Composable
private fun SwatchRow(
    selected: Long?,
    swatches: List<Long>,
    onNone: () -> Unit,
    onPick: (Long) -> Unit,
    /** Opens a colour picker for any colour; null offers the swatches only. */
    onCustom: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
        contentPadding = PaddingValues(vertical = Ca.spacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = onNone,
                label = { Text(stringResource(Res.string.appicon_none)) },
            )
        }
        items(swatches) { argb ->
            Surface(
                onClick = { onPick(argb) },
                shape = CircleShape,
                color = Color(argb.toInt()),
                border = BorderStroke(
                    if (selected == argb) 3.dp else 1.dp,
                    if (selected == argb) scheme.primary else scheme.outlineVariant,
                ),
                modifier = Modifier.size(32.dp),
                content = {},
            )
        }
        if (onCustom != null) {
            item {
                Surface(
                    onClick = onCustom,
                    shape = CircleShape,
                    color = scheme.surfaceContainerHighest,
                    border = BorderStroke(1.dp, scheme.outlineVariant),
                    modifier = Modifier.size(32.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Symbol(CaSymbols.palette, stringResource(Res.string.appicon_custom_colour), size = 18.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LabelledSlider(label: String, value: Float, from: Float, to: Float, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            FieldLabel(label)
            Text(
                "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = value.coerceIn(from, to), onValueChange = onChange, valueRange = from..to)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun layerLabel(layer: UiIconLayer): String = when (layer) {
    UiIconLayer.None -> stringResource(Res.string.appicon_none)
    is UiIconLayer.Color -> hexOf(layer.argb)
    is UiIconLayer.Gradient -> "${hexOf(layer.startArgb)} → ${hexOf(layer.endArgb)} · ${layer.angle}°"
    is UiIconLayer.RepoIcon -> layer.name
    is UiIconLayer.Resource -> layer.path.substringAfterLast('/').substringAfterLast('\\')
    is UiIconLayer.ImageFile -> layer.path.substringAfterLast('/').substringAfterLast('\\')
}

private fun hexOf(argb: Long): String = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

/** Near-white or near-black, where "a darker shade" makes a dull gradient. */
private fun isNeutralExtreme(argb: Long): Boolean {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (r > 0xE0 && g > 0xE0 && b > 0xE0) || (r < 0x20 && g < 0x20 && b < 0x20)
}

/** [argb] at roughly two thirds of its brightness: the far end of a gradient made from one colour. */
private fun darken(argb: Long): Long {
    fun channel(shift: Int) = (((argb shr shift) and 0xFF) * 2 / 3) shl shift
    return (argb and 0xFF000000L) or channel(16) or channel(8) or channel(0)
}
