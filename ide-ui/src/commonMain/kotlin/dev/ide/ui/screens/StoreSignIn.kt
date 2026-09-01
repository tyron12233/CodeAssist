package dev.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiSignInPhase
import dev.ide.ui.components.PrimaryActionButton
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.store_signin_completing
import dev.ide.ui.generated.resources.store_signin_dismiss
import dev.ide.ui.generated.resources.store_signin_failed
import dev.ide.ui.generated.resources.store_signin_github
import dev.ide.ui.generated.resources.store_signin_reopen
import dev.ide.ui.generated.resources.store_signin_sign_out
import dev.ide.ui.generated.resources.store_signin_signed_in_as
import dev.ide.ui.generated.resources.store_signin_submit_soon
import dev.ide.ui.generated.resources.store_signin_title
import dev.ide.ui.generated.resources.store_signin_unavailable_body
import dev.ide.ui.generated.resources.store_signin_unavailable_title
import dev.ide.ui.generated.resources.store_signin_waiting_body
import dev.ide.ui.generated.resources.store_signin_waiting_title
import dev.ide.ui.generated.resources.store_signin_why
import dev.ide.ui.generated.resources.submit_send
import org.jetbrains.compose.resources.stringResource

/** The provider wire name; the store deliberately supports GitHub first, Google later. */
private const val GITHUB = "github"

/**
 * The store's sign-in sheet.
 *
 * Reached from Publish, never from launch: the store is readable and installable anonymously, and asking
 * for an identity before there is a reason to want one is how a browse turns into a bounce.
 *
 * The sheet reads the engine's auth state rather than holding its own. It has to: the redirect comes back
 * through a deep link into the activity, which can happen while this sheet is gone, so the state cannot
 * live in composition.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun StoreSignInSheet(
    backend: IdeBackend,
    onDismiss: () -> Unit,
    /** Opens the publish flow. Null when this build cannot submit, which hides the action. */
    onSubmitProject: (() -> Unit)? = null,
    /** Opens a URL in a browser or custom tab. Null when the host cannot, which hides sign-in entirely. */
    onOpenUrl: ((String) -> Unit)?,
) {
    val state by backend.store.authState().collectAsState()
    val providers = backend.store.authProviders()
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Sign-in needs both a configured provider and a host that can open a browser. Without either
            // the honest thing is to say so, not to show a button that cannot work.
            if (providers.isEmpty() || onOpenUrl == null) {
                Heading(stringResource(Res.string.store_signin_unavailable_title))
                Spacer(Modifier.height(10.dp))
                Body(stringResource(Res.string.store_signin_unavailable_body))
                Spacer(Modifier.height(20.dp))
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.store_signin_dismiss)) }
                return@Column
            }

            when (state.phase) {
                UiSignInPhase.SignedOut, UiSignInPhase.Failed -> {
                    Heading(stringResource(Res.string.store_signin_title))
                    Spacer(Modifier.height(10.dp))
                    Body(stringResource(Res.string.store_signin_why))
                    if (state.phase == UiSignInPhase.Failed) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            stringResource(Res.string.store_signin_failed),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        state.message?.let {
                            Spacer(Modifier.height(4.dp))
                            // The backend's own words: it knows why, and paraphrasing loses the reason.
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    if (GITHUB in providers) {
                        PrimaryActionButton(
                            label = stringResource(Res.string.store_signin_github),
                            glyph = CaSymbols.forkRight,
                            onClick = { backend.store.beginSignIn(GITHUB)?.let(onOpenUrl) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.store_signin_dismiss)) }
                }

                UiSignInPhase.AwaitingBrowser -> {
                    Heading(stringResource(Res.string.store_signin_waiting_title))
                    Spacer(Modifier.height(10.dp))
                    Body(stringResource(Res.string.store_signin_waiting_body))
                    Spacer(Modifier.height(22.dp))
                    // No progress bar here: the app cannot see what the browser is doing, and a moving bar
                    // would be claiming progress it does not have.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { backend.store.beginSignIn(GITHUB)?.let(onOpenUrl) }) {
                            Text(stringResource(Res.string.store_signin_reopen))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(Res.string.store_signin_dismiss))
                        }
                    }
                }

                UiSignInPhase.Completing -> {
                    Heading(stringResource(Res.string.store_signin_completing))
                    Spacer(Modifier.height(16.dp))
                    // Here a bar IS honest: the token exchange is ours and it is running.
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                UiSignInPhase.SignedIn -> {
                    val label = state.account?.label.orEmpty()
                    Heading(stringResource(Res.string.store_signin_signed_in_as, label))
                    Spacer(Modifier.height(10.dp))
                    Body(stringResource(Res.string.store_signin_submit_soon))
                    Spacer(Modifier.height(20.dp))
                    if (onSubmitProject != null) {
                        PrimaryActionButton(
                            label = stringResource(Res.string.submit_send),
                            glyph = CaSymbols.upload,
                            onClick = { onDismiss(); onSubmitProject() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { backend.store.signOut() }) {
                            Text(stringResource(Res.string.store_signin_sign_out))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(Res.string.store_signin_dismiss))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
