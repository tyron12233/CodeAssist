package dev.ide.core.settings

import dev.ide.platform.settings.SettingControl
import dev.ide.platform.settings.SettingsPage
import dev.ide.platform.settings.SettingsScope

/**
 * The built-in Settings pages, declared against the same [SettingsPage] SPI a plugin uses — so built-ins and
 * plugin pages render through one generic path. These are pure *declarations* (control lists); their effects
 * are applied centrally by the backend (it knows the built-in keys), so the hooks stay empty here. A plugin
 * page, by contrast, carries its own [SettingsPage.onChanged]/[SettingsPage.onAction] logic.
 *
 * Control keys are page-local; the host stores them under `settings.<pageId>.<key>` (app scope) — the exact
 * keys [SettingsStore] reads, so a generic write and the typed [IdeSettings] view stay in sync.
 */
object BuiltInSettingsPages {
    const val APPEARANCE = "appearance"
    const val EDITOR = "editor"
    const val COMPLETION = "completion"
    const val ANALYSIS = "analysis"
    const val BUILD = "build"
    /** App-scoped build-runtime page (distinct from the project-scoped [BUILD] page) — holds the
     *  separate-process toggle, which is app-global. See docs/build-process-isolation.md. */
    const val BUILD_RUNTIME = "buildRuntime"
    const val PRIVACY = "privacy"

    /** Toggle key on the [BUILD_RUNTIME] page: route builds/runs through the isolated `:build` process. */
    const val SEPARATE_PROCESS = "separateProcess"

    // Keys the backend special-cases (routed to a non-generic-store effect).
    const val CONFLICT_POLICY = "conflictPolicy"
    const val ANALYTICS = "analytics"
    const val CLEAR_CACHES = "clearCaches"
    const val VIEW_LOGS = "viewLogs"
    const val BACKUP = "backup"

    /** The conflict-policy choice values (mirror `dev.ide.deps.ConflictPolicy`). */
    const val CONFLICT_NEWEST = "newest"
    const val CONFLICT_PINNED = "pinned"
    const val CONFLICT_FAIL = "failOnConflict"

    private val d = IdeSettings()

    /** All built-in pages in display order. [analyticsAvailable] gates the analytics toggle on the Privacy page. */
    fun all(analyticsAvailable: Boolean): List<SettingsPage> = listOf(
        appearance, editor, completion, analysis, build, buildRuntime, privacy(analyticsAvailable),
    )

    private val appearance = page(APPEARANCE, "Appearance", "eye", 0) {
        listOf(
            SettingControl.Choice(
                "themeMode", "Theme", "Use a fixed theme or follow the operating system",
                default = d.themeMode,
                options = listOf(
                    SettingControl.Choice.Option(IdeSettings.THEME_LIGHT, "Light"),
                    SettingControl.Choice.Option(IdeSettings.THEME_DARK, "Dark"),
                    SettingControl.Choice.Option(IdeSettings.THEME_SYSTEM, "System"),
                ),
            ),
            SettingControl.Choice(
                "accent", "Accent", "The interface highlight color",
                default = d.accent,
                options = listOf(
                    SettingControl.Choice.Option(IdeSettings.ACCENT_VIOLET, "Violet"),
                    SettingControl.Choice.Option(IdeSettings.ACCENT_TEAL, "Teal"),
                    SettingControl.Choice.Option(IdeSettings.ACCENT_ORANGE, "Orange (Legacy)"),
                ),
            ),
        )
    }

    private val editor = page(EDITOR, "Editor", "code", 10) {
        listOf(
            SettingControl.IntSlider("fontScale", "Font size", default = (d.editorFontScale * 100).toInt(), min = 70, max = 200, step = 5, unit = "%"),
            SettingControl.Choice(
                "codeFont", "Code font",
                default = d.codeFont,
                options = listOf(
                    SettingControl.Choice.Option(IdeSettings.CODE_FONT_JETBRAINS, "JetBrains Mono"),
                    SettingControl.Choice.Option(IdeSettings.CODE_FONT_MONOSPACE, "System monospace"),
                ),
            ),
            SettingControl.Toggle("fontLigatures", "Font ligatures", "Render programming ligatures (-> != >= …) when the code font has them", default = d.fontLigatures),
            SettingControl.Toggle("inlayHints", "Inlay hints", "Inferred types and parameter-name hints, shown inline", default = d.inlayHints),
            SettingControl.Toggle("semanticHighlighting", "Semantic highlighting", "Type-aware coloring layered over the lexer", default = d.semanticHighlighting),
            SettingControl.Toggle("codeFolding", "Code folding", "Fold imports, bodies, and block comments", default = d.codeFolding),
            SettingControl.Toggle("wordWrap", "Word wrap", "Soft-wrap long lines at the viewport edge instead of scrolling horizontally", default = d.wordWrap),
            SettingControl.Toggle("wrapIndent", "Indent wrapped lines", "Align a wrapped line's continuation rows to its indentation (when word wrap is on)", default = d.wrapIndent),
            SettingControl.Toggle("twoAxisScroll", "Two-axis scrolling", "Drag in any direction to scroll both axes at once (touch)", default = d.twoAxisScroll, group = "Gestures"),
            SettingControl.Toggle("pinchZoom", "Pinch to zoom", "Pinch with two fingers to change the code font size", default = d.pinchZoom, group = "Gestures"),
            SettingControl.Toggle("softKeyboardSuggestions", "Keyboard suggestions", "Let the soft keyboard autocorrect, suggest, and auto-space. Off (recommended for code) treats input as raw text, so a typed '.' doesn't get an auto-inserted space.", default = d.softKeyboardSuggestions, group = "Keyboard"),
        )
    }

