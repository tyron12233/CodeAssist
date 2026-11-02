package dev.ide.agent.ui

import dev.ide.ui.components.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import dev.ide.agent.ui.generated.resources.free_ai_card_pitch
import dev.ide.agent.ui.generated.resources.free_ai_card_title
import dev.ide.agent.ui.generated.resources.free_ai_in_use
import dev.ide.agent.ui.generated.resources.free_ai_section_byok
import dev.ide.agent.ui.generated.resources.free_ai_use_this
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAgentProvider
import dev.ide.ui.backend.UiOpenRouterSignIn
import dev.ide.agent.ui.generated.resources.Res
import dev.ide.agent.ui.generated.resources.chat_add_backup_key
import dev.ide.agent.ui.generated.resources.chat_api_key
import dev.ide.agent.ui.generated.resources.chat_api_key_optional
import dev.ide.agent.ui.generated.resources.chat_backup_key
import dev.ide.agent.ui.generated.resources.chat_backup_keys_hint
import dev.ide.agent.ui.generated.resources.chat_base_url
import dev.ide.agent.ui.generated.resources.chat_ca_cert
import dev.ide.agent.ui.generated.resources.chat_ca_cert_hint
import dev.ide.agent.ui.generated.resources.chat_cancel
import dev.ide.agent.ui.generated.resources.chat_close
import dev.ide.agent.ui.generated.resources.chat_connected
import dev.ide.agent.ui.generated.resources.chat_done
import dev.ide.agent.ui.generated.resources.chat_gateway_hint
import dev.ide.agent.ui.generated.resources.chat_hide
import dev.ide.agent.ui.generated.resources.chat_model
import dev.ide.agent.ui.generated.resources.chat_openrouter_connected
import dev.ide.agent.ui.generated.resources.chat_openrouter_connected_free
import dev.ide.agent.ui.generated.resources.chat_openrouter_failed
import dev.ide.agent.ui.generated.resources.chat_openrouter_hint
import dev.ide.agent.ui.generated.resources.chat_openrouter_or_paste
import dev.ide.agent.ui.generated.resources.chat_openrouter_sign_in
import dev.ide.agent.ui.generated.resources.chat_openrouter_waiting
import dev.ide.agent.ui.generated.resources.chat_providers_subtitle
import dev.ide.agent.ui.generated.resources.chat_providers_title
import dev.ide.agent.ui.generated.resources.chat_remove_key
import dev.ide.agent.ui.generated.resources.chat_show
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import org.jetbrains.compose.resources.stringResource

/**
 * The AI provider / key manager, as a full-screen page (the app's [ExpressiveScaffold], over the whole window rather
 * than inside the chat panel). CodeAssist Free AI leads as its own card: the sign-in, or the points and the
 * rewarded-ad button. The bring-your-own-key providers follow as selectable cards; the active one expands to its key
 * fields (and, for the custom gateway, a base URL + model). Everything auto-saves through
 * [dev.ide.ui.backend.AgentService], so the back arrow is the only way out it needs.
 * Reached from the chat header's key button and the empty-state call to action. See docs/agentic-coding.md.
 */
