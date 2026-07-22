package dev.ide.ui.components

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAgentConfig
import dev.ide.ui.backend.UiAgentMessage
import dev.ide.ui.backend.UiAgentModel
import dev.ide.ui.backend.UiAgentPermissionMode
import dev.ide.ui.backend.UiAgentRole
import dev.ide.ui.backend.UiAgentToolCall
import dev.ide.ui.backend.UiAgentToolStatus
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.chat_add_key
import dev.ide.ui.generated.resources.chat_close
import dev.ide.ui.generated.resources.chat_manage_keys
import dev.ide.ui.generated.resources.chat_empty_body
import dev.ide.ui.generated.resources.chat_empty_title
import dev.ide.ui.generated.resources.chat_mode_ask
import dev.ide.ui.generated.resources.chat_mode_auto
import dev.ide.ui.generated.resources.chat_mode_plan
import dev.ide.ui.generated.resources.chat_need_key
import dev.ide.ui.generated.resources.chat_new
import dev.ide.ui.generated.resources.chat_placeholder
import dev.ide.ui.generated.resources.chat_retry
import dev.ide.ui.generated.resources.chat_send
import dev.ide.ui.generated.resources.chat_stop
import dev.ide.ui.generated.resources.chat_thinking
import dev.ide.ui.generated.resources.chat_title
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.screens.CodeSample
import dev.ide.ui.screens.inlineMarkup
import dev.ide.ui.theme.Ca
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
            Hairline()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (chat.messages.isEmpty()) {
                    EmptyState(configured = cfg.configured, onManage = { showProviders = true })
                } else {
                    Transcript(chat.messages, onRetry = { backend.agent.retry() })
                }
            }
            Hairline()
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
                color = Ca.colors.textPrimary,
                style = Ca.type.subhead,
                fontWeight = FontWeight.SemiBold,
            )
            ModelPicker(cfg = cfg, models = models, onPick = onPickModel)
        }
        Chip(
            text = modeLabel(cfg.mode),
            modifier = Modifier.clip(RoundedCornerShape(Ca.radius.pill)).clickable(onClick = onCycleMode),
            fill = Ca.colors.accentSoft,
            textColor = Ca.colors.accent,
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
            Text(label, color = Ca.colors.textTertiary, style = Ca.type.caption2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val chevron by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
            Icon(CaIcons.chevronDown, null, Modifier.size(12.dp).rotate(chevron), tint = Ca.colors.textTertiary)
        }
        CaDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.displayName, style = Ca.type.footnote, color = Ca.colors.textPrimary) },
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
            msg.role == UiAgentRole.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier.widthIn(max = 320.dp)
                        // Chat-bubble rounding: a small corner on the tail side (bottom-end for the user).
                        .background(Ca.colors.accentSoft, RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(msg.text, color = Ca.colors.textPrimary, style = Ca.type.footnote)
                }
            }
            else -> AssistantMessage(msg)
        }
    }
}

@Composable
private fun ErrorMessage(text: String, onRetry: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth()
            .background(Ca.colors.error.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Icon(CaIcons.warning, null, Modifier.size(15.dp), tint = Ca.colors.error)
            Text(text, color = Ca.colors.error, style = Ca.type.footnote)
        }
        if (onRetry != null) {
            val interaction = remember { MutableInteractionSource() }
            Row(
                Modifier.clip(RoundedCornerShape(Ca.radius.pill))
                    .background(Ca.colors.error.copy(alpha = 0.16f))
                    .pressScale(interaction)
                    .clickable(interactionSource = interaction, indication = null, onClick = onRetry)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(CaIcons.refresh, null, Modifier.size(13.dp), tint = Ca.colors.error)
                Text(
                    stringResource(Res.string.chat_retry),
                    color = Ca.colors.error, style = Ca.type.caption, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun AssistantMessage(msg: UiAgentMessage) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (msg.thinking.isNotBlank()) ThinkingBlock(msg.thinking, msg.streaming)
        msg.toolCalls.forEach { ToolCallRow(it) }
        if (msg.text.isNotBlank()) AssistantMarkdown(msg.text)
        // A blinking caret while the answer is still streaming in.
        if (msg.streaming && msg.text.isNotBlank()) TypingCaret()
        if (msg.streaming && msg.text.isBlank() && msg.thinking.isBlank() && msg.toolCalls.isEmpty()) {
            ThinkingBlock(thinking = "", streaming = true)
        }
    }
}

@Composable
private fun TypingCaret() {
    val alpha = pulseAlpha(true)
    Box(
        Modifier.size(width = 7.dp, height = 14.dp)
            .background(Ca.colors.accent.copy(alpha = alpha), RoundedCornerShape(2.dp)),
    )
}

@Composable
private fun ThinkingBlock(thinking: String, streaming: Boolean) {
    Column(
        Modifier.fillMaxWidth().entranceSlideUp().background(Ca.colors.surface2, RoundedCornerShape(14.dp)).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                CaIcons.sparkle, null, Modifier.size(12.dp),
                tint = Ca.colors.accent.copy(alpha = pulseAlpha(streaming)),
            )
            Text(stringResource(Res.string.chat_thinking), style = Ca.type.caption2, color = Ca.colors.textTertiary)
        }
        if (thinking.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(thinking, style = Ca.type.caption, color = Ca.colors.textTertiary)
        }
    }
}

