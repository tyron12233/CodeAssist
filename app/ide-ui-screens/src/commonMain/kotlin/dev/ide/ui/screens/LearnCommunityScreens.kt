package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiLearnAnswer
import dev.ide.ui.backend.UiLearnAuthor
import dev.ide.ui.backend.UiLearnReportReason
import dev.ide.ui.backend.UiLearnSolution
import dev.ide.ui.backend.UiLearnSolutionPage
import dev.ide.ui.backend.UiLearnThread
import dev.ide.ui.backend.UiLearnThreadSummary
import dev.ide.ui.backend.UiLesson
import dev.ide.ui.backend.UiLessonStep
import dev.ide.ui.components.CodeSample
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.components.PillChip
import dev.ide.ui.generated.resources.*
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Symbol
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// =====================================================================================================
// Discussion: the questions asked about a lesson (or one of its exercises)
// =====================================================================================================

/**
 * A lesson's questions, newest activity first. Opened from the lesson (the banner's menu, the player's top
 * bar) already scoped to the exercise the learner is on, with a chip to widen it to the whole lesson.
 * Reading needs no account; asking does, and [onNeedSignIn] raises the sign-in sheet.
 */
@Composable
fun LearnDiscussionScreen(
    backend: IdeBackend,
    lessonId: String,
    stepId: String?,
    onBack: () -> Unit,
    onOpenThread: (threadId: String) -> Unit,
    onAsk: (stepId: String?) -> Unit,
    onNeedSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val signedIn = backend.store.authState().collectAsState().value.signedIn
    var scoped by remember(stepId) { mutableStateOf(stepId != null) }
    var reload by remember { mutableStateOf(0) }
    val lesson by produceState<UiLesson?>(null, lessonId) { value = runCatching { backend.learn.lesson(lessonId) }.getOrNull() }
    val threads by produceState<List<UiLearnThreadSummary>?>(null, lessonId, scoped, reload) {
        value = runCatching { backend.learn.threads(lessonId, if (scoped) stepId else null) }.getOrNull()
    }
    val stepTitle = lesson?.steps?.firstOrNull { it.id == stepId }?.title
    val now = remember(threads) { dev.ide.ui.platform.nowMillis() }

    ExpressiveScaffold(
        title = stringResource(Res.string.learn_discussion),
        onBack = onBack,
        modifier = modifier,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (signedIn) onAsk(if (scoped) stepId else null) else onNeedSignIn() },
                icon = { Icon(CaIcons.plus, null) },
                text = { Text(stringResource(Res.string.learn_ask_question)) },
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding(), bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item("lesson") {
                Text(lesson?.title.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (stepId != null && stepTitle != null) {
                item("scope") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { PillChip(label = stringResource(Res.string.learn_this_exercise), selected = scoped, onClick = { scoped = true }) }
                        item { PillChip(label = stringResource(Res.string.learn_whole_lesson), selected = !scoped, onClick = { scoped = false }) }
                    }
                }
            }
            val list = threads
            when {
                list == null -> item("error") { LoadError { reload++ } }
                list.isEmpty() -> item("empty") { EmptyNote(stringResource(Res.string.learn_no_questions)) }
                else -> items(list.size, key = { list[it].id }) { i ->
                    ThreadRow(list[i], now) { onOpenThread(list[i].id) }
                }
            }
        }
    }
}

@Composable
private fun ThreadRow(t: UiLearnThreadSummary, now: Long, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = c.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, c.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthorAvatar(t.author, 22.dp)
                Text(
                    listOfNotNull(if (t.mine) stringResource(Res.string.learn_you) else t.author.name, learnAge(t.lastActivityAtMs, now)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (t.accepted) Tag(stringResource(Res.string.learn_solved), c.primaryContainer, c.onPrimaryContainer)
                Tag(pluralStringResource(Res.plurals.learn_answers_n, t.answers, t.answers), c.surfaceContainerHighest, c.onSurfaceVariant)
            }
            t.stepTitle?.let { Text(stringResource(Res.string.learn_about_step, it), style = MaterialTheme.typography.labelSmall, color = c.outline) }
        }
    }
}

