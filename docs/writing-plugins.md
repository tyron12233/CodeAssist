# Writing CodeAssist plugins

CodeAssist is built as a plugin platform, and the IDE's own features are the first consumers of that
platform. Java support, Kotlin support, the block editor, the AI agent and Git are not privileged host code:
each is a plugin that registers through the same SPI available to you. This guide walks through that SPI end
to end and builds up to two complete, shipping examples you can read in the repository.

Everything described here is the **internal (one-classpath) tier**: a plugin is a Gradle module compiled into
the app, declared in code. The same `Plugin` can instead ship as a separate app the user installs, which is
packaging rather than a different SPI; see [Ship your plugin as its own app](#15-ship-your-plugin-as-its-own-app).

**Contents**

1. [Before you begin](#1-before-you-begin)
2. [How the plugin model works](#2-how-the-plugin-model-works)
3. [The SPI, type by type](#3-the-spi-type-by-type)
4. [Build your first plugin](#4-build-your-first-plugin)
5. [Contribute to extension points](#5-contribute-to-extension-points)
6. [Contribute scoped services](#6-contribute-scoped-services)
7. [Listen to IDE events and log](#7-listen-to-ide-events-and-log)
8. [Add a settings page](#8-add-a-settings-page)
9. [Add actions](#9-add-actions)
10. [Contribute UI](#10-contribute-ui)
11. [Case study: the Git plugin](#11-case-study-the-git-plugin)
12. [Case study: the AI Agent plugin](#12-case-study-the-ai-agent-plugin)
13. [Enable, disable, and dependencies](#13-enable-disable-and-dependencies)
14. [Test your plugin](#14-test-your-plugin)
15. [Ship your plugin as its own app](#15-ship-your-plugin-as-its-own-app)
16. [Appendix A: extension point index](#appendix-a-extension-point-index)
17. [Appendix B: class index](#appendix-b-class-index)
18. [Appendix C: service index](#appendix-c-service-index)

---

## 1. Before you begin

### What you will build

By the end of this guide you will be able to write a plugin that:

- contributes behaviour to the engine (a language backend, an analyzer, an index, a build task, a project
  template, a version-control provider, …) through **extension points**;
- owns lazily built, scope-bound objects through **scoped services**;
- reacts to IDE lifecycle events and writes attributable logs through the **message bus** and **logger**;
- adds a **settings page** with no UI code at all;
- adds **actions** that appear in the toolbar, context menus, and command palette;
- adds **Compose UI**: dockable tool windows, full screens, app-wide overlays, editor view modes, and file
  tree icons;
- can be **turned off by the user**, taking its whole surface with it.

### Prerequisites

| Requirement | Why |
| --- | --- |
| The CodeAssist repository, buildable locally | Plugins are Gradle modules in the same build |
| Kotlin, and Compose Multiplatform if you contribute UI | The SPI is Kotlin; UI contributions carry `@Composable` bodies |
| An Android SDK on the machine | The Compose shells (`:ide-ui`, `:ide-core`, launchers) apply AGP and need it even to configure. CI sets `CI_CORE_ONLY=true` to build only the framework; leave it unset locally. See [settings.gradle.kts](../settings.gradle.kts) |

Read [architecture.md](architecture.md) first if you have not: it explains the project model, the two-level
graph, and the concurrency rules your plugin code runs under.

### Key terms

| Term | Meaning |
| --- | --- |
| **Extension point (EP)** | A typed, string-id-keyed slot that many implementations can be contributed to. `dev.ide.platform.ExtensionPoint<T>` |
| **Extension** | One implementation contributed to an EP |
| **Extension registry** | The store of contributions, hierarchical (project registry parents the application registry). `dev.ide.platform.ExtensionRegistry` |
| **Scoped service** | A lazily built, cached object bound to an APPLICATION / WORKSPACE / MODULE scope |
| **Engine plugin** | A `dev.ide.plugin.Plugin`: data-driven contributions, no Compose |
| **UI plugin** | A `dev.ide.ui.ext.UiPlugin`: Compose-bearing contributions |
| **Plugin id** | The stable string that attributes every contribution a plugin makes, e.g. `vcs` |
| **Manifest** | A plugin's identity and load-order metadata: `dev.ide.plugin.PluginManifest` |
| **Essential plugin** | One the IDE cannot run without; the user cannot disable it |

---

## 2. How the plugin model works

### 2.1 One model, no privileged host path

Before this model existed, the IDE wired its own features in a ~270-line imperative block, and third-party
extensibility was a separate, weaker mechanism alongside it. That arrangement decays: the host's path is the
one that gets maintained, and the plugin path falls behind.

CodeAssist inverts it. [`ApplicationEnvironment`](../ide-core/src/main/kotlin/dev/ide/core/ApplicationEnvironment.kt)
builds a [`PluginManager`](../plugin-impl/src/main/kotlin/dev/ide/plugin/impl/PluginManager.kt) over the
application registry and loads a list of plugins, and that list is the IDE. If a capability cannot be expressed
as a plugin contribution, that is treated as a gap in the SPI, not as a reason to add host wiring.

The practical consequence for you: every built-in plugin in
[`BuiltInPlugins.kt`](../ide-core/src/main/kotlin/dev/ide/core/BuiltInPlugins.kt) is a worked example of the
API you are about to use.

### 2.2 The two facets

A feature can have up to two facets:

```
                      ┌──────────────────────────────────────┐
   BuiltInPlugin  ───▶│ engine: dev.ide.plugin.Plugin        │──▶ ExtensionRegistry (data-driven)
   (one feature)      │   manifest / register(reg)           │    services, EPs, settings, actions
                      ├──────────────────────────────────────┤
                      │ ui: dev.ide.ui.ext.UiPlugin?         │──▶ UiPluginHost (Compose-bearing)
                      │   contributeUi(scope)                │    tool windows, screens, overlays
                      └──────────────────────────────────────┘
```

They are two objects rather than one because a `@Composable` body cannot live in the engine module (which
knows nothing of Compose) and cannot cross the neutral `IdeBackend` boundary as data. They are **declared
together** in one [`BuiltInPlugin`](../ide-core/src/main/kotlin/dev/ide/core/BuiltInPlugins.kt) entry, so the
user's single enable/disable decision governs both halves: the engine facet's manifest carries the identity,
and only enabled plugins' UI facets are handed to the shell.

A plugin may have only an engine facet (most do), or an engine facet plus a UI facet (Git, the AI agent). For
a **built-in**, a UI-only plugin is not a thing: the manifest, and therefore the identity, lives on the engine
facet. An **installed** plugin carries its manifest in its APK instead, so it may declare either facet or
both (see [section 15](#15-ship-your-plugin-as-its-own-app)) and, since both halves come off one APK, may
implement both with a **single class**: name it in `entryPoints` and in `uiEntryPoints` and the IDE
instantiates it once. Its UI facet implements a different, narrower interface (`dev.ide.plugin.ui.UiPlugin`),
for the reason given in [section 10.11](#1011-an-installed-plugins-ui-facet).

### 2.3 Lifetimes

| Object | Lifetime | Notes |
| --- | --- | --- |
| `ApplicationEnvironment` | One per running app | Owns the app registry, the app message bus, the plugin manager |
| `PluginManager` | One per app | Loads plugins **once** at startup, in dependency order |
| `UiPluginHost` | One per process | Loads UI facets once (idempotent `ensureLoaded()`) |
| A project's `PlatformCore` | One per opened project | Its registry **parents** the app registry |

Two rules follow from that table:

1. **`register()` runs once, before any project is open.** Do not resolve project state in it. Contributions
   that need the open project take `ApplicationEnvironment` and read `env.activeEngine` **lazily at callback
   time**. Every capturing built-in does exactly this. See `CompletionBuiltinsPlugin`, `AndroidXmlPlugin`,
   and `IdeCoreActionsPlugin` in `BuiltInPlugins.kt`.
2. **Enable/disable is applied on restart.** The manager loads once and does not hot-swap. The catalog
   reflects persisted intent, not a live toggle. The same holds for the plugin apps on the device: the IDE
   records what changed about them while it was running (installed, updated, uninstalled) and the Plugins
   screen applies the lot with one restart. See §13.1.

### 2.4 The substrate (`platform-core`)

Everything sits on two primitives from
[`platform-core`](../platform-core/src/main/kotlin/dev/ide/platform/Platform.kt). `platform-core` has no
domain knowledge: no "project", no "Android", no "Java".

**Extension points.**

```kotlin
class ExtensionPoint<T : Any>(val id: String)

interface ExtensionRegistry {
    fun <T : Any> register(ep: ExtensionPoint<T>, impl: T, plugin: PluginId): Disposable
    fun <T : Any> extensions(ep: ExtensionPoint<T>): List<T>
    fun unregisterAll(plugin: PluginId)
}
```

Typed, attributed to a `PluginId`, individually removable via the returned `Disposable`, and bulk-removable
per plugin. `extensions(ep)` returns contributions in registration order, which is why load order is declared
rather than accidental (see [PluginManager](#34-pluginmanager)).

**Scoped services.** ([`Services.kt`](../platform-core/src/main/kotlin/dev/ide/platform/Services.kt))
`SERVICE_EP` carries `ServiceDescriptor`s into a `ServiceContainer` with three levels: `APPLICATION`,
`WORKSPACE`, and `MODULE`. A service is built lazily on first resolution, cached at its scope, and disposed with
its container. So "register a service" is a specific kind of "register an extension", not a parallel
mechanism.

---

## 3. The SPI, type by type

Package: `dev.ide.plugin` in module [`:plugin-api`](../plugin-api).

### 3.1 `Plugin`

[`dev.ide.plugin.Plugin`](../plugin-api/src/main/kotlin/dev/ide/plugin/Plugin.kt)

```kotlin
interface Plugin {
    val manifest: PluginManifest
    fun register(reg: PluginRegistration)
    fun dispose() {}
}
```

| Member | Why it exists |
| --- | --- |
| `manifest` | Identity and load order. `manifest.id` is both the attribution key for every contribution and the node id in the `dependsOn` graph |
| `register(reg)` | The single contribution hook. Runs exactly once, after every plugin in `dependsOn`. Everything the plugin adds goes through `reg` so it can be attributed and tracked |
| `dispose()` | Optional. Only for resources the plugin owns beyond its registry contributions, such as a background scope or a file watcher. Registry contributions are torn down automatically, so most plugins never override this |

One thing the interface does **not** ask of a plugin shipped as its own app: a `manifest`. That property is
defaulted, because such a plugin's identity is its packaged `codeassist_plugin.toml`, which the IDE reads
before any of the plugin's code runs and uses in preference to whatever an entry point returns. Declaring one
anyway used to be the single line that broke an already-compiled plugin whenever `PluginManifest` grew a
field, since Kotlin compiles a call relying on default arguments into a synthetic constructor whose descriptor
names every parameter. A **built-in** still overrides it: it has no packaged manifest, and the loader refuses
a plugin whose id is blank.

### 3.2 `PluginManifest`

[`dev.ide.plugin.PluginManifest`](../plugin-api/src/main/kotlin/dev/ide/plugin/PluginManifest.kt)

```kotlin
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val apiVersion: Int = PLUGIN_API_VERSION,
    val dependsOn: List<String> = emptyList(),
    val description: String = "",
    val essential: Boolean = false,
    // installed plugins only (unused for built-ins, where the class is the entry point):
    val entryPoints: List<String> = emptyList(),
    val uiEntryPoints: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val minHostVersion: String? = null,
    val trusted: Boolean = true,
)
```

| Field | Why it exists | Guidance |
| --- | --- | --- |
| `id` | Attribution key, `dependsOn` node id, persisted in the user's disabled set | Lowercase, hyphenated, stable forever, because renaming it silently re-enables a plugin the user disabled |
| `name` | Shown in **Settings → Plugins** | Human title case, e.g. `Version Control` |
| `version` | Displayed on the plugin's row | Semantic version |
| `apiVersion` | Host SPI/ABI compatibility floor, bumped when this SPI changes incompatibly, **including when a field is added to this class** (Kotlin's synthetic default-argument constructor names every parameter, so an older plugin calls a method that no longer exists) | Leave at the default; an installed plugin declaring another value is rejected at load, which is the readable version of the linkage error it would otherwise hit |
| `dependsOn` | Drives the **topological load order**, and drops dependents when a dependency is disabled | Declare an edge whenever your contribution must land after another's |
| `description` | One line under the name in **Settings → Plugins** | Say what the user gets, not how it is implemented |
| `essential` | The plugin cannot be disabled; it and everything it transitively depends on stay loaded | Only for things the IDE genuinely cannot run without; ignored for an installed plugin |
| `entryPoints` | FQCNs the loader instantiates for an installed plugin | Unused for built-ins, where the class is the entry point |
| `capabilities` | Declared for the trust model | Parsed and carried; nothing reads it yet |
| `minHostVersion` | Rejects an installed plugin on an IDE older than it needs | Set it if you use a recently added SPI |
| `trusted` | Follows from the origin's signature | The host's to decide; ignored for an installed plugin |

`manifest.pluginId` derives the `dev.ide.platform.PluginId` used for attribution, so you never construct one
by hand.

The manifest is a Kotlin literal for built-ins ("manifest + entry point"). An installed plugin ships the same
shape as TOML, which is why the two tiers share one SPI. See
[Ship your plugin as its own app](#15-ship-your-plugin-as-its-own-app).

### 3.3 `PluginRegistration`

[`dev.ide.plugin.PluginRegistration`](../plugin-api/src/main/kotlin/dev/ide/plugin/PluginRegistration.kt)
is the registrar handed to `register()`. It exists so a plugin never threads a `PluginId` by hand and never
has to remember to unregister anything.

```kotlin
interface PluginRegistration {
    val pluginId: PluginId
    fun <T : Any> register(ep: ExtensionPoint<T>, impl: T): Disposable
    fun <T : Any> service(key: ServiceKey<T>, level: ServiceScopeLevel, factory: ServiceFactory<T>): Disposable
    fun contributeVia(block: (ExtensionRegistry, PluginId) -> Unit)
    fun onDispose(d: Disposable)

    val messageBus: MessageBus
    fun busConnection(): MessageBusConnection
    fun logger(tag: String): Logger
    val hostVersion: String?
}
```

| Member | Why it exists |
| --- | --- |
| `register(ep, impl)` | The common case. Attributes to this plugin and tracks the handle for unload |
| `service(key, level, factory)` | Registering a service through the raw EP would make you pass the id twice; this collapses it |
| `contributeVia { ext, pid -> … }` | A bridge for pre-existing `(ExtensionRegistry, PluginId)` facades such as `ModuleTypeRegistry` and `ProjectTemplateRegistry`. Those discard per-registration handles, so their unload relies on the bulk `unregisterAll(pluginId)` sweep, which is exact because they attribute to this same id |
| `onDispose(d)` | Ties an arbitrary `Disposable` to unload (LIFO with the rest) |
| `messageBus` | **Publish**: your own `Topic`s, or the IDE's lifecycle topics |
| `busConnection()` | **Subscribe**: returns a connection already tracked for unload. A raw `messageBus.connect()` is not tracked, and its subscriptions outlive an unload |
| `logger(tag)` | A `Logger` whose records carry your plugin id, so the in-app Logs viewer can filter by plugin. Attribution is stamped by the platform and cannot be forged |
| `hostVersion` | The running IDE's version, or null when the host supplied none. The same value the loader compares against `minHostVersion`, so a runtime branch and a declared floor agree about what they are running on. Prefer `minHostVersion` to refuse an old IDE outright; read this to adapt behaviour or report the host |

### 3.4 `PluginManager`

[`dev.ide.plugin.impl.PluginManager`](../plugin-impl/src/main/kotlin/dev/ide/plugin/impl/PluginManager.kt)

- `loadAll(plugins)` topologically sorts by `manifest.dependsOn` (dependencies first), then loads each.
  Independent plugins keep their declared relative order, so the result is deterministic.
- It **throws** on a duplicate id, a `dependsOn` naming a plugin that is not in the set, or a dependency
  cycle. These are programming errors, surfaced at startup rather than as a mysterious ordering bug.
- `unload(id)` disposes the plugin's tracked `Disposable`s LIFO, then sweeps `unregisterAll(id)`, then calls
  `dispose()`. Both teardown paths are list removals, so running both is idempotent.

Why declared order matters, concretely: the JDT language backend must be index 0 on `LANGUAGE_BACKEND_EP`
because the resolution fallback `backendFor` relies on it. That used to be implicit registration sequencing.
Now `kotlin-language`, `xml-language`, and the analysis plugins carry `dependsOn = listOf("jdt-language")`,
and the manager enforces it.

### 3.5 `PluginCatalog`

[`dev.ide.plugin.impl.PluginCatalog`](../plugin-impl/src/main/kotlin/dev/ide/plugin/impl/PluginCatalog.kt) is
pure and host-agnostic: it takes all manifests plus the user's persisted disabled ids and computes the set
that loads this session.

- An `essential` plugin, and everything it transitively depends on, is force-enabled, so a disabled id among
  them is ignored.
- Any other plugin is enabled unless the user disabled it, **or** it transitively depends on a disabled plugin.
  Dropping dependents matters: a dangling `dependsOn` edge is exactly what `PluginManager.loadAll` rejects.

---

## 4. Build your first plugin

This section builds a minimal engine-only plugin, `hello`, that contributes a settings page. Later sections
add actions, services, events, and UI on top of it.

### Step 1: Decide where the code lives

You have two choices, and both are used in-tree:

| Choice | When to use it | Example |
| --- | --- | --- |
| A class in [`BuiltInPlugins.kt`](../ide-core/src/main/kotlin/dev/ide/core/BuiltInPlugins.kt) or a sibling file in `:ide-core` | The plugin only wires up types that already exist in modules `:ide-core` depends on | `BlocksPlugin`, `VcsPlugin`, `AgentPlugin` |
| A new Gradle module | The plugin brings its own engine code, its own dependencies, or its own Compose UI | `:vcs-impl` + `:vcs-ui`, `:agent-impl` + `:agent-ui` |

Start with a class in `:ide-core`. Move to a module as soon as the plugin needs its own dependencies: a
plugin that drags a third-party library into `:ide-core` puts that library on everyone's classpath.

### Step 2: Register the module (new module only)

Add it to [`settings.gradle.kts`](../settings.gradle.kts). Pure engine modules go in the first `include(...)`
block; anything applying Compose or AGP goes in the `CI_CORE_ONLY` guarded block, because those plugins need
the Android SDK even to configure.

```kotlin
include(
    // …
    ":hello-impl", // the Hello plugin engine: <one line on what it does>
)
```

Then a `build.gradle.kts`. Depend on `:plugin-api` (which re-exposes `:platform-core` via `api`) plus whatever
API modules hold the EPs you contribute to:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    api(project(":plugin-api"))          // Plugin / PluginManifest / PluginRegistration + platform-core
    implementation(project(":language-api")) // only if you contribute a language EP
}
```

Finally, add `implementation(project(":hello-impl"))` to
[`ide-core/build.gradle.kts`](../ide-core/build.gradle.kts) so `BuiltInPlugins` can reference your entry point.

### Step 3: Write the `Plugin`

```kotlin
package dev.ide.hello

import dev.ide.platform.settings.SETTINGS_PAGE_EP
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration

/**
 * Hello, contributed as a built-in plugin. Non-essential, so the whole feature can be turned off from
 * Settings > Plugins.
 */
class HelloPlugin : Plugin {
    override val manifest = PluginManifest(
        id = ID,
        name = "Hello",
        description = "A worked example: one settings page and one palette command.",
    )

    override fun register(reg: PluginRegistration) {
        reg.register(SETTINGS_PAGE_EP, HelloSettingsPage)
    }

    companion object {
        const val ID = "hello"
    }
}
```

Three things to notice, because they are the conventions every built-in follows:

- **The id is a `const val` on a companion.** Anything that gates on the plugin being enabled (see
  [Enable, disable, and dependencies](#13-enable-disable-and-dependencies)) refers to `HelloPlugin.ID`
  rather than repeating a string literal.
- **`register` does nothing but register.** No I/O, no project lookups, no thread starting. It runs during app
  construction, before any project exists.
- **The KDoc says what the user gets.** The description string is user-facing; it renders in Settings.

### Step 4: Declare it in `BuiltInPlugins`

Open [`BuiltInPlugins.kt`](../ide-core/src/main/kotlin/dev/ide/core/BuiltInPlugins.kt) and add an entry to
`assemble`:

```kotlin
object BuiltInPlugins {
    fun assemble(env: ApplicationEnvironment, codecs: FacetCodecRegistry): List<BuiltInPlugin> = listOf(
        BuiltInPlugin(PlatformPlugin()),
        // …
        BuiltInPlugin(HelloPlugin()),
    )
}
```

`BuiltInPlugin(engine, ui = null)` is the unified declaration described in [The two facets](#22-the-two-facets).
Pass `ui = …` once the plugin has a Compose facet (see [Contribute UI](#10-contribute-ui)).

Order in this list is only a tie-break: `PluginManager` topologically sorts by `dependsOn` first. Declare a
`dependsOn` edge if you actually need one; do not rely on list position.

### Step 5: Run and verify

Launch the desktop shell (`:ide-desktop`) or install the Android app (`:ide-android`), then:

1. Open **Settings → Plugins**. `Hello` should be listed with your description and an enabled toggle.
2. Open the settings page you registered and check the controls render.
3. Toggle the plugin off, restart, and confirm the surface is gone. That round trip is the real test that the
   feature is a plugin rather than host code.

### Step 6: Write a test

You do not need the app to test a plugin. Build a registry, load the plugin, assert the contributions:

```kotlin
@Test
fun `contributes a settings page`() {
    val reg = ExtensionRegistryImpl()
    PluginManager(reg).loadAll(listOf(HelloPlugin()))
    assertTrue(reg.extensions(SETTINGS_PAGE_EP).any { it.id == "hello" })
}
```

See [`PluginManagerTest`](../plugin-impl/src/test/kotlin/dev/ide/plugin/impl/PluginManagerTest.kt) for the
full set of behaviours the manager guarantees, and [Test your plugin](#14-test-your-plugin) for more recipes.

---

## 5. Contribute to extension points

An extension point is a typed slot. Contributing to one is a single call:

```kotlin
override fun register(reg: PluginRegistration) {
    reg.register(LANGUAGE_BACKEND_EP, MyLanguageBackend())
    reg.register(FILE_TYPE_EP, FileTypeMapping(listOf(".mylang"), MyLanguageBackend.LANGUAGE_ID))
}
```

The full inventory is in [Appendix A](#appendix-a-extension-point-index). A representative slice:

| Extension point | Contribute to add |
| --- | --- |
| `dev.ide.lang.LANGUAGE_BACKEND_EP` | A new editor language (parse, complete, navigate) |
| `dev.ide.lang.COMPILATION_CONTEXT_PROVIDER_EP` | `platform.compilationContext` | `CompilationContextProvider` | The analysis inputs for your language |
| `dev.ide.lang.FILE_TYPE_EP` | A file suffix → language mapping |
| `dev.ide.analysis.ANALYZER_EP` | An inspection producing diagnostics |
| `dev.ide.analysis.QUICK_FIX_PROVIDER_EP` | A fix for a diagnostic |
| `dev.ide.analysis.ACTION_PROVIDER_EP` | A caret intention (no diagnostic needed) |
| `dev.ide.index.INDEX_EP` | A persisted symbol index |
| `dev.ide.build.BUILD_SYSTEM_EP` | Support for a new build system |
| `dev.ide.build.BUILD_PLUGIN_EP` | Extra tasks wired into every build graph |
| `dev.ide.build.SOURCE_GENERATOR_EP` | A code generator that runs before compilation |
| `dev.ide.vcs.VCS_PROVIDER_EP` | Another version-control system |
| `dev.ide.platform.settings.SETTINGS_PAGE_EP` | A settings category |
| `dev.ide.plugin.action.UI_ACTION_EP` | A toolbar / menu / palette command |

### Two useful details

**File-to-language routing is a registration, not a `when`.** A file's `LanguageId` resolves through
`FILE_TYPE_EP` ([`FileType.kt`](../language-api/src/main/kotlin/dev/ide/lang/FileType.kt)). A mapping may point
at a language with no registered backend. That is how `.pro` and `.md` are edited as plain text and, because
the analysis pipeline dispatches by language, are never analysed as Java.

**Contributions are queried live.** Consumers such as
[`ActionManager`](../plugin-impl/src/main/kotlin/dev/ide/plugin/impl/ActionManager.kt) call `extensions(EP)` on
every resolution rather than caching a snapshot at construction, so a plugin loaded or unloaded later is
reflected without rebuilding anything.

### Declaring your own extension point

If your plugin is itself extensible, publish an EP from your API module:

```kotlin
package dev.ide.hello

import dev.ide.platform.ExtensionPoint

/** Greeters the Hello plugin consults, in registration order. */
val GREETER_EP = ExtensionPoint<Greeter>("hello.greeter")
```

Rules that matter:

- **The id string is the identity.** A producer and a consumer that each construct `ExtensionPoint("hello.greeter")`
  see the same contributions. Two EPs sharing an id with different types fail at runtime, not at compile time.
- **Namespace it.** Platform EPs use `platform.*`; use your plugin id as the prefix.
- **Consume it defensively.** `extensions(EP).ifEmpty { builtinDefaults }` is the pattern used by the Kotlin
  compiler-plugin EP, so a standalone test with an empty registry still behaves.

### Support a language the IDE has never heard of

A plugin for Python, C++, Go or anything else laid out unlike a JVM module contributes on five extension
points in `project-model-api`, all of them published. Nothing about this path is host-only: the built-in
Android support registers exactly these.

```kotlin
class PythonPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "python-support", name = "Python",
        capabilities = listOf(
            PluginCapabilities.MODEL_MODULE_TYPE,   // it contributes a kind of module
            PluginCapabilities.MODEL_FACET,         // ... with configuration of its own
            PluginCapabilities.LANG_BACKEND,        // ... and teaches the editor the language
        ),
    )

    override fun register(reg: PluginRegistration) {
        reg.register(ModuleTypeExtensionPoint, PythonModuleType)         // a kind of module
        reg.register(FACET_CODEC_EP, PythonFacetCodec)                   // its module.toml table
        reg.register(ProjectTemplateExtensionPoint, PythonAppTemplate)   // a Create-Project entry
        reg.register(PROJECT_IMPORTER_EP, PyProjectImporter)             // adopt an existing pyproject.toml
        reg.register(LANGUAGE_BACKEND_EP, PythonBackend)                 // parse / resolve / complete
        reg.register(FILE_TYPE_EP, FileTypeMapping(listOf(".py"), LanguageId("python")))
    }
}
```

**A facet is a type plus its codec, always both.** The core cannot serialize a `Facet` generically, so
`ModifiableModule.putFacet` refuses a facet whose key has no registered `FacetCodec` and `FacetContainer.get`
answers null for one. Two rules follow from how they are matched:

- `FacetKey` has **reference identity**. Declare it once as a `val` and have the facet and the codec name that
  same instance; two keys sharing an id are two different keys.
- The **TOML table name is the on-disk identity**, in a namespace flat across every plugin. `decode` is
  resolved by table, so pick one that reads as your domain and expect the last registration to win.

A table nobody claims is *not* lost: it is carried through a load and a save untouched, so a project edited
with your plugin disabled keeps its configuration.

```kotlin
val PYTHON_FACET = FacetKey<PythonFacet>("python")

data class PythonFacet(val interpreter: String, val venv: String?) : Facet {
    override val key get() = PYTHON_FACET
}

object PythonFacetCodec : FacetCodec<PythonFacet> {
    override val key = PYTHON_FACET
    override val tomlTable = "python"          // the [python] table in module.toml
    override fun encode(f: PythonFacet) = mapOf("interpreter" to f.interpreter, "venv" to f.venv)
    override fun decode(v: Map<String, Any?>) =
        PythonFacet(v["interpreter"] as? String ?: "python3", v["venv"] as? String)
}
```

Codec values must be TOML-representable (`String`, `Long`, `Boolean`, and lists of those), so that an
`encode` and a load-from-disk produce structurally equal values.

**The model's vocabularies are open, so your layout does not have to lie.** `ContentRole`, `PlatformKind`,
`LibraryKind`, `LanguageLevel` and `DependencyScope` were enums until SPI `2.0.0`. They are now value types
whose built-in constants live on the companion, and a plugin declares its own:

```kotlin
object Py {
    val PACKAGE_ROOT = ContentRole("python-package")   // not "a Java source root"
    val STUBS = ContentRole("python-stubs")
    val PLATFORM = PlatformKind("PYTHON")              // resolves a Python SDK, never android.jar
    val WHEEL = LibraryKind("WHEEL")                   // not "a jar"
    val LEVEL = LanguageLevel("PYTHON_3_12")           // not JAVA_17

    /** On the runtime path, never the compile one. */
    val RUNTIME_REQUIRES = DependencyScope.register(
        DependencyScope("RUNTIME_REQUIRES", "runtimeRequires",
            onCompile = false, onRuntime = true, onTest = true),
    )
}

object PythonModuleType : ModuleType {
    override val id = "python-app"
    override val displayName = "Python Application"
    override val platform get() = Py.PLATFORM
    override fun defaultSourceSets() = listOf(
        SourceSetTemplate("main", DependencyScope.IMPLEMENTATION,
            mapOf("src" to setOf(Py.PACKAGE_ROOT), "stubs" to setOf(Py.STUBS))),
    )
    override fun defaultFacets() = emptyList<FacetTemplate>()
    override fun supportedBuildSystems() = setOf(BuildSystemId.NATIVE)
}
```

Three things to know about the open vocabularies:

- **`DependencyScope` is the one that needs registering.** The others round-trip on their name alone, but a
  scope also carries classpath semantics (`onCompile`/`onRuntime`/`onTest`) that a name cannot recover.
  `DependencyScope.register(...)` from your `register` makes a project that persisted it load with the real
  semantics; without it the scope still round-trips, but is re-derived permissively (on every classpath) and a
  `[dependencies]` table keyed by its `id` is skipped.
- **An exhaustive `when` over one of them now needs an `else`.** That is the one way this change can stop
  existing plugin code from compiling, and why `PLUGIN_SPI_VERSION` went to `2.0.0`.
- **The built-in on-disk spellings did not change.** `ContentRole.SOURCE` is still written as `java`,
  a source set's scope still as `IMPLEMENTATION`, a level still as `JAVA_17`. Your own values persist under
  their `id`/`name`, so pick ones unlikely to collide with those.

**Adopting an existing project** is `PROJECT_IMPORTER_EP`: `detect(root)` claims a folder (highest
`confidence` wins), `resolve` reads its build files into an `ExternalProjectModel`, and the host applies that
snapshot in one transaction. The snapshot names your module type by id and your facets by table name, so an
importer needs no reference to the classes that provide them. Pair it with `BUILD_FILE_WRITER_EP` if edits the
user makes in the IDE should survive the next sync.

`ModuleTypeRegistry`, `FacetCodecRegistry`, `ProjectTemplateRegistry` and `FileIconRegistry` (all in
`dev.ide.model`) are the read side of these four EPs, if you need to resolve rather than contribute. They
read through to the extension registry on every lookup, so a plugin that loads later is still seen.

**Your language's analysis inputs are yours to supply.** The host builds a `CompilationContext` by walking the
project model, which produces the JVM reading of a module: a classpath, a platform SDK boot classpath, a Java
language level. A virtualenv, an include path or a sysroot is none of those, so contribute a
`CompilationContextProvider` and the host asks you first for the languages you claim:

```kotlin
val PY_INTERPRETER = ContextKey<String>("python.interpreter")

object PythonContexts : CompilationContextProvider {
    override val languages = setOf(LanguageId("python"))

    override fun contextFor(
        workspace: Workspace, module: Module, language: LanguageId, variant: Set<String>?,
    ): CompilationContext? {
        val facet = module.facets.get(PYTHON_FACET) ?: return null   // not my module after all
        return object : CompilationContext {
            override val sourceRoots = module.sourceSets.flatMap { it.contentRoots }
                .filter { Py.PACKAGE_ROOT in it.roles }.map { it.dir }

            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> attribute(key: ContextKey<T>): T? =
                if (key === PY_INTERPRETER) facet.interpreter as T else null
        }
    }
}

reg.register(COMPILATION_CONTEXT_PROVIDER_EP, PythonContexts)
```

Only `sourceRoots` is required: `classpath` and `bootClasspath` default to `ClasspathSnapshot.EMPTY`,
`languageLevel` to `LanguageLevel.DEFAULT`, `outputDir` to null, `processors` to empty. `ContextKey` has
reference identity like `FacetKey`, so the provider that writes an attribute and the backend that reads it are
the same plugin naming the same `val`, and the core never has to know the key exists. `ClasspathEntryKind` is
open too, so a language that does have a dependency path can name entries the core has no word for
(`ClasspathEntryKind("INCLUDE_DIR")`).

The host asks providers claiming the language in registration order and takes the first non-null answer;
returning null falls back to the model-derived context, and a provider that throws is logged and skipped so
one broken plugin cannot stop analysis of everything else.

If your language does have a classpath but needs something extra alongside it, start from
`ModuleCompilationContext.create(workspace, module, variant)`, which is the model-derived context the host
would otherwise hand you, and add to what it returns.

What is still JVM-shaped underneath: `Module` carries `classpath()` and `outputDir` on the interface, which is
why a non-JVM `Module` still answers a classpath question. It is not in your way once you supply your own
context.

---

## 6. Contribute scoped services

Use a service when your plugin owns an object that is expensive to build, must be shared, and must be torn
down with something. Use a plain extension when the plugin contributes behaviour to a host-owned engine.

```kotlin
val HELLO_SERVICE = ServiceKey<HelloService>("hello.service")

override fun register(reg: PluginRegistration) {
    reg.service(HELLO_SERVICE, ServiceScopeLevel.WORKSPACE) {
        HelloService(getService(ENGINE_CONTEXT))
    }
}
```

`ENGINE_CONTEXT` is `internal` to `:ide-core`, so that exact line compiles in a built-in plugin only. An
installed plugin can name a narrower set of keys; see [What you can resolve](#what-you-can-resolve).

The factory's receiver is [`ServiceScope`](../platform-core/src/main/kotlin/dev/ide/platform/Services.kt),
which gives you:

| Member | Purpose |
| --- | --- |
| `getService(key)` | Pull a dependency, including from a parent scope |
| `scopeObject` | The domain object this scope is bound to: the `Module` at MODULE, the `Workspace` at WORKSPACE, `null` at APPLICATION |
| `container` / `parent` | The containers themselves, when you need `getServiceOrNull` |
| `onDispose(d)` | Extra teardown that runs when the scope's container disposes |

Choosing a level:

| Level | Built once per | Use for |
| --- | --- | --- |
| `APPLICATION` | Running app | Caches shared across projects, host capability ports, warm compilers |
| `WORKSPACE` | Opened project | Project-wide engines: search, dependencies, build, signing |
| `MODULE` | Module | Per-module analyzers |

Two properties follow from the container design:

- **Lazy.** A service is not built until something asks for it. A plugin whose service is never resolved costs
  nothing at startup.
- **Cascading.** An unresolved key falls back to the parent scope, so a MODULE-scoped factory can depend on a
  WORKSPACE-scoped service without any wiring.

`getServiceOrNull` is the resolution path for optional host capabilities. The platform ports (the program
interpreter, the APK installer, the custom-view and real-view runtimes, the Kotlin plugin loader and compiler
backend, the Android device tools) are APPLICATION services registered by whichever launcher is running. The
engine resolves each with `getServiceOrNull` and falls back to an in-process default when the port is absent,
which is the case on desktop and in a standalone test with no host. Use the same shape for anything only some
hosts provide. The keys are declared in
[`PlatformPorts.kt`](../ide-core/src/main/kotlin/dev/ide/core/PlatformPorts.kt) (most are `internal` today;
`ANALYTICS_SERVICE` is public).

### Getting an instance of a service

`register()` runs inside [`ApplicationEnvironment`](../ide-core/src/main/kotlin/dev/ide/core/ApplicationEnvironment.kt)'s
constructor, once at startup, before any project is open. The application container exists by then, so an
APPLICATION-scoped service is resolvable there; a WORKSPACE- or MODULE-scoped one is not, because there is no
open project to scope it to. Prefer registering something that is handed a scope later and resolving at that
point. Four shapes cover it.

**From your own service factory.** The factory is lazy: it runs on first resolution, when the scope it is
registered at exists. This is where a service's own dependencies belong.

```kotlin
reg.service(MY_SERVICE, ServiceScopeLevel.WORKSPACE) {
    MyService(workspace(), getService(SOME_OTHER_KEY))
}
```

**From a `Module` or `Workspace`.** Both carry a public `service(key)` lookup, and `Module.service` walks
MODULE, then WORKSPACE, then APPLICATION, so one call reaches any scope. `Workspace.serviceOrNull(key)` is
the same lookup for a capability whose absence is normal, so a consumer can fall back instead of throwing:

```kotlin
reg.register(ANALYZER_EP, object : FileAnalyzer {
    override suspend fun analyze(target: AnalysisTarget): List<Diagnostic> {
        val mine = target.module.service(MY_SERVICE)
        val index = target.index            // IndexService, handed to you directly
        ...
    }
})
```

What the callbacks hand you to resolve through:

| From | You get |
| --- | --- |
| `AnalysisTarget` (analyzers, `QuickFixProvider`, `ActionProvider`) | `.module`, `.index` (`IndexService`), `.resolver` (`SourceAnalyzer`), `.parsed`, `.file` |
| `ProjectAnalysisScope` (project analyzers, batch lint) | `.modules`, `.index` |
| `SyntheticClassContext` | `.module`, `.workspace` |
| A `ServiceFactory` | `module()`, `workspace()`, `getService(key)` |

`ActionContext` is the exception: a `UI_ACTION_EP` command is given `projectRoot`, `activeFilePath` and the
selection as plain strings and offsets, with no model object, so it can resolve nothing. An action that needs
services fits better on `ACTION_PROVIDER_EP`, which is given an `AnalysisTarget`.

**From the registrar**, for an APPLICATION-scoped service with no scope object in sight.
`reg.appServices` is a read-only
[`ServiceLookup`](../platform-core/src/main/kotlin/dev/ide/platform/Services.kt) over the application
container: it resolves keys, and cannot define a service, evict an instance, or dispose a scope.

```kotlin
private lateinit var services: ServiceLookup

override fun register(reg: PluginRegistration) {
    services = reg.appServices          // keep the lookup; resolve at callback time
}

private fun sdk() = services.getServiceOrNull(APP_SDK_MANAGER)
```

Hold it rather than resolving in `register`: resolving during load forces the service to be built then, and
a service another plugin registers is only there once that plugin has run, so name that plugin in
`manifest.dependsOn`. The edge also means the user disabling it disables yours, since the catalog drops a
disabled plugin's dependents.

**From the message bus**, when what you need is the transition rather than the object:
`reg.busConnection().subscribe(topic, listener)`, auto-unsubscribed on unload. See
[section 7](#7-listen-to-ide-events-and-log).

### What you can resolve

A key is usable only if you can name its type, and that differs by tier:

| Tier | Can name |
| --- | --- |
| Built-in (a module in this build) | Every key in [Appendix C](#appendix-c-service-index), `internal` ones included |
| Installed (its own APK) | Keys from the published SPI artifacts, plus keys it or another plugin declares |

Resolution itself is available to both tiers: `reg.appServices` for APPLICATION scope, and
`Module.service` / `Workspace.service` from the callbacks above. What differs is what you can *name*.

Five engine capabilities are nameable from the published SPI. `WORKSPACE_SERVICE` is the bound `Workspace`.
The other four are the promoted services (`BUILD_CONTROL`, `SYMBOL_SEARCH`, `MODULE_SOURCES`,
`MODULE_ANALYSIS`), each declared in the api module whose types it already speaks, and each typed to a
*narrowed* interface rather than to the engine class behind it. `:ide-core` registers the narrow key
alongside its own and resolves both to one instance, so a plugin and the IDE act on the same build, the same
index, and the same module model.

The narrowing is the point. The engine's build service is a 1500-line class whose width is phrased in the
console, run-picker and permission types the IDE's UI port owns; `BuildControl` is three members. What is
promoted is frozen as plugin API, and what is not stays `internal` and free to change. Everything else in
Appendix C is built-in-only, and a plugin's route to it is extension points and the contexts they pass.

Plugin-to-plugin sharing works fully in both tiers: declare a `ServiceKey` in an artifact both sides compile
against, register the service, and have the consumer list you in `dependsOn` so your `register()` runs
first. That is the case `appServices` closes for the installed tier, which had no way to resolve an
APPLICATION-scoped service at all.

Container lookup is by the key's string id, so a forged `ServiceKey<Any>("ide.service.build")` does return
the real instance. Do not do this. The type is `internal`, so what you get back is `Any` plus reflection
against a shape that carries no compatibility promise.

---

## 7. Listen to IDE events and log

### 7.1 Subscribing

The registrar exposes the application message bus. Always subscribe through `busConnection()`: it returns a
`MessageBusConnection` already tracked for unload, so the subscriptions disappear when the plugin unloads. A
raw `messageBus.connect()` leaks past unload.

```kotlin
override fun register(reg: PluginRegistration) {
    val log = reg.logger("hello")
    reg.busConnection().subscribe(
        IdeEventTopics.BUILD,
        BuildEventListener { event ->            // explicit listener constructor; see the note below
            if (event is BuildEvent.Finished && !event.succeeded) {
                log.warn("build of ${event.module} failed: ${event.failureKind}")
            }
        },
    )
}
```

> **Note:** `MessageBusConnection.subscribe(topic, listener)` is generic, and Kotlin does not SAM-convert a
> type-variable parameter. Pass the explicit listener constructor (`BuildEventListener { … }`), never a bare
> lambda. A bare lambda does not compile, and the resulting error is not obvious.

### 7.2 The lifecycle topics

[`dev.ide.core.event.IdeEventTopics`](../ide-core/src/main/kotlin/dev/ide/core/event/IdeEventTopics.kt)

| Topic | Payload | Fires when |
| --- | --- | --- |
| `EDITOR` | `EditorEvent.FileOpened / FileClosed / ActiveEditorChanged / SelectionChanged` | An editor session transitions. Selection events are debounced to settle, not per keystroke |
| `BUILD` | `BuildEvent.Started / Finished` | A compile or assemble, including the compile half of a run |
| `RUN` | `RunEvent.Started / Finished` | A program run or Android app launch |
| `ANALYSIS` | `AnalysisEvent(path, diagnostics)` | A file's merged diagnostics were published |
| `PROJECT` | `ProjectEvent.Opened / Closed` | A project became, or stopped being, the active engine |
| `INDEXING` | `IndexEvent.Started / Finished(status)` | Index build progress |

Lower-level spines are also on the same bus and available to you: `dev.ide.vfs.VfsTopics` (raw file changes),
`dev.ide.model.event.ProjectModelTopics` (model commits), and `dev.ide.core.settings.SettingsTopics`.

**Delivery contract.** Synchronous, in subscription order, on whatever thread performed the transition. A
build or analysis pass runs on a background dispatcher, not the UI thread. Therefore:

- a listener that touches UI state must marshal to the UI thread itself;
- a slow listener slows the transition it is observing; do real work off the callback;
- publish sites guard subscribers with `runCatching`, so a throwing listener cannot break the engine, but that
  is a safety net rather than error handling.

### 7.3 Publishing your own topics

For plugin-to-plugin messaging, define a `Topic` and publish through the same bus:

```kotlin
val GREETED: Topic<GreetedListener> = Topic("hello.greeted", GreetedListener::class.java)

reg.messageBus.syncPublisher(GREETED).onGreeted(name)
```

### 7.4 Logging

`reg.logger(tag)` returns a `Logger` whose `LogRecord.source` is your plugin id. That flows into the shared
`Log` facade and surfaces in the in-app **Logs** viewer, which offers a per-plugin filter chip and a source
badge, so a plugin's output is separable from the IDE's. The attribution is stamped by the platform, so one
plugin cannot log as another.

---

## 8. Add a settings page

A settings page is the highest-leverage contribution in the platform: you declare typed controls and the
generic settings UI renders, persists, and namespaces them. **No UI code, no host edit.**

[`dev.ide.platform.settings.SettingsPage`](../platform-core/src/main/kotlin/dev/ide/platform/settings/Settings.kt)

```kotlin
internal object HelloSettingsPage : SettingsPage {
    override val id = "hello"                       // also the preference namespace: settings.hello.*
    override val title = "Hello"
    override val iconId = "sparkle"                 // resolved by the UI icon registry
    override val scope = SettingsScope.APPLICATION  // or PROJECT
    override val order = 120                        // built-ins occupy 0..99

    override fun controls(): List<SettingControl> = listOf(
        SettingControl.Text(
            key = "greeting",
            title = "Greeting",
            description = "What the Hello command says.",
            placeholder = "Hello, world",
        ),
        SettingControl.Toggle(
            key = "shout",
            title = "Shout",
            description = "Upper-case the greeting.",
            default = false,
        ),
        SettingControl.Action(
            key = "reset",
            title = "Reset greeting",
            buttonLabel = "Reset",
            destructive = true,
            advanced = true,
        ),
    )

    override fun onChanged(key: String, values: PreferenceReader) { /* re-apply an effect */ }
    override fun onAction(key: String, values: PreferenceReader): String? = "Greeting reset"
}
```

| Control | Renders as |
| --- | --- |
| `SettingControl.Toggle` | On/off switch |
| `SettingControl.IntSlider` | Slider over `[min, max]` stepped by `step`, with an optional `unit` label |
| `SettingControl.Choice` | Segmented control / chips over `options` |
| `SettingControl.Text` | Free-text field |
| `SettingControl.Action` | A button; the press routes `key` to `onAction` |

Design notes:

- **Keys are page-local.** The host namespaces stored keys by page id, so `greeting` cannot collide with
  another plugin's `greeting`. Values persist as strings under `settings.<pageId>.<key>`.
- **`scope` decides where values live.** `APPLICATION` writes the IDE-wide prefs file; `PROJECT` writes the
  open project's `.platform/`. Pick by whether the setting is about the user or about the project.
- **`advanced = true` collapses a control** into the page's Advanced group. Use it for anything a normal user
  should not have to read past.
- **`controls()` is re-queried when the page is shown**, so it can depend on current state.
- **`onChanged` runs host code.** This is exactly the kind of hook the trust model will gate once capabilities
  are enforced. Keep it cheap and side-effect-obvious.

`VcsSettingsPage` in [`VcsPlugin.kt`](../ide-core/src/main/kotlin/dev/ide/core/VcsPlugin.kt) is a short,
production example: three text fields, one of them `advanced`, persisting under `settings.vcs.*`.

---

## 9. Add actions

CodeAssist has a hybrid action model. Choosing the right half is the main decision:

| | Engine action (`IdeAction`) | UI host action (`UiHostAction`) |
| --- | --- | --- |
| Module | [`:plugin-api`](../plugin-api/src/main/kotlin/dev/ide/plugin/action/IdeAction.kt) | [`:ide-ui-api`](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/ext/UiActions.kt) |
| Crosses the UI boundary | Yes, as neutral DTOs | No, it lives on the UI side |
| Use for | Pure engine operations: build, re-index, refactor, generate | Driving the running UI: navigate to a screen, toggle the theme, open a file |
| Registered via | `UI_ACTION_EP` on the engine registrar | `scope.action(...)` on a `UiPlugin` |

The split exists because "run a build" is expressible as data an engine can execute anywhere, while "navigate
to the Dependencies screen" is meaningless outside a running Compose UI.

### 9.1 Engine actions

```kotlin
reg.register(
    UI_ACTION_EP,
    SimpleAction(
        id = "hello.greet",
        text = "Say Hello",
        places = setOf(ActionPlaces.COMMAND_PALETTE),
        iconId = "sparkle",
        order = 100,
    ) { ctx ->
        val root = ctx.projectRoot ?: return@SimpleAction ActionResult.message("No project is open")
        ActionResult.message("Hello from $root")
    },
)
```

| Type | Role |
| --- | --- |
| [`IdeAction`](../plugin-api/src/main/kotlin/dev/ide/plugin/action/IdeAction.kt) | One invocable command: `id`, `text`, `iconId`, `places`, `order`, `isVisible`, `isEnabled`, `suspend perform` |
| [`SimpleAction`](../plugin-api/src/main/kotlin/dev/ide/plugin/action/Builders.kt) | Lambda-backed `IdeAction`, when a dedicated class is overkill |
| [`ActionGroup`](../plugin-api/src/main/kotlin/dev/ide/plugin/action/ActionGroup.kt) / `SimpleGroup` | Menu nesting; `children(ctx)` returns action and group ids, and the literal `"---"` inserts a divider |
| [`ActionContext`](../plugin-api/src/main/kotlin/dev/ide/plugin/action/ActionContext.kt) | Read-only snapshot: `place`, `projectRoot`, `activeFilePath`, `selectionStart/End`, `contextPath`, plus `caret` and `documentText` for the editor places |
| [`ActionResult`](../plugin-api/src/main/kotlin/dev/ide/plugin/action/ActionResult.kt) | A status `message` plus declarative `effects` |
| [`ActionEffect`](../plugin-api/src/main/kotlin/dev/ide/plugin/action/ActionEffect.kt) | `OpenFile`, `Navigate`, `RefreshTree`, `ReloadFile`, `ApplyEdits`, `ApplyWorkspaceEdit`, `MoveCaret`, `Select`, `CreateFile`, `RenameFile`, `DeleteFile` |

`ActionEffect` is why an engine action can ask the UI to navigate without depending on the UI: the action
returns an instruction, and the UI applies the ones it can honour and ignores the rest.

`isVisible` hides an action entirely; `isEnabled` leaves it listed but greyed. Prefer greying for an action
that is temporarily unavailable: a vanishing menu item reads as a bug.

### 9.2 Places

[`ActionPlaces`](../plugin-api/src/main/kotlin/dev/ide/plugin/action/ActionPlaces.kt). The UI-side string
mirror is `dev.ide.ui.backend.UiActionPlaces`.

| Place | Where it renders | Context you get |
| --- | --- | --- |
| `MAIN_TOOLBAR` (`mainToolbar`) | The editor top bar, in a slot beside the built-in chrome | Active file, selection |
| `MAIN_OVERFLOW` (`mainToolbar.overflow`) | Where the compact/mobile top bar folds overflow | Same |
| `MORE_MENU` (`moreMenu`) | The editor "More" sheet | Same |
| `FILE_CONTEXT` (`fileContext`) | File-tree row long-press / right-click | `contextPath` = the node |
| `EDITOR_TAB` (`editorTab`) | An open tab's context menu | `activeFilePath` = the tab's file |
| `COMMAND_PALETTE` (`commandPalette`) | The searchable palette | Global, plus the caret when an editor is focused |
| `EDITOR` (`editor`) | The Alt-Enter popup, the editor overflow menu, and the palette | `caret`, `documentText`, selection |

`ActionPlace` is a `@JvmInline value class` over a string, and an open set: a plugin can invent
its own place for its own surface without changing the platform.

### 9.3 Editor actions

An action placed on `ActionPlaces.EDITOR` acts on code. It is listed in the Alt-Enter popup beside the
analysis quick-fixes and intentions, in the editor's overflow menu (where an `ActionGroup` becomes a
submenu), and in the command palette while an editor is focused.

Two extra pieces of context arrive for these places:

- [`CaretContext`](../plugin-api/src/main/kotlin/dev/ide/plugin/action/CaretContext.kt) on
  `ActionContext.caret`: the caret `offset`, the file's `languageId`, and the innermost tree node's
  `nodeKind` / `nodeStart` / `nodeEnd` / `nodeText`, plus `ancestors` (innermost first to the file root,
  each a `CaretAncestor` carrying its own kind and span). `isInside(kind)` tests where the caret sits and
  `enclosing(kind)` returns what to act on.
- `ActionContext.documentText`: the live buffer, including unsaved changes. Compute edits against exactly
  this string; the host applies them to the same buffer, so offsets cannot drift.

Node kinds are the open string set the engine's DOM uses (`class_decl`, `method_decl`, `block`,
`method_call`, `literal`, and language-specific ids such as Kotlin's `kt.lambda`). Gate an action with
`isVisible` so it is listed only where it applies: a popup the user opened to fix one thing should not
fill up with actions that do not.

```kotlin
reg.register(
    UI_ACTION_EP,
    SimpleAction(
        id = "hello.wrapInRun",
        text = "Wrap Call in run { }",
        places = setOf(ActionPlaces.EDITOR),
        visible = { it.caret?.nodeKind == "method_call" },
    ) { ctx ->
        val caret = ctx.caret ?: return@SimpleAction ActionResult.NONE
        val call = ctx.documentText?.substring(caret.nodeStart, caret.nodeEnd)
            ?: return@SimpleAction ActionResult.NONE
        ActionResult.effect(
            ActionEffect.ApplyEdits(
                TextEdit.replace(caret.nodeStart, caret.nodeEnd, "run { $call }"),
            ),
        )
    },
)
```

Edits go through the editor's own text path, so a plugin's rewrite lands in the same undo step as typing
and re-triggers analysis normally. `ApplyWorkspaceEdit` spans files (open buffers are edited in place,
closed files are written through), and `CreateFile` never overwrites an existing file. Pair an edit with
`Select` to leave a generated name ready to type over, which is how a refactor asks for a name without a
dialog.

#### When to use the analysis tier instead

`CaretContext` is flat on purpose: listing runs on caret moves, and the snapshot has to cross the
`IdeBackend` port. An action that needs to walk the syntax tree, resolve a symbol, or query the project
index belongs on the analysis extension point instead:
[`ActionProvider`](../analysis-api/src/main/kotlin/dev/ide/analysis/QuickFix.kt) on
`platform.actionProvider`, whose `actions(ctx: EditorActionContext)` receives the live `DomNode`, its
ancestor chain, the resolver, and the index, and returns `QuickFix`es producing a `WorkspaceEdit`. Both
tiers are listed in the same popup and menu, so the choice is about what the action needs, not where it
appears.

`analysis-api` is published alongside `plugin-api`, so an installed plugin can use either tier. The
difference is what each one costs and reaches, not who is allowed to use it:

| | `IdeAction` on `EDITOR` | `ActionProvider` |
| --- | --- | --- |
| Module | `plugin-api` (needs only `platform-core`) | `analysis-api` (pulls in `language-api` and `index-api`) |
| Context | Flat `CaretContext` plus the buffer | Live DOM, resolver, index, module |
| Scope | Any language, or gated on `caret.languageId` | Declared `languages` set |
| Produces | `ActionEffect`s, including file and caret effects | `WorkspaceEdit` (text edits only) |
| Good for | Line and text rewrites, sending code elsewhere, file moves | Type-aware refactors, generated members, import fixes |

One consequence of that last row: only the portable tier can place the caret or a selection, since a
`WorkspaceEdit` carries edits and nothing else. An analysis-tier refactor that generates a name cannot
leave it selected for the user to type over.

### 9.4 How it reaches the UI

[`ActionManager`](../plugin-impl/src/main/kotlin/dev/ide/plugin/impl/ActionManager.kt) is the single consumer
of `UI_ACTION_EP` / `ACTION_GROUP_EP`. It resolves a place into an ordered, visibility-filtered list
(`actionsFor`), expands groups into a menu tree with separators collapsed (`menuFor`), and dispatches by id
(`invoke`). The host exposes it across the boundary as `IdeBackend.actions`
([`ActionService`](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/backend/BackendServices.kt)), and the UI
renders `UiActionItem` / `UiMenuGroup` and applies `UiActionEffect`s.

An unknown action id returns a message result rather than throwing, so a stale UI round trip degrades
gracefully.

---

## 10. Contribute UI

Module: [`:ide-ui-api`](../ide-ui-api), package `dev.ide.ui.ext`. This is the Compose-bearing half of the
hybrid model: contributions that render their own bodies and therefore cannot cross the `IdeBackend` boundary
as data.

### 10.1 `UiPlugin` and `UiContributionScope`

[`UiPlugin.kt`](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/ext/UiPlugin.kt)

```kotlin
interface UiPlugin {
    val id: String
    fun contributeUi(scope: UiContributionScope)
}

interface UiContributionScope {
    val pluginId: String
    fun action(action: UiHostAction): Registration
    fun toolWindow(toolWindow: ToolWindowContribution): Registration
    fun screen(screen: ScreenContribution): Registration
    fun viewMode(mode: EditorViewModeContribution): Registration
    fun overlay(overlay: OverlayContribution): Registration
    fun tabDecoration(decoration: TabDecorationContribution): Registration
    fun treeIcon(iconId: String, icon: TreeIcon): Registration
    fun editorLanguage(profile: EditorLanguageProfile): Registration
}
```

One scope covers the process-global UI registries, so there is a single place to look for what a plugin can
add to the UI. Each method returns a `Registration` that the plugin's unload disposes.

`editorLanguage` is the text layer: an
[`EditorLanguageProfile`](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/ext/EditorLanguages.kt) teaching the
editor a language's keywords, comment markers, and lexical family, which gives it coloring, Toggle Comment,
and brace-aware Enter. See [custom-language-support.md](custom-language-support.md) for the whole language
surface.

### 10.2 How a UI facet gets loaded

```
BuiltInPlugins        ApplicationEnvironment          IdeBackend            CodeAssistApp
BuiltInPlugin(     →  loads enabled engine facets  →  uiPlugins()      →   UiPluginHost.register(it)
  engine, ui)         exposes enabledUiPlugins                             UiPluginHost.ensureLoaded()
```

`ApplicationEnvironment.enabledUiPlugins` is already filtered by `PluginCatalog`, so a disabled plugin's UI
never reaches `UiPluginHost`, and there is no per-plugin gating in the UI layer at all. `ensureLoaded()` is
idempotent and is called from the shell at startup and defensively from the panel hosts.

### 10.3 Tool windows

[`ToolWindows.kt`](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/ext/ToolWindows.kt)

```kotlin
scope.toolWindow(
    ToolWindowContribution(
        id = "hello.panel",
        title = "Hello",
        iconId = "sparkle",
        anchor = ToolWindowAnchor.LEFT,
        order = 40,
        content = { ctx -> HelloPanel(ctx) },
    ),
)
```

| Anchor | Renders as | Host code |
| --- | --- | --- |
| `LEFT` | An icon on the left activity rail, next to Files / Search / Structure; the docked pane or the phone push drawer shows the body | `buildLeftPanels` in [`EditorLayouts.kt`](../ide-ui/src/commonMain/kotlin/dev/ide/ui/screens/EditorLayouts.kt) |
| `RIGHT` | A top-bar toggle and the right rail on desktop; a right-edge swipe drawer on phones | [`RightToolOverlay.kt`](../ide-ui/src/commonMain/kotlin/dev/ide/ui/components/RightToolOverlay.kt), `EditorCenter.kt` |
| `BOTTOM` | An extra tab in the build console, after Problems / Log / Steps | [`BuildConsole.kt`](../ide-ui/src/commonMain/kotlin/dev/ide/ui/components/BuildConsole.kt) |

`order` sorts within an anchor and is merged with the built-in panels, so a contribution can slot between
them. Both the LEFT and RIGHT surfaces **self-gate**: with no contribution for an anchor, the host renders
nothing there, rather than a placeholder for a feature that is switched off.

`content` receives a [`ToolWindowContext`](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/ext/ToolWindows.kt):

| Member | Purpose |
| --- | --- |
| `backend: IdeBackend` | Everything the engine exposes to the UI |
| `activeFilePath: String?` | The file in the active editor |
| `fileActions: FileActions` | Platform bridges: open an external link, share or pick a file |
| `openScreen(id)` | Navigate to a contributed [screen](#104-screens) |

### 10.4 Screens

A sidebar panel is too narrow for a branch list, a commit history, or a diff, especially on a phone, so a
panel's deeper flows are screens rather than nested sheets.

```kotlin
scope.screen(ScreenContribution("hello.detail", "Hello detail") { ctx -> HelloDetail(ctx) })
```

`ScreenContext` gives you `backend`, `fileActions`, `back()`, and `openScreen(id)` for screen-to-screen
navigation. The host routes every contributed screen through one generic destination
(`Screen.PluginScreen` in [`AppNavGraph.kt`](../ide-ui/src/commonMain/kotlin/dev/ide/ui/AppNavGraph.kt)), so
adding a screen needs no navigation-graph edit.

Reach a screen from: a tool window's `ctx.openScreen(id)`, a `UiHostAction` navigating to it, or an engine
action returning `ActionEffect.Navigate(id)`.

### 10.5 Overlays

An app-wide floating layer rendered above every screen, for something that must appear regardless of where the
user is, such as a permission prompt.

```kotlin
scope.overlay(OverlayContribution("hello.prompt") { ctx -> HelloPrompt(ctx.backend) })
```

The body **decides its own visibility**: it typically observes a backend flow and renders nothing until there
is something to show. All registered overlays are composed by
[`AppOverlays.kt`](../ide-ui/src/commonMain/kotlin/dev/ide/ui/AppOverlays.kt).

### 10.6 Editor view modes

Beyond the built-in Code / Blocks / Preview / Split surfaces:

```kotlin
scope.viewMode(
    EditorViewModeContribution(
        id = "hello.hex",
        label = "Hex",
        appliesTo = { path -> path.endsWith(".bin") },
        content = { ctx -> HexView(ctx.text) },
    ),
)
```

`appliesTo` gates the mode per file, so it is only offered for files it can actually render. `ViewModeContext`
carries `backend`, `filePath`, and the live `text`.

### 10.7 UI host actions

```kotlin
scope.action(
    SimpleUiAction(
        id = "hello.openDetail",
        text = "Hello detail",
        places = setOf(UiActionPlaces.MORE_MENU, UiActionPlaces.COMMAND_PALETTE),
        description = "Open the Hello detail screen",
        iconId = "sparkle",
        order = 200,
    ) { host -> host.openScreen("hello.detail") },
)
```

[`UiActionHost`](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/ext/UiActions.kt) is supplied by the shell at
invocation time: the action is a static contribution, while the host varies with the current screen. It
exposes `backend`, `navigate(destination)`, `toggleTheme()`, `openFile(path, offset)`, and `message(text)`.
Named destinations live in `UiDestinations` (`HUB`, `SETTINGS`, `MODULES`, `SDK`, `KEYSTORES`, `LOGS`,
`PROJECTS`, `DEPENDENCIES`, `CODE_STYLE`, `ICONS`).

`description` is a subtitle for list-style menus; the "More" sheet renders it.

The IDE's own More-menu and palette entries are contributed exactly this way, through
[`BuiltInUiPlugin`](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/ext/BuiltInUiActions.kt), which is the
reference example.

### 10.8 Icon ids

Icon ids are opaque strings both sides agree on, resolved by
[`actionIcon(iconId)`](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/icons/ActionIcons.kt). An unknown id
falls back to a generic glyph, so a plugin naming an icon this build does not ship still renders.

Available today, grouped: `run`/`play`, `stop`, `refresh`/`reindex`, `build`/`hammer`, `save`, `search`/`find`,
`settings`/`gear`, `terminal`/`console`, `copy`, `code`, `braces`/`codeStyle`/`format`, `file`/`doc`, `folder`,
`share`, `command`, `eye`, `image`, `sparkle`/`ai`/`chat`, `layers`/`modules`, `pkg`/`sdk`, `key`/`keystore`/
`signing`, `lightbulb`/`inspections`/`analysis`, `git`/`branch`/`vcs`, `commit`/`history`, `merge`,
`pullRequest`, `pull`/`fetch`/`download`, `push`/`upload`, `account`/`user`/`signIn`, `stash`, `close`, `plus`.

File-tree icons are separate: `scope.treeIcon(iconId, icon)` registers into `TreeIcons`. Note that tree icons
are a persistent lookup, so the returned `Registration` is a no-op: nothing unregisters an icon.

### 10.9 Tab decorations

[`TabDecorations.kt`](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/ext/TabDecorations.kt)

An open editor tab carries one status dot: amber while the file has unsaved edits, and otherwise whatever a
decoration reports. A decoration answers "does this tab need attention" without the user opening it.

```kotlin
scope.tabDecoration(
    TabDecorationContribution("hello.tab.todos", order = 200) { tab ->
        if (tab.path in TodoStore.flagged) TabDecoration(IconTint.Warning, "has TODOs") else null
    },
)
```

The dot is host-drawn, so a decoration is data rather than a `@Composable` body: a themed `IconTint` (use
`IconTint.Fixed(color)` only when the color itself carries meaning), a `TabDotStyle`, and a label for
accessibility. The producer, though, *is* composable, so it reads state and the strip re-decorates when those
reads change:

| Member | Purpose |
| --- | --- |
| `path`, `name` | The tab's workspace path (`library://…` for a library tab) and displayed name |
| `active`, `modified` | Whether the tab is focused, and whether it has unsaved edits |
| `staleOnDisk` | The file changed on disk while the tab held unsaved edits, so the host did not reload it |
| `diagnostics`, `errorCount`, `warningCount` | The diagnostics anchored to the tab's buffer |
| `backend` | The engine, for a producer whose state lives behind it (a build, version control, your own service) |

Three constraints come with the slot:

- **One tab, one dot.** Producers are asked in `order`, low first, and the first non-null claims the tab.
  The built-ins occupy 50 (changed on disk), 100 (analysis errors and warnings) and 120 (build errors), so a
  lower order outranks them and a higher one fills in where they decline. The Git plugin's conflict dot goes
  above all of them at 40; its working-copy dot below at 200.
- **Two shapes, so the colors go further.** `TabDotStyle.Filled` is something to act on now (errors, a file
  that changed underneath you); `Outlined` is a standing property of the file (warnings, differing from
  HEAD). A filled amber dot already means unsaved edits, so do not spend it on anything else.
- **Decide, do not work.** `decorate` runs once per open tab on every recomposition of the strip. Subscribe
  where the state is produced (`IdeEventTopics.ANALYSIS` carries a file's merged diagnostics to the engine
  facet), keep the answer in an observable store, and read that store here.

`diagnostics` is the tab's last analysis result. Every open tab is analyzed, not just the focused one
([`OpenTabDiagnostics.kt`](../ide-ui/src/commonMain/kotlin/dev/ide/ui/editor/engine/OpenTabDiagnostics.kt)
sweeps the rest when a tab opens and again whenever indexing or a build settles), but a background tab is not
re-analyzed while it sits there, so its diagnostics are as old as the last of those events. A decoration that
needs more than that reads a source with it, the way the built-in build dot reads
`backend.build.buildState`.

### 10.10 Setting up a UI module

A UI plugin module is Compose Multiplatform with a desktop (JVM) and an Android target. Depend on `:ide-ui`,
which re-exposes `:ide-ui-api` via `api`, so the `UiPlugin` SPI and the shell's design system both come
through:

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    android {
        namespace = "dev.ide.hello.ui"
        compileSdk = 36
        minSdk = 24
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":ide-ui"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
```

[`vcs-ui/build.gradle.kts`](../vcs-ui/build.gradle.kts) is the reference. Two conventions it follows:

- **Talk to the engine only through `IdeBackend`.** No `:ide-core`, no `java.nio`, no engine types.
- **Keep your strings in your own module.** `compose.resources { publicResClass = false; packageOfResClass =
  "dev.ide.hello.ui.generated.resources" }` keeps the generated accessor a private detail. See
  [localization.md](localization.md). Note that Compose Resources renders `\'` literally, so write `'` directly.

### 10.11 An installed plugin's UI facet

Everything above is the **built-in** UI model (`dev.ide.ui.ext.UiPlugin`), which lives in `ide-ui-api`.
A plugin shipped as its own app uses a second, narrower interface instead: `dev.ide.plugin.ui.UiPlugin`, from
the published [`plugin-ui-api`](../plugin-ui-api). Same idea, deliberately smaller surface:

| | Built-in (`ide-ui-api`) | Installed (`plugin-ui-api`) |
| --- | --- | --- |
| Entry point | `UiPlugin.contributeUi(scope)` | `UiPlugin.contribute(ui)` |
| Declared in | `BuiltInPlugin(engine, ui)` in `BuiltInPlugins.kt` | `uiEntryPoints` in the packaged manifest |
| Contributions | tool windows, screens, overlays, view modes, host actions, tab decorations, tree icons, editor languages | tool windows, screens, overlays |
| A body is handed | `ToolWindowContext` etc., carrying the whole `IdeBackend` | `UiContext`: active file, project path, `openFile`, `openScreen` |
| Teardown | `Registration` | `UiHandle` |

The narrow context is the point. Publishing the built-in model would freeze `IdeBackend`, every concern
service and DTO in it, as plugin API, and that is the layer that changes most. An installed plugin does not
need it: its **engine facet** has the whole engine SPI (files, project model, indexes, analysis, the message
bus) and shares its classloader, so the panel calls into its own plugin for anything real and asks the host
only for what is a property of the running UI.

The host adapts one model onto the other in
[`ExternalUiPlugin.kt`](../ide-ui-api/src/jvmShared/kotlin/dev/ide/ui/ext/ExternalUiPlugin.kt), so a
contributed panel lands in the same registries a built-in's does and the shell renders both the same way. Ids
are not namespaced on the way through, which is what lets an engine-side action return
`ActionEffect.Navigate("com.example.screen")` and open the UI facet's own screen.

Loading and gating: the facets are instantiated off the classloader the engine facet was loaded from, only
for a plugin that is enabled, consented to, and whose engine facet loaded cleanly. Each class the manifest
names is instantiated once, so naming one class in both lists gives you one object rather than two halves
with separate copies of every field. A UI facet that is missing, is not a `UiPlugin`, or throws while being
created is reported on that plugin's row in the Plugins screen; its engine facet keeps running, which is the
reason to keep them two classes when they really are separate. Packaging, the Compose pins and the two-facet pairing are in
[section 15](#15-ship-your-plugin-as-its-own-app).

---

## 11. Case study: the Git plugin

Version control is the most complete plugin in the tree: it brings its own engine, its own UI, its own
extension point, a settings page, a backend service, and a full enable/disable path. Deeper coverage of the
Git engine itself is in [version-control.md](version-control.md).

### 11.1 The module map

| Module | Contains | Why it is separate |
| --- | --- | --- |
| [`:vcs-api`](../vcs-api) | `VcsProvider`, `VcsRepository`, the model, credentials, forge ports, `VCS_PROVIDER_EP` | A neutral SPI other providers and consumers compile against without pulling JGit |
| [`:vcs-impl`](../vcs-impl) | `GitProvider`, `GitRepository` (JGit), `GitHubClient`, `FileAccountStore` | The engine and its heavy dependency, isolated from everything that only needs the model |
| [`:vcs-ui`](../vcs-ui) | `VcsUiPlugin`, `GitPanel`, and the seven screens | Compose Multiplatform; cannot live in an engine module |
| `:ide-core` | `VcsPlugin` (engine facet), `VcsBackend` (the `VcsService` impl) | The host wiring point where the plugin joins the app |

This api / impl / ui split is the repository convention, and it exists so the dependency direction stays
acyclic. See [modules.md](modules.md).

### 11.2 The extension point it publishes

[`vcs-api/VcsProvider.kt`](../vcs-api/src/main/kotlin/dev/ide/vcs/VcsProvider.kt)

```kotlin
interface VcsProvider {
    val id: String
    val displayName: String
    fun findRoot(dir: Path): Path?
    fun open(root: Path): VcsRepository
    fun init(dir: Path, defaultBranch: String = "main"): VcsRepository
    fun clone(url: String, target: Path, branch: String? = null, depth: Int = 0,
              auth: VcsCredentials? = null, progress: VcsProgress = VcsProgress.None): VcsRepository
}

val VCS_PROVIDER_EP = ExtensionPoint<VcsProvider>("platform.vcsProvider")
```

**Why it exists:** so support for Mercurial or Fossil is one more registration rather than a host change. The
host asks every registered provider whether a directory is a checkout it owns and uses the first that answers.

**Why the built-in Git provider is not registered on it:** `GitProvider` needs the resolved config directory,
which is not known until a project manager exists. So `VcsBackend` resolves the EP first and falls back to
constructing `GitProvider` itself:

```kotlin
private val provider: VcsProvider? by lazy {
    ctx.manager?.env?.platform?.extensions?.extensions(VCS_PROVIDER_EP)?.firstOrNull()
        ?: configDir?.let { GitProvider(it) }
}
```

The pattern is that the extension point is the seam for additions, while a context-heavy built-in stays a
concrete field. The same reasoning applies to `BUILD_SYSTEM_EP`, where the built-in Java and Android build
systems stay per-project engine fields while the EP carries plugin additions.

### 11.3 The engine facet

[`ide-core/VcsPlugin.kt`](../ide-core/src/main/kotlin/dev/ide/core/VcsPlugin.kt), in full:

```kotlin
internal class VcsPlugin : Plugin {
    override val manifest = PluginManifest(
        id = ID,
        name = "Version Control",
        description = "Git for your projects: changes, commits, branches, history, and GitHub sign-in.",
    )

    override fun register(reg: PluginRegistration) {
        reg.register(SETTINGS_PAGE_EP, VcsSettingsPage)
    }

    companion object {
        const val ID = "vcs"
        const val PAGE = "vcs"
        const val PREF_USER_NAME: String = "settings.$PAGE.userName"
        const val PREF_USER_EMAIL: String = "settings.$PAGE.userEmail"
        const val PREF_CLIENT_ID: String = "settings.$PAGE.githubClientId"
    }
}
```

Points that generalise to any plugin:

- **The engine facet is small.** It registers one settings page. It is not where the feature lives; it is
  where the feature's identity lives. The manifest here is what the catalog, the Plugins screen, and both
  gating checks key off.
- **Preference keys are constants derived from the page id.** `VcsBackend` reads
  `ctx.manager?.preference(VcsPlugin.PREF_USER_NAME)`; nothing repeats the string.
- **`internal` is fine.** A built-in plugin does not need to be public; only `BuiltInPlugins` references it.

### 11.4 The backend service, and gating

The working copy is served by a concern backend: a `VcsService` implementation over the neutral UI port.
[`IdeServicesBackend`](../ide-core/src/main/kotlin/dev/ide/core/IdeServicesBackend.kt) wires it and gates it
on the plugin being enabled:

```kotlin
override val vcs: VcsService =
    if (manager?.env?.pluginCatalog?.isEnabled(VcsPlugin.ID) != false) VcsBackend(this)
    else VcsService.Unsupported
```

Three details that matter:

1. **`VcsService.Unsupported` is a real, complete no-op implementation**, not a null. Every method on
   [`VcsService`](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/backend/VcsService.kt) has a default, so the
   UI never branches on nullability; it asks `supported()` and hides the surface.
2. **`!= false`** rather than `== true`: a manager-less backend (tests, a single-project harness) has no
   catalog, so the feature stays wired instead of silently vanishing in tests.
3. **The gate is on the id constant**, so renaming the plugin id is a compile-time change, not a silent
   behaviour change.

`VcsBackend` itself demonstrates the engine-side rules a plugin service should follow: everything crosses as
plain DTOs so the UI never sees a JGit type; shared reads are `StateFlow`s the backend refreshes; one-shot
commands are `suspend` and return a result carrying a message already fit to show; and repository access is
serialized behind a mutex because JGit commands are not safe for concurrent use on the same repository.

### 11.5 The UI facet

[`vcs-ui/VcsUiPlugin.kt`](../vcs-ui/src/commonMain/kotlin/dev/ide/vcs/ui/VcsUiPlugin.kt), also in full:

```kotlin
object VcsUiPlugin : UiPlugin {
    override val id: String = "vcs-ui"

    override fun contributeUi(scope: UiContributionScope) {
        scope.toolWindow(
            ToolWindowContribution(
                id = LeftPanelId.SOURCE,     // the shell's source-control rail slot
                title = "Git",
                iconId = "git",
                anchor = ToolWindowAnchor.LEFT,
                order = 40,
                content = { ctx -> GitPanel(ctx) },
            ),
        )
        scope.screen(ScreenContribution(VcsService.SCREEN_BRANCHES, "Branches") { ctx -> BranchesScreen(ctx) })
        scope.screen(ScreenContribution(VcsService.SCREEN_HISTORY,  "History")  { ctx -> HistoryScreen(ctx) })
        scope.screen(ScreenContribution(VcsService.SCREEN_DIFF,     "Diff")     { ctx -> DiffScreen(ctx) })
        scope.screen(ScreenContribution(VcsService.SCREEN_ACCOUNTS, "Accounts") { ctx -> AccountsScreen(ctx) })
        scope.screen(ScreenContribution(VcsService.SCREEN_CLONE,    "Clone")    { ctx -> CloneScreen(ctx) })
        scope.screen(ScreenContribution(VcsService.SCREEN_STASHES,  "Stashes")  { ctx -> StashesScreen(ctx) })
        scope.screen(ScreenContribution(VcsService.SCREEN_GITHUB,   "GitHub")   { ctx -> GitHubScreen(ctx) })
    }
}
```

Why it is shaped this way:

- **One panel, seven screens.** The panel is the entry point; everything reached from it is a full screen
  rather than a nested sheet, because a sidebar panel cannot hold a branch list, a commit history, or a diff
  on a phone.
- **It registers under `LeftPanelId.SOURCE`**, the shell's source-control slot, so it takes the rail position
  the placeholder used to hold and the phone bottom-nav slot that maps to it. Nothing else claims that id.
  This is the mechanism for taking over a well-known host slot, and the host does not special-case Git anywhere.
- **The screen ids are constants on `VcsService`**, so the engine's `authRequired` result and the UI's
  `openScreen` call cannot drift apart.

The panel body is an ordinary composable over `ToolWindowContext`:

```kotlin
internal fun GitPanel(ctx: ToolWindowContext) {
    val vcs = ctx.backend.vcs
    val status by vcs.status.collectAsState()
    val activity by vcs.activity.collectAsState()

    // The panel may be opened long after the last file-system change, so re-read on entry rather than
    // trusting the cached snapshot.
    LaunchedEffect(ctx.backend, hasProject) { vcs.refresh() }

    fun perform(block: suspend () -> UiVcsResult) {
        scope.launch {
            val result = block()
            if (result.message.isNotBlank()) feedback.show(result.message, isError = !result.ok)
            if (result.authRequired) ctx.openScreen(VcsService.SCREEN_ACCOUNTS)   // engine → UI navigation
        }
    }
    // …
}
```

### 11.6 The wiring checklist

Everything above joins the app through three edits outside the plugin's own modules:

```kotlin
// settings.gradle.kts: inside the CI_CORE_ONLY guarded block, because vcs-ui applies Compose + AGP
":vcs-ui",

// ide-core/build.gradle.kts
implementation(project(":vcs-impl"))
implementation(project(":vcs-ui"))

// ide-core/BuiltInPlugins.kt
BuiltInPlugin(VcsPlugin(), ui = VcsUiPlugin),
```

That single `BuiltInPlugin` line is the whole registration. Turning the toggle off in **Settings → Plugins**
and restarting removes the settings page, the `VcsService` (the UI gets `Unsupported`), the Git rail panel,
the phone nav slot, and all seven screens, with no feature-specific code anywhere in the shell.

---

## 12. Case study: the AI Agent plugin

The agent is a smaller contrast to Git: the same two-facet structure, different UI surfaces.

[`agent-ui/AgentUiPlugin.kt`](../agent-ui/src/commonMain/kotlin/dev/ide/agent/ui/AgentUiPlugin.kt)

```kotlin
object AgentUiPlugin : UiPlugin {
    override val id: String = "agent-ui"

    override fun contributeUi(scope: UiContributionScope) {
        scope.toolWindow(
            ToolWindowContribution(
                id = "agent.chat",
                title = "AI",
                iconId = "sparkle",
                anchor = ToolWindowAnchor.RIGHT,
                content = { ctx -> ChatDrawer(ctx.backend) },
            ),
        )
        scope.overlay(
            OverlayContribution("agent.permission") { ctx -> AgentPermissionDialog(ctx.backend) },
        )
    }
}
```

| Difference from Git | Why |
| --- | --- |
| `RIGHT` anchor rather than `LEFT` | Chat is a companion to the editor, not a navigator. It gets the desktop right rail and the phone right-edge swipe drawer |
| An **overlay** rather than screens | The write-permission prompt must appear over any screen while a mutating tool call waits for approval. The overlay body observes `backend.agent.permissionRequest` and renders nothing otherwise |
| Its own id rather than a host slot id | Nothing in the shell reserves a slot for chat, so it introduces one |

The engine facet, [`AgentPlugin`](../ide-core/src/main/kotlin/dev/ide/core/AgentPlugin.kt), mirrors
`VcsPlugin` exactly: one settings page (provider choice and API keys), an `ID` constant, and the same
`isEnabled(AgentPlugin.ID)` gate on `IdeBackend.agent`.

Because the RIGHT surfaces are fully plugin-derived, disabling the agent leaves the right rail and the swipe
drawer rendering nothing at all, because the host has no chat-specific chrome to hide. See
[agentic-coding.md](agentic-coding.md).

---

## 13. Enable, disable, and dependencies

### 13.1 What the user sees

**Settings → Plugins** lists every plugin with its name, version, description, and a toggle, under two tabs.
**Built-in** plugins ship inside the IDE; **Installed** plugins came from a separate app the user installed,
and each of those rows also carries the package it came from and, if it did not load this launch, why. Each
tab label carries its count, so an installed plugin is visible without switching tabs.
Essential plugins show a locked "Required" pill, which never applies to an installed plugin.

Nothing on this screen is live, because plugins are loaded once per process. So the screen carries a hint
naming everything a restart would apply, and a **Restart now** button that applies it. What lands in that
list is both the answers given here and what has happened to the plugin apps on the device since launch:
`PluginPackageWatcher` (in `:ide-android`) watches the package manager and records an install, an install over
an existing plugin, or an uninstall on
[`PluginChanges`](../ide-core/src/main/kotlin/dev/ide/core/plugins/PluginChanges.kt). A change is listed only
when a restart would load something different, so toggling a plugin off and back on leaves nothing waiting.
The restart takes every process of the app down, including the `:build` daemon and `:preview`, since each one
loaded the installed plugins for itself; modified editor buffers are written to disk first.

The state flows: `PluginsScreen` → `SettingsService.setPluginEnabled(id, enabled)` →
[`SettingsBackend`](../ide-core/src/main/kotlin/dev/ide/core/backend/SettingsBackend.kt) →
`ProjectManager.setDisabledPlugins(...)`, persisted app-globally in `prefs.properties` under the key
`plugins.disabled`. At the next launch `ApplicationEnvironment` builds the catalog over the built-in manifests
plus whatever its `PluginSource`s discovered, and loads only the enabled subset. A disabled plugin is dropped
before its code is touched at all, so an installed one never even gets a classloader.

### 13.2 Making your plugin disable cleanly

A plugin disables cleanly when nothing it contributes is loaded and no host code has to know it exists.
The recipes, in order of preference:

| Your surface | How it disappears |
| --- | --- |
| An EP contribution | Automatically: the plugin is never loaded, so it never registers |
| A UI facet | Automatically: `enabledUiPlugins` filters it out before `UiPluginHost` sees it |
| An `IdeBackend` concern service | Gate the field: `if (catalog?.isEnabled(MyPlugin.ID) != false) MyBackend(this) else MyService.Unsupported` |
| Hard-coded UI that is not a `UiPlugin` | Expose a capability flag on a backend service, read it once into `IdeUiState`, and render conditionally |

The last row is the fallback path, and the block editor is the worked example: a togglable plugin whose
surface is a hard-coded editor toggle rather than a `UiPlugin`. It works like this. `BlocksPlugin` registers
the only `BLOCK_MAPPING_EP` contribution. The generic `BlockService` is inert with no mapping, and exposes an
`enabled` flag that is true only while `BLOCK_MAPPING_EP` has contributions. The shell reads that once into
`IdeUiState.blocksEnabled`, which is safe because the plugin set is app-global and restart-applied, and hides
the Code/Blocks toggle. Follow that shape rather than checking a plugin id in the UI layer.

### 13.3 `dependsOn` in practice

Declare an edge when a contribution's position in a registration-ordered EP matters, or when the plugin relies
on another plugin's types or services existing.

```kotlin
override val manifest = PluginManifest(
    id = "kotlin-language",
    name = "Kotlin Language",
    dependsOn = listOf("jdt-language"),
)
```

Consequences, all of them intentional:

- `PluginManager` loads `jdt-language` first, always.
- If `jdt-language` is missing from the assembled set, `loadAll` throws at startup rather than misbehaving
  later.
- If the user disables a dependency, `PluginCatalog` drops every transitive dependent too, so the load graph
  stays valid and no plugin runs against a missing prerequisite.
- If a dependency is `essential`, the catalog force-enables it and everything it depends on.

### 13.4 Choosing `essential`

`essential = true` removes the user's choice, so it needs a real justification. The current essentials are
`platform` (the file-icon classifier and base file types), `jdt-language` and `java-psi-language` (the default
language backend and resolution fallback), and `ide-core-services` (the engine's scoped services). If the IDE
would merely be worse without the plugin, it is not essential.

---

## 14. Test your plugin

Plugins are testable without launching the app, which is why the SPI stays free of Compose and of the engine.

### Loading and contributions

```kotlin
@Test
fun `loads in dependency order regardless of declaration order`() {
    val reg = ExtensionRegistryImpl()
    val order = mutableListOf<String>()
    PluginManager(reg).loadAll(
        listOf(
            FakePlugin("b", dependsOn = listOf("a"), loadOrder = order),
            FakePlugin("a", loadOrder = order),
        )
    )
    assertEquals(listOf("a", "b"), order)
    assertEquals(listOf("a-impl", "b-impl"), reg.extensions(EP))
}
```

### Unload

```kotlin
@Test
fun `unload removes exactly the plugin's own contributions`() {
    val reg = ExtensionRegistryImpl()
    val mgr = PluginManager(reg)
    mgr.loadAll(listOf(FakePlugin("a"), FakePlugin("b")))
    mgr.unload(PluginId("a"))
    assertEquals(listOf("b-impl"), reg.extensions(EP))
}
```

### Enable/disable rules

Test the catalog directly, since it is pure:

```kotlin
val catalog = PluginCatalog(manifests, disabledIds = setOf("hello"))
assertFalse(catalog.isEnabled("hello"))
assertFalse(catalog.isEnabled("hello-extras"))   // transitively depends on hello
assertTrue(catalog.isEnabled("platform"))        // essential, force-enabled
```

### UI contributions

Registries are process-global, so register in the test and dispose in a `finally`:

```kotlin
@Test
fun leftToolWindowRegisters() {
    val reg = ToolWindowRegistry.register(
        ToolWindowContribution("test.explorer", "Explorer", "folder", ToolWindowAnchor.LEFT) {}
    )
    try {
        assertTrue(ToolWindowRegistry.forAnchor(ToolWindowAnchor.LEFT).any { it.id == "test.explorer" })
    } finally {
        reg.dispose()
    }
}
```

For rendering, `:ide-ui` desktop tests snapshot composables headlessly with `ImageComposeScene` and a
`StubBackend`. See [`ExtRegistryTest`](../ide-ui/src/desktopTest/kotlin/dev/ide/ui/components/ExtRegistryTest.kt).

### Reference tests to read

| Test | Covers |
| --- | --- |
| [`PluginManagerTest`](../plugin-impl/src/test/kotlin/dev/ide/plugin/impl/PluginManagerTest.kt) | Load order, unload, facade sweep, service registration |
| [`PluginCatalogTest`](../plugin-impl/src/test/kotlin/dev/ide/plugin/impl/PluginCatalogTest.kt) | Essentials, disabled closure, dependents |
| [`PluginBusLoggerTest`](../plugin-impl/src/test/kotlin/dev/ide/plugin/impl/PluginBusLoggerTest.kt) | Bus publish/subscribe and log attribution through the registrar |
| [`ActionManagerTest`](../plugin-impl/src/test/kotlin/dev/ide/plugin/impl/ActionManagerTest.kt) | Place resolution, menu expansion, dispatch |
| [`ExtRegistryTest`](../ide-ui/src/desktopTest/kotlin/dev/ide/ui/components/ExtRegistryTest.kt) | Tool-window anchors and palette resolution |

### Practical notes

- `:ide-core` is excluded under `CI_CORE_ONLY`; run its tests with that flag **unset**.
- `IdeAction.perform` is `suspend`; drive it with `runBlocking` in tests.

---

## 15. Ship your plugin as its own app

Everything above is the internal tier, where a plugin is a module inside the IDE. A plugin can instead be a
**separate Android app the user installs**: the IDE finds it through the package manager, reads its manifest,
and loads its classes off the installed APK. The `Plugin` you wrote does not change; only its packaging does.

A complete, buildable example of everything in this section is
[`samples/hello-plugin`](../samples/hello-plugin): `./gradlew :samples:hello-plugin:installDebug`. Its
[README](../samples/hello-plugin/README.md) also covers what changes when the plugin is a project of its own
rather than a module of this build.

You can also write one inside CodeAssist itself. **New project > Plugin > CodeAssist Plugin** scaffolds
everything below, wired up: it asks for a plugin id and what to contribute (a command, a settings page, both,
or a **tool window panel**), then generates the packaged manifest, the marker activity, and the entry point,
with the SPI already declared as a `compileOnly` dependency. Choosing the panel generates a UI facet instead,
and adds `plugin-ui-api` plus the Compose artifacts the IDE bundles, pinned; the Compose compiler needs no
declaration, since CodeAssist applies it to any module whose classpath carries the Compose runtime. While
editing `codeassist_plugin.toml` the IDE runs the same checks the loader makes (a malformed id, an
`apiVersion` this build does not load, a `minHostVersion` newer than the running IDE, an entry point that
names no class or a class that does not implement `Plugin` / `UiPlugin`, a missing marker activity), and
completes the manifest's keys, the plugin ids available to `dependsOn`, and the `Plugin` and `UiPlugin`
implementations in the project.

It also checks `capabilities`, which is the list the user reads at the consent gate when deciding whether to
let the plugin run at all: a value the IDE does not recognise is flagged, because it is shown to that user
exactly as written, and so is one whose facet the plugin does not declare (`ui.toolWindow` with no
`uiEntryPoints` cannot be delivered). The vocabulary is `PluginCapabilities` in the SPI.

**Watching your plugin run.** Anything your plugin logs through `reg.logger(...)` is attributed to its plugin
id, and **Settings > Plugins** offers **Logs** on an installed plugin's row, which opens the Logs viewer
filtered to that plugin. That is the difference between reading your own output and reading the whole IDE's.

Three things go into the plugin app, and a fourth if it has UI.

**1. The plugin manifest**, as `res/raw/codeassist_plugin.toml`. This is `PluginManifest` in TOML, and it is
what the IDE reads to build its catalogue, so it must agree with what your entry point contributes:

```toml
[plugin]
id = "com.example.hello"
name = "Hello"
version = "1.0.0"
apiVersion = 2
description = "Adds a Hello command and a settings page."
entryPoints = ["com.example.hello.HelloPlugin"]
uiEntryPoints = ["com.example.hello.HelloUiPlugin"]   # optional; the Compose UI facet
dependsOn = ["kotlin-language"]
capabilities = ["ui.action", "ui.settingsPage", "ui.toolWindow"]
minHostVersion = "3.11.0"
```

The two entry-point lists are independent: declare either, or both. A plugin with only `uiEntryPoints` is a
complete plugin (it loads, holds its place in the load order, and contributes its UI); a plugin with neither
is rejected.

`id` must match `[A-Za-z0-9][A-Za-z0-9._-]*`, the same shape as an `applicationId` or a Java package, which
is normally where it comes from. Case is part of the id, so write it the same way wherever another plugin
names it in `dependsOn`; two plugins whose ids differ only in capitalisation count as a clash and the second
is rejected. `apiVersion` must match the IDE's `PLUGIN_API_VERSION`, and `minHostVersion` is compared against
the running IDE's version. Anything wrong here, including an unparseable file or an id another plugin already
holds, is reported on the plugin's row in the Plugins screen rather than failing silently; a plugin listed
there with a reason and no switch is one the IDE found but could not read.

`essential` and `trusted` are ignored here: those are the IDE's to decide.

**2. A marker activity** in the app's `AndroidManifest.xml`, which is how the IDE finds the app at all, and
which doubles as your app's own screen:

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

**4. A UI facet**, if the plugin has UI. Implement `dev.ide.plugin.ui.UiPlugin` from `plugin-ui-api` and name
it in `uiEntryPoints`:

```kotlin
class HelloUiPlugin : UiPlugin {
    override val id = "com.example.hello"   // the packaged manifest's id

    override fun contribute(ui: UiRegistration) {
        ui.toolWindow(
            ToolWindow(
                id = "com.example.hello.panel",
                title = "Hello",
                iconId = "sparkle",                   // an id in the IDE's icon registry
                anchor = ToolWindowAnchor.LEFT,
            ) { ctx -> HelloPanel(ctx) },
        )
    }
}

@Composable
private fun HelloPanel(ctx: UiContext) {
    Text(ctx.activeFilePath?.let { "Editing ${it.substringAfterLast('/')}" } ?: "No file open")
}
```

The two facets need not be two classes. One class may implement both and be named in `entryPoints` and in
`uiEntryPoints`; the IDE instantiates it once, so the halves share ordinary fields and the panel reads
whatever `register` set up. Keep them apart when they really are apart: two classes fail independently, so a
UI facet that throws is reported against your plugin while its engine half goes on working, where one class
means one failure takes both.

Either way both are instantiated off the same APK on the **same classloader**, so they are ordinary Kotlin to
each other: with two classes a shared `object` is the whole channel between them, with nothing to serialise
and no extension point in the middle. (Two *different* plugins cannot do this, since each gets its own
classloader, and the message bus is the channel there.) `samples/hello-plugin` shows the pairing: the palette
command writes to a `HelloState` object and the panel reads it.

Compose has to be declared, and pinned to what the IDE bundles, because your `@Composable` code composes into
the IDE's own runtime:

```kotlin
dependencies {
    // The BOM carries the versions, including the Compose the IDE provides.
    compileOnly(platform("io.github.tyron12233:plugin-bom:2.0.0"))

    compileOnly("io.github.tyron12233:plugin-ui-api")
    compileOnly("androidx.compose.runtime:runtime")
    compileOnly("androidx.compose.foundation:foundation")
    compileOnly("androidx.compose.ui:ui")
    compileOnly("androidx.compose.material3:material3")
    compileOnly("androidx.compose.ui:ui-tooling-preview")   // for @Preview; see below
}
```

Writing the versions out (`androidx.compose.runtime:runtime:1.11.2`, and so on) works too, and is what a
project scaffolded inside the IDE does, since it builds on-device. The BOM is there so that a plugin built
with Gradle outside the IDE has one number to get right instead of thirteen.

Two consequences worth knowing before you build one:

- **A Compose version newer than the IDE's fails at first composition, not at build time.** `apiVersion` and
  `minHostVersion` cannot catch it: it is a linkage error inside a body that has already been registered.
  Compile against these versions, and against the Kotlin the IDE was built with, so the Compose compiler's
  output matches the runtime it lands on.
- **A plugin has no `Context` for its own package.** No drawables, no `stringResource`: icons are ids in the
  IDE's registry and text is Kotlin string literals.

#### Previewing a panel

A panel body takes a `UiContext`, so previewing one means having a context outside the IDE. `UiContext`
provides it, and the rest is an ordinary `@Preview` rendered by the editor's preview pane:

```kotlin
@Preview
@Composable
private fun HelloPanelPreview() {
    HelloPanel(UiContext.preview(activeFilePath = "MainActivity.kt"))
}

@Preview
@Composable
private fun HelloPanelNoFilePreview() {
    HelloPanel(UiContext.preview())
}
```

`preview()` answers the `projectPath` and `activeFilePath` you give it, and its `openFile`, `openScreen` and
`back` do nothing, because a preview has no editor to open a file in. `ScreenUiContext.preview()` is the same
thing for a contributed screen. The two states above are the ones worth having as separate previews: a panel
that reads the active file is usually written against the case where there is one, and looks wrong in the
case where there is not.

The scaffolded panel comes with a preview already, and `samples/hello-plugin` has two. This is the whole of
what previewing gives you: the body, composed. The rail, the panel frame and the plugin's registration are
not part of it, so an id or an anchor that is wrong still shows up only once the plugin is installed.

### What a separately-packaged plugin can reach

The engine SPI is published, so the extension points in these modules are available to a plugin app:

```kotlin
compileOnly(platform("io.github.tyron12233:plugin-bom:2.0.0")) // one version for everything below

compileOnly("io.github.tyron12233:plugin-api")        // actions, menus, palette commands
compileOnly("io.github.tyron12233:platform-core")     // scoped services, settings pages, logging
compileOnly("io.github.tyron12233:project-model-api") // module types, templates, facets + codecs, file icons
compileOnly("io.github.tyron12233:language-api")      // file types, completion, postfix, compilation contexts
compileOnly("io.github.tyron12233:analysis-api")      // analyzers, diagnostics, quick fixes, intentions
compileOnly("io.github.tyron12233:index-api")         // persisted indexes
compileOnly("io.github.tyron12233:build-api")         // build systems, build plugins, tasks, source generators
compileOnly("io.github.tyron12233:plugin-ui-api")     // tool windows, screens, overlays (see part 4)
```

Take only the ones you use; each brings the ones below it transitively (`plugin-ui-api` brings nothing: it
depends on no other CodeAssist module).

The Compose UI surfaces are reachable, but through a **different, narrower model** than the built-in one in
[section 10](#10-contribute-ui): see part 4 below and [section 10.11](#1011-an-installed-plugins-ui-facet).
What is not reachable from an installed plugin is the internal `ide-ui-api` model itself: editor view modes,
UI host actions and tab decorations, which are built-in-only because they hand a body the whole `IdeBackend`.

**3. Your plugin classes**, compiled against the plugin SPI as `compileOnly`. They implement `Plugin`,
`dev.ide.plugin.ui.UiPlugin`, or both, and they do **not** declare a `PluginManifest`: the TOML above is this
plugin's identity, and the IDE has read it before your class is instantiated. The IDE's classloader is the
parent of your plugin's, so the SPI, the Kotlin stdlib, and the Compose runtime resolve to the IDE's copies.
Bundling your own copy of any of them does nothing: the parent wins, and shipping a mismatched version is how
you get a linkage error reported against your plugin.

The SPI is published, so it is an ordinary dependency:

```kotlin
dependencies {
    compileOnly(platform("io.github.tyron12233:plugin-bom:2.0.0"))
    compileOnly("io.github.tyron12233:plugin-api")
    compileOnly("io.github.tyron12233:platform-core")
}
```

Upgrading a plugin written against `1.x`? See
[Migrating a plugin to SPI 2.0.0](plugin-spi-2.0-migration.md): `PLUGIN_API_VERSION` moved to `3`, so an
older plugin is refused at the gate, and recompiling is usually the whole migration.

Those two modules are GPL-3.0-or-later **with** the Classpath exception, which is what lets your plugin carry
whatever license you choose; the rest of CodeAssist is plain GPL-3.0-or-later. The SPI version is independent
of the IDE's, and `PLUGIN_SPI_VERSION` in `plugin-api` is the value a scaffolded project is generated with.

Compile with the Kotlin version the IDE was built with, or the SPI's metadata is unreadable. Under AGP 9
Kotlin is built into `com.android.application` and the `org.jetbrains.kotlin.android` plugin is rejected, so
the version is set on the build tools instead:

```kotlin
@file:OptIn(
    org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class,
    org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class,
)

kotlin {
    compilerVersion.set("2.4.0")
}
```

with `kotlin.compiler.runViaBuildToolsApi=true` in `gradle.properties`. Without both, the build fails on
`Module was compiled with an incompatible version of Kotlin`.

What to expect at runtime:

- Your plugin loads in the IDE's process, under its UID and its granted permissions. Class loading isolates
  versions, not privileges.
- Your plugin does not run when it is installed. The IDE lists it and asks first, showing the capabilities
  the manifest declares, the signing certificate of the installed package, and a plain statement that a
  plugin is not sandboxed; it loads only once the user allows it. Discovery is not consent, so declare
  `capabilities` accurately: it is what the user reads before deciding.
- Changing your plugin's enabled state takes effect on the IDE's next launch; the manager loads once at
  startup and does not hot-swap.
- Installing a new build of your plugin over the old one does not change the running IDE either, and it is
  the case with no other symptom: the classloader it already has reads the install path from before the
  update, so your plugin goes on behaving exactly as it did. The IDE reports the update on its row in
  **Settings > Plugins** and applies it with the **Restart now** button there. Installing from the IDE's own
  Run row says the same thing in the build console.
- If your entry point throws, your plugin is rolled back and skipped with the reason shown on its row. The
  IDE still starts, and so does every other plugin that does not depend on yours.

Half the trust model is built: the consent gate above is real, and what it shows the user is the
`capabilities` list, the signing certificate, and the fact that a plugin is not sandboxed. What is **not**
built is enforcement: nothing checks that a plugin only does what it declared. Declare `capabilities`
accurately anyway: today it is what the user reads before allowing your plugin, and enforcement is what it is
for.

The design discussion is in [ui-extensibility-and-plugin-api.md](ui-extensibility-and-plugin-api.md), and the
model as built is summarised in [plugin-system.md](plugin-system.md).

---

## Appendix A: extension point index

Every published extension point, its id, the type it carries, and what contributing to it adds.

### Platform

| FQN | Id | Type | Contribute to add |
| --- | --- | --- | --- |
| `dev.ide.platform.SERVICE_EP` | `platform.service` | `ServiceDescriptor<*>` | A scoped service (use `reg.service(...)`) |
| `dev.ide.platform.settings.SETTINGS_PAGE_EP` | `platform.settingsPage` | `SettingsPage` | A Settings category |

### Project model

| FQN | Id | Type | Contribute to add |
| --- | --- | --- | --- |
| `dev.ide.model.ModuleTypeExtensionPoint` | `platform.moduleType` | `ModuleType` | A module type |
| `dev.ide.model.FileIconExtensionPoint` | `platform.fileIcon` | `FileIconProvider` | File-tree icon classification |
| `dev.ide.model.template.ProjectTemplateExtensionPoint` | `platform.projectTemplate` | `ProjectTemplate` | A Create-Project template |
| `dev.ide.model.FACET_CODEC_EP` | `platform.facetCodec` | `FacetCodec<*>` | Persistence for a module facet |
| `dev.ide.model.sync.PROJECT_IMPORTER_EP` | `platform.projectImporter` | `ProjectImporter` | Import of a foreign project layout |
| `dev.ide.model.sync.BUILD_FILE_WRITER_EP` | `platform.buildFileWriter` | `BuildFileWriter` | Writing changes back to a build file |

### Languages, completion, analysis

| FQN | Id | Type | Contribute to add |
| --- | --- | --- | --- |
| `dev.ide.lang.LANGUAGE_BACKEND_EP` | `platform.languageBackend` | `LanguageBackend` | A language backend |
| `dev.ide.lang.FILE_TYPE_EP` | `platform.fileType` | `FileTypeMapping` | Suffix → language routing |
| `dev.ide.lang.completion.COMPLETION_CONTRIBUTOR_EP` | `platform.completionContributor` | `CompletionContribution` | Completion items |
| `dev.ide.lang.completion.COMPLETION_WEIGHER_EP` | `platform.completionWeigher` | `CompletionWeigher` | Completion ranking |
| `dev.ide.lang.postfix.POSTFIX_TEMPLATE_EP` | `platform.postfixTemplate` | `PostfixTemplate` | A postfix template |
| `dev.ide.lang.synthetic.SYNTHETIC_CLASS_EP` | `platform.syntheticClass` | `SyntheticClassProvider` | Generated classes the editor must see (e.g. `R`) |
| `dev.ide.index.INDEX_EP` | `platform.index` | `IndexExtension<*, *>` | A persisted index |
| `dev.ide.analysis.ANALYZER_EP` | `platform.analyzer` | `Analyzer` | An inspection |
| `dev.ide.analysis.DIAGNOSTIC_PROVIDER_EP` | `platform.diagnosticProvider` | `DiagnosticProvider` | A diagnostic source |
| `dev.ide.analysis.QUICK_FIX_PROVIDER_EP` | `platform.quickFixProvider` | `QuickFixProvider` | A fix for a diagnostic |
| `dev.ide.analysis.ACTION_PROVIDER_EP` | `platform.actionProvider` | `ActionProvider` | A caret intention |
| `dev.ide.block.BLOCK_MAPPING_EP` | `platform.blockMapping` | `BlockMapping` | Block-editor projection for a language |
| `dev.ide.lang.kotlin.compile.KOTLIN_COMPILER_PLUGIN_EP` | `platform.kotlinCompilerPlugin` | `KotlinCompilerPlugin` | A Kotlin compiler plugin |
| `dev.ide.lang.kotlin.symbols.KOTLIN_SYNTHETIC_MEMBER_EP` | `platform.kotlinSyntheticMember` | `KotlinSyntheticMemberProvider` | Editor visibility of compiler-generated members |

### Build

Step-by-step guide: [custom-build-plugins.md](custom-build-plugins.md).

| FQN | Id | Type | Contribute to add |
| --- | --- | --- | --- |
| `dev.ide.build.BUILD_SYSTEM_EP` | `platform.buildSystem` | `BuildSystem` | A build system |
| `dev.ide.build.BUILD_PLUGIN_EP` | `platform.buildPlugin` | `BuildPlugin` | Tasks applied to every build graph |
| `dev.ide.build.RUN_TASK_PROVIDER_EP` | `platform.runTaskProvider` | `RunTaskProvider` | Entries in the Run picker |
| `dev.ide.build.SOURCE_GENERATOR_EP` | `platform.sourceGenerator` | `SourceGenerator` | Generated sources before compilation |

### Actions, VCS, Android

| FQN | Id | Type | Contribute to add |
| --- | --- | --- | --- |
| `dev.ide.plugin.action.UI_ACTION_EP` | `platform.uiAction` | `IdeAction` | A toolbar / menu / palette command |
| `dev.ide.plugin.action.ACTION_GROUP_EP` | `platform.actionGroup` | `ActionGroup` | Menu nesting |
| `dev.ide.vcs.VCS_PROVIDER_EP` | `platform.vcsProvider` | `VcsProvider` | Another version-control system |
| `dev.ide.android.support.icons.ICON_REPOSITORY_EP` | `platform.iconRepository` | `IconRepository` | A source for the Icon Manager |

See also [extension-points.md](extension-points.md) for how the built-ins are wired through these.

---

## Appendix B: class index

### Engine SPI: [`:plugin-api`](../plugin-api)

| FQN | File |
| --- | --- |
| `dev.ide.plugin.Plugin` | [Plugin.kt](../plugin-api/src/main/kotlin/dev/ide/plugin/Plugin.kt) |
| `dev.ide.plugin.PluginManifest` | [PluginManifest.kt](../plugin-api/src/main/kotlin/dev/ide/plugin/PluginManifest.kt) |
| `dev.ide.plugin.PluginRegistration` | [PluginRegistration.kt](../plugin-api/src/main/kotlin/dev/ide/plugin/PluginRegistration.kt) |
| `dev.ide.plugin.action.IdeAction` | [IdeAction.kt](../plugin-api/src/main/kotlin/dev/ide/plugin/action/IdeAction.kt) |
| `dev.ide.plugin.action.ActionGroup` | [ActionGroup.kt](../plugin-api/src/main/kotlin/dev/ide/plugin/action/ActionGroup.kt) |
| `dev.ide.plugin.action.ActionPlace` / `ActionPlaces` | [ActionPlace.kt](../plugin-api/src/main/kotlin/dev/ide/plugin/action/ActionPlace.kt), [ActionPlaces.kt](../plugin-api/src/main/kotlin/dev/ide/plugin/action/ActionPlaces.kt) |
| `dev.ide.plugin.action.ActionContext` | [ActionContext.kt](../plugin-api/src/main/kotlin/dev/ide/plugin/action/ActionContext.kt) |
| `dev.ide.plugin.action.ActionResult` / `ActionEffect` | [ActionResult.kt](../plugin-api/src/main/kotlin/dev/ide/plugin/action/ActionResult.kt), [ActionEffect.kt](../plugin-api/src/main/kotlin/dev/ide/plugin/action/ActionEffect.kt) |
| `dev.ide.plugin.action.SimpleAction` / `SimpleGroup` | [Builders.kt](../plugin-api/src/main/kotlin/dev/ide/plugin/action/Builders.kt) |
| `dev.ide.plugin.action.UI_ACTION_EP` / `ACTION_GROUP_EP` | [Actions.kt](../plugin-api/src/main/kotlin/dev/ide/plugin/action/Actions.kt) |

### Engine runtime: [`:plugin-impl`](../plugin-impl)

| FQN | File |
| --- | --- |
| `dev.ide.plugin.impl.PluginManager` | [PluginManager.kt](../plugin-impl/src/main/kotlin/dev/ide/plugin/impl/PluginManager.kt) |
| `dev.ide.plugin.impl.PluginCatalog` | [PluginCatalog.kt](../plugin-impl/src/main/kotlin/dev/ide/plugin/impl/PluginCatalog.kt) |
| `dev.ide.plugin.impl.PluginRegistrationImpl` | [PluginRegistrationImpl.kt](../plugin-impl/src/main/kotlin/dev/ide/plugin/impl/PluginRegistrationImpl.kt) |
| `dev.ide.plugin.impl.ActionManager` | [ActionManager.kt](../plugin-impl/src/main/kotlin/dev/ide/plugin/impl/ActionManager.kt) |

### Substrate: [`:platform-core`](../platform-core)

| FQN | File |
| --- | --- |
| `dev.ide.platform.ExtensionPoint` / `ExtensionRegistry` | [Platform.kt](../platform-core/src/main/kotlin/dev/ide/platform/Platform.kt) |
| `dev.ide.platform.PluginId` / `Disposable` | [Platform.kt](../platform-core/src/main/kotlin/dev/ide/platform/Platform.kt) |
| `dev.ide.platform.MessageBus` / `MessageBusConnection` / `Topic` | [Platform.kt](../platform-core/src/main/kotlin/dev/ide/platform/Platform.kt) |
| `dev.ide.platform.ServiceKey` / `ServiceScope` / `ServiceContainer` / `ServiceScopeLevel` | [Services.kt](../platform-core/src/main/kotlin/dev/ide/platform/Services.kt) |
| `dev.ide.platform.settings.SettingsPage` / `SettingControl` | [Settings.kt](../platform-core/src/main/kotlin/dev/ide/platform/settings/Settings.kt) |
| `dev.ide.platform.log.Logger` / `Log` | [Log.kt](../platform-core/src/main/kotlin/dev/ide/platform/log/Log.kt) |

### Project model: [`:project-model-api`](../project-model-api)

| FQN | File |
| --- | --- |
| `dev.ide.model.ModuleType` / `SourceSetTemplate` / `FacetTemplate` / `ContentRole` | [ProjectModel.kt](../project-model-api/src/main/kotlin/dev/ide/model/ProjectModel.kt) |
| `dev.ide.model.PlatformKind` / `LanguageLevel` / `LibraryKind` / `DependencyScope` | [ProjectModel.kt](../project-model-api/src/main/kotlin/dev/ide/model/ProjectModel.kt) |
| `dev.ide.model.Facet` / `FacetKey` / `FacetContainer` | [ProjectModel.kt](../project-model-api/src/main/kotlin/dev/ide/model/ProjectModel.kt) |
| `dev.ide.model.FacetCodec` / `FacetData` / `FacetCodecRegistry` | [FacetCodec.kt](../project-model-api/src/main/kotlin/dev/ide/model/FacetCodec.kt) |
| `dev.ide.model.ModuleTypeRegistry` / `UnknownModuleType` | [ModuleTypeRegistry.kt](../project-model-api/src/main/kotlin/dev/ide/model/ModuleTypeRegistry.kt) |
| `dev.ide.model.ProjectTemplateRegistry` | [ProjectTemplateRegistry.kt](../project-model-api/src/main/kotlin/dev/ide/model/ProjectTemplateRegistry.kt) |
| `dev.ide.model.FileIconRegistry` / `FileIconProvider` / `IconTarget` | [FileIconRegistry.kt](../project-model-api/src/main/kotlin/dev/ide/model/FileIconRegistry.kt) |
| `dev.ide.model.template.ProjectTemplate` / `ProjectScaffold` / `TemplateParameter` | [ProjectTemplate.kt](../project-model-api/src/main/kotlin/dev/ide/model/template/ProjectTemplate.kt) |
| `dev.ide.model.sync.ProjectImporter` / `ExternalProjectModel` / `BuildFileWriter` | [ProjectSync.kt](../project-model-api/src/main/kotlin/dev/ide/model/sync/ProjectSync.kt) |
| `dev.ide.model.ModuleSources` | [ModuleSources.kt](../project-model-api/src/main/kotlin/dev/ide/model/ModuleSources.kt) |

The four registries are the read side of the model's extension points; they moved here from the unpublished
`:project-model-impl` in SPI `2.0.0`, so a plugin resolves module types, facet codecs, templates and file
icons through the same objects the host does. See
[Support a language the IDE has never heard of](#support-a-language-the-ide-has-never-heard-of).

### Installed-plugin UI SPI: [`:plugin-ui-api`](../plugin-ui-api)

Published, and the only UI surface an installed plugin compiles against. See
[section 10.11](#1011-an-installed-plugins-ui-facet).

| Type | What it is |
| --- | --- |
| `dev.ide.plugin.ui.UiPlugin` | The UI facet an installed plugin implements; named by `uiEntryPoints` |
| `UiRegistration` | What it registers through: `toolWindow`, `screen`, `overlay` |
| `UiHandle` | Removes one contribution |
| `ToolWindow` / `Screen` / `Overlay` | The three contributions, each with a `@Composable` body |
| `ToolWindowAnchor` | `LEFT` / `RIGHT` / `BOTTOM` |
| `UiContext` / `ScreenUiContext` | What a body is handed: active file, project path, `openFile`, `openScreen`, `back` |

### UI SPI: [`:ide-ui-api`](../ide-ui-api)

| FQN | File |
| --- | --- |
| `dev.ide.ui.ext.UiPlugin` / `UiContributionScope` / `UiPluginHost` | [UiPlugin.kt](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/ext/UiPlugin.kt) |
| `dev.ide.ui.ext.ToolWindowContribution` / `ToolWindowAnchor` / `ToolWindowContext` | [ToolWindows.kt](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/ext/ToolWindows.kt) |
| `dev.ide.ui.ext.ScreenContribution` / `ScreenContext` / `ScreenRegistry` | [ToolWindows.kt](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/ext/ToolWindows.kt) |
| `dev.ide.ui.ext.OverlayContribution` / `OverlayContext` | [ToolWindows.kt](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/ext/ToolWindows.kt) |
| `dev.ide.ui.ext.EditorViewModeContribution` / `ViewModeContext` | [ToolWindows.kt](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/ext/ToolWindows.kt) |
| `dev.ide.ui.ext.UiHostAction` / `SimpleUiAction` / `UiActionHost` / `UiDestinations` / `Registration` | [UiActions.kt](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/ext/UiActions.kt) |
| `dev.ide.ui.ext.BuiltInUiPlugin` | [BuiltInUiActions.kt](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/ext/BuiltInUiActions.kt) |
| `dev.ide.ui.backend.IdeBackend` | [IdeBackend.kt](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/backend/IdeBackend.kt) |
| `dev.ide.ui.backend.UiActionPlaces` and the `Ui*` action DTOs | [IdeBackend.kt](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/backend/IdeBackend.kt) |
| `dev.ide.ui.icons.actionIcon` | [ActionIcons.kt](../ide-ui-api/src/commonMain/kotlin/dev/ide/ui/icons/ActionIcons.kt) |

### Host wiring: [`:ide-core`](../ide-core)

| FQN | File |
| --- | --- |
| `dev.ide.core.ApplicationEnvironment` | [ApplicationEnvironment.kt](../ide-core/src/main/kotlin/dev/ide/core/ApplicationEnvironment.kt) |
| `dev.ide.core.BuiltInPlugin` / `BuiltInPlugins` | [BuiltInPlugins.kt](../ide-core/src/main/kotlin/dev/ide/core/BuiltInPlugins.kt) |
| `dev.ide.core.VcsPlugin` / `VcsSettingsPage` | [VcsPlugin.kt](../ide-core/src/main/kotlin/dev/ide/core/VcsPlugin.kt) |
| `dev.ide.core.AgentPlugin` | [AgentPlugin.kt](../ide-core/src/main/kotlin/dev/ide/core/AgentPlugin.kt) |
| `dev.ide.core.event.IdeEventTopics` | [IdeEventTopics.kt](../ide-core/src/main/kotlin/dev/ide/core/event/IdeEventTopics.kt) |
| `dev.ide.core.IdeServicesBackend` | [IdeServicesBackend.kt](../ide-core/src/main/kotlin/dev/ide/core/IdeServicesBackend.kt) |
| `dev.ide.core.ANALYTICS_SERVICE` and the platform-port `ServiceKey`s | [PlatformPorts.kt](../ide-core/src/main/kotlin/dev/ide/core/PlatformPorts.kt) |

### Example plugins

| Plugin | Engine facet | UI facet |
| --- | --- | --- |
| Version Control | [VcsPlugin.kt](../ide-core/src/main/kotlin/dev/ide/core/VcsPlugin.kt) | [VcsUiPlugin.kt](../vcs-ui/src/commonMain/kotlin/dev/ide/vcs/ui/VcsUiPlugin.kt) |
| AI Agent | [AgentPlugin.kt](../ide-core/src/main/kotlin/dev/ide/core/AgentPlugin.kt) | [AgentUiPlugin.kt](../agent-ui/src/commonMain/kotlin/dev/ide/agent/ui/AgentUiPlugin.kt) |
| Block Editor, Kotlin, Java, Android, and the rest | [BuiltInPlugins.kt](../ide-core/src/main/kotlin/dev/ide/core/BuiltInPlugins.kt) | none |

---

## Appendix C: service index

Every service key the IDE registers, what it is for, and who can name it. Resolve one with `getService` /
`getServiceOrNull` from a service factory or from `PluginRegistration.appServices` (APPLICATION scope), or
with `Module.service(key)` / `Workspace.service(key)` from an extension point callback. See
[Getting an instance of a service](#getting-an-instance-of-a-service).

### Published SPI

| Key | Scope | Interface | For |
| --- | --- | --- | --- |
| `dev.ide.model.WORKSPACE_SERVICE` | WORKSPACE | `Workspace` | The bound workspace. The `ServiceScope.workspace()` helper is this lookup |
| `dev.ide.build.BUILD_CONTROL` | WORKSPACE | `BuildControl` | Start or stop the build; compile and run a module's `main` and capture its output |
| `dev.ide.index.SYMBOL_SEARCH` | WORKSPACE | `SymbolSearch` | Symbol and member lookup over the workspace indexes |
| `dev.ide.model.MODULE_SOURCES` | WORKSPACE | `ModuleSources` | A module's source sets and source roots, including adding one |
| `dev.ide.analysis.MODULE_ANALYSIS` | MODULE | `ModuleAnalysis` | The module's `SourceAnalyzer` per language: resolution and diagnostics for code the plugin did not parse |

The keys an installed plugin can name. The four below `WORKSPACE_SERVICE` are **narrowed aliases** of engine
services listed further down: the interface is the promoted slice, declared in the api module whose types it
already speaks, and `IdeCoreServicesPlugin` registers it against the same instance the internal key
resolves. So a plugin gets the live service, and the members it can reach are the ones the IDE committed to.

### Engine services: [`IdeServices.kt`](../ide-core/src/main/kotlin/dev/ide/core/IdeServices.kt)

The open project's decomposed concerns, registered by `IdeCoreServicesPlugin` in
[`BuiltInPlugins.kt`](../ide-core/src/main/kotlin/dev/ide/core/BuiltInPlugins.kt) and resolved from the
workspace container. All are `internal` to `:ide-core`, so naming one directly is built-in-only; the four
marked **SPI** are additionally reachable from the published SPI through the narrowed alias in the table
above.

| Key | Scope | For |
| --- | --- | --- |
| `ENGINE_CONTEXT` | WORKSPACE | The engine's shared-infrastructure surface. What every service below is built from |
| `MODULE_ANALYZERS` | MODULE | This module's source analyzers, one per language, built on first use. **SPI**: `MODULE_ANALYSIS` |
| `BUILD_SERVICE` | WORKSPACE | Build and run orchestration, for the native Java/Kotlin and the Android builds. **SPI**: `BUILD_CONTROL` |
| `MODULE_SERVICE` | WORKSPACE | Module configuration and management, behind the Module Settings editor. **SPI**: `MODULE_SOURCES` |
| `DEPENDENCY_SERVICE` | WORKSPACE | Maven dependencies: add, resolve, conflicts, BOM platforms |
| `PROJECT_SYNC_SERVICE` | WORKSPACE | Re-importing a project whose model comes from a foreign build system |
| `SEARCH_SERVICE` | WORKSPACE | Go-to-symbol and member search over the index, plus find-in-files. **SPI**: `SYMBOL_SEARCH` |
| `REFACTOR_SERVICE` | WORKSPACE | The Java rename refactoring |
| `LANGUAGE_FEATURE_SERVICE` | WORKSPACE | On-demand editor features that delegate to a language backend |
| `KOTLIN_EDITOR_SERVICE` | WORKSPACE | Kotlin-analyzer-backed editor queries |
| `ANDROID_RESOURCE_SERVICE` | WORKSPACE | Android resource navigation and preview |
| `COMPOSE_PREVIEW_SERVICE` | WORKSPACE | The on-device Compose `@Preview` interpreter path |
| `ICON_MANAGER_SERVICE` | WORKSPACE | Browsable icon repositories and a module's existing drawables |
| `SIGNING_SERVICE` | WORKSPACE | The keystore registry and APK signing configuration |
| `BLOCK_SERVICE` | WORKSPACE | The projectional (block) editor's projection of a buffer |
| `ACTION_MANAGER` | WORKSPACE | Resolves and dispatches what is contributed to `UI_ACTION_EP` / `ACTION_GROUP_EP` |
| `APP_SDK_MANAGER` | APPLICATION | One SDK download queue across every project; resolvable with no project open |
| `APP_KEYSTORE_REGISTRY` | APPLICATION | One keystore registry across every project |
| `PROJECT_TEMPLATES` | APPLICATION | The Create-Project template registry, enumerable before a project exists |

### Plugin-owned services

Registered by a built-in plugin rather than by the engine, in the plugin's own module. Public, but the module
is unpublished, so still built-in only.

| Key | Scope | Declared in | For |
| --- | --- | --- | --- |
| `ANDROID_RESOURCE_REPOSITORY` | WORKSPACE | `:android-support` | The shared, buffer-aware merged `ResourceRepository` cache for a workspace. The synthetic `R` resolves it through the `Workspace` it is handed, and parses directly when a host publishes none. A worked example of a plugin publishing a capability other code resolves by key |

### Platform ports: [`PlatformPorts.kt`](../ide-core/src/main/kotlin/dev/ide/core/PlatformPorts.kt)

APPLICATION-scoped host capabilities the engine needs but does not own. The launcher registers whichever it
supplies; an absent one resolves to null, so **`getServiceOrNull` is always the call here** and the consumer
falls back to an in-process default. Absent is the normal state on desktop and in tests.

| Key | Visibility | For |
| --- | --- | --- |
| `PROGRAM_INTERPRETER` | internal | Runs a compiled program through the bytecode VM |
| `APK_INSTALLER` | internal | Installs a built APK on the device |
| `APP_LOG_CHANNEL` | internal | Forwards the running app's log back into the IDE |
| `CUSTOM_VIEW_RUNTIME` | internal | Renders a project's own custom `View` for layout preview |
| `REAL_VIEW_RUNTIME` | internal | The real-view preview inspector |
| `KOTLIN_PLUGIN_LOADER` | internal | Loads dexed Kotlin compiler plugins |
| `KOTLIN_COMPILER_BACKEND` | internal | The on-device kotlinc, as a persistent forked VM |
| `ANDROID_DEVICE_TOOLS` | internal | aapt2, R8/D8 and the rest of the Android toolchain |
| `ANALYTICS_SERVICE` | public | Opt-in usage analytics; absent resolves to a no-op service |
| `STORE_CATALOG_SOURCE` | public | The remote Projects Store catalog |
| `STORE_ACCOUNT_SERVICE` | public | The store's OAuth sign-in |
| `STORE_SUBMISSION_SERVICE` | public | Store submissions, which need a signed-in session |
| `STORE_REVIEW_SERVICE` | public | Store ratings and reviews |
| `NOTIFICATION_PRESENTER` | public | Raises an OS-level notification |

The public keys here are public so a launcher can register them from outside `:ide-core`, not because they
are part of the plugin SPI. `:ide-core` is unpublished, so an installed plugin cannot name them either.
