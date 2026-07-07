package dev.ide.core.backend

import dev.ide.analysis.AnalyzerId
import dev.ide.core.BackendContext
import dev.ide.core.completion.CompletionOptions
import dev.ide.core.settings.BuiltInSettingsPages
import dev.ide.core.settings.CodeStyleSettings
import dev.ide.core.settings.IdeSettings
import dev.ide.core.settings.SettingsStore
import dev.ide.lang.dom.Severity
import dev.ide.platform.settings.PreferenceReader
import dev.ide.platform.settings.SettingControl
import dev.ide.platform.settings.SettingsPage
import dev.ide.platform.settings.SettingsScope
import dev.ide.ui.backend.SettingsService
import dev.ide.ui.backend.UiAccent
import dev.ide.ui.backend.UiCodeStyle
import dev.ide.ui.backend.UiInspection
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.backend.UiSettingControl
import dev.ide.ui.backend.UiSettings
import dev.ide.ui.backend.UiSettingsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [SettingsService]: the app-global typed settings ([settingsStore], prefs-backed) + per-project settings
 * (engine-backed) + the extensible settings-page model + the inspection catalogue + app preferences. Writes
 * route by page scope and re-apply the effect. The analytics toggle is special-cased to the consent decision
 * (cross-cutting; reached through [BackendContext]).
 */
internal class SettingsBackend(private val ctx: BackendContext) : SettingsService {

    override fun preference(key: String): String? = ctx.manager?.preference(key)

    override fun setPreference(key: String, value: String) {
        ctx.manager?.setPreference(key, value)
    }

    private val settingsStore = SettingsStore(
        get = { k -> ctx.manager?.preference(k) },
        set = { k, v -> ctx.manager?.setPreference(k, v) },
    )

    override fun settings(): UiSettings = settingsStore.load().toUi()

    override fun settingsPages(): List<UiSettingsPage> {
        val svc = ctx.servicesOrNull
        val builtIn = BuiltInSettingsPages.all(ctx.analyticsAvailable())
        // With no project open (the picker's Settings & Tools hub) only the app-scoped built-in pages are
        // available — the project-scoped built-ins (e.g. Build) and any plugin-contributed pages need an
        // engine, so they're added only once a project is open. (The hub shows the app pages; the in-project
        // Project Settings surface shows the project pages — see UiSettingsPage.scope.)
        val pages = if (svc == null) builtIn.filter { it.scope != SettingsScope.PROJECT }
        else builtIn + svc.settingsPages()
        return pages.sortedBy { it.order }.map { toUiPage(it) }
    }

    override fun setSetting(pageId: String, key: String, value: String) {
        // The analytics toggle isn't a generic-store value — it routes to the persisted consent decision.
        if (pageId == BuiltInSettingsPages.PRIVACY && key == BuiltInSettingsPages.ANALYTICS) {
            ctx.setAnalyticsConsent(value.toBooleanStrictOrNull() ?: false)
            return
        }
        val page = findPage(pageId) ?: return
        val fullKey = settingKey(pageId, key)
        if (page.scope == SettingsScope.PROJECT) ctx.servicesOrNull?.setProjectPref(fullKey, value)
        else ctx.manager?.setPreference(fullKey, value)
        applyAfterChange(page, key)
    }

    override suspend fun invokeSettingAction(pageId: String, key: String): String? {
        if (pageId == BuiltInSettingsPages.PRIVACY && key == BuiltInSettingsPages.CLEAR_CACHES) {
            return withContext(Dispatchers.IO) { ctx.servicesOrNull?.clearProjectCaches() }
        }
        // viewLogs / backup are wired to UI flows by the screen, not here.
        val page = findPage(pageId) ?: return null
        return if (isBuiltIn(pageId)) null else page.onAction(key, scopedReader(page))
    }

    override fun codeStyle(languageId: String): UiCodeStyle = settingsStore.loadCodeStyle(languageId).toUi()

    override fun setCodeStyle(languageId: String, style: UiCodeStyle) {
        settingsStore.saveCodeStyle(languageId, style.toSettings())
    }

    override suspend fun formatStylePreview(languageId: String, style: UiCodeStyle): String =
        withContext(Dispatchers.Default) {
            ctx.servicesOrNull?.formatStylePreview(languageId, style.toSettings().toFormatStyle()) ?: ""
        }

