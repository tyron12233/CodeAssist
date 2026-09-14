package dev.ide.ui.screens

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
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
import dev.ide.ui.components.StatFigure
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.*
import dev.ide.ui.icons.CaSymbols
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
    // The submission a Delete tap is asking about. Deleting removes the upload from the store, so it asks
    // first, and it names the version: a listing can have several here and the wrong one is the mistake
    // worth preventing.
    var pendingDelete by remember { mutableStateOf<UiStoreSubmission?>(null) }
    // Why a delete did not happen, from the backend. "That one is still in review" and "that one is
    // published" both name the next step, so they are shown as they were written.
    var deleteError by remember { mutableStateOf<String?>(null) }

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
                title = profile?.displayName ?: stringResource(Res.string.you_title),
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
                else -> LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                    item("head") {
                        ProfileHead(backend, current) { editing = true }
                    }
                    if (onModerate != null && current.isModerator) {
                        item("moderation") {
                            Spacer(Modifier.height(20.dp))
                            ModerationEntry(current.moderationQueue, onModerate)
                        }
                    }
                    item("submissions") {
                        Spacer(Modifier.height(24.dp))
                        Eyebrow(
                            stringResource(Res.string.you_submissions),
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        if (submissions.isEmpty()) {
                            Muted(stringResource(Res.string.you_submissions_none))
                        }
                    }
                    items(submissions, key = { "${it.itemId}:${it.version}" }) { submission ->
                        SubmissionRow(
                            submission = submission,
                            onWithdraw = {
                                scope.launch {
                                    backend.store.withdrawSubmission(submission.itemId, submission.version)
                                    epoch++
                                }
                            },
                            onUpdate = onUpdateListing?.let { start -> { start(submission.itemId) } },
                            onDelete = { deleteError = null; pendingDelete = submission },
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
                    item("published") {
                        Spacer(Modifier.height(24.dp))
                        Eyebrow(
                            stringResource(Res.string.you_published),
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        if (published.isEmpty()) {
                            Muted(stringResource(Res.string.you_published_none))
                        }
                    }
                    items(published, key = { it.slug }) { item ->
                        PublishedRow(backend, item) { onOpenItem(item.slug) }
                    }
                    item("actions") {
                        Spacer(Modifier.height(28.dp))
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (onPublish != null) {
                                Button(onPublish, Modifier.fillMaxWidth()) {
                                    Text(stringResource(Res.string.you_publish))
                                }
                            }
                            OutlinedButton(
                                onClick = { onOpenPublicProfile(current.handle) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(Res.string.you_view_public))
                            }
                            TextButton(
                                onClick = {
                                    backend.store.signOut()
                                    // Back to where this was opened from: a signed-out You screen has
                                    // nothing on it but an invitation to sign in again.
                                    onBack()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    stringResource(Res.string.you_sign_out),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
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
 * The way in to the review queue.
 *
 * A row rather than a button in the action stack at the foot: moderating is not something the owner of
 * this profile does to their own account, and burying it under Publish / View public profile / Sign out
 * would read as one of those. The count is on it because whether anything is waiting is the only reason
 * to tap it.
 */
@Composable
private fun ModerationEntry(waiting: Int, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = c.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Symbol(CaSymbols.gavel, contentDescription = null, size = 22.dp, tint = c.onSecondaryContainer)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.you_moderation),
                    style = MaterialTheme.typography.titleSmall,
                    color = c.onSecondaryContainer,
                )
                Text(
                    if (waiting > 0) {
                        stringResource(Res.string.you_moderation_waiting, waiting)
                    } else {
                        stringResource(Res.string.you_moderation_clear)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSecondaryContainer,
                )
            }
            Symbol(CaSymbols.chevronRight, contentDescription = null, size = 20.dp, tint = c.onSecondaryContainer)
        }
    }
}

@Composable
private fun ProfileHead(backend: IdeBackend, profile: UiMyProfile, onEdit: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Avatar(backend, profile)
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        profile.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        color = c.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (profile.verified) {
                        Symbol(CaSymbols.verified, contentDescription = null, size = 18.dp, tint = c.primary)
                    }
                }
                Text("@${profile.handle}", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
            OutlinedButton(onClick = onEdit) {
                Symbol(CaSymbols.edit, contentDescription = null, size = 16.dp, tint = c.onSurface)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(Res.string.you_edit))
            }
        }
        profile.bio?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
        }
        val meta = listOfNotNull(profile.location, profile.linkUrl)
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.outline)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatFigure(
                value = profile.publishedCount.toString(),
                label = stringResource(Res.string.you_stat_published),
                onContainer = c.onSurface,
            )
            StatFigure(
                value = profile.pendingCount.toString(),
                label = stringResource(Res.string.you_stat_review),
                onContainer = c.onSurface,
            )
            StatFigure(
                value = compactCount(profile.followers),
                label = stringResource(Res.string.you_stat_followers),
                onContainer = c.onSurface,
            )
            StatFigure(
                value = compactCount(profile.totalInstalls),
                label = stringResource(Res.string.you_stat_installs),
                onContainer = c.onSurface,
            )
        }
    }
}

