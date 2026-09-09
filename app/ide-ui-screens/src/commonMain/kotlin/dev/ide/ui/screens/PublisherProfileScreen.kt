package dev.ide.ui.screens

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiPublisherProfile
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.components.Eyebrow
import dev.ide.ui.components.FollowButton
import dev.ide.ui.components.StatFigure
import dev.ide.ui.components.formatStars
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.profile_installs
import dev.ide.ui.generated.resources.profile_likes
import dev.ide.ui.generated.resources.profile_missing
import dev.ide.ui.generated.resources.profile_none
import dev.ide.ui.generated.resources.profile_projects
import dev.ide.ui.generated.resources.profile_published
import dev.ide.ui.generated.resources.profile_rating
import dev.ide.ui.generated.resources.profile_unrated
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Symbol
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * A publisher's page: who they are, what they have published, and what it adds up to.
 *
 * The totals are the point. A single project's install count says little, while "three projects, 98k
 * installs, 4.6 across them" is the thing a reader is actually judging when they decide whether to trust
 * the next one.
 *
 * The rating shown is weighted by each project's rating count, computed server-side, so a project with one
 * five-star review cannot outweigh one with two hundred ratings.
 */
@Composable
fun PublisherProfileScreen(
    backend: IdeBackend,
    handle: String,
    onBack: () -> Unit,
    onOpenItem: (UiStoreItem) -> Unit,
    modifier: Modifier = Modifier,
    /** Asks for sign-in when following needs an account. Null hides the follow button entirely. */
    onNeedSignIn: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var following by remember(handle) { mutableStateOf<Boolean?>(null) }
    var followMessage by remember(handle) { mutableStateOf<String?>(null) }
    val signedIn = backend.store.authState().collectAsState().value.signedIn

    val profile by produceState<UiPublisherProfile?>(null, handle, backend) {
        value = UiPublisherProfile(handle, handle, loading = true)
        value = runCatching { backend.store.publisherProfile(handle) }.getOrNull()
    }
    val current = profile

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            DetailTopBar(
                title = current?.displayName ?: handle,
                isSaved = false,
                onBack = onBack,
            )
            when {
                current == null -> Missing()
                current.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                    item("head") {
                        ProfileHeader(
                            profile = current,
                            // Local state wins once tapped, so the button reflects the tap rather than the
                            // fetch it was rendered from.
                            following = following ?: current.following,
                            onToggleFollow = {
                                if (!signedIn) {
                                    onNeedSignIn?.invoke()
                                } else {
                                    val next = !(following ?: current.following)
                                    following = next
                                    scope.launch {
                                        val error = backend.store.setFollowing(handle, next)
                                        if (error != null) {
                                            // Put it back: the server did not accept it.
                                            following = !next
                                            followMessage = error
                                        }
                                    }
                                }
                            },
                            showFollow = onNeedSignIn != null,
                            message = followMessage,
                        )
                    }
                    current.error?.let { error ->
                        item("error") {
                            Text(
                                error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                    item("shelf") {
                        Spacer(Modifier.height(22.dp))
                        Eyebrow(
                            stringResource(Res.string.profile_published),
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (current.items.isEmpty()) {
                        item("empty") {
                            Text(
                                stringResource(Res.string.profile_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                        }
                    }
                    itemsIndexed(current.items, key = { _, it -> it.id }) { i, item ->
                        StoreItemRow(item, i, onOpenItem)
                    }
                    item("tail") { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(
    profile: UiPublisherProfile,
    following: Boolean,
    onToggleFollow: () -> Unit,
    showFollow: Boolean,
    message: String?,
) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = CircleShape, color = c.primaryContainer, modifier = Modifier.size(64.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Symbol(
                        CaSymbols.person,
                        contentDescription = null,
                        size = 32.dp,
                        tint = c.onPrimaryContainer,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        profile.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        color = c.onSurface,
                    )
                    if (profile.verified) {
                        Symbol(CaSymbols.verified, contentDescription = null, size = 18.dp, tint = c.primary)
                    }
                }
                Text(
                    "@${profile.handle}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                )
            }
            if (showFollow) {
                FollowButton(
                    following = following,
                    onClick = onToggleFollow,
                    container = if (following) c.surfaceContainerHighest else c.primary,
                    content = if (following) c.onSurfaceVariant else c.onPrimary,
                )
            }
        }
        profile.bio?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
        }
        val meta = listOfNotNull(profile.location, profile.linkUrl)
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                meta.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = c.outline,
            )
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = c.error)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatFigure(
                value = profile.projectCount.toString(),
                label = stringResource(Res.string.profile_projects),
                onContainer = c.onSurface,
            )
            StatFigure(
                value = compactCount(profile.totalInstalls),
                label = stringResource(Res.string.profile_installs),
                onContainer = c.onSurface,
            )
            StatFigure(
                value = compactCount(profile.totalLikes),
                label = stringResource(Res.string.profile_likes),
                onContainer = c.onSurface,
            )
            StatFigure(
                // Absent rather than 0.0: an unrated catalogue has no average, and zero would read as
                // unanimously terrible.
                value = profile.averageRating?.let { formatStars(it) } ?: "–",
                label = if (profile.averageRating != null) {
                    stringResource(Res.string.profile_rating)
                } else {
                    stringResource(Res.string.profile_unrated)
                },
                onContainer = c.onSurface,
            )
        }
    }
}

@Composable
private fun Missing() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(Res.string.profile_missing),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}