    private fun CodeStyleSettings.toUi() = UiCodeStyle(
        preset = preset, indentSize = indentSize, continuationIndent = continuationIndent,
        maxLineLength = maxLineLength, useTabs = useTabs, braceStyle = braceStyle,
        spaceBeforeParens = spaceBeforeParens, spaceWithinParens = spaceWithinParens,
        spaceAfterComma = spaceAfterComma, spaceAroundOperators = spaceAroundOperators,
        spaceBeforeBrace = spaceBeforeBrace, blankLinesToKeep = blankLinesToKeep,
        wrapMethodParameters = wrapMethodParameters, wrapMethodArguments = wrapMethodArguments,
        wrapChainedCalls = wrapChainedCalls, wrapBinaryExpressions = wrapBinaryExpressions,
        blankLinesAfterImports = blankLinesAfterImports, blankLinesBeforeMethod = blankLinesBeforeMethod,
        blankLinesBeforeField = blankLinesBeforeField, blankLinesBeforeFirstMember = blankLinesBeforeFirstMember,
        blankLinesBetweenTypes = blankLinesBetweenTypes, spaceBeforeSemicolon = spaceBeforeSemicolon,
        spaceAroundLambdaArrow = spaceAroundLambdaArrow, spaceAroundTernary = spaceAroundTernary,
        spaceAfterTypeCast = spaceAfterTypeCast, formatComments = formatComments, wrapComments = wrapComments,
    )

    private fun UiCodeStyle.toSettings() = CodeStyleSettings(
        preset = preset, indentSize = indentSize, continuationIndent = continuationIndent,
        maxLineLength = maxLineLength, useTabs = useTabs, braceStyle = braceStyle,
        spaceBeforeParens = spaceBeforeParens, spaceWithinParens = spaceWithinParens,
        spaceAfterComma = spaceAfterComma, spaceAroundOperators = spaceAroundOperators,
        spaceBeforeBrace = spaceBeforeBrace, blankLinesToKeep = blankLinesToKeep,
        wrapMethodParameters = wrapMethodParameters, wrapMethodArguments = wrapMethodArguments,
        wrapChainedCalls = wrapChainedCalls, wrapBinaryExpressions = wrapBinaryExpressions,
        blankLinesAfterImports = blankLinesAfterImports, blankLinesBeforeMethod = blankLinesBeforeMethod,
        blankLinesBeforeField = blankLinesBeforeField, blankLinesBeforeFirstMember = blankLinesBeforeFirstMember,
        blankLinesBetweenTypes = blankLinesBetweenTypes, spaceBeforeSemicolon = spaceBeforeSemicolon,
        spaceAroundLambdaArrow = spaceAroundLambdaArrow, spaceAroundTernary = spaceAroundTernary,
        spaceAfterTypeCast = spaceAfterTypeCast, formatComments = formatComments, wrapComments = wrapComments,
    )

    override fun inspections(): List<UiInspection> {
        val svc = ctx.servicesOrNull ?: return emptyList()
        val profile = svc.inspectionProfile()
        return svc.registeredAnalyzers().map { a ->
            UiInspection(
                id = a.id.value,
                displayName = a.displayName,
                language = a.languages.firstOrNull()?.id?.let(::prettyLang) ?: "All",
                tier = a.tier.name.lowercase().replaceFirstChar { it.uppercase() },
                enabled = profile.isEnabled(a.id),
                severity = (profile.severityOverrides[a.id] ?: a.defaultSeverity).toUiSeverity(),
                defaultSeverity = a.defaultSeverity.toUiSeverity(),
            )
        }.sortedWith(compareBy({ it.language }, { it.displayName }))
    }

    override fun setInspection(id: String, enabled: Boolean, severity: UiSeverity) {
        ctx.servicesOrNull?.setInspection(AnalyzerId(id), enabled, severity.toDomSeverity())
    }

    // --- settings helpers ---

    private fun settingKey(pageId: String, key: String) = "settings.$pageId.$key"

    private fun findPage(pageId: String): SettingsPage? =
        BuiltInSettingsPages.all(ctx.analyticsAvailable()).firstOrNull { it.id == pageId }
            ?: ctx.servicesOrNull?.settingsPages()?.firstOrNull { it.id == pageId }

    private fun isBuiltIn(pageId: String): Boolean =
        BuiltInSettingsPages.all(ctx.analyticsAvailable()).any { it.id == pageId }

    /** Read a control's raw stored value (scoped store), or null to fall back to the control default. */
    private fun readSetting(pageId: String, key: String, project: Boolean): String? {
        val fullKey = settingKey(pageId, key)
        return if (project) ctx.servicesOrNull?.projectPref(fullKey) else ctx.manager?.preference(fullKey)
    }

