package dev.ide.agent.ui


import dev.ide.ui.components.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.ide.ui.itemsKeyed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAgentCommand
import dev.ide.ui.backend.UiAgentMention
import dev.ide.ui.backend.UiAgentAttachment
import dev.ide.ui.backend.UiAgentAttachmentKind
import dev.ide.ui.backend.UiAgentTodoStatus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import dev.ide.ui.backend.UiAgentConfig
import dev.ide.ui.backend.UiAgentMessage
import dev.ide.ui.backend.UiAgentModel
import dev.ide.ui.backend.UiAgentPermissionMode
import dev.ide.ui.backend.UiAgentRole
import dev.ide.ui.backend.UiAgentSegment
import dev.ide.ui.backend.UiAgentToolCall
import dev.ide.ui.backend.UiAgentToolStatus
import dev.ide.ui.backend.UiAgentUsage
import dev.ide.agent.ui.generated.resources.Res
import dev.ide.agent.ui.generated.resources.chat_add_key
import dev.ide.agent.ui.generated.resources.chat_attach
import dev.ide.agent.ui.generated.resources.chat_attach_file
import dev.ide.agent.ui.generated.resources.chat_attach_image
import dev.ide.agent.ui.generated.resources.chat_attach_photo
import dev.ide.agent.ui.generated.resources.chat_paste_image
import dev.ide.agent.ui.generated.resources.chat_history
import dev.ide.agent.ui.generated.resources.chat_files_changed
import dev.ide.agent.ui.generated.resources.chat_jump_latest
import dev.ide.agent.ui.generated.resources.chat_more
import dev.ide.agent.ui.generated.resources.chat_thought
import dev.ide.agent.ui.generated.resources.chat_tool_count
import dev.ide.agent.ui.generated.resources.chat_image_failed
import dev.ide.agent.ui.generated.resources.chat_undo
import dev.ide.agent.ui.generated.resources.chat_undone
import dev.ide.agent.ui.generated.resources.chat_use_model
import dev.ide.agent.ui.generated.resources.chat_close
import dev.ide.agent.ui.generated.resources.chat_copied
import dev.ide.agent.ui.generated.resources.chat_copy
import dev.ide.agent.ui.generated.resources.chat_usage_cached
import dev.ide.agent.ui.generated.resources.chat_usage_input
import dev.ide.agent.ui.generated.resources.chat_usage_output
import dev.ide.agent.ui.generated.resources.chat_manage_keys
import dev.ide.agent.ui.generated.resources.chat_empty_body
import dev.ide.agent.ui.generated.resources.chat_empty_title
import dev.ide.agent.ui.generated.resources.chat_mode_ask
import dev.ide.agent.ui.generated.resources.chat_mode_auto
import dev.ide.agent.ui.generated.resources.chat_mode_plan
import dev.ide.agent.ui.generated.resources.chat_need_key
import dev.ide.agent.ui.generated.resources.chat_new
import dev.ide.agent.ui.generated.resources.chat_placeholder
import dev.ide.agent.ui.generated.resources.chat_retry
import dev.ide.agent.ui.generated.resources.chat_send
import dev.ide.agent.ui.generated.resources.chat_stop
import dev.ide.agent.ui.generated.resources.chat_thinking
import dev.ide.agent.ui.generated.resources.chat_title
import dev.ide.ui.components.CodeSample
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.markdown.Markdown
import dev.ide.ui.markdown.defaultHeadingStyle
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.CaMotion
import dev.ide.ui.theme.Ide
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The AI agent chat drawer: a streamed transcript over the tool-using agent. Renders user/assistant messages,
 * streaming reasoning, per-tool-call status, and a composer; a right-edge drawer on desktop. See
 * docs/agentic-coding.md.
 *
 * The panel is opaque: it paints its own `surface` rather than letting whatever hosts it show through, and
 * nothing inside composites with alpha. Text over a half-transparent fill has no predictable contrast — what
 * is behind it decides — and that is not a property you can check once, because the thing behind is the
 * editor, whatever file happens to be open.
 */
