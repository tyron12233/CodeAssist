package dev.ide.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.agent.ui.generated.resources.Res
import dev.ide.agent.ui.generated.resources.aihome_action_explain
import dev.ide.agent.ui.generated.resources.aihome_action_fix
import dev.ide.agent.ui.generated.resources.aihome_action_screen
import dev.ide.agent.ui.generated.resources.aihome_build
import dev.ide.agent.ui.generated.resources.aihome_chats
import dev.ide.agent.ui.generated.resources.aihome_continue
import dev.ide.agent.ui.generated.resources.aihome_greeting
import dev.ide.agent.ui.generated.resources.aihome_greeting_name
import dev.ide.agent.ui.generated.resources.aihome_headline
import dev.ide.agent.ui.generated.resources.aihome_headline_project
import dev.ide.agent.ui.generated.resources.aihome_idea_converter
import dev.ide.agent.ui.generated.resources.aihome_idea_habit
import dev.ide.agent.ui.generated.resources.aihome_idea_quiz
import dev.ide.agent.ui.generated.resources.aihome_idea_tip
import dev.ide.agent.ui.generated.resources.aihome_idea_todo
import dev.ide.agent.ui.generated.resources.aihome_idea_weather
import dev.ide.agent.ui.generated.resources.aihome_new_app
import dev.ide.agent.ui.generated.resources.aihome_new_chat
import dev.ide.agent.ui.generated.resources.aihome_no_chats
import dev.ide.agent.ui.generated.resources.aihome_open_editor
import dev.ide.agent.ui.generated.resources.aihome_open_failed
import dev.ide.agent.ui.generated.resources.aihome_opening
import dev.ide.agent.ui.generated.resources.aihome_placeholder
import dev.ide.agent.ui.generated.resources.aihome_placeholder_project
import dev.ide.agent.ui.generated.resources.aihome_pick_title
import dev.ide.agent.ui.generated.resources.aihome_pick_new_sub
import dev.ide.agent.ui.generated.resources.aihome_pick_projects
import dev.ide.agent.ui.generated.resources.aihome_pick_search
import dev.ide.agent.ui.generated.resources.aihome_pick_none
import dev.ide.agent.ui.generated.resources.aihome_kind_android
import dev.ide.agent.ui.generated.resources.aihome_kind_project
import dev.ide.agent.ui.generated.resources.aihome_prompt_explain
import dev.ide.agent.ui.generated.resources.aihome_prompt_fix
import dev.ide.agent.ui.generated.resources.aihome_prompt_screen
import dev.ide.agent.ui.generated.resources.aihome_recent
import dev.ide.agent.ui.generated.resources.aihome_run
import dev.ide.agent.ui.generated.resources.aihome_time_days
import dev.ide.agent.ui.generated.resources.aihome_time_hours
import dev.ide.agent.ui.generated.resources.aihome_time_minutes
import dev.ide.agent.ui.generated.resources.aihome_time_now
import dev.ide.agent.ui.generated.resources.aihome_title
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.UiAgentRecentChat
import dev.ide.ui.backend.UiAgentRole
import dev.ide.ui.components.BottomSheet
import dev.ide.ui.components.CircularProgressIndicator
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.ext.HomeTabContext
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What the AI tab is showing. Held outside the composition so switching to another home tab and back returns to
 * the same place (the conversation keeps running in the backend either way).
 */
internal object AgentHomeState {
    var inChat by mutableStateOf(false)

    /** The project being opened in the background, by name, while it opens. */
    var opening by mutableStateOf<String?>(null)
    var draft by mutableStateOf("")
    var error by mutableStateOf<String?>(null)

    /** What the composer talks to: null for a new app, else a project's root. */
    var target by mutableStateOf<String?>(null)
    var drawerOpen by mutableStateOf(false)
    var pickerOpen by mutableStateOf(false)
}

/**
 * The AI home tab, laid out like a chat assistant's start page: a greeting, a row of suggestions, and one composer at
 * the bottom whose context chip says what it talks to (a new app, or one of the user's projects). Chats live in a
 * side drawer. Projects open in the background (the app does not jump to the editor while this tab is showing); Run
 * and Open in editor in the chat take the user into the project when they want.
 */
