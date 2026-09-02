package dev.ide.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.VcsService
import dev.ide.ui.components.BetaInfo
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.settings_title
import dev.ide.ui.ext.ScreenContext
import dev.ide.ui.ext.ScreenRegistry
import dev.ide.ui.navigation.ScreenHost
import dev.ide.ui.screens.NotificationBell
import dev.ide.ui.screens.NotificationsSheet
import dev.ide.ui.screens.PublisherProfileScreen
import dev.ide.ui.screens.PublishingGuideScreen
import dev.ide.ui.screens.StoreSignInSheet
import dev.ide.ui.screens.SubmitProjectScreen
import dev.ide.ui.screens.CodeStyleScreen
import dev.ide.ui.screens.CreateProjectScreen
import dev.ide.ui.screens.EditorScreen
import dev.ide.ui.screens.ExportProjectScreen
import dev.ide.ui.screens.HomeScreen
import dev.ide.ui.screens.ImportPreviewScreen
import dev.ide.ui.screens.KeystoreCreateScreen
import dev.ide.ui.screens.KeystoreImportScreen
import dev.ide.ui.backend.IconSnippets
import dev.ide.ui.backend.UiInsertionTarget
import dev.ide.ui.screens.AppIconStudioScreen
import dev.ide.ui.screens.IconManagerScreen
import dev.ide.ui.screens.KeystoreManagerScreen
import dev.ide.ui.screens.LearnScreen
import dev.ide.ui.screens.LessonPlayerScreen
import dev.ide.ui.screens.LessonTrackScreen
import dev.ide.ui.screens.ModuleConfigScreen
import dev.ide.ui.screens.ModulesTab
import dev.ide.ui.screens.PluginsScreen
import dev.ide.ui.screens.ProjectsHomeScreen
import dev.ide.ui.screens.ExploreFeed
import dev.ide.ui.screens.ProjectsStoreScreen
import dev.ide.ui.screens.RunScreen
import dev.ide.ui.screens.SdkManagerScreen
import dev.ide.ui.screens.SettingsHubScreen
import dev.ide.ui.screens.SettingsScreen
import dev.ide.ui.screens.SettingsView
import dev.ide.ui.screens.StorageScreen
import dev.ide.ui.screens.StoreItemScreen
import dev.ide.ui.screens.SymbolMacroEditorScreen
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch

/**
 * Routes the current [CodeAssistAppState.screen] to its screen composable, animating between them with
 * [ScreenHost]. Every destination reads state from [app] and reports back through its intent functions, so
 * this layer only wires arguments and callbacks.
 *
 * @param state the active project's UI state, re-keyed per project by the caller.
 * @param dark whether the resolved theme is dark (for the editor's quick theme toggle).
 */
