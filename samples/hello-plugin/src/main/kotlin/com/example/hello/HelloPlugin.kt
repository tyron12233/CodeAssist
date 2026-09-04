package com.example.hello

import dev.ide.build.BUILD_PLUGIN_EP
import dev.ide.build.RUN_TASK_PROVIDER_EP
import dev.ide.build.SOURCE_GENERATOR_EP
import dev.ide.platform.log.Logger
import dev.ide.platform.settings.PreferenceReader
import dev.ide.platform.settings.SETTINGS_PAGE_EP
import dev.ide.platform.settings.SettingControl
import dev.ide.platform.settings.SettingsPage
import dev.ide.platform.settings.SettingsScope
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginRegistration
import dev.ide.plugin.action.ActionContext
import dev.ide.plugin.action.ActionEffect
import dev.ide.plugin.action.ActionPlaces
import dev.ide.plugin.action.ActionResult
import dev.ide.plugin.action.SimpleAction
import dev.ide.plugin.action.TextEdit
import dev.ide.plugin.action.UI_ACTION_EP

/**
 * The entry point named by `res/raw/codeassist_plugin.toml`. The IDE instantiates this class off the
 * installed APK with its own classloader as the parent, so the SPI types below bind to the IDE's copies.
 *
 * It contributes to several surfaces, each a plain extension-point registration:
 *  - a command in the command palette and the More menu ([UI_ACTION_EP]);
 *  - an editor action at the caret, on the same extension point but the [ActionPlaces.EDITOR] place;
 *  - a category in Settings ([SETTINGS_PAGE_EP]);
 *  - build logic: a task on every build graph, a source generator, and a Run-picker row (see
 *    [HelloBuildReportPlugin], [HelloBuildInfoGenerator] and [HelloRunTaskProvider] in HelloBuildPlugin.kt);
 *  - a log line attributed to this plugin, which the Logs screen can filter by.
 *
 * This is the plugin's **engine facet**. Its Compose UI is a second class, [HelloUiPlugin], named by
 * `uiEntryPoints` in the same manifest and loaded off this same APK on the same classloader, which is why
 * the two share [HelloState] as an ordinary object.
 */
class HelloPlugin : Plugin {

    // No manifest is declared here: res/raw/codeassist_plugin.toml is this plugin's identity, and the IDE
    // has read it before this class is instantiated.

    override fun register(reg: PluginRegistration) {
        val log = reg.logger("HelloPlugin")
        log.info("loaded from a separate APK")

        // Hand the service lookup to the object the UI facet reads. Resolution itself stays lazy: `register`
        // runs at startup, before any project is open, and the interpreter follows whichever one is.
        HelloInterpreter.services = reg.appServices

        reg.register(
            UI_ACTION_EP,
            SimpleAction(
                id = "com.example.hello.greet",
                text = "Hello: say hello",
                places = setOf(ActionPlaces.COMMAND_PALETTE, ActionPlaces.MORE_MENU),
                iconId = "sparkle",
            ) { ctx ->
                log.info("greet invoked from '${ctx.place.id}', project=${ctx.projectRoot ?: "none"}")
                // Written straight into the object the UI facet reads: the panel updates with no bridge.
                HelloState.greeted("the palette")
                ActionResult.message("Hello from a plugin installed as its own app.")
            },
        )

        // An editor action: same extension point, but placed at the caret. It is listed in the Alt-Enter
        // popup, the editor's overflow menu, and the palette while an editor is focused.
        reg.register(
            UI_ACTION_EP,
            SimpleAction(
                id = "com.example.hello.wrapInRunCatching",
                text = "Hello: wrap call in runCatching { }",
                places = setOf(ActionPlaces.EDITOR),
                iconId = "sparkle",
                order = 100,
                // Listing runs on caret moves, so the predicate reads the flat snapshot and nothing else.
                // Without it the action would offer itself on every caret position in every file.
                visible = { ctx ->
                    val caret = ctx.caret
                    caret != null && caret.languageId == "kotlin" && caret.nodeKind == "method_call"
                },
            ) { ctx -> wrapInRunCatching(ctx, log) },
        )

        val settings = HelloSettingsPage(log)
        reg.register(SETTINGS_PAGE_EP, settings)

        // The build facet. Contributing build logic is an extension-point registration like any other, so it
        // belongs in the engine facet next to the actions above rather than in a plugin of its own.
        //
        // The registrar's `appServices` lookup is held, not resolved here: the build plugin reads the toggle
        // on the settings page above every time a build graph is assembled, so the value is the current one.
        val services = reg.appServices
        reg.register(BUILD_PLUGIN_EP, HelloBuildReportPlugin { helloBuildReportEnabled(services, settings) })
        reg.register(SOURCE_GENERATOR_EP, HelloBuildInfoGenerator())
        reg.register(RUN_TASK_PROVIDER_EP, HelloRunTaskProvider())

        log.info("registered 2 actions, 1 settings page and 3 build contributions; the UI facet adds a tool window")
    }
}

