package dev.ide.ui.editor.blocks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.ide.ui.backend.UiTextEdit
import dev.ide.ui.components.CaDropdownMenu
import dev.ide.ui.components.CaMenuItem
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.block_modifier_add
import dev.ide.ui.generated.resources.block_modifier_args
import dev.ide.ui.generated.resources.block_modifier_empty
import dev.ide.ui.generated.resources.block_modifier_fills
import dev.ide.ui.generated.resources.block_modifier_group_behaviour
import dev.ide.ui.generated.resources.block_modifier_group_drawing
import dev.ide.ui.generated.resources.block_modifier_group_size
import dev.ide.ui.generated.resources.block_modifier_group_spacing
import dev.ide.ui.generated.resources.block_modifier_preview
import dev.ide.ui.generated.resources.block_modifier_title
import dev.ide.ui.generated.resources.block_move_down
import dev.ide.ui.generated.resources.block_move_up
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.delete
import dev.ide.ui.generated.resources.save
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import org.jetbrains.compose.resources.stringResource

/**
 * The editor for a Compose `Modifier` chain: a schematic of the box it describes on top, then its links in
 * order, each with its glyph, its arguments (completed like the forms' value fields), and controls to move
 * or remove it, and an Add menu of common modifiers. Save writes the chain back as one expression.
 */
@Composable
internal fun ModifierSheet(
    initial: ModifierChain,
    group: String,
    suggest: Suggest,
    onSave: (ModifierChain, List<UiTextEdit>) -> Unit,
    onDismiss: () -> Unit,
    /** Completion for link `i`'s arguments, asked at that call in the file. */
    suggestArgs: (suspend (ModifierChain, Int, String) -> List<Suggestion>)? = null,
    /** Link `i`'s parameter labels (`fraction: Float = …`), from parameter info. */
    paramsOf: (suspend (ModifierChain, Int) -> List<String>?)? = null,
) {
    val links = remember { mutableStateListOf<ModifierLink>().apply { addAll(initial.links) } }
    val extra = remember { mutableStateListOf<UiTextEdit>() }
    var adding by remember { mutableStateOf(false) }
    val chain = initial.copy(links = links.toList())
    val scheme = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(Ca.radius.sheet)).background(scheme.surfaceContainerHigh)
                .padding(20.dp).heightIn(max = 680.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Modifier, scheme.primary, 18)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.block_modifier_title), style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
            }
            SchematicPreview(chain)
            if (links.isEmpty()) {
                Text(stringResource(Res.string.block_modifier_empty), style = MaterialTheme.typography.bodyMedium, color = scheme.outline)
            }
            links.forEachIndexed { i, link ->
                val rowSuggest: Suggest = suggestArgs?.let { f -> { _, text, _ -> f(chain, i, text) } } ?: suggest
                LinkRow(
                    link, group, rowSuggest,
                    params = paramsOf?.let { f -> { f(chain, i) } },
                    onArgs = { links[i] = links[i].copy(args = it) },
                    onExtra = { extra += it },
                    onUp = if (i > 0) ({ val l = links.removeAt(i); links.add(i - 1, l) }) else null,
                    onDown = if (i < links.lastIndex) ({ val l = links.removeAt(i); links.add(i + 1, l) }) else null,
                    onRemove = { links.removeAt(i) },
                )
            }
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(Ca.radius.control)).clickable { adding = true }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(CaIcons.plus, null, Modifier.size(16.dp), tint = scheme.primary)
                    Text(stringResource(Res.string.block_modifier_add), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                }
                CaDropdownMenu(expanded = adding, onDismissRequest = { adding = false }, iconLed = true) {
                    for ((groupName, presets) in MODIFIER_PRESETS) {
                        Text(
                            presetGroupLabel(groupName), style = MaterialTheme.typography.labelSmall, color = scheme.outline,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                        )
                        for (preset in presets) {
                            CaMenuItem(
                                label = preset.name,
                                supporting = preset.code().removePrefix(".").removePrefix(preset.name).ifEmpty { null },
                                onClick = { adding = false; links += preset },
                                leading = { GlyphIcon(glyphFor(preset.name), scheme.onSurfaceVariant, 18) },
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
                TextButton(onClick = { onSave(chain, extra.distinct()) }) { Text(stringResource(Res.string.save)) }
            }
        }
    }
}