@Composable
internal fun AppNavGraph(
    app: CodeAssistAppState,
    state: IdeUiState,
    fileActions: FileActions,
    codeFont: FontFamily,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val backend = app.backend
    val scope = rememberCoroutineScope()
    ScreenHost(app.screen, modifier) { s ->
        when (s) {
            Screen.Projects -> HomeRoute(app, fileActions)

            Screen.CreateProject -> CreateProjectScreen(
                backend = backend,
                onCancel = { app.navigateTo(Screen.Projects) },
                onCreated = { app.navigateTo(Screen.Editor) },
                initialTemplateId = app.createTemplateId,
            )

            Screen.ImportProject -> {
                val preview = app.importPreview
                val path = app.importArchivePath
                if (preview != null && path != null) {
                    ImportPreviewScreen(
                        backend = backend,
                        archivePath = path,
                        preview = preview,
                        onCancel = app::cancelImportPreview,
                        onImported = app::finishImportPreview,
                    )
                }
            }

            Screen.ExportProject -> {
                val target = app.exportTarget
                if (target != null) {
                    ExportProjectScreen(
                        backend = backend,
                        project = target,
                        fileActions = fileActions,
                        initialAuthor = app.exportAuthor(),
                        onAuthorRemembered = app::rememberExportAuthor,
                        onReveal = if (fileActions.canReveal) ({ path -> fileActions.reveal(path) }) else null,
                        onSaveCopy = if (fileActions.canExport) ({ path -> fileActions.exportFile(path) }) else null,
                        onShare = if (fileActions.canShare) ({ path -> fileActions.share(path) }) else null,
                        onDone = app::finishExport,
                        // The other way to share the same project. Hidden when this build cannot publish.
                        onPublish = if (backend.store.submissionsAvailable()) {
                            // `target` is the project this export flow was opened for, so publishing
                            // continues with it rather than asking again.
                            { app.finishExport(); app.openSubmitProject(target) }
                        } else {
                            null
                        },
                    )
                }
            }

            Screen.LessonTrack -> LessonTrackScreen(
                backend = backend,
                trackId = app.currentTrackId,
                epoch = app.learnEpoch,
                onOpenLesson = { id -> app.openLesson(id) },
                onBack = app::exitLessonTrack,
            )

            Screen.LessonPlayer -> LessonPlayerScreen(
                backend = backend,
                lessonId = app.currentLessonId,
                initialStep = app.lessonInitialStep,
                inlayHintsEnabled = state.inlayHintsEnabled,
                host = state.composePreviewHost,
                onExit = app::exitLessonPlayer,
            )

            Screen.PublisherProfile -> PublisherProfileScreen(
                backend = backend,
                handle = app.publisherHandle.orEmpty(),
                onBack = { app.navigateTo(Screen.Projects) },
                onOpenItem = app::openStoreItem,
                // Following needs an account; null would hide the button, which is not the same as it
                // being unavailable, so it asks instead.
                onNeedSignIn = { app.navigateTo(Screen.Projects) },
            )

            Screen.PublishingGuide -> PublishingGuideScreen(
                onBack = { app.navigateTo(Screen.Projects) },
                onPublish = if (backend.store.submissionsAvailable()) ({ app.openSubmitProject() }) else null,
            )

            Screen.SubmitProject -> SubmitProjectScreen(
                backend = backend,
                initialProject = app.submitProject,
                onBack = { app.navigateTo(Screen.Projects) },
                // Back to Explore on success: the submission now shows there as "under review", which is
                // the only place its state is visible.
                onSubmitted = { app.navigateTo(Screen.Projects) },
            )

            Screen.StoreItem -> {
                val storeScope = rememberCoroutineScope()
                // The bookmark IS the like now: saving a project is the signal that you rate it, so the
                // store counts saves publicly rather than asking twice for one opinion. Server-backed, with
                // the device list as the offline answer (see StoreLikes), which is why a reinstall or a
                // second device keeps the list.
                var saved by remember(app.storeItem?.id) {
                    mutableStateOf(app.storeItem?.let { backend.store.isLiked(it.id) } == true)
                }
                StoreItemScreen(
                    backend = backend,
                    item = app.storeItem,
                    onBack = { app.navigateTo(Screen.Projects) },
                    onCreateFromTemplate = { id -> app.createProject(id) },
                    isSaved = saved,
                    onToggleSaved = app.storeItem?.let { current ->
                        {
                            val next = !saved
                            // Flip first: the local write is what makes the tap feel like it landed, and a
                            // refusal puts it back rather than leaving the button lying.
                            saved = next
                            storeScope.launch {
                                if (backend.store.setLike(current.id, next) != null) saved = !next
                            }
                        }
                    },
                    // Signing in to review needs a browser; null on a host that cannot open one, which the
                    // sheet reports rather than working around.
                    onOpenUrl = if (fileActions.canOpenUrl) ({ url: String -> fileActions.openUrl(url) }) else null,
                    onOpenPublisher = { handle -> app.openPublisher(handle) },
                )
            }

            Screen.Editor -> EditorScreen(
                state = state,
                onToggleTheme = { app.toggleTheme(dark) },
                onOpenHub = { app.openHub(Screen.Editor) },
                onOpenIconManager = { app.openIconManager(Screen.Editor) },
                onNewImageAsset = { resDir -> app.openIconManager(Screen.Editor, resDir) },
                onOpenDependencies = { module -> app.openModuleConfig(module, ModulesTab.Dependencies) },
                onOpenModuleConfig = { module -> app.openModuleConfig(module, ModulesTab.Settings) },
                onCloseProject = { app.navigateTo(Screen.Projects) },
                onOpenRun = { app.navigateTo(Screen.Run) },
                fileActions = fileActions,
            )

            Screen.Run -> RunScreen(
                backend = state.backend,
                onBack = { app.navigateTo(Screen.Editor) },
                onOpenDiagnostic = { d ->
                    d.file?.let {
                        state.openAtLine(it, d.line, d.column)
                        app.navigateTo(Screen.Editor)
                    }
                },
            )

            Screen.ModuleConfig -> ModuleConfigScreen(
                backend = state.backend,
                initialModule = app.configModule,
                initialTab = app.modulesTab,
                onBack = { app.navigateTo(Screen.Editor) },
                onOpenKeystoreManager = { app.openKeystoreManager(Screen.ModuleConfig, inProject = true) },
                codeFont = codeFont,
                fileActions = fileActions,
            )

            Screen.SdkManager -> SdkManagerScreen(
                backend = state.backend,
                onBack = { app.navigateTo(Screen.Hub) },
            )

            // A plugin-contributed screen (ScreenRegistry). The route is generic: the contribution renders its
            // own body against the backend, the host bridges, and a Back that pops to wherever it came from.
            Screen.PluginScreen -> {
                // Hold the id this instance is showing rather than reading it live. Navigating away clears
                // `pluginScreenId` while [ScreenHost]'s AnimatedContent still composes this branch for the
                // exit animation, so reading it live would blank the screen mid-transition. It follows a
                // change to another plugin screen (a diff opening that file's history), which stays on this
                // same destination and so reuses this instance.
                var shown by remember { mutableStateOf(app.pluginScreenId) }
                LaunchedEffect(app.pluginScreenId) {
                    app.pluginScreenId?.let { shown = it }
                }
                val contribution = shown?.let { ScreenRegistry.find(it) }
                if (contribution != null) {
                    val openInEditor = LocalPluginFileOpener.current
                    val screenCtx = remember(backend, fileActions, app, openInEditor) {
                        object : ScreenContext {
                            override val backend = backend
                            override val fileActions = fileActions
                            override fun back() = app.navigateBack()
                            override fun openScreen(id: String) = app.openPluginScreen(id)
                            override fun openFile(path: String, offset: Int) = openInEditor(path, offset)
                        }
                    }
                    contribution.content(screenCtx)
                }
            }

            Screen.Plugins -> PluginsScreen(
                backend = state.backend,
                onBack = { app.navigateTo(Screen.Hub) },
                // The Logs viewer is an editor overlay, so the row offers it only with a project open.
                onOpenLogs = if (app.epoch > 0) {
                    { pluginId ->
                        state.logsSource = pluginId
                        state.logsOpen = true
                        app.navigateTo(Screen.Editor)
                    }
                } else null,
            )

            Screen.Storage -> StorageScreen(
                backend = state.backend,
                onBack = { app.navigateTo(Screen.Hub) },
            )

            Screen.CodeStyle -> CodeStyleScreen(
                backend = state.backend,
                // The live formatter preview is engine-backed: available when the hub (hence Code Style) was
                // opened from the editor, not from the project picker.
                hasProject = app.hubReturn == Screen.Editor,
                onBack = { app.navigateTo(Screen.Hub) },
            )

            Screen.EditorSymbols -> SymbolMacroEditorScreen(
                state = state,
                onBack = { app.navigateTo(Screen.Hub) },
            )

            Screen.KeystoreManager -> KeystoreManagerScreen(
                backend = state.backend,
                onBack = { app.navigateTo(app.keystoreReturn) },
                onCreate = { app.navigateTo(Screen.KeystoreCreate) },
                onImport = app::openKeystoreImport,
                // Signing assignment is per-project: only offered when the manager was opened from a project
                // context (the editor's hub or a module's Signing tab), never from the picker's hub. So it stays
                // hidden with no project open and cannot navigate into one.
                onManageSigning = if (app.keystoreInProject) app::openSigningAssignment else null,
                fileActions = fileActions,
            )

            Screen.IconManager -> {
                val tab = state.active
                // The reference form depends on the buffer, not just the file name (a Compose file wants a
                // painter, a plain one wants `R.`), so the target is computed from the live text once on
                // entry rather than per frame: materialising the rope is not free.
                val insertionTarget = remember(tab?.path) {
                    tab?.let { UiInsertionTarget(it.path, composeContext = IconSnippets.looksLikeCompose(it.text)) }
                }
                IconManagerScreen(
                    backend = state.backend,
                    onBack = { app.navigateTo(app.iconManagerReturn) },
                    onOpenAppIconStudio = { repoId, name -> app.openAppIconStudio(repoId, name) },
                    // Offered only when there is an editor tab to write into; the edits drive that tab's own
                    // session, then the user is handed back to it so they see the result in place.
                    onInsert = if (tab == null) null else { ref ->
                        scope.launch {
                            val edits = state.backend.icons.iconInsertion(
                                path = tab.path,
                                text = tab.text,
                                caret = tab.session.selection.min,
                                ref = ref,
                            )
                            if (state.applyEdits(edits)) app.navigateTo(Screen.Editor)
                        }
                    },
                    insertionTarget = insertionTarget,
                    initialResDir = app.iconManagerResDir,
                    fileActions = fileActions,
                )
            }

            Screen.AppIconStudio -> AppIconStudioScreen(
                backend = state.backend,
                onBack = { app.navigateTo(Screen.IconManager) },
                onChooseIcon = { app.navigateTo(Screen.IconManager) },
                seedRepoId = app.appIconSeedRepoId,
                seedIconName = app.appIconSeedName,
                fileActions = fileActions,
            )

            Screen.KeystoreCreate -> KeystoreCreateScreen(
                backend = state.backend,
                onBack = { app.navigateTo(Screen.KeystoreManager) },
                onDone = { app.navigateTo(Screen.KeystoreManager) },
            )

            Screen.KeystoreImport -> {
                val path = app.keystoreImportPath
                if (path == null) {
                    app.navigateTo(Screen.KeystoreManager)
                } else {
                    KeystoreImportScreen(
                        backend = state.backend,
                        path = path,
                        onBack = { app.navigateTo(Screen.KeystoreManager) },
                        onDone = { app.navigateTo(Screen.KeystoreManager) },
                    )
                }
            }

            Screen.Hub -> SettingsHubScreen(
                onBack = { app.navigateTo(app.hubReturn) },
                onOpenGlobalSettings = { app.navigateTo(Screen.Settings) },
                onOpenCodeStyle = { app.navigateTo(Screen.CodeStyle) },
                onOpenSymbols = { app.navigateTo(Screen.EditorSymbols) },
                onOpenSdkManager = { app.navigateTo(Screen.SdkManager) },
                onOpenKeystoreManager = app::openKeystoreManagerFromHub,
                onOpenPlugins = { app.navigateTo(Screen.Plugins) },
                onOpenStorage = { app.navigateTo(Screen.Storage) },
            )

            // Settings, reached from the hub. With a project open (hub entered from the editor) the
            // project-scoped pages (dependency conflicts, inspections) merge in; from the picker only the
            // global app pages show.
            Screen.Settings -> SettingsScreen(
                backend = state.backend,
                onBack = { app.navigateTo(Screen.Hub) },
                onSettingsChanged = app::reloadSettings,
                // The logs viewer is an editor overlay; only meaningful with a project open.
                onOpenLogs = {
                    if (app.epoch > 0) {
                        state.logsOpen = true
                        app.navigateTo(Screen.Editor)
                    }
                },
                view = if (app.hubReturn == Screen.Editor) SettingsView.All else SettingsView.Global,
                title = stringResource(Res.string.settings_title),
                codeFont = codeFont,
                fileActions = fileActions,
            )
        }
    }
}

