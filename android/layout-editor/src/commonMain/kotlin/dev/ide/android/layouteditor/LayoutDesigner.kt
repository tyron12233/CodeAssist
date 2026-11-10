package dev.ide.android.layouteditor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import dev.ide.android.layout.ANDROID_LAYOUT_MODEL
import dev.ide.android.layout.AndroidLayoutModel
import dev.ide.android.layout.LAYOUT_RENDERER
import dev.ide.android.layout.LayoutEdit
import dev.ide.android.layout.LayoutElement
import dev.ide.android.layout.LayoutRenderRequest
import dev.ide.android.layout.LayoutRenderResult
import dev.ide.android.layout.LayoutRenderer
import dev.ide.android.layouteditor.generated.resources.Res
import dev.ide.android.layouteditor.generated.resources.le_unavailable
import dev.ide.plugin.ui.EditorPreviewContext
import dev.ide.plugin.ui.TextReplacement
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.stringResource

/** Debounce before a render, so a burst of typing in the code view becomes one render. */
private const val RENDER_DEBOUNCE_MS = 200L

/** Width from which the panels dock beside the canvas instead of sliding up as sheets. */
private val DOCKED_MIN_WIDTH = 720.dp

/**
 * The design editor for one layout file: the rendered layout on a canvas you can select from, the component
 * tree, and the selected view's attributes, all editing the shared buffer through [ctx].
 *
 * Everything it knows about the project comes from two host services, the renderer and the layout model; it
 * holds no project state of its own beyond what the user is looking at.
 */