/** The provider's picture where there is one, and the initials it falls back to where there is not. */
@Composable
private fun Avatar(backend: IdeBackend, profile: UiMyProfile) {
    val c = MaterialTheme.colorScheme
    val file by produceState<String?>(null, profile.avatarUrl) {
        value = profile.avatarUrl?.let { runCatching { backend.store.avatarFile(it) }.getOrNull() }
    }
    Surface(shape = CircleShape, color = c.primaryContainer, modifier = Modifier.size(64.dp)) {
        val path = file
        if (path != null) {
            ShotImage(backend, path, ContentScale.Crop, Modifier.fillMaxSize().clip(CircleShape))
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    initialsOf(profile.displayName),
                    style = MaterialTheme.typography.titleLarge,
                    color = c.onPrimaryContainer,
                )
            }
        }
    }
}

/** At most two letters, from the first two words that have one. */
private fun initialsOf(name: String): String =
    name.split(' ', '-', '_').mapNotNull { it.firstOrNull { ch -> ch.isLetterOrDigit() } }
        .take(2).joinToString("").uppercase().ifEmpty { "?" }

@Composable
private fun SubmissionRow(
    submission: UiStoreSubmission,
    onWithdraw: () -> Unit,
    onUpdate: (() -> Unit)? = null,
    /** Offered for a decision that is finished with: a rejection is not a thing to keep looking at. */
    onDelete: (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    submission.projectName,
                    style = MaterialTheme.typography.titleSmall,
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
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = when (submission.status) {
                    UiSubmissionStatus.REJECTED -> c.errorContainer
                    else -> c.surfaceContainerHighest
                },
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Eyebrow(
                        when (submission.status) {
                            UiSubmissionStatus.REJECTED -> stringResource(Res.string.you_reason_rejected)
                            UiSubmissionStatus.CHANGES_REQUESTED -> stringResource(Res.string.you_reason_changes)
                            else -> stringResource(Res.string.you_reason_note)
                        },
                        color = when (submission.status) {
                            UiSubmissionStatus.REJECTED -> c.onErrorContainer
                            else -> c.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (submission.status) {
                            UiSubmissionStatus.REJECTED -> c.onErrorContainer
                            else -> c.onSurfaceVariant
                        },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (submission.status == UiSubmissionStatus.SUBMITTED) {
                TextButton(onWithdraw) { Text(stringResource(Res.string.submit_withdraw)) }
            }
            // A decision that asked for something is the one case with an obvious next step, so the step
            // is here rather than three screens away.
            if (onUpdate != null &&
                (submission.status == UiSubmissionStatus.REJECTED ||
                    submission.status == UiSubmissionStatus.CHANGES_REQUESTED)
            ) {
                TextButton(onUpdate) { Text(stringResource(Res.string.you_update)) }
            }
            // Only for a refusal. A live version is the listing's history and a pending one is withdrawn
            // first, so offering Delete on either would be offering something the store refuses.
            if (onDelete != null && submission.status == UiSubmissionStatus.REJECTED) {
                TextButton(onDelete) {
                    Text(stringResource(Res.string.you_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: UiSubmissionStatus) {
    val c = MaterialTheme.colorScheme
    val (label, container, content) = when (status) {
        UiSubmissionStatus.SUBMITTED ->
            Triple(stringResource(Res.string.you_status_submitted), c.secondaryContainer, c.onSecondaryContainer)
        UiSubmissionStatus.BUILDING ->
            Triple(stringResource(Res.string.you_status_building), c.secondaryContainer, c.onSecondaryContainer)
        UiSubmissionStatus.CHANGES_REQUESTED ->
            Triple(stringResource(Res.string.you_status_changes), c.tertiaryContainer, c.onTertiaryContainer)
        UiSubmissionStatus.REJECTED ->
            Triple(stringResource(Res.string.you_status_rejected), c.errorContainer, c.onErrorContainer)
        UiSubmissionStatus.PUBLISHED ->
            Triple(stringResource(Res.string.you_status_published), c.primaryContainer, c.onPrimaryContainer)
    }
    Surface(shape = CircleShape, color = container) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun PublishedRow(backend: IdeBackend, item: UiPublishedItem, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ListingIcon(backend, item.iconPath)
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                color = c.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // A listing whose first version was never accepted is in this list too, and without this it
            // read as published: no version line and nothing else to go on.
            val state = item.publishedVersion?.let { stringResource(Res.string.you_version, it) }
                ?: when (item.status) {
                    "rejected" -> stringResource(Res.string.you_status_listing_rejected)
                    "unpublished" -> stringResource(Res.string.you_status_listing_unpublished)
                    else -> stringResource(Res.string.you_status_listing_pending)
                }
            Text(state, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        Symbol(CaSymbols.chevronRight, contentDescription = null, size = 20.dp, tint = c.onSurfaceVariant)
    }
}

/**
 * A listing's own app icon, falling back to a glyph tile.
 *
 * The same media cache the Explore cards' icons use, so an icon is fetched once per device and a listing
 * looks the same here as it does in the store. The fallback is not a blank plate: an item whose first
 * version is still in review has no published icon yet, and neither does one that shipped none.
 */
@Composable
private fun ListingIcon(backend: IdeBackend, iconPath: String?) {
    val c = MaterialTheme.colorScheme
    val file by produceState<String?>(null, iconPath, backend) {
        value = iconPath?.let { runCatching { backend.store.screenshotFile(it) }.getOrNull() }
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = c.primaryContainer,
        modifier = Modifier.size(40.dp),
    ) {
        val path = file
        if (path != null) {
            ShotImage(backend, path, ContentScale.Crop, Modifier.fillMaxSize())
        } else {
            Box(contentAlignment = Alignment.Center) {
                Symbol(CaSymbols.deployedCode, contentDescription = null, size = 20.dp, tint = c.onPrimaryContainer)
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
