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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
                .background(Ca.colors.glassThick)
                .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.xl))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CaIcons.key, null, Modifier.size(18.dp), tint = Ca.colors.accent)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(Res.string.chat_providers_title),
                    color = Ca.colors.textPrimary, style = Ca.type.title3, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButtonCa(CaIcons.close, stringResource(Res.string.chat_close), onClose, iconSize = 16, boxSize = 30)
            }
            Text(stringResource(Res.string.chat_providers_subtitle), color = Ca.colors.textTertiary, style = Ca.type.caption)
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
                        onSelect = { backend.agent.selectProvider(provider.id); cfg = backend.agent.config() },
                        onSetKey = { backend.agent.setProviderKey(provider.id, it) },
                        onSetGateway = { url, model -> backend.agent.setGateway(url, model) },
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
    onSelect: () -> Unit,
    onSetKey: (String) -> Unit,
    onSetGateway: (String, String) -> Unit,
) {
    val isGateway = provider.id == "gateway"
    var key by remember(provider.id) { mutableStateOf(provider.apiKey) }
    var baseUrl by remember(provider.id) { mutableStateOf(gatewayBaseUrl) }
    var model by remember(provider.id) { mutableStateOf(gatewayModel) }
    val hasKey = key.isNotBlank() && (!isGateway || baseUrl.isNotBlank())
    val shape = RoundedCornerShape(Ca.radius.md)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(if (selected) Ca.colors.accentSoft else Ca.colors.surface2, shape)
            .border(1.dp, if (selected) Ca.colors.accent else Ca.colors.hairline, shape)
            .clickable(onClick = onSelect)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RadioDot(selected)
            Text(
                provider.displayName,
                color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (hasKey) {
                Chip(
                    stringResource(Res.string.chat_connected),
                    fill = Ca.colors.success.copy(alpha = 0.16f),
                    textColor = Ca.colors.success,
                )
            }
        }
        if (selected) {
            SecretField(key, stringResource(Res.string.chat_api_key)) { key = it; onSetKey(it) }
            if (isGateway) {
                PlainField(baseUrl, stringResource(Res.string.chat_base_url)) { baseUrl = it; onSetGateway(it, model) }
                PlainField(model, stringResource(Res.string.chat_model)) { model = it; onSetGateway(baseUrl, it) }
                Text(stringResource(Res.string.chat_gateway_hint), color = Ca.colors.textTertiary, style = Ca.type.caption2)
            }
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    Box(
        Modifier.size(18.dp).clip(CircleShape)
            .border(2.dp, if (selected) Ca.colors.accent else Ca.colors.separator, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(9.dp).clip(CircleShape).background(Ca.colors.accent))
    }
}

@Composable
private fun SecretField(value: String, placeholder: String, onValueChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Ca.radius.control)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Ca.colors.surface3)
            .border(1.dp, Ca.colors.hairline, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = Ca.colors.textTertiary, style = Ca.type.footnote)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Ca.type.footnote.copy(color = Ca.colors.textPrimary),
                cursorBrush = SolidColor(Ca.colors.accent),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            stringResource(if (visible) Res.string.chat_hide else Res.string.chat_show),
            color = Ca.colors.accent, style = Ca.type.caption, fontWeight = FontWeight.SemiBold,
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
        Modifier.fillMaxWidth().clip(shape).background(Ca.colors.surface3)
            .border(1.dp, Ca.colors.hairline, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = Ca.colors.textTertiary, style = Ca.type.footnote)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = Ca.type.footnote.copy(color = Ca.colors.textPrimary),
            cursorBrush = SolidColor(Ca.colors.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
