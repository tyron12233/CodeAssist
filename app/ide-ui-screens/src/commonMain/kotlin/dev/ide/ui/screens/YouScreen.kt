package dev.ide.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import dev.ide.ui.components.SupportingOnContainer
import dev.ide.ui.components.dashedBorder
import dev.ide.ui.theme.CaShapes
import org.jetbrains.compose.resources.pluralStringResource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiMyProfile
import dev.ide.ui.backend.UiPublishedItem
import dev.ide.ui.backend.UiStoreSubmission
import dev.ide.ui.backend.UiSubmissionStatus
import dev.ide.ui.components.Eyebrow
import dev.ide.ui.components.LinearProgressIndicator
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.learn_section_title
import dev.ide.ui.generated.resources.you_delete_account_deleting
import dev.ide.ui.generated.resources.you_delete_account_body
import dev.ide.ui.generated.resources.you_delete_account_title
import dev.ide.ui.generated.resources.you_delete_account
import dev.ide.ui.generated.resources.*
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.itemsKeyed
import dev.ide.ui.joinMeta
import dev.ide.ui.theme.Symbol
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The signed-in account's own page: who the store thinks you are, what you have sent it, and what is live.
 *
 * It exists because none of that was reachable. The publisher row carried a handle and a display name that
 * only the submit flow ever wrote (`user-` and eight characters of a uuid), no screen read it back, and
 * `mySubmissions` had no caller at all, so the one place a review decision could be seen in the app was a
 * notification that was itself not being delivered.
 *
 * Reading the submissions is therefore not only for display: the engine compares each one against the
 * state it last saw and raises a notification when it changed, so opening this screen is also how a
 * decision made while the app was closed gets noticed on a device that missed the push.
 */