@Composable
fun ChatDrawer(
    backend: IdeBackend,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    fileActions: FileActions = FileActions.None,
    activeFilePath: String? = null,
) {
    val chat by backend.agent.chatState.collectAsState()
    val models by backend.agent.models.collectAsState()
    var cfg by remember { mutableStateOf(backend.agent.config()) }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var showProviders by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val imageFailed = stringResource(Res.string.chat_image_failed)

    // Fetch the provider's live model list when the drawer opens or the provider changes.
    LaunchedEffect(cfg.selectedProvider) { backend.agent.refreshModels() }

    fun submit() {
        val text = input.text.trim()
        if (text.isEmpty()) return
        input = TextFieldValue("")
        if (text.startsWith("/")) {
            val name = text.removePrefix("/").substringBefore(' ').lowercase()
            val args = text.substringAfter(' ', "").trim()
            when (name) {
                "resume", "history" -> { showHistory = true; return }
                "model" -> { modelMenuOpen = true; return }
            }
            if (backend.agent.runCommand(name, args)) {
                cfg = backend.agent.config()
                return
            }
        }
        backend.agent.send(text)
    }

    /** Reads, scales and queues the image at [path]; every image source (picker, camera, clipboard) ends here. */
    fun attachImageAt(path: String?) {
        if (path == null) return
        scope.launch {
            val bytes = backend.projects.imageBytes(path)
            val attachment = bytes?.let { prepareImageAttachment(path.substringAfterLast('/'), it) }
            if (attachment != null) backend.agent.attach(attachment) else notice = imageFailed
        }
    }

    val imageSources = ImageSources(
        pick = if (fileActions.canPickFile) ({ fileActions.pickFile(IMAGE_EXTENSIONS, ::attachImageAt) }) else null,
        camera = if (fileActions.canTakePhoto) ({ fileActions.takePhoto(::attachImageAt) }) else null,
        paste = if (fileActions.canPasteImage) ({ fileActions.pasteImage(::attachImageAt) }) else null,
        hasClipboardImage = fileActions::hasClipboardImage,
    )

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize()) {
            ChatChrome {
                ChatHeader(
                cfg = cfg,
                models = models.ifEmpty { cfg.providers.firstOrNull { it.id == cfg.selectedProvider }?.models ?: emptyList() },
                modelMenuOpen = modelMenuOpen,
                onModelMenu = { modelMenuOpen = it },
                onPickModel = { backend.agent.setModel(it); cfg = backend.agent.config() },
                onManage = { showProviders = true },
                onHistory = { showHistory = true },
                onCycleMode = {
                    backend.agent.setPermissionMode(nextMode(cfg.mode))
                    cfg = backend.agent.config()
                },
                onNew = { backend.agent.newSession() },
                onClose = onClose,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (chat.messages.isEmpty()) {
                    EmptyState(configured = cfg.configured, onManage = { showProviders = true })
                } else {
                    Transcript(
                        chat.messages,
                        busy = chat.busy,
                        onRetry = { backend.agent.retry() },
                        onUndo = { backend.agent.undoTurn(it) },
                        onUseModel = { backend.agent.switchModelAndRetry(it); cfg = backend.agent.config() },
                    )
                }
            }
            if (chat.todos.isNotEmpty() && (chat.busy || chat.todos.any { it.status != UiAgentTodoStatus.DONE })) {
                TodoCard(chat.todos)
            }
            ChatChrome {
                Column {
                    notice?.let { message ->
                        LaunchedEffect(message) { delay(3000); notice = null }
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        )
                    }
                    Suggestions(
                        backend = backend,
                        input = input,
                        onPick = { input = it },
                    )
                    AttachmentChips(
                        chat.pendingAttachments,
                        onRemove = { backend.agent.detach(it) },
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
                    )
                    Composer(
                    value = input,
                    configured = cfg.configured,
                    busy = chat.busy,
                    images = imageSources,
                    activeFilePath = activeFilePath,
                    onAttachFile = { path ->
                        backend.agent.attach(UiAgentAttachment(UiAgentAttachmentKind.FILE, path.substringAfterLast('/'), path = path))
                    },
                    onValueChange = { input = it },
                    onSend = ::submit,
                    onStop = { backend.agent.stop() },
                    )
                }
            }
        }
        if (showProviders) {
            AgentProvidersSheet(backend) {
                showProviders = false
                cfg = backend.agent.config()
            }
        }
        if (showHistory) {
            AgentHistorySheet(backend) {
                showHistory = false
                cfg = backend.agent.config()
            }
        }
    }
}

private val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp", "gif")

/** The ways this host can supply an image; a null source is one the platform lacks, and is not offered. */
private class ImageSources(
    val pick: (() -> Unit)?,
    val camera: (() -> Unit)?,
    val paste: (() -> Unit)?,
    val hasClipboardImage: () -> Boolean,
) {
    val any: Boolean get() = pick != null || camera != null || paste != null
}

/**
 * What the composer can complete at the caret: a slash command while the message is just `/name`, or a project
 * file while the word under the caret starts with `@`. Picking one rewrites that word.
 */
@Composable
private fun Suggestions(backend: IdeBackend, input: TextFieldValue, onPick: (TextFieldValue) -> Unit) {
    val text = input.text
    val caret = input.selection.end.coerceIn(0, text.length)
    val beforeCaret = text.substring(0, caret)
    val commandQuery = if (text.startsWith("/") && ' ' !in text) text.removePrefix("/").lowercase() else null
    val mentionMatch = remember(beforeCaret) { MENTION_AT_CARET.find(beforeCaret) }
    val mentionQuery = mentionMatch?.groupValues?.get(1)

    var mentions by remember { mutableStateOf<List<UiAgentMention>>(emptyList()) }
    LaunchedEffect(mentionQuery) {
        mentions = if (mentionQuery == null) emptyList() else {
            delay(120)
            backend.agent.mentionCandidates(mentionQuery)
        }
    }
    val commands: List<UiAgentCommand> = remember(commandQuery) {
        if (commandQuery == null) emptyList() else backend.agent.commands().filter { it.name.startsWith(commandQuery) }
    }
    if (commands.isEmpty() && (mentionQuery == null || mentions.isEmpty())) return

    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp)
            .clip(MaterialTheme.shapes.medium).background(scheme.surfaceContainerHigh)
            .heightIn(max = 220.dp).verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        if (commands.isNotEmpty()) {
            commands.forEach { command ->
                SuggestionRow(
                    title = "/" + command.name,
                    detail = command.description,
                    onClick = {
                        val value = "/" + command.name + " "
                        onPick(TextFieldValue(value, TextRange(value.length)))
                    },
                )
            }
        } else {
            val start = mentionMatch!!.range.first
            mentions.forEach { mention ->
                SuggestionRow(
                    title = mention.label,
                    detail = mention.detail,
                    onClick = {
                        val replaced = text.substring(0, start) + "@" + mention.insert + " "
                        val after = text.substring(caret)
                        onPick(TextFieldValue(replaced + after, TextRange(replaced.length)))
                    },
                )
            }
        }
    }
}

