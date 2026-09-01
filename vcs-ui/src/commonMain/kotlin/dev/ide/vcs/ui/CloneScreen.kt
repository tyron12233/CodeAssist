package dev.ide.vcs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiForgeRepo
import dev.ide.ui.backend.UiProjectFolderKind
import dev.ide.ui.backend.VcsService
import dev.ide.ui.components.Chip
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.ext.ScreenContext
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ide
import dev.ide.vcs.ui.generated.resources.Res
import dev.ide.vcs.ui.generated.resources.vcs_cancel
import dev.ide.vcs.ui.generated.resources.vcs_clone_action
import dev.ide.vcs.ui.generated.resources.vcs_clone_folder
import dev.ide.vcs.ui.generated.resources.vcs_clone_none
import dev.ide.vcs.ui.generated.resources.vcs_clone_search
import dev.ide.vcs.ui.generated.resources.vcs_clone_sign_in
import dev.ide.vcs.ui.generated.resources.vcs_clone_title
import dev.ide.vcs.ui.generated.resources.vcs_clone_unrecognized_body
import dev.ide.vcs.ui.generated.resources.vcs_clone_unrecognized_open
import dev.ide.vcs.ui.generated.resources.vcs_clone_unrecognized_title
import dev.ide.vcs.ui.generated.resources.vcs_clone_url
import dev.ide.vcs.ui.generated.resources.vcs_clone_yours
import dev.ide.vcs.ui.generated.resources.vcs_fork
import dev.ide.vcs.ui.generated.resources.vcs_private
import dev.ide.vcs.ui.generated.resources.vcs_sign_in_github
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Clone a repository into a new project: paste a URL, or pick one of the signed-in account's repositories.
 *
 * A clone always lands as a listable project, but a repository is not a CodeAssist project, so the result
 * says which of the three cases it was. A recognized one opens straight away and the shell lands on it, so
 * this screen does not navigate itself. One nothing recognized stops here instead, behind a notice: the files
 * are saved and listed either way, and the user decides whether to open an editor that cannot build them.
 */
@Composable
internal fun CloneScreen(ctx: ScreenContext) {
    val vcs = ctx.backend.vcs
    val accounts by vcs.accounts.collectAsState()
    val activity by vcs.activity.collectAsState()
    val scope = rememberCoroutineScope()
    val feedback = rememberVcsFeedback()

    var url by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var repos by remember { mutableStateOf(emptyList<UiForgeRepo>()) }
    var loading by remember { mutableStateOf(false) }
    // A finished clone that no build system recognized: held here rather than opened, because the user has to
    // be told before they land in an editor that cannot build anything.
    var unrecognized by remember { mutableStateOf<ClonedFolder?>(null) }

    // Debounced so typing a search term does not fire a request per keystroke.
    LaunchedEffect(query, accounts.size) {
        if (accounts.isEmpty()) {
            repos = emptyList()
            return@LaunchedEffect
        }
        if (query.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
        loading = true
        repos = vcs.forgeRepositories(query)
        loading = false
    }

    fun startClone() {
        val target = url.trim()
        val name = folder.trim().ifBlank { target.substringAfterLast('/').removeSuffix(".git") }
        scope.launch {
            unrecognized = null
            val result = vcs.cloneRepository(target, name)
            feedback.show(result.message, isError = !result.ok)
            val path = result.path
            if (!result.ok || path == null) return@launch
            // The clone is a listable project either way. Only an unrecognized one stops here for an answer.
            if (result.projectKind == UiProjectFolderKind.UNKNOWN) unrecognized = ClonedFolder(name, path)
            else ctx.backend.projects.openProject(path)
        }
    }

    ExpressiveScaffold(
        title = stringResource(Res.string.vcs_clone_title),
        onBack = ctx::back,
        large = false,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                VcsField(url, { url = it }, stringResource(Res.string.vcs_clone_url), leading = CaIcons.share)
                VcsField(folder, { folder = it }, stringResource(Res.string.vcs_clone_folder), leading = CaIcons.folder)
                PrimaryButton(
                    stringResource(Res.string.vcs_clone_action),
                    ::startClone,
                    icon = CaIcons.cloudDownload,
                )
                if (activity.busy) {
                    Text(
                        activity.task,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (activity.fraction >= 0f) {
                        LinearProgressIndicator({ activity.fraction }, Modifier.fillMaxWidth().height(3.dp))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                    }
                }
            }
            FeedbackStrip(feedback)
            unrecognized?.let { cloned ->
                UnrecognizedCloneNotice(
                    name = cloned.name,
                    onDismiss = { unrecognized = null },
                    onOpen = {
                        unrecognized = null
                        scope.launch { ctx.backend.projects.openProject(cloned.path) }
                    },
                )
            }

            if (accounts.isEmpty()) {
                VcsEmptyState(
                    icon = CaIcons.account,
                    title = stringResource(Res.string.vcs_sign_in_github),
                    body = stringResource(Res.string.vcs_clone_sign_in),
                ) {
                    Spacer(Modifier.height(4.dp))
                    PrimaryButton(
                        stringResource(Res.string.vcs_sign_in_github),
                        { ctx.openScreen(VcsService.SCREEN_ACCOUNTS) },
                        icon = CaIcons.account,
                    )
                }
            } else {
                SectionHeader(stringResource(Res.string.vcs_clone_yours), repos.size)
                VcsField(
                    query,
                    { query = it },
                    stringResource(Res.string.vcs_clone_search),
                    leading = CaIcons.search,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(6.dp))
                if (repos.isEmpty() && !loading) {
                    Text(
                        stringResource(Res.string.vcs_clone_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(repos, key = { it.fullName }) { repo ->
                            RepoRow(repo) {
                                url = repo.cloneUrl
                                folder = repo.name
                            }
                        }
                        item("tail") { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

/** A clone that finished, waiting on the user because nothing recognized what it holds. */
private data class ClonedFolder(val name: String, val path: String)

/**
 * Said in place, on the clone screen, the moment a clone turns out to hold no project the IDE understands.
 * The files are already saved and listed, so the only question left is whether to open them now, and the
 * editor repeats the limitation in its own banner once it does.
 */
@Composable
private fun UnrecognizedCloneNotice(name: String, onDismiss: () -> Unit, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(Ide.colors.warning.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(CaIcons.warning, null, Modifier.size(16.dp), tint = Ide.colors.warning)
            Text(
                stringResource(Res.string.vcs_clone_unrecognized_title),
                style = MaterialTheme.typography.bodyMedium,
                color = Ide.colors.warning,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            stringResource(Res.string.vcs_clone_unrecognized_body, name),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onDismiss) { Text(stringResource(Res.string.vcs_cancel), style = MaterialTheme.typography.labelLarge) }
            PrimaryButton(stringResource(Res.string.vcs_clone_unrecognized_open), onOpen, icon = CaIcons.folder)
        }
    }
}

@Composable
private fun RepoRow(repo: UiForgeRepo, onPick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onPick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CaIcons.folder, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                repo.fullName,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (repo.description.isNotBlank()) {
                Text(
                    repo.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (repo.private) Chip(stringResource(Res.string.vcs_private))
                if (repo.fork) Chip(stringResource(Res.string.vcs_fork))
                if (repo.language.isNotBlank()) Chip(repo.language)
                if (repo.updatedLabel.isNotBlank()) Chip(repo.updatedLabel)
            }
        }
    }
}

/** How long a search term must settle before a request goes out. */
private const val SEARCH_DEBOUNCE_MS = 350L