    /** A page-local reader handed to a plugin page's hooks (it reads its own control keys). */
    private fun scopedReader(page: SettingsPage): PreferenceReader = object : PreferenceReader {
        override fun raw(key: String) = readSetting(page.id, key, page.scope == SettingsScope.PROJECT)
    }

    private fun applyAfterChange(page: SettingsPage, key: String) {
        // Publish the change on the workspace event spine FIRST (config stamp + the out-of-process hint
        // fan-out), then apply the engine-side effects below. No engine open → nothing to notify or apply.
        ctx.servicesOrNull?.events?.settingChanged(page.id, key, page.scope == SettingsScope.PROJECT)
        when (page.id) {
            // Completion knobs feed the engine; everything else built-in is applied UI-side (the UI re-reads
            // settings()), so there's nothing to push here.
            BuiltInSettingsPages.COMPLETION -> ctx.servicesOrNull?.let { it.completionOptions = currentCompletionOptions() }
            BuiltInSettingsPages.BUILD -> if (key == BuiltInSettingsPages.CONFLICT_POLICY) {
                ctx.servicesOrNull?.setConflictPolicy(parseConflictPolicy(readSetting(page.id, key, project = true)))
            }
            else -> if (!isBuiltIn(page.id)) page.onChanged(key, scopedReader(page)) // plugin pages react themselves
        }
    }

    private fun currentCompletionOptions(): CompletionOptions {
        val s = settingsStore.load()
        return CompletionOptions(
            maxItems = s.completionMaxItems,
            postfixTemplates = s.postfixTemplates,
            wordCompletion = s.wordCompletion,
        )
    }

    private fun parseConflictPolicy(value: String?): dev.ide.deps.ConflictPolicy = when (value) {
        BuiltInSettingsPages.CONFLICT_PINNED -> dev.ide.deps.ConflictPolicy.PINNED
        BuiltInSettingsPages.CONFLICT_FAIL -> dev.ide.deps.ConflictPolicy.FAIL_ON_CONFLICT
        else -> dev.ide.deps.ConflictPolicy.NEWEST
    }

    private fun toUiPage(page: SettingsPage): UiSettingsPage {
        val project = page.scope == SettingsScope.PROJECT
        val controls =
            if (page.id == BuiltInSettingsPages.BUILD_RUNTIME) buildRuntimeControls()
            else page.controls().map { toUiControl(page.id, it, project) }
        return UiSettingsPage(
            id = page.id,
            title = page.title,
            iconId = page.iconId,
            scope = if (project) "project" else "app",
            controls = controls,
            inspectionsSection = BuiltInSettingsPages.isInspectionsPage(page),
        )
    }

    /**
     * The Build Runtime page is rendered dynamically: the R8 forked-VM heap slider's MAX is this device's
     * measured forked-VM ceiling (so the user can only scale DOWN from the real limit), the slider is HIDDEN
     * in In-process mode (replaced by the app's memory limit in the mode description), and a warning shows if a
     * saved value exceeds the device limit. The ceiling is read from [BuiltInSettingsPages.R8_CEILING_PREF]
     * (measured once in the background by the host): null = not yet measured, 0 = forking unavailable here.
     */
    private val R8_MIN_MB = 768
    private val FALLBACK_R8_MAX_MB = 2048 // slider max before the device limit has been measured

