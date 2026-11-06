package dev.ide.agent.ui

import dev.ide.ui.components.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ide.agent.ui.generated.resources.Res
import dev.ide.agent.ui.generated.resources.free_ai_ad_failed
import dev.ide.agent.ui.generated.resources.free_ai_ad_loading
import dev.ide.agent.ui.generated.resources.free_ai_ad_pending
import dev.ide.agent.ui.generated.resources.free_ai_ads_done
import dev.ide.agent.ui.generated.resources.free_ai_ads_left
import dev.ide.agent.ui.generated.resources.free_ai_breakdown
import dev.ide.agent.ui.generated.resources.free_ai_cost_hint
import dev.ide.agent.ui.generated.resources.free_ai_paused
import dev.ide.agent.ui.generated.resources.free_ai_pitch
import dev.ide.agent.ui.generated.resources.free_ai_points
import dev.ide.agent.ui.generated.resources.free_ai_sign_in_github
import dev.ide.agent.ui.generated.resources.free_ai_sign_in_google
import dev.ide.agent.ui.generated.resources.free_ai_signing_in
import dev.ide.agent.ui.generated.resources.free_ai_strip_points
import dev.ide.agent.ui.generated.resources.free_ai_strip_sign_in
import dev.ide.agent.ui.generated.resources.free_ai_watch_ad
import dev.ide.agent.ui.generated.resources.free_ai_prompt_title
import dev.ide.agent.ui.generated.resources.free_ai_use_own_key
import dev.ide.agent.ui.generated.resources.chat_close
import dev.ide.ui.ads.LocalAds
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiFreeAiState
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The provider id of CodeAssist Free AI (agent-impl's `CodeAssistFreeProvider.ID`). */
internal const val FREE_AI_PROVIDER = "codeassist"

/** Where the rewarded-ad button stands, between the tap and the server's credit. */
private enum class AdPhase { IDLE, LOADING, PENDING, FAILED }

/**
 * Keeps [UiFreeAiState] fresh while a free-tier surface is on screen: on open, and whenever the store's sign-in
 * changes (the sign-in itself happens in the browser, and comes back through the store's deep link).
 */
@Composable
private fun rememberFreeAi(backend: IdeBackend): UiFreeAiState {
    val state by backend.agent.freeAi.collectAsState()
    val auth by backend.store.authState().collectAsState()
    LaunchedEffect(auth.signedIn) { backend.agent.refreshFreeAi() }
    return state
}

/**
 * The free tier's card body in the provider sheet, in place of the key fields: the sign-in when signed out,
 * the wallet and the rewarded-ad button when signed in.
 */
@Composable
internal fun FreeAiBlock(backend: IdeBackend) {
    val state = rememberFreeAi(backend)
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.signedIn) {
            SignInButtons(backend)
            return@Column
        }
        if (!state.enabled) {
            Text(stringResource(Res.string.free_ai_paused), color = scheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(CaIcons.sparkle, null, Modifier.size(18.dp), tint = scheme.primary)
            Text(
                stringResource(Res.string.free_ai_points, state.points),
                color = scheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            stringResource(Res.string.free_ai_breakdown, state.freePoints, state.earnedPoints, state.dailyFreePoints),
            color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
        )
        WatchAdButton(backend, state, compact = false)
        Text(stringResource(Res.string.free_ai_cost_hint), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        state.error?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.labelSmall) }
    }
}

/**
 * A one-line strip above the composer while the free tier is the selected provider: the balance, and the
 * rewarded-ad button when one can be shown. Tapping the text opens the provider sheet.
 */
@Composable
internal fun FreeAiStrip(backend: IdeBackend, onManage: () -> Unit) {
    val state = rememberFreeAi(backend)
    if (!state.available) return
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(Ca.radius.pill)).clickable(onClick = onManage)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(CaIcons.sparkle, null, Modifier.size(14.dp), tint = scheme.primary)
            Text(
                if (state.signedIn) stringResource(Res.string.free_ai_strip_points, state.points)
                else stringResource(Res.string.free_ai_strip_sign_in),
                color = if (state.signedIn && state.points < LOW_POINTS) scheme.error else scheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (state.signedIn) WatchAdButton(backend, state, compact = true)
    }
}

@Composable
internal fun SignInButtons(backend: IdeBackend, centered: Boolean = false) {
    val uriHandler = LocalUriHandler.current
    val scheme = MaterialTheme.colorScheme
    var waiting by remember { mutableStateOf(false) }
    Text(
        stringResource(Res.string.free_ai_pitch), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
        textAlign = if (centered) TextAlign.Center else null,
    )
    val providers = backend.store.authProviders().ifEmpty { listOf("github") }
    providers.forEach { provider ->
        val label = when (provider) {
            "google" -> stringResource(Res.string.free_ai_sign_in_google)
            else -> stringResource(Res.string.free_ai_sign_in_github)
        }
        PrimaryButton(
            label,
            onClick = {
                backend.store.beginSignIn(provider)?.let { url ->
                    waiting = runCatching { uriHandler.openUri(url) }.isSuccess
                }
            },
            modifier = Modifier.fillMaxWidth(),
            icon = CaIcons.account,
        )
    }
    if (waiting) {
        Text(
            stringResource(Res.string.free_ai_signing_in), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
            textAlign = if (centered) TextAlign.Center else null,
        )
    }
}

