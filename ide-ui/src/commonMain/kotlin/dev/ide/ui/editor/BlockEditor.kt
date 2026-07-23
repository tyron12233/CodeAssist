package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.SymbolHit
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.backend.UiBlockEdit
import dev.ide.ui.backend.UiBlockNode
import dev.ide.ui.backend.UiBlockPart
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiTextEdit
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.RangeEdit
import dev.ide.ui.editor.blocks.BlockCat
import dev.ide.ui.editor.blocks.BlockMetrics
import dev.ide.ui.editor.blocks.DragGhost
import dev.ide.ui.editor.blocks.DragPayload
import dev.ide.ui.editor.blocks.DragState
import dev.ide.ui.editor.blocks.DropDescriptor
import dev.ide.ui.editor.blocks.InlineInput
import dev.ide.ui.editor.blocks.ValueShape
import dev.ide.ui.editor.blocks.blockColor
import dev.ide.ui.editor.blocks.canvasOrigin
import dev.ide.ui.editor.blocks.dragSource
import dev.ide.ui.editor.blocks.dropZone
import dev.ide.ui.editor.blocks.rememberBlockShape
import dev.ide.ui.editor.blocks.rememberCBlockShape
import dev.ide.ui.editor.blocks.rememberValueShape
import dev.ide.ui.editor.blocks.valueShapeOf
import dev.ide.ui.editor.blocks.valueShapePadding
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.block_add_a_block
import dev.ide.ui.generated.resources.block_add_block
import dev.ide.ui.generated.resources.block_block_count
import dev.ide.ui.generated.resources.block_button
import dev.ide.ui.generated.resources.block_cannot_project
import dev.ide.ui.generated.resources.block_drop_to_delete
import dev.ide.ui.generated.resources.block_duplicate
import dev.ide.ui.generated.resources.block_edit_expression
import dev.ide.ui.generated.resources.block_focus_hint
import dev.ide.ui.generated.resources.block_fold
import dev.ide.ui.generated.resources.block_import_count
import dev.ide.ui.generated.resources.block_keyword_to
import dev.ide.ui.generated.resources.block_live_projection
import dev.ide.ui.generated.resources.block_no_index_matches
import dev.ide.ui.generated.resources.block_no_matches
import dev.ide.ui.generated.resources.block_palette_call
import dev.ide.ui.generated.resources.block_palette_comment
import dev.ide.ui.generated.resources.block_palette_for_each
import dev.ide.ui.generated.resources.block_palette_if
import dev.ide.ui.generated.resources.block_palette_if_else
import dev.ide.ui.generated.resources.block_palette_return
import dev.ide.ui.generated.resources.block_palette_variable
import dev.ide.ui.generated.resources.block_palette_while
import dev.ide.ui.generated.resources.block_projecting
import dev.ide.ui.generated.resources.block_search_placeholder
import dev.ide.ui.generated.resources.block_searching_index
import dev.ide.ui.generated.resources.block_statement_slot
import dev.ide.ui.generated.resources.block_trash
import dev.ide.ui.generated.resources.block_wrap_if
import dev.ide.ui.generated.resources.clear
import dev.ide.ui.generated.resources.close
import dev.ide.ui.generated.resources.delete
import dev.ide.ui.generated.resources.expand
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The projectional block editor — a Sketchware/Blockly canvas, projected from the same buffer the text
 * editor edits. Solid, content-width, category-colored puzzle blocks interlock by a notch + bump; control
 * statements are C-blocks wrapping their body behind a left arm; expressions sit in white value sockets as
 * nested reporter pills. Authoring is drag-and-drop (long-press to move a block onto a gap / the trash;
 * drag a palette block to insert) plus tap-to-type (a socket becomes an inline editor; the fragment
 * explodes into blocks on reparse). Every gesture is a surgical text edit on the one buffer.
 */
@Composable
fun BlockEditor(
    path: String,
    session: EditorSession,
    backend: IdeBackend,
    modifier: Modifier = Modifier,
) {
    var tree by remember(path) { mutableStateOf<UiBlockNode?>(null) }
    var projectedText by remember(path) { mutableStateOf("") }
    var failed by remember(path) { mutableStateOf(false) }
    var editing by remember(path) { mutableStateOf<EditTarget?>(null) }
    var selected by remember(path) { mutableStateOf<Selection?>(null) }
    var paletteOpen by remember(path) { mutableStateOf(false) }
    var focusStack by remember(path) { mutableStateOf<List<UiBlockNode>>(emptyList()) } // drill-in breadcrumb
    val drag = remember(path) { DragState() }
    val scope = rememberCoroutineScope()

    // Re-project whenever the buffer's text changes (keyed on the session's revision Int, not the text
    // String). The full text is pulled lazily here — debounced, off the typing path.
    LaunchedEffect(path, session.textRevision) {
        delay(250)
        val text = session.doc.text
        val projected = runCatching { backend.blocks.projectBlocks(path, text) }
        tree = projected.getOrNull()
        failed = projected.isFailure || projected.getOrNull() == null
        projectedText = text
        editing = null; selected = null; focusStack = emptyList() // node ids/offsets change on reproject
        drag.targets.clear()
    }

    // [extra] carries doc-level edits held by inline completion (auto-imports) — applied atomically with the
    // block edit's own edits, surgically on the shared session (applyEdits sorts descending), so untouched
    // code survives byte-for-byte and the text editor sees the same buffer.
    val applyEdit: (UiBlockEdit, List<UiTextEdit>) -> Unit = { edit, extra ->
        editing = null; selected = null; focusStack = emptyList()
        scope.launch {
            val edits = runCatching { backend.blocks.applyBlockEdit(path, projectedText, edit) }.getOrDefault(emptyList())
            val all = edits + extra
            if (all.isNotEmpty()) {
                val ranges = all.map { RangeEdit(it.start, it.end, it.newText, it.start + it.newText.length) }
                session.applyEdits(ranges, TextRange(all.minOf { it.start }.coerceAtLeast(0)))
            }
        }
    }
    val ctx = remember(projectedText, editing, selected, drag) {
        Ctx(path, backend, scope, projectedText, editing, selected?.blockId, drag, { editing = it }, { selected = it }, applyEdit, { paletteOpen = true }, { focusStack = focusStack + it })
    }

    Box(modifier.background(Ca.colors.editorBg).canvasOrigin(drag)) {
        Column(Modifier.fillMaxSize()) {
            val current = tree
            when {
                current == null && failed -> Hint(stringResource(Res.string.block_cannot_project))
                current == null -> Hint(stringResource(Res.string.block_projecting))
                else -> Box(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .clickable(remember { MutableInteractionSource() }, null) { ctx.select(null) }
                        .padding(14.dp),
                ) { PuzzleCanvas(current, ctx) }
            }
            BlockBar(drag, onAddBlock = { paletteOpen = !paletteOpen })
        }
        if (paletteOpen) Palette(ctx) { paletteOpen = false }
        focusStack.lastOrNull()?.let { node ->
            FocusSheet(node, ctx, canBack = focusStack.size > 1, onBack = { focusStack = focusStack.dropLast(1) }, onClose = { focusStack = emptyList() })
        }
        selected?.let { sel -> ActionBar(sel, ctx, Modifier.align(Alignment.BottomCenter).padding(bottom = 58.dp)) }
        if (drag.isDragging) DragGhost(drag)
    }
}

// ---------------------------------------------------------------------------
// Top-level document: file header pill, fields, method hats.
// ---------------------------------------------------------------------------

@Composable
internal fun PuzzleCanvas(file: UiBlockNode, ctx: Ctx) {
    val tops = bodyChildren(file)?.children ?: listOf(file)
    val pkg = tops.firstOrNull { it.label == "package" }?.let { sliceSource(ctx.source, it.start, it.end).removePrefix("package").trim().removeSuffix(";").trim() }
    val imports = tops.count { it.label == "import" }
    val cls = tops.firstOrNull { it.label == "class" }
    val members = cls?.let { bodyChildren(it)?.children } ?: emptyList()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.wrapContentWidth()) {
        FileHeader(cls?.let { className(it, ctx.source) } ?: file.label, pkg, imports)
        val fields = members.filter { it.label == "field" }
        if (fields.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(BlockMetrics.stackGap)) {
                fields.forEach { FieldBlock(it, ctx) }
            }
        }
        members.filter { it.label == "method" }.forEach { MethodHat(it, ctx) }
        if (cls == null) {
            // Not a class file — interlock whatever decls there are (notch+bump), same as a method body.
            InterlockColumn(Modifier.wrapContentWidth()) {
                tops.filter { it.label !in setOf("package", "import") }.forEach { Stmt(it, gap = null, ctx = ctx) }
            }
        }
    }
}