@Composable
fun YouScreen(
    backend: IdeBackend,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens a published listing by its slug. */
    onOpenItem: (String) -> Unit = {},
    /** Opens the public profile page at a handle, which is what everyone else sees. */
    onOpenPublicProfile: (String) -> Unit = {},
    /** Starts the publish flow. Null in a build that cannot publish, which hides the button. */
    onPublish: (() -> Unit)? = null,
    /**
     * Starts the publish flow as a new version of an existing listing, by slug.
     *
     * The action a rejection actually calls for. Without it, answering one meant going to Publish and
     * picking the listing out of a list again, having just been looking at it.
     */
    onUpdateListing: ((String) -> Unit)? = null,
    /** Offers sign-in, for the case where the session ended while this screen was open. */
    onSignIn: () -> Unit = {},
    /**
     * Opens the review queue. Null in a build with no moderation transport.
     *
     * Drawn only when the profile says this account moderates, which almost none do. It is not what
     * permits anything: the queue and every decision on it are re-checked against `store_admins` by the
     * backend, so a build that passed this unconditionally would gain a screen full of refusals.
     */
    onModerate: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val signedIn = backend.store.authState().collectAsState().value.signedIn
    var epoch by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var profile by remember { mutableStateOf<UiMyProfile?>(null) }
    var submissions by remember { mutableStateOf<List<UiStoreSubmission>>(emptyList()) }
    var published by remember { mutableStateOf<List<UiPublishedItem>>(emptyList()) }
    var editing by remember { mutableStateOf(false) }
    // Deleting the whole account: the confirmation, the call in flight, and its failure if it had one.
    var confirmAccountDelete by remember { mutableStateOf(false) }
    var deletingAccount by remember { mutableStateOf(false) }
    var accountDeleteError by remember { mutableStateOf<String?>(null) }
    // The submission a Delete tap is asking about. Deleting removes the upload from the store, so it asks
    // first, and it names the version: a listing can have several here and the wrong one is the mistake
    // worth preventing.
    var pendingDelete by remember { mutableStateOf<UiStoreSubmission?>(null) }
    // Why a delete did not happen, from the backend. "That one is still in review" and "that one is
    // published" both name the next step, so they are shown as they were written.
    var deleteError by remember { mutableStateOf<String?>(null) }
    // The Learning section: synced first, so a phone that just signed in shows the account's figures.
    var learning by remember { mutableStateOf<dev.ide.ui.backend.UiLearnAccount?>(null) }
    var lessonsStarted by remember { mutableStateOf(0) }
    LaunchedEffect(epoch, signedIn) {
        if (!signedIn) { learning = null; return@LaunchedEffect }
        runCatching { backend.learn.syncProgress() }
        learning = runCatching { backend.learn.account() }.getOrNull()?.takeIf { it.signedIn }
        lessonsStarted = runCatching { backend.learn.progress().completedByLesson.size }.getOrDefault(0)
    }

    LaunchedEffect(epoch, signedIn) {
        if (!signedIn) {
            loading = false
            profile = null
            return@LaunchedEffect
        }
        loading = true
        profile = runCatching { backend.store.myProfile() }.getOrNull()
        // Also what notices a review decision this device was never told about: the engine compares each
        // submission with the state it last recorded and posts a notification for anything that moved.
        submissions = runCatching { backend.store.mySubmissions() }.getOrDefault(emptyList())
        published = runCatching { backend.store.myPublishedItems() }.getOrDefault(emptyList())
        loading = false
    }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            DetailTopBar(
                // The page's own header carries the name, so the bar does not repeat it.
                title = stringResource(Res.string.you_title),
                isSaved = false,
                onBack = onBack,
            )
            val current = profile
            when {
                !signedIn -> SignedOut(onSignIn)
                loading && current == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                current == null -> Unreachable { epoch++ }
                editing -> ProfileForm(
                    backend = backend,
                    profile = current,
                    onCancel = { editing = false },
                    onSaved = { editing = false; epoch++ },
                )
                else -> YouPage(
                    backend = backend,
                    profile = current,
                    submissions = submissions,
                    published = published,
                    deleteError = deleteError,
                    accountDeleteError = accountDeleteError,
                    onEdit = { editing = true },
                    onModerate = onModerate?.takeIf { current.isModerator },
                    onWithdraw = { submission ->
                        scope.launch {
                            backend.store.withdrawSubmission(submission.itemId, submission.version)
                            epoch++
                        }
                    },
                    onUpdate = onUpdateListing,
                    onDelete = { deleteError = null; pendingDelete = it },
                    onOpenItem = onOpenItem,
                    onPublish = onPublish,
                    learning = learning,
                    lessonsStarted = lessonsStarted,
                    onOpenPublicProfile = { onOpenPublicProfile(current.handle) },
                    onSignOut = {
                        backend.store.signOut()
                        // Back to where this was opened from: a signed-out You screen has nothing on it but
                        // an invitation to sign in again.
                        onBack()
                    },
                    onDeleteAccount = if (backend.store.accountDeletionAvailable()) {
                        { accountDeleteError = null; confirmAccountDelete = true }
                    } else {
                        null
                    },
                )
            }
        }
    }

    // Deleting the account cannot be undone, so it says exactly what goes, and that projects on the device stay.
    if (confirmAccountDelete) {
        AlertDialog(
            onDismissRequest = { if (!deletingAccount) confirmAccountDelete = false },
            title = { Text(stringResource(Res.string.you_delete_account_title)) },
            text = { Text(stringResource(Res.string.you_delete_account_body)) },
            confirmButton = {
                TextButton(
                    enabled = !deletingAccount,
                    onClick = {
                        deletingAccount = true
                        scope.launch {
                            val error = backend.store.deleteAccount()
                            deletingAccount = false
                            confirmAccountDelete = false
                            accountDeleteError = error
                            // Signed out now: a signed-out You screen has nothing on it, so leave it.
                            if (error == null) onBack()
                        }
                    },
                ) {
                    Text(
                        stringResource(if (deletingAccount) Res.string.you_delete_account_deleting else Res.string.you_delete_account),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !deletingAccount, onClick = { confirmAccountDelete = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    // Deleting takes the upload out of the store and can take the project row with it, so it asks. The
    // dialog is outside the list on purpose: the row it belongs to disappears the moment it succeeds.
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(Res.string.you_delete_title)) },
            text = {
                Text(stringResource(Res.string.you_delete_body, target.projectName, target.version))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            deleteError = backend.store.deleteSubmission(target.itemId, target.version)
                            // Both lists change: the submission is gone, and so is the listing when that
                            // submission was the only thing behind it.
                            if (deleteError == null) epoch++
                        }
                    },
                ) {
                    Text(stringResource(Res.string.you_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
}

/**
 * The signed-in page itself, once there is a profile to draw.
 *
 * Top to bottom it answers, in order: who am I here (the hero and the numbers), is anything waiting on me
 * (moderation, then submissions that are still open), what have I shipped (the app grid, which ends in the
 * way to ship the next one), and the account itself.
 *
 * Live submissions are not listed as submissions. Every one of them is already an app in the grid, and
 * listing them twice made the page read as twice as long as the account actually is; a listing's release
 * count is carried on its card instead.
 */
@Composable
private fun YouPage(
    backend: IdeBackend,
    profile: UiMyProfile,
    submissions: List<UiStoreSubmission>,
    published: List<UiPublishedItem>,
    deleteError: String?,
    accountDeleteError: String?,
    onEdit: () -> Unit,
    onModerate: (() -> Unit)?,
    onWithdraw: (UiStoreSubmission) -> Unit,
    onUpdate: ((String) -> Unit)?,
    onDelete: (UiStoreSubmission) -> Unit,
    onOpenItem: (String) -> Unit,
    onPublish: (() -> Unit)?,
    onOpenPublicProfile: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: (() -> Unit)?,
    learning: dev.ide.ui.backend.UiLearnAccount? = null,
    lessonsStarted: Int = 0,
) {
    val open = submissions.filter { it.status != UiSubmissionStatus.PUBLISHED }
    val iconBySlug = published.associate { it.slug to it.iconPath }
    // A newer version of a live app that is still being looked at: the app's card says so, so the grid
    // does not claim the live version is the whole story.
    val pendingBySlug = open
        .filter { it.status == UiSubmissionStatus.SUBMITTED || it.status == UiSubmissionStatus.BUILDING }
        .groupBy { it.itemId }
        .mapValues { (_, list) -> list.first().version }
    val releases = submissions.filter { it.status == UiSubmissionStatus.PUBLISHED }
        .groupingBy { it.itemId }.eachCount()
    // Null is the Publish tile, which closes the grid so the next app has an obvious place to go.
    val cells: List<UiPublishedItem?> = published + if (onPublish != null) listOf(null) else emptyList()
    val rows = cells.chunked(2)

    LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
        item("head") {
            ProfileHero(
                backend = backend,
                displayName = profile.displayName,
                handle = profile.handle,
                avatarUrl = profile.avatarUrl,
                verified = profile.verified,
                bio = profile.bio,
                location = profile.location,
                linkUrl = profile.linkUrl,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                FilledTonalButton(
                    onClick = onEdit,
                    contentPadding = PaddingValues(start = 14.dp, end = 18.dp),
                    modifier = Modifier.height(40.dp),
                ) {
                    Symbol(CaSymbols.edit, contentDescription = null, size = 18.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(Res.string.you_edit))
                }
            }
            Spacer(Modifier.height(20.dp))
            ProfileStats(
                listOf(
                    ProfileStat(CaSymbols.rocketLaunch, profile.publishedCount.toString(), stringResource(Res.string.you_stat_published)),
                    ProfileStat(CaSymbols.hourglassTop, profile.pendingCount.toString(), stringResource(Res.string.you_stat_review)),
                    ProfileStat(CaSymbols.person, compactCount(profile.followers), stringResource(Res.string.you_stat_followers)),
                    ProfileStat(CaSymbols.download, compactCount(profile.totalInstalls), stringResource(Res.string.you_stat_installs)),
                ),
            )
        }
        if (onModerate != null) {
            item("moderation") {
                Spacer(Modifier.height(12.dp))
                ModerationEntry(profile.moderationQueue, onModerate)
            }
        }
        if (open.isNotEmpty()) {
            item("submissions_head") {
                Spacer(Modifier.height(28.dp))
                ProfileSectionHeader(stringResource(Res.string.you_submissions), open.size)
                Spacer(Modifier.height(12.dp))
            }
            itemsKeyed(open, key = { "${it.itemId}:${it.version}" }) { submission ->
                SubmissionCard(
                    backend = backend,
                    submission = submission,
                    iconPath = iconBySlug[submission.itemId],
                    onWithdraw = { onWithdraw(submission) },
                    onUpdate = onUpdate?.let { start -> { start(submission.itemId) } },
                    onDelete = { onDelete(submission) },
                )
            }
            deleteError?.let { reason ->
                item("delete_error") {
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
        }
        item("published_head") {
            Spacer(Modifier.height(28.dp))
            ProfileSectionHeader(stringResource(Res.string.you_published), published.size)
            Spacer(Modifier.height(12.dp))
            if (published.isEmpty()) {
                Muted(stringResource(Res.string.you_published_none))
                Spacer(Modifier.height(12.dp))
            }
        }
        items(rows.size, key = { "apps_$it" }) { r ->
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Max)
                    .padding(horizontal = 16.dp).padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rows[r].forEach { cell ->
                    val cellModifier = Modifier.weight(1f).fillMaxHeight()
                    if (cell == null) {
                        PublishTile(onPublish ?: {}, cellModifier)
                    } else {
                        AppCard(
                            backend = backend,
                            item = cell,
                            pendingVersion = pendingBySlug[cell.slug],
                            releases = releases[cell.slug] ?: 0,
                            onOpen = { onOpenItem(cell.slug) },
                            modifier = cellModifier,
                        )
                    }
                }
                // Alone on the page (nothing published yet) the Publish tile takes the whole row: half a
                // row of empty slot beside nothing reads as a layout that failed to load.
                if (rows[r].size == 1 && published.isNotEmpty()) Spacer(Modifier.weight(1f))
            }
        }
        if (learning != null) {
            item("learning") {
                Spacer(Modifier.height(16.dp))
                ProfileSectionHeader(stringResource(Res.string.learn_section_title))
                Spacer(Modifier.height(12.dp))
                LearnProfileCard(learning.streak, learning.bestStreak, lessonsStarted, learning.badges)
                Spacer(Modifier.height(12.dp))
            }
        }
        item("account") {
            Spacer(Modifier.height(16.dp))
            ProfileSectionHeader(stringResource(Res.string.you_account))
            Spacer(Modifier.height(12.dp))
            AccountCard(onOpenPublicProfile, onSignOut, onDeleteAccount)
            accountDeleteError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * The way in to the review queue.
 *
 * A card of its own rather than a button in the account group: moderating is not something the owner of
 * this profile does to their own account. The count is the largest thing on it because whether anything
 * is waiting is the only reason to tap it.
 */
@Composable
private fun ModerationEntry(waiting: Int, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(24.dp),
        color = c.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(c.onSecondaryContainer.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(CaSymbols.gavel, contentDescription = null, size = 22.dp, tint = c.onSecondaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.you_moderation),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = c.onSecondaryContainer,
                )
                SupportingOnContainer(
                    if (waiting > 0) {
                        stringResource(Res.string.you_moderation_waiting, waiting)
                    } else {
                        stringResource(Res.string.you_moderation_clear)
                    },
                    onContainer = c.onSecondaryContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (waiting > 0) {
                Surface(shape = CircleShape, color = c.primary) {
                    Text(
                        compactCount(waiting),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = c.onPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            } else {
                Symbol(CaSymbols.chevronRight, contentDescription = null, size = 22.dp, tint = c.onSecondaryContainer)
            }
        }
    }
}

/** The glyph a status is drawn with where there is no app icon to show instead. */
private fun statusGlyph(status: UiSubmissionStatus): Char = when (status) {
    UiSubmissionStatus.SUBMITTED -> CaSymbols.hourglassTop
    UiSubmissionStatus.BUILDING -> CaSymbols.construction
    UiSubmissionStatus.CHANGES_REQUESTED -> CaSymbols.rateReview
    UiSubmissionStatus.REJECTED -> CaSymbols.block
    UiSubmissionStatus.PUBLISHED -> CaSymbols.checkCircle
}

/**
 * One submission that is still open: in review, building, or answered with something to do.
 *
 * Shows the app's own icon when the listing has one, so "Nimbus Weather 1.1.0 is in review" is
 * recognisably the Nimbus in the grid below, and the status glyph otherwise.
 */
@Composable
private fun SubmissionCard(
    backend: IdeBackend,
    submission: UiStoreSubmission,
    iconPath: String?,
    onWithdraw: () -> Unit,
    onUpdate: (() -> Unit)? = null,
    /** Offered for a decision that is finished with: a rejection is not a thing to keep looking at. */
    onDelete: (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    val rejected = submission.status == UiSubmissionStatus.REJECTED
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = c.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (iconPath != null) {
                    ListingIcon(backend, iconPath, size = 44.dp)
                } else {
                    val (container, content) = statusColors(submission.status)
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(container),
                        contentAlignment = Alignment.Center,
                    ) {
                        Symbol(statusGlyph(submission.status), contentDescription = null, size = 22.dp, tint = content)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        submission.projectName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = c.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(Res.string.you_version, submission.version),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                    )
                }
                StatusChip(submission.status)
            }
            // The note is the whole point of a rejection, so it is labelled as what it is. Unlabelled, a
            // paragraph under a red chip could be read as a description of the project rather than the
            // reason it was turned down and the thing to fix.
            submission.note?.takeIf { it.isNotBlank() }?.let { note ->
                Spacer(Modifier.height(12.dp))
                val noteContainer = if (rejected) c.errorContainer else c.surfaceContainerHighest
                val noteContent = if (rejected) c.onErrorContainer else c.onSurfaceVariant
                Surface(shape = RoundedCornerShape(16.dp), color = noteContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Eyebrow(
                            when (submission.status) {
                                UiSubmissionStatus.REJECTED -> stringResource(Res.string.you_reason_rejected)
                                UiSubmissionStatus.CHANGES_REQUESTED -> stringResource(Res.string.you_reason_changes)
                                else -> stringResource(Res.string.you_reason_note)
                            },
                            color = noteContent,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(note, style = MaterialTheme.typography.bodyMedium, color = noteContent)
                    }
                }
            }
            val canWithdraw = submission.status == UiSubmissionStatus.SUBMITTED
            // A decision that asked for something is the one case with an obvious next step, so the step
            // is here rather than three screens away.
            val canUpdate = onUpdate != null &&
                (rejected || submission.status == UiSubmissionStatus.CHANGES_REQUESTED)
            // Only for a refusal. A live version is the listing's history and a pending one is withdrawn
            // first, so offering Delete on either would be offering something the store refuses.
            val canDelete = onDelete != null && rejected
            if (canWithdraw || canUpdate || canDelete) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (canUpdate) {
                        FilledTonalButton(onUpdate!!) {
                            Symbol(CaSymbols.upload, contentDescription = null, size = 18.dp)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(Res.string.you_update))
                        }
                    }
                    if (canWithdraw) {
                        OutlinedButton(onWithdraw) { Text(stringResource(Res.string.submit_withdraw)) }
                    }
                    if (canDelete) {
                        TextButton(onDelete!!) {
                            Text(stringResource(Res.string.you_delete), color = c.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusColors(status: UiSubmissionStatus): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return when (status) {
        UiSubmissionStatus.SUBMITTED, UiSubmissionStatus.BUILDING -> c.secondaryContainer to c.onSecondaryContainer
        UiSubmissionStatus.CHANGES_REQUESTED -> c.tertiaryContainer to c.onTertiaryContainer
        UiSubmissionStatus.REJECTED -> c.errorContainer to c.onErrorContainer
        UiSubmissionStatus.PUBLISHED -> c.primaryContainer to c.onPrimaryContainer
    }
}

@Composable
private fun StatusChip(status: UiSubmissionStatus) {
    val (container, content) = statusColors(status)
    val label = when (status) {
        UiSubmissionStatus.SUBMITTED -> stringResource(Res.string.you_status_submitted)
        UiSubmissionStatus.BUILDING -> stringResource(Res.string.you_status_building)
        UiSubmissionStatus.CHANGES_REQUESTED -> stringResource(Res.string.you_status_changes)
        UiSubmissionStatus.REJECTED -> stringResource(Res.string.you_status_rejected)
        UiSubmissionStatus.PUBLISHED -> stringResource(Res.string.you_status_published)
    }
    Surface(shape = CircleShape, color = container) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/**
 * One of the account's apps, as a tile in a two-column grid: the icon large, because it is the thing a
 * publisher recognises their work by, then where it stands.
 *
 * A listing whose first version was never accepted is in this grid too, so the status line says so; with
 * only a version line it would read as published.
 */
@Composable
private fun AppCard(
    backend: IdeBackend,
    item: UiPublishedItem,
    pendingVersion: String?,
    releases: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    val live = item.publishedVersion != null
    Surface(onClick = onOpen, shape = RoundedCornerShape(24.dp), color = c.surfaceContainer, modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                ListingIcon(backend, item.iconPath, size = 56.dp)
                Spacer(Modifier.weight(1f))
                Symbol(CaSymbols.arrowForward, contentDescription = null, size = 20.dp, tint = c.onSurfaceVariant)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = c.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.summary.isNotBlank()) {
                Text(
                    item.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (live) c.primary else if (item.status == "rejected") c.error else c.outline),
                )
                val state = item.publishedVersion?.let { v ->
                    listOfNotNull(
                        "v$v",
                        releases.takeIf { it > 1 }?.let { pluralStringResource(Res.plurals.you_releases, it, it) },
                    ).joinMeta()
                } ?: when (item.status) {
                    "rejected" -> stringResource(Res.string.you_status_listing_rejected)
                    "unpublished" -> stringResource(Res.string.you_status_listing_unpublished)
                    else -> stringResource(Res.string.you_status_listing_pending)
                }
                Text(
                    state,
                    style = MaterialTheme.typography.labelMedium,
                    color = c.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (pendingVersion != null && live) {
                Spacer(Modifier.height(8.dp))
                Surface(shape = CircleShape, color = c.secondaryContainer) {
                    Row(
                        Modifier.padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Symbol(CaSymbols.hourglassTop, contentDescription = null, size = 14.dp, tint = c.onSecondaryContainer)
                        Text(
                            listOf("v$pendingVersion", stringResource(Res.string.you_status_submitted)).joinMeta(),
                            style = MaterialTheme.typography.labelSmall,
                            color = c.onSecondaryContainer,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** The last cell of the app grid: an empty slot, dashed, that is the way to fill it. */
@Composable
private fun PublishTile(onPublish: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier.heightIn(min = 168.dp).clip(RoundedCornerShape(24.dp))
            .dashedBorder(c.outline, cornerRadius = 24.dp)
            .clickable(onClick = onPublish),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
            Box(
                Modifier.size(52.dp).clip(CaShapes.SquareAction).background(c.primary),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(CaSymbols.add, contentDescription = null, size = 28.dp, tint = c.onPrimary)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(Res.string.you_publish),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = c.primary,
            )
        }
    }
}

/** The account's own controls, grouped as one card at the foot of the page. */
@Composable
private fun AccountCard(
    onOpenPublicProfile: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: (() -> Unit)?,
) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = c.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column {
            AccountRow(CaSymbols.visibility, stringResource(Res.string.you_view_public), c.onSurface, onOpenPublicProfile)
            AccountDivider()
            AccountRow(CaSymbols.accountCircle, stringResource(Res.string.you_sign_out), c.onSurface, onSignOut, chevron = false)
            if (onDeleteAccount != null) {
                AccountDivider()
                AccountRow(CaSymbols.delete, stringResource(Res.string.you_delete_account), c.error, onDeleteAccount, chevron = false)
            }
        }
    }
}

@Composable
private fun AccountRow(glyph: Char, label: String, tint: Color, onClick: () -> Unit, chevron: Boolean = true) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Symbol(glyph, contentDescription = null, size = 22.dp, tint = tint)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint, modifier = Modifier.weight(1f))
        if (chevron) Symbol(CaSymbols.chevronRight, contentDescription = null, size = 20.dp, tint = c.onSurfaceVariant)
    }
}

@Composable
private fun AccountDivider() = Box(
    Modifier.fillMaxWidth().padding(start = 56.dp).height(1.dp)
        .background(MaterialTheme.colorScheme.outlineVariant),
)

/**
 * A listing's own app icon, falling back to a glyph tile.
 *
 * The same media cache the Explore cards' icons use, so an icon is fetched once per device and a listing
 * looks the same here as it does in the store. The fallback is not a blank plate: an item whose first
 * version is still in review has no published icon yet, and neither does one that shipped none.
 */
@Composable
private fun ListingIcon(backend: IdeBackend, iconPath: String?, size: Dp) {
    val c = MaterialTheme.colorScheme
    val file by produceState<String?>(null, iconPath, backend) {
        value = iconPath?.let { runCatching { backend.store.screenshotFile(it) }.getOrNull() }
    }
    Surface(
        shape = RoundedCornerShape(size * 0.28f),
        color = c.primaryContainer,
        modifier = Modifier.size(size),
    ) {
        val path = file
        if (path != null) {
            ShotImage(backend, path, ContentScale.Crop, Modifier.fillMaxSize())
        } else {
            Box(contentAlignment = Alignment.Center) {
                Symbol(CaSymbols.deployedCode, contentDescription = null, size = size * 0.5f, tint = c.onPrimaryContainer)
            }
        }
    }
}

/**
 * The edit form.
 *
 * The handle is checked against the backend as it is typed, because it is the one field that can be
 * refused for a reason no client-side rule can see. The answer is advisory: saving asks again, and the
 * backend's answer is the one that decides.
 */
@Composable
private fun ProfileForm(
    backend: IdeBackend,
    profile: UiMyProfile,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(profile.displayName) }
    var handle by remember { mutableStateOf(profile.handle) }
    var bio by remember { mutableStateOf(profile.bio.orEmpty()) }
    var location by remember { mutableStateOf(profile.location.orEmpty()) }
    var link by remember { mutableStateOf(profile.linkUrl.orEmpty()) }
    var handleFree by remember { mutableStateOf<Boolean?>(null) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(handle) {
        handleFree = null
        if (handle == profile.handle || handle.isBlank()) return@LaunchedEffect
        // Typing is faster than a round trip, and every keystroke does not deserve one.
        delay(400)
        handleFree = runCatching { backend.store.handleAvailable(handle) }.getOrNull()
    }

    LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize().padding(horizontal = 20.dp)) {
        item("form") {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.you_field_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = handle,
                onValueChange = { handle = it.trim().lowercase() },
                label = { Text(stringResource(Res.string.you_field_handle)) },
                prefix = { Text("@") },
                singleLine = true,
                isError = handleFree == false,
                supportingText = {
                    Text(
                        when (handleFree) {
                            true -> stringResource(Res.string.you_handle_free)
                            false -> stringResource(Res.string.you_handle_taken)
                            null -> stringResource(Res.string.you_handle_rules)
                        },
                        color = when (handleFree) {
                            false -> c.error
                            else -> c.onSurfaceVariant
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(Res.string.you_handle_note),
                style = MaterialTheme.typography.bodySmall,
                color = c.outline,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = bio,
                onValueChange = { bio = it },
                label = { Text(stringResource(Res.string.you_field_bio)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text(stringResource(Res.string.you_field_location)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text(stringResource(Res.string.you_field_link)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            message?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = c.error)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (saving) return@Button
                        saving = true
                        message = null
                        scope.launch {
                            val error = backend.store.saveProfile(
                                handle = handle,
                                displayName = name,
                                bio = bio.ifBlank { null },
                                location = location.ifBlank { null },
                                linkUrl = link.ifBlank { null },
                            )
                            saving = false
                            if (error == null) onSaved() else message = error
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(Res.string.save))
                }
                OutlinedButton(onClick = onCancel, enabled = !saving, modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.cancel))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SignedOut(onSignIn: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(Res.string.you_signed_out),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onSignIn) { Text(stringResource(Res.string.you_sign_in)) }
    }
}

@Composable
private fun Unreachable(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(Res.string.you_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onRetry) { Text(stringResource(Res.string.retry)) }
    }
}

@Composable
private fun Muted(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 20.dp),
)