/** `@partial` ending at the caret, not glued to a preceding word (so an e-mail address is not a mention). */
private val MENTION_AT_CARET = Regex("""(?:^|(?<=\s))@([\w./-]*)$""")

@Composable
private fun SuggestionRow(title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ChatHeader(
    cfg: UiAgentConfig,
    models: List<UiAgentModel>,
    modelMenuOpen: Boolean,
    onModelMenu: (Boolean) -> Unit,
    onPickModel: (String) -> Unit,
    onManage: () -> Unit,
    onHistory: () -> Unit,
    onCycleMode: () -> Unit,
    onNew: () -> Unit,
    onClose: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SparkleBadge(size = 26)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.chat_title),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                // One line: the bar is 52dp, and a title that wraps (a longer translation in a narrow drawer)
                // splits mid-word and pushes the model picker under it out of view.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ModelPicker(cfg = cfg, models = models, open = modelMenuOpen, onOpen = onModelMenu, onPick = onPickModel)
        }
        // The permission mode cycles on tap, and is tinted by how much it lets the agent do: a session left
        // on auto-accept applies edits without asking, which should be visible in the header rather than
        // something you have to open settings to discover. The dense house chip keeps the 52dp bar intact
        // where an M3 chip's own padding would crowd out the title.
        val scheme = MaterialTheme.colorScheme
        val modeFill = when (cfg.mode) {
            UiAgentPermissionMode.AUTO_ACCEPT -> scheme.tertiaryContainer
            UiAgentPermissionMode.PLAN_ONLY -> scheme.secondaryContainer
            UiAgentPermissionMode.ASK_EACH -> scheme.surfaceContainerHighest
        }
        val modeText = when (cfg.mode) {
            UiAgentPermissionMode.AUTO_ACCEPT -> scheme.onTertiaryContainer
            UiAgentPermissionMode.PLAN_ONLY -> scheme.onSecondaryContainer
            UiAgentPermissionMode.ASK_EACH -> scheme.onSurfaceVariant
        }
        Chip(
            text = modeLabel(cfg.mode),
            modifier = Modifier.clip(RoundedCornerShape(Ca.radius.pill)).clickable(onClick = onCycleMode),
            fill = modeFill,
            textColor = modeText,
        )
        // The occasional actions share one menu, which leaves the title and the model name room to read.
        var menuOpen by remember { mutableStateOf(false) }
        Box {
            IconButtonCa(CaIcons.ellipsis, stringResource(Res.string.chat_more), { menuOpen = true }, iconSize = 16, boxSize = 30)
            CaDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                HeaderMenuItem(CaIcons.plus, stringResource(Res.string.chat_new)) { menuOpen = false; onNew() }
                HeaderMenuItem(CaIcons.clock, stringResource(Res.string.chat_history)) { menuOpen = false; onHistory() }
                HeaderMenuItem(CaIcons.key, stringResource(Res.string.chat_manage_keys)) { menuOpen = false; onManage() }
            }
        }
        if (onClose != null) {
            IconButtonCa(CaIcons.close, stringResource(Res.string.chat_close), onClose, iconSize = 16, boxSize = 30)
        }
    }
}

@Composable
private fun HeaderMenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) },
        leadingIcon = { Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        onClick = onClick,
    )
}

@Composable
private fun ModelPicker(
    cfg: UiAgentConfig,
    models: List<UiAgentModel>,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    onPick: (String) -> Unit,
) {
    val provider = cfg.providers.firstOrNull { it.id == cfg.selectedProvider }
    val current = cfg.model.ifBlank { provider?.defaultModel ?: "" }
    val label = models.firstOrNull { it.id == current }?.displayName
        ?: current.ifBlank { provider?.displayName ?: "" }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(Ca.radius.pill)).clickable { onOpen(true) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val chevron by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
            Icon(CaIcons.chevronDown, null, Modifier.size(12.dp).rotate(chevron), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CaDropdownMenu(expanded = open, onDismissRequest = { onOpen(false) }) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) },
                    onClick = { onPick(model.id); onOpen(false) },
                )
            }
        }
    }
}

/**
 * The conversation. It follows a streaming reply only while the reader is at the bottom: scrolling up to read stops
 * the follow, a jump-to-latest button brings it back, and a message the user sends always returns to the bottom.
 * Following keeps the END of the newest message in view. Scrolling to that message's index instead put its top at
 * the top of the screen, which for a tall tool-heavy turn held the view on its first tool call while the answer
 * streamed in below the fold.
 */
