package dev.ide.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiIconLayer
import dev.ide.ui.components.CaSwitch
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.editor.preview.AppIconRaster.drawAppIcon
import dev.ide.ui.editor.preview.IconMask
import dev.ide.ui.editor.preview.drawCheckerboard
import dev.ide.ui.editor.preview.drawUiDrawable
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.appicon_apply
import dev.ide.ui.generated.resources.appicon_applied
import dev.ide.ui.generated.resources.appicon_background
import dev.ide.ui.generated.resources.appicon_choose_icon
import dev.ide.ui.generated.resources.appicon_choose_image
import dev.ide.ui.generated.resources.appicon_current
import dev.ide.ui.generated.resources.appicon_files
import dev.ide.ui.generated.resources.appicon_foreground
import dev.ide.ui.generated.resources.appicon_gen_playstore
import dev.ide.ui.generated.resources.appicon_gen_rasters
import dev.ide.ui.generated.resources.appicon_gen_round
import dev.ide.ui.generated.resources.appicon_manifest
import dev.ide.ui.generated.resources.appicon_mask_circle
import dev.ide.ui.generated.resources.appicon_mask_rounded
import dev.ide.ui.generated.resources.appicon_mask_square
import dev.ide.ui.generated.resources.appicon_mask_squircle
import dev.ide.ui.generated.resources.appicon_module
import dev.ide.ui.generated.resources.appicon_monochrome
import dev.ide.ui.generated.resources.appicon_no_module
import dev.ide.ui.generated.resources.appicon_none
import dev.ide.ui.generated.resources.appicon_offset_x
import dev.ide.ui.generated.resources.appicon_offset_y
import dev.ide.ui.generated.resources.appicon_outputs
import dev.ide.ui.generated.resources.appicon_preview
import dev.ide.ui.generated.resources.appicon_replacing
import dev.ide.ui.generated.resources.appicon_same_as_foreground
import dev.ide.ui.generated.resources.appicon_scale
import dev.ide.ui.generated.resources.appicon_themed
import dev.ide.ui.generated.resources.appicon_title
import dev.ide.ui.generated.resources.appicon_use_project_drawable
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/** Cards stay this wide at most and centre in the window, matching the Settings hub. */
private val CARD_MAX = 620.dp

/**
 * The app-icon studio: compose a launcher icon from a background and a foreground layer, see it under every
 * launcher mask (and as an Android 13+ themed icon), then write the adaptive XML, the density PNGs, the round
 * variant and the Play Store image, and point the manifest at them.
 *
 * The preview draws the exact models the generator will write, through the same code path that rasterises the
 * PNGs, so what is shown here is what lands in the project.
 */
