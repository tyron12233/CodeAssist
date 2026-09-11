package dev.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateListOf
import dev.ide.ui.generated.resources.submit_add_screenshot
import dev.ide.ui.generated.resources.submit_category_loading
import dev.ide.ui.generated.resources.submit_category_retry
import dev.ide.ui.generated.resources.submit_category_unavailable
import dev.ide.ui.generated.resources.submit_screenshots
import dev.ide.ui.generated.resources.submit_screenshots_desc
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
    /** Picks image files for the screenshots. Null hides the picker on a host that cannot. */
    fileActions: dev.ide.ui.backend.FileActions? = null,
    /**
     * The project to publish, when the caller already knows it.
     *
     * Set when this was reached from Share, where a project was just chosen — asking again for something
     * the user picked one screen ago is the kind of step that makes a flow feel like paperwork.
     */
    initialProject: ProjectInfo? = null,
) {
    var chosen by remember(initialProject?.rootPath) { mutableStateOf(initialProject) }
    var packaged by remember { mutableStateOf<UiPackagedProject?>(null) }
    var packing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(UiSubmissionDraft()) }
    // The tags field keeps its own text. Deriving it from the parsed list instead ate the separator the
    // moment it was typed ("a," parses to ["a"] and formats back to "a"), so a comma could never be
    // entered at all and the field looked like it rejected them. Unkeyed, like `draft` itself: the two
    // hold one answer in two shapes and must not outlive each other.
    var tagsText by remember { mutableStateOf("") }
    var categories by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var categoriesLoading by remember { mutableStateOf(true) }
    var categoryAttempt by remember { mutableStateOf(0) }
    // The images people will actually judge the project by. Same picker the export flow uses.
    val screenshots = remember(chosen?.rootPath) { mutableStateListOf<String>() }
    var sending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // Resolved here rather than inside the click handler: a string resource needs composition.
    val requiredText = stringResource(Res.string.submit_required)

    val projects = remember { runCatching { backend.projects.projects() }.getOrDefault(emptyList()) }

    LaunchedEffect(categoryAttempt) {
        categoriesLoading = true
        categories = runCatching { backend.store.submitCategories() }.getOrDefault(emptyList())
        categoriesLoading = false
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
                        Field(stringResource(Res.string.submit_field_tags), tagsText) { raw ->
                            tagsText = raw
                            draft = draft.copy(tags = parseTags(raw))
                        }
                        // Hidden rather than disabled on a host that cannot pick files: there is nothing the
                        // user could do to make it work.
                        val picker = fileActions?.takeIf { it.canPickFile }
                        if (picker != null) {
                            Spacer(Modifier.height(18.dp))
                            Eyebrow(stringResource(Res.string.submit_screenshots))
                            Spacer(Modifier.height(4.dp))
                            Body(stringResource(Res.string.submit_screenshots_desc))
                            if (screenshots.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    screenshots.forEach { path ->
                                        ScreenshotThumb(backend, path) { screenshots.remove(path) }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(
                                onClick = {
                                    picker.pickFile(SCREENSHOT_EXTENSIONS) { path ->
                                        // Re-checked on the way back in, not trusted from when the button was
                                        // drawn: the callback lands after an arbitrary trip through the system
                                        // picker. The same image twice would also make removing one ambiguous,
                                        // since `remove` drops the first match.
                                        if (path != null && path !in screenshots &&
                                            screenshots.size < MAX_SUBMIT_SCREENSHOTS
                                        ) {
                                            screenshots += path
                                        }
                                    }
                                },
                                enabled = screenshots.size < MAX_SUBMIT_SCREENSHOTS,
                            ) {
                                Text(stringResource(Res.string.submit_add_screenshot))
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Eyebrow(stringResource(Res.string.submit_field_category))
                        Spacer(Modifier.height(8.dp))
                        when {
                            categoriesLoading -> Body(stringResource(Res.string.submit_category_loading))
                            // The slug is a foreign key, so the form cannot invent one. With no list there is
                            // nothing valid to pick and the submission is blocked either way, so it says so
                            // and offers the retry rather than leaving a heading over empty space.
                            categories.isEmpty() -> {
                                Body(stringResource(Res.string.submit_category_unavailable))
                                TextButton(onClick = { categoryAttempt++ }) {
                                    Text(stringResource(Res.string.submit_category_retry))
                                }
                            }
                            // The slug is what the backend stores, so the chip carries it and shows the title.
                            // Flowed rather than split into fixed rows: the list is a table the backend owns
                            // and can grow, and the titles are translated.
                            else -> FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                categories.forEach { (slug, title) ->
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
        // The picker holds the screenshots in its own list so removing one does not rebuild the draft on
        // every tap; they join it here, at the one point that matters.
        val request = draft.copy(screenshotPaths = screenshots.toList())
        val result = runCatching { backend.store.submit(request, archive) }.getOrNull()
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

/**
 * Tags as typed: comma separated, trimmed, deduplicated and capped.
 *
 * The cap is `cardinality(tags) <= 10` on `store_items`. Applied here rather than left to the row because
 * the archive is uploaded before the row is written: an eleventh tag would fail the submission after the
 * upload had already happened.
 */
private fun parseTags(raw: String): List<String> =
    raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_SUBMIT_TAGS)

/** Matches the database CHECK and the submission service's own cap. */
private const val MAX_SUBMIT_SCREENSHOTS = 6

/** Matches `cardinality(tags) <= 10` on `store_items`. */
private const val MAX_SUBMIT_TAGS = 10

/** What the store accepts as a screenshot, and what narrows the host's picker to images. */
private val SCREENSHOT_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp")