@Composable
internal fun Transcript(
    messages: List<UiAgentMessage>,
    busy: Boolean,
    onRetry: () -> Unit,
    onUndo: (Long) -> Unit,
    onUseModel: (String) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    var following by remember { mutableStateOf(true) }
    // Set by a scroll the reader made. Only the scrollable's own gestures, wheel and fling pass through nested
    // scroll, so the programmatic scrolls below never set it.
    var readerScrolled by remember { mutableStateOf(false) }
    var seen by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // The zero-height anchor after the last message. Scrolling to it lands on the very end of the content, because
    // the list will not leave empty space below its last item.
    val end = messages.size
    val readerScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                readerScrolled = true
                // Moving back through the conversation stops the follow at once, so a reply streaming in meanwhile
                // cannot pull the view back down.
                if (consumed.y > 0f) following = false
                return Offset.Zero
            }
        }
    }
    // Where a reader's scroll settles decides whether to follow again: at the bottom it does. A reader's scroll is
    // measured as it is applied, so the list already knows whether it can go further.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && readerScrolled) {
                readerScrolled = false
                following = !listState.canScrollForward
            }
        }
    }
    // A message the user sends returns to the end, wherever the reader was.
    LaunchedEffect(messages.size) {
        if (messages.size < seen) seen = 0
        if (messages.drop(seen).any { it.role == UiAgentRole.USER }) following = true
        seen = messages.size
    }
    // Following is driven by the measured layout, not by message changes: whenever the list can scroll further while
    // following, it goes to the end. That catches every way the content grows (a streamed token, a tool row, a
    // folding run, an expanding diff), and it scrolls against the layout that already holds the new content. A
    // scroll issued from a message change ran before that layout and was clamped to the old, shorter content.
    val currentEnd by rememberUpdatedState(messages.size)
    LaunchedEffect(listState) {
        snapshotFlow { following && listState.canScrollForward && !listState.isScrollInProgress }
            .collectLatest { behind ->
                // Not during a scroll: that is the reader's drag or fling, or the jump button's animation, and an
                // instant scroll here would cancel it. Retried a few frames while a layout is still settling.
                var tries = 0
                while (behind && tries++ < 3 && following && listState.canScrollForward && !listState.isScrollInProgress) {
                    runCatching { listState.scrollToItem(currentEnd) }
                    withFrameNanos { }
                }
            }
    }
    val last = messages.lastOrNull()
    val lastId = last?.id
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(readerScroll),
            // The bottom padding is short by the spacing the end anchor adds after the last message.
            contentPadding = PaddingValues(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 2.dp),
            // Chat-app feel: content anchors to the bottom when short, newest message at the bottom.
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
        ) {
            itemsKeyed(messages, key = { it.id }) { msg ->
                // Only the most recent failure offers a retry (it resumes the latest turn).
                val latest = msg.id == lastId
                val retry = if (latest && msg.isError && msg.canRetry) onRetry else null
                val useModel = if (latest && msg.isError) msg.suggestedModel else null
                MessageItem(
                    msg, retry,
                    onUndo = if (!busy && msg.canUndo) ({ onUndo(msg.id) }) else null,
                    suggestedModel = useModel,
                    onUseModel = onUseModel,
                )
            }
            item(key = TRANSCRIPT_END) { Spacer(Modifier.fillMaxWidth().height(1.dp)) }
        }
        AnimatedVisibility(
            visible = !following && listState.canScrollForward,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            SmallFloatingActionButton(
                onClick = {
                    following = true
                    scope.launch { runCatching { listState.animateScrollToItem(end) } }
                },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(CaIcons.chevronDown, stringResource(Res.string.chat_jump_latest), Modifier.size(18.dp))
            }
        }
    }
}

/** The key of the transcript's end anchor; message keys are their Long ids, so a String cannot collide. */
private const val TRANSCRIPT_END = "transcript-end"

@Composable
private fun MessageItem(
    msg: UiAgentMessage,
    onRetry: (() -> Unit)? = null,
    onUndo: (() -> Unit)? = null,
    suggestedModel: String? = null,
    onUseModel: (String) -> Unit = {},
) {
    // One selection scope per message, so a drag (or a long-press and its handles) can take any span of it, across
    // its paragraphs and code blocks. Controls inside opt out with DisableSelection.
    SelectionContainer(Modifier.fillMaxWidth().entranceSlideUp()) {
        MessageContent(msg, onRetry, onUndo, suggestedModel, onUseModel)
    }
}

