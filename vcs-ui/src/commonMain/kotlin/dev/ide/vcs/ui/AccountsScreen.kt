package dev.ide.vcs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.UiVcsAccount
import dev.ide.ui.backend.UiVcsResult
import dev.ide.ui.backend.UiVcsSignIn
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.ext.ScreenContext
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.vcs.ui.generated.resources.Res
import dev.ide.vcs.ui.generated.resources.vcs_sign_out_of
import dev.ide.vcs.ui.generated.resources.vcs_accounts
import dev.ide.vcs.ui.generated.resources.vcs_active_account
import dev.ide.vcs.ui.generated.resources.vcs_cancel
import dev.ide.vcs.ui.generated.resources.vcs_copied
import dev.ide.vcs.ui.generated.resources.vcs_copy
import dev.ide.vcs.ui.generated.resources.vcs_device_code_body
import dev.ide.vcs.ui.generated.resources.vcs_device_code_title
import dev.ide.vcs.ui.generated.resources.vcs_email
import dev.ide.vcs.ui.generated.resources.vcs_host
import dev.ide.vcs.ui.generated.resources.vcs_host_hint
import dev.ide.vcs.ui.generated.resources.vcs_identity_missing
import dev.ide.vcs.ui.generated.resources.vcs_name
import dev.ide.vcs.ui.generated.resources.vcs_open_in_browser
import dev.ide.vcs.ui.generated.resources.vcs_other_servers
import dev.ide.vcs.ui.generated.resources.vcs_other_servers_body
import dev.ide.vcs.ui.generated.resources.vcs_password
import dev.ide.vcs.ui.generated.resources.vcs_remove
import dev.ide.vcs.ui.generated.resources.vcs_save
import dev.ide.vcs.ui.generated.resources.vcs_saved_hosts
import dev.ide.vcs.ui.generated.resources.vcs_set_identity
import dev.ide.vcs.ui.generated.resources.vcs_sign_in
import dev.ide.vcs.ui.generated.resources.vcs_sign_in_github
import dev.ide.vcs.ui.generated.resources.vcs_sign_in_intro
import dev.ide.vcs.ui.generated.resources.vcs_sign_in_token
import dev.ide.vcs.ui.generated.resources.vcs_sign_out
import dev.ide.vcs.ui.generated.resources.vcs_signing_in
import dev.ide.vcs.ui.generated.resources.vcs_token_help
import dev.ide.vcs.ui.generated.resources.vcs_token_hint
import dev.ide.vcs.ui.generated.resources.vcs_use_account
import dev.ide.vcs.ui.generated.resources.vcs_username
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Sign-in and the commit identity: the signed-in GitHub accounts, the browser device flow (or an access
 * token when this build carries no OAuth client id), saved credentials for other Git servers, and the name
 * and email commits are recorded under.
 */
@Composable
internal fun AccountsScreen(ctx: ScreenContext) {
    val vcs = ctx.backend.vcs
    val accounts by vcs.accounts.collectAsState()
    val signIn by vcs.signIn.collectAsState()
    val scope = rememberCoroutineScope()
    val feedback = rememberVcsFeedback()

    var token by remember { mutableStateOf("") }
    var identityName by remember { mutableStateOf("") }
    var identityEmail by remember { mutableStateOf("") }
    var hosts by remember { mutableStateOf(emptyList<String>()) }
    var hostName by remember { mutableStateOf("") }
    var hostUser by remember { mutableStateOf("") }
    var hostPassword by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        val identity = vcs.identity()
        identityName = identity.name
        identityEmail = identity.email
        hosts = vcs.credentialHosts()
    }
    // An abandoned screen must not leave the poll running against a code the user will never enter.
    DisposableEffect(Unit) { onDispose { vcs.cancelSignIn() } }

    fun perform(block: suspend () -> UiVcsResult) {
        scope.launch {
            val result = block()
            if (result.message.isNotBlank()) feedback.show(result.message, isError = !result.ok)
            reload++
        }
    }

    ExpressiveScaffold(title = stringResource(Res.string.vcs_accounts), onBack = ctx::back, large = false) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FeedbackStrip(feedback, Modifier.padding(horizontal = 0.dp))

            accounts.forEach { account ->
                AccountCard(
                    account = account,
                    onUse = { perform { vcs.setActiveAccount(account.id) } },
                    onSignOut = { perform { vcs.signOut(account.id) } },
                )
            }

            SignInCard(
                state = signIn,
                canUseBrowser = vcs.deviceAuthSupported(),
                signedIn = accounts.isNotEmpty(),
                onStart = { scope.launch { vcs.startSignIn() } },
                onCancel = { vcs.cancelSignIn() },
                onOpenUrl = { url -> if (ctx.fileActions.canOpenUrl) ctx.fileActions.openUrl(url) },
                canOpenUrl = ctx.fileActions.canOpenUrl,
                token = token,
                onToken = { token = it },
                onSubmitToken = {
                    val entered = token
                    token = ""
                    perform { vcs.signInWithToken(entered) }
                },
            )

            IdentityCard(
                name = identityName,
                email = identityEmail,
                onName = { identityName = it },
                onEmail = { identityEmail = it },
                onSave = { perform { vcs.setIdentity(identityName, identityEmail) } },
            )

            HostCredentialsCard(
                hosts = hosts,
                host = hostName,
                username = hostUser,
                password = hostPassword,
                onHost = { hostName = it },
                onUsername = { hostUser = it },
                onPassword = { hostPassword = it },
                onSave = {
                    val h = hostName
                    val u = hostUser
                    val p = hostPassword
                    hostPassword = ""
                    perform { vcs.saveHostCredentials(h, u, p) }
                },
                onRemove = { host -> perform { vcs.clearHostCredentials(host) } },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AccountCard(account: UiVcsAccount, onUse: () -> Unit, onSignOut: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainerHigh, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).background(scheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                account.login.firstOrNull()?.uppercase() ?: "?",
                color = scheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                account.name.ifBlank { account.login },
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${account.login} · ${account.host}",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (account.active) {
                Text(
                    stringResource(Res.string.vcs_active_account),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                )
            } else {
                Text(
                    stringResource(Res.string.vcs_use_account),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onUse)
                        .padding(vertical = 2.dp),
                )
            }
        }
        VcsIconButton(
            CaIcons.close,
            stringResource(Res.string.vcs_sign_out_of, account.login),
            onSignOut,
            iconSize = 16,
            boxSize = 32,
        )
    }
}