/**
 * Replace the call at the caret with `runCatching { <call> }` and leave the result selected.
 *
 * The shape every editor action follows: read the caret snapshot and the buffer off the context, compute
 * the replacement, and return it as effects. The action never touches a file or the editor itself, which is
 * what lets the host apply the edit through its normal text path, in one undo step.
 */
private fun wrapInRunCatching(ctx: ActionContext, log: Logger): ActionResult {
    val caret = ctx.caret ?: return ActionResult.NONE
    val text = ctx.documentText ?: return ActionResult.NONE

    // Read the call from the buffer rather than from CaretContext.nodeText: that field is capped, so a long
    // node arrives truncated. The span is exact either way.
    val start = caret.nodeStart.coerceIn(0, text.length)
    val end = caret.nodeEnd.coerceIn(start, text.length)
    if (end == start) return ActionResult.NONE
    val call = text.substring(start, end)
    if (call.startsWith("runCatching")) return ActionResult.message("Already wrapped")

    // Ancestors carry their spans, so an action can report or act on what encloses the caret.
    val enclosing = caret.enclosing("method_decl")
    log.info("wrapping `$call` at $start..$end, inside a function: ${enclosing != null}")

    val replacement = "runCatching { $call }"
    return ActionResult(
        message = "Wrapped the call in runCatching",
        effects = listOf(
            ActionEffect.ApplyEdits(listOf(TextEdit.replace(start, end, replacement))),
            // Pairing an edit with a selection is how a generated result is left ready to type over.
            ActionEffect.Select(start, start + replacement.length),
        ),
    )
}

/** A Settings category the IDE renders generically from these control declarations. */
internal class HelloSettingsPage(private val log: Logger) : SettingsPage {

    override val id = "com.example.hello"
    override val title = "Hello Plugin"
    override val iconId = "sparkle"
    override val scope = SettingsScope.APPLICATION
    override val order = 2000

    override fun controls(): List<SettingControl> = listOf(
        SettingControl.Text(
            key = "name",
            title = "Who to greet",
            description = "Used by the greeting below and by the palette command.",
            default = "world",
            placeholder = "world",
        ),
        SettingControl.Toggle(
            key = "loud",
            title = "Shout the greeting",
            description = "Uppercase the greeting.",
            default = false,
        ),
        SettingControl.Toggle(
            key = HelloSettings.BUILD_REPORT,
            title = "Write a build report",
            description = "Adds a step to every build that writes build/reports/hello-build-report/.",
            default = true,
        ),
        SettingControl.Action(
            key = "greet",
            title = "Say hello",
            description = "Proves a plugin from another APK is running host code.",
            buttonLabel = "Say it",
        ),
    )

    override fun onChanged(key: String, values: PreferenceReader) {
        log.info("setting '$key' changed to '${values.raw(key)}'")
    }

    override fun onAction(key: String, values: PreferenceReader): String? {
        if (key != "greet") return null
        HelloState.greeted("Settings")
        val greeting = "Hello, ${values.string("name", "world")}!"
        return if (values.bool("loud", false)) greeting.uppercase() else greeting
    }
}