@Composable
internal fun AgentHomeTab(ctx: HomeTabContext) {
    val backend = ctx.backend
    val state = AgentHomeState
    if (state.inChat) {
        AgentHomeChat(ctx) { state.inChat = false }
    } else {
        AgentHomeLanding(ctx)
    }
    state.opening?.let { OpeningOverlay(it) }
    // Signed out of Free AI (or no key for the chosen provider): ask before starting rather than failing the turn.
    when (gate.value) {
        Gate.SignIn -> if (backend.agent.config().selectedProvider == FREE_AI_PROVIDER) {
            FreeAiSignInDialog(
                backend,
                onUseOwnKey = { gate.value = Gate.Providers },
                onDismiss = { if (gate.value == Gate.SignIn) gate.value = null },
            )
        } else {
            AgentProvidersSheet(backend) { gate.value = null }
        }
        Gate.Providers -> AgentProvidersSheet(backend) { gate.value = null }
        null -> Unit
    }
}

private enum class Gate { SignIn, Providers }

private val gate = mutableStateOf<Gate?>(null)

/** True when the agent can answer; otherwise opens the sign-in or key sheet and returns false. */
private fun ready(backend: IdeBackend): Boolean {
    if (backend.agent.config().configured) return true
    gate.value = Gate.SignIn
    return false
}

