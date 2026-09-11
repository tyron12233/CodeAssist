package dev.ide.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.OpenFile
import dev.ide.ui.editor.core.isLarge
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.editor_large_file_notice
import dev.ide.ui.generated.resources.library_decompile_java
import dev.ide.ui.generated.resources.library_readonly_decompiled
import dev.ide.ui.generated.resources.library_readonly_source
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * The read-only notice for a LIBRARY tab (a `library://…` decompiled or attached-source view), or null for a
 * normal editable file.
 *
 * A notice rather than a banner of its own: it is one sentence, and it used to be one of ten bars competing
 * for the top of the screen. Not dismissible, because it describes the file currently on screen, so hiding
 * it would leave the reader typing into something that will not save.
 */
@Composable
internal fun readOnlyNotice(state: IdeUiState, active: OpenFile): EditorNotice? {
    val kind = active.libraryKind ?: return null
    val label = if (kind == "source") stringResource(Res.string.library_readonly_source)
    else stringResource(Res.string.library_readonly_decompiled)
    val decompile = stringResource(Res.string.library_decompile_java)
    return EditorNotice(
        id = "readonly",
        level = NoticeLevel.Info,
        summary = label,
        actionLabel = decompile.takeIf { kind != "decompiled_java" },
        onAction = { state.openLibrary(active.path, forceJava = true) }.takeIf { kind != "decompiled_java" },
    )
}

/**
 * The large-file notice: past [isLarge] the editor suppresses the memory-heavy code intelligence (analysis,
 * semantic colouring, folds, inlays, completion, outline) so a big file stays within the heap on a low-RAM
 * device. Syntax highlighting and editing are unaffected. Null for a normal-sized file.
 */
@Composable
internal fun largeFileNotice(active: OpenFile): EditorNotice? {
    if (!active.session.doc.isLarge()) return null
    return EditorNotice(
        id = "largefile",
        level = NoticeLevel.Info,
        summary = stringResource(Res.string.editor_large_file_notice),
    )
}