/**
 * The landing surface: the project manager on its own, or (behind the Projects Store flag) that manager as
 * one tab of [HomeScreen] alongside the store and Learn.
 */
@Composable
private fun HomeRoute(app: CodeAssistAppState, fileActions: FileActions) {
    val projects = remember(app.epoch, app.projectsRefresh) { app.backend.projects.projects() }
    if (!app.storeEnabled) {
        ProjectPickerRoute(app, fileActions, projects)
        return
    }
    HomeScreen(
        tab = app.homeTab,
        onSelectTab = app::selectHomeTab,
        projectsContent = { ProjectPickerRoute(app, fileActions, projects) },
        storeContent = { StoreRoute(app, fileActions) },
        learnContent = { LearnRoute(app, fileActions) },
    )
}

@Composable
private fun ProjectPickerRoute(
    app: CodeAssistAppState,
    fileActions: FileActions,
    projects: List<ProjectInfo>,
) {
    val backend = app.backend
    var notificationsVisible by remember { mutableStateOf(false) }
    ProjectsHomeScreen(
        bell = { NotificationBell(backend, onClick = { notificationsVisible = true }) },
        projects = projects,
        onOpen = app::openProject,
        // Straight to the full-screen Create-Project gallery, which owns the template picker.
        onNewProject = { app.createProject() },
        onDeleteProject = app::deleteProject,
        // One "Import project" entry for both sources; it asks which when the host can do both.
        onImportProject = if (fileActions.canPickDirectory || fileActions.canPickFile) app::requestProjectImport else null,
        // Cloning is the other way into a project, and it is the only one a user with no projects yet can
        // reach: the Git panel lives in the editor, which needs a project open. Hidden when the version-control
        // plugin is disabled or this build carries no engine.
        onCloneRepository = if (app.backend.vcs.supported()) {
            { app.openPluginScreen(VcsService.SCREEN_CLONE) }
        } else null,
        onExportProject = if (fileActions.canShare || fileActions.canExport || fileActions.canReveal) {
            app::startExport
        } else null,
        onBackup = app::backupProjects,
        onOpenHub = { app.openHub(Screen.Projects) },
        onSubmitSuggestions = if (fileActions.canOpenUrl) {
            { fileActions.openUrl(BetaInfo.FEEDBACK_URL) }
        } else null,
        onJoinDiscord = if (fileActions.canOpenUrl) {
            { fileActions.openUrl(BetaInfo.DISCORD_URL) }
        } else null,
        onSponsor = if (fileActions.canOpenUrl) {
            { fileActions.openUrl(BetaInfo.SPONSOR_URL) }
        } else null,
        onStarOnGitHub = if (fileActions.canOpenUrl) {
            { fileActions.openUrl(BetaInfo.REPO_URL) }
        } else null,
        storagePath = backend.projects.storageRootPath(),
        onOpenInFiles = if (fileActions.canReveal) {
            { backend.projects.storageRootPath()?.let { fileActions.reveal(it) } }
        } else null,
        onRefresh = app::refreshProjects,
        showLegacyRecovery = app.showLegacyRecovery,
        onDismissLegacyRecovery = app::dismissLegacyRecovery,
        loadIcon = { backend.projects.projectIcon(it.rootPath) },
    )
    if (notificationsVisible) {
        NotificationsSheet(
            backend = backend,
            onDismiss = { notificationsVisible = false },
            onOpenTarget = { n ->
                notificationsVisible = false
                app.openNotificationTarget(n)
            },
        )
    }
}

