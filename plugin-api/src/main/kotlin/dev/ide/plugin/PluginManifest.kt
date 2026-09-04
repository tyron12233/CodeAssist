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
 * compileOnly(platform("io.github.tyron12233:plugin-bom:2.1.0"))
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
 * `docs/plugin-spi-2.0-migration.md` is the upgrade path for a plugin written against `1.x`.
 *
 * `2.1.0` opened the interpreter: a new artifact, `interp-api`, through which a plugin runs the code in the
 * user's project, plus the pieces a plugin needs to show or run the result. It is additive, so
 * [PLUGIN_API_VERSION] is unchanged and a `2.0.0` plugin keeps loading. It added:
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
 */
const val PLUGIN_SPI_VERSION: String = "2.1.0"

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