/** A suggestion card: what it says, its icon, and what tapping it does. */
private class Suggestion(val text: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
private fun AgentHomeLanding(ctx: HomeTabContext) {
    val backend = ctx.backend
    val state = AgentHomeState
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val chat by backend.agent.chatState.collectAsState()
    val epoch by backend.projects.projectEpoch.collectAsState()
    val auth by backend.store.authState().collectAsState()
    var projects by remember { mutableStateOf<List<ProjectInfo>>(emptyList()) }
    var recent by remember { mutableStateOf<List<UiAgentRecentChat>>(emptyList()) }
    LaunchedEffect(epoch, state.drawerOpen) {
        withContext(Dispatchers.Default) {
            projects = runCatching { backend.projects.projects() }.getOrDefault(emptyList()).sortedByDescending { it.lastOpened }
            recent = backend.agent.recentChats()
        }
    }
    val openFailed = stringResource(Res.string.aihome_open_failed)
    val target = projects.firstOrNull { it.rootPath == state.target }

    /** Opens [project] in the background (unless it is open already), then starts or resumes a chat about it. */
    fun chatAbout(project: ProjectInfo, prompt: String? = null, resume: String? = null) {
        if (prompt != null && !ready(backend)) return
        scope.launch {
            state.error = null
            val alreadyOpen = epoch > 0 && backend.project.rootPath == project.rootPath
            if (!alreadyOpen) {
                state.opening = project.name
                val ok = runCatching { backend.projects.openProject(project.rootPath) }.getOrDefault(false)
                state.opening = null
                if (!ok) {
                    state.error = openFailed
                    return@launch
                }
            }
            // What the editor does when it opens a project: resolve its declared libraries (a project whose
            // template dependencies were never resolved compiles with nothing but the Android SDK otherwise).
            runCatching { backend.deps.startPendingDependencyResolution() }
            if (resume != null) backend.agent.resumeSession(resume) else backend.agent.newSession()
            state.drawerOpen = false
            state.inChat = true
            if (prompt != null) backend.agent.send(prompt)
        }
    }

    fun submit() {
        val text = state.draft.trim()
        if (text.isEmpty()) return
        if (target != null) {
            if (!ready(backend)) return
            state.draft = ""
            chatAbout(target, prompt = text)
            return
        }
        if (!ready(backend)) return
        backend.agent.startNewApp()
        backend.agent.send(text)
        state.draft = ""
        state.inChat = true
    }

    val suggestions: List<Suggestion> = if (target == null) {
        listOf(
            Res.string.aihome_idea_tip, Res.string.aihome_idea_quiz, Res.string.aihome_idea_todo,
            Res.string.aihome_idea_habit, Res.string.aihome_idea_weather, Res.string.aihome_idea_converter,
        ).map { res -> stringResource(res).let { text -> Suggestion(text, CaIcons.sparkle) { state.draft = text } } }
    } else {
        listOf(
            Triple(Res.string.aihome_action_explain, Res.string.aihome_prompt_explain, CaIcons.info),
            Triple(Res.string.aihome_action_fix, Res.string.aihome_prompt_fix, CaIcons.warning),
            Triple(Res.string.aihome_action_screen, Res.string.aihome_prompt_screen, CaIcons.plus),
        ).map { (label, prompt, icon) ->
            Suggestion(stringResource(label), icon) { scope.launch { chatAbout(target, prompt = getString(prompt)) } }
        }
    }
    val firstName = auth.account?.displayName?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }

    Box(Modifier.fillMaxSize().background(scheme.surface)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(onMenu = { state.drawerOpen = true })
            // The greeting sits in the middle of the free space; the suggestions sit just above the composer.
            Column(Modifier.weight(1f).fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Column(
                        Modifier.align(Alignment.CenterHorizontally).widthIn(max = CONTENT_WIDTH).fillMaxWidth().padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            if (firstName != null) stringResource(Res.string.aihome_greeting_name, firstName) else stringResource(Res.string.aihome_greeting),
                            style = MaterialTheme.typography.headlineMedium.copy(
                                brush = Brush.linearGradient(listOf(scheme.primary, scheme.tertiary)),
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                        Text(
                            if (target != null) stringResource(Res.string.aihome_headline_project, target.name) else stringResource(Res.string.aihome_headline),
                            color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        state.error?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(
                        Modifier.align(Alignment.CenterHorizontally).widthIn(max = CONTENT_WIDTH).fillMaxWidth()
                            .horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        suggestions.forEach { SuggestionCard(it) }
                }
            }
            Composer(
                value = state.draft,
                onChange = { state.draft = it },
                placeholder = if (target != null) stringResource(Res.string.aihome_placeholder_project, target.name) else stringResource(Res.string.aihome_placeholder),
                contextChip = { ContextChip(target) { state.pickerOpen = true } },
                onSubmit = ::submit,
            )
        }
        ProjectPickerSheet(
            visible = state.pickerOpen,
            target = target,
            projects = projects,
            onPick = { picked ->
                state.target = picked?.rootPath
                state.pickerOpen = false
            },
            onDismiss = { state.pickerOpen = false },
        )
        ChatsDrawer(
            open = state.drawerOpen,
            current = chat.messages.firstOrNull { it.role == UiAgentRole.USER }?.text,
            recent = recent,
            projects = projects,
            onClose = { state.drawerOpen = false },
            onNewChat = {
                state.drawerOpen = false
                state.target = null
                state.draft = ""
            },
            onContinue = {
                state.drawerOpen = false
                state.inChat = true
            },
            onOpenChat = { c -> projects.firstOrNull { it.rootPath == c.projectRoot }?.let { chatAbout(it, resume = c.sessionId) } },
        )
    }
}

private val CONTENT_WIDTH = 720.dp

@Composable
private fun TopBar(onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconButtonCa(CaIcons.sidebar, stringResource(Res.string.aihome_chats), onMenu, iconSize = 20, boxSize = 40)
        Text(
            stringResource(Res.string.aihome_title), color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SuggestionCard(s: Suggestion) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.width(150.dp).height(112.dp).clip(RoundedCornerShape(20.dp)).background(scheme.surfaceContainerHigh)
            .clickable(onClick = s.onClick).padding(14.dp),
    ) {
        Text(
            s.text, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium,
            maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Box(
            Modifier.align(Alignment.End).size(30.dp).clip(CircleShape).background(scheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(s.icon, null, Modifier.size(15.dp), tint = scheme.primary)
        }
    }
}

/** The one input, pinned at the bottom: the text, what it talks to, and send. */
@Composable
private fun Composer(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    contextChip: @Composable () -> Unit,
    onSubmit: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val canSend = value.isNotBlank()
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = CONTENT_WIDTH).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(scheme.surfaceContainerHigh)
                .padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(end = 10.dp).heightIn(min = 24.dp)) {
                if (value.isEmpty()) {
                    Text(placeholder, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                contextChip()
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .background(if (canSend) scheme.primary else scheme.surfaceContainerHighest)
                        .clickable(enabled = canSend, onClick = onSubmit),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        CaIcons.arrowRight, stringResource(Res.string.aihome_build), Modifier.size(18.dp),
                        tint = if (canSend) scheme.onPrimary else scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** What the composer talks to, as a chip in it; tapping opens [ProjectPickerSheet]. */
@Composable
private fun ContextChip(target: ProjectInfo?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.clip(RoundedCornerShape(Ca.radius.pill)).background(scheme.surfaceContainerHighest)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(if (target == null) CaIcons.sparkle else projectIcon(target), null, Modifier.size(14.dp), tint = scheme.primary)
        Text(
            target?.name ?: stringResource(Res.string.aihome_new_app), color = scheme.onSurface,
            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
        Icon(CaIcons.chevronDown, null, Modifier.size(12.dp), tint = scheme.onSurfaceVariant)
    }
}

private fun projectIcon(p: ProjectInfo): ImageVector = if (p.isAndroid) CaIcons.androidLogo else CaIcons.folder

/**
 * Choose what the composer talks to: a new app, or one of the user's projects, most recently opened first. Each
 * project says what kind it is and when it was last opened, which also tells apart two projects with the same name;
 * a search field appears once the list is long.
 */
@Composable
private fun ProjectPickerSheet(
    visible: Boolean,
    target: ProjectInfo?,
    projects: List<ProjectInfo>,
    onPick: (ProjectInfo?) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    if (visible) dev.ide.ui.platform.PlatformBackHandler(enabled = true, onBack = onDismiss)
    LaunchedEffect(visible) { if (!visible) query = "" }
    BottomSheet(visible = visible, onDismiss = onDismiss, heightFraction = 0.72f) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(Res.string.aihome_pick_title), color = scheme.onSurface, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 8.dp),
            )
            PickerRow(
                icon = CaIcons.sparkle,
                title = stringResource(Res.string.aihome_new_app),
                subtitle = stringResource(Res.string.aihome_pick_new_sub),
                selected = target == null,
                accent = true,
            ) { onPick(null) }
            Text(
                stringResource(Res.string.aihome_pick_projects), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 2.dp),
            )
            if (projects.size > SEARCH_FROM) {
                SearchField(query, onChange = { query = it })
            }
        }
        val shown = projects.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (shown.isEmpty()) {
                item {
                    Text(
                        stringResource(Res.string.aihome_pick_none), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                    )
                }
            }
            items(shown, key = { it.rootPath }) { p ->
                val kind = stringResource(if (p.isAndroid) Res.string.aihome_kind_android else Res.string.aihome_kind_project)
                PickerRow(
                    icon = projectIcon(p),
                    title = p.name,
                    subtitle = if (p.lastOpened > 0) "$kind · ${relativeTime(p.lastOpened)}" else kind,
                    selected = target?.rootPath == p.rootPath,
                ) { onPick(p) }
            }
        }
    }
}

