package dev.ide.core.backend

import dev.ide.analysis.AnalyzerId
import dev.ide.core.BackendContext
import dev.ide.core.completion.CompletionOptions
import dev.ide.core.settings.BuiltInSettingsPages
import dev.ide.core.settings.IdeSettings
import dev.ide.core.settings.SettingsStore
import dev.ide.lang.dom.Severity
import dev.ide.platform.settings.PreferenceReader
import dev.ide.platform.settings.SettingControl
import dev.ide.platform.settings.SettingsPage
import dev.ide.platform.settings.SettingsScope
import dev.ide.ui.backend.SettingsService
import dev.ide.ui.backend.UiAccent
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
        val svc = ctx.servicesOrNull ?: return emptyList()
        val pages = (BuiltInSettingsPages.all(ctx.analyticsAvailable()) + svc.settingsPages()).sortedBy { it.order }
        return pages.map { toUiPage(it) }
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
        return UiSettingsPage(
            id = page.id,
            title = page.title,
            iconId = page.iconId,
            scope = if (project) "project" else "app",
            controls = page.controls().map { toUiControl(page.id, it, project) },
            inspectionsSection = BuiltInSettingsPages.isInspectionsPage(page),
        )
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
        accent = if (accent == IdeSettings.ACCENT_TEAL) UiAccent.Teal else UiAccent.Violet,
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
