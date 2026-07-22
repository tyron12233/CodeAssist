package dev.ide.ui

import androidx.compose.runtime.Composable
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.components.AnalyticsConsentSheet
import dev.ide.ui.components.BetaInfo
import dev.ide.ui.components.BuildNotificationGate
import dev.ide.ui.components.ErrorDialog
import dev.ide.ui.components.MigrationNotice
import dev.ide.ui.components.OnboardingSheet
import dev.ide.ui.components.AgentPermissionDialog
import dev.ide.ui.components.PermissionDialog
import dev.ide.ui.components.RunConflictDialog
import dev.ide.ui.screens.ImportErrorDialog

/**
 * The app-wide overlays layered over the current screen, split out of [CodeAssistApp] so its body stays
 * navigation + layout. Two groups: the one-at-a-time first-launch sheets (build-system migration notice, the
 * onboarding tour, analytics consent) shown only over the project picker ([onPicker]); and the always-on
 * dialogs (run-sandbox permission prompt, first-build notification gate, run-conflict confirmation, the
 * non-fatal error dialog, and the unrecognized-`.caproj` notice). Each is an already-encapsulated composable;
 * this only gates visibility and wires the callbacks.
 */
@Composable
internal fun AppOverlays(
    backend: IdeBackend,
    state: IdeUiState,
    fileActions: FileActions,
    /** True when the picker landing is showing — the only place the first-launch sheets appear. */
    onPicker: Boolean,
    showMigration: Boolean,
    onBackup: suspend () -> Unit,
    onDismissMigration: () -> Unit,
    showOnboarding: Boolean,
    onGetStarted: () -> Unit,
    onFinishOnboarding: () -> Unit,
    showAnalytics: Boolean,
    onAllowAnalytics: () -> Unit,
    onDeclineAnalytics: () -> Unit,
    importError: String?,
    onDismissImportError: () -> Unit,
) {
    // Upgrade notice first (the build-system migration warning), then the feature tour — both over the picker
    // only, one at a time.
    MigrationNotice(visible = showMigration && onPicker, onBackup = onBackup, onDismiss = onDismissMigration)
    OnboardingSheet(
        visible = showOnboarding && !showMigration && onPicker,
        // Final CTA: send the user straight into the Create-Project flow so the tour ends on a concrete action.
        onGetStarted = onGetStarted,
        onFinish = onFinishOnboarding,
    )
    // Opt-in analytics consent — last of the first-launch sheets, after onboarding/migration.
    AnalyticsConsentSheet(
        visible = showAnalytics && !showOnboarding && !showMigration && onPicker,
        onAllow = onAllowAnalytics,
        onDecline = onDeclineAnalytics,
        onLearnMore = if (fileActions.canOpenUrl) ({ fileActions.openUrl(BetaInfo.PRIVACY_URL) }) else null,
    )
    // The run sandbox's permission prompt — overlays everything while a guarded program is blocked.
    PermissionDialog(backend)
    // The AI agent's write-permission prompt (ASK_EACH) — overlays while a mutating tool call awaits approval.
    AgentPermissionDialog(backend)
    // First-build notification-permission gate — asks for the permission the isolated build process needs, and
    // falls back to in-process builds (with an explanation) if declined. No-op after the one-time prompt.
    BuildNotificationGate(state)
    // "Already running" confirmation — raised when a new Run is requested while a build/program is in flight.
    RunConflictDialog(state)
    // IntelliJ-style non-fatal error dialog — overlays everything when the engine reports an unexpected error.
    ErrorDialog(backend)
    // "Unrecognized file" notice when a picked/opened file wasn't a readable .caproj package.
    ImportErrorDialog(importError, onDismissImportError)
}
