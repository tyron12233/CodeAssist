package dev.ide.ui.editor.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.ComposePreviewHost
import dev.ide.ui.backend.EditorService
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.editor.CodeEditor
import dev.ide.ui.editor.CodeLanguage
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.folding.FoldRegion
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.learn_compose_downloading
import dev.ide.ui.generated.resources.learn_compose_edit_hint
import dev.ide.ui.generated.resources.learn_compose_indexing
import dev.ide.ui.generated.resources.learn_compose_preparing
import dev.ide.ui.screens.CodeSample
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jetbrains.compose.resources.stringResource

/** Debounce a typing burst in the interactive Kotlin field into a single re-render. */
private const val LESSON_COMPOSE_DEBOUNCE_MS = 300L

/**
 * A compact, live Jetpack Compose preview embedded in a Learn lesson (a [dev.ide.ui.backend.UiContentBlock.ComposePreview]).
 * Renders the self-contained [code]'s `@Preview @Composable` on a phone-style card through the platform
 * [host] (the same interpreter the editor's Compose preview uses), so a Compose lesson can *show* the UI it
 * teaches rendering as real UI. When [interactive] the learner gets an editable Kotlin field above the frame
 * that re-renders as they type. [caption] labels the frame.
 *
 * Rendering runs against the bundled Compose runtime (Compose-for-Desktop / Compose-for-Android), so standard
 * material3/foundation composables render everywhere. Lowering needs `androidx.compose.*` on a classpath, which
 * the backend resolves once into a hidden scratch project — the first Compose lesson shows a brief "Preparing"
 * gate. With no [host] the block degrades to a read-only Kotlin code sample so the lesson still reads.
 */
@Composable
fun LessonComposePreview(
    code: String,
    backend: IdeBackend,
    host: ComposePreviewHost?,
    interactive: Boolean,
    caption: String,
    modifier: Modifier = Modifier,
) {
    if (host == null) {
        // Host without Compose support: fall back to the source read-only so the lesson still reads.
        CodeSample(code.trim(), "kotlin", modifier.fillMaxWidth())
        return
    }

    // Warm the Compose scratch (resolve androidx.compose the first time) before rendering, so the first Compose
    // lesson shows a clear "Preparing" message instead of a bare spinner. `prepare` opens the gate even on a
    // slow start; the host's own lowering still waits for the classpath, so a premature render just shows busy.
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        ready = runCatching { backend.learn.prepare("kotlin-compose") }.getOrDefault(true)
    }
    // While preparing, reflect whether the workspace is still indexing (after the one-time library download) so
    // the wait shows live activity rather than a frozen-looking spinner.
    var indexing by remember { mutableStateOf(false) }
    LaunchedEffect(ready) {
        while (!ready && isActive) {
            indexing = runCatching { backend.learn.indexing("kotlin-compose") }.getOrDefault(false)
            delay(600)
        }
    }

    // The editable buffer (interactive lessons only) — reset when the authored code changes.
    val session = remember(code) { EditorSession(code.trim(), CodeLanguage.Kotlin) }
    val editorBackend = remember(backend) { ComposeLessonEditorBackend(backend) }

    // Re-render on each settled edit (interactive) or once (static). Reading `textRevision` in composition
    // observes edits; the buffer text is pulled inside the effect.
    val rev = if (interactive) session.textRevision else 0
    var renderCode by remember(code) { mutableStateOf(code.trim()) }
    LaunchedEffect(code, rev, interactive) {
        if (interactive) delay(LESSON_COMPOSE_DEBOUNCE_MS)
        renderCode = if (interactive) session.doc.text else code.trim()
    }

    // Live error analysis + inlay hints + code folding for the interactive editor, pushed to the shared session
    // (the same intelligence the main lesson editor shows) — computed against the kotlin-compose scratch and
    // re-run on each settled edit once the workspace is ready.
    if (interactive) {
        LaunchedEffect(ready, session.textRevision) {
            if (!ready) return@LaunchedEffect
            delay(LESSON_COMPOSE_DEBOUNCE_MS)
            val src = session.doc.text
            session.applyAnalysis(runCatching { backend.learn.analyze("kotlin-compose", src) }.getOrDefault(emptyList()))
            session.applyInlayHints(runCatching { backend.learn.hints("kotlin-compose", src, 0, src.length) }.getOrDefault(emptyList()))
            session.applyCodeFolds(
                runCatching { backend.learn.folds("kotlin-compose", src) }.getOrDefault(emptyList())
                    .map { FoldRegion(it.startOffset, it.endOffset, it.placeholder, it.kind, it.collapsedByDefault) },
            )
        }
    }

    var problems by remember(code) { mutableStateOf<List<PreviewIssue>>(emptyList()) }
    var busy by remember(code) { mutableStateOf(false) }

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (interactive) {
            val editorShape = RoundedCornerShape(Ca.radius.md)
            Box(
                Modifier.fillMaxWidth().height(200.dp).clip(editorShape)
                    .background(Ca.colors.editorBg).border(1.dp, Ca.colors.hairline, editorShape),
            ) {
                CodeEditor(
                    path = "Preview.kt",
                    session = session,
                    backend = editorBackend,
                    modifier = Modifier.fillMaxSize(),
                    completionAutoPopup = true,
                )
            }
        }

        // The device card: real Compose UI composed by the host, capped so a tall preview doesn't dominate.
        val shape = RoundedCornerShape(Ca.radius.lg)
        Box(
            Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 520.dp)
                .shadow(10.dp, shape).clip(shape)
                .background(Color.White).border(1.dp, Ca.colors.separator, shape).clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            if (!ready) {
                PreparingRow(indexing)
            } else {
                // The lesson card is always a clean LIGHT phone screen, so force a light Material theme + dark
                // content color around the rendered composable. Without this, the composed Text/Card inherit the
                // IDE's ambient theme (dark on Android) and render near-white — invisible on the white card.
                MaterialTheme(colorScheme = lightColorScheme()) {
                    CompositionLocalProvider(LocalContentColor provides Color(0xFF1C1B1F)) {
                        host.LessonPreview(
                            code = renderCode,
                            dark = false,
                            onProblems = { problems = it },
                            onBusy = { busy = it },
                            modifier = Modifier.fillMaxWidth().padding(4.dp),
                        )
                    }
                }
            }
            PreviewProblemChip(problems, Modifier.align(Alignment.TopStart).padding(Ca.spacing.s3))
        }

        val cap = caption.ifBlank { if (interactive) stringResource(Res.string.learn_compose_edit_hint) else "" }
        if (cap.isNotBlank()) {
            Text(cap, color = Ca.colors.textTertiary, style = Ca.type.caption, fontWeight = FontWeight.Medium)
        }
    }
}

