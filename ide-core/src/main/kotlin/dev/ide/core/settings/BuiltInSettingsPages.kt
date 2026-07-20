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
    /** Project-scoped Compose Preview page — the interpreter sandbox toggles (see `PreviewSandboxPolicy`). */
    const val PREVIEW = "preview"

    /** Toggle key on the [BUILD_RUNTIME] page: route builds/runs through the isolated `:build` process. */
    const val SEPARATE_PROCESS = "separateProcess"

    /** Toggle key on the [BUILD_RUNTIME] page: weave the IDE log bridge into DEBUG builds so a running app
     *  forwards its logs to the Logcat tab. Read per build (device only); default on. */
    const val INJECT_APP_LOG = "injectAppLog"

    /** Action key on the [BUILD_RUNTIME] page (separate-process-capable hosts only): re-request the runtime
     *  notification permission the isolated build process needs. Handled UI-side (needs the platform permission
     *  launcher) — the SettingsScreen mirrors this key; there's no engine-side effect here. */
    const val BUILD_NOTIFICATIONS = "buildNotifications"

    /** IntSlider key on the [BUILD_RUNTIME] page: the heap (MB) the on-device R8 (release/minify) pass runs
     *  with in a forked VM — larger than the app's own heap cap. Read by `ForkedR8Shrinker` (:ide-android),
     *  which steps down + warns in the build log if the device can't grant it. Android-only effect. */
    const val R8_MAX_HEAP = "r8MaxHeapMb"
    const val R8_MAX_HEAP_DEFAULT = 1536

    /** Choice key on the [BUILD_RUNTIME] page: where the release/minify R8 pass runs. Read by
     *  `ForkedR8Shrinker`. [R8_MODE_FORKED] (the default) runs R8 in a separate VM with more memory than the
     *  app cap, falling back to in-process if the device can't; [R8_MODE_INPROCESS] always runs in-process. */
    const val R8_MODE = "r8Mode"
    const val R8_MODE_FORKED = "forked"
    const val R8_MODE_INPROCESS = "inprocess"
    const val R8_MODE_DEFAULT = R8_MODE_FORKED

    /** App preference (NOT a user control): the largest heap (MB) a forked VM grants R8 on this device,
     *  measured once per app version in the background (`0` = forking unavailable, absent = not yet measured).
     *  The host (:ide-android) writes it; the settings UI reads it for the slider's MAX and the shrinker for
     *  its default heap, so the user can only scale DOWN from the real device limit. */
    const val R8_CEILING_PREF = "r8.detectedCeilingMb"

    /** IntSlider key on [BUILD_RUNTIME]: input size (MB) at/above which an on-device debug-dex step (the
     *  dexBuilder archive) runs in a separate VM instead of the app heap. Read by `ForkedD8Dexer` (:ide-android),
     *  and only when R8 execution is Forked VM. Android-only. Lower = safer on small heaps but more VM spawns. */
    const val DEX_OFFHEAP_MB = "dexOffHeapMb"
    const val DEX_OFFHEAP_MB_DEFAULT = 8

    /** IntSlider key on [BUILD_RUNTIME]: the most classes merged into Dalvik bytecode in one batch on a large
     *  app (debug, native multidex). Read by `DexMergeTask` via the on-device `AndroidDeviceTools.mergeChunkProvider`.
     *  Smaller = lower peak memory + slightly larger APK; larger = tighter packing + more memory. Android-only. */
    const val DEX_MERGE_BATCH = "dexMergeBatch"
    const val DEX_MERGE_BATCH_DEFAULT = 6000

    /** IntSlider key on [BUILD_RUNTIME]: the most forked dexing VMs (the dex merge / off-heap archive) allowed
     *  to run at once. `0` = auto (sized from available device RAM ÷ the forked-VM heap). Read by `ForkedD8Dexer`
     *  (:ide-android), and only when R8 execution is Forked VM. Higher = faster merges on roomy devices but more
     *  RAM committed at once; `0`/lower is safer on tight devices. Android-only. */
    const val DEX_FORK_CONCURRENCY = "dexForkConcurrency"
    const val DEX_FORK_CONCURRENCY_DEFAULT = 0

    /** Toggle key on the [ANALYSIS] page: write per-pass / per-stage editor timings to the log (diagnostic).
     *  Applied by the backend — it flips the shared `PerfTrace` flag. */
    const val PERF_LOGGING = "perfLogging"

    /** Toggle keys on the [PREVIEW] page — `sandbox` + a capitalized `SandboxCategory.id`. Read by
     *  `ComposePreviewService.sandboxCategories()` per preview open; all default ON (restricted). */
    const val SANDBOX_FILE_IO = "sandboxFileIo"
    const val SANDBOX_NETWORK = "sandboxNetwork"
    const val SANDBOX_ANDROID = "sandboxAndroidSystem"
    const val SANDBOX_PROCESS = "sandboxProcessControl"

    /** Toggle key on the [PREVIEW] page: render the XML real-view layout preview by INTERPRETING the library +
     *  project view classes (the `:jvm-interp` VM) instead of dexing them onto a class loader. Default ON. Read
     *  by `IdeServices.realViewPreview`. Keeps downloaded/user code off ART and re-enables project custom views. */
    const val LAYOUT_INTERPRET = "layoutInterpret"

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

    /** All built-in pages in display order. [analyticsAvailable] gates the analytics toggle on the Privacy page.
     *  Code Style is not here: it has its own dedicated screen (the formatting profiles are per-language). */
    fun all(analyticsAvailable: Boolean): List<SettingsPage> = listOf(
        appearance, editor, completion, analysis, preview, build, buildRuntime, privacy(analyticsAvailable),
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
            SettingControl.Toggle("softKeyboardSuggestions", "Keyboard suggestions", "Let the soft keyboard autocorrect, suggest, and auto-space (a normal keyboard). Turn off for raw code input, so a typed '.' doesn't get an auto-inserted space, at the cost of the suggestion strip.", default = d.softKeyboardSuggestions, group = "Keyboard"),
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
            SettingControl.Toggle(PERF_LOGGING, "Log analysis timings", "Diagnostic: write per-pass (semantic / diagnostics / folds / inlay / previews) and per-stage timings to the log so you can find what makes a file slow. Read them in Privacy → View logs. Off by default.", default = d.analysisPerfLogging, advanced = true),
        )
    }

    // Per-project: whether preview code may escape the sandbox is a property of the project you're editing
    // (your own app vs. an untrusted sample), not of the device. Applies to previews opened after a change.
    private val preview = page(PREVIEW, "Preview", "image", 35, scope = SettingsScope.PROJECT) {
        listOf(
            SettingControl.Toggle(
                LAYOUT_INTERPRET, "Interpret layout classes",
                "Render the XML layout preview by interpreting your libraries' and views' bytecode instead of compiling them to dex. Keeps downloaded and app code off the device's class loader and lets your own custom views render without a build step. Turn off to dex the classpath instead (needs a one-time prepare step). Applies to newly opened previews.",
                default = true, group = "Layout preview",
            ),
            SettingControl.Toggle(
                SANDBOX_FILE_IO, "Block file access",
                "Stop previewed code from reading or writing files (java.io / java.nio / kotlin.io). Blocked calls return null and are listed on the preview's problem chip. Applies to newly opened previews.",
                default = true, group = "Preview sandbox",
            ),
            SettingControl.Toggle(
                SANDBOX_NETWORK, "Block network access",
                "Stop previewed code from opening sockets or HTTP connections (java.net, OkHttp, Ktor).",
                default = true, group = "Preview sandbox",
            ),
            SettingControl.Toggle(
                SANDBOX_ANDROID, "Block Android system calls",
                "Stop previewed code from launching activities/services, sending broadcasts, using system services, ContentResolver, or SharedPreferences. Resource and density reads stay available.",
                default = true, group = "Preview sandbox",
            ),
            SettingControl.Toggle(
                SANDBOX_PROCESS, "Block process & reflection",
                "Stop previewed code from exec'ing processes, calling System.exit, loading native libraries, or invoking members reflectively.",
                default = true, group = "Preview sandbox",
            ),
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
            SettingControl.Toggle(
                INJECT_APP_LOG, "Forward app logs",
                "On a debug build, inject a small log bridge into your app so its logs (logcat, println, crashes) stream to the Logcat tab. Debug builds only — release builds are never modified. Applies on the next build.",
                default = true,
            ),
            // The Build Runtime page's R8 controls are rendered dynamically by SettingsBackend (the slider's
            // max is this device's measured forked-VM limit, and it's hidden in In-process mode), so these
            // static descriptors only supply keys / scope / defaults — their descriptions aren't shown.
            SettingControl.Choice(
                R8_MODE, "R8 execution", null,
                default = R8_MODE_DEFAULT,
                options = listOf(
                    SettingControl.Choice.Option(R8_MODE_FORKED, "Forked VM"),
                    SettingControl.Choice.Option(R8_MODE_INPROCESS, "In-process"),
                ),
            ),
            SettingControl.IntSlider(
                R8_MAX_HEAP, "R8 forked-VM heap", null,
                default = R8_MAX_HEAP_DEFAULT, min = 768, max = 4096, step = 128, unit = "MB",
            ),
            // Rendered dynamically by SettingsBackend (rich descriptions); these descriptors only carry the
            // key / default / scope for the write path. Debug-build dexing memory knobs (R8 above = release).
            SettingControl.IntSlider(
                DEX_OFFHEAP_MB, "Off-heap dexing threshold", null,
                default = DEX_OFFHEAP_MB_DEFAULT, min = 2, max = 64, step = 2, unit = "MB", advanced = true,
            ),
            SettingControl.IntSlider(
                DEX_MERGE_BATCH, "Dex merge batch size", null,
                default = DEX_MERGE_BATCH_DEFAULT, min = 1000, max = 20000, step = 1000, advanced = true,
            ),
            SettingControl.IntSlider(
                DEX_FORK_CONCURRENCY, "Max concurrent dex forks", null,
                default = DEX_FORK_CONCURRENCY_DEFAULT, min = 0, max = 4, step = 1, advanced = true,
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
