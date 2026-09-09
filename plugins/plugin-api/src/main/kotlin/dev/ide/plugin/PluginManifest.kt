// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin

import dev.ide.platform.PluginId

/**
 * The plugin SPI version this build of the IDE loads. A plugin built outside the IDE declares the version it
 * was compiled against as [PluginManifest.apiVersion]; a mismatch is rejected at load rather than allowed to
 * fail later as a linkage error. Bumped whenever the SPI changes incompatibly.
 *
 * **Incompatibly includes adding a parameter to [PluginManifest].** Kotlin compiles a call that relies on
 * default arguments into a synthetic constructor whose descriptor names every parameter, so a plugin
 * compiled against a manifest with one field fewer calls a method the new one does not have. That is
 * source-compatible and binary-incompatible, and the difference is invisible until a plugin built against
 * the older artifact is loaded. `2` is where this moved when `1.2.0` added [PluginManifest.uiEntryPoints]:
 * every plugin built against `1.1.0` or earlier fails, and it is better that they are told so at the gate
 * than that they throw a `NoSuchMethodError` out of a constructor.
 *
 * `3` is where `2.0.0` moved it, for the same reason at a larger scale: the project model's closed
 * vocabularies ([dev.ide.model.ContentRole], [dev.ide.model.PlatformKind], [dev.ide.model.LibraryKind],
 * [dev.ide.model.LanguageLevel], [dev.ide.model.DependencyScope] and [dev.ide.model.ClasspathEntryKind])
 * stopped being enums so that a plugin can name a value of its own. A plugin compiled against the enums
 * references `Enum` members and `values()`/`valueOf` descriptors that no longer exist, which is a linkage
 * error at first touch rather than at load.
 */
const val PLUGIN_API_VERSION: Int = 3

