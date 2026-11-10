package dev.ide.android.layouteditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.android.layout.LayoutAttributeInfo
import dev.ide.android.layout.LayoutElement
import dev.ide.android.layout.LayoutResource
import dev.ide.android.layout.LayoutValueCompletion
import dev.ide.android.layouteditor.generated.resources.Res
import dev.ide.android.layouteditor.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * What a property editor works with: the view, what it accepts, and how to change it. [write] takes a batch of
 * attribute writes (`null` removes) and applies them as ONE edit, so a margin split into four sides is one undo
 * step, not four.
 */
internal class PropertyContext(
    val element: LayoutElement,
    val catalog: Map<String, LayoutAttributeInfo>,
    val canEdit: Boolean,
    val write: (List<Pair<String, String?>>) -> Unit,
    val complete: suspend (attribute: String, field: String, caret: Int) -> LayoutValueCompletion,
    val resources: suspend (type: String) -> List<LayoutResource>,
    val themeColors: suspend () -> List<LayoutResource>,
) {
    fun accepts(name: String): Boolean = name in catalog || element.attribute(name) != null
    fun value(name: String): String? = element.attribute(name)?.value
    fun set(name: String, value: String?) = write(listOf(name to value))
}

/** The editor for [spec], sized for the strip's editor area. */
@Composable
internal fun PropertyEditorBody(spec: PropertySpec, pc: PropertyContext) {
    val attr = spec.writeAttribute(pc::accepts)
    when (spec.editor) {
        PropertyEditor.ID -> ValueField(
            initial = pc.value("android:id")?.substringAfter('/').orEmpty(),
            enabled = pc.canEdit,
            placeholder = stringResource(Res.string.le_attr_id_hint),
            complete = null,
            onCommit = { v -> pc.set("android:id", v.trim().substringAfter('/').takeIf { it.isNotEmpty() }?.let { "@+id/$it" }) },
        )
        PropertyEditor.SIZE -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DimensionChoice(stringResource(Res.string.le_prop_width), "android:layout_width", pc)
            DimensionChoice(stringResource(Res.string.le_prop_height), "android:layout_height", pc)
        }
        PropertyEditor.MARGIN -> BoxEditor(BOX_MARGIN, pc)
        PropertyEditor.PADDING -> BoxEditor(BOX_PADDING, pc)
        PropertyEditor.GRAVITY -> GravityEditor(attr, pc)
        PropertyEditor.WEIGHT -> SliderEditor(attr, pc, range = 0f..4f, steps = 7, format = { dp(it, "") })
        PropertyEditor.ALPHA -> SliderEditor(attr, pc, range = 0f..1f, steps = 19, format = { ((it * 100).roundToInt() / 100f).toString() })
        PropertyEditor.IMAGE -> ImagePicker(attr, pc)
        PropertyEditor.SCALE_TYPE -> ScaleTypePicker(attr, pc)
        PropertyEditor.COLOR -> ColorPicker(attr, pc)
        PropertyEditor.TEXT -> TextEditor(attr, pc)
        PropertyEditor.TEXT_SIZE -> PresetEditor(attr, pc, listOf("12sp", "14sp", "16sp", "20sp", "24sp", "34sp"), stringResource(Res.string.le_attr_text_size_hint))
        PropertyEditor.CHOICE -> ChoiceEditor(attr, pc)
        PropertyEditor.BOOLEAN -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val v = pc.value(attr)
            for (b in listOf("true", "false")) ChoiceChip(b, selected = v == b, enabled = pc.canEdit) { pc.set(attr, b) }
            if (v != null) ChoiceChip(stringResource(Res.string.le_attr_unset), selected = false, enabled = pc.canEdit) { pc.set(attr, null) }
        }
        PropertyEditor.PLAIN -> ValueField(pc.value(attr).orEmpty(), pc.canEdit, "", { f, c -> pc.complete(attr, f, c) }) { pc.set(attr, it.ifBlank { null }) }
    }
}

/** wrap / match / a value, for a layout size. */
@Composable
private fun DimensionChoice(label: String, attr: String, pc: PropertyContext) {
    val v = pc.value(attr).orEmpty()
    val keywords = listOf("wrap_content", "match_parent")
    var custom by remember(v) { mutableStateOf(v.isNotEmpty() && v !in keywords && v != "fill_parent") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SegmentedChoice(
            options = listOf("wrap", "match", stringResource(Res.string.le_attr_custom)),
            selected = when { custom -> 2; v == "wrap_content" -> 0; v == "match_parent" || v == "fill_parent" -> 1; else -> -1 },
            enabled = pc.canEdit,
            onSelect = { i -> if (i < 2) { custom = false; pc.set(attr, keywords[i]) } else custom = true },
        )
        if (custom) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (n in listOf(0, 48, 56, 120)) ChoiceChip("${n}dp", selected = v == "${n}dp", enabled = pc.canEdit) { pc.set(attr, "${n}dp") }
            }
            ValueField(v.takeIf { it !in keywords }.orEmpty(), pc.canEdit, stringResource(Res.string.le_attr_dimension_hint), { f, c -> pc.complete(attr, f, c) }) {
                if (it.isNotBlank()) pc.set(attr, it)
            }
        }
    }
}