@Composable
internal fun AgentProvidersSheet(backend: IdeBackend, onClose: () -> Unit) {
    var cfg by remember { mutableStateOf(backend.agent.config()) }
    val signIn by backend.agent.openRouterSignIn.collectAsState()
    val freeAi by backend.agent.freeAi.collectAsState()
    // The sign-in saved a key and switched provider/model behind this page's back; re-read it.
    LaunchedEffect(signIn) { if (signIn is UiOpenRouterSignIn.Connected) cfg = backend.agent.config() }
    LaunchedEffect(freeAi.signedIn) { cfg = backend.agent.config() }
    val free = cfg.providers.firstOrNull { it.id == FREE_AI_PROVIDER }
    val select = { id: String -> backend.agent.selectProvider(id); cfg = backend.agent.config() }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ExpressiveScaffold(
                title = stringResource(Res.string.chat_providers_title),
                onBack = onClose,
            ) { padding ->
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(padding)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .widthIn(max = 640.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (free != null) {
                        FreeAiCard(backend, selected = cfg.selectedProvider == FREE_AI_PROVIDER) { select(FREE_AI_PROVIDER) }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        stringResource(Res.string.free_ai_section_byok),
                        color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    )
                    Text(stringResource(Res.string.chat_providers_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    cfg.providers.filter { it.id != FREE_AI_PROVIDER }.forEach { provider ->
                        ProviderCard(
                            provider = provider,
                            selected = provider.id == cfg.selectedProvider,
                            gatewayBaseUrl = cfg.gatewayBaseUrl,
                            gatewayModel = cfg.gatewayModel,
                            gatewayCaCert = cfg.gatewayCaCert,
                            onSelect = { select(provider.id) },
                            onSetKey = { backend.agent.setProviderKey(provider.id, it) },
                            onSetBackupKeys = { backend.agent.setProviderBackupKeys(provider.id, it) },
                            onSetGateway = { url, model, ca -> backend.agent.setGateway(url, model, ca) },
                            signIn = if (provider.id == OPENROUTER && backend.agent.openRouterSignInSupported()) {
                                { OpenRouterSignInBlock(backend, signIn) }
                            } else {
                                null
                            },
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/**
 * CodeAssist Free AI's card: what it is, then the sign-in (signed out) or the wallet and the rewarded-ad button
 * (signed in), and a button to switch to it while another provider is in use.
 */
@Composable
private fun FreeAiCard(backend: IdeBackend, selected: Boolean, onSelect: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Ca.radius.xl)
    val container = if (selected) scheme.secondaryContainer else scheme.surfaceContainerHigh
    val onContainer = if (selected) scheme.onSecondaryContainer else scheme.onSurface
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(container, shape)
            .border(1.dp, if (selected) scheme.primary else scheme.outlineVariant, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SparkleBadge(size = 36, animated = false)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.free_ai_card_title),
                    color = onContainer, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                )
                Text(stringResource(Res.string.free_ai_card_pitch), color = onContainer.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
            }
            if (selected) Chip(stringResource(Res.string.free_ai_in_use), fill = scheme.primary, textColor = scheme.onPrimary)
        }
        FreeAiBlock(backend)
        if (!selected) {
            PrimaryButton(stringResource(Res.string.free_ai_use_this), onSelect, Modifier.fillMaxWidth(), icon = CaIcons.sparkle)
        }
    }
}

@Composable
private fun ProviderCard(
    provider: UiAgentProvider,
    selected: Boolean,
    gatewayBaseUrl: String,
    gatewayModel: String,
    gatewayCaCert: String,
    onSelect: () -> Unit,
    onSetKey: (String) -> Unit,
    onSetBackupKeys: (List<String>) -> Unit,
    onSetGateway: (String, String, String) -> Unit,
    /** A browser sign-in offered above the key field (OpenRouter), or null. */
    signIn: (@Composable () -> Unit)? = null,
) {
    val isGateway = provider.id == "gateway"
    // CodeAssist Free AI runs on the store sign-in, not a key: its card shows the wallet instead of key fields.
    val keyless = provider.id == FREE_AI_PROVIDER
    // Keyed on the stored key too: a browser sign-in replaces it while the sheet is open.
    var key by remember(provider.id, provider.apiKey) { mutableStateOf(provider.apiKey) }
    var backups by remember(provider.id) { mutableStateOf(provider.backupKeys) }
    var baseUrl by remember(provider.id) { mutableStateOf(oneLine(gatewayBaseUrl)) }
    var model by remember(provider.id) { mutableStateOf(gatewayModel) }
    var caCert by remember(provider.id) { mutableStateOf(gatewayCaCert) }
    // A gateway is often a local server that takes no key, so its URL alone makes it usable.
    val hasKey = if (isGateway) baseUrl.isNotBlank() else key.isNotBlank() || backups.any { it.isNotBlank() }
    val shape = RoundedCornerShape(Ca.radius.md)
    val scheme = MaterialTheme.colorScheme
    // A selected card is a tonal container, so its text and its radio dot take the container's own content
    // colour. Leaving them on onSurface is the classic pairing slip: legible in one theme, muddy in the other.
    val container = if (selected) scheme.secondaryContainer else scheme.surfaceContainerHigh
    val onContainer = if (selected) scheme.onSecondaryContainer else scheme.onSurface
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(container, shape)
            .border(1.dp, if (selected) scheme.primary else scheme.outlineVariant, shape)
            .clickable(onClick = onSelect)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RadioDot(selected, onContainer)
            Text(
                provider.displayName,
                color = onContainer, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (hasKey) {
                // A solid tonal pair rather than a tinted wash of the status green: Material has no success
                // container, and tertiary is the role that reads as a positive state at full opacity.
                Chip(
                    stringResource(Res.string.chat_connected),
                    fill = scheme.tertiaryContainer,
                    textColor = scheme.onTertiaryContainer,
                )
            }
        }
        if (selected) {
            signIn?.invoke()
            if (!keyless) {
                SecretField(key, stringResource(if (isGateway) Res.string.chat_api_key_optional else Res.string.chat_api_key)) { key = it; onSetKey(it) }
                BackupKeys(backups) { backups = it; onSetBackupKeys(it) }
            }
            if (isGateway) {
                // A single-line field shows only the first line of a multi-line paste, so a pasted "URL + key" block
                // looked like a clean URL while the rest still went into requests. Flatten it so all of it is visible.
                PlainField(baseUrl, stringResource(Res.string.chat_base_url)) { baseUrl = oneLine(it); onSetGateway(baseUrl, model, caCert) }
                PlainField(model, stringResource(Res.string.chat_model)) { model = it; onSetGateway(baseUrl, it, caCert) }
                PlainField(caCert, stringResource(Res.string.chat_ca_cert)) { caCert = it; onSetGateway(baseUrl, model, it) }
                Text(stringResource(Res.string.chat_ca_cert_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Text(stringResource(Res.string.chat_gateway_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * "Sign in with OpenRouter": opens the browser on OpenRouter's consent page and reports where the sign-in stands.
 * The key it brings back lands in the field below, so pasting a key by hand stays possible.
 */
@Composable
private fun OpenRouterSignInBlock(backend: IdeBackend, state: UiOpenRouterSignIn) {
    val uriHandler = LocalUriHandler.current
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state == UiOpenRouterSignIn.Waiting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.chat_openrouter_waiting),
                    color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(Res.string.chat_cancel),
                    color = scheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(Ca.radius.pill))
                        .clickable { backend.agent.cancelOpenRouterSignIn() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        } else {
            PrimaryButton(
                stringResource(Res.string.chat_openrouter_sign_in),
                onClick = {
                    backend.agent.beginOpenRouterSignIn()?.let { url ->
                        runCatching { uriHandler.openUri(url) }.onFailure { backend.agent.cancelOpenRouterSignIn() }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                icon = CaIcons.key,
            )
        }
        val status = when (state) {
            is UiOpenRouterSignIn.Connected -> state.freeModel
                ?.let { stringResource(Res.string.chat_openrouter_connected_free, it) }
                ?: stringResource(Res.string.chat_openrouter_connected)
            is UiOpenRouterSignIn.Failed -> stringResource(Res.string.chat_openrouter_failed, state.message)
            else -> stringResource(Res.string.chat_openrouter_hint)
        }
        Text(
            status,
            color = if (state is UiOpenRouterSignIn.Failed) scheme.error else scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(Res.string.chat_openrouter_or_paste), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

private const val OPENROUTER = "openrouter"

/**
 * The keys tried after the main one, in order, when a key hits a rate or usage limit or is rejected. Each row is a
 * masked field with a remove button; there is no cap on how many.
 */
@Composable
private fun BackupKeys(keys: List<String>, onChange: (List<String>) -> Unit) {
    keys.forEachIndexed { i, value ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f)) {
                SecretField(value, stringResource(Res.string.chat_backup_key, i + 1)) { v ->
                    onChange(keys.mapIndexed { j, old -> if (j == i) v else old })
                }
            }
            IconButtonCa(
                CaIcons.close, stringResource(Res.string.chat_remove_key),
                { onChange(keys.filterIndexed { j, _ -> j != i }) },
                iconSize = 14, boxSize = 30,
            )
        }
    }
    Text(
        stringResource(Res.string.chat_add_backup_key),
        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(Ca.radius.pill))
            .clickable { onChange(keys + "") }
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
    if (keys.isNotEmpty()) {
        Text(stringResource(Res.string.chat_backup_keys_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

private val LINE_BREAKS = Regex("\\s*[\\r\\n]+\\s*")

private fun oneLine(text: String): String = text.replace(LINE_BREAKS, " ")

@Composable
private fun RadioDot(selected: Boolean, selectedTint: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier.size(18.dp).clip(CircleShape)
            .border(2.dp, if (selected) selectedTint else MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(9.dp).clip(CircleShape).background(selectedTint))
    }
}

@Composable
private fun SecretField(value: String, placeholder: String, onValueChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Ca.radius.control)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            stringResource(if (visible) Res.string.chat_hide else Res.string.chat_show),
            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(Ca.radius.pill))
                .clickable { visible = !visible }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun PlainField(value: String, placeholder: String, onValueChange: (String) -> Unit) {
    val shape = RoundedCornerShape(Ca.radius.control)
    Box(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
