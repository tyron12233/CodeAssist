package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiBlockEdit
import dev.ide.ui.backend.UiBlockNode
import dev.ide.ui.backend.UiBlockPart
import dev.ide.ui.backend.UiTextEdit
import dev.ide.ui.editor.blocks.ActiveDrag
import dev.ide.ui.editor.blocks.BODY
import dev.ide.ui.editor.blocks.BlockCanvas
import dev.ide.ui.editor.blocks.BlockCanvasState
import dev.ide.ui.editor.blocks.BlockDrag
import dev.ide.ui.editor.blocks.BlockGeometry
import dev.ide.ui.editor.blocks.BlockInk
import dev.ide.ui.editor.blocks.BlockLabels
import dev.ide.ui.editor.blocks.BlockLayouter
import dev.ide.ui.editor.blocks.BlockPalette
import dev.ide.ui.editor.blocks.BlockSite
import dev.ide.ui.editor.blocks.CanvasIndex
import dev.ide.ui.editor.blocks.ComposeTextMeasure
import dev.ide.ui.editor.blocks.DocRef
import dev.ide.ui.editor.blocks.DragPayload
import dev.ide.ui.editor.blocks.DropTarget
import dev.ide.ui.editor.blocks.EventChoice
import dev.ide.ui.editor.blocks.EventPicker
import dev.ide.ui.editor.blocks.FieldKind
import dev.ide.ui.editor.blocks.FileOutline
import dev.ide.ui.editor.blocks.FunctionForm
import dev.ide.ui.editor.blocks.FunctionSpec
import dev.ide.ui.editor.blocks.Gap
import dev.ide.ui.editor.blocks.Hidden
import dev.ide.ui.editor.blocks.ArgChipKind
import dev.ide.ui.editor.blocks.CallSig
import dev.ide.ui.editor.blocks.LArgChip
import dev.ide.ui.editor.blocks.LArgHole
import dev.ide.ui.editor.blocks.callKey
import dev.ide.ui.editor.blocks.parseParamLabel
import dev.ide.ui.editor.blocks.segmentParts
import dev.ide.ui.editor.blocks.supplied
import dev.ide.ui.editor.blocks.LKind
import dev.ide.ui.editor.blocks.ModifierSheet
import dev.ide.ui.editor.blocks.parseModifierChain
import dev.ide.ui.editor.blocks.OutlineFunction
import dev.ide.ui.editor.blocks.OutlineGroup
import dev.ide.ui.editor.blocks.OutlineScreen
import dev.ide.ui.editor.blocks.OutlineVariable
import dev.ide.ui.editor.blocks.PaletteCategory
import dev.ide.ui.editor.blocks.PaletteEntry
import dev.ide.ui.editor.blocks.PaletteTemplate
import dev.ide.ui.editor.blocks.ScratchCodec
import dev.ide.ui.editor.blocks.ScratchInput
import dev.ide.ui.editor.blocks.ScratchStack
import dev.ide.ui.editor.blocks.SnippetWrap
import dev.ide.ui.editor.blocks.Suggestion
import dev.ide.ui.editor.blocks.VariableForm
import dev.ide.ui.editor.blocks.VariableSpec
import dev.ide.ui.editor.blocks.applyTextEdits
import dev.ide.ui.editor.blocks.buildOutline
import dev.ide.ui.editor.blocks.dedent
import dev.ide.ui.editor.blocks.dragShapeOf
import dev.ide.ui.editor.blocks.findFirst
import dev.ide.ui.editor.blocks.functionCode
import dev.ide.ui.editor.blocks.functionHeader
import dev.ide.ui.editor.blocks.indentAt
import dev.ide.ui.editor.blocks.paletteTemplates
import dev.ide.ui.editor.blocks.parameterNames
import dev.ide.ui.editor.blocks.parseFunctionHeader
import dev.ide.ui.editor.blocks.rememberBlockInk
import dev.ide.ui.editor.blocks.scratchContent
import dev.ide.ui.editor.blocks.signatureText
import dev.ide.ui.editor.blocks.variableCode
import dev.ide.ui.editor.blocks.bodyOf
import dev.ide.ui.editor.blocks.variableTemplates
import dev.ide.ui.editor.blocks.variablesInScope
import dev.ide.ui.editor.blocks.withoutBody
import dev.ide.ui.editor.blocks.InlineInput
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.RangeEdit
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.block_back_to_outline
import dev.ide.ui.generated.resources.block_canvas_description
import dev.ide.ui.generated.resources.block_cannot_project
import dev.ide.ui.generated.resources.block_drop_to_delete
import dev.ide.ui.generated.resources.block_duplicate
import dev.ide.ui.generated.resources.block_edit_expression
import dev.ide.ui.generated.resources.block_focus_hint
import dev.ide.ui.generated.resources.block_form_edit_function
import dev.ide.ui.generated.resources.block_function_missing
import dev.ide.ui.generated.resources.block_form_place_local
import dev.ide.ui.generated.resources.block_keyword_to
import dev.ide.ui.generated.resources.block_palette
import dev.ide.ui.generated.resources.block_pick_function
import dev.ide.ui.generated.resources.block_projecting
import dev.ide.ui.generated.resources.block_properties
import dev.ide.ui.generated.resources.block_preview
import dev.ide.ui.generated.resources.block_wrap_if
import dev.ide.ui.generated.resources.close
import dev.ide.ui.generated.resources.delete
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import dev.ide.ui.theme.LightSyntaxColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The projectional block editor, Sketchware-style: the file opens on an outline of what it declares, and
 * each function opens on its own page as a Scratch-like stack of blocks under its hat. Everything is a live
 * projection of the same buffer the code editor edits; every gesture compiles to a surgical text edit.
 *
 * On a page, blocks are dragged with the stack below them and snap to the nearest connection (a silhouette
 * shows where); a block dropped on empty canvas becomes a scratch stack, kept beside the file rather than in
 * it. The palette's category rail holds real blocks to drag in, and a tap on a token or socket types code
 * with completion.
 */
@OptIn(FlowPreview::class)
@Composable
fun BlockEditor(
    path: String,
    session: EditorSession,
    backend: IdeBackend,
    modifier: Modifier = Modifier,
    /** Renders a `@Composable` page's preview beside its blocks; null where previews do not run. */
    previewHost: dev.ide.ui.ComposePreviewHost? = null,
) {
    val scope = rememberCoroutineScope()
    // The model lives on the tab's session, so switching to Code and back returns to the same page, zoom and
    // palette instead of the outline. Its coroutine scope is this composition's, re-attached on return.
    val model = remember(path, session, backend) {
        (session.viewStates[BLOCKS_VIEW] as? BlockEditorModel)?.takeIf { it.path == path && it.backend === backend }
            ?: BlockEditorModel(path, session, backend, scope).also { session.viewStates[BLOCKS_VIEW] = it }
    }
    model.scope = scope

    // Re-project whenever the buffer changes (keyed on the revision, not the text), debounced off the typing
    // path; right after a block edit the projection is wanted at once.
    LaunchedEffect(path, session.textRevision) {
        if (!model.awaitingProjection) delay(250)
        model.reproject()
    }
    LaunchedEffect(path) { if (!model.scratchLoaded) model.loadScratch() }
    // Persist the scratch stacks a moment after they settle.
    LaunchedEffect(path) {
        snapshotFlow { model.scratch.toList() }.drop(1).debounce(400).collect { backend.blocks.saveScratchStacks(path, ScratchCodec.encode(it)) }
    }

    val d = LocalDensity.current
    val g = remember(d.density) { BlockGeometry(d.density) }
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val baseStyle = Ide.type.codeSmall
    val language = remember(path) { languageFor(path.substringAfterLast('/')) }
    val measure = remember(measurer, baseStyle, language) { ComposeTextMeasure(measurer, baseStyle, baseStyle, language, LightSyntaxColors) }
    val ink = rememberBlockInk(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurface)
    val labels = BlockLabels(keywordTo = stringResource(Res.string.block_keyword_to))
    model.measure = measure
    model.previewHost = previewHost
    model.geometry = g
    model.labels = labels

    BoxWithConstraints(modifier.background(Ide.colors.editorBg)) {
        val wide = maxWidth >= 720.dp
        val outline = model.outline
        when {
            outline == null && model.failed -> Hint(stringResource(Res.string.block_cannot_project))
            outline == null -> Hint(stringResource(Res.string.block_projecting))
            wide -> Row(Modifier.fillMaxSize()) {
                Outline(model, outline, ink, Modifier.width(320.dp).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (model.openKey != null) FunctionPage(model, outline, ink, g, wide = true)
                    else Hint(stringResource(Res.string.block_pick_function))
                }
            }
            model.openKey == null -> Outline(model, outline, ink, Modifier.fillMaxSize())
            else -> FunctionPage(model, outline, ink, g, wide = false)
        }
        model.Dialogs(outline)
    }
}

@Composable
private fun Outline(model: BlockEditorModel, outline: FileOutline, ink: BlockInk, modifier: Modifier) {
    OutlineScreen(
        outline, model.openKey,
        onOpen = { model.open(it) },
        onAddFunction = { model.functionForm = FunctionFormState(null, null, it.key) },
        onAddVariable = { model.variableForm = VariableFormState(null, it.key) },
        onAddEvent = { model.findEvents(it) },
        onEditVariable = { model.variableForm = VariableFormState(it, it.group) },
        onDeleteVariable = { model.edit(DocRef.File, listOf(UiBlockEdit.DeleteBlock(it.node.id))) },
        ink = ink,
        modifier = modifier,
    )
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}

// ---------------------------------------------------------------------------
// A function's page.
// ---------------------------------------------------------------------------