    private val completion = page(COMPLETION, "Code Completion", "sparkle", 20) {
        listOf(
            SettingControl.Toggle("autoPopup", "Auto-show suggestions", "Pop the list up while typing (off = Ctrl-Space only)", default = d.completionAutoPopup),
            SettingControl.Toggle("postfixTemplates", "Postfix templates", "Offer .val / .if / .notnull / … completions", default = d.postfixTemplates),
            SettingControl.Toggle("wordCompletion", "Word completion", "Offer words already in the file as a fallback", default = d.wordCompletion),
            SettingControl.IntSlider("delayMs", "Auto-popup delay", "How long after a keystroke the list appears", default = d.completionDelayMs, min = IdeSettings.MIN_COMPLETION_DELAY_MS, max = IdeSettings.MAX_COMPLETION_DELAY_MS, step = 10, unit = "ms", advanced = true),
            SettingControl.IntSlider("maxItems", "Maximum suggestions", default = d.completionMaxItems, min = IdeSettings.MIN_COMPLETION_MAX_ITEMS, max = IdeSettings.MAX_COMPLETION_MAX_ITEMS, step = 10, advanced = true),
        )
    }

    private val analysis = page(ANALYSIS, "Analysis & Inspections", "lightbulb", 30) {
        listOf(
            SettingControl.Toggle("onTheFly", "Analyze on the fly", "Show diagnostics as you type (off = on build only)", default = d.analyzeOnTheFly),
            SettingControl.IntSlider("reparseDelayMs", "Reparse delay", "Quiet period after a keystroke before re-analysis", default = d.reparseDelayMs, min = IdeSettings.MIN_REPARSE_DELAY_MS, max = IdeSettings.MAX_REPARSE_DELAY_MS, step = 50, unit = "ms", advanced = true),
        )
    }

    private val build = page(BUILD, "Build & Dependencies", "hammer", 40, scope = SettingsScope.PROJECT) {
        listOf(
            SettingControl.Choice(
                CONFLICT_POLICY, "Dependency conflicts", "Which version wins when two are requested in the graph",
                default = CONFLICT_NEWEST,
                options = listOf(
                    SettingControl.Choice.Option(CONFLICT_NEWEST, "Newest"),
                    SettingControl.Choice.Option(CONFLICT_PINNED, "Direct wins"),
                    SettingControl.Choice.Option(CONFLICT_FAIL, "Fail on conflict"),
                ),
            ),
        )
    }

    // App-global (not per-project): running the build in its own process is about this device's memory
    // headroom + your robustness preference, the same for every project. Default ON. The effect is applied
    // by the backend (it reads `settings.buildRuntime.separateProcess`); see docs/build-process-isolation.md.
    private val buildRuntime = page(BUILD_RUNTIME, "Build Runtime", "hammer", 45) {
        listOf(
            SettingControl.Toggle(
                SEPARATE_PROCESS, "Build in a separate process",
                "Run builds and your program in an isolated process so an out-of-memory crash can't take down the IDE. Off = build in-process (uses less memory, no isolation). Takes effect the next time you open a project.",
                default = true,
            ),
        )
    }

    private fun privacy(analyticsAvailable: Boolean) = page(PRIVACY, "Privacy & Data", "info", 50) {
        buildList {
            if (analyticsAvailable) {
                add(SettingControl.Toggle(ANALYTICS, "Share performance analytics", "Anonymous performance metrics only — never your code or file names", default = false, group = "Privacy"))
            }
            add(SettingControl.Action(CLEAR_CACHES, "Clear caches", "Free regenerable dependency / language / preview caches (never source)", buttonLabel = "Clear", group = "Storage"))
            add(SettingControl.Action(VIEW_LOGS, "View logs", "Recent editor, analysis, and build activity", buttonLabel = "Open", group = "Storage"))
            add(SettingControl.Action(BACKUP, "Back up projects", "Export every project to a single zip", buttonLabel = "Back up", group = "Storage"))
        }
    }

    /** Small builder for an anonymous built-in [SettingsPage] (empty hooks; effects are applied by the backend). */
    private fun page(
        id: String, title: String, iconId: String, order: Int,
        scope: SettingsScope = SettingsScope.APPLICATION,
        controlsProvider: () -> List<SettingControl>,
    ): SettingsPage = object : SettingsPage {
        override val id = id
        override val title = title
        override val iconId = iconId
        override val scope = scope
        override val order = order
        override fun controls() = controlsProvider()
    }

    /** Whether [page] is the built-in Analysis page that wants the inspection list appended. */
    fun isInspectionsPage(page: SettingsPage): Boolean = page.id == ANALYSIS
}
