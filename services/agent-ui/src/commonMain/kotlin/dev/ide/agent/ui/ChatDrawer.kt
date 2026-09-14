package dev.ide.agent.ui

import dev.ide.ui.components.*

import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAgentConfig
import dev.ide.ui.backend.UiAgentMessage
import dev.ide.ui.backend.UiAgentModel
import dev.ide.ui.backend.UiAgentPermissionMode
import dev.ide.ui.backend.UiAgentRole
import dev.ide.ui.backend.UiAgentToolCall
import dev.ide.ui.backend.UiAgentToolStatus
import dev.ide.ui.backend.UiAgentUsage
import dev.ide.agent.ui.generated.resources.Res
import dev.ide.agent.ui.generated.resources.chat_add_key
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
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.CaMotion
import dev.ide.ui.theme.Ide
import org.jetbrains.compose.resources.stringResource

/**
 * The AI agent chat drawer: a streamed transcript over the tool-using agent. Surface-agnostic (the caller
 * wraps it in a glass pane), mirroring the build console. Renders user/assistant messages, streaming
 * reasoning, per-tool-call status, and a composer; a right-edge drawer on desktop. See docs/agentic-coding.md.
 */
@Composable
fun ChatDrawer(backend: IdeBackend, onClose: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val chat by backend.agent.chatState.collectAsState()
    val models by backend.agent.models.collectAsState()
    var cfg by remember { mutableStateOf(backend.agent.config()) }
    var input by remember { mutableStateOf("") }
    var showProviders by remember { mutableStateOf(false) }

    // Fetch the provider's live model list when the drawer opens or the provider changes.
    LaunchedEffect(cfg.selectedProvider) { backend.agent.refreshModels() }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ChatChrome {
                ChatHeader(
                cfg = cfg,
                models = models.ifEmpty { cfg.providers.firstOrNull { it.id == cfg.selectedProvider }?.models ?: emptyList() },
                onPickModel = { backend.agent.setModel(it); cfg = backend.agent.config() },
                onManage = { showProviders = true },
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
                    Transcript(chat.messages, onRetry = { backend.agent.retry() })
                }
            }
            ChatChrome {
                Composer(
                value = input,
                configured = cfg.configured,
                busy = chat.busy,
                onValueChange = { input = it },
                onSend = {
                    if (input.isNotBlank()) {
                        backend.agent.send(input)
                        input = ""
                    }
                },
                onStop = { backend.agent.stop() },
                )
            }
        }
        if (showProviders) {
            AgentProvidersSheet(backend) {
                showProviders = false
                cfg = backend.agent.config()
            }
        }
    }
}

@Composable
private fun ChatHeader(
    cfg: UiAgentConfig,
    models: List<UiAgentModel>,
    onPickModel: (String) -> Unit,
    onManage: () -> Unit,
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
            )
            ModelPicker(cfg = cfg, models = models, onPick = onPickModel)
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
        IconButtonCa(CaIcons.key, stringResource(Res.string.chat_manage_keys), onManage, iconSize = 16, boxSize = 30)
        IconButtonCa(CaIcons.refresh, stringResource(Res.string.chat_new), onNew, iconSize = 16, boxSize = 30)
        if (onClose != null) {
            IconButtonCa(CaIcons.close, stringResource(Res.string.chat_close), onClose, iconSize = 16, boxSize = 30)
        }
    }
}

@Composable
private fun ModelPicker(cfg: UiAgentConfig, models: List<UiAgentModel>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val provider = cfg.providers.firstOrNull { it.id == cfg.selectedProvider }
    val current = cfg.model.ifBlank { provider?.defaultModel ?: "" }
    val label = models.firstOrNull { it.id == current }?.displayName
        ?: current.ifBlank { provider?.displayName ?: "" }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(Ca.radius.pill)).clickable { open = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val chevron by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
            Icon(CaIcons.chevronDown, null, Modifier.size(12.dp).rotate(chevron), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CaDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) },
                    onClick = { onPick(model.id); open = false },
                )
            }
        }
    }
}

@Composable
private fun Transcript(messages: List<UiAgentMessage>, onRetry: () -> Unit) {
    val listState = rememberLazyListState()
    val last = messages.lastOrNull()
    val tail = (last?.text?.length ?: 0) + (last?.thinking?.length ?: 0) + (last?.toolCalls?.size ?: 0)
    LaunchedEffect(messages.size, tail) {
        if (messages.isNotEmpty()) runCatching { listState.animateScrollToItem(messages.lastIndex) }
    }
    val lastId = last?.id
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        // Chat-app feel: content anchors to the bottom when short, newest message at the bottom.
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
    ) {
        items(messages, key = { it.id }) { msg ->
            // Only the most recent failure offers a retry (it resumes the latest turn).
            val retry = if (msg.id == lastId && msg.isError && msg.canRetry) onRetry else null
            MessageItem(msg, retry)
        }
    }
}