private enum class Side { Start, Top, End, Bottom }

/**
 * A margin or padding as the box it is: the four sides around the view, each showing its value. Tap sides to
 * pick which ones a value goes to (all four to start with), then a preset or a typed value sets them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxEditor(box: BoxAttributes, pc: PropertyContext) {
    val current = readBox(pc.element, box)
    var picked by remember { mutableStateOf(Side.entries.toSet()) }
    fun valueOf(s: Side) = when (s) { Side.Start -> current.start; Side.Top -> current.top; Side.End -> current.end; Side.Bottom -> current.bottom }
    fun apply(value: String?) {
        fun pick(s: Side) = if (s in picked) value else valueOf(s)
        val next = BoxValues(pick(Side.Start), pick(Side.Top), pick(Side.End), pick(Side.Bottom))
        pc.write(boxWrites(pc.element, box, next))
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The diagram: four side buttons around the view.
        Box(Modifier.fillMaxWidth().height(132.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(width = 112.dp, height = 56.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) { Symbol(glyphFor(pc.element.tag), null, size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            SideButton(valueOf(Side.Top), Side.Top in picked, Modifier.align(Alignment.TopCenter)) { picked = toggle(picked, Side.Top) }
            SideButton(valueOf(Side.Bottom), Side.Bottom in picked, Modifier.align(Alignment.BottomCenter)) { picked = toggle(picked, Side.Bottom) }
            SideButton(valueOf(Side.Start), Side.Start in picked, Modifier.align(Alignment.CenterStart)) { picked = toggle(picked, Side.Start) }
            SideButton(valueOf(Side.End), Side.End in picked, Modifier.align(Alignment.CenterEnd)) { picked = toggle(picked, Side.End) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceChip(stringResource(Res.string.le_box_all), selected = picked.size == 4, enabled = true) { picked = Side.entries.toSet() }
            ChoiceChip(stringResource(Res.string.le_box_horizontal), selected = picked == setOf(Side.Start, Side.End), enabled = true) { picked = setOf(Side.Start, Side.End) }
            ChoiceChip(stringResource(Res.string.le_box_vertical), selected = picked == setOf(Side.Top, Side.Bottom), enabled = true) { picked = setOf(Side.Top, Side.Bottom) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in listOf(0, 4, 8, 12, 16, 24, 32)) ChoiceChip("$n", selected = false, enabled = pc.canEdit && picked.isNotEmpty()) { apply("${n}dp") }
            ChoiceChip(stringResource(Res.string.le_attr_unset), selected = false, enabled = pc.canEdit && picked.isNotEmpty()) { apply(null) }
        }
        ValueField("", pc.canEdit && picked.isNotEmpty(), stringResource(Res.string.le_attr_dimension_hint), { f, c -> pc.complete(box.shorthand, f, c) }) {
            if (it.isNotBlank()) apply(it.trim())
        }
    }
}

private fun toggle(set: Set<Side>, s: Side) = if (s in set) set - s else set + s

@Composable
private fun SideButton(value: String?, picked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg = if (picked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (picked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier.size(width = 64.dp, height = 34.dp).clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            value?.let { dpNumber(it)?.let { n -> dp(n, "") } ?: it.substringAfter('/') } ?: "0",
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
            color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Gravity as a 3×3 grid of where the content (or the view) sits. */
@Composable
private fun GravityEditor(attr: String, pc: PropertyContext) {
    val v = pc.value(attr)
    val cell = gravityCell(v)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (r in 0..2) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in 0..2) {
                    val on = cell == (r to c)
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable(enabled = pc.canEdit) { pc.set(attr, gravityFor(r, c)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(if (on) 12.dp else 8.dp).clip(CircleShape)
                                .background(if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline),
                        )
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(v ?: stringResource(Res.string.le_attr_default), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            if (v != null) ChoiceChip(stringResource(Res.string.le_attr_unset), selected = false, enabled = pc.canEdit) { pc.set(attr, null) }
        }
    }
}