@Composable
private fun FileHeader(name: String, pkg: String?, imports: Int) {
    Row(
        Modifier.clip(RoundedCornerShape(Ca.radius.pill)).background(Ca.colors.surface, RoundedCornerShape(Ca.radius.pill)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(CaIcons.box, null, Modifier.size(15.dp), tint = Ca.colors.syntax.type)
        Text(name, color = Ca.colors.textPrimary, style = Ca.type.code, fontWeight = FontWeight.SemiBold)
        if (pkg != null) Text(pkg, color = Ca.colors.textTertiary, style = Ca.type.codeSmall)
        if (imports > 0) Text(pluralStringResource(Res.plurals.block_import_count, imports, imports), color = Ca.colors.textTertiary, fontSize = 10.5.sp)
    }
}

/** A slim data-colored field block. */
@Composable
private fun FieldBlock(node: UiBlockNode, ctx: Ctx) {
    val color = blockColor(BlockCat.Data)
    Row(
        Modifier.clip(RoundedCornerShape(BlockMetrics.corner)).blockShadow(BlockMetrics.corner).background(color, RoundedCornerShape(BlockMetrics.corner)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(CaIcons.docText, null, Modifier.size(14.dp), tint = Ca.colors.block.text.copy(alpha = 0.85f))
        Text(sliceSource(ctx.source, node.start, node.end).trimEnd(';'), color = Ca.colors.block.text, style = Ca.type.codeSmall)
    }
}

/** A method "hat": rounded-top block + the body stack directly below it. Folds on demand. */
@Composable
private fun MethodHat(node: UiBlockNode, ctx: Ctx) {
    val color = blockColor(BlockCat.Method)
    val body = bodyChildren(node)
    var expanded by remember(node.id) { mutableStateOf(true) }
    val hatPx = with(LocalDensity.current) { BlockMetrics.hatCorner.toPx() }
    val shape = rememberBlockShape(notchTop = false, bumpBottom = expanded, topRadius = hatPx)
    // Pull the body up by exactly connDepth so the first statement's notch seats into the hat's bump (no gap).
    Column(verticalArrangement = Arrangement.spacedBy(-BlockMetrics.connDepth), modifier = Modifier.wrapContentWidth()) {
        Row(
            Modifier.clip(shape).background(color, shape)
                .clickable(remember(node.id) { MutableInteractionSource() }, null) { expanded = !expanded }
                .padding(start = 13.dp, end = 16.dp, top = 8.dp, bottom = 8.dp + if (expanded) BlockMetrics.connDepth else 0.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(if (expanded) CaIcons.caretDown else CaIcons.caretRight, stringResource(Res.string.block_fold), Modifier.size(13.dp), tint = Ca.colors.block.text.copy(alpha = 0.8f))
            Text(signatureOf(node, ctx.source), color = Ca.colors.block.text, style = Ca.type.code, fontWeight = FontWeight.SemiBold)
            body?.let {
                if (!expanded) Text(pluralStringResource(Res.plurals.block_block_count, it.children.size, it.children.size),
                    color = Ca.colors.block.text.copy(alpha = 0.78f), fontSize = 10.5.sp)
            }
        }
        if (expanded && body != null) Stack(body, ctx)
    }
}

@Composable
@Preview
private fun MethodHatPreview() {

}

// ---------------------------------------------------------------------------
// Statement stack + dispatch.
// ---------------------------------------------------------------------------

/** An interlocking stack of statements + a trailing "add block" ghost (a drop zone). */
@Composable
private fun Stack(body: Body, ctx: Ctx) {
    InterlockColumn(Modifier.wrapContentWidth()) {
        body.children.forEachIndexed { i, child ->
            Stmt(child, gap = DropDescriptor.StatementGap(body.ownerId, body.slotIndex, i), ctx = ctx)
        }


        Ghost(DropDescriptor.StatementGap(body.ownerId, body.slotIndex, body.children.size), empty = body.children.isEmpty(), ctx = ctx)
    }
}

@Composable
private fun Stmt(node: UiBlockNode, gap: DropDescriptor.StatementGap?, ctx: Ctx) {
    val body = bodyChildren(node)
    if (body != null && isControl(node)) CBlock(node, body, gap, ctx) else SimpleBlock(node, gap, ctx)
}

private val CONTROL_LABELS = setOf("if", "for", "while", "do", "try", "switch")

private fun isControl(node: UiBlockNode) = node.label in CONTROL_LABELS

private fun catOf(node: UiBlockNode): BlockCat = when {
    node.label in CONTROL_LABELS -> BlockCat.Control
    node.label == "return" || node.label == "throw" -> BlockCat.Return
    node.label == "comment" -> BlockCat.Comment
    node.label == "var" || node.label == "field" || node.kind == "local_var" || node.kind == "field_decl" -> BlockCat.Data
    node.label == "" || node.kind == "method_call" || node.kind == "ExpressionStatement" -> BlockCat.Call
    else -> BlockCat.Opaque
}

/** A simple (non-wrapping) statement block: notch + bump, content-width, with sockets/pills inline. */
@Composable
private fun SimpleBlock(node: UiBlockNode, gap: DropDescriptor.StatementGap?, ctx: Ctx) {
    val color = blockColor(catOf(node))
    StatementShell(node, color, gap, ctx) { BlockInline(node, ctx, onPill = true, skipBody = false) }
}

/**
 * A C-block (if/for/while): the header bar, the left arm, and the closing footer are drawn as one
 * continuous shape ([rememberCBlockShape]) so the whole bracket reads as a single piece; the header
 * content and the indented body are laid into the top bar and the carved-out mouth. The header's
 * bottom carries a downward inner notch; the body overlaps up into it by [BlockMetrics.connDepth] so the
 * first wrapped block's top notch interlocks, and the footer's bump links the whole C to the next block.
 */
@Composable
private fun CBlock(node: UiBlockNode, body: Body, gap: DropDescriptor.StatementGap?, ctx: Ctx) {
    val color = blockColor(BlockCat.Control)
    val selected = ctx.selectedId == node.id
    val d = LocalDensity.current
    val mouthInsetPx = with(d) { (BlockMetrics.arm + BlockMetrics.connInset).toPx() }
    val armPx = with(d) { BlockMetrics.arm.toPx() }
    val footerPx = with(d) { (BlockMetrics.footer + BlockMetrics.connDepth).toPx() }
    val overlap = with(d) { BlockMetrics.connDepth.roundToPx() }
    // Header height feeds the carve; measured below and fed back so the mouth ceiling matches the bar.
    var headerPx by remember { mutableIntStateOf(with(d) { 38.dp.roundToPx() }) }
    val shape = rememberCBlockShape(
        headerHeightPx = headerPx.toFloat(), footerHeightPx = footerPx, armWidthPx = armPx, mouthInsetPx = mouthInsetPx,
    )
    Layout(
        content = {
            // 0: header content — the keyword + condition, laid into the top bar (the shape draws the bar).
            Box(
                Modifier.selectable(node, gap, ctx, selected)
                    .padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp + BlockMetrics.connDepth),
            ) { BlockInline(node, ctx, onPill = true, skipBody = true) }
            // 1: the wrapped body, indented past the arm into the carved mouth. The end padding is the
            // right margin the footer/header bars wrap the widest child with. No bottom padding: the
            // footer overlaps the last child's bump (below) so the bracket hugs the body height exactly.
            Box(Modifier.padding(start = BlockMetrics.arm, end = 0.dp, top = 0.dp)) { Stack(body, ctx) }
        },
        // background(shape) paints the bracket fill, but must not clip(shape): the wrapped body sits in
        // the carved mouth (outside the path), so clipping would hide it.
        modifier = Modifier.wrapContentWidth()
            .background(color, shape)
            .then(gap?.let { Modifier.dropZone(ctx.drag, it) } ?: Modifier)
            .insertionLine(gap != null && ctx.drag.hovered == gap),
    ) { measurables, constraints ->
        val cs = constraints.copy(minHeight = 0, minWidth = 0)
        val header = measurables[0].measure(cs)
        val mouth = measurables[1].measure(cs)
        if (headerPx != header.height) headerPx = header.height
        // mouth.width already includes the arm (start padding), so the block wraps the body exactly.
        val width = maxOf(header.width, mouth.width).coerceIn(constraints.minWidth, constraints.maxWidth)
        val mouthY = (header.height - overlap).coerceAtLeast(0)   // body tucks into the header's inner notch
        // Footer overlaps the body by connDepth (last child's bump drops into it), so the bracket wraps the
        // body height exactly instead of leaving an empty colored gap above the closing arm.
        val height = (mouthY + mouth.height - overlap + footerPx.roundToInt()).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            header.place(0, 0)
            mouth.place(0, mouthY)
        }
    }
}

/** The shell for a notch+bump statement block: shape, fill, shadow, selection, drag, insert-line. */
@Composable
private fun StatementShell(node: UiBlockNode, color: Color, gap: DropDescriptor.StatementGap?, ctx: Ctx, content: @Composable () -> Unit) {
    val selected = ctx.selectedId == node.id
    val shape = rememberBlockShape()
    Box(
        Modifier.wrapContentWidth()
            .then(gap?.let { Modifier.dropZone(ctx.drag, it) } ?: Modifier)
            .insertionLine(gap != null && ctx.drag.hovered == gap)
            .clip(shape).background(color, shape)
            .selectable(node, gap, ctx, selected)
            .padding(start = 13.dp, end = 14.dp, top = 7.dp, bottom = 7.dp + BlockMetrics.connDepth),
    ) { content() }
}

private fun Modifier.selectable(node: UiBlockNode, gap: DropDescriptor.StatementGap?, ctx: Ctx, selected: Boolean): Modifier = composed {
    this
        .clickable(remember(node.id) { MutableInteractionSource() }, null) {
            ctx.select(if (selected) null else Selection(node.id, gap, sliceSource(ctx.source, node.start, node.end)))
        }
        .dragSource(ctx.drag, { DragPayload.MoveBlock(node.label.ifEmpty { "block" }, node.id, sliceSource(ctx.source, node.start, node.end)) }) { drop ->
            when (drop) {
                is DropDescriptor.Trash -> ctx.apply(UiBlockEdit.DeleteBlock(node.id))
                is DropDescriptor.StatementGap -> ctx.apply(UiBlockEdit.MoveBlock(node.id, drop.ownerId, drop.slotIndex, drop.index))
                else -> {}
            }
        }
}

// ---------------------------------------------------------------------------
// Inline content: keyword labels, value sockets, reporter pills.
// ---------------------------------------------------------------------------

/** Render a block's inline content (keyword + parts) as a wrapping row. [onPill] = text is on a colored block. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockInline(node: UiBlockNode, ctx: Ctx, onPill: Boolean, skipBody: Boolean) {
    val bodySlot = if (skipBody) bodySlotIndex(node) else -1
    val keyword = keywordFor(node)
    val strip = if (keyword != null) setOf(node.label, "for", "each") else emptySet()
    val dividers = remember(node) { chainDividerIndices(node) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
        if (keyword != null) Keyword(keyword, onPill)
        node.parts.forEachIndexed { i, part ->
            if (i in dividers) ChainDivider()
            val si = slotIndexInNode(node, part)
            when (part) {
                is UiBlockPart.Field -> if (part.editable) Token(node.id, part, onPill, ctx) else Chrome(part.text, onPill, strip, node)
                is UiBlockPart.Slot -> if (!part.multiple && si != bodySlot) Socket(node.id, si, part, ctx)
            }
        }
    }
}

private fun keywordFor(node: UiBlockNode): String? = when (node.label) {
    "if" -> "if"; "while" -> "while"; "do" -> "do"; "try" -> "try"; "switch" -> "switch"
    "for" -> if (node.kind == "EnhancedForStatement") "for each" else "for"
    "return" -> "return"; "throw" -> "throw"
    "var" -> "set"                                              // B1: `set <type> <name> to <value>`
    "" -> if (isCallStatement(node)) "call" else null          // B1: `call foo(args)`
    else -> null
}

/** An expression statement whose expression is a method call — gets the `call` lead-in (B1). */
private fun isCallStatement(node: UiBlockNode): Boolean =
    node.kind == "ExpressionStatement" &&
        node.parts.filterIsInstance<UiBlockPart.Slot>().any { it.children.singleOrNull()?.kind == "method_call" }

/** A bold keyword label drawn directly on the block. */
@Composable
private fun Keyword(text: String, onPill: Boolean) {
    Text(text, color = if (onPill) Ca.colors.block.text else Ca.colors.textPrimary, style = Ca.type.code, fontWeight = FontWeight.Bold)
}

/** An editable token on the block (a name) — bold white text, tap to rename (with inline completion).
 *  A collapsed call's `qualifier` reads dimmed/normal so the bold method name carries the block. */
@Composable
private fun Token(blockId: String, field: UiBlockPart.Field, onPill: Boolean, ctx: Ctx) {
    val active = ctx.editing?.let { it.blockId == blockId && it.role == field.role && it.slotIndex == null } == true
    if (active) {
        InlineInput(field.text, docStart = field.start, expectedValueKind = null, ctx = ctx) { text, extra ->
            ctx.apply(UiBlockEdit.SetField(blockId, field.role, text), extra)
        }
        return
    }
    val qualifier = field.role == "qualifier"
    val base = if (onPill) Ca.colors.block.text else Ca.colors.textPrimary
    Text(
        field.text.ifEmpty { "•" },
        color = if (qualifier) base.copy(alpha = 0.66f) else base,
        style = Ca.type.code,
        fontWeight = if (qualifier) FontWeight.Normal else FontWeight.SemiBold,
        modifier = Modifier.clickable(remember(blockId + field.role) { MutableInteractionSource() }, null) { ctx.startEdit(EditTarget(blockId, field.role, null, field.text)) },
    )
}

/** Read-only syntax chrome — keywords/braces/parens dropped; a for-each `:` reads "in", `=` kept. */
@Composable
private fun Chrome(text: String, onPill: Boolean, strip: Set<String>, node: UiBlockNode) {
    var shown = cleanChrome(text, strip)
    if (node.kind == "EnhancedForStatement" && shown == ":") shown = "in"
    if (shown.isEmpty()) return
    if (shown == "in") { Keyword("in", onPill); return }
    Text(shown, color = (if (onPill) Ca.colors.block.text else Ca.colors.syntax.punctuation).copy(alpha = 0.7f), style = Ca.type.code)
}

/** A value input: the typed socket (hexagon = boolean, pill = number, …). Empty → a recessed hole
 *  hinting the expected kind; filled → a reporter; tap → type code (with inline completion). [depth] is the
 *  reporter-nesting level the filled value renders at — 0 for a statement-level socket, deeper for an
 *  operand inside a chain/operator block so its pill picks up the [Color.deepen] tint and the cap applies. */
@Composable
private fun Socket(ownerId: String, slotIndex: Int, slot: UiBlockPart.Slot, ctx: Ctx, depth: Int = 0) {
    val active = ctx.editing?.let { it.blockId == ownerId && it.slotIndex == slotIndex && it.role == null } == true
    if (active) {
        InlineInput(sliceSource(ctx.source, slot.start, slot.end), docStart = slot.start, expectedValueKind = slot.valueKind, ctx = ctx) { text, extra ->
            ctx.apply(UiBlockEdit.ReplaceSlot(ownerId, slotIndex, text), extra)
        }
        return
    }
    val onTap = { ctx.startEdit(EditTarget(ownerId, null, slotIndex, sliceSource(ctx.source, slot.start, slot.end))) }
    val child = slot.children.singleOrNull()
    if (child == null) {
        val vShape = valueShapeOf(slot.valueKind)
        val shape = rememberValueShape(vShape)
        Box(
            Modifier.heightIn(min = 22.dp).widthIn(min = 40.dp).clip(shape)
                .background(Ca.colors.block.hole, shape)
                .then(if (vShape == ValueShape.Type) Modifier.border(1.dp, Ca.colors.block.socket.copy(alpha = 0.5f), shape) else Modifier)
                .clickable(remember { MutableInteractionSource() }, null, onClick = onTap)
                .padding(horizontal = 12.dp + valueShapePadding(vShape)),
            contentAlignment = Alignment.CenterStart,
        ) {
            // the hole hints the kind it expects ("boolean", "number", …); unknown falls back to the category
            val hint = if (slot.valueKind != "unknown") slot.valueKind else slot.category.lowercase()
            Text(hint, color = Ca.colors.block.text.copy(alpha = 0.6f), fontStyle = FontStyle.Italic, fontSize = 11.sp)
        }
        return
    }
    Box(Modifier.clickable(remember(ownerId + slotIndex) { MutableInteractionSource() }, null, onClick = onTap)) { Value(child, ctx, depth) }
}

/** A reporter value: a white pill for literals/types/raw, a colored pill for variables/calls/operators.
 *  Both take the [ValueShape] of the kind the node produces: `a < b` becomes a green hexagon, a string
 *  literal a sharp white rect; UNKNOWN keeps the rounded pill. [depth] is the reporter-nesting level
 *  (0 = a top-level socket); deeper pills lose their drop shadow and darken slightly so layers read
 *  cleanly instead of stacking shadows. A fluent chain of ≥3 links lays out vertically (one link per row)
 *  rather than wrapping mid-expression. [depth] counts *drawn pills*, so a transparent grouping (a `var`
 *  fragment, a paren) keeps it — only entering a pill's content steps it. */
@Composable
private fun Value(node: UiBlockNode, ctx: Ctx, depth: Int = 0) {
    val vShape = valueShapeOf(node.valueKind)
    // Past the nesting cap, a compound expression collapses to a chip you tap to drill into (A2). Plain
    // leaves (a name/literal with no nested slot) stay inline however deep — they aren't noisy.
    if (depth >= VALUE_DEPTH_CAP && node.parts.any { it is UiBlockPart.Slot }) { CollapsedChip(node, ctx); return }
    val onlyField = node.parts.singleOrNull() as? UiBlockPart.Field
    if (onlyField != null && onlyField.editable) {
        if (onlyField.role == "name") Pill(BlockCat.Data, vShape, depth) { Text(onlyField.text, color = Ca.colors.block.text, style = Ca.type.code) }
        else White(onlyField.text, vShape)
        return
    }
    when {
        node.kind == "type_ref" || node.kind.endsWith("Type") -> White(sliceSource(ctx.source, node.start, node.end), vShape) // a whole type, not its generics
        node.kind == "method_call" && chainLinkCount(node) >= 3 -> Pill(BlockCat.Call, vShape, depth) { ChainStack(node, ctx, depth + 1) }
        node.kind == "method_call" || node.kind == "member_access" -> Pill(BlockCat.Call, vShape, depth) { ValueInline(node, ctx, depth + 1) }
        node.kind == "InfixExpression" && infixOperands(node).size >= 3 -> Pill(BlockCat.Op, vShape, depth) { OpStack(node, ctx, depth + 1) }
        node.kind == "InfixExpression" -> Pill(BlockCat.Op, vShape, depth) { ValueInline(node, ctx, depth + 1) }
        node.parts.any { it is UiBlockPart.Slot } -> ValueInline(node, ctx, depth) // transparent grouping (a fragment, paren…) — no pill, same depth
        else -> White(sliceSource(ctx.source, node.start, node.end), vShape)
    }
}

/** A reporter's inline content on ONE line (no FlowRow wrap — a wide reporter pushes the *statement* row
 *  to wrap at pill boundaries, which are meaningful, instead of fragmenting the pill itself). */
@Composable
private fun ValueInline(node: UiBlockNode, ctx: Ctx, depth: Int) {
    val dividers = remember(node) { chainDividerIndices(node) }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        node.parts.forEachIndexed { i, part ->
            if (i in dividers) ChainDivider()
            ValuePart(node, part, ctx, depth)
        }
    }
}