@Composable
private fun StoreRoute(app: CodeAssistAppState, fileActions: FileActions) {
    // Publishing asks for an account at the moment it is needed, not at launch.
    var signInVisible by remember { mutableStateOf(false) }
    var notifyLaunch by remember(app.backend) { mutableStateOf(app.backend.store.launchNotificationEnabled()) }
    var notifyMessage by remember { mutableStateOf<String?>(null) }
    val storeScope = rememberCoroutineScope()
    // Ask for the server-driven feed once per epoch. Null means there is no remote store to reach —
    // NOT that the store is empty — so the bundled catalog screen takes over. Those are opposite
    // claims and must not render the same page.
    val feed by produceState<dev.ide.ui.backend.UiStoreFeed?>(null, app.backend, app.epoch) {
        value = runCatching { app.backend.store.feed(seedItemId = null) }.getOrNull()
    }
    val current = feed
    if (current == null) {
        ProjectsStoreScreen(
            backend = app.backend,
            onOpenItem = app::openStoreItem,
            onOpenHub = { app.openHub(Screen.Projects) },
        )
        return
    }
    // The bundled scaffolds fill the "Bundled with your IDE" shelf in the empty and sparse states. They
    // come from the DEVICE, not the feed — that shelf's whole point is working with no network — so they
    // are read from the bundled catalog rather than from `feed`.
    val bundledItems by produceState(emptyList<dev.ide.ui.backend.UiStoreItem>(), app.backend, app.epoch) {
        value = runCatching {
            app.backend.store.catalog().sections
                .firstOrNull { it.id == "templates" }?.items
                ?.filter { it.templateId != null }
                ?.take(4)
                .orEmpty()
        }.getOrDefault(emptyList())
    }
    ExploreFeed(
        feed = current,
        onOpenItem = app::openStoreItem,
        installing = app.backend.store.installProgress().collectAsState().value,
        onInstallItem = { item -> app.installStoreItem(item) },
        onOpenSearch = { app.selectHomeTab(dev.ide.ui.HomeTab.Store) },
        bundled = bundledItems,
        onUseBundled = { item -> item.templateId?.let(app::createProject) },
        onPublish = { signInVisible = true },
        // The switch was inert: nothing was passed, so it defaulted to false and a no-op handler. It now
        // reflects and writes a real broadcast subscription on the device row.
        notifyOnLaunch = notifyLaunch,
        notifyMessage = notifyMessage,
        onNotifyChange = { wanted ->
            storeScope.launch {
                val error = app.backend.store.setLaunchNotification(wanted)
                // Only follow the server: a switch that flips on its own would claim a subscription the
                // backend never accepted.
                if (error == null) notifyLaunch = wanted
                notifyMessage = error
            }
        },
        // A publisher with no handle has no page to open — the handle IS the address — so the tap is a
        // no-op rather than opening a profile that cannot resolve.
        onOpenPublisher = { publisher -> publisher.handle?.let { app.openPublisher(it) } },
        onAccount = if (app.backend.store.authProviders().isNotEmpty()) ({ signInVisible = true }) else null,
        signedIn = app.backend.store.authState().collectAsState().value.signedIn,
        onHowItWorks = { app.openPublishingGuide() },
    )
    if (signInVisible) {
        StoreSignInSheet(
            backend = app.backend,
            onDismiss = { signInVisible = false },
            onSubmitProject = if (app.backend.store.submissionsAvailable()) ({ app.openSubmitProject() }) else null,
            // Null when the host cannot open a browser, which the sheet reports rather than working around.
            onOpenUrl = if (fileActions.canOpenUrl) ({ url: String -> fileActions.openUrl(url) }) else null,
        )
    }
}

@Composable
private fun LearnRoute(app: CodeAssistAppState, fileActions: FileActions) {
    LearnScreen(
        backend = app.backend,
        epoch = app.learnEpoch,
        onOpenTrack = app::openTrack,
        onResume = app::resumeLesson,
        onOpenDocs = if (fileActions.canOpenUrl) {
            { fileActions.openUrl(BetaInfo.REPO_URL) }
        } else null,
        onJoinDiscord = if (fileActions.canOpenUrl) {
            { fileActions.openUrl(BetaInfo.DISCORD_URL) }
        } else null,
    )
}
