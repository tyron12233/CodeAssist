package dev.ide.core.templates

import dev.ide.android.support.AndroidApiLevels
import dev.ide.android.support.AndroidFacet
import dev.ide.model.BuildSystemId
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateDependency
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter
import dev.ide.model.template.TextValidation
import dev.ide.plugin.PLUGIN_API_VERSION
import dev.ide.plugin.PLUGIN_SPI_VERSION

/**
 * A CodeAssist plugin, scaffolded as the app the IDE loads it from: a packaged TOML manifest under
 * `res/raw`, the marker activity the package manager is queried for, and an entry-point class implementing
 * `Plugin` (the engine facet) or `dev.ide.plugin.ui.UiPlugin` (the Compose UI facet). Building it produces an
 * installable APK; the IDE discovers it once installed and loads it on the next launch.
 *
 * The plugin SPI comes in as `compileOnly` Maven coordinates rather than being bundled, because the IDE's
 * classloader is the parent of the plugin's: the SPI, the Kotlin stdlib and the Compose runtime all resolve
 * to the IDE's own copies at runtime, and a second copy inside the plugin APK is dead weight at best.
 * [PLUGIN_SPI_VERSION] is the published version, so the coordinate written here is the one the IDE this
 * template ships in was built against.
 *
 * The generated app deliberately targets the IDE's own `minSdk`: the plugin's code runs inside the IDE's
 * process, so a plugin that installed on an older device than the IDE supports could never be loaded there.
 *
 * A UI plugin adds one requirement the engine tier does not have: its `@Composable` bodies are compiled by
 * the Compose compiler plugin, whose output has to match the Compose runtime it runs against. The Compose
 * coordinates below are therefore pinned to exactly what the IDE bundles, and are `compileOnly` like the
 * rest, since the plugin binds to the IDE's own Compose at runtime. The Compose compiler plugin itself needs
 * no declaration: CodeAssist applies it to any module whose classpath carries the Compose runtime.
 */
object CodeAssistPluginTemplate : ProjectTemplate {

    override val id = TemplateId("codeassist-plugin")
    override val displayName = "CodeAssist Plugin"
    override val description =
        "A plugin for this IDE, packaged as its own app. Adds a command, a settings page, or a UI panel."
    override val category = TemplateCategory.PLUGIN
    override val iconId = "pkg"

    override fun parameters(): List<TemplateParameter> = listOf(
        TemplateParameter.Text(
            key = PLUGIN_ID,
            label = "Plugin id",
            placeholder = "com.example.myplugin",
            validation = TextValidation.PACKAGE_NAME,
            help = "The plugin's identity, used for load order and for enabling or disabling it. " +
                "Defaults to the package name. Not a display name.",
        ),
        TemplateParameter.Choice(
            key = CONTRIBUTES,
            label = "Contributes",
            options = listOf(
                TemplateParameter.Choice.Option(BOTH, "Command and settings page"),
                TemplateParameter.Choice.Option(COMMAND, "A command"),
                TemplateParameter.Choice.Option(SETTINGS, "A settings page"),
                TemplateParameter.Choice.Option(PANEL, "A tool window panel (Compose UI)"),
            ),
            defaultIndex = 0,
            help = "What the generated plugin registers. A command is visible immediately; a settings page " +
                "appears once a project is open; a panel is a Compose tool window on the editor's side rail.",
        ),
    )