@Composable
private fun SignInCard(
    state: UiVcsSignIn,
    canUseBrowser: Boolean,
    signedIn: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onOpenUrl: (String) -> Unit,
    canOpenUrl: Boolean,
    token: String,
    onToken: (String) -> Unit,
    onSubmitToken: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainerLow, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(CaIcons.account, null, Modifier.size(18.dp), tint = scheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(Res.string.vcs_sign_in_github),
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (!signedIn) {
            Text(
                stringResource(Res.string.vcs_sign_in_intro),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }

        when (state) {
            is UiVcsSignIn.AwaitingUser -> DeviceCodePanel(state, onCancel, onOpenUrl, canOpenUrl)

            UiVcsSignIn.Starting -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(Res.string.vcs_signing_in),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }

            is UiVcsSignIn.Failed -> Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
            )

            else -> Unit
        }

        if (state !is UiVcsSignIn.AwaitingUser && canUseBrowser) {
            PrimaryButton(stringResource(Res.string.vcs_sign_in_github), onStart, icon = CaIcons.account)
        }

        Text(
            stringResource(Res.string.vcs_sign_in_token),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        VcsField(
            value = token,
            onValueChange = onToken,
            placeholder = stringResource(Res.string.vcs_token_hint),
            leading = CaIcons.key,
        )
        Text(
            stringResource(Res.string.vcs_token_help),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.outline,
        )
        PrimaryButton(stringResource(Res.string.vcs_sign_in), onSubmitToken)
    }
}

@Composable
private fun DeviceCodePanel(
    state: UiVcsSignIn.AwaitingUser,
    onCancel: () -> Unit,
    onOpenUrl: (String) -> Unit,
    canOpenUrl: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(scheme.secondaryContainer, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(Res.string.vcs_device_code_title),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSecondaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.userCode,
                style = Ca.type.code.copy(fontSize = 22.sp, letterSpacing = 3.sp),
                color = scheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(if (copied) Res.string.vcs_copied else Res.string.vcs_copy),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSecondaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(state.userCode))
                        copied = true
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Text(
            stringResource(Res.string.vcs_device_code_body, state.verificationUri),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSecondaryContainer,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                stringResource(Res.string.vcs_signing_in),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSecondaryContainer,
            )
            Spacer(Modifier.weight(1f))
            if (canOpenUrl) {
                Text(
                    stringResource(Res.string.vcs_open_in_browser),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSecondaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpenUrl(state.verificationUri) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Text(
                stringResource(Res.string.vcs_cancel),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSecondaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun IdentityCard(
    name: String,
    email: String,
    onName: (String) -> Unit,
    onEmail: (String) -> Unit,
    onSave: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainerLow, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(Res.string.vcs_set_identity),
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        if (name.isBlank() || email.isBlank()) {
            Text(
                stringResource(Res.string.vcs_identity_missing),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        VcsField(name, onName, stringResource(Res.string.vcs_name), leading = CaIcons.account)
        VcsField(email, onEmail, stringResource(Res.string.vcs_email), leading = CaIcons.docText)
        PrimaryButton(stringResource(Res.string.vcs_save), onSave)
    }
}

@Composable
private fun HostCredentialsCard(
    hosts: List<String>,
    host: String,
    username: String,
    password: String,
    onHost: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSave: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainerLow, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(Res.string.vcs_other_servers),
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(Res.string.vcs_other_servers_body),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        if (hosts.isNotEmpty()) {
            Text(
                stringResource(Res.string.vcs_saved_hosts),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.outline,
            )
            hosts.forEach { saved ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        saved,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(Res.string.vcs_remove),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onRemove(saved) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        VcsField(host, onHost, stringResource(Res.string.vcs_host_hint), leading = CaIcons.share)
        VcsField(username, onUsername, stringResource(Res.string.vcs_username), leading = CaIcons.account)
        VcsField(password, onPassword, stringResource(Res.string.vcs_password), leading = CaIcons.key)
        PrimaryButton(stringResource(Res.string.vcs_save), onSave)
    }
}