@Composable
fun AppIconStudioScreen(
    backend: IdeBackend,
    onBack: () -> Unit,
    /** Return to the Icon Manager to pick a different foreground icon. */
    onChooseIcon: () -> Unit = {},
    seedRepoId: String? = null,
    seedIconName: String? = null,
    fileActions: FileActions = FileActions.None,
) {
    val state = rememberAppIconStudioState(backend, seedRepoId, seedIconName)
    val snackbar = remember { SnackbarHostState() }

    state.message?.let { text ->
        LaunchedEffect(text) {
            snackbar.showSnackbar(text)
            state.dismissMessage()
        }
    }

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

            else -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Ca.spacing.s3),
            ) {
                Spacer(Modifier.height(Ca.spacing.s2))
                PreviewCard(state)
                ForegroundCard(state, onChooseIcon, fileActions)
                BackgroundCard(state)
                MonochromeCard(state)
                OutputsCard(state)
                PlanCard(state)
                ApplyRow(state)
                Spacer(Modifier.height(Ca.spacing.s8))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------

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
                // What the module ships today, so a replacement is a comparison rather than a leap.
                LabelledTile(stringResource(Res.string.appicon_current), size = 64.dp) {
                    Canvas(Modifier.fillMaxSize()) { drawCheckerboard(cell = 6f) }
                    state.current?.current?.let { drawable ->
                        Canvas(Modifier.fillMaxSize()) { drawUiDrawable(drawable, Offset.Zero, size) }
                    }
                }
                LabelledTile(stringResource(Res.string.appicon_preview), size = 112.dp) {
                    Canvas(Modifier.fillMaxSize()) { drawCheckerboard(cell = 8f) }
                    state.preview?.let { preview ->
                        Canvas(Modifier.fillMaxSize()) {
                            drawAppIcon(preview, state.mask, monochrome = state.showThemed)
                        }
                    }
                }
            }

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
                        label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }

            FilterChip(
                selected = state.showThemed,
                onClick = { state.toggleThemed() },
                label = { Text(stringResource(Res.string.appicon_themed)) },
                leadingIcon = { Icon(CaIcons.moon, null, Modifier.size(16.dp)) },
            )

            state.current?.moduleName?.let {
                Caption("${stringResource(Res.string.appicon_module)}: $it")
            }
        }
    }
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

        if (state.projectIcons.isNotEmpty()) {
            FieldLabel(stringResource(Res.string.appicon_use_project_drawable))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
                contentPadding = PaddingValues(vertical = Ca.spacing.s1),
            ) {
                items(state.projectIcons, key = { it.resType + "/" + it.name }) { icon ->
                    val config = icon.configurations.firstOrNull { it.qualifier.isEmpty() }
                        ?: icon.configurations.first()
                    FilterChip(
                        selected = (state.spec.foreground as? UiIconLayer.Resource)?.path == config.path,
                        onClick = { state.setForegroundResource(config.path) },
                        label = { Text(icon.name, maxLines = 1) },
                    )
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

@Composable
private fun BackgroundCard(state: AppIconStudioState) {
    StudioCard {
        SectionHeader(stringResource(Res.string.appicon_background), layerLabel(state.spec.background))
        SwatchRow(
            selected = (state.spec.background as? UiIconLayer.Color)?.argb,
            swatches = APP_ICON_BACKGROUNDS,
            onNone = { state.clearBackground() },
            onPick = { state.setBackgroundColor(it) },
        )
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

@Composable
private fun OutputsCard(state: AppIconStudioState) {
    StudioCard(contentPadding = PaddingValues(vertical = Ca.spacing.s2)) {
        Text(
            stringResource(Res.string.appicon_outputs),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Ca.spacing.s4, vertical = Ca.spacing.s2),
        )
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
    }
}

@Composable
private fun PlanCard(state: AppIconStudioState) {
    val plan = state.plan ?: return
    StudioCard {
        Text(
            stringResource(Res.string.appicon_files, plan.files.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (plan.replacing.isNotEmpty()) {
            Text(
                stringResource(Res.string.appicon_replacing, plan.replacing.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        plan.manifestChange?.let { Caption(stringResource(Res.string.appicon_manifest, it)) }
        plan.warnings.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ApplyRow(state: AppIconStudioState) {
    Column(
        Modifier.widthIn(max = CARD_MAX).fillMaxWidth().padding(horizontal = Ca.spacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
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
        if (state.applied) {
            Text(
                stringResource(Res.string.appicon_applied),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
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
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
        contentPadding = PaddingValues(vertical = Ca.spacing.s1),
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = onNone,
                label = { Text(stringResource(Res.string.appicon_none)) },
            )
        }
        items(swatches) { argb ->
            val scheme = MaterialTheme.colorScheme
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
    is UiIconLayer.Color -> "#" + (layer.argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
    is UiIconLayer.RepoIcon -> layer.name
    is UiIconLayer.Resource -> layer.path.substringAfterLast('/').substringAfterLast('\\')
    is UiIconLayer.ImageFile -> layer.path.substringAfterLast('/').substringAfterLast('\\')
}