    override fun dependencies(args: TemplateArgs): List<TemplateDependency> = buildList {
        add(TemplateDependency(MODULE, "io.github.tyron12233:plugin-api:$PLUGIN_SPI_VERSION", scope = "compileOnly"))
        add(TemplateDependency(MODULE, "io.github.tyron12233:platform-core:$PLUGIN_SPI_VERSION", scope = "compileOnly"))
        if (args.string(CONTRIBUTES, BOTH) == PANEL) {
            add(
                TemplateDependency(
                    MODULE,
                    "io.github.tyron12233:plugin-ui-api:$PLUGIN_SPI_VERSION",
                    scope = "compileOnly",
                ),
            )
            // Pinned to what the IDE bundles, because a plugin's Compose calls resolve to the IDE's copy at
            // runtime. Re-derive after a Compose upgrade with
            // `./gradlew :ide-android:dependencies --configuration debugCompileClasspath`.
            add(TemplateDependency(MODULE, "androidx.compose.runtime:runtime:$COMPOSE", scope = "compileOnly"))
            add(TemplateDependency(MODULE, "androidx.compose.foundation:foundation:$COMPOSE", scope = "compileOnly"))
            add(TemplateDependency(MODULE, "androidx.compose.ui:ui:$COMPOSE", scope = "compileOnly"))
            add(TemplateDependency(MODULE, "androidx.compose.material3:material3:$MATERIAL3", scope = "compileOnly"))
            // For the generated `@Preview`. compileOnly like the rest: the annotation is not needed at
            // runtime, and the IDE detects a preview by the annotation's simple name either way.
            add(
                TemplateDependency(
                    MODULE,
                    "androidx.compose.ui:ui-tooling-preview:$COMPOSE",
                    scope = "compileOnly",
                ),
            )
        }
    }

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val pkg = args.packageName
        val pluginId = args.string(PLUGIN_ID, pkg)
        val contributes = args.string(CONTRIBUTES, BOTH)
        val command = contributes == BOTH || contributes == COMMAND
        val settings = contributes == BOTH || contributes == SETTINGS
        val panel = contributes == PANEL
        val entryClass = "${className(args.name)}Plugin"
        val uiClass = "${className(args.name)}UiPlugin"
        val path = pkg.replace('.', '/')

        scaffold.workspace.beginModification().apply {
            addProject(args.name, BuildSystemId.NATIVE, scaffold.rootDir)
            commit()
        }
        scaffold.workspace.projects.first { it.name == args.name }.beginModification().apply {
            addModule(MODULE, scaffold.moduleType("android-app")).apply {
                languageLevel = scaffold.languageLevel
                putFacet(
                    AndroidFacet(
                        namespace = pkg,
                        compileSdk = AndroidApiLevels.LATEST,
                        minSdk = IDE_MIN_SDK,
                        targetSdk = AndroidApiLevels.LATEST,
                    ),
                )
            }
            commit()
        }