/** A number on a slider, written when the drag ends. */
@Composable
private fun SliderEditor(attr: String, pc: PropertyContext, range: ClosedFloatingPointRange<Float>, steps: Int, format: (Float) -> String) {
    val current = pc.value(attr)?.toFloatOrNull()
    var pos by remember(current) { mutableFloatStateOf(current ?: range.start) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (current == null && pos == range.start) stringResource(Res.string.le_attr_default) else format(pos),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
            )
            if (current != null) ChoiceChip(stringResource(Res.string.le_attr_unset), selected = false, enabled = pc.canEdit) { pc.set(attr, null) }
        }
        Slider(
            value = pos,
            onValueChange = { pos = it },
            onValueChangeFinished = { pc.set(attr, format(pos)) },
            valueRange = range,
            steps = steps,
            enabled = pc.canEdit,
        )
    }
}

/** Fixed presets plus a typed value, for a size like text size. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetEditor(attr: String, pc: PropertyContext, presets: List<String>, hint: String) {
    val v = pc.value(attr)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in presets) ChoiceChip(p, selected = v == p, enabled = pc.canEdit) { pc.set(attr, p) }
        }
        ValueField(v.orEmpty(), pc.canEdit, hint, { f, c -> pc.complete(attr, f, c) }) { pc.set(attr, it.ifBlank { null }) }
    }
}

/** An enum (one choice) or flags (several) attribute, as chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceEditor(attr: String, pc: PropertyContext) {
    val (values, flags) = choicesOf(pc.catalog[attr])
    val v = pc.value(attr)
    val on = v?.split('|')?.map { it.trim() }?.toSet().orEmpty()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (option in values) ChoiceChip(option, selected = option in on, enabled = pc.canEdit) {
            if (!flags) pc.set(attr, option)
            else {
                val next = if (option in on) on - option else on + option
                pc.set(attr, values.filter { it in next }.joinToString("|").ifEmpty { null })
            }
        }
        if (v != null) ChoiceChip(stringResource(Res.string.le_attr_unset), selected = false, enabled = pc.canEdit) { pc.set(attr, null) }
    }
}

/** Text: typed, or one of the project's string resources (shown with their values). */
@Composable
private fun TextEditor(attr: String, pc: PropertyContext) {
    val v = pc.value(attr)
    var strings by remember { mutableStateOf<List<LayoutResource>>(emptyList()) }
    LaunchedEffect(Unit) { strings = runCatching { pc.resources("string") }.getOrDefault(emptyList()) }
    var query by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ValueField(v.orEmpty(), pc.canEdit, "", { f, c -> pc.complete(attr, f, c) }) { pc.set(attr, it.ifEmpty { null }) }
        if (strings.isNotEmpty()) {
            SectionLabel(stringResource(Res.string.le_res_strings))
            if (strings.size > 6) SearchField(query, { query = it }, stringResource(Res.string.le_res_search))
            val q = query.trim().lowercase()
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                for (s in strings.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.value?.lowercase()?.contains(q) == true }) {
                    val on = v == s.reference
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable(enabled = pc.canEdit) { pc.set(attr, s.reference) }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(s.name, style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.45f))
                        Text(s.value.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.55f))
                    }
                }
            }
        }
    }
}

/** Colors: the theme's roles first (they follow dark mode), then the project's color resources, then any value. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPicker(attr: String, pc: PropertyContext) {
    val v = pc.value(attr)
    var theme by remember { mutableStateOf<List<LayoutResource>>(emptyList()) }
    var colors by remember { mutableStateOf<List<LayoutResource>>(emptyList()) }
    LaunchedEffect(Unit) {
        theme = runCatching { pc.themeColors() }.getOrDefault(emptyList())
        colors = runCatching { pc.resources("color") }.getOrDefault(emptyList())
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (theme.isNotEmpty()) {
            SectionLabel(stringResource(Res.string.le_res_theme_colors))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in theme) Swatch(c, selected = v == c.reference || v == "?${c.name}", enabled = pc.canEdit) { pc.set(attr, c.reference) }
            }
        }
        if (colors.isNotEmpty()) {
            SectionLabel(stringResource(Res.string.le_res_colors))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in colors) Swatch(c, selected = v == c.reference, enabled = pc.canEdit) { pc.set(attr, c.reference) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(parseHexColor(v.orEmpty()) ?: MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
            )
            Box(Modifier.weight(1f)) {
                ValueField(v.orEmpty(), pc.canEdit, stringResource(Res.string.le_attr_color_hint), { f, c -> pc.complete(attr, f, c) }) { pc.set(attr, it.ifBlank { null }) }
            }
            if (v != null) ChoiceChip(stringResource(Res.string.le_attr_unset), selected = false, enabled = pc.canEdit) { pc.set(attr, null) }
        }
    }
}

@Composable
private fun Swatch(c: LayoutResource, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(64.dp).clickable(enabled = enabled, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(c.argb?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        Text(
            c.name.removePrefix("color"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** The project's drawables and mipmaps as a thumbnail grid; vector drawables show a placeholder glyph. */