    private fun buildRuntimeControls(): List<UiSettingControl> {
        val pid = BuiltInSettingsPages.BUILD_RUNTIME
        val appHeapMb = (Runtime.getRuntime().maxMemory() / (1024L * 1024L)).toInt()
        val ceiling = ctx.manager?.preference(BuiltInSettingsPages.R8_CEILING_PREF)?.trim()?.toIntOrNull()
        val mode = ctx.manager?.preference(settingKey(pid, BuiltInSettingsPages.R8_MODE)) ?: BuiltInSettingsPages.R8_MODE_DEFAULT
        val sepOn = ctx.manager?.preference(settingKey(pid, BuiltInSettingsPages.SEPARATE_PROCESS))?.toBooleanStrictOrNull() ?: true

        val out = ArrayList<UiSettingControl>()
        out += UiSettingControl.Toggle(
            BuiltInSettingsPages.SEPARATE_PROCESS, "Build in a separate process",
            "Run builds and your program in an isolated process so an out-of-memory crash can't take down the IDE. Off = build in-process (uses less memory, no isolation). Takes effect the next time you open a project.",
            sepOn, false, null,
        )

        val modeDesc = when {
            mode == BuiltInSettingsPages.R8_MODE_INPROCESS ->
                "R8 runs inside the IDE, capped at this app's memory limit (~$appHeapMb MB). Pick Forked VM to give R8 more by running it in a separate VM."
            ceiling == 0 ->
                "This device can't run R8 in a separate VM, so it runs in-process (~$appHeapMb MB). Large apps may run out of memory; there's nothing to tune here."
            else ->
                "Forked VM (default) runs R8 in a separate VM with more memory than this app's ~$appHeapMb MB limit, so large apps don't run out of memory; it falls back to in-process if the device can't. Android only."
        }
        out += UiSettingControl.Choice(
            BuiltInSettingsPages.R8_MODE, "R8 execution", modeDesc, mode,
            listOf(
                UiSettingControl.Choice.Option(BuiltInSettingsPages.R8_MODE_FORKED, "Forked VM"),
                UiSettingControl.Choice.Option(BuiltInSettingsPages.R8_MODE_INPROCESS, "In-process"),
            ),
            false, null,
        )

        // The heap slider applies only to the forked VM; hide it in In-process and when forking is unavailable.
        if (mode != BuiltInSettingsPages.R8_MODE_INPROCESS && ceiling != 0) {
            val max = (ceiling ?: FALLBACK_R8_MAX_MB).coerceAtLeast(R8_MIN_MB)
            val saved = ctx.manager?.preference(settingKey(pid, BuiltInSettingsPages.R8_MAX_HEAP))?.trim()?.toIntOrNull()
            // Default to the device limit (the max) so the user only ever scales DOWN; clamp the displayed value.
            val value = (saved ?: max).coerceIn(R8_MIN_MB, max)
            val limitNote = if (ceiling != null) "This device's limit is $ceiling MB." else "Measuring this device's limit…"
            val warn = if (ceiling != null && saved != null && saved > ceiling)
                " ⚠ Your saved value ($saved MB) is above the device limit; R8 will use $ceiling MB."
            else ""
            out += UiSettingControl.Slider(
                BuiltInSettingsPages.R8_MAX_HEAP, "R8 forked-VM heap",
                "Heap for R8's forked VM. $limitNote$warn",
                value, R8_MIN_MB, max, 128, "MB", false, null,
            )
        }

        // Debug-build dexing memory knobs (the R8 controls above govern the release/minify path). Both are
        // advanced and Android-only, grouped apart so they don't read as part of R8.
        val dexGroup = "Debug build (dexing)"
        val forkable = mode != BuiltInSettingsPages.R8_MODE_INPROCESS && ceiling != 0
        val offHeap = (ctx.manager?.preference(settingKey(pid, BuiltInSettingsPages.DEX_OFFHEAP_MB))?.trim()?.toIntOrNull()
            ?: BuiltInSettingsPages.DEX_OFFHEAP_MB_DEFAULT).coerceIn(2, 64)
        val offHeapDesc = if (forkable)
            "On a clean build the dexer turns your whole project (and large libraries) into Dalvik bytecode — heavy work that normally runs inside the IDE. When one of those steps is at least this big, it's moved to the separate VM instead (the same one R8 uses), keeping it off the IDE's ~$appHeapMb MB heap. Lower = safer on low-memory devices but more short-lived VMs (slightly slower); higher = fewer VMs but more pressure on the IDE. Small edits always stay in-process."
        else
            "Moves large dexing steps off the IDE's heap into a separate VM on a clean build. Inactive while R8 execution is In-process (or the device can't fork a VM) — everything dexes in-process then."
        out += UiSettingControl.Slider(
            BuiltInSettingsPages.DEX_OFFHEAP_MB, "Off-heap dexing threshold",
            offHeapDesc, offHeap, 2, 64, 2, "MB", true, dexGroup,
        )

        val mergeBatch = (ctx.manager?.preference(settingKey(pid, BuiltInSettingsPages.DEX_MERGE_BATCH))?.trim()?.toIntOrNull()
            ?: BuiltInSettingsPages.DEX_MERGE_BATCH_DEFAULT).coerceIn(1000, 20000)
        out += UiSettingControl.Slider(
            BuiltInSettingsPages.DEX_MERGE_BATCH, "Dex merge batch size",
            "On a very large app the final dexing step merges classes in batches so it doesn't need all of them in memory at once. Smaller batches keep that memory low (good for low-memory devices) but make the APK slightly larger (less shared compression across classes); larger batches pack tighter but need more memory per merge. Most apps never reach this — it only kicks in past a few thousand classes.",
            mergeBatch, 1000, 20000, 1000, "classes", true, dexGroup,
        )

        val forkConc = (ctx.manager?.preference(settingKey(pid, BuiltInSettingsPages.DEX_FORK_CONCURRENCY))?.trim()?.toIntOrNull()
            ?: BuiltInSettingsPages.DEX_FORK_CONCURRENCY_DEFAULT).coerceIn(0, 4)
        val forkConcDesc = if (forkable)
            "How many of these separate dexing VMs may run at once. The dex merge splits across a few of them, so several libraries dex in parallel instead of one at a time. 0 = automatic (chosen from your device's free memory and the VM heap above). Higher is faster on devices with plenty of RAM but commits more memory at once; lower (or 0) is safer on tight devices. Takes effect the next time the build starts."
        else
            "Caps how many separate dexing VMs run at once. Inactive while R8 execution is In-process (or the device can't fork a VM) — everything dexes in-process then."
        out += UiSettingControl.Slider(
            BuiltInSettingsPages.DEX_FORK_CONCURRENCY, "Max concurrent dex forks",
            forkConcDesc, forkConc, 0, 4, 1, null, true, dexGroup,
        )
        return out
    }