/**
 * The version the SPI artifacts are published under, so a project that compiles against them can be
 * scaffolded with a coordinate that resolves:
 *
 * ```
 * compileOnly(platform("io.github.tyron12233:plugin-bom:2.7.0"))
 * compileOnly("io.github.tyron12233:plugin-api")
 * compileOnly("io.github.tyron12233:platform-core")
 * ```
 *
 * Independent of the IDE's own version, because the SPI changes far less often than the app ships.
 * Whether a plugin is *compatible* is decided by [PLUGIN_API_VERSION] and the manifest's
 * [PluginManifest.minHostVersion], never by this coordinate. The publishing configuration reads this
 * constant, so what a scaffolded project asks for and what is actually published cannot drift.
 *
 * Semver over the SPI *source* surface: a minor bump adds to it and leaves every existing plugin compiling.
 * Whether an already-compiled plugin still loads is a separate question, answered by [PLUGIN_API_VERSION].
 * `1.1.0` added the editor action tier (the `EDITOR` place, [action.CaretContext] on [action.ActionContext],
 * and the editing, caret and file [action.ActionEffect]s). `1.2.0` added the UI facet: a new artifact,
 * `plugin-ui-api`, and [PluginManifest.uiEntryPoints] to name the classes in it, which moved
 * [PLUGIN_API_VERSION] to `2`. `1.3.0` stopped requiring a plugin to declare a [PluginManifest] at all (see
 * [Plugin.manifest]), which is what keeps the next field from costing a version again.
 *
 * `2.0.0` is a major bump because it is the first change that can stop an existing plugin from *compiling*.
 * It opened the project model's closed vocabularies (see [PLUGIN_API_VERSION]) so that a plugin for a
 * language laid out unlike a JVM module can name its own content roles, platform, packaging, language level,
 * dependency scopes and classpath-entry kinds; the constants and `values()`/`valueOf` survive, but an
 * exhaustive `when` over one of them now needs an `else`. It also:
 *
 *  - promoted the model registries a plugin needs to reach ([dev.ide.model.FacetCodecRegistry],
 *    [dev.ide.model.ModuleTypeRegistry], [dev.ide.model.ProjectTemplateRegistry],
 *    [dev.ide.model.FileIconRegistry]) out of the unpublished `project-model-impl` into `project-model-api`;
 *  - added `dev.ide.lang.CompilationContextProvider`, so a plugin supplies the analysis inputs for its own
 *    language rather than receiving the model's JVM reading of a module, and gave
 *    `dev.ide.lang.CompilationContext` defaults for every member a non-JVM language has no answer for
 *    (`outputDir` became nullable, which narrows an existing override rather than breaking it);
 *  - added the `lang.backend`, `model.moduleType` and `model.facet` capabilities such a plugin declares.
 *
 * `docs/plugin-spi-2.0-migration.md` is the upgrade path for a plugin written against `1.x`. Note that
 * `2.0.0` itself was never published, so `2.1.0` below is the first `2.x` coordinate that resolves; it
 * carries the whole of the change described above.
 *
 * `2.1.0` opened the interpreter: a new artifact, `interp-api`, through which a plugin runs the code in the
 * user's project, plus the pieces a plugin needs to show or run the result. It is additive over `2.0.0`, so
 * [PLUGIN_API_VERSION] stays at `3`. It added:
 *
 *  - `dev.ide.interp.api.CodeInterpreter` (the `platform.codeInterpreter` service) and its sessions, for
 *    interpreting a project's Kotlin source with no compile step or its compiled classes on the bytecode VM;
 *  - `dev.ide.plugin.ui.EditorPreview`, a preview pane for a file kind the IDE's own four do not cover, and
 *    `UiRegistration.editorPreview` to register one;
 *  - the interpret-run surface in `build-api` (`ProgramInterpreter`, `InterpretRunRequest`,
 *    `InterpretExecTask`, `ProgramIo` and `RunWindow`, previously unpublished), and
 *    `BuildContext.programInterpreter`, so a plugin's own Run row can run what it built;
 *  - the `interp.run` and `ui.editorPreview` capabilities.
 *
 * `docs/plugin-interpreter.md` is the guide to that surface.
 *
 * `2.2.0` opened the user's resources: [dev.ide.model.ModuleResources] (the `platform.moduleResources`
 * service), through which a plugin reads what a module holds and generates into it. Additive over `2.1.0` in
 * an already-published artifact (`project-model-api`), so [PLUGIN_API_VERSION] stays at `3` and no coordinate
 * is added to `plugin-bom`. It added:
 *
 *  - the module-type-neutral half: `resourceRoots` and `putResourceFile` over any [dev.ide.model.ContentRole],
 *    plus [dev.ide.model.contentRootsFor] under them. This is what "generate a file into the user's module
 *    and have the IDE notice" needed: writing the file was always possible, and getting the build, the index
 *    and the editor to see it was not. It works on `src/main/resources`, on `assets/`, and on a role a plugin
 *    defined for a module type of its own;
 *  - the Android resource model on top: the query half ([dev.ide.model.ResourceEntry],
 *    [dev.ide.model.ResourceFilter]), reading the same merged, buffer-aware repository the IDE's own
 *    `@type/name` resolution and synthetic `R` read, and `putValueResource`/`createResourceFile`, which are
 *    the engine's resource authoring with the module, the conflict policy and the outcome named
 *    ([dev.ide.model.ResourceConflict], [dev.ide.model.ResourceWrite]) where the quick fix they were written
 *    for had all three implied. A module whose type is not Android's is answered, not thrown at.
 *
 * A plugin that writes declares [PluginCapabilities.FS_WRITE]; nothing here needed a capability of its own.
 *
 * `2.2.0` also opened three SPI modules that were finished and simply never published, none of which needed
 * an API change to do it: `vcs-api` (version-control providers, which the built-in Git plugin already
 * dogfoods), `agent-api` (agent tools, the workspace they act on, and the provider-neutral LLM interface),
 * and `block-api` (block-editor mappings). `plugin-bom` therefore pins coroutines as well as Compose, since
 * `agent-api` exposes `Flow` and a plugin's suspend code has to bind to the copy the IDE bundles.
 *
 * And it added [PluginRegistration.dataDir], a directory a plugin owns for state that is not a setting:
 * [dev.ide.platform.settings.PreferenceStore] covers string key/value, and a plugin with a cache or a
 * downloaded index was otherwise picking a path the IDE neither cleans up nor backs up.
 *
 * `2.3.0` opened facet configuration to a plugin that does not own the facet:
 * [dev.ide.model.ModifiableModule.putFacetData] writes one as its `module.toml` table plus values, with no
 * [dev.ide.model.Facet] instance to encode. A project template scaffolding a module of another plugin's type,
 * or an importer translating a foreign build file, knows the table and the values but not the class, which
 * only the plugin declaring the facet has. The way through before was to declare a duplicate facet and aim
 * its codec at the same table, which silently takes over that table's persistence for every module in every
 * project. Additive over `2.2.0`, so [PLUGIN_API_VERSION] stays at `3`. It also tightened what the model
 * accepts around facets, all of it previously undetected until a save or a later read:
 *
 *  - a facet table in [dev.ide.model.RESERVED_FACET_TABLES] is refused, by both `putFacetData` and
 *    [dev.ide.model.FacetCodecRegistry.register]. Those tables are the model's own, and a facet claiming one
 *    overwrote the module's configuration on the next save;
 *  - facet values are checked against what TOML can hold where they are staged, so a codec emitting a null or
 *    a value type with no representation names its own table instead of failing an unrelated save later;
 *  - a `module.toml` table two codecs claim is logged, naming both. Last registration still wins, but the
 *    loser keeps working through [dev.ide.model.FacetCodecRegistry.codecFor], so the takeover had no symptom.
 *
 * `2.4.0` opened the editor's appearance: [dev.ide.plugin.editor.EditorDecorationProvider] on
 * `platform.editorDecoration` marks up the text of any open file with tinted ranges, gutter glyphs and
 * inlays. Semantic highlighting, folding and type hints were already contributable, but only by implementing
 * a whole `dev.ide.lang.LanguageBackend` for a language, which a coverage tint, a version-control change bar
 * or a bookmark has no business doing: they apply to files in every language and know nothing about parsing
 * one. Additive, so [PLUGIN_API_VERSION] stays at `3`.
 *
 * A decoration is data, and its color is a role ([dev.ide.plugin.editor.DecorationTint]) the host resolves
 * against the active theme rather than a literal: the IDE's themes are generated, so a plugin has nothing
 * fixed to have hard-coded. Providers are pulled on the editor's own debounced pass run, so a provider needs
 * no channel into the UI and is cancelled when the user types. It also added
 * [PluginCapabilities.UI_EDITOR_DECORATION], which is worth declaring because it is the one contribution
 * that changes how the user's own code looks rather than adding a surface they choose to open.
 *
 * `2.5.0` finished the editor surface with the two things a decoration cannot be, both in `plugin-ui-api` and
 * both registered by a UI facet ([PluginCapabilities.UI_EDITOR_LAYER],
 * [PluginCapabilities.UI_EDITOR_PAINTER]). Additive, so [PLUGIN_API_VERSION] stays at `3`.
 *
 *  - `dev.ide.plugin.ui.EditorLayer` places real composables at document positions, anchored at an offset,
 *    after a line, or on a row above one. A hover card, an inline button and a code-lens row need touch
 *    targets and Material components, which no amount of data can express. The host positions them in the
 *    layout phase, so they scroll with the text without recomposing.
 *  - `dev.ide.plugin.ui.EditorPainter` draws into the editor's canvas, below the text or above it. This is
 *    the escape hatch for the one thing the decoration tier refuses on purpose: a plugin whose colors are its
 *    own (a blame heatmap, a coverage gradient) rather than one of the theme's named roles.
 *
 * The painter is also the first contribution in the SPI that runs inside a draw, which is why it is the only
 * one with a failure policy of its own: a painter that throws is retired for the rest of the session rather
 * than retried, because a draw that throws once throws every frame. `plugin-ui-api` gained a `compileOnly`
 * dependency on compose-ui for that one signature, on the same terms as the runtime it already had.
 *
 * `2.6.0` opened the tab itself: `dev.ide.plugin.ui.EditorViewMode` adds a surface beside the IDE's own Code,
 * Blocks, Preview and Split, and its `isDefault` claim decides which files OPEN into that surface, which is
 * how a plugin comes to own a file kind. Additive, so [PLUGIN_API_VERSION] stays at `3`.
 *
 * A pane is a view of the tab's buffer rather than a second copy of it: `replaceText` writes through the one
 * document the code editor edits, so a pane's edit is undoable, is analysed, and marks the tab dirty exactly
 * as typing does, and the user can switch to Code and see what the pane wrote. Code stays reachable from the
 * toggle even for a claimed kind, deliberately: a pane can be wrong about a file, and a user who cannot see
 * the text has no way to find out why.
 *
 * The registry behind this existed and was never wired to anything. Wiring it also meant opening the UI's own
 * `EditorViewMode` from an enum into a value over its persisted id, the same move the project model's
 * vocabularies made in `2.0.0` and for the same reason. The four built-in ids are unchanged, so no session
 * file moved.
 *
 * `2.7.0` added a keymap: `dev.ide.plugin.keymap` and `platform.keyBinding`, so a plugin can bind a keyboard
 * shortcut and a user can rebind one. Additive, so [PLUGIN_API_VERSION] stays at `3`.
 *
 * A binding is a `Shortcut` (one press or a chord) plus an action id plus a `KeyContext`, and a shortcut's
 * `Primary` modifier is Command on macOS and Control elsewhere, which is exactly what the IDE's shortcuts
 * meant when they were `isCtrlPressed || isMetaPressed` conditions. Three layers resolve a press, in
 * decreasing authority: the user's own bindings, then the contributions on the extension point, then nothing.
 * The user always wins, or rebinding would be advisory. A shortcut two commands claim is reported rather than
 * resolved silently, since the loser looks broken to whoever presses the key.
 *
 * The editor's own shortcuts moved onto it: roughly twenty `if` conditions inside the editor's key handler
 * became `dev.ide.ui.ext.EDITOR_KEY_DEFAULTS`, a table the editor falls back to when no engine is present and
 * the engine turns into bindings when it is. Each modifier variant that used to be a branch inside one
 * handler (declaration vs implementation, next vs previous diagnostic, line vs block comment) is now its own
 * command, and so independently rebindable.
 *
 * What deliberately did NOT move: caret motion, text input, and the keys a popup or a live template owns
 * while it is open. Those are the text-input and modal contracts rather than commands, they have no action to
 * bind to, and an IME commit is not a key press at all.
 */