/** One part of a reporter's inline content: an editable token, read-only chrome, or a nested value slot. */
@Composable
private fun ValuePart(node: UiBlockNode, part: UiBlockPart, ctx: Ctx, depth: Int) {
    when (part) {
        is UiBlockPart.Field -> if (part.editable) {
            // role-aware: the collapsed qualifier reads dimmed, chain method names bold
            val qualifier = part.role == "qualifier"
            val name = node.kind == "method_call" && NAME_ROLE.matches(part.role)
            Text(
                part.text,
                color = if (qualifier) Ca.colors.block.text.copy(alpha = 0.66f) else Ca.colors.block.text,
                style = Ca.type.code,
                fontWeight = if (qualifier) FontWeight.Normal else if (name) FontWeight.SemiBold else FontWeight.Medium,
            )
        } else {
            val s = part.text.trim()
            when {
                // B1: a declaration's `=` reads as the word "to" (`set x to 1`).
                s == "=" && (node.label == "var" || node.kind == "local_var" || node.kind == "field_decl") ->
                    Text(stringResource(Res.string.block_keyword_to), color = Ca.colors.block.text, style = Ca.type.code, fontWeight = FontWeight.SemiBold)
                s.isNotEmpty() -> Text(s, color = Ca.colors.block.text.copy(alpha = 0.7f), style = Ca.type.code)
            }
        }
        is UiBlockPart.Slot -> if (!part.multiple) {
            val c = part.children.singleOrNull()
            // Same depth: the pill step is applied by the enclosing pill's content call, not per slot.
            if (c != null) Value(c, ctx, depth) else White("")
        }
    }
}