    private fun toUiControl(pageId: String, c: SettingControl, project: Boolean): UiSettingControl {
        val raw = readSetting(pageId, c.key, project)
        return when (c) {
            is SettingControl.Toggle -> {
                val v = if (pageId == BuiltInSettingsPages.PRIVACY && c.key == BuiltInSettingsPages.ANALYTICS) ctx.analyticsConsent() == true
                else raw?.toBooleanStrictOrNull() ?: c.default
                UiSettingControl.Toggle(c.key, c.title, c.description, v, c.advanced, c.group)
            }
            is SettingControl.IntSlider -> UiSettingControl.Slider(
                c.key, c.title, c.description, (raw?.trim()?.toIntOrNull() ?: c.default).coerceIn(c.min, c.max),
                c.min, c.max, c.step, c.unit, c.advanced, c.group,
            )
            is SettingControl.Choice -> UiSettingControl.Choice(
                c.key, c.title, c.description, raw ?: c.default,
                c.options.map { UiSettingControl.Choice.Option(it.value, it.label) }, c.advanced, c.group,
            )
            is SettingControl.Text -> UiSettingControl.Text(c.key, c.title, c.description, raw ?: c.default, c.placeholder, c.advanced, c.group)
            is SettingControl.Action -> UiSettingControl.Action(c.key, c.title, c.description, c.buttonLabel, c.destructive, c.advanced, c.group)
        }
    }

    private fun IdeSettings.toUi(): UiSettings = UiSettings(
        themeMode = themeMode,
        accent = when (accent) {
            IdeSettings.ACCENT_TEAL -> UiAccent.Teal
            IdeSettings.ACCENT_ORANGE -> UiAccent.Orange
            else -> UiAccent.Violet
        },
        editorFontScale = editorFontScale,
        codeFont = codeFont,
        fontLigatures = fontLigatures,
        inlayHints = inlayHints,
        semanticHighlighting = semanticHighlighting,
        codeFolding = codeFolding,
        completionAutoPopup = completionAutoPopup,
        completionDelayMs = completionDelayMs,
        completionMaxItems = completionMaxItems,
        postfixTemplates = postfixTemplates,
        wordCompletion = wordCompletion,
        analyzeOnTheFly = analyzeOnTheFly,
        reparseDelayMs = reparseDelayMs,
        wordWrap = wordWrap,
        wrapIndent = wrapIndent,
        twoAxisScroll = twoAxisScroll,
        pinchZoom = pinchZoom,
        softKeyboardSuggestions = softKeyboardSuggestions,
        formatOnSave = formatOnSave,
    )

    private fun Severity.toUiSeverity(): UiSeverity = when (this) {
        Severity.ERROR -> UiSeverity.Error
        Severity.WARNING -> UiSeverity.Warning
        Severity.INFO -> UiSeverity.Info
        Severity.HINT -> UiSeverity.Hint
    }

    private fun UiSeverity.toDomSeverity(): Severity = when (this) {
        UiSeverity.Error -> Severity.ERROR
        UiSeverity.Warning -> Severity.WARNING
        UiSeverity.Info -> Severity.INFO
        UiSeverity.Hint -> Severity.HINT
    }

    private fun prettyLang(id: String): String = when (id.lowercase()) {
        "java" -> "Java"
        "kotlin" -> "Kotlin"
        "xml" -> "XML"
        else -> id.replaceFirstChar { it.uppercase() }
    }
}