@Composable
private fun PickerRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (selected) scheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                .background(if (accent) scheme.primaryContainer else scheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = if (accent) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title, color = if (selected) scheme.onSecondaryContainer else scheme.onSurface, style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle, color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) Icon(CaIcons.check, null, Modifier.size(18.dp), tint = scheme.onSecondaryContainer)
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(Ca.radius.pill)).background(scheme.surfaceContainerHighest)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(CaIcons.search, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(stringResource(Res.string.aihome_pick_search), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val SEARCH_FROM = 6

/** The side drawer: start a new chat, carry on the current one, or reopen a recent one from any project. */
@Composable
private fun ChatsDrawer(
    open: Boolean,
    current: String?,
    recent: List<UiAgentRecentChat>,
    projects: List<ProjectInfo>,
    onClose: () -> Unit,
    onNewChat: () -> Unit,
    onContinue: () -> Unit,
    onOpenChat: (UiAgentRecentChat) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    if (open) dev.ide.ui.platform.PlatformBackHandler(enabled = true, onBack = onClose)
    AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(scheme.scrim.copy(alpha = 0.4f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
    }
    AnimatedVisibility(open, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
        val byRoot = projects.associateBy { it.rootPath }
        Column(
            Modifier.fillMaxHeight().widthIn(max = 320.dp).fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)).background(scheme.surfaceContainerLow)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(Res.string.aihome_chats), color = scheme.onSurface, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 16.dp),
            )
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.pill)).background(scheme.secondaryContainer)
                    .clickable(onClick = onNewChat).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(CaIcons.plus, null, Modifier.size(16.dp), tint = scheme.onSecondaryContainer)
                Text(stringResource(Res.string.aihome_new_chat), color = scheme.onSecondaryContainer, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (current != null) {
                    DrawerLabel(stringResource(Res.string.aihome_continue))
                    DrawerRow(current, null, onContinue)
                }
                DrawerLabel(stringResource(Res.string.aihome_recent))
                val chats = recent.filter { it.projectRoot in byRoot }.take(MAX_RECENT)
                if (chats.isEmpty()) {
                    Text(
                        stringResource(Res.string.aihome_no_chats), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                chats.forEach { c ->
                    DrawerRow(c.title, "${byRoot.getValue(c.projectRoot).name} · ${relativeTime(c.updatedAtMs)}") { onOpenChat(c) }
                }
            }
        }
    }
}