@Composable
private fun presetGroupLabel(key: String): String = when (key) {
    "Size" -> stringResource(Res.string.block_modifier_group_size)
    "Spacing" -> stringResource(Res.string.block_modifier_group_spacing)
    "Drawing" -> stringResource(Res.string.block_modifier_group_drawing)
    else -> stringResource(Res.string.block_modifier_group_behaviour)
}

@Composable
private fun LinkRow(
    link: ModifierLink, group: String, suggest: Suggest,
    onArgs: (String) -> Unit, onExtra: (List<UiTextEdit>) -> Unit,
    onUp: (() -> Unit)?, onDown: (() -> Unit)?, onRemove: () -> Unit,
    params: (suspend () -> List<String>?)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    // What this link takes, from parameter info: shown as the field's placeholder and under it.
    var labels by remember { mutableStateOf<List<String>?>(null) }
    androidx.compose.runtime.LaunchedEffect(link.name) { labels = params?.invoke() }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.md)).background(scheme.surfaceContainer)
            .padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.height(32.dp), contentAlignment = Alignment.Center) { GlyphIcon(glyphFor(link.name), scheme.onSurfaceVariant, 16) }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.height(32.dp).widthIn(min = 64.dp), contentAlignment = Alignment.CenterStart) {
            Text(link.name, style = Ide.type.codeSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(top = 1.dp)) {
            val hint = labels?.takeIf { it.isNotEmpty() }?.joinToString(", ")
            when {
                link.args != null -> SuggestField(hint ?: stringResource(Res.string.block_modifier_args), link.args, onArgs, FieldKind.Value, group, suggest, onExtra, compact = true, language = KOTLIN)
                link.lambda != null -> Box(Modifier.height(30.dp), contentAlignment = Alignment.CenterStart) { Text("{ \u2026 }", style = Ide.type.codeSmall, color = scheme.outline) }
            }
            if (hint != null && !link.args.isNullOrEmpty()) {
                Text(
                    remember(hint) { dev.ide.ui.editor.highlight(hint, KOTLIN, dev.ide.ui.theme.LightSyntaxColors) }, style = Ide.type.codeSmall,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp, top = 3.dp).graphicsLayer { alpha = 0.7f },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButtonCa(CaIcons.chevronUp, stringResource(Res.string.block_move_up), { onUp?.invoke() }, iconSize = 15, boxSize = 30, tint = if (onUp == null) scheme.outlineVariant else null)
            IconButtonCa(CaIcons.chevronDown, stringResource(Res.string.block_move_down), { onDown?.invoke() }, iconSize = 15, boxSize = 30, tint = if (onDown == null) scheme.outlineVariant else null)
            IconButtonCa(CaIcons.close, stringResource(Res.string.delete), onRemove, iconSize = 15, boxSize = 30)
        }
    }
}

@Composable
internal fun GlyphIcon(glyph: Glyph, color: Color, sizeDp: Int) {
    Canvas(Modifier.size(sizeDp.dp)) { drawGlyph(glyph, 0f, 0f, size.minDimension, color) }
}