@Composable
private fun FunctionPage(model: BlockEditorModel, outline: FileOutline, ink: BlockInk, g: BlockGeometry, wide: Boolean) {
    val fn = outline.find(model.openKey ?: "", model.openName, model.openGroup)
    Column(Modifier.fillMaxSize()) {
        PageBar(model, fn, wide, outline.kotlin)
        if (fn == null) { Hint(stringResource(Res.string.block_function_missing)); return@Column }
        val showPreview = model.previewOpen && isComposable(fn)
        // The preview sits beside the blocks when there is room, under them on a phone.
        val split: @Composable (@Composable (Modifier) -> Unit) -> Unit = { canvas ->
            if (!showPreview) canvas(Modifier.weight(1f).fillMaxWidth())
            else if (wide) Row(Modifier.weight(1f).fillMaxWidth()) {
                canvas(Modifier.weight(1f).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                PagePreview(model, fn, Modifier.width(380.dp).fillMaxHeight())
            } else {
                canvas(Modifier.weight(1f).fillMaxWidth())
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                PagePreview(model, fn, Modifier.fillMaxWidth().height(320.dp))
            }
        }
        split { canvasModifier ->
        Box(canvasModifier.onGloballyPositioned { model.canvasWidth = it.size.width.toFloat() }) {
            val drag = model.drag
            LaunchedEffect(fn.key, model.projectedText) { model.loadSignatures(fn) }
            val layoutFor = remember(fn.node, model.projectedText, model.scratchVersion, model.signatureVersion, model.lambdaPages.toList(), model.measure, model.geometry) {
                { hidden: Hidden?, gap: Gap? -> model.layoutPage(fn, hidden, gap) }
            }
            BlockCanvas(
                layoutFor = layoutFor,
                state = model.canvas,
                drag = drag,
                ink = ink,
                selected = model.selected?.let { it.doc to it.blockId },
                onTap = { hit, index -> model.tap(hit, index, fn) },
                startDrag = { hit, index, at -> model.startDrag(hit, index, at) },
                onDrop = { d, target, landing, trash -> model.drop(d, target, landing, trash, fn) },
                modifier = Modifier.fillMaxSize(),
                overlayRect = { index -> model.editRect(index) },
                overlay = model.editing?.let { e -> { EditOverlay(model, e) } },
                description = stringResource(Res.string.block_canvas_description),
            )
            if (model.paletteOpen) {
                val paletteW = minOf(340.dp, (LocalDensity.current.run { model.canvasWidth.toDp() }) * 0.82f).coerceAtLeast(240.dp)
                BlockPalette(
                    categories = model.categories(outline.kotlin),
                    entriesFor = { model.paletteEntries(it, outline, fn) },
                    query = model.paletteQuery,
                    onQuery = { model.searchPalette(it) },
                    searchResults = model.searchResults,
                    searching = model.searching,
                    onAddVariable = { model.variableForm = VariableFormState(null, dev.ide.ui.editor.blocks.LOCAL_PLACE) },
                    drag = drag,
                    ink = ink,
                    g = g,
                    scopeTitle = model.scopeFocus?.receiver,
                    // Faded out (not removed: the lifting gesture lives in it) while a block is being dragged.
                    modifier = Modifier.width(paletteW).fillMaxHeight().graphicsLayer { alpha = if (drag.active != null) 0f else 1f },
                )
            }
            model.selected?.let { sel ->
                if (drag.active == null && model.editing == null) ActionBar(model, sel, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
            }
            if (drag.active != null) Trash(drag, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
        }
        }
    }
    LaunchedEffect(fn?.key) { model.paletteFor(outline, fn) }
}

private fun isComposable(fn: OutlineFunction) = "@Composable" in fn.signature

/** The page's Compose preview: the `@Preview` that shows this function, rendered by the platform host. */
@Composable
private fun PagePreview(model: BlockEditorModel, fn: OutlineFunction, modifier: Modifier) {
    dev.ide.ui.editor.preview.ComposePreviewPane(
        path = model.path, text = model.projectedText, backend = model.backend, host = model.previewHost,
        modifier = modifier.clipToBounds(), selected = model.previewFor(fn), split = true,
    )
}

@Composable
private fun PageBar(model: BlockEditorModel, fn: OutlineFunction?, wide: Boolean, kotlin: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(46.dp).background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val lambda = model.lambdaPages.lastOrNull()
        if (!wide || lambda != null) {
            BarButton(CaIcons.chevronLeft, stringResource(Res.string.block_back_to_outline)) { model.closePage() }
        }
        Column(Modifier.weight(1f)) {
            Text(lambda?.title ?: fn?.name ?: "", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val lang = remember(kotlin) { languageFor(if (kotlin) "a.kt" else "a.java") }
            val syntax = Ide.colors.syntax
            Text(
                remember(fn?.signature, syntax) { highlight(fn?.signature ?: "", lang, syntax) },
                style = Ide.type.codeSmall, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = 0.85f },
            )
        }
        if (fn != null && parseFunctionHeader(fn.signature, kotlin) != null) {
            BarButton(CaIcons.gear, stringResource(Res.string.block_form_edit_function)) { model.editFunction(fn) }
        }
        if (fn != null && kotlin && isComposable(fn)) {
            BarButton(CaIcons.eye, stringResource(Res.string.block_preview), on = model.previewOpen) { model.previewOpen = !model.previewOpen }
        }
        BarButton(CaIcons.layers, stringResource(Res.string.block_palette), on = model.paletteOpen) { model.paletteOpen = !model.paletteOpen }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, on: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(Ca.radius.control))
            .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(18.dp), tint = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun Trash(drag: BlockDrag, modifier: Modifier) {
    val on = drag.overTrash
    Row(
        modifier.clip(RoundedCornerShape(Ca.radius.control))
            .background(if (on) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceContainerHigh)
            .onGloballyPositioned { drag.trash["bar"] = it.boundsInRoot() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fg = if (on) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(CaIcons.close, null, Modifier.size(16.dp), tint = fg)
        Text(stringResource(Res.string.block_drop_to_delete), color = fg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ActionBar(model: BlockEditorModel, sel: Selection, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(Ca.radius.lg)).background(Ide.colors.glassThick).padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        val props = remember(sel, model.signatureVersion) { model.propertiesOf(sel) }
        if (props != null) {
            ActionItem(CaIcons.plus, stringResource(Res.string.block_properties)) { model.propertySheet = props }
        }
        if (sel.inList) {
            ActionItem(CaIcons.layers, stringResource(Res.string.block_wrap_if)) { model.wrapInIf(sel) }
            ActionItem(CaIcons.copy, stringResource(Res.string.block_duplicate)) { model.duplicate(sel) }
        }
        ActionItem(CaIcons.close, stringResource(Res.string.delete), tint = MaterialTheme.colorScheme.error) { model.edit(sel.doc, listOf(UiBlockEdit.DeleteBlock(sel.blockId))) }
    }
}

@Composable
private fun ActionItem(icon: ImageVector, label: String, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Column(
        Modifier.widthIn(min = 46.dp).clip(RoundedCornerShape(Ca.radius.sm)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Icon(icon, label, Modifier.size(18.dp), tint = tint)
        Text(label, color = MaterialTheme.colorScheme.outline, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The inline editor for the token or socket being typed into, with completion against its document. */
@Composable
private fun EditOverlay(model: BlockEditorModel, e: EditTarget) {
    val ins = e.insert
    // A new argument is typed in place: the source it completes against already has its `, ` / `name = `.
    val source = model.docText(e.doc).let { t -> if (ins != null && ins.closeAt in 0..t.length) t.substring(0, ins.closeAt) + ins.leading + t.substring(ins.closeAt) else t }
    val ctx = Ctx(model.path, model.backend, model.scope, source, onEdit = { model.editing = it })
    InlineInput(e.initial, docStart = e.docStart, expectedValueKind = e.valueKind, ctx = ctx) { text, extra -> model.commit(e, text, extra) }
}

// ---------------------------------------------------------------------------
// State.
// ---------------------------------------------------------------------------

/** A token ([role] set) or socket ([slotIndex] set) being typed into. */
internal data class EditTarget(
    val doc: DocRef, val blockId: String, val role: String?, val slotIndex: Int?,
    val initial: String, val docStart: Int, val valueKind: String?,
    /** Typing a new argument into a hole rather than editing what is there. */
    val insert: ArgInsert? = null,
)

/**
 * Where a new argument goes: argument [index] of segment [link] of [callId], by [name] when set. [closeAt] and
 * [leading] place it in the source while it is typed (for completion); [key]/[paramIndex] find its hole.
 */
internal data class ArgInsert(val callId: String, val link: Int, val index: Int, val name: String?, val closeAt: Int, val leading: String, val key: String, val paramIndex: Int)

/** The selected statement and where it sits (for wrap/duplicate). */
internal data class Selection(val doc: DocRef, val blockId: String, val ownerId: String, val slotIndex: Int, val index: Int, val inList: Boolean, val text: String)

/**
 * What the inline editor needs: the document it edits within (for completion), the host port, and a way to
 * close the editor (`startEdit(null)`).
 */
internal class Ctx(
    val path: String,
    val backend: IdeBackend,
    val scope: CoroutineScope,
    val source: String,
    private val onEdit: (EditTarget?) -> Unit,
) {
    fun startEdit(t: EditTarget?) = onEdit(t)
}

/** The property sheet of call [callId]'s segment [link]: its [optional] parameters still to add, and [sig]'s overloads. */
internal class PropertySheetState(val callId: String, val link: Int, val key: String, val sig: CallSig, val optional: List<dev.ide.ui.editor.blocks.MissingParam>)

/** The modifier chain in socket [slotIndex] of [ownerId] (source [start]..[end]), being edited in its sheet. */
internal class ModifierSheetState(
    val doc: DocRef, val ownerId: String, val slotIndex: Int, val chain: dev.ide.ui.editor.blocks.ModifierChain, val group: String,
    val start: Int, val end: Int,
)

internal class VariableFormState(val existing: OutlineVariable?, val group: String)
internal class FunctionFormState(val existing: OutlineFunction?, val spec: FunctionSpec?, val group: String)

/** The key the Blocks view keeps its state under in [EditorSession.viewStates]. */
private const val BLOCKS_VIEW = "blocks"

internal class BlockEditorModel(
    val path: String,
    private val session: EditorSession,
    val backend: IdeBackend,
    var scope: CoroutineScope,
) {
    val kotlin = path.endsWith(".kt") || path.endsWith(".kts")
    private val wrap = SnippetWrap(kotlin)

    var tree by mutableStateOf<UiBlockNode?>(null); private set
    var projectedText by mutableStateOf(""); private set
    var failed by mutableStateOf(false); private set
    var outline by mutableStateOf<FileOutline?>(null); private set
    /** A block edit was applied to the file and its projection has not caught up: hold further edits. */
    var awaitingProjection = false; private set

    var openKey by mutableStateOf<String?>(null); private set
    var openName by mutableStateOf<String?>(null); private set
    var openGroup by mutableStateOf<String?>(null); private set
    /** A function just added: open it once the projection shows it. */
    private var pendingOpen: Pair<String, String>? = null

    var editing by mutableStateOf<EditTarget?>(null)
    var selected by mutableStateOf<Selection?>(null)
    var paletteOpen by mutableStateOf(false)
    var variableForm by mutableStateOf<VariableFormState?>(null)
    var functionForm by mutableStateOf<FunctionFormState?>(null)
    var events by mutableStateOf<List<EventChoice>?>(null)
    var eventsOpen by mutableStateOf(false)
    var focus by mutableStateOf<UiBlockNode?>(null)
    var modifierSheet by mutableStateOf<ModifierSheetState?>(null)

    val canvas = BlockCanvasState()
    val drag = BlockDrag()
    var canvasWidth by mutableStateOf(0f)
    var previewHost: dev.ide.ui.ComposePreviewHost? = null
    var previewOpen by mutableStateOf(false)

    /**
     * The `@Preview` that shows [fn]: [fn] itself when it is one, else a preview in the file that calls it,
     * else null (the pane then shows the file's first preview).
     */
    fun previewFor(fn: OutlineFunction): String? {
        val all = outline?.functions ?: return null
        if ("@Preview" in fn.signature) return fn.name
        return all.firstOrNull { p -> "@Preview" in p.signature && Regex("\\b" + Regex.escape(fn.name) + "\\s*\\(").containsMatchIn(slice(DocRef.File, p.node.start, p.node.end)) }?.name
    }

    lateinit var measure: dev.ide.ui.editor.blocks.TextMeasure
    lateinit var geometry: BlockGeometry
    var labels = BlockLabels()

    // ---- the file ----

    suspend fun reproject() {
        val text = session.doc.text
        val projected = runCatching { backend.blocks.projectBlocks(path, text) }
        val root = projected.getOrNull()
        tree = root
        failed = projected.isFailure || root == null
        projectedText = text
        outline = root?.let { buildOutline(it, text, kotlin) }
        awaitingProjection = false
        editing = null; selected = null
        pendingOpen?.let { (group, name) ->
            outline?.functions?.firstOrNull { it.group == group && it.name == name }?.let { open(it); pendingOpen = null }
        }
        openKey?.let { key -> outline?.find(key, openName, openGroup)?.let { openKey = it.key; openName = it.name } }
    }

    fun open(fn: OutlineFunction) {
        if (openKey != fn.key) { canvas.reset(); lambdaPages.clear(); scopeFocus = null }
        openKey = fn.key; openName = fn.name; openGroup = fn.group
        selected = null; editing = null
    }

    fun closePage() {
        if (lambdaPages.isNotEmpty()) { lambdaPages.removeAt(lambdaPages.lastIndex); selected = null; editing = null; return }
        openKey = null; openName = null; openGroup = null; paletteOpen = false; selected = null; editing = null; scopeFocus = null
    }

    /** The scratch page key: stable across a function's body edits (unlike its outline key). */
    private fun pageOf(fn: OutlineFunction) = "${fn.group}/${fn.kind}:${fn.name}"

    // ---- scratch stacks ----

    val scratch = mutableStateListOf<ScratchStack>()
    private val scratchRoots = mutableStateMapOf<Long, Pair<String, UiBlockNode>>()
    var scratchVersion by mutableStateOf(0); private set
    private var nextKey = 1L

    var scratchLoaded = false; private set

    suspend fun loadScratch() {
        scratchLoaded = true
        val stacks = ScratchCodec.decode(runCatching { backend.blocks.loadScratchStacks(path) }.getOrNull())
        scratch.clear(); scratch.addAll(stacks)
        nextKey = (stacks.maxOfOrNull { it.key } ?: 0L) + 1
        stacks.forEach { projectScratch(it) }
    }

    private suspend fun projectScratch(s: ScratchStack) {
        val wrapped = wrap.wrap(s.text, s.isValue)
        val root = runCatching { backend.blocks.projectBlocks(path, wrapped) }.getOrNull() ?: return
        scratchRoots[s.key] = wrapped to root
        scratchVersion++
    }

    private fun setScratch(updated: ScratchStack?, key: Long) {
        val i = scratch.indexOfFirst { it.key == key }
        if (updated == null || updated.text.isBlank()) {
            if (i >= 0) scratch.removeAt(i)
            scratchRoots.remove(key); scratchVersion++
            return
        }
        if (i >= 0) scratch[i] = updated else scratch += updated
        scope.launch { projectScratch(updated) }
    }

    private fun addScratch(page: String, at: Offset, text: String, isValue: Boolean) =
        setScratch(ScratchStack(nextKey, page, at.x, at.y, isValue, text), nextKey++)

    private fun stackOf(doc: DocRef): ScratchStack? = (doc as? DocRef.Scratch)?.let { d -> scratch.firstOrNull { it.key == d.key } }

    fun docText(doc: DocRef): String = when (doc) {
        DocRef.File -> projectedText
        is DocRef.Scratch -> scratchRoots[doc.key]?.first ?: ""
    }

    private fun docRoot(doc: DocRef): UiBlockNode? = when (doc) {
        DocRef.File -> tree
        is DocRef.Scratch -> scratchRoots[doc.key]?.second
    }

    // ---- call signatures ----

    /** The callee parameters of the open page's calls, by [callKey]; filled in after each projection. */
    private val signatures = mutableStateMapOf<String, CallSig>()
    /** Optional parameters picked from the property sheet, by call key: shown as holes to fill. */
    private val revealed = mutableStateMapOf<String, Set<Int>>()
    /** Calls whose optional parameters are unfolded on the block. */
    private val expandedCalls = androidx.compose.runtime.mutableStateSetOf<String>()
    private val overloadChoice = HashMap<String, Int>()
    var signatureVersion by mutableStateOf(0); private set
    private var signaturesFor: Pair<String, String>? = null

    /**
     * Ask the resolver what each call segment on [fn]'s page takes: signature help at the segment's last
     * argument (or inside its empty `()`), the same query the code editor's parameter popup makes.
     */
    suspend fun loadSignatures(fn: OutlineFunction) {
        val text = projectedText
        if (signaturesFor == fn.key to text) return
        signaturesFor = fn.key to text
        // Where to ask: after the last argument, in an empty `()`, or (a call written with only a trailing
        // lambda) in a `()` added after its name, so `Column { }` still learns its lambda's receiver.
        val probes = HashMap<String, Pair<String, Int>>()
        fun visit(n: UiBlockNode) {
            if (n.kind == "method_call") {
                val links = n.parts.count { it is UiBlockPart.Field && it.editable && Regex("name\\d*").matches(it.role) }
                for (link in 0 until links) {
                    val seg = segmentParts(n, link) ?: continue
                    val s = supplied(seg)
                    val at = s.written.lastOrNull()?.end ?: s.emptySlot?.start
                    if (at != null) { probes[callKey(n.start, link)] = text to at; continue }
                    val name = seg.firstOrNull() as? UiBlockPart.Field ?: continue
                    if (s.trailingLambda && name.end in 0..text.length) {
                        probes[callKey(n.start, link)] = (text.substring(0, name.end) + "()" + text.substring(name.end)) to name.end + 1
                    }
                }
            }
            for (p in n.parts) if (p is UiBlockPart.Slot) p.children.forEach(::visit)
        }
        visit(fn.node)
        val resolved = HashMap<String, CallSig>()
        for ((key, probe) in probes) {
            val (probed, at) = probe
            val help = runCatching { backend.editor.signatureHelp(path, probed, at) }.getOrNull() ?: continue
            if (help.signatures.isEmpty()) continue
            val overloads = help.signatures.map { sig -> sig.parameters.map { parseParamLabel(it.label, kotlin) } }
            val chosen = (overloadChoice[key] ?: help.activeSignature).coerceIn(0, overloads.size - 1)
            resolved[key] = CallSig(overloads, chosen)
        }
        if (projectedText != text) return
        signatures.clear(); signatures.putAll(resolved)
        signatureVersion++
    }

    // ---- layout ----

    fun layoutPage(fn: OutlineFunction, hidden: Hidden?, gap: Gap?): dev.ide.ui.editor.blocks.CanvasLayout {
        val layouter = BlockLayouter(
            measure, geometry, labels, hidden = hidden, gap = gap, signatures = signatures, revealed = revealed, expandedCalls = expandedCalls,
            composables = outline?.functions?.filter { "@Composable" in it.signature }?.map { it.name }?.toSet() ?: emptySet(), kotlin = kotlin,
        )
        openLambda()?.let { return layouter.layoutLambda(it, lambdaPages.last().title, projectedText) }
        return layouter.layoutFunction(fn.node, projectedText, scratch.filter { it.page == pageOf(fn) }.mapNotNull { s ->
            scratchRoots[s.key]?.let { (text, root) -> ScratchInput(s.key, s.x, s.y, root, text, s.isValue) }
        })
    }

    fun editRect(index: CanvasIndex): Rect? {
        val e = editing ?: return null
        e.insert?.let { ins ->
            return index.actions.firstOrNull { a ->
                a.doc == e.doc && when (val it = a.item) {
                    is LArgHole -> it.key == ins.key && it.paramIndex == ins.paramIndex
                    is LArgChip -> it.key == ins.key && it.kind == ArgChipKind.Add && ins.paramIndex < 0
                    else -> false
                }
            }?.rect
        }
        return if (e.role != null) index.tokens.firstOrNull { it.doc == e.doc && it.token.blockId == e.blockId && it.token.field.role == e.role }?.rect
        else index.sockets.firstOrNull { it.doc == e.doc && it.socket.ownerId == e.blockId && it.socket.slotIndex == e.slotIndex }?.rect
    }

    // ---- applying edits ----

    /**
     * Compile [edits] against [doc]'s projected text, all at once (so their offsets agree), and apply them
     * with [extra] text edits; [then] runs after. A refused edit (stale ids, overlap) changes nothing.
     */
    fun edit(doc: DocRef, edits: List<UiBlockEdit>, extra: List<UiTextEdit> = emptyList(), then: (() -> Unit)? = null) {
        if (awaitingProjection && doc == DocRef.File) return
        editing = null; selected = null
        val text = docText(doc)
        scope.launch {
            val compiled = ArrayList<UiTextEdit>()
            for (e in edits) {
                val r = runCatching { backend.blocks.applyBlockEdit(path, text, e) }.getOrDefault(emptyList())
                if (r.isEmpty()) return@launch
                compiled += r
            }
            applyText(doc, text, compiled + extra)
            then?.invoke()
        }
    }

    private fun applyText(doc: DocRef, text: String, all: List<UiTextEdit>) {
        if (all.isEmpty()) return
        when (doc) {
            DocRef.File -> {
                if (applyTextEdits(text, all) == null) return
                awaitingProjection = true
                session.applyEdits(all.map { RangeEdit(it.start, it.end, it.newText, it.start + it.newText.length) }, TextRange(all.minOf { it.start }.coerceAtLeast(0)))
            }
            is DocRef.Scratch -> {
                val s = stackOf(doc) ?: return
                val next = applyTextEdits(text, all)?.let { wrap.unwrap(it, s.isValue) } ?: return
                setScratch(s.copy(text = next), s.key)
            }
        }
    }

    // ---- taps ----

    fun tap(hit: CanvasIndex.Hit?, index: CanvasIndex, fn: OutlineFunction) {
        editing = null
        when (hit) {
            null -> selected = null
            is CanvasIndex.Hit.Action -> argAction(hit.placed)
            is CanvasIndex.Hit.Token -> {
                val f = hit.placed.token.field
                if (f.editable) editing = EditTarget(hit.placed.doc, hit.placed.token.blockId, f.role, null, f.text, f.start, null)
            }
            is CanvasIndex.Hit.Socket -> editSocket(hit.placed.doc, hit.placed.socket.ownerId, hit.placed.socket.slotIndex, hit.placed.socket.slot)
            is CanvasIndex.Hit.Block -> {
                val p = hit.placed
                val b = p.block
                when {
                    b.kind == LKind.Hat -> editFunction(fn)
                    b.kind == LKind.Chip -> focus = b.node
                    b.modifierChain && p.site is BlockSite.InSocket -> {
                        val site = p.site as BlockSite.InSocket
                        val n = b.node ?: return
                        val src = docText(site.doc)
                        val text = slice(site.doc, n.start, n.end)
                        val second = text.substringAfter('\n', "").takeWhile { it == ' ' || it == '\t' }
                        val indent = if ('\n' in text) second else indentAt(src, n.start) + "    "
                        parseModifierChain(text, indent)?.let { modifierSheet = ModifierSheetState(site.doc, site.ownerId, site.slotIndex, it, fn.group, n.start, n.end) }
                            ?: editSocket(site.doc, site.ownerId, site.slotIndex, findSlot(site.doc, site.ownerId, site.slotIndex) ?: return)
                    }
                    p.site is BlockSite.InSocket -> {
                        val site = p.site as BlockSite.InSocket
                        val slot = findSlot(site.doc, site.ownerId, site.slotIndex) ?: return
                        editSocket(site.doc, site.ownerId, site.slotIndex, slot)
                    }
                    p.site is BlockSite.InList -> {
                        val site = p.site as BlockSite.InList
                        val id = b.id ?: return
                        selected = if (selected?.blockId == id && selected?.doc == site.doc) null
                        else Selection(site.doc, id, site.ownerId, site.slotIndex, site.index, site.droppable, b.node?.let { slice(site.doc, it.start, it.end) } ?: "")
                        // The body this block sits in becomes the palette's scope, if it is a scoped lambda.
                        if (site.doc == DocRef.File) scopeOfBody(site.ownerId)?.let { (call, link) -> focusScope(site.ownerId, site.slotIndex, call.id, link) }
                    }
                }
            }
        }
    }

    /** A tap on an argument hole (type the argument), `+N`/`−` (show or fold optional ones), or `1/3`. */
    private fun argAction(placed: dev.ide.ui.editor.blocks.PlacedAction) {
        when (val item = placed.item) {
            is LArgHole -> editing = EditTarget(
                placed.doc, item.callId, null, null, "", docStart = if (item.closeAt >= 0) item.closeAt + item.leading.length else -1,
                valueKind = item.shape.name.lowercase(), insert = ArgInsert(item.callId, item.link, item.insertIndex, if (item.named) item.name else null, item.closeAt, item.leading, item.key, item.paramIndex),
            )
            is LArgChip -> when (item.kind) {
                ArgChipKind.More -> { expandedCalls += item.key; signatureVersion++ }
                ArgChipKind.Fewer -> { expandedCalls -= item.key; signatureVersion++ }
                ArgChipKind.Overload -> {}
                ArgChipKind.Add -> editing = EditTarget(
                    placed.doc, item.callId, null, null, "", docStart = if (item.closeAt >= 0) item.closeAt + item.leading.length else -1,
                    valueKind = null, insert = ArgInsert(item.callId, item.link, item.insertIndex, null, item.closeAt, item.leading, item.key, -1),
                )
                ArgChipKind.Open -> node(placed.doc, item.key)?.let { block ->
                    val call = node(placed.doc, item.callId)
                    val name = call?.let { segmentParts(it, item.link)?.firstOrNull() as? UiBlockPart.Field }?.text ?: ""
                    lambdaPages += LambdaPage(block.start, "$name { }")
                    selected = null
                }
                ArgChipKind.Scope -> {
                    val (owner, slot) = item.key.substringBeforeLast(':') to (item.key.substringAfterLast(':').toIntOrNull() ?: 0)
                    focusScope(owner, slot, item.callId, item.link)
                    scopePickerOpen = true
                }
            }
            else -> {}
        }
    }

    private fun editSocket(doc: DocRef, ownerId: String, slotIndex: Int, slot: UiBlockPart.Slot) {
        editing = EditTarget(doc, ownerId, null, slotIndex, slice(doc, slot.start, slot.end), slot.start, slot.valueKind)
    }

    // ---- lambda pages ----

    /** A lambda body opened on its own page: found again by its source start, titled after its call. */
    data class LambdaPage(val start: Int, val title: String)

    /** Lambda bodies opened from the current function page, innermost last. */
    val lambdaPages = mutableStateListOf<LambdaPage>()

    /** The `{ … }` block node of the innermost open lambda page, if it is still in the file. */
    fun openLambda(): UiBlockNode? {
        val page = lambdaPages.lastOrNull() ?: return null
        return tree?.let { root -> findFirst(root) { it.kind == "block" && it.start == page.start } }
    }

    // ---- scopes ----

    /** A lambda body in focus for the palette's Scope tab: its list slot, and the call whose lambda it is. */
    data class ScopeFocus(val ownerId: String, val slotIndex: Int, val callId: String, val link: Int, val receiver: String)

    var scopeFocus by mutableStateOf<ScopeFocus?>(null); private set
    var scopeFunctions by mutableStateOf<List<PaletteTemplate>?>(null); private set
    var scopePickerOpen by mutableStateOf(false)
    private var scopeJob: kotlinx.coroutines.Job? = null

    /** The call (and segment) whose trailing lambda is the body block [bodyId], if it runs with a receiver. */
    private fun scopeOfBody(bodyId: String): Pair<UiBlockNode, Int>? {
        val root = tree ?: return null
        var found: Pair<UiBlockNode, Int>? = null
        findFirst(root) { n ->
            if (n.kind == "method_call") {
                val at = n.parts.indexOfFirst { p -> p is UiBlockPart.Slot && p.category == "STATEMENT" && p.children.singleOrNull()?.id == bodyId }
                if (at >= 0) {
                    val link = (n.parts.subList(0, at).count { it is UiBlockPart.Field && it.editable && Regex("name\\d*").matches(it.role) } - 1).coerceAtLeast(0)
                    val sig = signatures[callKey(n.start, link)]
                    if (sig != null && dev.ide.ui.editor.blocks.lambdaReceiver(sig.params.lastOrNull()?.type ?: "") != null) found = n to link
                }
            }
            found != null
        }
        return found
    }

    /** Make the lambda body [ownerId]'s list the palette's scope, and load what it can call. */
    fun focusScope(ownerId: String, slotIndex: Int, callId: String, link: Int) {
        val call = node(DocRef.File, callId) ?: return
        val receiver = signatures[callKey(call.start, link)]?.params?.lastOrNull()?.type?.let { dev.ide.ui.editor.blocks.lambdaReceiver(it) } ?: return
        val focus = ScopeFocus(ownerId, slotIndex, callId, link, receiver)
        if (focus == scopeFocus && scopeFunctions != null) return
        scopeFocus = focus
        scopeFunctions = null
        scopeJob?.cancel()
        scopeJob = scope.launch { scopeFunctions = loadScopeFunctions(focus) }
    }

    /**
     * What a lambda body's scope adds: completion on an empty line at the end of the body, less completion on
     * an empty line at the top of the enclosing function (functions only), each as a call template carrying
     * the import completion would add for it.
     */
    private suspend fun loadScopeFunctions(focus: ScopeFocus): List<PaletteTemplate> {
        val src = projectedText
        val owner = node(DocRef.File, focus.ownerId) ?: return emptyList()
        val list = owner.parts.filterIsInstance<UiBlockPart.Slot>().getOrNull(focus.slotIndex) ?: return emptyList()
        val inside = list.children.lastOrNull()?.end ?: list.start
        val fn = outline?.find(openKey ?: "", openName, openGroup)
        val fnBody = fn?.let { bodyOf(it.node) }
        val fnList = fnBody?.let { b -> node(DocRef.File, b.ownerId)?.parts?.filterIsInstance<UiBlockPart.Slot>()?.getOrNull(b.slotIndex) }
        val outside = fnList?.start ?: return emptyList()
        suspend fun methodsAt(at: Int): List<dev.ide.ui.backend.UiCompletionItem> {
            val indent = indentAt(src, at) + "    "
            val probed = src.substring(0, at) + "\n" + indent + src.substring(at)
            val result = runCatching { backend.editor.complete(path, probed, at + 1 + indent.length) }.getOrNull() ?: return emptyList()
            return result.items.filter { it.kind == dev.ide.ui.backend.UiCompletionKind.Method }
        }
        val outer = methodsAt(outside).map { it.label }.toSet()
        return methodsAt(inside).filter { it.label !in outer }.distinctBy { it.label }.sortedBy { it.label.lowercase() }.take(40).map { item ->
            val imports = item.additionalEdits.mapNotNull { Regex("import\\s+([\\w.]+)").find(it.newText)?.groupValues?.get(1) }
            PaletteTemplate(dev.ide.ui.editor.blocks.callTemplate(item.label), false, imports)
        }
    }

    /** Add [t] at the end of the focused scope's body (the scope `+` handle's quick list). */
    fun insertInScope(t: PaletteTemplate) {
        val focus = scopeFocus ?: return
        scopePickerOpen = false
        val owner = node(DocRef.File, focus.ownerId) ?: return
        val size = owner.parts.filterIsInstance<UiBlockPart.Slot>().getOrNull(focus.slotIndex)?.children?.size ?: return
        val plain = withoutBody(t.text)
        edit(DocRef.File, listOf(UiBlockEdit.InsertTemplate(focus.ownerId, focus.slotIndex, size, plain)), importsFor(DocRef.File, plain) + templateImports(t))
    }

    /** The import edits a template's own imports need in the file now. */
    private fun templateImports(t: PaletteTemplate): List<UiTextEdit> {
        if (t.imports.isEmpty() || !kotlin) return emptyList()
        val declared = Regex("^\\s*import\\s+([\\w.]*\\w(?:\\.\\*)?)", RegexOption.MULTILINE).findAll(projectedText).map { it.groupValues[1] }.toSet()
        return dev.ide.ui.editor.blocks.importEdits(projectedText, t.imports.filter { it !in declared && "${it.substringBeforeLast('.')}.*" !in declared })
    }

    // ---- the property picker ----

    /** The selected statement's call (a Kotlin call statement, or what a Java expression statement calls). */
    private fun callOf(sel: Selection): UiBlockNode? {
        val n = node(sel.doc, sel.blockId) ?: return null
        if (n.kind == "method_call") return n
        return n.parts.filterIsInstance<UiBlockPart.Slot>().firstNotNullOfOrNull { s -> s.children.singleOrNull()?.takeIf { it.kind == "method_call" } }
    }

    /** What the property sheet for the selection offers, or null when the callee is not known. */
    fun propertiesOf(sel: Selection): PropertySheetState? {
        if (sel.doc != DocRef.File) return null
        val call = callOf(sel) ?: return null
        val links = call.parts.count { it is UiBlockPart.Field && it.editable && Regex("name\\d*").matches(it.role) }
        val link = (links - 1).coerceAtLeast(0)
        val key = callKey(call.start, link)
        val sig = signatures[key] ?: return null
        val seg = segmentParts(call, link) ?: return null
        val s = supplied(seg)
        val open = dev.ide.ui.editor.blocks.missingParams(sig, s, kotlin, expanded = true).first.filter { it.param.optional && it.index !in (revealed[key] ?: emptySet()) }
        if (open.isEmpty() && sig.overloads.size < 2) return null
        return PropertySheetState(call.id, link, key, sig, open)
    }

    var propertySheet by mutableStateOf<PropertySheetState?>(null)

    /** Show optional parameter [m] as a hole on its call and start typing into it. */
    fun pickProperty(state: PropertySheetState, m: dev.ide.ui.editor.blocks.MissingParam) {
        propertySheet = null
        revealed[state.key] = (revealed[state.key] ?: emptySet()) + m.index
        signatureVersion++
        val call = node(DocRef.File, state.callId) ?: return
        val s = supplied(segmentParts(call, state.link) ?: return)
        val closeAt = s.written.lastOrNull()?.end ?: s.emptySlot?.start ?: -1
        val named = m.named || !(m.index == s.positional && s.named.isEmpty())
        val leading = (if (s.written.isNotEmpty()) ", " else "") + if (named && m.param.name != null) "${m.param.name} = " else ""
        editing = EditTarget(
            DocRef.File, state.callId, null, null, "", docStart = if (closeAt >= 0) closeAt + leading.length else -1,
            valueKind = dev.ide.ui.editor.blocks.shapeOfType(m.param.type).name.lowercase(),
            insert = ArgInsert(state.callId, state.link, if (named) s.written.size else m.index, if (named) m.param.name else null, closeAt, leading, state.key, m.index),
        )
    }

    fun pickOverload(state: PropertySheetState, index: Int) {
        overloadChoice[state.key] = index
        signatures[state.key]?.let { signatures[state.key] = it.copy(chosen = index) }
        signatureVersion++
        propertySheet = null
    }

    /** For a Kotlin file: the imports [code] would need here, as edits (none for a scratch stack or Java). */
    fun importsFor(doc: DocRef, code: String): List<UiTextEdit> =
        if (!kotlin || doc != DocRef.File) emptyList() else dev.ide.ui.editor.blocks.importEdits(projectedText, dev.ide.ui.editor.blocks.missingImports(code, projectedText))

    /** Write what was typed into [e]: a token, a socket, or a new argument. */
    fun commit(e: EditTarget, text: String, extra: List<UiTextEdit>) {
        editing = null
        val ins = e.insert
        val edit = when {
            ins != null -> if (text.isBlank()) null else UiBlockEdit.InsertArgument(ins.callId, ins.link, ins.index, (ins.name?.let { "$it = " } ?: "") + text.trim())
            e.role != null -> UiBlockEdit.SetField(e.blockId, e.role, text)
            // Clearing an argument removes it (and its comma) rather than leaving `f(a, )`.
            text.isBlank() && isArgument(e.doc, e.blockId, e.slotIndex ?: 0) -> UiBlockEdit.RemoveArgument(e.blockId, e.slotIndex ?: 0)
            else -> UiBlockEdit.ReplaceSlot(e.blockId, e.slotIndex ?: 0, text)
        } ?: return
        // Auto-import edits sit above the insertion point, so they apply to the file as they are.
        edit(e.doc, listOf(edit), if (e.doc == DocRef.File) extra.filter { ins == null || it.end <= ins.closeAt } else emptyList())
    }

    /** Whether slot [slotIndex] of [ownerId] is a call argument holding a value. */
    fun isArgument(doc: DocRef, ownerId: String, slotIndex: Int): Boolean =
        findSlot(doc, ownerId, slotIndex)?.let { it.category == "ARGUMENT" && it.children.isNotEmpty() } == true

    private fun slice(doc: DocRef, start: Int, end: Int): String {
        val t = docText(doc)
        return if (start in 0..t.length && end in start..t.length) t.substring(start, end) else ""
    }

    private fun node(doc: DocRef, id: String): UiBlockNode? = docRoot(doc)?.let { r -> findFirst(r) { it.id == id } }

    private fun findSlot(doc: DocRef, ownerId: String, slotIndex: Int): UiBlockPart.Slot? =
        node(doc, ownerId)?.parts?.filterIsInstance<UiBlockPart.Slot>()?.getOrNull(slotIndex)

    // ---- the action bar ----

    fun wrapInIf(sel: Selection) = edit(sel.doc, listOf(UiBlockEdit.WrapRange(sel.blockId, 1, "if (true) {\n$BODY\n}")))

    fun duplicate(sel: Selection) {
        val text = dedent(sel.text, indentAt(docText(sel.doc), node(sel.doc, sel.blockId)?.start ?: 0))
        edit(sel.doc, listOf(UiBlockEdit.InsertTemplate(sel.ownerId, sel.slotIndex, sel.index + 1, text)))
    }

    // ---- dragging ----

    /** What pressing [hit] lifts: the statement and those below it, a value, or a whole scratch value. */
    fun startDrag(hit: CanvasIndex.Hit, index: CanvasIndex, at: Offset): ActiveDrag? {
        if (awaitingProjection) return null
        editing = null; selected = null
        val placed = when (hit) {
            is CanvasIndex.Hit.Action -> return null
            is CanvasIndex.Hit.Block -> hit.placed
            is CanvasIndex.Hit.Token -> index.block(hit.placed.doc, hit.placed.token.blockId)
            is CanvasIndex.Hit.Socket -> index.block(hit.placed.doc, hit.placed.socket.ownerId)
        } ?: return null
        val doc = placed.site.doc
        val grab = at - placed.rect.topLeft
        val layouter = BlockLayouter(measure, geometry, labels, kotlin = kotlin)
        val text = docText(doc)
        return when (val site = placed.site) {
            is BlockSite.InList -> {
                val owner = node(doc, site.ownerId) ?: return null
                val list = owner.parts.filterIsInstance<UiBlockPart.Slot>().getOrNull(site.slotIndex)?.children ?: return null
                val count = if (site.droppable) site.tail else 1
                val run = list.drop(site.index).take(count).ifEmpty { return null }
                val runText = text.substring(run.first().start, run.last().end)
                val whole = doc is DocRef.Scratch && site.index == 0 && run.size == list.size
                val body = layouter.layoutStatements(run, text, doc)
                ActiveDrag(
                    DragPayload.Run(doc, run.first().id, run.size, run.map { it.id }.toSet(), runText, indentAt(text, run.first().start), whole),
                    body, null, dragShapeOf(body, null), grab,
                )
            }
            is BlockSite.InSocket -> {
                val value = placed.block.node ?: return null
                val v = layouter.layoutReporter(value, text, doc)
                ActiveDrag(
                    DragPayload.Value(doc, site.ownerId, site.slotIndex, value.id, text.substring(value.start, value.end), false, argument = isArgument(doc, site.ownerId, site.slotIndex)),
                    null, v, dragShapeOf(null, v), grab,
                )
            }
            is BlockSite.Top -> {
                // A scratch stack's lone value lifts whole; a function's hat does not move.
                if (doc !is DocRef.Scratch || placed.block.kind == LKind.Hat) return null
                val value = placed.block.node ?: return null
                val (ownerId, slot) = parentSlot(doc, value.id) ?: return null
                val v = layouter.layoutReporter(value, text, doc)
                ActiveDrag(DragPayload.Value(doc, ownerId, slot, value.id, text.substring(value.start, value.end), true), null, v, dragShapeOf(null, v), grab)
            }
        }
    }

    private fun parentSlot(doc: DocRef, id: String): Pair<String, Int>? {
        val root = docRoot(doc) ?: return null
        var found: Pair<String, Int>? = null
        findFirst(root) { n ->
            val slots = n.parts.filterIsInstance<UiBlockPart.Slot>()
            val i = slots.indexOfFirst { s -> s.children.any { it.id == id } }
            if (i >= 0) found = n.id to i
            found != null
        }
        return found
    }

    /**
     * Resolve a drop. Within one document a move is one batch of edits; between the file and a scratch
     * stack it is an insert in one and a removal from the other. Nothing under the drop and not the trash:
     * the blocks become (or move) a scratch stack at [landing].
     */
    fun drop(d: ActiveDrag, target: DropTarget?, landing: Offset, trash: Boolean, fn: OutlineFunction) {
        val page = pageOf(fn)
        when (val p = d.payload) {
            is DragPayload.Run -> dropRun(p, d, target, landing, trash, page)
            is DragPayload.Value -> dropValue(p, target, landing, trash, page)
            is DragPayload.Template -> dropTemplate(p.template, target, landing, page)
        }
    }

    private fun dropRun(p: DragPayload.Run, d: ActiveDrag, target: DropTarget?, landing: Offset, trash: Boolean, page: String) {
        val text = dedent(p.text, p.indent)
        val remove: () -> Unit = {
            val s = stackOf(p.doc)
            if (p.wholeScratch && s != null) setScratch(null, s.key)
            else edit(p.doc, listOf(UiBlockEdit.DeleteRange(p.firstId, p.count)))
        }
        when {
            trash -> remove()
            target is DropTarget.Stack -> {
                if (target.above) stackOf(target.doc)?.let { s -> setScratch(s.copy(y = s.y - d.shape.height), s.key) }
                if (target.doc == p.doc) edit(p.doc, listOf(UiBlockEdit.MoveRange(p.firstId, p.count, target.ownerId, target.slotIndex, target.index)))
                else edit(target.doc, listOf(UiBlockEdit.InsertTemplate(target.ownerId, target.slotIndex, target.index, text)), then = remove)
            }
            target is DropTarget.Wrap -> {
                val template = wrapTemplate(p) ?: return
                if (target.doc == p.doc) {
                    if (wrapContains(target, p)) return
                    edit(p.doc, listOf(UiBlockEdit.WrapRange(target.firstId, target.count, template), UiBlockEdit.DeleteRange(p.firstId, p.count)))
                } else edit(target.doc, listOf(UiBlockEdit.WrapRange(target.firstId, target.count, template)), then = remove)
            }
            target is DropTarget.Socket -> {}
            p.wholeScratch -> stackOf(p.doc)?.let { s -> setScratch(s.copy(x = landing.x, y = landing.y), s.key) }
            else -> { addScratch(page, landing, text, isValue = false); remove() }
        }
    }

    /** A lifted C-block as a wrap template: its empty body replaced by the body marker. */
    private fun wrapTemplate(p: DragPayload.Run): String? {
        if (p.count != 1) return null
        val block = node(p.doc, p.firstId) ?: return null
        val bodyAt = emptyBodyOffset(block) ?: return null
        val rel = bodyAt - block.start
        val t = p.text
        if (rel !in 0..t.length) return null
        return dedent(t.substring(0, rel).trimEnd() + "\n" + BODY + "\n" + t.substring(rel).trimStart(), p.indent)
    }

    private fun emptyBodyOffset(block: UiBlockNode): Int? {
        for (part in block.parts) {
            val slot = part as? UiBlockPart.Slot ?: continue
            val child = slot.children.singleOrNull() ?: continue
            if (slot.category == "STATEMENT" && child.kind == "block") {
                val list = child.parts.filterIsInstance<UiBlockPart.Slot>().firstOrNull { it.multiple } ?: return null
                return if (list.children.isEmpty()) list.start else null
            }
        }
        return null
    }

    /** Whether the list a C-block would wrap holds the C-block itself. */
    private fun wrapContains(t: DropTarget.Wrap, p: DragPayload.Run): Boolean {
        val first = node(t.doc, t.firstId) ?: return true
        val (owner, slot) = parentSlot(t.doc, first.id) ?: return true
        val list = node(t.doc, owner)?.parts?.filterIsInstance<UiBlockPart.Slot>()?.getOrNull(slot)?.children ?: return true
        val dragged = node(p.doc, p.firstId) ?: return true
        return dragged.start >= list.first().start && dragged.end <= list.last().end
    }

    private fun dropValue(p: DragPayload.Value, target: DropTarget?, landing: Offset, trash: Boolean, page: String) {
        // Lifting a call's argument takes the argument out, comma and all; any other value leaves its socket empty.
        val clear = if (p.argument) UiBlockEdit.RemoveArgument(p.ownerId, p.slotIndex) else UiBlockEdit.ReplaceSlot(p.ownerId, p.slotIndex, "")
        val remove: () -> Unit = {
            val s = stackOf(p.doc)
            if (p.wholeScratch && s != null) setScratch(null, s.key)
            else edit(p.doc, listOf(clear))
        }
        when {
            trash -> remove()
            target is DropTarget.Socket -> {
                if (target.doc == p.doc && !p.wholeScratch) {
                    edit(p.doc, listOf(UiBlockEdit.ReplaceSlot(target.ownerId, target.slotIndex, p.text), clear))
                } else edit(target.doc, listOf(UiBlockEdit.ReplaceSlot(target.ownerId, target.slotIndex, p.text)), then = remove)
            }
            target != null -> {}
            p.wholeScratch -> stackOf(p.doc)?.let { s -> setScratch(s.copy(x = landing.x, y = landing.y), s.key) }
            else -> { addScratch(page, landing, p.text, isValue = true); remove() }
        }
    }

    private fun dropTemplate(t: PaletteTemplate, target: DropTarget?, landing: Offset, page: String) {
        val plain = withoutBody(t.text)
        when (target) {
            is DropTarget.Stack -> {
                if (target.above) stackOf(target.doc)?.let { s -> setScratch(s.copy(y = s.y - templateHeight(t)), s.key) }
                edit(target.doc, listOf(UiBlockEdit.InsertTemplate(target.ownerId, target.slotIndex, target.index, plain)), importsFor(target.doc, plain) + if (target.doc == DocRef.File) templateImports(t) else emptyList())
            }
            is DropTarget.Wrap -> if (BODY in t.text) edit(target.doc, listOf(UiBlockEdit.WrapRange(target.firstId, target.count, t.text)), importsFor(target.doc, plain))
            is DropTarget.Socket -> edit(target.doc, listOf(UiBlockEdit.ReplaceSlot(target.ownerId, target.slotIndex, plain)), importsFor(target.doc, plain))
            null -> addScratch(page, landing, plain, t.isValue)
        }
    }

    private fun templateHeight(t: PaletteTemplate): Float = paletteCache[t]?.let { it.body?.h ?: it.value?.h } ?: 0f

    // ---- the palette ----

    private val paletteCache = mutableStateMapOf<PaletteTemplate, PaletteEntry>()
    var paletteQuery by mutableStateOf(""); private set
    var searchResults by mutableStateOf<List<PaletteEntry>>(emptyList()); private set
    var searching by mutableStateOf(false); private set
    private var searchJob: kotlinx.coroutines.Job? = null

    fun categories(kotlin: Boolean): List<PaletteCategory> = PaletteCategory.entries.filter {
        when (it) {
            PaletteCategory.Compose -> kotlin && "androidx.compose" in projectedText
            PaletteCategory.Scope -> scopeFocus != null
            else -> true
        }
    }

    private fun templatesFor(c: PaletteCategory, outline: FileOutline, fn: OutlineFunction): List<PaletteTemplate> =
        if (c == PaletteCategory.Variables) variableTemplates(dev.ide.ui.editor.blocks.localNames(fn, projectedText) + parameterNames(fn, kotlin) + variablesInScope(outline, fn).map { it.name }, kotlin)
        else paletteTemplates(c, kotlin)

    fun paletteEntries(c: PaletteCategory, outline: FileOutline, fn: OutlineFunction): List<PaletteEntry> {
        val templates = if (c == PaletteCategory.Scope) scopeFunctions ?: emptyList() else templatesFor(c, outline, fn)
        if (c == PaletteCategory.Scope) templates.filter { it !in paletteCache }.forEach { t -> scope.launch { projectTemplate(t) } }
        return templates.map { paletteCache[it] ?: PaletteEntry(it, null, null) }
    }

    /** Project every template on the page's palette, category by category, so each is drawn as itself. */
    suspend fun paletteFor(outline: FileOutline, fn: OutlineFunction?) {
        fn ?: return
        for (c in categories(kotlin)) if (c != PaletteCategory.Scope) for (t in templatesFor(c, outline, fn)) projectTemplate(t)
    }

    private suspend fun projectTemplate(t: PaletteTemplate): PaletteEntry? {
        paletteCache[t]?.let { return it }
        val wrapped = wrap.wrap(withoutBody(t.text), t.isValue)
        val root = runCatching { backend.blocks.projectBlocks(path, wrapped) }.getOrNull() ?: return null
        val content = scratchContent(root, t.isValue) ?: return null
        val layouter = BlockLayouter(measure, geometry, labels, kotlin = kotlin)
        val entry = if (t.isValue) content.value?.let { PaletteEntry(t, null, layouter.layoutReporter(it, wrapped, DocRef.File)) }
        else content.body?.takeIf { it.children.isNotEmpty() }?.let { PaletteEntry(t, layouter.layoutStatements(it.children, wrapped, DocRef.File), null) }
        if (entry != null) paletteCache[t] = entry
        return entry
    }

    /** Search the palette: matching templates, plus project symbols and classpath members as calls/types. */
    fun searchPalette(q: String) {
        paletteQuery = q
        searchJob?.cancel()
        if (q.isBlank()) { searchResults = emptyList(); searching = false; return }
        searchJob = scope.launch {
            searching = true
            delay(150)
            val query = q.trim()
            val statics = PaletteCategory.entries.flatMap { paletteTemplates(it, kotlin) }.filter { query.lowercase() in it.text.lowercase() }
            val symbols = runCatching { backend.search.searchSymbols(query, 10) }.getOrDefault(emptyList())
            val members = runCatching { backend.search.searchMembers(query, 10) }.getOrDefault(emptyList())
            val hits = (symbols.map { it to false } + members.map { it to true }).map { (h, member) ->
                val kind = h.kind.lowercase()
                when {
                    member || "method" in kind || "function" in kind -> PaletteTemplate(if (kotlin) "${h.name}()" else "${h.name}();", false)
                    "class" in kind || "interface" in kind || "enum" in kind || "record" in kind || "object" in kind ->
                        PaletteTemplate(if (kotlin) "val value = ${h.name}()" else "${h.name} value = new ${h.name}();", false)
                    else -> PaletteTemplate(h.name, true)
                }
            }
            val all = (statics + hits).distinct()
            searchResults = all.map { paletteCache[it] ?: PaletteEntry(it, null, null) }
            for (t in all) projectTemplate(t)?.let { e -> searchResults = searchResults.map { if (it.template == t) e else it } }
            searching = false
        }
    }

    // ---- form suggestions ----

    /**
     * Completion for a form field, from the same engine the code editor uses: [text] is placed where it would
     * sit in the file (a type annotation, an `@annotation`, an initializer) at the end of the group the
     * declaration goes into, and the items for that position come back as whole-field replacements, each
     * with the auto-import it needs. Modifiers are the language's keywords.
     */
    suspend fun suggest(kind: FieldKind, text: String, groupKey: String): List<Suggestion> {
        if (kind == FieldKind.Modifier) return modifierSuggestions(text)
        val outline = outline ?: return emptyList()
        val src = projectedText
        val group = outline.groups.firstOrNull { it.key == groupKey } ?: outline.groups.firstOrNull { it.body != null } ?: return emptyList()
        val body = group.body ?: return emptyList()
        val owner = node(DocRef.File, body.ownerId) ?: return emptyList()
        val list = owner.parts.filterIsInstance<UiBlockPart.Slot>().getOrNull(body.slotIndex) ?: return emptyList()
        val at = list.children.lastOrNull()?.end?.let { e -> if (src.getOrNull(e) == ';') e + 1 else e } ?: list.start
        val indent = list.children.firstOrNull()?.let { indentAt(src, it.start) } ?: "    "
        val lead = when (kind) {
            FieldKind.Type -> if (kotlin) "val __probe: " else ""
            FieldKind.Annotation -> "@"
            FieldKind.Value -> if (kotlin) "val __probe = " else "Object __probe = "
            FieldKind.Modifier -> ""
        }
        val bare = if (kind == FieldKind.Annotation) text.removePrefix("@") else text
        val probe = "\n" + indent + lead + bare
        val probed = src.substring(0, at) + probe + src.substring(at)
        val caret = at + probe.length
        val result = runCatching { backend.editor.complete(path, probed, caret) }.getOrNull() ?: return emptyList()
        val fieldStart = caret - bare.length
        val tokenStart = (result.replaceStart - fieldStart).coerceIn(0, bare.length)
        val typeKinds = setOf(
            dev.ide.ui.backend.UiCompletionKind.Class, dev.ide.ui.backend.UiCompletionKind.Interface, dev.ide.ui.backend.UiCompletionKind.Enum,
            dev.ide.ui.backend.UiCompletionKind.Record, dev.ide.ui.backend.UiCompletionKind.TypeParameter, dev.ide.ui.backend.UiCompletionKind.AnnotationType,
        )
        return result.items.asSequence()
            .filter { item ->
                when (kind) {
                    FieldKind.Type, FieldKind.Annotation -> item.kind in typeKinds
                    else -> item.kind != dev.ide.ui.backend.UiCompletionKind.Snippet && item.kind != dev.ide.ui.backend.UiCompletionKind.Word
                }
            }
            .filter { "__probe" !in it.label }
            .take(8)
            .map { item ->
                val replaced = bare.substring(0, tokenStart) + item.insertText
                Suggestion(item.label, item.container ?: item.detail, if (kind == FieldKind.Annotation) "@$replaced" else replaced, item.additionalEdits.filter { it.end <= at })
            }
            .toList()
    }

    /**
     * [chain] written back in place of the sheet's expression with link [link]'s arguments set to [args], and
     * the offset just after them: the real call site, so completion and parameter info answer for that link.
     */
    private fun probeModifier(sheet: ModifierSheetState, chain: dev.ide.ui.editor.blocks.ModifierChain, link: Int, args: String): Pair<String, Int>? {
        if (sheet.doc != DocRef.File) return null
        val src = projectedText
        if (sheet.start !in 0..src.length || sheet.end !in sheet.start..src.length) return null
        val marker = '\u0000'
        val links = chain.links.toMutableList()
        val l = links.getOrNull(link) ?: return null
        links[link] = l.copy(args = args + marker)
        val code = chain.copy(links = links).code()
        val at = code.indexOf(marker)
        if (at < 0) return null
        return (src.substring(0, sheet.start) + code.removeRange(at, at + 1) + src.substring(sheet.end)) to (sheet.start + at)
    }

    /**
     * Imports for links the table does not know: completion asked at each link's name in the chain (as
     * `Modifier.foo|`), keeping the auto-import of the item that is exactly that name.
     */
    private suspend fun linkImports(sheet: ModifierSheetState, chain: dev.ide.ui.editor.blocks.ModifierChain): List<UiTextEdit> {
        val src = projectedText
        val out = ArrayList<UiTextEdit>()
        for ((i, link) in chain.links.withIndex()) {
            if (link.name in dev.ide.ui.editor.blocks.COMPOSE_IMPORTS) continue
            val head = chain.copy(links = chain.links.take(i)).code() + (if (chain.multiline && i > 0) "\n" + chain.indent else "") + "." + link.name
            if (sheet.start !in 0..src.length || sheet.end !in sheet.start..src.length) continue
            val probed = src.substring(0, sheet.start) + head + src.substring(sheet.end)
            val caret = sheet.start + head.length
            val result = runCatching { backend.editor.complete(path, probed, caret) }.getOrNull() ?: continue
            result.items.firstOrNull { it.label.substringBefore('(') == link.name }?.additionalEdits?.filter { it.end <= sheet.start }?.let { out += it }
        }
        return out
    }

    /** Import edits with the same text at the same place, once. */
    private fun dedupeImports(edits: List<UiTextEdit>): List<UiTextEdit> {
        val seen = HashSet<String>()
        return edits.filter { e -> val k = "${e.start}:${e.newText}"; seen.add(k) && !(e.newText.contains("import ") && e.newText.trim().removePrefix("import ").trim().let { fqn -> projectedText.contains("import $fqn\n") }) }
    }

    /** Completion for a modifier link's arguments, asked at that argument in the file (named parameters first). */
    suspend fun modifierArgSuggestions(sheet: ModifierSheetState, chain: dev.ide.ui.editor.blocks.ModifierChain, link: Int, args: String): List<Suggestion> {
        val (probed, caret) = probeModifier(sheet, chain, link, args) ?: return emptyList()
        val result = runCatching { backend.editor.complete(path, probed, caret) }.getOrNull() ?: return emptyList()
        val argStart = caret - args.length
        val tokenStart = (result.replaceStart - argStart).coerceIn(0, args.length)
        return result.items.asSequence()
            .filter { it.kind != dev.ide.ui.backend.UiCompletionKind.Word && it.kind != dev.ide.ui.backend.UiCompletionKind.Snippet }
            .take(8)
            .map { item -> Suggestion(item.label, item.container ?: item.detail, args.substring(0, tokenStart) + item.insertText, item.additionalEdits.filter { it.end <= sheet.start }) }
            .toList()
    }

    /** The parameters of a modifier link, as the code editor's parameter info shows them. */
    suspend fun modifierParams(sheet: ModifierSheetState, chain: dev.ide.ui.editor.blocks.ModifierChain, link: Int): List<String>? {
        val args = chain.links.getOrNull(link)?.args ?: return null
        val (probed, caret) = probeModifier(sheet, chain, link, args) ?: return null
        val help = runCatching { backend.editor.signatureHelp(path, probed, caret) }.getOrNull() ?: return null
        return help.signatures.getOrNull(help.activeSignature)?.parameters?.map { it.label }
    }

    private fun modifierSuggestions(text: String): List<Suggestion> {
        val words = if (kotlin) listOf("private", "protected", "internal", "public", "open", "override", "abstract", "suspend", "inline", "const", "lateinit", "operator", "infix")
        else listOf("public", "private", "protected", "static", "final", "abstract", "synchronized")
        val head = text.substringBeforeLast(' ', "").let { if (it.isEmpty()) "" else "$it " }
        val typed = text.substringAfterLast(' ')
        val have = text.split(' ').toSet()
        return words.filter { it.startsWith(typed) && it !in have }.map { Suggestion(it, null, "$head$it ", emptyList()) }
    }

    // ---- forms ----

    fun editFunction(fn: OutlineFunction) {
        val spec = parseFunctionHeader(fn.signature, kotlin) ?: return
        functionForm = FunctionFormState(fn, spec, fn.group)
    }

    private fun groupOf(outline: FileOutline, key: String): OutlineGroup? = outline.groups.firstOrNull { it.key == key }

    fun saveVariable(outline: FileOutline, form: VariableFormState, spec: VariableSpec, groupKey: String, extra: List<UiTextEdit> = emptyList()) {
        val code = variableCode(spec, kotlin)
        val existing = form.existing
        if (existing != null) {
            val n = existing.node
            applyText(DocRef.File, projectedText, listOf(UiTextEdit(n.start, n.end + if (!kotlin && projectedText.getOrNull(n.end) == ';') 1 else 0, code)) + extra.filter { it.end <= n.start })
            return
        }
        if (groupKey == dev.ide.ui.editor.blocks.LOCAL_PLACE) {
            // A local goes at the top of the open function's body, without modifiers.
            val fn = outline.find(openKey ?: return, openName, openGroup) ?: return
            val fnBody = bodyOf(fn.node) ?: return
            val local = variableCode(spec.copy(modifiers = ""), kotlin)
            edit(DocRef.File, listOf(UiBlockEdit.InsertTemplate(fnBody.ownerId, fnBody.slotIndex, 0, local)), extra + importsFor(DocRef.File, local))
            return
        }
        val body = groupOf(outline, groupKey)?.body ?: return
        // After the group's last variable, else at the top of its members (in a Kotlin file, after imports).
        val lastVar = body.children.indexOfLast { it.label == "field" }
        val lastHeader = body.children.indexOfLast { it.label == "package" || it.label == "import" || it.label == "imports" }
        val at = maxOf(lastVar, lastHeader) + 1
        edit(DocRef.File, listOf(UiBlockEdit.InsertTemplate(body.ownerId, body.slotIndex, at, code)), extra + importsFor(DocRef.File, code))
    }

    fun saveFunction(outline: FileOutline, form: FunctionFormState, spec: FunctionSpec, groupKey: String, extra: List<UiTextEdit> = emptyList()) {
        val existing = form.existing
        if (existing != null) {
            // Rewrite just the header; the body stays as it is. Callers are not renamed: that is a refactoring
            // the code view's Rename does across the project.
            val n = existing.node
            val old = signatureText(n, projectedText)
            val start = n.start
            val src = projectedText
            var end = start
            var matched = 0
            // Walk the raw header until the collapsed signature is consumed (it collapsed whitespace).
            while (end < src.length && matched < old.length) {
                val c = src[end]
                if (c.isWhitespace()) { if (old[matched] == ' ') matched++; end++; while (end < src.length && src[end].isWhitespace()) end++; continue }
                if (c != old[matched]) return
                matched++; end++
            }
            applyText(DocRef.File, src, listOf(UiTextEdit(start, end, functionHeader(spec, kotlin).replace("\n", "\n" + indentAt(src, start)))) + extra.filter { it.end <= start })
            openName = spec.name
            return
        }
        val body = groupOf(outline, groupKey)?.body ?: return
        pendingOpen = groupKey to spec.name
        edit(DocRef.File, listOf(UiBlockEdit.InsertTemplate(body.ownerId, body.slotIndex, body.children.size, functionCode(spec, kotlin))), extra + importsFor(DocRef.File, functionCode(spec, kotlin)))
    }

    /**
     * Offer the methods [group]'s class may override, as completion offers them at a member position, and
     * insert the chosen stub at the end of the class.
     */
    fun findEvents(group: OutlineGroup) {
        val body = group.body ?: return
        val src = projectedText
        val owner = node(DocRef.File, body.ownerId) ?: return
        val list = owner.parts.filterIsInstance<UiBlockPart.Slot>().getOrNull(body.slotIndex) ?: return
        val at = list.children.lastOrNull()?.end?.let { e -> if (src.getOrNull(e) == ';') e + 1 else e } ?: list.start
        val memberIndent = list.children.firstOrNull()?.let { indentAt(src, it.start) } ?: (indentAt(src, group.node?.start ?: 0) + "    ")
        val probe = "\n" + memberIndent + if (kotlin) "override fun " else ""
        val probed = src.substring(0, at) + probe + src.substring(at)
        val caret = at + probe.length
        events = null
        eventsOpen = true
        scope.launch {
            val result = runCatching { backend.editor.complete(path, probed, caret) }.getOrNull()
            val stubs = result?.items.orEmpty().filter { "@Override" in it.insertText || (kotlin && it.detail == "override") }
            events = stubs.map { item ->
                EventChoice(item.label, item.container ?: item.detail.orEmpty()) {
                    eventsOpen = false
                    val replaced = applyTextEdits(probed, listOf(UiTextEdit(result!!.replaceStart, caret, item.insertText)) + item.additionalEdits) ?: return@EventChoice
                    val tailLen = src.length - at
                    if (!replaced.startsWith(src.substring(0, at)) || !replaced.endsWith(src.substring(at))) return@EventChoice
                    val inserted = replaced.substring(at, replaced.length - tailLen)
                    applyText(DocRef.File, src, listOf(UiTextEdit(at, at, if (inserted.startsWith("\n")) inserted else "\n$inserted")))
                }
            }
        }
    }

    @Composable
    fun Dialogs(outline: FileOutline?) {
        outline ?: return
        val groups = outline.groups.filter { it.body != null }
        variableForm?.let { form ->
            VariableForm(
                form.existing, kotlin, groups, form.group,
                localPlace = if (openKey != null) stringResource(Res.string.block_form_place_local) else null,
                suggest = { kind, text, g -> suggest(kind, text, g) },
                onSave = { spec, g, extra -> variableForm = null; saveVariable(outline, form, spec, g, extra) },
                onDelete = form.existing?.let { v -> { variableForm = null; edit(DocRef.File, listOf(UiBlockEdit.DeleteBlock(v.node.id))) } },
                onDismiss = { variableForm = null },
            )
        }
        functionForm?.let { form ->
            FunctionForm(
                form.spec, editing = form.existing != null, kotlin = kotlin, groups = groups, group = form.group,
                suggest = { kind, text, g -> suggest(kind, text, g) },
                onSave = { spec, g, extra -> functionForm = null; saveFunction(outline, form, spec, g, extra) },
                onDismiss = { functionForm = null },
            )
        }
        if (eventsOpen) EventPicker(events) { eventsOpen = false }
        if (scopePickerOpen) dev.ide.ui.editor.blocks.ScopePicker(scopeFocus?.receiver ?: "", scopeFunctions, kotlin, onPick = { insertInScope(it) }) { scopePickerOpen = false }
        propertySheet?.let { sheet ->
            dev.ide.ui.editor.blocks.PropertySheet(
                sheet.sig, sheet.optional, kotlin,
                onPick = { pickProperty(sheet, it) }, onOverload = { pickOverload(sheet, it) }, onDismiss = { propertySheet = null },
            )
        }
        modifierSheet?.let { sheet ->
            ModifierSheet(
                sheet.chain, sheet.group, suggest = { kind, text, g -> suggest(kind, text, g) },
                suggestArgs = { chain, link, args -> modifierArgSuggestions(sheet, chain, link, args) },
                paramsOf = { chain, link -> modifierParams(sheet, chain, link) },
                onSave = { chain, extra ->
                    modifierSheet = null
                    val code = if (chain.links.isEmpty()) chain.base else chain.code()
                    scope.launch {
                        // Imports: the known names the chain now uses, and any link only completion knows.
                        val known = importsFor(sheet.doc, code)
                        val probed = if (sheet.doc == DocRef.File) linkImports(sheet, chain) else emptyList()
                        val all = (if (sheet.doc == DocRef.File) extra else emptyList()) + known + probed
                        edit(sheet.doc, listOf(UiBlockEdit.ReplaceSlot(sheet.ownerId, sheet.slotIndex, code)), dedupeImports(all))
                    }
                },
                onDismiss = { modifierSheet = null },
            )
        }
        focus?.let { node -> FocusSheet(this, node) { focus = null } }
    }
}

/**
 * The drill-in sheet for a value nested too deep to inline: the expression re-rooted on its own canvas,
 * tokens and sockets still editable (each tap edits the file).
 */
@Composable
private fun FocusSheet(model: BlockEditorModel, node: UiBlockNode, onClose: () -> Unit) {
    val ink = rememberBlockInk(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurface)
    val state = remember(node) { BlockCanvasState() }
    val layoutFor = remember(node, model.projectedText) {
        { _: Hidden?, _: Gap? -> BlockLayouter(model.measure, model.geometry, model.labels, kotlin = model.kotlin).layoutValue(node, model.projectedText) }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(Ca.radius.sheet)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(CaIcons.braces, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(Res.string.block_edit_expression), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(CaIcons.close, stringResource(Res.string.close), Modifier.size(18.dp).clickable(onClick = onClose), tint = MaterialTheme.colorScheme.outline)
            }
            BlockCanvas(
                layoutFor = layoutFor, state = state, drag = remember { BlockDrag() }, ink = ink, selected = null,
                onTap = { hit, index ->
                    if (hit is CanvasIndex.Hit.Token || hit is CanvasIndex.Hit.Socket) model.tap(hit, index, dummyFunction(node)) else model.editing = null
                },
                startDrag = { _, _, _ -> null },
                onDrop = { _, _, _, _ -> },
                modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)).background(Ide.colors.editorBg),
                overlayRect = { index -> model.editRect(index) },
                overlay = model.editing?.let { e -> { EditOverlay(model, e) } },
                zoomControls = false,
            )
            Text(stringResource(Res.string.block_focus_hint), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A stand-in page owner for taps routed from the drill-in sheet (they never reach a hat). */
private fun dummyFunction(node: UiBlockNode) =
    OutlineFunction(node, dev.ide.ui.editor.blocks.FunctionKind.Function, "", "", "", 0, "")