@Composable
private fun MessageItem(msg: UiAgentMessage, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().entranceSlideUp()) {
        when {
            msg.isError -> ErrorMessage(msg.text, onRetry)
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
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                CopyButton(msg.text)
            }
            else -> AssistantMessage(msg)
        }
    }
}

@Composable
private fun ErrorMessage(text: String, onRetry: (() -> Unit)?) {
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
            Text(text, color = scheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CopyButton(text, tint = scheme.onErrorContainer)
            if (onRetry != null) {
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

@Composable
private fun AssistantMessage(msg: UiAgentMessage) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (msg.thinking.isNotBlank()) ThinkingBlock(msg.thinking, msg.streaming)
        if (msg.toolCalls.isNotEmpty()) ToolCallsSection(msg.toolCalls)
        if (msg.text.isNotBlank()) AssistantMarkdown(msg.text)
        // A blinking caret while the answer is still streaming in.
        if (msg.streaming && msg.text.isNotBlank()) TypingCaret()
        if (msg.streaming && msg.text.isBlank() && msg.thinking.isBlank() && msg.toolCalls.isEmpty()) {
            ThinkingBlock(thinking = "", streaming = true)
        }
        // Copy the finished answer.
        if (!msg.streaming && msg.text.isNotBlank()) CopyButton(msg.text)
        if (!msg.streaming) msg.usage?.let { UsageFooter(it) }
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
 * The turn's tool calls. A single call renders as one row; several collapse under a header (chevron +
 * aggregate status + count, plus the latest tool's title when collapsed) so a long tool-heavy turn does not
 * flood the transcript. Expanded by default; the collapse state is remembered per message.
 */
@Composable
private fun ToolCallsSection(calls: List<UiAgentToolCall>) {
    if (calls.size <= 1) {
        calls.forEach { ToolCallRow(it) }
        return
    }
    var expanded by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ToolGroupHeader(calls, expanded) { expanded = !expanded }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { calls.forEach { ToolCallRow(it) } }
        }
    }
}

@Composable
private fun ToolGroupHeader(calls: List<UiAgentToolCall>, expanded: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "toolChevron")
        Icon(CaIcons.chevronDown, null, Modifier.size(14.dp).rotate(rotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        ToolStatusIcon(aggregateStatus(calls))
        // A bare count is locale-safe (no pluralized label needed).
        Text("${calls.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        if (!expanded) {
            Text(
                calls.last().title,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
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
    val alpha = pulseAlpha(true)
    Box(
        Modifier.size(width = 7.dp, height = 14.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha), RoundedCornerShape(2.dp)),
    )
}

@Composable
private fun ThinkingBlock(thinking: String, streaming: Boolean) {
    Column(
        Modifier.fillMaxWidth().entranceSlideUp().background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                CaIcons.sparkle, null, Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha(streaming)),
            )
            Text(stringResource(Res.string.chat_thinking), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (thinking.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(thinking, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToolCallRow(call: UiAgentToolCall) {
    Row(
        Modifier.fillMaxWidth().entranceSlideUp().background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToolStatusIcon(call.status)
        Column(Modifier.weight(1f)) {
            Text(call.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (call.detail.isNotBlank()) {
                Text(call.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
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

@Composable
private fun AssistantMarkdown(text: String) {
    // The shared Markdown renderer (headings, lists, quotes, emphasis, links), tuned to the chat's compact
    // footnote style; fenced code uses the syntax-highlighted CodeSample card.
    Markdown(
        text,
        paragraphStyle = MaterialTheme.typography.bodyMedium,
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
private fun ErrorBar(message: String) {
    Text(
        message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun Composer(
    value: String,
    configured: Boolean,
    busy: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
            if (value.isEmpty()) {
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
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                        onSend()
                        true
                    } else {
                        false
                    }
                },
            )
        }
        // A native filled icon button, so the disabled state, ripple and tonal roles all come from the theme.
        // While a turn is running it becomes the stop control rather than a second button appearing beside it.
        val canSend = configured && value.isNotBlank() && !busy
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

@Composable
private fun pulseAlpha(active: Boolean): Float {
    if (!active) return 1f
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    return alpha
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