/** The number of flattened chain links (`name`/`name1`/…) a collapsed method call carries. */
private fun chainLinkCount(node: UiBlockNode): Int =
    node.parts.count { it is UiBlockPart.Field && it.editable && NAME_ROLE.matches(it.role) }

/**
 * A long fluent chain laid out vertically — the receiver and first call on the first row, then each
 * `.method(args)` link on its own indented row. Punctuation (`.`, `(`, `)`, `,`) is synthesized for the
 * display only; tapping anywhere on the enclosing socket still edits the whole call as text, and args
 * render as nested value reporters. This keeps `sb.append(a).append(b).append(c)` readable top-to-bottom
 * instead of wrapping into a pill-soup. */
@Composable
private fun ChainStack(node: UiBlockNode, ctx: Ctx, depth: Int) {
    val parts = node.parts
    val nameIdxs = remember(node) {
        parts.indices.filter { val p = parts[it]; p is UiBlockPart.Field && p.editable && NAME_ROLE.matches(p.role) }
    }
    val first = nameIdxs.first()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.wrapContentWidth()) {
        // Row 0: the receiver (parts before the first method name) + the first link, on one line. Each
        // editable piece is a real Token / Socket so it edits in place — not just the first row.
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            parts.subList(0, first).forEach { p ->
                when (p) {
                    is UiBlockPart.Field -> if (p.editable) Token(node.id, p, onPill = true, ctx = ctx)
                    is UiBlockPart.Slot -> if (!p.multiple) Socket(node.id, slotIndexInNode(node, p), p, ctx, depth)
                }
            }
            ChainLink(node, parts, first, nameIdxs.getOrNull(1) ?: parts.size, ctx, depth, leadingDot = first > 0)
        }
        // Each subsequent link on its own row, indented under the first dot.
        for (li in 1 until nameIdxs.size) {
            Row(Modifier.padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                ChainLink(node, parts, nameIdxs[li], nameIdxs.getOrNull(li + 1) ?: parts.size, ctx, depth, leadingDot = true)
            }
        }
    }
}

/** One `.method(arg, arg)` chain link, parts in `[nameIdx, end)`. The name is an editable [Token] and each
 *  argument a real [Socket], so every row edits in place. Parens render only for an actual call (a `(` in
 *  the link's chrome, or any argument) so a bare field hop (`.bar`) stays paren-less. */
@Composable
private fun ChainLink(node: UiBlockNode, parts: List<UiBlockPart>, nameIdx: Int, end: Int, ctx: Ctx, depth: Int, leadingDot: Boolean) {
    val name = parts[nameIdx] as UiBlockPart.Field
    val argSlots = parts.subList(nameIdx + 1, end).filterIsInstance<UiBlockPart.Slot>().filter { !it.multiple }
    val isCall = argSlots.isNotEmpty() || (nameIdx + 1 until end).any { val p = parts[it]; p is UiBlockPart.Field && !p.editable && '(' in p.text }
    val punct = Ca.colors.block.text.copy(alpha = 0.6f)
    if (leadingDot) Text(".", color = punct, style = Ca.type.code)
    Token(node.id, name, onPill = true, ctx = ctx)
    if (isCall) {
        Text("(", color = punct, style = Ca.type.code)
        argSlots.forEachIndexed { i, slot ->
            if (i > 0) Text(",", color = punct, style = Ca.type.code)
            Socket(node.id, slotIndexInNode(node, slot), slot, ctx, depth)
        }
        Text(")", color = punct, style = Ca.type.code)
    }
}

/** The operand slots of a (possibly flattened) infix expression — `a && b && c` has three. */
private fun infixOperands(node: UiBlockNode): List<UiBlockPart.Slot> =
    node.parts.filterIsInstance<UiBlockPart.Slot>().filter { !it.multiple }

/** The operator symbol of an infix expression — the trimmed chrome between its operands (`&&`, `+`, …). */
private fun infixOperator(node: UiBlockNode): String =
    node.parts.firstNotNullOfOrNull { (it as? UiBlockPart.Field)?.takeIf { f -> !f.editable }?.text?.trim()?.ifEmpty { null } } ?: ""

/**
 * A long same-operator infix chain (`x > 0 && y > 0 && z > 0 && w > 0`) laid out vertically (A3): each
 * operand on its own row as an editable [Socket], the operator in a fixed gutter so the operands align —
 * faithful to Scratch's hexagonal `and`/`or` blocks. A two-operand infix (`a < b`) stays inline. */
@Composable
private fun OpStack(node: UiBlockNode, ctx: Ctx, depth: Int) {
    val operands = infixOperands(node)
    val op = infixOperator(node)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.wrapContentWidth()) {
        operands.forEachIndexed { i, slot ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(22.dp), contentAlignment = Alignment.CenterEnd) {
                    if (i > 0) Text(op, color = Ca.colors.block.text, style = Ca.type.code, fontWeight = FontWeight.SemiBold)
                }
                Socket(node.id, slotIndexInNode(node, slot), slot, ctx, depth)
            }
        }
    }
}

/** The method-name roles a collapsed call/chain carries: `name`, `name1`, `name2`, … */
private val NAME_ROLE = Regex("name\\d*")

/**
 * Part indices where a thin divider separates one flattened chain segment from the next — before each
 * chrome dot that follows the previous link's close-paren (or its ARGUMENT slot), NOT the first `.`
 * after the qualifier. The gap-walked chrome can merge tokens (`").."`, `"()."`), so this tracks parens
 * instead of matching exact chrome strings: a `.` is a segment boundary once a `(` (or a leading `)`)
 * has gone by.
 */
private fun chainDividerIndices(node: UiBlockNode): Set<Int> {
    if (node.kind != "method_call") return emptySet()
    val out = HashSet<Int>()
    var sawParen = false
    node.parts.forEachIndexed { i, part ->
        val f = part as? UiBlockPart.Field ?: return@forEachIndexed
        if (f.editable) return@forEachIndexed
        val dot = f.text.indexOf('.')
        if (dot >= 0 && (sawParen || f.text.take(dot).contains(')'))) out += i
        if (f.text.contains('(')) sawParen = true
    }
    return out
}

/** The 1×16dp segment divider between flattened chain links. */
@Composable
private fun ChainDivider() {
    Box(Modifier.width(1.dp).height(16.dp).background(Ca.colors.block.text.copy(alpha = 0.25f)))
}

@Composable
private fun Pill(cat: BlockCat, shape: ValueShape = ValueShape.Unknown, depth: Int = 0, content: @Composable () -> Unit) {
    val color = blockColor(cat).deepen(depth)
    val s = rememberValueShape(shape)
    val hpad = (if (depth == 0) 8.dp else 6.dp) + valueShapePadding(shape) // tighten nested padding
    Box(
        Modifier.clip(s)
            .then(if (depth == 0) Modifier.shadow(2.dp, s, clip = false) else Modifier) // shadow only on the top layer
            .background(color, s)
            .heightIn(min = 22.dp).padding(horizontal = hpad, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun White(text: String, shape: ValueShape = ValueShape.Unknown) {
    val s = rememberValueShape(shape)
    val long = shape == ValueShape.Text && text.length > 28 // a long string literal: ellipsize, tap to edit
    Box(
        Modifier.heightIn(min = 22.dp).clip(s).background(Ca.colors.block.socket, s)
            .then(if (shape == ValueShape.Type) Modifier.border(1.dp, Ca.colors.block.socketText.copy(alpha = 0.3f), s) else Modifier) // the type tag's outline
            .then(if (long) Modifier.widthIn(max = 220.dp) else Modifier)
            .padding(horizontal = 8.dp + valueShapePadding(shape), vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text.ifEmpty { " " }, color = literalColor(shape), style = Ca.type.code, maxLines = 1, overflow = if (long) TextOverflow.Ellipsis else TextOverflow.Clip) }
}

// Literal syntax colors for the value sockets. The socket is white in BOTH themes (see BlockColors), so
// these are fixed dark-on-white tones rather than the theme-flipping editor syntax palette.
private val LITERAL_STRING = Color(0xFF067D17)
private val LITERAL_NUMBER = Color(0xFF1750EB)
private val LITERAL_KEYWORD = Color(0xFF9B2393)

/** A literal/value socket's text color, by the kind its shape encodes — string green, number blue, etc. */
@Composable
private fun literalColor(shape: ValueShape): Color = when (shape) {
    ValueShape.Text -> LITERAL_STRING
    ValueShape.Number -> LITERAL_NUMBER
    ValueShape.Boolean -> LITERAL_KEYWORD
    else -> Ca.colors.block.socketText
}

/** Darken a nested reporter's fill slightly per [depth] so layers read without stacking drop shadows. */
private fun Color.deepen(depth: Int): Color {
    if (depth <= 0) return this
    val f = 1f - 0.07f * depth.coerceAtMost(3)
    return Color(red * f, green * f, blue * f, alpha)
}

/** How deep reporter pills nest inline before collapsing to a drill-in chip (A2). Tunable. */
private const val VALUE_DEPTH_CAP = 3

/** The block category color a value reporter would use — mirrors [Value]'s dispatch. */
private fun catForValue(node: UiBlockNode): BlockCat = when {
    node.kind == "method_call" || node.kind == "member_access" -> BlockCat.Call
    node.kind == "InfixExpression" -> BlockCat.Op
    else -> BlockCat.Data
}

/** A short one-line summary of an expression for its collapsed chip — its source, middle-elided if long. */
private fun summaryOf(node: UiBlockNode, source: String): String {
    val t = sliceSource(source, node.start, node.end).replace(Regex("\\s+"), " ").trim()
    return if (t.length <= 22) t else t.take(12) + "…" + t.takeLast(7)
}

/** A collapsed deep expression: a category-tinted chip showing a summary + an expand glyph; tap to drill in. */
@Composable
private fun CollapsedChip(node: UiBlockNode, ctx: Ctx) {
    val s = rememberValueShape(valueShapeOf(node.valueKind))
    Row(
        Modifier.heightIn(min = 22.dp).clip(s).background(blockColor(catForValue(node)).deepen(2), s)
            .clickable(remember(node.id) { MutableInteractionSource() }, null) { ctx.focus(node) }
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(summaryOf(node, ctx.source), color = Ca.colors.block.text, style = Ca.type.code, maxLines = 1)
        Icon(CaIcons.braces, stringResource(Res.string.expand), Modifier.size(13.dp), tint = Ca.colors.block.text.copy(alpha = 0.7f))
    }
}

/**
 * The drill-in focus sheet (A2): a deeply-nested expression re-rooted on its own, full-width and clutter-
 * free, with its immediate parts editable (names as tokens, arguments as real sockets via [BlockInline]).
 * Nesting inside the sheet re-expands from depth 0, so a chip here drills another level (breadcrumb stack).
 */
@Composable
internal fun FocusSheet(node: UiBlockNode, ctx: Ctx, canBack: Boolean, onBack: () -> Unit, onClose: () -> Unit) {
    val color = blockColor(catForValue(node))
    val shape = rememberBlockShape(notchTop = false, bumpBottom = false)
    Box(
        Modifier.fillMaxSize().background(Ca.colors.glassThick)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(20.dp).widthIn(max = 540.dp).clip(RoundedCornerShape(Ca.radius.sheet)).background(Ca.colors.surface)
                .clickable(remember { MutableInteractionSource() }, null) {} // swallow taps so the scrim's close doesn't fire
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canBack) Icon(CaIcons.chevronLeft, stringResource(Res.string.back), Modifier.size(20.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onBack), tint = Ca.colors.textSecondary)
                Icon(CaIcons.braces, null, Modifier.size(16.dp), tint = Ca.colors.accent)
                Text(stringResource(Res.string.block_edit_expression), color = Ca.colors.textPrimary, style = Ca.type.headline, modifier = Modifier.weight(1f))
                Icon(CaIcons.close, stringResource(Res.string.close), Modifier.size(18.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onClose), tint = Ca.colors.textTertiary)
            }
            Box(
                Modifier.fillMaxWidth().clip(shape).background(color, shape).padding(horizontal = 14.dp, vertical = 10.dp),
            ) { BlockInline(node, ctx, onPill = true, skipBody = false) }
            Text(stringResource(Res.string.block_focus_hint), color = Ca.colors.textTertiary, style = Ca.type.caption)
        }
    }
}