/** The box the chain describes, drawn to fit: fills and strokes in their shapes, padding as dashed insets. */
@Composable
private fun SchematicPreview(chain: ModifierChain) {
    val scheme = MaterialTheme.colorScheme
    val sch = remember(chain) { schematicOf(chain) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(Ca.radius.md)).background(scheme.surfaceContainerLowest)) {
            Canvas(Modifier.fillMaxWidth().height(170.dp)) {
                val pad = 16.dp.toPx()
                val availW = size.width - pad * 2
                val availH = size.height - pad * 2
                val boxW = if (sch.fillsWidth) availW / density else sch.width
                val boxH = if (sch.fillsHeight) availH / density else sch.height
                val scale = minOf(availW / (boxW * density), availH / (boxH * density), 1.6f) * density
                val ox = (size.width - boxW * scale) / 2
                val oy = (size.height - boxH * scale) / 2
                // The box's own bounds, as a faint outline.
                drawRect(scheme.outlineVariant, Offset(ox, oy), Size(boxW * scale, boxH * scale), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
                for (op in sch.ops) when (op) {
                    is SchematicOp.Fill -> shapeAt(op.x, op.y, op.w, op.h, op.shape, op.radius, ox, oy, scale, sch, boxW, boxH) { path ->
                        drawPath(path, Color(op.argb.toInt()).copy(alpha = Color(op.argb.toInt()).alpha * sch.alpha))
                    }
                    is SchematicOp.Stroke -> shapeAt(op.x, op.y, op.w, op.h, op.shape, op.radius, ox, oy, scale, sch, boxW, boxH) { path ->
                        drawPath(path, Color(op.argb.toInt()), style = Stroke(op.width * scale))
                    }
                    is SchematicOp.Inset -> {}
                    is SchematicOp.Content -> {
                        val (x, y, w, h) = fit(op.x, op.y, op.w, op.h, sch, boxW, boxH)
                        drawRoundRect(
                            scheme.primary.copy(alpha = 0.9f), Offset(ox + x * scale, oy + y * scale), Size(w * scale, h * scale),
                            CornerRadius(3.dp.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))),
                        )
                    }
                }
            }
        }
        val dims = when {
            sch.fillsWidth && sch.fillsHeight -> stringResource(Res.string.block_modifier_fills)
            else -> "${if (sch.fillsWidth) "↔" else sch.width.toInt()} × ${if (sch.fillsHeight) "↕" else sch.height.toInt()} dp"
        }
        Text("$dims · ${stringResource(Res.string.block_modifier_preview)}", style = MaterialTheme.typography.labelSmall, color = scheme.outline)
    }
}

/** Map a schematic rect onto a filled-width/height box: offsets keep, sizes stretch with the box. */
private fun fit(x: Float, y: Float, w: Float, h: Float, sch: Schematic, boxW: Float, boxH: Float): List<Float> =
    listOf(x, y, w + (boxW - sch.width).coerceAtLeast(0f), h + (boxH - sch.height).coerceAtLeast(0f))

private fun DrawScope.shapeAt(
    x0: Float, y0: Float, w0: Float, h0: Float, shape: Glyph, radius: Float,
    ox: Float, oy: Float, scale: Float, sch: Schematic, boxW: Float, boxH: Float, draw: (Path) -> Unit,
) {
    val (x, y, w, h) = fit(x0, y0, w0, h0, sch, boxW, boxH)
    val l = ox + x * scale
    val t = oy + y * scale
    val pw = w * scale
    val ph = h * scale
    val path = Path()
    when (shape) {
        Glyph.ShapeCircle -> path.addOval(androidx.compose.ui.geometry.Rect(l, t, l + pw, t + ph))
        Glyph.ShapeCut -> {
            val c = (radius * scale).coerceAtMost(minOf(pw, ph) / 2)
            path.moveTo(l + c, t); path.lineTo(l + pw - c, t); path.lineTo(l + pw, t + c); path.lineTo(l + pw, t + ph - c)
            path.lineTo(l + pw - c, t + ph); path.lineTo(l + c, t + ph); path.lineTo(l, t + ph - c); path.lineTo(l, t + c); path.close()
        }
        Glyph.ShapeRounded -> path.addRoundRect(androidx.compose.ui.geometry.RoundRect(l, t, l + pw, t + ph, CornerRadius((radius * scale).coerceAtMost(minOf(pw, ph) / 2))))
        else -> path.addRect(androidx.compose.ui.geometry.Rect(l, t, l + pw, t + ph))
    }
    draw(path)
}

private val KOTLIN = dev.ide.ui.editor.languageFor("a.kt")