@Composable
private fun DrawerLabel(text: String) {
    Text(
        text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun DrawerRow(title: String, subtitle: String?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.pill)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(title, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun relativeTime(atMs: Long): String {
    val minutes = ((System.currentTimeMillis() - atMs) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> stringResource(Res.string.aihome_time_now)
        minutes < 60 -> minutes.toInt().let { n -> pluralStringResource(Res.plurals.aihome_time_minutes, n, n) }
        minutes < 60 * 24 -> (minutes / 60).toInt().let { n -> pluralStringResource(Res.plurals.aihome_time_hours, n, n) }
        else -> (minutes / (60 * 24)).toInt().let { n -> pluralStringResource(Res.plurals.aihome_time_days, n, n) }
    }
}

/** The conversation, full screen: the chat with a back arrow, the project's name, and Run / Open in editor. */
@Composable
private fun AgentHomeChat(ctx: HomeTabContext, onBack: () -> Unit) {
    val backend = ctx.backend
    val chat by backend.agent.chatState.collectAsState()
    val epoch by backend.projects.projectEpoch.collectAsState()
    val inProject = !chat.creatingApp && epoch > 0
    val title = if (inProject) backend.project.name else stringResource(Res.string.aihome_new_app)
    // System back returns to the tab's start page rather than leaving the tab (innermost handler wins).
    dev.ide.ui.platform.PlatformBackHandler(enabled = true, onBack = onBack)
    ChatDrawer(
        backend,
        fileActions = ctx.fileActions,
        onOpenFile = { ctx.openFile(it) },
        onBack = onBack,
        title = title,
        headerActions = if (inProject) {
            {
                val run = ctx.runProject
                if (run != null && !chat.busy) IconButtonCa(CaIcons.play, stringResource(Res.string.aihome_run), run, iconSize = 16, boxSize = 30)
                IconButtonCa(CaIcons.code, stringResource(Res.string.aihome_open_editor), ctx::openEditor, iconSize = 16, boxSize = 30)
            }
        } else null,
    )
}

@Composable
private fun OpeningOverlay(name: String) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(scheme.scrim.copy(alpha = 0.32f)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(RoundedCornerShape(Ca.radius.lg)).background(scheme.surfaceContainerHigh).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(20.dp))
            Text(stringResource(Res.string.aihome_opening, name), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private const val MAX_RECENT = 20