/** The trailing "add block" ghost in a stack — a dashed pill that's also a drop zone. */
@Composable
private fun Ghost(gap: DropDescriptor.StatementGap, empty: Boolean, ctx: Ctx) {
    val hot = ctx.drag.hovered == gap
    Row(
        Modifier.padding(vertical = 12.dp, horizontal = 8.dp).dropZone(ctx.drag, gap).insertionLine(hot)
            .clip(RoundedCornerShape(BlockMetrics.corner)).dashed(if (hot) Ca.colors.accent else Ca.colors.separatorStrong)
            .clickable(remember(gap) { MutableInteractionSource() }, null) { ctx.openPalette() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(CaIcons.plus, null, Modifier.size(12.dp), tint = Ca.colors.textTertiary)
        if (empty) Text(stringResource(Res.string.block_add_block), color = Ca.colors.textTertiary, fontStyle = FontStyle.Italic, fontSize = 11.sp)
    }
}

// ---------------------------------------------------------------------------
// Bottom bar, palette, action bar.
// ---------------------------------------------------------------------------

@Composable
private fun BlockBar(drag: DragState, onAddBlock: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(Ca.colors.surface).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (drag.payload is DragPayload.MoveBlock) {
            Row(
                Modifier.clip(RoundedCornerShape(Ca.radius.control)).background(if (drag.hovered == DropDescriptor.Trash) Ca.colors.error else Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
                    .dropZone(drag, DropDescriptor.Trash).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val on = drag.hovered == DropDescriptor.Trash
                Icon(CaIcons.close, stringResource(Res.string.block_trash), Modifier.size(16.dp), tint = if (on) Ca.colors.textOnAccent else Ca.colors.textSecondary)
                Text(stringResource(Res.string.block_drop_to_delete), color = if (on) Ca.colors.textOnAccent else Ca.colors.textSecondary, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Row(
                Modifier.clip(RoundedCornerShape(Ca.radius.control)).background(Ca.colors.accentSoft, RoundedCornerShape(Ca.radius.control))
                    .clickable(remember { MutableInteractionSource() }, null, onClick = onAddBlock).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(CaIcons.plus, null, Modifier.size(16.dp), tint = Ca.colors.accent)
                Text(stringResource(Res.string.block_button), color = Ca.colors.accent, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold)
            }
        }
        Box(Modifier.weight(1f))
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(Ca.radius.pill)).background(Ca.colors.run))
        Text(stringResource(Res.string.block_live_projection), color = Ca.colors.textTertiary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

// [label] is the stable English key used to filter the palette by typed query; [labelRes] is the localized
// text shown on the block (and in the drag preview).
private data class PaletteItem(val label: String, val labelRes: StringResource, val cat: BlockCat, val ghost: String, val text: String)

private val PALETTE = listOf(
    PaletteItem("If", Res.string.block_palette_if, BlockCat.Control, "if ( ) { }", "if (true) {\n}"),
    PaletteItem("If / Else", Res.string.block_palette_if_else, BlockCat.Control, "if ( ) { } else { }", "if (true) {\n} else {\n}"),
    PaletteItem("For each", Res.string.block_palette_for_each, BlockCat.Control, "for ( : ) { }", "for (var item : items) {\n}"),
    PaletteItem("While", Res.string.block_palette_while, BlockCat.Control, "while ( ) { }", "while (true) {\n}"),
    PaletteItem("Return", Res.string.block_palette_return, BlockCat.Return, "return ;", "return value;"),
    PaletteItem("Variable", Res.string.block_palette_variable, BlockCat.Data, "var = ;", "var name = value;"),
    PaletteItem("Call", Res.string.block_palette_call, BlockCat.Call, "method();", "method();"),
    PaletteItem("Comment", Res.string.block_palette_comment, BlockCat.Comment, "// …", "// comment"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Palette(ctx: Ctx, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Pair<SymbolHit, Boolean>>>(emptyList()) } // hit → from member search
    var searching by remember { mutableStateOf(false) }
    // Debounced index search: project symbols + classpath members, rendered as draggable templates.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) { hits = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        delay(150)
        val symbols = runCatching { ctx.backend.search.searchSymbols(q, 12) }.getOrDefault(emptyList())
        val members = runCatching { ctx.backend.search.searchMembers(q, 12) }.getOrDefault(emptyList())
        hits = symbols.map { it to false } + members.map { it to true }
        searching = false
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = Ca.radius.sheet, topEnd = Ca.radius.sheet)).background(Ca.colors.surface).padding(16.dp).padding(bottom = 52.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(CaIcons.layers, null, Modifier.size(18.dp), tint = Ca.colors.accent)
                Text(stringResource(Res.string.block_add_a_block), color = Ca.colors.textPrimary, style = Ca.type.headline, modifier = Modifier.weight(1f))
                Text(stringResource(Res.string.block_statement_slot), color = Ca.colors.textTertiary, style = Ca.type.caption)
                Icon(CaIcons.close, stringResource(Res.string.close), Modifier.size(18.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onClose), tint = Ca.colors.textTertiary)
            }
            PaletteSearch(query) { query = it }
            val statics = if (query.isBlank()) PALETTE else PALETTE.filter { it.label.contains(query.trim(), ignoreCase = true) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                statics.forEach { item -> PaletteBlock(stringResource(item.labelRes), item.ghost, item.cat, item.text, ctx) }
                hits.forEach { (hit, fromMembers) -> PaletteBlock(hit.name, hit.detail, BlockCat.Call, templateFor(hit, fromMembers), ctx) }
            }
            when {
                searching -> Text(stringResource(Res.string.block_searching_index), color = Ca.colors.textTertiary, style = Ca.type.caption)
                query.isNotBlank() && hits.isEmpty() && statics.isEmpty() -> Text(stringResource(Res.string.block_no_matches), color = Ca.colors.textTertiary, style = Ca.type.caption)
                query.isNotBlank() && hits.isEmpty() -> Text(stringResource(Res.string.block_no_index_matches), color = Ca.colors.textTertiary, style = Ca.type.caption)
            }
        }
    }
}

/** The palette's search row: filters the static blocks AND queries the symbol/member indexes. */
@Composable
private fun PaletteSearch(query: String, onQuery: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.control)).background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control)).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(CaIcons.search, null, Modifier.size(14.dp), tint = Ca.colors.textTertiary)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(stringResource(Res.string.block_search_placeholder), color = Ca.colors.textTertiary, style = Ca.type.codeSmall)
            BasicTextField(
                query, onQuery, singleLine = true,
                textStyle = Ca.type.codeSmall.copy(color = Ca.colors.textPrimary),
                cursorBrush = SolidColor(Ca.colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) Icon(CaIcons.close, stringResource(Res.string.clear), Modifier.size(13.dp).clickable(remember { MutableInteractionSource() }, null) { onQuery("") }, tint = Ca.colors.textTertiary)
    }
}