@Composable
private fun ToolCallRow(call: UiAgentToolCall) {
    Row(
        Modifier.fillMaxWidth().entranceSlideUp().background(Ca.colors.surface2, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToolStatusIcon(call.status)
        Column(Modifier.weight(1f)) {
            Text(call.title, style = Ca.type.caption, color = Ca.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (call.detail.isNotBlank()) {
                Text(call.detail, style = Ca.type.caption2, color = Ca.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                Modifier.size(14.dp), color = Ca.colors.accent, strokeWidth = 1.5.dp,
            )
            UiAgentToolStatus.OK -> Icon(CaIcons.check, null, Modifier.size(14.dp), tint = Ca.colors.success)
            UiAgentToolStatus.ERROR -> Icon(CaIcons.error, null, Modifier.size(14.dp), tint = Ca.colors.error)
            UiAgentToolStatus.DENIED -> Icon(CaIcons.close, null, Modifier.size(14.dp), tint = Ca.colors.warning)
        }
    }
}

@Composable
private fun AssistantMarkdown(text: String) {
    val blocks = remember(text) { splitFences(text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            if (block.code) {
                CodeSample(block.text, block.lang)
            } else if (block.text.isNotBlank()) {
                Text(inlineMarkup(block.text), color = Ca.colors.textPrimary, style = Ca.type.footnote)
            }
        }
    }
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
            color = Ca.colors.textPrimary, style = Ca.type.headline, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (configured) stringResource(Res.string.chat_empty_body) else stringResource(Res.string.chat_need_key),
            modifier = Modifier.entranceSlideUp(150),
            color = Ca.colors.textTertiary, style = Ca.type.footnote,
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
        color = Ca.colors.error,
        style = Ca.type.caption,
        modifier = Modifier.fillMaxWidth()
            .background(Ca.colors.error.copy(alpha = 0.12f))
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
        // Focus glow: the pill's border animates to the accent and thickens while the field is focused.
        val fieldInteraction = remember { MutableInteractionSource() }
        val focused by fieldInteraction.collectIsFocusedAsState()
        val borderColor by animateColorAsState(if (focused) Ca.colors.accent else Ca.colors.hairline, label = "composerBorder")
        val borderWidth by animateDpAsState(if (focused) 1.5.dp else 1.dp, label = "composerBorderWidth")
        Box(
            Modifier.weight(1f)
                .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.xl))
                .border(borderWidth, borderColor, RoundedCornerShape(Ca.radius.xl))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    stringResource(if (configured) Res.string.chat_placeholder else Res.string.chat_need_key),
                    color = Ca.colors.textTertiary, style = Ca.type.footnote,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = configured && !busy,
                textStyle = Ca.type.footnote.copy(color = Ca.colors.textPrimary),
                cursorBrush = SolidColor(Ca.colors.accent),
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
        val canSend = configured && value.isNotBlank() && !busy
        val sendInteraction = remember { MutableInteractionSource() }
        Box(
            Modifier.size(38.dp)
                .pressScale(sendInteraction)
                .background(
                    if (busy || canSend) {
                        Brush.linearGradient(listOf(Ca.colors.accent, Ca.colors.accentStrong))
                    } else {
                        SolidColor(Ca.colors.surface3)
                    },
                    CircleShape,
                )
                .clickable(interactionSource = sendInteraction, indication = null, enabled = busy || canSend) {
                    if (busy) onStop() else onSend()
                },
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(targetState = busy, label = "sendIcon") { b ->
                Icon(
                    if (b) CaIcons.stop else CaIcons.arrowRight,
                    stringResource(if (b) Res.string.chat_stop else Res.string.chat_send),
                    Modifier.size(18.dp),
                    tint = if (b || canSend) Ca.colors.textOnAccent else Ca.colors.textTertiary,
                )
            }
        }
    }
}

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
            .background(
                Brush.linearGradient(listOf(Ca.colors.accent, Ca.colors.accentStrong)),
                RoundedCornerShape((size / 3).dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(CaIcons.sparkle, null, Modifier.size((size * 0.6f).dp).scale(scale), tint = Ca.colors.textOnAccent)
    }
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.hairline))
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

private data class MdBlock(val code: Boolean, val text: String, val lang: String)

/** Split assistant text into plain and fenced-code segments, tolerating an unclosed fence while streaming. */
private fun splitFences(text: String): List<MdBlock> {
    if (!text.contains("```")) return listOf(MdBlock(false, text, ""))
    val blocks = ArrayList<MdBlock>()
    val lines = text.split('\n')
    var inCode = false
    var lang = ""
    val buffer = StringBuilder()
    fun flush(code: Boolean) {
        if (buffer.isNotEmpty()) {
            blocks += MdBlock(code, buffer.toString().trimEnd('\n'), lang)
            buffer.setLength(0)
        }
    }
    for (line in lines) {
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                flush(true)
                inCode = false
                lang = ""
            } else {
                flush(false)
                inCode = true
                lang = line.trimStart().removePrefix("```").trim()
            }
        } else {
            buffer.append(line).append('\n')
        }
    }
    flush(inCode)
    return blocks
}
