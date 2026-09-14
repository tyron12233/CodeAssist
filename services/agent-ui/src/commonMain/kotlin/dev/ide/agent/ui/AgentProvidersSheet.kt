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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAgentProvider
import dev.ide.agent.ui.generated.resources.Res
import dev.ide.agent.ui.generated.resources.chat_api_key
import dev.ide.agent.ui.generated.resources.chat_base_url
import dev.ide.agent.ui.generated.resources.chat_ca_cert
import dev.ide.agent.ui.generated.resources.chat_ca_cert_hint
import dev.ide.agent.ui.generated.resources.chat_close
import dev.ide.agent.ui.generated.resources.chat_connected
import dev.ide.agent.ui.generated.resources.chat_done
import dev.ide.agent.ui.generated.resources.chat_gateway_hint
import dev.ide.agent.ui.generated.resources.chat_hide
import dev.ide.agent.ui.generated.resources.chat_model
import dev.ide.agent.ui.generated.resources.chat_providers_subtitle
import dev.ide.agent.ui.generated.resources.chat_providers_title
import dev.ide.agent.ui.generated.resources.chat_show
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import org.jetbrains.compose.resources.stringResource

/**
 * The AI provider / key manager. A glass dialog listing each provider (plus a first-class "Custom gateway"
 * for OpenAI-compatible endpoints) as a selectable card; the active card expands to a masked, show/hide API
 * key field (and, for the gateway, a base URL + model). Everything auto-saves through [dev.ide.ui.backend.AgentService].
 * Reached from the chat header's key button and the empty-state call to action. See docs/agentic-coding.md.
 */
@Composable
internal fun AgentProvidersSheet(backend: IdeBackend, onClose: () -> Unit) {
    var cfg by remember { mutableStateOf(backend.agent.config()) }
    CenteredDialog(visible = true, onDismiss = onClose) {
        Column(
            Modifier.widthIn(max = 460.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(Ca.radius.xl))
                .background(Ide.colors.glassThick)
                .border(1.dp, Ide.colors.glassEdge, RoundedCornerShape(Ca.radius.xl))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CaIcons.key, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(Res.string.chat_providers_title),
                    color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButtonCa(CaIcons.close, stringResource(Res.string.chat_close), onClose, iconSize = 16, boxSize = 30)
            }
            Text(stringResource(Res.string.chat_providers_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                cfg.providers.forEach { provider ->
                    ProviderCard(
                        provider = provider,
                        selected = provider.id == cfg.selectedProvider,
                        gatewayBaseUrl = cfg.gatewayBaseUrl,
                        gatewayModel = cfg.gatewayModel,
                        gatewayCaCert = cfg.gatewayCaCert,
                        onSelect = { backend.agent.selectProvider(provider.id); cfg = backend.agent.config() },
                        onSetKey = { backend.agent.setProviderKey(provider.id, it) },
                        onSetGateway = { url, model, ca -> backend.agent.setGateway(url, model, ca) },
                    )
                }
            }
            PrimaryButton(stringResource(Res.string.chat_done), onClose, Modifier.fillMaxWidth())
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
    onSetGateway: (String, String, String) -> Unit,
) {
    val isGateway = provider.id == "gateway"
    var key by remember(provider.id) { mutableStateOf(provider.apiKey) }
    var baseUrl by remember(provider.id) { mutableStateOf(gatewayBaseUrl) }
    var model by remember(provider.id) { mutableStateOf(gatewayModel) }
    var caCert by remember(provider.id) { mutableStateOf(gatewayCaCert) }
    val hasKey = key.isNotBlank() && (!isGateway || baseUrl.isNotBlank())
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
                Chip(
                    stringResource(Res.string.chat_connected),
                    fill = Ide.colors.success.copy(alpha = 0.16f),
                    textColor = Ide.colors.success,
                )
            }
        }
        if (selected) {
            SecretField(key, stringResource(Res.string.chat_api_key)) { key = it; onSetKey(it) }
            if (isGateway) {
                PlainField(baseUrl, stringResource(Res.string.chat_base_url)) { baseUrl = it; onSetGateway(it, model, caCert) }
                PlainField(model, stringResource(Res.string.chat_model)) { model = it; onSetGateway(baseUrl, it, caCert) }
                PlainField(caCert, stringResource(Res.string.chat_ca_cert)) { caCert = it; onSetGateway(baseUrl, model, it) }
                Text(stringResource(Res.string.chat_ca_cert_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Text(stringResource(Res.string.chat_gateway_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

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
