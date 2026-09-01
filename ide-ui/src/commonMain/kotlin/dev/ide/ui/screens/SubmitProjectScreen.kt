package dev.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.UiPackagedProject
import dev.ide.ui.backend.UiSubmissionDraft
import dev.ide.ui.components.Eyebrow
import dev.ide.ui.components.PillChip
import dev.ide.ui.components.PrimaryActionButton
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.submit_excluded_none
import dev.ide.ui.generated.resources.submit_excluded_title
import dev.ide.ui.generated.resources.submit_excluded_why
import dev.ide.ui.generated.resources.submit_field_category
import dev.ide.ui.generated.resources.submit_field_description
import dev.ide.ui.generated.resources.submit_field_summary
import dev.ide.ui.generated.resources.submit_field_tags
import dev.ide.ui.generated.resources.submit_field_title
import dev.ide.ui.generated.resources.submit_field_version
import dev.ide.ui.generated.resources.submit_included
import dev.ide.ui.generated.resources.submit_no_projects
import dev.ide.ui.generated.resources.submit_packaging
import dev.ide.ui.generated.resources.submit_pick_project
import dev.ide.ui.generated.resources.submit_required
import dev.ide.ui.generated.resources.submit_review_note
import dev.ide.ui.generated.resources.submit_send
import dev.ide.ui.generated.resources.submit_sending
import dev.ide.ui.generated.resources.submit_title
import dev.ide.ui.icons.CaSymbols
import org.jetbrains.compose.resources.stringResource

/**
 * Publish a project to the store.
 *
 * Packaging comes before the form on purpose. The archive is about to be made public, so the first thing
 * the screen shows is what is in it and what was dropped: the packager excludes keystores,
 * `local.properties`, `.env` and `google-services.json`, and saying so is how the user can tell it
 * happened rather than taking it on trust. The form is only reached once that is on screen.
 */
@Composable
fun SubmitProjectScreen(
    backend: IdeBackend,
    onBack: () -> Unit,
    onSubmitted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var chosen by remember { mutableStateOf<ProjectInfo?>(null) }
    var packaged by remember { mutableStateOf<UiPackagedProject?>(null) }
    var packing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(UiSubmissionDraft()) }
    var categories by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var sending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // Resolved here rather than inside the click handler: a string resource needs composition.
    val requiredText = stringResource(Res.string.submit_required)

    val projects = remember { runCatching { backend.projects.projects() }.getOrDefault(emptyList()) }

    LaunchedEffect(Unit) {
        categories = runCatching { backend.store.submitCategories() }.getOrDefault(emptyList())
    }

    // Packaging starts as soon as a project is chosen: it is local, and its result is what the rest of the
    // screen is about.
    LaunchedEffect(chosen?.rootPath) {
        val root = chosen?.rootPath ?: return@LaunchedEffect
        packing = true
        message = null
        packaged = runCatching { backend.store.packProject(root) }.getOrNull()
        if (packaged == null) message = backend.store.packFailure(root)
        packing = false
        // The project's own name is the obvious starting title; the user can change it.
        if (draft.title.isBlank()) draft = draft.copy(title = chosen?.name.orEmpty())
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            DetailTopBar(title = stringResource(Res.string.submit_title), isSaved = false, onBack = onBack)
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize().padding(horizontal = 20.dp)) {
                if (chosen == null) {
                    item("pick") {
                        Eyebrow(stringResource(Res.string.submit_pick_project))
                        Spacer(Modifier.height(10.dp))
                    }
                    if (projects.isEmpty()) {
                        item("none") { Body(stringResource(Res.string.submit_no_projects)) }
                    }
                    items(projects, key = { it.rootPath }) { project ->
                        ProjectRow(project) { chosen = project }
                    }
                    return@LazyColumn
                }

                item("packaged") {
                    Spacer(Modifier.height(6.dp))
                    if (packing) {
                        Body(stringResource(Res.string.submit_packaging))
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        packaged?.let { PackagedSummary(it) }
                    }
                    message?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }

                if (packaged != null) {
                    item("form") {
                        Spacer(Modifier.height(22.dp))
                        Field(stringResource(Res.string.submit_field_title), draft.title) { draft = draft.copy(title = it) }
                        Field(stringResource(Res.string.submit_field_summary), draft.summary) { draft = draft.copy(summary = it) }
                        Field(
                            stringResource(Res.string.submit_field_description),
                            draft.description,
                            lines = 4,
                        ) { draft = draft.copy(description = it) }
                        Field(stringResource(Res.string.submit_field_version), draft.version) { draft = draft.copy(version = it) }
                        Field(stringResource(Res.string.submit_field_tags), draft.tags.joinToString(", ")) { raw ->
                            draft = draft.copy(tags = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                        }
                        Spacer(Modifier.height(14.dp))
                        Eyebrow(stringResource(Res.string.submit_field_category))
                        Spacer(Modifier.height(8.dp))
                        // The slug is what the backend stores, so the chip carries it and shows the title.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            categories.take(3).forEach { (slug, title) ->
                                PillChip(
                                    label = title,
                                    selected = draft.category == slug,
                                    onClick = { draft = draft.copy(category = slug) },
                                )
                            }
                        }
                        if (categories.size > 3) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                categories.drop(3).forEach { (slug, title) ->
                                    PillChip(
                                        label = title,
                                        selected = draft.category == slug,
                                        onClick = { draft = draft.copy(category = slug) },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(22.dp))
                        Body(stringResource(Res.string.submit_review_note))
                        Spacer(Modifier.height(16.dp))
                        val complete = draft.title.isNotBlank() && draft.summary.isNotBlank() &&
                            draft.description.isNotBlank() && draft.category.isNotBlank()
                        PrimaryActionButton(
                            label = if (sending) {
                                stringResource(Res.string.submit_sending)
                            } else {
                                stringResource(Res.string.submit_send)
                            },
                            glyph = CaSymbols.upload,
                            onClick = {
                                if (!complete) {
                                    message = requiredText
                                } else if (!sending) {
                                    sending = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(28.dp))
                    }
                }
            }
        }
    }

    // The upload itself, kept out of the click handler so leaving the button does not cancel it.
    LaunchedEffect(sending) {
        if (!sending) return@LaunchedEffect
        val archive = packaged ?: return@LaunchedEffect
        val result = runCatching { backend.store.submit(draft, archive) }.getOrNull()
        sending = false
        message = result?.message
        if (result?.success == true) onSubmitted()
    }
}

@Composable
private fun PackagedSummary(packaged: UiPackagedProject) {
    Column {
        Text(
            stringResource(Res.string.submit_included, packaged.fileCount, formatSize(packaged.totalBytes)),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(14.dp))
        Eyebrow(stringResource(Res.string.submit_excluded_title))
        Spacer(Modifier.height(6.dp))
        if (packaged.excluded.isEmpty()) {
            Body(stringResource(Res.string.submit_excluded_none))
        } else {
            Body(stringResource(Res.string.submit_excluded_why))
            Spacer(Modifier.height(6.dp))
            packaged.excluded.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProjectRow(project: ProjectInfo, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(project.name, style = MaterialTheme.typography.titleMedium)
            Text(
                project.rootPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String, lines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = lines == 1,
        minLines = lines,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

@Composable
private fun Body(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "${(bytes * 10 / (1024 * 1024)) / 10.0} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