const val PLUGIN_SPI_VERSION: String = "2.7.0"

/**
 * A plugin's identity and load-order metadata. Built-ins construct this as a Kotlin literal on their entry
 * point; the same shape round-trips through TOML for a future externally-packaged plugin, so the loader for
 * that tier parses into this exact type without an SPI change.
 *
 * The internal (one-classpath) tier uses [id]/[name]/[version]/[apiVersion]/[dependsOn]. The remaining fields
 * are carried but inert until the external/dex tier enforces them: [entryPoints] and [uiEntryPoints] (the
 * class FQCNs a loader instantiates, unused for built-ins, where the class *is* the entry point),
 * [capabilities] (declared, prompted, and enforced only for untrusted code), [minHostVersion], and [trusted]
 * (built-ins are trusted; an external plugin defaults untrusted).
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    /** Host plugin-SPI/ABI compatibility floor. Bumped when this SPI changes incompatibly. */
    val apiVersion: Int = PLUGIN_API_VERSION,
    /** Ids of plugins that must load before this one. Drives the topological load order. */
    val dependsOn: List<String> = emptyList(),
    /** One-line human-readable summary, shown in the Plugins settings UI. */
    val description: String = "",
    /** An essential plugin cannot be disabled — the IDE cannot function without it (the platform substrate,
     *  the default language backend + resolution fallback, the engine's core scoped services). Essentials and
     *  everything they transitively depend on stay loaded regardless of the user's disabled set. */
    val essential: Boolean = false,

    // Inert until the external/dex tier (parsed + carried now, enforced by that tier's loader):
    val entryPoints: List<String> = emptyList(),
    /**
     * The class FQCNs implementing the UI facet (`dev.ide.plugin.ui.UiPlugin`, from the `plugin-ui-api`
     * artifact), instantiated off the same classloader as [entryPoints], so a plugin's two facets can call
     * each other directly.
     *
     * Independent of [entryPoints]: a plugin may declare either list, or both. A UI facet is instantiated
     * only for a plugin that is enabled, consented to, and whose engine facet loaded, so the UI is governed
     * by exactly the decision the engine facet is.
     *
     * A class named in BOTH lists is instantiated once, so a plugin whose two facets are one class holds its
     * state in ordinary fields. Two classes stay two objects, and keep failing independently: a UI facet that
     * throws is reported while the engine facet goes on loading.
     */
    val uiEntryPoints: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val minHostVersion: String? = null,
    val trusted: Boolean = true,
) {
    /** The attribution id every contribution this plugin makes is tagged with. */
    val pluginId: PluginId get() = PluginId(id)
}