/**
 * "Watch an ad: +N points". The points are credited by the server when Google's signed reward callback arrives,
 * not by this button: after the ad closes it re-reads the wallet a few times while that callback lands.
 * Hidden where the host has no rewarded ads (desktop, or a release built without a rewarded unit).
 */
@Composable
internal fun WatchAdButton(backend: IdeBackend, state: UiFreeAiState, compact: Boolean) {
    val host = LocalAds.current?.host ?: return
    if (!host.rewardedAvailable || !state.enabled) return
    val userId = state.userId ?: return
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(AdPhase.IDLE) }
    val noneLeft = state.adsLeftToday <= 0

    fun watch() {
        if (phase == AdPhase.LOADING || noneLeft) return
        phase = AdPhase.LOADING
        host.showRewarded(userId) { earned ->
            if (!earned) {
                phase = AdPhase.FAILED
                return@showRewarded
            }
            phase = AdPhase.PENDING
            val before = state.points
            scope.launch {
                // Google's callback usually reaches the server within seconds; stop as soon as it shows.
                for (wait in longArrayOf(1_500, 3_000, 5_000, 10_000)) {
                    delay(wait)
                    backend.agent.refreshFreeAi()
                    delay(800)
                    if (backend.agent.freeAi.value.points > before) break
                }
                phase = AdPhase.IDLE
            }
        }
    }

    if (compact) {
        if (noneLeft) return
        val label = when (phase) {
            AdPhase.LOADING -> stringResource(Res.string.free_ai_ad_loading)
            else -> "+${state.adPoints}"
        }
        Row(
            Modifier.clip(RoundedCornerShape(Ca.radius.pill))
                .background(scheme.primaryContainer)
                .clickable(onClick = ::watch)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(CaIcons.play, null, Modifier.size(12.dp), tint = scheme.onPrimaryContainer)
            Text(label, color = scheme.onPrimaryContainer, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
        return
    }

    if (noneLeft) {
        Text(stringResource(Res.string.free_ai_ads_done), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        return
    }
    PrimaryButton(
        if (phase == AdPhase.LOADING) stringResource(Res.string.free_ai_ad_loading)
        else stringResource(Res.string.free_ai_watch_ad, state.adPoints),
        onClick = ::watch,
        modifier = Modifier.fillMaxWidth(),
        icon = CaIcons.play,
    )
    val status = when (phase) {
        AdPhase.PENDING -> stringResource(Res.string.free_ai_ad_pending)
        AdPhase.FAILED -> stringResource(Res.string.free_ai_ad_failed)
        else -> stringResource(Res.string.free_ai_ads_left, state.adsLeftToday, state.adDailyCap)
    }
    Text(
        status,
        color = if (phase == AdPhase.FAILED) scheme.error else scheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
    )
}

/** Below this the strip turns the balance red: about one more Haiku agent step. */
private const val LOW_POINTS = 15

/**
 * The chat's empty state while CodeAssist Free AI is selected and nobody is signed in: what the free tier is,
 * the sign-in buttons, and a way out to bring-your-own-key for someone who would rather not sign in.
 */
@Composable
internal fun FreeAiSignInPrompt(backend: IdeBackend, onUseOwnKey: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.widthIn(max = 420.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(Res.string.free_ai_prompt_title),
            color = scheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        SignInButtons(backend, centered = true)
        Text(
            stringResource(Res.string.free_ai_use_own_key),
            color = scheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(Ca.radius.pill)).clickable(onClick = onUseOwnKey)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

/**
 * Shown when someone presses send on the free tier while signed out. Their message stays in the composer; the
 * dialog closes by itself once the sign-in lands, so the next tap on send goes through.
 */
@Composable
internal fun FreeAiSignInDialog(backend: IdeBackend, onUseOwnKey: () -> Unit, onDismiss: () -> Unit) {
    val state by backend.agent.freeAi.collectAsState()
    val auth by backend.store.authState().collectAsState()
    LaunchedEffect(auth.signedIn) { backend.agent.refreshFreeAi() }
    LaunchedEffect(state.signedIn) { if (state.signedIn) onDismiss() }
    CenteredDialog(visible = true, onDismiss = onDismiss) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(Ca.radius.xl))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.xl))
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(CaIcons.sparkle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                IconButtonCa(CaIcons.close, stringResource(Res.string.chat_close), onDismiss, iconSize = 16, boxSize = 30)
            }
            Spacer(Modifier.height(4.dp))
            FreeAiSignInPrompt(backend, onUseOwnKey = { onDismiss(); onUseOwnKey() })
        }
    }
}
