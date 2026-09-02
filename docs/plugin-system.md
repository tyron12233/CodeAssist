# The plugin system

One extensibility model underlies the whole IDE: every capability, whether shipped in-tree or (in future)
loaded from a separate artifact, is contributed by a **plugin** through the same extension-point registry and
scoped-service container. The IDE's own built-ins are the first consumers of this API, so there is no
separate, privileged host-wiring path.

> **Writing a plugin?** This page describes the model as built. For the task-oriented, step-by-step
> guide — creating a plugin module, contributing to extension points, adding services, settings pages,
> actions and Compose UI, and two worked examples — see
> [writing-plugins.md](writing-plugins.md).

This document describes the model as built. It covers both tiers: the internal one, where a plugin is a module
compiled into the app, and the installed one, where a plugin is a separate app the user installs and the IDE
loads off the device (see [Installed plugins](#installed-plugins)). Both load through the same
`PluginManager`. What is still outstanding is the trust half, listed under [Future work](#future-work) and
designed in [ui-extensibility-and-plugin-api.md](ui-extensibility-and-plugin-api.md).

## The substrate (`platform-core`)

Two primitives everything else builds on:

- **Extension points.** `ExtensionPoint<T>(id)` + `ExtensionRegistry.register(ep, impl, pluginId): Disposable`
  / `extensions(ep): List<T>`. Typed, string-id keyed, contributed dynamically, attributed to a `PluginId`,
  and individually removable. The registry is hierarchical: a per-project registry parents the application
  registry, so a query resolves app-global and project-local contributions together. `unregisterAll(pluginId)`
  removes every contribution a plugin made in one call.
- **Scoped services.** `SERVICE_EP` carries `ServiceDescriptor`s into a `ServiceContainer` with three scopes
  (`APPLICATION` / `WORKSPACE` / `MODULE`). A service is built lazily on first resolution, cached per scope,
  and disposed with its container. So "register a service" is just a specific kind of "register an extension".

## The plugin SPI (`plugin-api`)

A plugin declares its identity and contributions through three types:

```kotlin
interface Plugin {
    val manifest: PluginManifest
    fun register(reg: PluginRegistration)
    fun dispose() {}
}

data class PluginManifest(
    val id: String, val name: String, val version: String = "1.0.0",
    val apiVersion: Int = 1,
    val dependsOn: List<String> = emptyList(),   // plugin ids; drives topological load order
    // Installed plugins only: entryPoints (the FQCNs the loader instantiates) and minHostVersion are
    // enforced at load; capabilities and trusted are parsed and carried, but nothing reads them yet.
)

interface PluginRegistration {
    val pluginId: PluginId
    fun <T : Any> register(ep: ExtensionPoint<T>, impl: T): Disposable
    fun <T : Any> service(key: ServiceKey<T>, level: ServiceScopeLevel, factory: ServiceFactory<T>): Disposable
    fun contributeVia(block: (ExtensionRegistry, PluginId) -> Unit)   // bridges existing facades
    fun onDispose(d: Disposable)

    val messageBus: MessageBus            // publish (own topics via syncPublisher, or the IDE's lifecycle topics)
    fun busConnection(): MessageBusConnection   // subscribe; tracked for unload (auto-unsubscribed)
    fun logger(tag: String): Logger       // attributed to pluginId → filterable in the Logs viewer
}
```

`PluginRegistration` attributes every contribution to the plugin's id automatically and tracks each returned
`Disposable`, so a plugin never threads a `PluginId` by hand. `contributeVia` is the bridge for existing
`(ExtensionRegistry, PluginId)` facades (e.g. `AndroidSupport.register`, `JdtAnalysisSupport.register`); those
discard their per-registration handles, so their unload relies on the bulk `unregisterAll(pluginId)` sweep,
which is exact because they attribute to the same id.

For built-ins the manifest is a Kotlin literal on the entry-point class (the "manifest + entry point" model);
an installed plugin ships the same `PluginManifest` shape as TOML, read by `PluginManifestToml`
(see [Installed plugins](#installed-plugins)).

### Events and logging

`PluginRegistration` also hands a plugin the eventing + logging substrate, so a plugin can *observe* and *report*,
not just contribute:

- **Message bus.** `messageBus` is the application-wide `MessageBus` (the one every project shares, threaded in
  from `PlatformCore.messageBus`), for **publishing** — either the IDE's own lifecycle topics or a `Topic` the
  plugin defines itself for plugin-to-plugin messaging. **Subscribing** goes through `busConnection()`, which
  returns a `MessageBusConnection` already tracked for unload, so its subscriptions are removed automatically
  (a raw `messageBus.connect()` is not tracked). Beyond the existing spines (`VfsTopics` / `ProjectModelTopics` /
  `SettingsTopics`), the IDE publishes a set of plugin-facing lifecycle topics in `ide-core`'s
  `dev.ide.core.event.IdeEventTopics`: editor (open/close/active/selection), build, run, analysis diagnostics,
  project open/close, and indexing. These are published from the point that owns each transition — `BuildService`
  (build/run), `EditorBackend` (editor, driven by the UI), `IdeServicesBackend.swapEngine` (project), and
  `IdeServices` via the existing analysis/index listener seams — always guarded so a faulty subscriber can't break
  the engine. Delivery is synchronous on the transition's thread (a build/analysis pass is a background
  dispatcher, not the UI thread).
- **Logging.** `logger(tag)` returns a `Logger` whose records are attributed to the plugin's id (`LogRecord.source`),
  so a plugin's output flows into the same `Log` facade as the IDE's and is separable in the in-app Logs viewer
  (which gained a per-plugin filter). The attribution is stamped by the platform, not the caller.

## The manager (`plugin-impl`)

`PluginManager(registry, bus)` (the `bus` is the app's `MessageBus`, handed to each plugin's registrar) loads a
set of plugins in the topological order of `manifest.dependsOn` (throwing on
a missing dependency or a cycle) and unloads a plugin by disposing its tracked `Disposable`s (LIFO) and then
`unregisterAll(id)`. Both teardown paths are list-removals, so running both is idempotent. Making load order
an explicit `dependsOn` edge replaces the old reliance on hand-tuned registration sequencing (for example, the
JDT language backend must load first so it is the `backendFor` fallback; the Kotlin and XML backends and the
analysis plugins declare `dependsOn = ["jdt-language"]`).

`load` records a plugin before calling `register`, so a plugin that throws part-way through is rolled back
rather than leaving untracked contributions on the registry. Alongside the strict `loadAll(plugins)` there is
`loadAll(plugins, onError)`, which rolls a failed plugin back, skips everything that transitively depends on
it, and carries on. That is the path for plugins the IDE did not write: the host rethrows for its own
built-ins, where a failure is its bug, and records the reason for an installed plugin, where a failure must
cost the user that plugin and not the launch.

## The IDE dogfoods it (`ide-core`)

`ide-core/BuiltInPlugins.kt` holds the IDE's own built-ins as a set of `Plugin`s (`platform`, `jdt-language`,
`java-psi-language`, `kotlin-language`, `xml-language`, `java-support`, `kotlin-support`, `blocks`,
`android-support`, `samples`, `completion-builtins`, `indexing`, `jdt-analysis`, `kotlin-analysis`,
`xml-analysis`, `android-xml`, `ide-core-services`, `ide-core-actions`, `editor-text-actions`, …), each
mapping to the `PluginId` it
contributed under before. Most are non-essential and can be disabled; the essentials (`platform`,
`jdt-language`, `java-psi-language`, `ide-core-services`) cannot. For example `blocks` contributes the Java
block decomposition on the `blockMapping` EP — disabling it drops the only mapping, so the engine's
`BlockService` reports the block editor unavailable and the UI hides the Code/Blocks toggle.
`ApplicationEnvironment` builds a `PluginManager` over the application registry and loads
`BuiltInPlugins.assemble(...)`; there is no imperative "register everything" block anymore.

Contributions that must reach the currently-open project (the synthetic `R` class, the completion
acceptance-stats weigher, the XML resource host, the app-compat action, the Run/Stop/Re-index commands) take
`ApplicationEnvironment` and read `env.activeEngine` lazily at callback time, never during `register`.

The extension points the built-ins contribute to (all `platform.*`) include: language backends
(`languageBackend`), file-type mappings (`fileType`), completion (`completionContributor` / `completionWeigher`
/ `postfixTemplate`), indexes (`index`), analysis (`analyzer` / `diagnosticProvider` / `quickFixProvider` /
`actionProvider`), synthetic classes (`syntheticClass`), block mapping (`blockMapping`), module types
(`moduleType`), project templates (`projectTemplate`), file icons (`fileIcon`), facet codecs (`facetCodec`),
Kotlin compiler plugins (`kotlinCompilerPlugin`), build systems (`buildSystem`), run-task providers
(`runTaskProvider`), settings pages (`settingsPage`), UI actions (`uiAction` / `actionGroup`), and scoped
services (`service`).

### Editor actions

Editor actions come from two extension points that resolve into one list, because the two halves need
different things and only one of them can cross the `IdeBackend` port.

`platform.uiAction` carries the portable half. An `IdeAction` placed on `ActionPlaces.EDITOR` receives a
flat `CaretContext` on `ActionContext.caret` (the caret offset, the file's language, the innermost node's
kind and span and text, and the ancestor chain with each ancestor's kind and span) plus the live buffer as
`ActionContext.documentText`. It returns `ActionEffect`s, which now include `ApplyEdits`,
`ApplyWorkspaceEdit`, `MoveCaret`, `Select`, `CreateFile`, `RenameFile` and `DeleteFile`, so a portable
action can rewrite code, place the caret, and move a declaration into a new file. Edits are applied through
the editor's text path, joining the same undo step as typing.

`platform.actionProvider` carries the type-aware half. `ActionProvider.actions(ctx: EditorActionContext)`
receives the live `DomNode` at the caret, its ancestor chain, and the `AnalysisTarget`'s resolver, index and
module, and returns `QuickFix`es producing a `WorkspaceEdit`. The engine resolves the caret's position once
per listing pass (`EditorActionContext.of`) and shares it across every provider, rather than each one
repeating the same walk. `CaretSnapshot` is the single place either tier's caret view is derived from, and
`AnalysisService.caretSnapshotAt` is how the portable tier obtains one.

`AnalysisService.editorActionsAt` merges the diagnostic quick-fixes with the provider intentions;
`EditorBackend.actionsAt` then appends the `EDITOR`-placed plugin actions, which carry a `UiAction.actionId`
and so round-trip through `invokeAction` (getting the full effect set) rather than by list index. Three
surfaces render the merged result: the Alt-Enter popup, the editor's overflow context menu (where
`ActionGroup`s become inline-expanding submenus), and the command palette when an editor is focused.

The built-ins split along the same line. `editor-text-actions` contributes the language-neutral line
actions (comment and uncomment, move a line, sort a selection) and move-to-a-new-file on the portable tier;
`jdt-analysis` contributes the Java member generators (constructor, `equals`/`hashCode`, `toString`,
accessors) and `kotlin-analysis` the Kotlin intentions (surround with, introduce variable, implement
members, expression or block body, braces, explicit type, extract function) on the analysis tier.

### File-to-language routing

A file's `LanguageId` is resolved through `FILE_TYPE_EP` (`FileTypeMapping`, in `language-api`) rather than a
hardcoded `when` in the host, so associating a language with a file suffix is a registration. A mapping may
target a language with no registered backend (ProGuard, Markdown); such a file is edited as plain text and,
because the analysis pipeline dispatches by language, is never analysed as Java.

### Build-system and run-task selection

The build service resolves a build system two ways: one contributed to `BUILD_SYSTEM_EP` whose id matches the
project's `buildSystemId` owns that project's builds outright (how a foreign build system takes over a project
its importer claimed), otherwise selection is by `BuildSystem.supports(moduleType)`: the built-in Java/Android
systems first, then the contributed ones, so a plugin can add support for a new module type without a host edit.
The built-ins stay concrete engine fields rather than extensions on purpose: they are per-project and
context-heavy (the Android one defers SDK detection), so the point is the seam for *additions*, not the registry
the built-ins themselves flow through.

Run-picker options come from `RUN_TASK_PROVIDER_EP` (`RunTaskProvider` → `RunTaskSpec`) and from the project's
bound build system (`BuildSystem.runTasks`), merged after the built-in enumeration. Dispatch follows the same
split: an id carrying a built-in prefix (`build:` / `run:` / `assemble:`) runs through the host's pipeline, and
any other id goes back to its contributor's `actionFor`, which returns a `RunAction` (graph + console header +
optional post-build step) the host runs through the usual executor, console, and cancellation path.

Tasks themselves are contributable: `BUILD_PLUGIN_EP` (`BuildPlugin`) is applied to every graph the host
realizes, after the build system's own plugins, so a plugin's task can be wired by name to the tasks the
pipeline just registered. See `docs/build-system.md` for the task contract and lifecycle names.

## Platform ports as host services

Desktop-versus-Android host capabilities (the dex runner, APK installer, custom-view runtime, Kotlin
compiler-plugin loader, Android device tools, real-view runtime) are modelled as `APPLICATION`-scoped services
(`ide-core/PlatformPorts.kt`) rather than constructor-threaded through the engine. `ProjectManager` registers
whichever ports the launcher supplied on the application container, and `IdeServices` resolves each with
`getServiceOrNull`. Absent (desktop, or a standalone test with no host) resolves null, so the consumer keeps
its in-process default; `getServiceOrNull` is the single resolution path. The `desktop`/`onDevice` launcher
factories are unchanged.

## Installed plugins

A plugin does not have to ship inside the IDE. On Android, a plugin is a **separate app the user installs**,
and the IDE reads it off the device. Both tiers load through the same `PluginManager`, in one topological
order, so an installed plugin's `dependsOn` edge onto a built-in is a real edge rather than a convention.

Three types carry the tier (`plugin-api`, `dev.ide.plugin.external`):

```kotlin
interface PluginSource {                       // a place installed plugins come from
    val id: String
    fun discover(): List<DiscoveredPlugin>
}

interface DiscoveredPlugin {                   // found, not yet loaded
    val manifest: PluginManifest               // parsed from the package, not from its code
    val origin: PluginOrigin                   // source id + package name + signing certificate
    fun classLoader(): ClassLoader             // called only for an enabled plugin
}
```

**Discovery reads manifests, not code.** A source returns manifests only; `ApplicationEnvironment` merges them
with the built-ins into the one `PluginCatalog`, applies the user's disabled set, and only then asks a
surviving plugin for a classloader. A plugin the user turned off never gets one, let alone a `register` call.

**The manifest is the same shape as a built-in's.** Built-ins carry `PluginManifest` as a Kotlin literal;
an installed plugin ships it as TOML, which `PluginManifestToml` (`ide-core`) is the only reader of. Two
fields are the host's to decide and are ignored whatever the file says: `essential` (a plugin cannot make
itself undisablable) and `trusted` (which follows from the origin's signature).

```toml
[plugin]
id = "com.example.hello"
name = "Hello"
version = "1.0.0"
apiVersion = 1
description = "Adds a Hello tool window."
entryPoints = ["com.example.hello.HelloPlugin"]
dependsOn = ["kotlin-language"]
capabilities = ["ui.toolWindow"]
minHostVersion = "3.11.0"
```

**Loading is failure-tolerant end to end.** `ExternalPluginLoader` (`plugin-impl`) checks `apiVersion` against
`PLUGIN_API_VERSION` and `minHostVersion` against the running IDE, builds the classloader, and instantiates
each entry point, returning `Result.Failed` with a user-facing reason rather than throwing. The discovered
manifest stays authoritative: the instantiated class's own `manifest` is ignored, so a plugin cannot claim an
id or a dependency edge other than the one the catalogue already used to ask the user. `PluginManager` gained
a `loadAll(plugins, onError)` that rolls back a plugin whose `register` throws, skips its dependents, and
carries on; the host rethrows for built-ins (its own bug) and records the reason for installed ones. Every
reason reaches the Plugins settings screen, which lists Built-in and Installed under separate tabs and shows
the failure against the row it belongs to.

### The Android source

`ApkPluginSource` (`:ide-android`) enumerates installed plugin apps. A plugin app declares a marker activity
and points a `meta-data` entry at the raw resource holding its manifest:

```xml
<activity android:name=".PluginInfoActivity" android:exported="true">
    <intent-filter>
        <action android:name="dev.ide.codeassist.action.PLUGIN" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
    <meta-data android:name="dev.ide.codeassist.plugin.manifest"
               android:resource="@raw/codeassist_plugin" />
</activity>
```

The activity is both the discovery marker and the plugin app's own screen, so the plugin is a real app rather
than a bare code container. The IDE's manifest carries a matching `<queries>` entry, without which package
visibility on API 30+ hides the plugin apps entirely.

Code comes off the installed APK as the system installed it: `PathClassLoader` over
`ApplicationInfo.sourceDir` plus its splits. Nothing is downloaded, written, or dexed, which is what separates
this path from loading a compiled artifact at runtime. Two consequences fall out of that:

- the APK is already dexed and optimised by the platform, so there is no D8 step and no
  content-addressed dex cache to maintain (the path `ArtKotlinPluginLoader` needs for compiler plugins); and
- the install directory is read-only, so the Android 14 rule that a dynamically loaded file must not be
  writable is satisfied by construction rather than by an explicit `setReadOnly`.

The parent is the IDE's own classloader, so a plugin's references to the plugin SPI, the Kotlin stdlib, and
the Compose runtime bind to the IDE's copies and cannot be shadowed by a second version inside the plugin APK.
`ApkPluginSource` records the package's signing certificate on the `PluginOrigin` and can be constrained to an
installer allowlist, which is what a trust model reads; nothing else consumes either yet.

**Isolation.** An installed plugin runs in the IDE's process, under its UID and its granted permissions.
Classloader separation is a versioning boundary, not a security one. The capability field in the manifest is
parsed and carried, but nothing reads it yet.

## Future work

- **Enforcing capabilities:** an installed plugin's declared `capabilities` drive install-time consent and a
  runtime permission broker modelled on the run sandbox's. See `docs/ui-extensibility-and-plugin-api.md`.
- **A desktop plugin source:** the same SPI over a plugins directory (`URLClassLoader`, parent = app
  classloader), plus install and uninstall for both hosts.
- **UI contributions from an installed plugin:** `UiPlugin` is discovered per built-in today; the external
  tier loads engine facets only.
- **UI contribution unification:** an `ide-ui-api` module homing the Compose-bearing registries (tool windows,
  screens, editor view modes, UI actions, tree icons) so UI contributions flow through the same plugin model.
- **Remaining host capabilities:** the `IdeServicesBackend`-layer ports (analytics, the build-runner factory,
  the notifications gate) and the build-system / run-task selectors are not yet EP- or service-modelled.