        scaffold.writeText(
            "$MODULE/src/main/res/raw/codeassist_plugin.toml",
            manifestToml(pluginId, args, pkg, entryClass, uiClass, command, settings, panel),
        )
        scaffold.writeText("$MODULE/src/main/AndroidManifest.xml", androidManifest())
        scaffold.writeText("$MODULE/src/main/res/values/strings.xml", stringsXml(args.name))
        // A plugin declaring only a UI facet needs no engine entry point: the two lists in the manifest are
        // independent, and the IDE loads whichever are named.
        if (panel) {
            scaffold.writeText(
                "$MODULE/src/main/kotlin/$path/$uiClass.kt",
                uiEntryPoint(pkg, pluginId, args.name, uiClass),
            )
        } else {
            scaffold.writeText(
                "$MODULE/src/main/kotlin/$path/$entryClass.kt",
                entryPoint(pkg, pluginId, args.name, entryClass, command, settings),
            )
        }
        scaffold.writeText(
            "$MODULE/src/main/kotlin/$path/PluginInfoActivity.kt",
            infoActivity(pkg, if (panel) uiClass else entryClass),
        )
        scaffold.writeText("$MODULE/proguard-rules.pro", PROGUARD_RULES)
        scaffold.writeText("README.md", readme(args.name, pluginId, if (panel) uiClass else entryClass, panel))
    }

    // ---- generated content ------------------------------------------------------------------------

    private fun manifestToml(
        pluginId: String,
        args: TemplateArgs,
        pkg: String,
        entryClass: String,
        uiClass: String,
        command: Boolean,
        settings: Boolean,
        panel: Boolean,
    ): String {
        val capabilities = buildList {
            if (command) add("\"ui.action\"")
            if (settings) add("\"ui.settingsPage\"")
            if (panel) add("\"ui.toolWindow\"")
        }.joinToString(", ")
        // Engine facet, UI facet, or both. A plugin's two facets load off the same APK and the same
        // classloader, so they can call each other directly; naming only one is a complete plugin.
        val entryPoints =
            if (panel) """uiEntryPoints = ["$pkg.$uiClass"]"""
            else """entryPoints = ["$pkg.$entryClass"]"""
        return """
            # What the IDE reads to decide whether to load this plugin, and how to list it in
            # Settings > Plugins. It is read without running any of the plugin's code, so it has to agree
            # with what the entry point actually registers.
            [plugin]
            id = "$pluginId"
            name = "${args.name}"
            version = "1.0.0"
            # Must equal the IDE's PLUGIN_API_VERSION, or the plugin is rejected with that reason.
            apiVersion = $PLUGIN_API_VERSION
            description = "${args.name}, a CodeAssist plugin."
            $entryPoints
            capabilities = [$capabilities]
        """.trimIndent() + "\n"
    }

    private fun androidManifest(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">

            <application
                android:allowBackup="true"
                android:icon="@android:drawable/ic_menu_info_details"
                android:label="@string/app_name">

                <!-- How CodeAssist finds this app at all: it queries the package manager for this action,
                     then reads the manifest out of the resource the meta-data points at. -->
                <activity
                    android:name=".PluginInfoActivity"
                    android:exported="true"
                    android:label="@string/app_name">

                    <intent-filter>
                        <action android:name="dev.ide.codeassist.action.PLUGIN" />
                        <category android:name="android.intent.category.DEFAULT" />
                    </intent-filter>

                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>

                    <meta-data
                        android:name="dev.ide.codeassist.plugin.manifest"
                        android:resource="@raw/codeassist_plugin" />
                </activity>
            </application>

        </manifest>
    """

    private fun stringsXml(name: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <string name="app_name">$name</string>
        </resources>
    """

    /**
     * The entry-point source. Written flush-left on purpose: the host trims a template's common indentation,
     * which cannot work once multi-line values are interpolated into the middle of a block (their own lines
     * carry no indent, so there is no common prefix left to strip). Each interpolated piece is therefore
     * indented to its final column here, and blocks are indented individually so a separating blank line
     * stays genuinely blank rather than becoming a run of spaces.
     */
    private fun entryPoint(
        pkg: String,
        pluginId: String,
        displayName: String,
        entryClass: String,
        command: Boolean,
        settings: Boolean,
    ): String {
        val imports = buildList {
            if (settings) {
                add("import dev.ide.platform.settings.PreferenceReader")
                add("import dev.ide.platform.settings.SETTINGS_PAGE_EP")
                add("import dev.ide.platform.settings.SettingControl")
                add("import dev.ide.platform.settings.SettingsPage")
            }
            add("import dev.ide.plugin.Plugin")
            add("import dev.ide.plugin.PluginRegistration")
            if (command) {
                add("import dev.ide.plugin.action.ActionPlaces")
                add("import dev.ide.plugin.action.ActionResult")
                add("import dev.ide.plugin.action.SimpleAction")
                add("import dev.ide.plugin.action.UI_ACTION_EP")
            }
        }.joinToString("\n")

        val d = "$"
        val body = buildList {
            if (command) {
                add(
                    BODY_INDENT + """reg.register(
$BODY_INDENT    UI_ACTION_EP,
$BODY_INDENT    SimpleAction(
$BODY_INDENT        id = "$pluginId.hello",
$BODY_INDENT        text = "$displayName: say hello",
$BODY_INDENT        places = setOf(ActionPlaces.COMMAND_PALETTE, ActionPlaces.MORE_MENU),
$BODY_INDENT        iconId = "sparkle",
$BODY_INDENT    ) { ctx ->
$BODY_INDENT        log.info("hello invoked from '${d}{ctx.place.id}'")
$BODY_INDENT        ActionResult.message("Hello from $displayName.")
$BODY_INDENT    },
$BODY_INDENT)"""
                )
            }
            if (settings) {
                add(BODY_INDENT + "reg.register(SETTINGS_PAGE_EP, ${entryClass}SettingsPage())")
            }
        }.joinToString("\n\n")

        val settingsPage = if (!settings) "" else """


/** A Settings category. The IDE renders the controls; this class only declares them. */
private class ${entryClass}SettingsPage : SettingsPage {

    override val id = "$pluginId"
    override val title = "$displayName"
    override val iconId = "sparkle"

    override fun controls(): List<SettingControl> = listOf(
        SettingControl.Toggle(
            key = "enabled",
            title = "Do the thing",
            description = "An example toggle. Its value is stored for you, namespaced by this page's id.",
            default = true,
        ),
    )

    override fun onChanged(key: String, values: PreferenceReader) {
        // React to a changed value here.
    }
}"""

        return """package $pkg

$imports

/**
 * The entry point named by `res/raw/codeassist_plugin.toml`. The IDE instantiates it off the installed APK
 * with its own classloader as the parent, so every SPI type below binds to the IDE's copy.
 *
 * There is no manifest to declare here: that TOML is this plugin's identity, and the IDE reads it before any
 * of this code runs.
 *
 * [register] runs once, at IDE startup, after every plugin this one lists in `dependsOn`. Everything it
 * contributes is tracked and removed automatically if the plugin is unloaded.
 */
class $entryClass : Plugin {

    override fun register(reg: PluginRegistration) {
        val log = reg.logger("$entryClass")
        log.info("loaded")

$body
    }
}$settingsPage
"""
    }

    /**
     * The UI facet's source: a tool window with a real Compose body. Written flush-left for the same reason
     * as [entryPoint].
     */
    private fun uiEntryPoint(pkg: String, pluginId: String, displayName: String, uiClass: String): String {
        val d = "$"
        return """package $pkg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.ide.plugin.ui.ToolWindow
import dev.ide.plugin.ui.ToolWindowAnchor
import dev.ide.plugin.ui.UiContext
import dev.ide.plugin.ui.UiPlugin
import dev.ide.plugin.ui.UiRegistration

/**
 * The UI facet named by `res/raw/codeassist_plugin.toml`. It runs inside the IDE's process and composes into
 * the IDE's own UI: every `androidx.compose.*` reference below binds to the IDE's copy of Compose, which is
 * why the Compose dependencies are compileOnly and pinned to the version it ships.
 *
 * [contribute] runs once at startup and should only register. The body is composed when the panel is open,
 * and not before.
 *
 * To add an engine facet (services, analyzers, commands, settings pages), implement `dev.ide.plugin.Plugin`
 * in another class and add it to `entryPoints` in the manifest. Both facets load off this APK on the same
 * classloader, so they are ordinary Kotlin to each other: a shared `object` is all the plumbing there is.
 */
class $uiClass : UiPlugin {

    // Must match the `id` in the packaged manifest, which is what the IDE attributes this UI to.
    override val id = "$pluginId"

    override fun contribute(ui: UiRegistration) {
        ui.toolWindow(
            ToolWindow(
                id = "$pluginId.panel",
                title = "$displayName",
                // Icons are ids in the IDE's registry: a plugin has no resources of its own to point at.
                iconId = "sparkle",
                anchor = ToolWindowAnchor.LEFT,
            ) { ctx -> Panel(ctx) },
        )
    }
}

/** The panel body. Ordinary Compose: state, recomposition and Material 3 all work as usual. */
@Composable
private fun Panel(ctx: UiContext) {
    var taps by remember { mutableStateOf(0) }
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("$displayName", style = MaterialTheme.typography.titleMedium)
        // Read from the context and the panel recomposes when the user switches tabs.
        Text(
            ctx.activeFilePath?.let { "Editing ${d}{it.substringAfterLast('/')}" } ?: "No file open",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { taps++ }) {
            Text(if (taps == 0) "Tap me" else "Tapped ${d}taps times")
        }
    }
}

/**
 * Renders [Panel] in the editor's preview pane, so the panel can be worked on without building, installing
 * and restarting. `UiContext.preview()` stands in for the IDE: pass it the state you want to look at, and
 * add another preview for the states that are easy to get wrong (here, no file open).
 */
@Preview
@Composable
private fun PanelPreview() {
    Panel(UiContext.preview(activeFilePath = "App.kt"))
}
"""
    }

    private fun infoActivity(pkg: String, entryClass: String): String = """
        package $pkg

        import android.app.Activity
        import android.graphics.Color
        import android.os.Bundle
        import android.util.TypedValue
        import android.view.ViewGroup
        import android.widget.LinearLayout
        import android.widget.ScrollView
        import android.widget.TextView

        /**
         * This app's own screen, and the activity whose intent filter makes the app discoverable as a
         * CodeAssist plugin. The code that matters runs inside the IDE, not here.
         */
        class PluginInfoActivity : Activity() {

            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                val body = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#101014"))
                    setPadding(dp(24), dp(48), dp(24), dp(24))
                }
                body.addView(text(getString(R.string.app_name), 28f))
                body.addView(
                    text(
                        "A CodeAssist plugin.\n\n" +
                            "Install this app, then open CodeAssist and look under " +
                            "Settings > Plugins > Installed. It loads on the IDE's next launch.\n\n" +
                            "Entry point: $entryClass",
                        15f,
                    )
                )
                setContentView(
                    ScrollView(this).apply {
                        addView(body, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                )
            }

            private fun text(value: String, size: Float) = TextView(this).apply {
                this.text = value
                setTextColor(Color.parseColor("#F2F2F7"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                setPadding(0, dp(6), 0, dp(6))
            }

            private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
        }
    """

    private fun readme(name: String, pluginId: String, entryClass: String, panel: Boolean): String {
        // Kotlin raw strings do not nest, so the optional section is built first and interpolated. Written
        // at the same column as the block it lands in (writeText trims the common indent, which ignores
        // blank lines), so no re-indentation is needed and no blank line picks up trailing spaces.
        val uiSection = if (!panel) "" else """

        ## The UI facet

        `$entryClass` implements `dev.ide.plugin.ui.UiPlugin`, so it contributes UI that renders itself: a
        tool window here, and `screen` / `overlay` from the same registration object.

        - Compose is `compileOnly` and pinned to the version CodeAssist bundles. Your `@Composable` code is
          compiled by the Compose compiler plugin (CodeAssist applies it automatically once the Compose
          runtime is on the classpath) and runs against the IDE's Compose at load time, so a newer Compose
          here than the IDE ships would fail at first composition rather than at build time.
        - The panel gets a `UiContext`: where the user is, and how to open a file or another screen.
          Everything else belongs in an engine facet (`dev.ide.plugin.Plugin`, in `entryPoints`), which has
          the project model, the virtual file system, indexes and analysis. Both facets load off this one APK
          on one classloader, so they can share an `object` and call each other directly.
        - Icons are ids in CodeAssist's own registry: a plugin has no `Context` for its own package, so it
          cannot ship drawables or use `stringResource`.
"""
        return """
        # $name

        A CodeAssist plugin, packaged as its own app. CodeAssist finds it through the package manager, reads
        `$MODULE/src/main/res/raw/codeassist_plugin.toml`, and loads `$entryClass` off the installed APK.

        ## Try it

        Build and install this project, then restart CodeAssist and open **Settings > Plugins > Installed**.
        Plugins load once at startup, so a newly installed plugin appears after the next launch. If it does
        not appear, its row in that screen carries the reason.

        ## The three parts

        | Part | Where |
        | --- | --- |
        | The packaged manifest | `$MODULE/src/main/res/raw/codeassist_plugin.toml` |
        | The marker activity CodeAssist queries for | `$MODULE/src/main/AndroidManifest.xml` |
        | The ${if (panel) "UI facet" else "entry point"} the manifest names | `$entryClass.kt` |

        ## Notes

        - `id` is `$pluginId`. It is the plugin's identity, not a display name: it decides load order, what
          another plugin's `dependsOn` refers to, and what an enable/disable choice is stored against.
        - The SPI is a `compileOnly` dependency. It is never bundled: the IDE's classloader is the parent of
          this plugin's, so the SPI, the Kotlin stdlib and the Compose runtime resolve to the IDE's copies.
        - Do not minify. The IDE loads the entry point by the name in the manifest, and R8 would rename it.
        - The plugin runs inside the IDE's process, under its permissions. Class loading separates versions,
          not privileges.
        - The SPI is licensed GPL-3.0-or-later with the Classpath exception, so this plugin can carry any
          license you choose.$uiSection
    """
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** The project name reduced to a usable class-name prefix ("My Plugin!" becomes "MyPlugin"). */
    private fun className(name: String): String {
        val cleaned = name.split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotEmpty() }
            .joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
        return when {
            cleaned.isEmpty() -> "My"
            cleaned.first().isDigit() -> "P$cleaned"
            else -> cleaned
        }
    }

    /** The generated module's name; the SPI dependencies are declared against it. */
    private const val MODULE = "plugin"

    /**
     * The IDE's own `minSdk`. A plugin's code is loaded into the IDE's process, so a lower floor would only
     * let the app install on devices the IDE itself cannot run on.
     */
    private const val IDE_MIN_SDK = 26

    /** Column the registration statements sit at inside `register`. */
    private const val BODY_INDENT = "        "

    private const val PLUGIN_ID = "pluginId"
    private const val CONTRIBUTES = "contributes"
    private const val BOTH = "both"
    private const val COMMAND = "command"
    private const val SETTINGS = "settings"
    private const val PANEL = "panel"

    /**
     * The Compose the IDE bundles, which a UI plugin must compile against: its classes resolve to the IDE's
     * copies at runtime (see [dependencies]). `androidx.compose.*` rather than the Compose Multiplatform
     * coordinates, because a plugin is an Android app and that is what those map to there.
     */
    private const val COMPOSE = "1.11.2"
    private const val MATERIAL3 = "1.4.0"

    private val PROGUARD_RULES = """
        # Keep minification off for a plugin. The IDE instantiates the entry point by the class name in
        # res/raw/codeassist_plugin.toml, and R8 would rename it.
        -keep class ** { *; }
    """.trimIndent() + "\n"
}