@Composable
private fun MessageContent(
    msg: UiAgentMessage,
    onRetry: (() -> Unit)?,
    onUndo: (() -> Unit)?,
    suggestedModel: String?,
    onUseModel: (String) -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        when {
            msg.isError -> ErrorMessage(msg.text, onRetry, suggestedModel, onUseModel)
            msg.role == UiAgentRole.USER -> Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(
                    Modifier.widthIn(max = 320.dp)
                        // Chat-bubble rounding: a small corner on the tail side (bottom-end for the user).
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    // onSecondaryContainer, not onSurface: a tonal container carries its own content colour,
                    // and borrowing the surface's is exactly how a bubble ends up unreadable in one theme.
                    Text(
                        msg.text,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = chatBodyStyle(),
                    )
                }
                AttachmentChips(msg.attachments, modifier = Modifier.widthIn(max = 320.dp))
                DisableSelection {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (msg.undone) {
                            Text(
                                stringResource(Res.string.chat_undone),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (onUndo != null) {
                            TextButton(onClick = onUndo) {
                                Icon(CaIcons.undo, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(Res.string.chat_undo), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        CopyButton(msg.text)
                    }
                }
            }
            else -> AssistantMessage(msg)
        }
    }
}

@Composable
private fun ErrorMessage(
    text: String,
    onRetry: (() -> Unit)?,
    suggestedModel: String? = null,
    onUseModel: (String) -> Unit = {},
) {
    // The errorContainer pair rather than a translucent error tint: it stays legible in both themes and
    // against any Material You palette, which an alpha-over-surface fill does not.
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
            .background(scheme.errorContainer, MaterialTheme.shapes.medium)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Icon(CaIcons.warning, null, Modifier.size(16.dp), tint = scheme.onErrorContainer)
            Text(text, color = scheme.onErrorContainer, style = chatBodyStyle())
        }
        DisableSelection {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CopyButton(text, tint = scheme.onErrorContainer)
                // The one-tap fix for a model the account cannot use: switch and re-run, rather than leaving the user
                // to find the model picker and guess which model would work.
                if (suggestedModel != null) {
                    TextButton(
                        onClick = { onUseModel(suggestedModel) },
                        colors = ButtonDefaults.textButtonColors(contentColor = scheme.onErrorContainer),
                    ) {
                        Icon(CaIcons.sparkle, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.chat_use_model, suggestedModel), style = MaterialTheme.typography.labelLarge)
                    }
                } else if (onRetry != null) {
                    TextButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.textButtonColors(contentColor = scheme.onErrorContainer),
                    ) {
                        Icon(CaIcons.refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.chat_retry), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/** One step as the transcript draws it: a [UiAgentSegment] with its tool ids resolved to their calls. */
private sealed interface Step {
    data class Text(val text: String) : Step
    data class Thinking(val text: String) : Step
    data class Tools(val calls: List<UiAgentToolCall>) : Step
}

/**
 * The message's steps in the order they happened. A message with no recorded steps (saved by an older version, or
 * plain text such as an error) falls back to reasoning, then tools, then text.
 */
private fun stepsOf(msg: UiAgentMessage): List<Step> {
    if (msg.segments.isEmpty()) {
        return buildList {
            if (msg.thinking.isNotBlank()) add(Step.Thinking(msg.thinking))
            if (msg.toolCalls.isNotEmpty()) add(Step.Tools(msg.toolCalls))
            if (msg.text.isNotBlank()) add(Step.Text(msg.text))
        }
    }
    val byId = msg.toolCalls.associateBy { it.id }
    return msg.segments.map { seg ->
        when (seg) {
            is UiAgentSegment.Text -> Step.Text(seg.text)
            is UiAgentSegment.Thinking -> Step.Thinking(seg.text)
            is UiAgentSegment.Tools -> Step.Tools(seg.ids.mapNotNull(byId::get))
        }
    }
}

@Composable
private fun AssistantMessage(msg: UiAgentMessage) {
    val steps = remember(msg.segments, msg.toolCalls, msg.text, msg.thinking) { stepsOf(msg) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { i, step ->
            // The newest step of a running turn is the live one: its reasoning stays open and its tool run stays
            // expanded until the turn moves past it.
            val live = msg.streaming && i == steps.lastIndex
            key(i) {
                when (step) {
                    is Step.Thinking -> if (step.text.isNotBlank()) ThinkingStep(step.text, live)
                    is Step.Tools -> if (step.calls.isNotEmpty()) DisableSelection { ToolRun(step.calls, live) }
                    is Step.Text -> if (step.text.isNotBlank()) AssistantMarkdown(step.text)
                }
            }
        }
        msg.waitUntilMs?.let { WaitRow(it, msg.waitReason) }
        val lastStep = steps.lastOrNull()
        // A blinking caret while the answer is still streaming in.
        if (msg.streaming && lastStep is Step.Text && lastStep.text.isNotBlank()) TypingCaret()
        // Something live at the end of a running turn that has nothing streaming yet: before the first token, and
        // between a finished batch of tools and the model's next step, which otherwise looks stalled.
        val idle = lastStep == null ||
            (lastStep is Step.Tools && lastStep.calls.none { it.status == UiAgentToolStatus.RUNNING })
        if (msg.streaming && msg.waitUntilMs == null && idle) ThinkingStep(thinking = "", live = true)
        // Copy the finished answer.
        DisableSelection {
            if (!msg.streaming && msg.text.isNotBlank()) CopyButton(msg.text)
            if (!msg.streaming) msg.usage?.let { UsageFooter(it) }
        }
    }
}

/**
 * What the finished turn cost, as a quiet footer. The cached figure is the point of it: a loop that is caching
 * properly shows it climbing turn over turn while the billed input stays small, so a run where it never appears
 * is the visible symptom of a prompt prefix that changed and threw the cache away.
 */
@Composable
private fun UsageFooter(usage: UiAgentUsage) {
    val parts = buildList {
        if (usage.input > 0) add(compactTokens(usage.input) + " " + stringResource(Res.string.chat_usage_input))
        if (usage.output > 0) add(compactTokens(usage.output) + " " + stringResource(Res.string.chat_usage_output))
        if (usage.cacheRead > 0) {
            add(compactTokens(usage.cacheRead) + " " + stringResource(Res.string.chat_usage_cached))
        }
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Token counts as "940" / "8.4k" / "1.2M" — enough to read a trend, short enough for a one-line footer. */
private fun compactTokens(count: Int): String = when {
    count < 1_000 -> count.toString()
    count < 1_000_000 -> "${count / 1000}.${(count % 1000) / 100}k"
    else -> "${count / 1_000_000}.${(count % 1_000_000) / 100_000}M"
}

/**
 * A run of consecutive tool calls. One call is a single compact row. Several get a summary header and stay open
 * while the run is live or still running, then fold to that header once the turn moves past them, so a tool-heavy
 * turn reads as its prose with a line per batch of work. A tap on the header overrides the automatic choice.
 */
@Composable
private fun ToolRun(calls: List<UiAgentToolCall>, live: Boolean) {
    if (calls.size == 1) {
        ToolCallRow(calls[0])
        return
    }
    var choice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val expanded = choice ?: (live || calls.any { it.status == UiAgentToolStatus.RUNNING })
    val rail = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth().entranceSlideUp()) {
        ToolRunHeader(calls, expanded) { choice = !expanded }
        if (expanded) {
            // A hairline rail ties the rows to their header, the way a nested list is indented under its parent.
            Column(
                Modifier.fillMaxWidth().padding(start = 8.dp)
                    .drawBehind { drawRect(rail, size = Size(1.dp.toPx(), size.height)) }
                    .padding(start = 10.dp),
            ) {
                calls.forEach { call -> key(call.id) { ToolCallRow(call) } }
            }
        }
    }
}

@Composable
private fun ToolRunHeader(calls: List<UiAgentToolCall>, expanded: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val files = remember(calls) { calls.flatMap { it.changes }.map { it.path }.distinct().size }
    val summary = buildString {
        append(pluralStringResource(Res.plurals.chat_tool_count, calls.size, calls.size))
        if (files > 0) append(" · ").append(pluralStringResource(Res.plurals.chat_files_changed, files, files))
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle)
            .padding(horizontal = 2.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToolStatusIcon(aggregateStatus(calls))
        Text(summary, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface, fontWeight = FontWeight.Medium, maxLines = 1)
        // Folded, the header still says what the run did last.
        Text(
            if (expanded) "" else calls.last().title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "toolChevron")
        Icon(CaIcons.chevronDown, null, Modifier.size(14.dp).rotate(rotation), tint = scheme.onSurfaceVariant)
    }
}

/** The status shown on a collapsed tool group: running wins, then error, then denied, else all-ok. */
private fun aggregateStatus(calls: List<UiAgentToolCall>): UiAgentToolStatus = when {
    calls.any { it.status == UiAgentToolStatus.RUNNING } -> UiAgentToolStatus.RUNNING
    calls.any { it.status == UiAgentToolStatus.ERROR } -> UiAgentToolStatus.ERROR
    calls.any { it.status == UiAgentToolStatus.DENIED } -> UiAgentToolStatus.DENIED
    else -> UiAgentToolStatus.OK
}

/** One-tap copy of a message's text, flipping to a check for ~1.5s as confirmation. */
@Composable
private fun CopyButton(text: String, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    IconButtonCa(
        if (copied) CaIcons.check else CaIcons.copy,
        stringResource(if (copied) Res.string.chat_copied else Res.string.chat_copy),
        onClick = {
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
        iconSize = 14,
        boxSize = 26,
        tint = if (copied) Ide.colors.success else tint,
    )
}

@Composable
private fun TypingCaret() {
    Box(
        Modifier.size(width = 7.dp, height = 14.dp)
            .background(pulseColor(true), RoundedCornerShape(2.dp)),
    )
}

/**
 * The model's reasoning. Open while it streams, so there is something to watch. Once the turn moves on it folds to one
 * "Thought" line that expands on tap: a finished turn's reasoning is rarely worth its screen space.
 */
@Composable
private fun ThinkingStep(thinking: String, live: Boolean) {
    var open by rememberSaveable { mutableStateOf(false) }
    val expanded = live || open
    val scheme = MaterialTheme.colorScheme
    val canToggle = !live && thinking.isNotBlank()
    Column(
        Modifier.fillMaxWidth().entranceSlideUp().clip(MaterialTheme.shapes.medium)
            .background(if (expanded) scheme.surfaceContainerHigh else Color.Transparent)
            .then(if (canToggle) Modifier.clickable { open = !open } else Modifier)
            .padding(horizontal = if (expanded) 12.dp else 2.dp, vertical = if (expanded) 10.dp else 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(CaIcons.sparkle, null, Modifier.size(12.dp), tint = pulseColor(live))
            Text(
                stringResource(if (live) Res.string.chat_thinking else Res.string.chat_thought),
                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
            )
            if (canToggle) {
                val rotation by animateFloatAsState(if (open) 0f else -90f, label = "thoughtChevron")
                Icon(CaIcons.chevronDown, null, Modifier.size(12.dp).rotate(rotation), tint = scheme.onSurfaceVariant)
            }
        }
        if (expanded && thinking.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(thinking, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

/**
 * One tool call as a single line: status, the call (its verb set apart from its target), and its result. A long
 * result is cut to the line; a tap shows it whole. Files the call changed list under it, each expanding to its diff.
 */
@Composable
private fun ToolCallRow(call: UiAgentToolCall) {
    var open by rememberSaveable(call.id) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val canOpen = call.detail.isNotBlank()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .then(if (canOpen) Modifier.clickable { open = !open } else Modifier)
                .padding(horizontal = 2.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolStatusIcon(call.status)
            Text(
                toolTitle(call.title, scheme.onSurfaceVariant),
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (canOpen && !open) {
                Text(
                    call.detail,
                    Modifier.widthIn(max = 160.dp),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (open) {
            Text(
                call.detail,
                Modifier.padding(start = 24.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
        }
        if (call.changes.isNotEmpty()) Box(Modifier.padding(start = 22.dp, top = 2.dp)) { FileChangesList(call.changes) }
    }
}

/** A call's title with its first word, the tool's verb, in a quieter weight than what it acted on. */
private fun toolTitle(title: String, verbColor: Color): AnnotatedString = buildAnnotatedString {
    val split = title.indexOf(' ')
    if (split <= 0) {
        append(title)
        return@buildAnnotatedString
    }
    withStyle(SpanStyle(color = verbColor, fontWeight = FontWeight.Medium)) { append(title.substring(0, split)) }
    append(title.substring(split))
}

@Composable
private fun ToolStatusIcon(status: UiAgentToolStatus) {
    // Cross-fade so the spinner dissolves into the check/error glyph when the call resolves.
    Crossfade(targetState = status, label = "toolStatus") { s ->
        when (s) {
            UiAgentToolStatus.RUNNING -> CircularProgressIndicator(
                Modifier.size(14.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 1.5.dp,
            )
            UiAgentToolStatus.OK -> Icon(CaIcons.check, null, Modifier.size(14.dp), tint = Ide.colors.success)
            UiAgentToolStatus.ERROR -> Icon(CaIcons.error, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
            UiAgentToolStatus.DENIED -> Icon(CaIcons.close, null, Modifier.size(14.dp), tint = Ide.colors.warning)
        }
    }
}

/**
 * The style for message text. A message is in whatever language its writer used (an English provider error, a
 * model's reply, the user's prompt), not the UI's, so each paragraph takes its direction from its own first
 * strong character, as Android's TextView does. Compose otherwise lays it out in the UI's direction, which put an
 * English sentence's final period on the far left under the Arabic UI.
 */
@Composable
private fun chatBodyStyle(): TextStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)

@Composable
private fun AssistantMarkdown(text: String) {
    // The shared Markdown renderer (headings, lists, quotes, emphasis, links), tuned to the chat's compact
    // footnote style; fenced code uses the syntax-highlighted CodeSample card.
    Markdown(
        text,
        paragraphStyle = chatBodyStyle(),
        headingStyle = { defaultHeadingStyle(it).copy(textDirection = TextDirection.Content) },
        color = MaterialTheme.colorScheme.onSurface,
        spacing = 8.dp,
        codeBlock = { code, lang -> CodeSample(code, lang) },
    )
}

@Composable
private fun EmptyState(configured: Boolean, onManage: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.entrancePop()) { SparkleBadge(size = 44, animated = true) }
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(Res.string.chat_empty_title),
            modifier = Modifier.entranceSlideUp(90),
            color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (configured) stringResource(Res.string.chat_empty_body) else stringResource(Res.string.chat_need_key),
            modifier = Modifier.entranceSlideUp(150),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
        )
        if (!configured) {
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                stringResource(Res.string.chat_add_key),
                onManage,
                Modifier.entranceSlideUp(210),
                icon = CaIcons.key,
            )
        }
    }
}

@Composable
private fun Composer(
    value: TextFieldValue,
    configured: Boolean,
    busy: Boolean,
    images: ImageSources,
    activeFilePath: String?,
    onAttachFile: (String) -> Unit,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (images.any || activeFilePath != null) {
            var attachOpen by remember { mutableStateOf(false) }
            Box {
                IconButtonCa(
                    CaIcons.plus, stringResource(Res.string.chat_attach), { attachOpen = true },
                    iconSize = 18, boxSize = 36, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CaDropdownMenu(expanded = attachOpen, onDismissRequest = { attachOpen = false }) {
                    images.pick?.let { pick ->
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.chat_attach_image), style = MaterialTheme.typography.bodyMedium) },
                            leadingIcon = { Icon(CaIcons.image, null, Modifier.size(16.dp)) },
                            onClick = { attachOpen = false; pick() },
                        )
                    }
                    images.camera?.let { camera ->
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.chat_attach_photo), style = MaterialTheme.typography.bodyMedium) },
                            leadingIcon = { Icon(CaIcons.eye, null, Modifier.size(16.dp)) },
                            onClick = { attachOpen = false; camera() },
                        )
                    }
                    // Offered only while there is an image to paste, checked as the menu opens.
                    val paste = images.paste
                    if (paste != null && remember(attachOpen) { images.hasClipboardImage() }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.chat_paste_image), style = MaterialTheme.typography.bodyMedium) },
                            leadingIcon = { Icon(CaIcons.copy, null, Modifier.size(16.dp)) },
                            onClick = { attachOpen = false; paste() },
                        )
                    }
                    if (activeFilePath != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.chat_attach_file), style = MaterialTheme.typography.bodyMedium) },
                            leadingIcon = { Icon(CaIcons.file, null, Modifier.size(16.dp)) },
                            onClick = { attachOpen = false; onAttachFile(activeFilePath) },
                        )
                    }
                }
            }
        }
        // The focus outline follows M3's own treatment: the field sits on a container fill and gains a primary
        // outline while focused. Motion uses the app's expressive springs rather than a linear tween.
        val scheme = MaterialTheme.colorScheme
        val fieldInteraction = remember { MutableInteractionSource() }
        val focused by fieldInteraction.collectIsFocusedAsState()
        val borderColor by animateColorAsState(
            if (focused) scheme.primary else scheme.outlineVariant,
            animationSpec = CaMotion.defaultEffects(),
            label = "composerBorder",
        )
        val borderWidth by animateDpAsState(
            if (focused) 2.dp else 1.dp,
            animationSpec = CaMotion.defaultEffects(),
            label = "composerBorderWidth",
        )
        val fieldShape = RoundedCornerShape(Ca.radius.xl)
        Box(
            Modifier.weight(1f)
                .background(scheme.surfaceContainerHighest, fieldShape)
                .border(borderWidth, borderColor, fieldShape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (value.text.isEmpty()) {
                Text(
                    stringResource(if (configured) Res.string.chat_placeholder else Res.string.chat_need_key),
                    color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = configured && !busy,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                maxLines = 5,
                interactionSource = fieldInteraction,
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                    when {
                        event.type != KeyEventType.KeyDown -> false
                        event.key == Key.Enter && !event.isShiftPressed -> { onSend(); true }
                        // Paste claims Ctrl/Cmd+V only when the clipboard holds an image; text paste is untouched.
                        event.key == Key.V && (event.isCtrlPressed || event.isMetaPressed) &&
                            images.paste != null && images.hasClipboardImage() -> { images.paste.invoke(); true }
                        else -> false
                    }
                },
            )
        }
        // A native filled icon button, so the disabled state, ripple and tonal roles all come from the theme.
        // While a turn is running it becomes the stop control rather than a second button appearing beside it.
        val canSend = configured && value.text.isNotBlank() && !busy
        FilledIconButton(
            onClick = { if (busy) onStop() else onSend() },
            enabled = busy || canSend,
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
        ) {
            Crossfade(targetState = busy, label = "sendIcon") { b ->
                Icon(
                    if (b) CaIcons.stop else CaIcons.arrowRight,
                    stringResource(if (b) Res.string.chat_stop else Res.string.chat_send),
                    Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * The agent's mark: a tonal container rather than the gradient slab it used to be. Material You supplies the
 * hue, so on Android 12+ it is the wallpaper's own primary — the chat reads as part of the system, not as a
 * separately-branded panel bolted into the IDE.
 */
@Composable
private fun SparkleBadge(size: Int, animated: Boolean = false) {
    val breathe = rememberInfiniteTransition(label = "sparkle")
    val pulse by breathe.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(1900), RepeatMode.Reverse),
        label = "sparkleScale",
    )
    val scale = if (animated) pulse else 1f
    Box(
        Modifier.size(size.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape((size / 2.6f).dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            CaIcons.sparkle, null, Modifier.size((size * 0.58f).dp).scale(scale),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * The header and composer sit on a tonal container so the transcript is the only thing on the base surface —
 * the same separation the editor chrome uses, and what makes a long scroll read as content moving under
 * fixed furniture rather than one flat sheet.
 */
@Composable
private fun ChatChrome(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) { content() }
}

/**
 * The "still working" pulse, as a colour animation between two solid theme roles rather than a fading alpha.
 * A pulsing alpha dims the glyph toward whatever sits behind it; lerping between two opaque colours keeps the
 * same sense of motion with contrast that holds at every point in the cycle.
 */
@Composable
private fun pulseColor(active: Boolean): Color {
    val scheme = MaterialTheme.colorScheme
    if (!active) return scheme.primary
    val transition = rememberInfiniteTransition(label = "pulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    return lerp(scheme.onSurfaceVariant, scheme.primary, progress)
}

@Composable
private fun modeLabel(mode: UiAgentPermissionMode): String = when (mode) {
    UiAgentPermissionMode.ASK_EACH -> stringResource(Res.string.chat_mode_ask)
    UiAgentPermissionMode.AUTO_ACCEPT -> stringResource(Res.string.chat_mode_auto)
    UiAgentPermissionMode.PLAN_ONLY -> stringResource(Res.string.chat_mode_plan)
}

private fun nextMode(mode: UiAgentPermissionMode): UiAgentPermissionMode = when (mode) {
    UiAgentPermissionMode.ASK_EACH -> UiAgentPermissionMode.AUTO_ACCEPT
    UiAgentPermissionMode.AUTO_ACCEPT -> UiAgentPermissionMode.PLAN_ONLY
    UiAgentPermissionMode.PLAN_ONLY -> UiAgentPermissionMode.ASK_EACH
}

