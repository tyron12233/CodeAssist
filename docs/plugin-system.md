# The plugin system

One extensibility model underlies the whole IDE: every capability, whether shipped in-tree or (in future)
loaded from a separate artifact, is contributed by a **plugin** through the same extension-point registry and
scoped-service container. The IDE's own built-ins are the first consumers of this API, so there is no
separate, privileged host-wiring path.

This document describes the model as built (the internal, one-classpath tier). The externally-packaged,
classloader-isolated, trust-gated tier is future work; see [Future work](#future-work) and
`docs/ui-extensibility-and-plugin-api.md`.

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
    // Carried but inert until the external tier: entryPoints, capabilities, minHostVersion, trusted.
)

interface PluginRegistration {
    val pluginId: PluginId
    fun <T : Any> register(ep: ExtensionPoint<T>, impl: T): Disposable
    fun <T : Any> service(key: ServiceKey<T>, level: ServiceScopeLevel, factory: ServiceFactory<T>): Disposable
    fun contributeVia(block: (ExtensionRegistry, PluginId) -> Unit)   // bridges existing facades
    fun onDispose(d: Disposable)
}
```

`PluginRegistration` attributes every contribution to the plugin's id automatically and tracks each returned
`Disposable`, so a plugin never threads a `PluginId` by hand. `contributeVia` is the bridge for existing
`(ExtensionRegistry, PluginId)` facades (e.g. `AndroidSupport.register`, `JdtAnalysisSupport.register`); those
discard their per-registration handles, so their unload relies on the bulk `unregisterAll(pluginId)` sweep,
which is exact because they attribute to the same id.

For built-ins the manifest is a Kotlin literal on the entry-point class (the "manifest + entry point" model);
the same `PluginManifest` shape is TOML-parseable, reserved for the external tier's on-disk manifests.

## The manager (`plugin-impl`)

`PluginManager(registry)` loads a set of plugins in the topological order of `manifest.dependsOn` (throwing on
a missing dependency or a cycle) and unloads a plugin by disposing its tracked `Disposable`s (LIFO) and then
`unregisterAll(id)`. Both teardown paths are list-removals, so running both is idempotent. Making load order
an explicit `dependsOn` edge replaces the old reliance on hand-tuned registration sequencing (for example, the
JDT language backend must load first so it is the `backendFor` fallback; the Kotlin and XML backends and the
analysis plugins declare `dependsOn = ["jdt-language"]`).

## The IDE dogfoods it (`ide-core`)

`ide-core/BuiltInPlugins.kt` holds the IDE's own built-ins as ~16 `Plugin`s (`platform`, `jdt-language`,
`kotlin-language`, `xml-language`, `java-support`, `kotlin-support`, `android-support`, `samples`,
`completion-builtins`, `indexing`, `jdt-analysis`, `kotlin-analysis`, `xml-analysis`, `android-xml`,
`ide-core-services`, `ide-core-actions`), each mapping to the `PluginId` it contributed under before.
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

### File-to-language routing

A file's `LanguageId` is resolved through `FILE_TYPE_EP` (`FileTypeMapping`, in `language-api`) rather than a
hardcoded `when` in the host, so associating a language with a file suffix is a registration. A mapping may
target a language with no registered backend (ProGuard, Markdown); such a file is edited as plain text and,
because the analysis pipeline dispatches by language, is never analysed as Java.

### Build-system and run-task selection

The build service picks a module's build system by `BuildSystem.supports(moduleType)` — its own built-in
Java/Android systems first, then `BUILD_SYSTEM_EP`, so a plugin can add support for a new module type without a
host edit. The built-ins stay concrete engine fields rather than extensions on purpose: they are per-project
and context-heavy (the Android one defers SDK detection), so the point is the seam for *additions*, not the
registry the built-ins themselves flow through. Extra Run-picker options come from `RUN_TASK_PROVIDER_EP`
(`RunTaskProvider` → `RunTaskSpec`), merged after the built-in enumeration; id dispatch stays host-side, so a
provider reuses a built-in id prefix (`build:` / `run:` / `assemble:`) to execute through the existing pipeline.

## Platform ports as host services

Desktop-versus-Android host capabilities (the dex runner, APK installer, custom-view runtime, Kotlin
compiler-plugin loader, Android device tools, real-view runtime) are modelled as `APPLICATION`-scoped services
(`ide-core/PlatformPorts.kt`) rather than constructor-threaded through the engine. `ProjectManager` registers
whichever ports the launcher supplied on the application container, and `IdeServices` resolves each with
`getServiceOrNull`. Absent (desktop, or a standalone test with no host) resolves null, so the consumer keeps
its in-process default; `getServiceOrNull` is the single resolution path. The `desktop`/`onDevice` launcher
factories are unchanged.

## Future work

- **Externally-packaged plugins:** a discovered on-disk manifest + a classloader-isolating loader
  (`URLClassLoader` on desktop, a D8-dexed `DexClassLoader` on ART, reusing the `KotlinPluginLoader`
  machinery), plus versioning, inter-plugin dependencies, enable/disable/uninstall, and the capability /
  permission (trust) model. See `docs/ui-extensibility-and-plugin-api.md`.
- **UI contribution unification:** an `ide-ui-api` module homing the Compose-bearing registries (tool windows,
  screens, editor view modes, UI actions, tree icons) so UI contributions flow through the same plugin model.
- **Remaining host capabilities:** the `IdeServicesBackend`-layer ports (analytics, the build-runner factory,
  the notifications gate) and the build-system / run-task selectors are not yet EP- or service-modelled.