@Composable
private fun ImagePicker(attr: String, pc: PropertyContext) {
    val v = pc.value(attr)
    var images by remember { mutableStateOf<List<LayoutResource>?>(null) }
    LaunchedEffect(Unit) {
        images = runCatching { pc.resources("drawable") + pc.resources("mipmap") }.getOrDefault(emptyList())
    }
    var query by remember { mutableStateOf("") }
    val list = images
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (list == null) {
            Text(stringResource(Res.string.le_attrs_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        if (list.isEmpty()) {
            Text(stringResource(Res.string.le_res_no_images), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (list.size > 8) SearchField(query, { query = it }, stringResource(Res.string.le_res_search))
        val q = query.trim().lowercase()
        LazyVerticalGrid(GridCells.Adaptive(76.dp), Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list.filter { q.isEmpty() || it.name.lowercase().contains(q) }, key = { it.reference }) { r ->
                val on = v == r.reference
                Column(
                    Modifier.clip(RoundedCornerShape(14.dp))
                        .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable(enabled = pc.canEdit) { pc.set(attr, r.reference) }.padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Thumbnail(r, Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(r.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (v != null) ChoiceChip(stringResource(Res.string.le_attr_unset), selected = false, enabled = pc.canEdit) { pc.set(attr, null) }
    }
}

@Composable
private fun Thumbnail(r: LayoutResource, modifier: Modifier) {
    val file = r.file
    val raster = file != null && file.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp")
    var bitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    if (raster) LaunchedEffect(file) { bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { decodeImageFile(file!!, 160) } }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b, null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
        else Symbol(if (raster) Sym.image else Sym.photoLibrary, null, size = 24.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Each scale type as a small picture of what it does to an image in its frame. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScaleTypePicker(attr: String, pc: PropertyContext) {
    val values = pc.catalog[attr]?.enumValues?.takeIf { it.isNotEmpty() }
        ?: listOf("center", "centerCrop", "centerInside", "fitCenter", "fitEnd", "fitStart", "fitXY", "matrix")
    val v = pc.value(attr)
    val frame = MaterialTheme.colorScheme.outline
    val image = MaterialTheme.colorScheme.primary
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in values) {
            val on = v == t
            Column(
                Modifier.width(84.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(enabled = pc.canEdit) { pc.set(attr, t) }.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Canvas(Modifier.size(width = 56.dp, height = 40.dp)) {
                    val f = Rect(Offset.Zero, size)
                    val r = scaleTypeRect(t, f, imageAspect = 0.75f)
                    clipRect(f.left, f.top, f.right, f.bottom) { drawRect(image.copy(alpha = 0.55f), r.topLeft, r.size) }
                    drawRect(frame, f.topLeft, f.size, style = Stroke(1.5.dp.toPx()))
                }
                Text(t, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** Where an image of aspect [imageAspect] (w/h) lands in [frame] under scale type [t]; small images for `center`. */
internal fun scaleTypeRect(t: String, frame: Rect, imageAspect: Float): Rect {
    val fw = frame.width
    val fh = frame.height
    fun fitted(cover: Boolean): Size {
        val s = if (cover) maxOf(fw / imageAspect, fh) else minOf(fw / imageAspect, fh)
        return Size(s * imageAspect, s)
    }
    fun at(size: Size, x: Float, y: Float) = Rect(Offset(frame.left + x, frame.top + y), size)
    return when (t) {
        "fitXY" -> frame
        "centerCrop" -> fitted(true).let { at(it, (fw - it.width) / 2, (fh - it.height) / 2) }
        "fitStart" -> fitted(false).let { at(it, 0f, 0f) }
        "fitEnd" -> fitted(false).let { at(it, fw - it.width, fh - it.height) }
        "center", "matrix" -> Size(fh * 1.3f * imageAspect, fh * 1.3f).let {
            if (t == "matrix") at(it, 0f, 0f) else at(it, (fw - it.width) / 2, (fh - it.height) / 2)
        }
        // fitCenter, centerInside (an image larger than its frame, so both shrink to fit)
        else -> fitted(false).let { at(it, (fw - it.width) / 2, (fh - it.height) / 2) }
    }
}