/** One draggable palette block (static or index hit): drop on a statement gap to insert [text]. */
@Composable
private fun PaletteBlock(label: String, ghost: String, cat: BlockCat, text: String, ctx: Ctx) {
    val color = blockColor(cat)
    val shape = rememberBlockShape(notchTop = true, bumpBottom = true)
    Column(
        Modifier.widthIn(min = 150.dp).clip(shape).background(color, shape)
            .dragSource(ctx.drag, { DragPayload.Template(label, text, cat) }) { drop ->
                if (drop is DropDescriptor.StatementGap) ctx.apply(UiBlockEdit.InsertTemplate(drop.ownerId, drop.slotIndex, drop.index, text))
            }
            .padding(start = 13.dp, end = 13.dp, top = 8.dp, bottom = 8.dp + BlockMetrics.connDepth),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(label, color = Ca.colors.block.text, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
        Text(ghost, color = Ca.colors.block.text.copy(alpha = 0.75f), style = Ca.type.codeSmall)
    }
}

/** What dropping an index hit inserts: methods call themselves, classes instantiate, the rest names itself. */
private fun templateFor(hit: SymbolHit, fromMembers: Boolean): String {
    val kind = hit.kind.lowercase()
    return when {
        fromMembers || "method" in kind || "function" in kind || "constructor" in kind -> "${hit.name}();"
        "class" in kind || "interface" in kind || "enum" in kind || "record" in kind -> "${hit.name} x = new ${hit.name}();"
        else -> hit.name
    }
}

@Composable
private fun ActionBar(selection: Selection, ctx: Ctx, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(Ca.radius.lg)).background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.lg)).padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selection.insertCtx != null) {
            ActionItem(CaIcons.layers, stringResource(Res.string.block_wrap_if)) { ctx.apply(UiBlockEdit.WrapInIf(selection.blockId)) }
            ActionItem(CaIcons.plus, stringResource(Res.string.block_duplicate)) { val ic = selection.insertCtx; ctx.apply(UiBlockEdit.InsertTemplate(ic.ownerId, ic.slotIndex, ic.index + 1, selection.sourceText)) }
            Box(Modifier.width(1.dp).height(24.dp).background(Ca.colors.separator))
        }
        ActionItem(CaIcons.close, stringResource(Res.string.delete), tint = Ca.colors.error) { ctx.apply(UiBlockEdit.DeleteBlock(selection.blockId)) }
    }
}

@Composable
private fun ActionItem(icon: ImageVector, label: String, tint: Color = Ca.colors.textPrimary, onClick: () -> Unit) {
    Column(
        Modifier.widthIn(min = 46.dp).clip(RoundedCornerShape(Ca.radius.sm)).clickable(remember { MutableInteractionSource() }, null, onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Icon(icon, label, Modifier.size(18.dp), tint = tint)
        Text(label, color = Ca.colors.textTertiary, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Ca.colors.textSecondary, style = Ca.type.subhead)
    }
}

// ---------------------------------------------------------------------------
// State + helpers.
// ---------------------------------------------------------------------------

internal data class EditTarget(val blockId: String, val role: String?, val slotIndex: Int?, val initial: String)
internal data class Selection(val blockId: String, val insertCtx: DropDescriptor.StatementGap?, val sourceText: String)

/** Everything the canvas composables need: the projected buffer + edit/drag state, plus the host port
 *  ([path]/[backend]/[scope]) so the inline editor can run completion. `startEdit(null)` cancels. */
internal class Ctx(
    val path: String,
    val backend: IdeBackend,
    val scope: CoroutineScope,
    val source: String,
    val editing: EditTarget?,
    val selectedId: String?,
    val drag: DragState,
    val startEdit: (EditTarget?) -> Unit,
    val select: (Selection?) -> Unit,
    private val applyEdit: (UiBlockEdit, List<UiTextEdit>) -> Unit,
    val openPalette: () -> Unit,
    /** Drill into a deeply-nested expression — re-roots the focus sheet at [node] so it can be read/edited
     *  in isolation. Default no-op (previews supply their own). */
    val focus: (UiBlockNode) -> Unit = {},
) {
    /** Apply a block edit, plus [extra] doc-level edits held by inline completion (auto-imports). */
    fun apply(edit: UiBlockEdit, extra: List<UiTextEdit> = emptyList()) = applyEdit(edit, extra)
}

private data class Body(val children: List<UiBlockNode>, val ownerId: String, val slotIndex: Int)

private fun bodyChildren(node: UiBlockNode): Body? {
    val slots = node.parts.filterIsInstance<UiBlockPart.Slot>()
    slots.indexOfFirst { it.multiple }.takeIf { it >= 0 }?.let { i -> return Body(slots[i].children, node.id, i) }
    val blockChild = slots.firstNotNullOfOrNull { s -> s.children.singleOrNull()?.takeIf { it.kind == "block" } }
    if (blockChild != null) {
        val inner = blockChild.parts.filterIsInstance<UiBlockPart.Slot>()
        val mi = inner.indexOfFirst { it.multiple }
        if (mi >= 0) return Body(inner[mi].children, blockChild.id, mi)
    }
    return null
}

private fun bodySlotIndex(node: UiBlockNode): Int {
    val slots = node.parts.filterIsInstance<UiBlockPart.Slot>()
    slots.indexOfFirst { it.multiple }.takeIf { it >= 0 }?.let { return it }
    return slots.indexOfFirst { s -> s.children.singleOrNull()?.kind == "block" }
}

private fun slotIndexInNode(node: UiBlockNode, part: UiBlockPart): Int =
    if (part is UiBlockPart.Slot) node.parts.filterIsInstance<UiBlockPart.Slot>().indexOf(part) else -1

/** A method's signature text — everything before its `{` body (e.g. `public int sum(int[] xs)`). */
private fun signatureOf(node: UiBlockNode, source: String): String {
    val bodyStart = node.parts.filterIsInstance<UiBlockPart.Slot>()
        .firstNotNullOfOrNull { s -> s.children.singleOrNull()?.takeIf { it.kind == "block" }?.start }
        ?: node.end
    return sliceSource(source, node.start, bodyStart).trim().trimEnd('{').trim().replace(Regex("\\s+"), " ")
}

/** The class name from a class node's header parts (the editable `name` token), or its label. */
private fun className(cls: UiBlockNode, source: String): String =
    cls.parts.filterIsInstance<UiBlockPart.Slot>().firstNotNullOfOrNull { s -> s.children.firstOrNull { it.kind == "name_ref" } }
        ?.let { sliceSource(source, it.start, it.end) } ?: "class"

private fun cleanChrome(text: String, strip: Set<String>): String {
    var t = text
    for (w in strip) t = t.replace(Regex("\\b" + Regex.escape(w) + "\\b"), "")
    t = t.filterNot { it in "(){}" }
    return t.trim().replace(Regex("\\s+"), " ")
}

private fun sliceSource(source: String, start: Int, end: Int): String =
    if (start in 0..source.length && end in start..source.length) source.substring(start, end) else ""

/** A subtle drop shadow for depth (the reference's `0 1px 2px`). Used on rounded pills/fields. */
private fun Modifier.blockShadow(corner: androidx.compose.ui.unit.Dp = BlockMetrics.corner): Modifier =
    this.shadow(2.dp, RoundedCornerShape(corner), clip = false)

/** A custom vertical layout that overlaps children so a block's bump drops into the next block's notch. */
@Composable
private fun InterlockColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val overlap = BlockMetrics.connDepth - BlockMetrics.stackGap
    Layout(content, modifier) { measurables, constraints ->
        val ov = overlap.roundToPx().coerceAtLeast(0)
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0, minWidth = 0)) }
        val width = (placeables.maxOfOrNull { it.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        var y = 0
        val ys = IntArray(placeables.size)
        placeables.forEachIndexed { i, p -> ys[i] = y; y += (p.height - ov).coerceAtLeast(0) }
        val height = (if (placeables.isEmpty()) 0 else y + ov).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) { placeables.forEachIndexed { i, p -> p.place(0, ys[i]) } }
    }
}

/** A 2px accent line along the top edge, shown when a drag hovers this gap. */
private fun Modifier.insertionLine(show: Boolean): Modifier = composed {
    val accent = Ca.colors.accent
    if (!show) this else this.drawBehind { drawRect(accent, size = Size(size.width, 2.dp.toPx())) }
}

/** A dashed rounded border (Compose has no dashed [androidx.compose.foundation.border]). */
private fun Modifier.dashed(color: Color): Modifier = this.drawBehind {
    val r = BlockMetrics.corner.toPx()
    drawRoundRect(
        color = color,
        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))),
        cornerRadius = CornerRadius(r, r),
    )
}

// ===========================================================================
// Compose previews — render the canvas from hand-built sample UiBlockNode trees
// (the real projection needs JDT, which is JVM-only). The samples mirror what the
// projection produces so the previews exercise every block kind without launching
// the app.
// ===========================================================================

/**
 * Builds sample [UiBlockNode]s while assembling the backing source string, so each node's offsets line up
 * with the text — exactly what the renderer slices for types, sockets, signatures, and the package line.
 */
private class BlockSample {
    val sb = StringBuilder()
    private var n = 0
    private fun id() = "s${n++}"

    fun chrome(t: String): UiBlockPart.Field { val s = sb.length; sb.append(t); return UiBlockPart.Field("syntax", t, false, s, sb.length) }
    fun field(role: String, t: String): UiBlockPart.Field { val s = sb.length; sb.append(t); return UiBlockPart.Field(role, t, true, s, sb.length) }
    fun gap() { sb.append("\n            ") }
    fun leaf(kind: String, label: String, role: String, t: String, valueKind: String = "unknown"): UiBlockNode {
        val s = sb.length; sb.append(t)
        return UiBlockNode(id(), kind, label, label, s, sb.length, listOf(UiBlockPart.Field(role, t, true, s, sb.length)), valueKind = valueKind)
    }
    fun build(kind: String, label: String, valueKind: String = "unknown", f: () -> List<UiBlockPart>): UiBlockNode {
        val s = sb.length; val parts = f()
        return UiBlockNode(id(), kind, label, label, s, sb.length, parts, valueKind = valueKind)
    }
    fun single(cat: String, child: UiBlockNode, valueKind: String = "unknown") =
        UiBlockPart.Slot(cat, false, child.start, child.end, listOf(child), valueKind = valueKind)
    fun empty(cat: String, valueKind: String = "unknown") =
        UiBlockPart.Slot(cat, false, sb.length, sb.length, emptyList(), valueKind = valueKind)
    fun list(cat: String, children: List<UiBlockNode>) =
        UiBlockPart.Slot(cat, true, children.firstOrNull()?.start ?: sb.length, children.lastOrNull()?.end ?: sb.length, children)
}