// =====================================================================================================
// Thread: one question and its answers
// =====================================================================================================

/**
 * One question: the asker's text, the code and error they attached, and the answers (the accepted one first,
 * then by votes). Code in an **answer** stays folded behind "Show code" until tapped, so browsing a thread
 * for a hint does not hand over the solution. The asker can mark the answer that solved it; anyone signed in
 * can upvote, answer, and report.
 */
@Composable
fun LearnThreadScreen(
    backend: IdeBackend,
    threadId: String,
    onBack: () -> Unit,
    onNeedSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val signedIn = backend.store.authState().collectAsState().value.signedIn
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val thread by produceState<UiLearnThread?>(null, threadId, reload) {
        value = runCatching { backend.learn.thread(threadId) }.getOrNull()
    }
    val loaded = remember(threadId, reload) { mutableStateOf(false) }
    LaunchedEffect(thread) { if (thread != null) loaded.value = true }
    var reporting by remember { mutableStateOf<Pair<String, String>?>(null) }
    val sentMsg = stringResource(Res.string.learn_report_sent)
    val now = remember(thread) { dev.ide.ui.platform.nowMillis() }

    fun act(block: suspend () -> String?) {
        scope.launch {
            val error = block()
            message = error
            if (error == null) reload++
        }
    }

    ExpressiveScaffold(
        title = stringResource(Res.string.learn_discussion),
        onBack = onBack,
        modifier = modifier,
        large = false,
        bottomBar = {
            val t = thread
            if (t != null) {
                AnswerComposer(
                    signedIn = signedIn,
                    onNeedSignIn = onNeedSignIn,
                    onPost = { body, code ->
                        val r = backend.learn.answer(t.id, body, code)
                        message = r.error
                        if (r.ok) reload++
                        r.ok
                    },
                )
            }
        },
    ) { padding ->
        val t = thread
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (t == null) {
                item("state") { if (loaded.value) LoadError { reload++ } else EmptyNote("…") }
                return@LazyColumn
            }
            message?.let { m -> item("msg") { Text(m, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
            item("question") {
                QuestionCard(
                    t, now,
                    onReport = { reporting = "thread" to t.id },
                    onDelete = if (t.mine) ({ act { backend.learn.deleteMine("thread", t.id) }; onBack() }) else null,
                )
            }
            item("answersHead") {
                Text(pluralStringResource(Res.plurals.learn_answers_n, t.answers.size, t.answers.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            }
            items(t.answers.size, key = { t.answers[it].id }) { i ->
                val a = t.answers[i]
                AnswerCard(
                    a, now,
                    canAccept = t.mine,
                    onVote = { if (!signedIn) onNeedSignIn() else act { backend.learn.vote(a.id, !a.votedByMe) } },
                    onAccept = { act { backend.learn.accept(t.id, if (a.accepted) null else a.id) } },
                    onReport = { if (!signedIn) onNeedSignIn() else reporting = "post" to a.id },
                    onDelete = if (a.mine) ({ act { backend.learn.deleteMine("post", a.id) } }) else null,
                )
            }
        }
    }

    reporting?.let { (kind, id) ->
        ReportDialog(
            onDismiss = { reporting = null },
            onSend = { reason ->
                reporting = null
                scope.launch { message = backend.learn.report(kind, id, reason, null) ?: sentMsg }
            },
        )
    }
}

@Composable
private fun QuestionCard(t: UiLearnThread, now: Long, onReport: () -> Unit, onDelete: (() -> Unit)?) {
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(t.title, style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuthorAvatar(t.author, 26.dp)
            Column(Modifier.weight(1f)) {
                Text(if (t.mine) stringResource(Res.string.learn_you) else t.author.name, style = MaterialTheme.typography.labelLarge)
                Text(
                    listOfNotNull(t.lessonTitle, t.stepTitle?.takeIf { it != t.lessonTitle }, learnAge(t.createdAtMs, now)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            ItemMenu(onReport = onReport.takeIf { !t.mine }, onDelete = onDelete)
        }
        if (t.body.isNotBlank()) Text(t.body, style = MaterialTheme.typography.bodyLarge)
        t.code?.let { code ->
            Text(stringResource(Res.string.learn_their_code), style = MaterialTheme.typography.labelMedium, color = c.outline)
            CodeSample(code, t.language ?: "kotlin")
        }
        t.errorText?.let { err ->
            Text(stringResource(Res.string.learn_error_label), style = MaterialTheme.typography.labelMedium, color = c.outline)
            Text(
                err,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = c.onErrorContainer,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.errorContainer).padding(12.dp),
            )
        }
    }
}

@Composable
private fun AnswerCard(
    a: UiLearnAnswer,
    now: Long,
    canAccept: Boolean,
    onVote: () -> Unit,
    onAccept: () -> Unit,
    onReport: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val c = MaterialTheme.colorScheme
    var codeShown by remember(a.id) { mutableStateOf(a.mine) }
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (a.accepted) c.primaryContainer.copy(alpha = 0.35f) else c.surfaceContainerLow)
            .border(1.dp, if (a.accepted) c.primary.copy(alpha = 0.6f) else c.outlineVariant, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuthorAvatar(a.author, 22.dp)
            Text(
                listOfNotNull(if (a.mine) stringResource(Res.string.learn_you) else a.author.name, learnAge(a.createdAtMs, now)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (a.accepted) Tag(stringResource(Res.string.learn_solved), c.primary, c.onPrimary)
            ItemMenu(onReport = onReport.takeIf { !a.mine }, onDelete = onDelete)
        }
        Text(a.body, style = MaterialTheme.typography.bodyLarge)
        a.code?.let { code ->
            if (codeShown) CodeSample(code)
            else Surface(
                onClick = { codeShown = true },
                shape = RoundedCornerShape(12.dp),
                color = c.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(CaIcons.eye, null, Modifier.size(18.dp), tint = c.primary)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.learn_show_code), style = MaterialTheme.typography.labelLarge, color = c.primary)
                        Text(stringResource(Res.string.learn_code_hidden), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(
                onClick = onVote,
                enabled = !a.mine,
                shape = CircleShape,
                color = if (a.votedByMe) c.primary else c.surfaceContainerHighest,
                contentColor = if (a.votedByMe) c.onPrimary else c.onSurface,
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(CaIcons.chevronUp, null, Modifier.size(16.dp))
                    Text(a.score.toString(), style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.weight(1f))
            if (canAccept) {
                TextButton(onClick = onAccept) {
                    Text(stringResource(if (a.accepted) Res.string.learn_unmark_solved else Res.string.learn_mark_solved))
                }
            }
        }
    }
}

/** The answer box pinned under a thread. Signed out it is one line that raises sign-in. */
@Composable
private fun AnswerComposer(signedIn: Boolean, onNeedSignIn: () -> Unit, onPost: suspend (body: String, code: String?) -> Boolean) {
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var body by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var withCode by remember { mutableStateOf(false) }
    var posting by remember { mutableStateOf(false) }
    Surface(color = c.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!signedIn) {
                FilledTonalButton(onClick = onNeedSignIn, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.learn_sign_in_to_post)) }
                return@Column
            }
            OutlinedTextField(
                value = body, onValueChange = { body = it },
                placeholder = { Text(stringResource(Res.string.learn_answer_hint)) },
                label = { Text(stringResource(Res.string.learn_your_answer)) },
                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
            )
            if (withCode) {
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    label = { Text(stringResource(Res.string.learn_add_code)) },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!withCode) TextButton(onClick = { withCode = true }) { Text(stringResource(Res.string.learn_add_code)) }
                Spacer(Modifier.weight(1f))
                PlayerButton(
                    stringResource(if (posting) Res.string.learn_posting else Res.string.learn_post),
                    primary = true, compact = true, enabled = !posting && body.isNotBlank(),
                ) {
                    posting = true
                    scope.launch {
                        if (onPost(body, code.takeIf { withCode })) { body = ""; code = ""; withCode = false }
                        posting = false
                    }
                }
            }
        }
    }
}

// =====================================================================================================
// Ask: the question form ("Ask for help" arrives with the learner's code and error filled in)
// =====================================================================================================

/**
 * The question form. Opened from "Ask for help" it arrives with [prefillCode] (the learner's buffer) and
 * [prefillError] (what the check said) attached, so helpers see exactly what was tried; both can be detached.
 */
@Composable
fun LearnAskScreen(
    backend: IdeBackend,
    lessonId: String,
    stepId: String?,
    prefillCode: String?,
    prefillError: String?,
    onBack: () -> Unit,
    onPosted: (threadId: String) -> Unit,
    modifier: Modifier = Modifier,
    /** Signed out, the Post button becomes a way to sign in: the form is reachable from "Ask for help". */
    onNeedSignIn: () -> Unit = {},
) {
    val signedIn = backend.store.authState().collectAsState().value.signedIn
    val scope = rememberCoroutineScope()
    val lesson by produceState<UiLesson?>(null, lessonId) { value = runCatching { backend.learn.lesson(lessonId) }.getOrNull() }
    val step = lesson?.steps?.firstOrNull { it.id == stepId }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var attachCode by remember { mutableStateOf(prefillCode != null) }
    var attachError by remember { mutableStateOf(prefillError != null) }
    var posting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ExpressiveScaffold(
        title = stringResource(Res.string.learn_ask_question),
        onBack = onBack,
        modifier = modifier,
        large = false,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    if (!signedIn) {
                        FilledTonalButton(onClick = onNeedSignIn, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(Res.string.learn_sign_in_to_post))
                        }
                        return@Row
                    }
                    PlayerButton(
                        stringResource(if (posting) Res.string.learn_posting else Res.string.learn_post),
                        primary = true,
                        enabled = !posting && title.trim().length >= 3,
                    ) {
                        posting = true
                        scope.launch {
                            val r = backend.learn.askQuestion(
                                lessonId, stepId, title, body,
                                code = prefillCode.takeIf { attachCode },
                                errorText = prefillError.takeIf { attachError },
                            )
                            posting = false
                            val newId = r.id
                            if (r.ok && newId != null) onPosted(newId) else error = r.error
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                listOfNotNull(lesson?.title, step?.title?.takeIf { it != lesson?.title }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            OutlinedTextField(
                value = title, onValueChange = { if (it.length <= 140) title = it },
                label = { Text(stringResource(Res.string.learn_question_title_label)) },
                placeholder = { Text(stringResource(Res.string.learn_question_title_hint)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = body, onValueChange = { if (it.length <= 4000) body = it },
                label = { Text(stringResource(Res.string.learn_question_body_label)) },
                placeholder = { Text(stringResource(Res.string.learn_question_body_hint)) },
                minLines = 3, modifier = Modifier.fillMaxWidth(),
            )
            if (prefillCode != null) {
                ToggleRow(stringResource(Res.string.learn_attach_code), attachCode) { attachCode = it }
                if (attachCode) CodeSample(prefillCode, (step as? UiLessonStep.Interactive)?.language ?: "kotlin")
            }
            if (prefillError != null) {
                ToggleRow(stringResource(Res.string.learn_attach_error), attachError) { attachError = it }
                if (attachError) {
                    Text(
                        prefillError,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// =====================================================================================================
// Solutions: how other learners solved an exercise, once you have
// =====================================================================================================

/**
 * Passing solutions to one exercise, top or newest first. Locked until the learner has passed it (the server
 * decides), so it cannot be used to skip the exercise. The "Share my solutions" switch is the opt-out for
 * the learner's own passes, which are shared by default.
 */
@Composable
fun LearnSolutionsScreen(
    backend: IdeBackend,
    lessonId: String,
    stepId: String,
    onBack: () -> Unit,
    onNeedSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val signedIn = backend.store.authState().collectAsState().value.signedIn
    val scope = rememberCoroutineScope()
    var sort by remember { mutableStateOf("top") }
    var reload by remember { mutableStateOf(0) }
    var share by remember { mutableStateOf(backend.learn.shareSolutions()) }
    var reporting by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val sentMsg = stringResource(Res.string.learn_report_sent)
    val lesson by produceState<UiLesson?>(null, lessonId) { value = runCatching { backend.learn.lesson(lessonId) }.getOrNull() }
    val page by produceState<UiLearnSolutionPage?>(null, lessonId, stepId, sort, reload, signedIn) {
        value = runCatching { backend.learn.solutions(lessonId, stepId, sort) }.getOrNull()
    }
    val now = remember(page) { dev.ide.ui.platform.nowMillis() }

    ExpressiveScaffold(title = stringResource(Res.string.learn_solutions), onBack = onBack, modifier = modifier) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding(), bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("head") {
                Text(
                    listOfNotNull(lesson?.title, lesson?.steps?.firstOrNull { it.id == stepId }?.title?.takeIf { it != lesson?.title }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (signedIn) {
                item("share") {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(Res.string.learn_share_solutions), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(Res.string.learn_share_solutions_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = share, onCheckedChange = { next ->
                                share = next
                                scope.launch { backend.learn.setShareSolutions(next)?.let { share = !next; message = it } }
                            })
                        }
                    }
                }
            }
            message?.let { m -> item("msg") { Text(m, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            val p = page
            when {
                p == null -> item("error") { LoadError { reload++ } }
                !p.unlocked -> item("locked") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        EmptyNote(stringResource(Res.string.learn_solutions_locked))
                        if (!signedIn) FilledTonalButton(onClick = onNeedSignIn) { Text(stringResource(Res.string.learn_sign_in)) }
                    }
                }
                else -> {
                    item("sort") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { PillChip(label = stringResource(Res.string.learn_sort_top), selected = sort == "top", onClick = { sort = "top" }) }
                            item { PillChip(label = stringResource(Res.string.learn_sort_new), selected = sort == "new", onClick = { sort = "new" }) }
                        }
                    }
                    if (p.solutions.isEmpty()) item("empty") { EmptyNote(stringResource(Res.string.learn_solutions_empty)) }
                    items(p.solutions.size, key = { p.solutions[it].id }) { i ->
                        val s = p.solutions[i]
                        SolutionCard(
                            s, now,
                            onLike = { scope.launch { backend.learn.likeSolution(s.id, !s.likedByMe)?.let { message = it } ?: reload++ } },
                            onReport = { reporting = s.id },
                            onDelete = if (s.mine) ({ scope.launch { backend.learn.deleteMine("solution", s.id); reload++ } }) else null,
                        )
                    }
                }
            }
        }
    }
    reporting?.let { id ->
        ReportDialog(onDismiss = { reporting = null }, onSend = { reason ->
            reporting = null
            scope.launch { message = backend.learn.report("solution", id, reason, null) ?: sentMsg }
        })
    }
}

@Composable
private fun SolutionCard(s: UiLearnSolution, now: Long, onLike: () -> Unit, onReport: () -> Unit, onDelete: (() -> Unit)?) {
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuthorAvatar(s.author, 24.dp)
            Text(
                listOfNotNull(if (s.mine) stringResource(Res.string.learn_you) else s.author.name, learnAge(s.createdAtMs, now)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            Surface(onClick = onLike, enabled = !s.mine, shape = CircleShape,
                color = if (s.likedByMe) c.primary else c.surfaceContainerHighest,
                contentColor = if (s.likedByMe) c.onPrimary else c.onSurface,
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Symbol(CaSymbols.star, contentDescription = null, size = 16.dp, filled = s.likedByMe)
                    Text(s.likes.toString(), style = MaterialTheme.typography.labelLarge)
                }
            }
            ItemMenu(onReport = onReport.takeIf { !s.mine }, onDelete = onDelete)
        }
        CodeSample(s.code, s.language)
    }
}

// =====================================================================================================
// Shared pieces
// =====================================================================================================

/** A reason picker for reporting; the reasons are the server's. */
@Composable
private fun ReportDialog(onDismiss: () -> Unit, onSend: (reason: String) -> Unit) {
    var picked by remember { mutableStateOf(UiLearnReportReason.SPAM) }
    val labels = mapOf(
        UiLearnReportReason.SPAM to stringResource(Res.string.learn_reason_spam),
        UiLearnReportReason.ABUSE to stringResource(Res.string.learn_reason_abuse),
        UiLearnReportReason.SPOILER to stringResource(Res.string.learn_reason_spoiler),
        UiLearnReportReason.WRONG to stringResource(Res.string.learn_reason_wrong),
        UiLearnReportReason.OTHER to stringResource(Res.string.learn_reason_other),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.learn_report_title)) },
        text = {
            Column {
                UiLearnReportReason.all.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { picked = r }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = picked == r, onClick = { picked = r })
                        Text(labels[r].orEmpty())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSend(picked) }) { Text(stringResource(Res.string.learn_report)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.learn_cancel)) } },
    )
}

@Composable
private fun ItemMenu(onReport: (() -> Unit)?, onDelete: (() -> Unit)?) {
    if (onReport == null && onDelete == null) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
            Symbol(CaSymbols.moreVert, contentDescription = stringResource(Res.string.learn_more_actions), size = 18.dp)
        }
        dev.ide.ui.components.CaDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            onReport?.let {
                dev.ide.ui.components.CaMenuItem(label = stringResource(Res.string.learn_report), icon = CaIcons.warning, onClick = { open = false; it() })
            }
            onDelete?.let {
                dev.ide.ui.components.CaMenuItem(label = stringResource(Res.string.learn_delete), icon = CaIcons.close, danger = true, onClick = { open = false; it() })
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Initials on a tinted circle: profile pictures are remote and optional, initials always render. */
@Composable
internal fun AuthorAvatar(author: UiLearnAuthor, size: androidx.compose.ui.unit.Dp) {
    val c = MaterialTheme.colorScheme
    val initial = author.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val palette = listOf(c.primaryContainer to c.onPrimaryContainer, c.secondaryContainer to c.onSecondaryContainer, c.tertiaryContainer to c.onTertiaryContainer)
    val (bg, fg) = palette[(author.handle ?: author.name).hashCode().mod(palette.size)]
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(initial, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Tag(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    Text(
        text, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
}

@Composable
private fun LoadError(onRetry: () -> Unit) {
    Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(Res.string.learn_couldnt_load), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = onRetry) { Text(stringResource(Res.string.learn_try_again)) }
    }
}

/** "3 d ago", from the notification strings. Null for a missing timestamp. */
@Composable
internal fun learnAge(ms: Long, now: Long): String? {
    if (ms <= 0L) return null
    val minutes = ((now - ms).coerceAtLeast(0)) / 60_000
    return when {
        minutes < 1 -> stringResource(Res.string.notif_just_now)
        minutes < 60 -> stringResource(Res.string.notif_minutes, minutes.toInt())
        minutes < 60 * 24 -> stringResource(Res.string.notif_hours, (minutes / 60).toInt())
        else -> stringResource(Res.string.notif_days, (minutes / (60 * 24)).toInt())
    }
}