/** The preparing state shown while the scratch resolves the Compose libraries (the one-time download) and
 *  builds its index; [indexing] flips the sub-line to "Indexing…" once the download is done. */
@Composable
private fun PreparingRow(indexing: Boolean) {
    Column(
        Modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(color = Ca.colors.accent, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
        Text(
            stringResource(Res.string.learn_compose_preparing),
            color = Ca.colors.textSecondary, style = Ca.type.footnote, fontWeight = FontWeight.Medium,
        )
        Text(
            stringResource(if (indexing) Res.string.learn_compose_indexing else Res.string.learn_compose_downloading),
            color = Ca.colors.textTertiary, style = Ca.type.caption2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * Wraps the real backend with a STANDALONE [EditorService] that routes completion to the Learn `kotlin-compose`
 * scratch (real Kotlin/Compose completions in the mini-editor) but leaves analysis empty — the lesson snippet's
 * `androidx.compose.ui.tooling.preview.Preview` import doesn't resolve against the desktop symbol jars, so
 * squiggling it would just nag; the live preview is the feedback that matters. Must be standalone (not a
 * delegate to `real.editor`) so it never hits the open project (there may be none on the Learn tab).
 */
private class ComposeLessonEditorBackend(private val real: IdeBackend) : IdeBackend by real {
    override val editor: EditorService = object : EditorService {
        override fun updateDocument(path: String, text: String) {}
        override fun saveFile(path: String, text: String) {}
        override suspend fun complete(path: String, text: String, offset: Int): UiCompletionResult =
            real.learn.complete("kotlin-compose", text, offset)
        override suspend fun analyze(path: String, text: String): List<UiDiagnostic> = emptyList()
    }
}