private fun BlockSample.call(recv: String?, name: String, args: List<String>): UiBlockNode = build("method_call", "call") {
    buildList {
        if (recv != null) { add(single("EXPRESSION", leaf("name_ref", "name", "name", recv))); add(chrome(".")) }
        add(single("NAME", leaf("name_ref", "name", "name", name)))
        add(chrome("("))
        args.forEachIndexed { i, a -> if (i > 0) add(chrome(", ")); add(single("ARGUMENT", leaf("name_ref", "name", "name", a))) }
        add(chrome(")"))
    }
}

private fun BlockSample.declStmt(): UiBlockNode = build("local_var", "var") {
    val type = single("TYPE", leaf("type_ref", "type", "type", "List<Note>"))
    val sp = chrome(" ")
    val frag = build("local_var", "var") {
        val nm = single("NAME", leaf("name_ref", "name", "name", "result"))
        val eq = chrome(" = ")
        val init = single("EXPRESSION", leaf("ClassInstanceCreation", "value", "code", "new ArrayList<>()"))
        listOf(nm, eq, init)
    }
    listOf(type, sp, single("EXPRESSION", frag), chrome(";"))
}

private fun BlockSample.ifStmt(): UiBlockNode = build("IfStatement", "if") {
    val pre = chrome("if (")
    val cond = single("EXPRESSION", call("note", "isPinned", emptyList()))
    val cl = chrome(") ")
    val block = build("block", "block") {
        val open = chrome("{")
        val stmt = build("ExpressionStatement", "") { listOf(single("EXPRESSION", call("result", "add", listOf("note"))), chrome(";")) }
        val close = chrome("}")
        listOf(open, list("STATEMENT", listOf(stmt)), close)
    }
    listOf(pre, cond, cl, single("STATEMENT", block))
}

private fun BlockSample.forEachStmt(): UiBlockNode = build("EnhancedForStatement", "for") {
    val pre = chrome("for (")
    val param = build("parameter", "param") {
        val t = single("TYPE", leaf("type_ref", "type", "type", "Note"))
        val sp = chrome(" ")
        val nm = single("NAME", leaf("name_ref", "name", "name", "note"))
        listOf(t, sp, nm)
    }
    val colon = chrome(" : ")
    val iter = single("EXPRESSION", leaf("name_ref", "name", "name", "notes"))
    val cl = chrome(") ")
    val block = build("block", "block") {
        val open = chrome("{")
        val ifS = ifStmt()
        val close = chrome("}")
        listOf(open, list("STATEMENT", listOf(ifS)), close)
    }
    listOf(pre, single("PARAMETER", param), colon, iter, cl, single("STATEMENT", block))
}

private fun BlockSample.returnStmt(): UiBlockNode = build("ReturnStatement", "return") {
    listOf(chrome("return "), single("EXPRESSION", leaf("name_ref", "name", "name", "result")), chrome(";"))
}

/** A whole compilation unit: package + imports + a class with a field and a control-flow-rich method. */
private fun sampleFile(): Pair<UiBlockNode, String> {
    val x = BlockSample()
    val file = x.build("compilation_unit", "file") {
        val pkg = x.leaf("package_decl", "package", "code", "package com.example.notes.data;")
        x.chrome("\n")
        val imp1 = x.leaf("import_decl", "import", "code", "import java.util.ArrayList;")
        x.chrome("\n")
        val imp2 = x.leaf("import_decl", "import", "code", "import java.util.List;")
        x.chrome("\n\n")
        val cls = x.build("class_decl", "class") {
            val pre = x.chrome("public final class ")
            val name = x.single("NAME", x.leaf("name_ref", "name", "name", "NoteRepository"))
            x.chrome(" {\n    ")
            val field = x.build("field_decl", "field") { listOf(x.chrome("private int total = 0;")) }
            x.chrome("\n    ")
            val method = x.build("method_decl", "method") {
                val sig = x.chrome("public List<Note> pinned(List<Note> notes) ")
                val block = x.build("block", "block") {
                    val open = x.chrome("{")
                    val decl = x.declStmt(); x.gap()
                    val forE = x.forEachStmt(); x.gap()
                    val ret = x.returnStmt()
                    val close = x.chrome("}")
                    listOf(open, x.list("STATEMENT", listOf(decl, forE, ret)), close)
                }
                listOf(sig, x.single("STATEMENT", block))
            }
            x.chrome("\n}")
            listOf(pre, name, x.list("DECLARATION", listOf(field, method)))
        }
        listOf(x.list("DECLARATION", listOf(pkg, imp1, imp2, cls)))
    }
    return file to x.sb.toString()
}

// ---- the typed/collapsed-call showcase (mirrors what the new JavaBlockMapping emits: qualifier +
// name/name1 fields + per-arg ARGUMENT slots, valueKinds on slots and nodes) ----

/** `<type> <name> = <literal>;` — the initializer slot (and literal) typed by the declared type. */
private fun BlockSample.typedDecl(type: String, name: String, kind: String, literal: String): UiBlockNode = build("local_var", "var") {
    val t = single("TYPE", leaf("type_ref", "type", "type", type, valueKind = "type"))
    val sp = chrome(" ")
    val frag = build("local_var", "var") {
        val nm = single("NAME", leaf("name_ref", "name", "name", name))
        val eq = chrome(" = ")
        val init = single("EXPRESSION", leaf("literal", "value", "code", literal, valueKind = kind), valueKind = kind)
        listOf(nm, eq, init)
    }
    listOf(t, sp, single("EXPRESSION", frag), chrome(";"))
}

/** `System.out.println("hi");` collapsed to ONE call block: dimmed qualifier + bold name + arg slot. */
private fun BlockSample.printlnStmt(): UiBlockNode = build("ExpressionStatement", "") {
    val call = build("method_call", "call") {
        listOf(
            field("qualifier", "System.out"), chrome("."), field("name", "println"), chrome("("),
            single("ARGUMENT", leaf("literal", "value", "code", "\"hi\"", valueKind = "string"), valueKind = "string"),
            chrome(")"),
        )
    }
    listOf(single("EXPRESSION", call), chrome(";"))
}

/** `sb.append(x).append(y).append(z);` flattened to ONE block — with ≥3 links it lays out vertically
 *  (one `.append(..)` per row) via [ChainStack] instead of wrapping inline. */
private fun BlockSample.chainStmt(): UiBlockNode = build("ExpressionStatement", "") {
    val call = build("method_call", "call") {
        listOf(
            field("qualifier", "sb"), chrome("."), field("name", "append"), chrome("("),
            single("ARGUMENT", leaf("name_ref", "name", "name", "x")),
            chrome(")."), field("name1", "append"), chrome("("),
            single("ARGUMENT", leaf("name_ref", "name", "name", "y")),
            chrome(")."), field("name2", "append"), chrome("("),
            single("ARGUMENT", leaf("name_ref", "name", "name", "z")),
            chrome(")"),
        )
    }
    listOf(single("EXPRESSION", call), chrome(";"))
}

/** An if whose boolean condition socket holds a boolean-producing infix — the green hexagon. */
private fun BlockSample.typedIfStmt(): UiBlockNode = build("IfStatement", "if") {
    val pre = chrome("if (")
    val cmp = build("InfixExpression", "", valueKind = "boolean") {
        listOf(
            single("EXPRESSION", leaf("name_ref", "name", "name", "n")),
            chrome(" < "),
            single("EXPRESSION", leaf("literal", "value", "code", "10", valueKind = "number"), valueKind = "number"),
        )
    }
    val cond = single("EXPRESSION", cmp, valueKind = "boolean")
    val cl = chrome(") ")
    val block = build("block", "block") {
        val open = chrome("{")
        val stmt = printlnStmt()
        val close = chrome("}")
        listOf(open, list("STATEMENT", listOf(stmt)), close)
    }
    listOf(pre, cond, cl, single("STATEMENT", block))
}

/** A while with an EMPTY boolean condition — the hexagon hole hinting "boolean". */
private fun BlockSample.whileEmptyStmt(): UiBlockNode = build("WhileStatement", "while") {
    val pre = chrome("while (")
    val cond = empty("EXPRESSION", valueKind = "boolean")
    val cl = chrome(") ")
    val block = build("block", "block") { listOf(chrome("{"), list("STATEMENT", emptyList()), chrome("}")) }
    listOf(pre, cond, cl, single("STATEMENT", block))
}

/** Typed sockets + collapsed calls in one unit: decls, a println, a fluent chain, an if, an empty while. */
internal fun typedSampleFile(): Pair<UiBlockNode, String> {
    val x = BlockSample()
    val file = x.build("compilation_unit", "file") {
        val pkg = x.leaf("package_decl", "package", "code", "package com.example.notes.demo;")
        x.chrome("\n\n")
        val cls = x.build("class_decl", "class") {
            val pre = x.chrome("public final class ")
            val name = x.single("NAME", x.leaf("name_ref", "name", "name", "Typed"))
            x.chrome(" {\n    ")
            val method = x.build("method_decl", "method") {
                val sig = x.chrome("void demo(StringBuilder sb, int x, int y) ")
                val block = x.build("block", "block") {
                    val open = x.chrome("{")
                    val d1 = x.typedDecl("int", "n", "number", "1"); x.gap()
                    val d2 = x.typedDecl("String", "s", "string", "\"x\""); x.gap()
                    val d3 = x.typedDecl("boolean", "ok", "boolean", "true"); x.gap()
                    val p = x.printlnStmt(); x.gap()
                    val c = x.chainStmt(); x.gap()
                    val ifS = x.typedIfStmt(); x.gap()
                    val w = x.whileEmptyStmt()
                    val close = x.chrome("}")
                    listOf(open, x.list("STATEMENT", listOf(d1, d2, d3, p, c, ifS, w)), close)
                }
                listOf(sig, x.single("STATEMENT", block))
            }
            x.chrome("\n}")
            listOf(pre, name, x.list("DECLARATION", listOf(method)))
        }
        listOf(x.list("DECLARATION", listOf(pkg, cls)))
    }
    return file to x.sb.toString()
}