@Composable
internal fun LayoutDesigner(ctx: EditorPreviewContext) {
    val renderer = ctx.service(LAYOUT_RENDERER)
    val model = ctx.service(ANDROID_LAYOUT_MODEL)
    if (renderer == null || model == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            Text(
                stringResource(Res.string.le_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    DesignerSurface(ctx, renderer, model)
}

@Composable
private fun DesignerSurface(ctx: EditorPreviewContext, renderer: LayoutRenderer, model: AndroidLayoutModel) {
    val path = ctx.path
    val state = remember(path) { DesignerState.forPath(path) }
    val scope = rememberCoroutineScope()
    val document = remember(ctx.text) { model.parse(ctx.text) }
    val currentCtx by rememberUpdatedState(ctx)

    // ---- Rendering ---------------------------------------------------------------------------------------
    var render by remember(path) { mutableStateOf<LayoutRenderResult?>(null) }
    var resourceErrors by remember(path) { mutableStateOf(emptyList<dev.ide.android.layout.LayoutResourceError>()) }
    var rendered by remember(path) { mutableStateOf(false) }
    // Bumped to render again with nothing else changed: after preparing libraries, or by hand.
    var renderTick by remember(path) { mutableIntStateOf(0) }
    var preparing by remember(path) { mutableStateOf(false) }
    // Whether a render is pending or in flight, reported to the host so a frame is only taken once drawn.
    var pending by remember(path) { mutableStateOf(true) }
    LaunchedEffect(pending) { currentCtx.reportRendering(pending) }
    LaunchedEffect(path, ctx.text, state.deviceIndex, state.landscape, state.night, renderTick) {
        pending = true
        delay(RENDER_DEBOUNCE_MS)
        val request = LayoutRenderRequest(
            path = path,
            text = ctx.text,
            widthPx = state.widthPx,
            heightPx = state.heightPx,
            density = state.device.density,
            night = state.night,
        )
        val next = runCatching { renderer.render(request) }.getOrNull()
        pending = false
        if (next == null) return@LaunchedEffect
        resourceErrors = next.resourceErrors
        // Errors in resources keep the last good render behind them: a half-typed edit should not blank it.
        if (next.resourceErrors.isEmpty() || render == null) render = next
        rendered = true
        if (!next.librariesNeedPreparing) preparing = false
        currentCtx.reportProblems(next.problems.map { p -> p.tag?.let { "<${it.substringAfterLast('.')}> ${p.message}" } ?: p.message })
    }
    // While the one-time library preparation runs, look again every few seconds: its build reports to the IDE's
    // console, not to this pane, and a render is how the pane learns the libraries are ready.
    LaunchedEffect(preparing) {
        while (preparing) {
            delay(4000)
            renderTick++
        }
    }
    val progress by renderer.progress.collectAsState()

    // ---- Selection ---------------------------------------------------------------------------------------
    val selected: LayoutElement? = state.selection?.resolve(document)
    // The caret offset this pane last moved the code editor to, so following the caret back does not re-select.
    var lastCaretWrite by remember(path) { mutableIntStateOf(-1) }

    fun select(element: LayoutElement?) {
        state.selection = element?.let(SelectionKey::of)
        if (element != null && currentCtx.isSplit) {
            lastCaretWrite = element.start
            currentCtx.select(element.start)
        }
    }

    // In Split, follow the code editor: moving the caret into an element selects it here.
    LaunchedEffect(ctx.caretOffset, ctx.isSplit, document) {
        if (!ctx.isSplit || ctx.caretOffset == lastCaretWrite) return@LaunchedEffect
        document.elementAt(ctx.caretOffset)?.let { state.selection = SelectionKey.of(it) }
    }

    // ---- Edits -------------------------------------------------------------------------------------------
    // Serialized: each edit is computed against the buffer the previous one produced.
    val editLock = remember(path) { Mutex() }
    fun apply(edits: List<LayoutEdit>) {
        if (edits.isEmpty()) return
        // Applying moves the code editor's caret to the first edit; that is not the user pointing at a view.
        lastCaretWrite = edits.minOf { it.start }
        currentCtx.applyEdits(edits.map { TextReplacement(it.start, it.end, it.newText) })
    }

    fun edit(compute: suspend (text: String) -> List<LayoutEdit>) {
        scope.launch {
            editLock.withLock {
                val edits = compute(currentCtx.text)
                apply(edits)
            }
        }
    }

    val actions = DesignActions(
        canEdit = ctx.canEdit,
        duplicate = { el ->
            val newId = el.id?.let { freshId(it, document.elements().mapNotNull { e -> e.id }.toSet()) }
            apply(listOf(duplicateElementEdit(document, el)))
            val key = SelectionKey.of(el)
            state.selection = SelectionKey(newId, key.path.dropLast(1) + ((key.path.lastOrNull() ?: 0) + 1))
        },
        delete = { el ->
            state.selection = el.parent?.let(SelectionKey::of)
            apply(listOf(deleteElementEdit(document.text, el)))
        },
        selectParent = { el -> el.parent?.let(::select) },
        showInCode = { el -> lastCaretWrite = el.start; currentCtx.select(el.start, el.startTagEnd) },
    )

    // ---- The selected view's catalog, and the project's strings (for text previews) ------------------------
    val parentTag = selected?.parent?.tag
    var catalog by remember(selected?.tag, parentTag) { mutableStateOf<Map<String, dev.ide.android.layout.LayoutAttributeInfo>>(emptyMap()) }
    LaunchedEffect(selected?.tag, parentTag) {
        val el = selected ?: return@LaunchedEffect
        catalog = runCatching { model.attributes(path, el.tag, el.parent?.tag) }.getOrDefault(emptyList()).associateBy { it.name }
    }
    var strings by remember(path) { mutableStateOf<Map<String, String>>(emptyMap()) }
    // Re-read when a render lands: a new or changed string resource shows up in the next render's inputs.
    LaunchedEffect(path, render) {
        strings = runCatching { model.resources(path, "string") }.getOrDefault(emptyList())
            .associate { it.name to (it.value ?: "") }
    }
    // The strip is shown for every new selection until closed; tapping the view again brings it back.
    var stripClosed by remember(path, state.selection) { mutableStateOf(false) }
    var stripHeight by remember { mutableStateOf(0) }

    fun writeAttributes(writes: List<Pair<String, String?>>) {
        val ref = selected?.ref ?: return
        if (writes.isEmpty()) return
        // All computed against one buffer and applied together: one undo step for a margin split into four.
        edit { text ->
            writes.flatMap { (name, value) ->
                if (value == null) model.removeAttribute(path, text, ref, name)
                else model.setAttribute(path, text, ref, name, value)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        val docked = maxWidth >= DOCKED_MIN_WIDTH
        val paneHeight = maxHeight
        val density = LocalDensity.current
        val showStrip = !docked && selected != null && state.sheet == null && !stripClosed
        val showChip = selected != null && !showStrip && state.sheet != DesignPanel.Attributes && !(docked && state.attributesDocked)
        val tree: @Composable (Modifier, (() -> Unit)?) -> Unit = { mod, onClose ->
            ComponentTreePanel(
                document = document,
                selected = selected,
                onSelect = { select(it) },
                onClose = onClose,
                modifier = mod,
            )
        }
        val attributes: @Composable (Modifier, (() -> Unit)?) -> Unit = { mod, onClose ->
            val el = selected
            if (el == null) {
                NothingSelected(mod)
            } else {
                AttributesPanel(
                    path = path,
                    element = el,
                    model = model,
                    canEdit = ctx.canEdit,
                    textProvider = { currentCtx.text },
                    onEdit = { compute -> edit(compute) },
                    onClose = onClose,
                    modifier = mod,
                )
            }
        }

        Row(Modifier.fillMaxSize()) {
            if (docked && state.treeDocked) {
                PanelFrame(Modifier.width(272.dp).fillMaxHeight().padding(start = 12.dp, top = 12.dp, bottom = 12.dp)) {
                    tree(Modifier.fillMaxSize(), null)
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                DesignCanvas(
                    state = state,
                    document = document,
                    render = render,
                    selected = selected,
                    onSelect = { select(it) },
                    actions = actions,
                    onOpenAttributes = { if (!docked) stripClosed = false else state.attributesDocked = true },
                    // On a phone the sheet (or the strip) covers the lower part of the canvas; the canvas keeps the
                    // selection above it.
                    chipShown = showChip,
                    // On a phone with the strip open, its header carries these; the floating bar would cover the view.
                    showActionBar = !showStrip,
                    obscuredBottom = when {
                        docked -> 0.dp
                        state.sheet != null -> paneHeight * state.sheetFraction
                        showStrip -> with(density) { stripHeight.toDp() }
                        else -> 0.dp
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                DesignTopBar(state, Modifier.align(Alignment.TopCenter).padding(12.dp))
                // The chip names the selection only where nothing else does: the strip, the attributes sheet and
                // the docked attributes pane all lead with it.
                if (showChip) Column(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 64.dp)) {
                    selected?.let { SelectionChip(it) }
                }
                if (progress != null || !rendered) {
                    RenderStatusChip(progress, Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 12.dp))
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { stripHeight = it.height },
                ) {
                    val rowGap by androidx.compose.animation.core.animateDpAsState(if (showStrip) 8.dp else 16.dp, label = "toolbarGap")
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = rowGap), verticalAlignment = Alignment.Bottom) {
                        DesignToolbar(state = state, docked = docked, modifier = Modifier)
                        Box(Modifier.weight(1f))
                        ZoomControls(state, Modifier)
                    }
                    // The strip slides up from the bottom edge and the toolbar row rides up on it. The last
                    // selection is kept so the strip still has content while it slides away.
                    val lastSelected = remember { mutableStateOf<LayoutElement?>(null) }
                    if (selected != null) lastSelected.value = selected
                    val el = selected ?: lastSelected.value
                    AnimatedVisibility(
                        visible = showStrip && el != null,
                        enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), expandFrom = Alignment.Top) +
                            slideInVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { it / 3 } +
                            fadeIn(tween(180)),
                        exit = shrinkVertically(tween(200), shrinkTowards = Alignment.Top) +
                            slideOutVertically(tween(200)) { it / 3 } + fadeOut(tween(150)),
                    ) {
                        if (el != null) PropertyStrip(
                            document = document,
                            element = el,
                            pc = PropertyContext(
                                element = el,
                                catalog = catalog,
                                canEdit = ctx.canEdit,
                                write = ::writeAttributes,
                                complete = { a, f, c -> model.completeValue(path, currentCtx.text, el.ref, a, f, c) },
                                resources = { type -> model.resources(path, type) },
                                themeColors = { model.themeColors(path) },
                            ),
                            strings = strings,
                            onSelect = { select(it) },
                            actions = actions,
                            onExpand = { state.sheet = DesignPanel.Attributes },
                            onClose = { stripClosed = true },
                            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                        )
                    }
                }
                val r = render
                if (resourceErrors.isNotEmpty()) {
                    ResourceErrorsCard(resourceErrors, lastRender = r != null && r.resourceErrors.isEmpty(), ctx, Modifier.align(Alignment.Center).padding(24.dp))
                } else if (r != null && r.librariesNeedPreparing) {
                    PrepareLibrariesCard(
                        count = r.unpreparedLibraryCount,
                        preparing = preparing,
                        onPrepare = { if (renderer.prepareLibraries(path)) preparing = true },
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
            }
            if (docked && state.attributesDocked) {
                PanelFrame(Modifier.width(332.dp).fillMaxHeight().padding(end = 12.dp, top = 12.dp, bottom = 12.dp)) {
                    attributes(Modifier.fillMaxSize(), null)
                }
            }
        }

        if (!docked) {
            val sheet = state.sheet
            AnimatedVisibility(
                visible = sheet != null,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut(),
            ) {
                // Remembered so the closing animation still has content to slide out.
                val shown = remember { mutableStateOf(sheet) }
                if (sheet != null) shown.value = sheet
                DesignSheet(
                    state = state,
                    maxHeight = maxHeight,
                    onDismiss = { state.sheet = null },
                ) { mod ->
                    when (shown.value) {
                        DesignPanel.Tree -> tree(mod) { state.sheet = null }
                        DesignPanel.Attributes -> attributes(mod) { state.sheet = null }
                        null -> {}
                    }
                }
            }
        }
    }
}

/** What the selection's action bar can do; each closes over the current document and buffer. */
internal class DesignActions(
    val canEdit: Boolean,
    val duplicate: (LayoutElement) -> Unit,
    val delete: (LayoutElement) -> Unit,
    val selectParent: (LayoutElement) -> Unit,
    val showInCode: (LayoutElement) -> Unit,
)