/** A 5-deep nested call `sanitize(normalize(trim(lower(read(text)))))` for the A2 depth-cap demo: past 3
 *  reporter levels the inner call collapses to a drill-in chip. Built outer-in so the text lands in order. */
private fun BlockSample.deepCall(): UiBlockNode {
    fun nest(name: String, inner: () -> UiBlockNode) = build("method_call", "call") {
        listOf(single("NAME", leaf("name_ref", "name", "name", name)), chrome("("), single("ARGUMENT", inner()), chrome(")"))
    }
    return nest("sanitize") { nest("normalize") { nest("trim") { nest("lower") { nest("read") {
        leaf("name_ref", "name", "name", "text")
    } } } } }
}

/** A unit with one statement whose initializer is [deepCall] — the canvas shows 3 nested pills then a chip. */
internal fun deepSampleFile(): Pair<UiBlockNode, String> {
    val x = BlockSample()
    val file = x.build("compilation_unit", "file") {
        val pkg = x.leaf("package_decl", "package", "code", "package com.example.notes.demo;")
        x.chrome("\n\n")
        val cls = x.build("class_decl", "class") {
            val pre = x.chrome("public final class ")
            val name = x.single("NAME", x.leaf("name_ref", "name", "name", "Deep"))
            x.chrome(" {\n    ")
            val method = x.build("method_decl", "method") {
                val sig = x.chrome("String demo(String text) ")
                val block = x.build("block", "block") {
                    val open = x.chrome("{")
                    val stmt = x.build("local_var", "var") {
                        val t = x.single("TYPE", x.leaf("type_ref", "type", "type", "String", valueKind = "type"))
                        val sp = x.chrome(" ")
                        val frag = x.build("local_var", "var") {
                            val nm = x.single("NAME", x.leaf("name_ref", "name", "name", "r"))
                            val eq = x.chrome(" = ")
                            val init = x.single("EXPRESSION", x.deepCall())
                            listOf(nm, eq, init)
                        }
                        listOf(t, sp, x.single("EXPRESSION", frag), x.chrome(";"))
                    }
                    val close = x.chrome("}")
                    listOf(open, x.list("STATEMENT", listOf(stmt)), close)
                }
                listOf(sig, x.single("STATEMENT", block))
            }
            x.chrome("\n}")
            listOf(pre, name, x.list("DECLARATION", listOf(method)))
        }
        listOf(x.list("DECLARATION", listOf(pkg, cls)))
    }
    return file to x.sb.toString()
}

/** The expression a user drills into from the chip: `lower(read(text))`, for the [FocusSheet] demo. */
internal fun deepFocusExpr(): Pair<UiBlockNode, String> {
    val x = BlockSample()
    val node = x.build("method_call", "call") {
        listOf(
            x.single("NAME", x.leaf("name_ref", "name", "name", "lower")), x.chrome("("),
            x.single("ARGUMENT", x.build("method_call", "call") {
                listOf(x.single("NAME", x.leaf("name_ref", "name", "name", "read")), x.chrome("("), x.single("ARGUMENT", x.leaf("name_ref", "name", "name", "text")), x.chrome(")"))
            }),
            x.chrome(")"),
        )
    }
    return node to x.sb.toString()
}

/** A `name > 0` boolean comparison, for [opSampleFile]'s operands. */
private fun BlockSample.cmp(nm: String): UiBlockNode = build("InfixExpression", "", valueKind = "boolean") {
    listOf(
        single("EXPRESSION", leaf("name_ref", "name", "name", nm)),
        chrome(" > "),
        single("EXPRESSION", leaf("literal", "value", "code", "0", valueKind = "number"), valueKind = "number"),
    )
}

/** A unit with `if (x > 0 && y > 0 && z > 0 && w > 0)` — the 4-operand `&&` chain renders as an [OpStack]. */
internal fun opSampleFile(): Pair<UiBlockNode, String> {
    val x = BlockSample()
    val file = x.build("compilation_unit", "file") {
        val pkg = x.leaf("package_decl", "package", "code", "package com.example.notes.demo;")
        x.chrome("\n\n")
        val cls = x.build("class_decl", "class") {
            val pre = x.chrome("public final class ")
            val name = x.single("NAME", x.leaf("name_ref", "name", "name", "Ops"))
            x.chrome(" {\n    ")
            val method = x.build("method_decl", "method") {
                val sig = x.chrome("void demo(int x, int y, int z, int w) ")
                val block = x.build("block", "block") {
                    val open = x.chrome("{")
                    val ifS = x.build("IfStatement", "if") {
                        val preIf = x.chrome("if (")
                        val cond = x.build("InfixExpression", "", valueKind = "boolean") {
                            listOf(
                                x.single("EXPRESSION", x.cmp("x"), valueKind = "boolean"), x.chrome(" && "),
                                x.single("EXPRESSION", x.cmp("y"), valueKind = "boolean"), x.chrome(" && "),
                                x.single("EXPRESSION", x.cmp("z"), valueKind = "boolean"), x.chrome(" && "),
                                x.single("EXPRESSION", x.cmp("w"), valueKind = "boolean"),
                            )
                        }
                        val cl = x.chrome(") ")
                        val body = x.build("block", "block") {
                            listOf(x.chrome("{"), x.list("STATEMENT", listOf(x.printlnStmt())), x.chrome("}"))
                        }
                        listOf(preIf, x.single("EXPRESSION", cond, valueKind = "boolean"), cl, x.single("STATEMENT", body))
                    }
                    val close = x.chrome("}")
                    listOf(open, x.list("STATEMENT", listOf(ifS)), close)
                }
                listOf(sig, x.single("STATEMENT", block))
            }
            x.chrome("\n}")
            listOf(pre, name, x.list("DECLARATION", listOf(method)))
        }
        listOf(x.list("DECLARATION", listOf(pkg, cls)))
    }
    return file to x.sb.toString()
}

/** A no-op backend so previews can build a [Ctx] (completion/search return nothing). */
internal object PreviewBackend : IdeBackend,
    dev.ide.ui.backend.FileService, dev.ide.ui.backend.EditorService, dev.ide.ui.backend.BlockService,
    dev.ide.ui.backend.PreviewService, dev.ide.ui.backend.SearchService, dev.ide.ui.backend.BuildService,
    dev.ide.ui.backend.DependencyService, dev.ide.ui.backend.ModuleService, dev.ide.ui.backend.SigningService,
    dev.ide.ui.backend.ProjectService,
    dev.ide.ui.backend.SdkService, dev.ide.ui.backend.SettingsService, dev.ide.ui.backend.ActionService,
    dev.ide.ui.backend.DiagnosticsService {
    override val files get() = this
    override val editor get() = this
    override val blocks get() = this
    override val preview get() = this
    override val search get() = this
    override val build get() = this
    override val deps get() = this
    override val modules get() = this
    override val signing get() = this
    override val projects get() = this
    override val sdk get() = this
    override val settings get() = this
    override val actions get() = this
    override val diagnostics get() = this

    override val project = ProjectInfo("preview", "/preview", 1)
    override fun fileTree(mode: TreeViewMode) = TreeNode("root", "preview", NodeKind.Workspace, null)
    override fun readFile(path: String) = ""
    override fun moduleNameForFile(path: String): String? = null
    override fun updateDocument(path: String, text: String) {}
    override fun saveFile(path: String, text: String) {}
    override suspend fun complete(path: String, text: String, offset: Int) = UiCompletionResult(emptyList(), offset, offset)
    override suspend fun analyze(path: String, text: String): List<UiDiagnostic> = emptyList()
    override val indexStatus: StateFlow<IndexUiStatus> = MutableStateFlow(IndexUiStatus())
    override suspend fun searchSymbols(query: String, limit: Int): List<SymbolHit> = emptyList()
    override suspend fun searchMembers(query: String, limit: Int): List<SymbolHit> = emptyList()
    override val buildState: StateFlow<BuildState> = MutableStateFlow(BuildState())
    override fun runBuild() {}
    override fun stopBuild() {}
}

/** Render the canvas from a sample tree with no-op callbacks (no projection effect; a stub backend). */
@Composable
private fun SamplePreview(dark: Boolean, sample: () -> Pair<UiBlockNode, String> = ::sampleFile) {
    val (file, src) = remember { sample() }
    CodeAssistTheme(dark = dark) {
        val drag = remember { DragState() }
        val scope = rememberCoroutineScope()
        val ctx = remember { Ctx("/preview/Sample.java", PreviewBackend, scope, src, null, null, drag, {}, {}, { _, _ -> }, {}) }
        Box(Modifier.width(380.dp).heightIn(min = 560.dp).background(Ca.colors.editorBg).padding(14.dp)) {
            PuzzleCanvas(file, ctx)
        }
    }
}

@Preview
@Composable
private fun PreviewBlocksDark() = SamplePreview(dark = true)

@Preview
@Composable
private fun PreviewBlocksLight() = SamplePreview(dark = false)

@Preview
@Composable
private fun PreviewTypedBlocksDark() = SamplePreview(dark = true, sample = ::typedSampleFile)

@Preview
@Composable
private fun PreviewTypedBlocksLight() = SamplePreview(dark = false, sample = ::typedSampleFile)

@Preview
@Composable
private fun PreviewBlockPalette() {
    CodeAssistTheme(dark = true) {
        val drag = remember { DragState() }
        val scope = rememberCoroutineScope()
        val ctx = remember { Ctx("/preview/Sample.java", PreviewBackend, scope, "", null, null, drag, {}, {}, { _, _ -> }, {}) }
        Box(Modifier.width(380.dp).height(440.dp).background(Ca.colors.bg)) {
            Palette(ctx) {}
        }
    }
}
