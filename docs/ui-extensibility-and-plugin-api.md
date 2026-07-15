# UI extensibility and the plugin API

> **Status — the unified internal plugin system is delivered.** The IDE now has one plugin system: a `Plugin`
> SPI + `PluginManager`, with the built-ins as its first consumers. See [`plugin-system.md`](plugin-system.md)
> for what ships. The UI-extensibility model this document specifies is delivered as `UiPlugin` /
> `UiContributionScope` in `:ide-ui-api` (tool windows, screens, view modes, tree icons, and data-driven
> actions all plug in through the one model). What remains **future** is the *external tier* this document also
> designs: dex-loaded, separately-built, trust-gated third-party plugins (on-disk manifest discovery,
> `DexClassLoader` isolation, a permission model). Read the sections below as the design for that external tier;
> read `plugin-system.md` for the internal model in use today.

This document specifies how the IDE's **UI** becomes extensible the same way its **engine** already is: through
the IntelliJ-style extension-point registry in `platform-core`. Today the engine is a plugin platform (language
backends, analyzers, indexes, templates, settings pages all plug in), but the UI is almost entirely hardcoded:
screens are a fixed `enum` + `when`, top-bar buttons are a fixed list, the "More" menu and file-tree context
menu are baked into composables, and there is no general action system. This is the gap this design closes.

It also introduces a real **plugin** unit: a packaged, versioned, dex-loadable artifact that contributes to the
UI (and the engine) through the same EPs the bundled features use.

## Decisions this design encodes

These were chosen up front and constrain everything below:

1. **Boundary: hybrid.** Data-driven contributions (actions, menus, toolbars, command palette) cross the
   `ide-ui` ↔ `IdeBackend` line as DTOs, exactly like `settingsPages()` does today, so the UI stays a thin
   renderer with no framework deps. A small **Compose registry** escape hatch carries the few contributions
   that must render custom bodies: tool-window contents, custom screens, and custom editor view modes.
2. **Action model: lean.** `IdeAction { id, text, icon, isEnabled(ctx), perform(ctx) }` + `ActionGroup` +
   named **places** (`mainToolbar`, `moreMenu`, `fileContext`, `editorTab`, `commandPalette`, ...). No
   `DataContext`/`Presentation`/`update()` machinery and no keymap in the first cut; both can layer on later.
3. **Plugin target: third-party, dex-loaded, from day one.** Contracts are designed for an untrusted,
   separately-built plugin loaded through a `DexClassLoader` on ART (a `URLClassLoader` on desktop), reusing
   the `KotlinPluginLoader` machinery that already exists. This forces data-friendly contracts, a manifest,
   classloader isolation, and a permission/trust model now rather than retrofitting them.
4. **First surfaces:** actions (toolbar + menus), tool windows & panels, and screens & view modes. (The
   command palette comes almost for free once actions exist, so it is folded in opportunistically.)

## Where we are starting from

The relevant existing machinery, all reused rather than replaced:

- `platform-core` `ExtensionPoint<T>(id)` + `ExtensionRegistry.register(ep, impl, pluginId): Disposable` /
  `extensions(ep)`. Typed, ordered, plugin-attributed, dynamically unregisterable (so plugin unload works),
  plus `MessageBus`, `PluginId`, and the scoped-service container (`SERVICE_EP`).
- The **data-driven UI template**: `SETTINGS_PAGE_EP` + `IdeBackend.settingsPages()`/`setSetting()`. A plugin
  registers a `SettingsPage`; the UI renders typed controls from DTOs and never sees the plugin object. Its doc
  already states "gate third-party pages behind the plugin trust model." This is the exact shape the action
  and menu APIs follow.
- The **dex-plugin loader**: `KotlinPluginLoader` (interface) with `DefaultKotlinPluginLoader`
  (`URLClassLoader`, desktop) and `ArtKotlinPluginLoader` (D8-in-process + `DexClassLoader`, ART). The loader's
  classloader parent is the app classloader, so a plugin's references to the Kotlin compiler, Compose runtime,
  and our own API modules resolve through parent delegation. `KotlinPluginLoading.RUNTIME_REGISTRAR` is already
  the "load a separately-built plugin at runtime and register it programmatically" path.
- The **only** already-extensible UI piece: `TreeIcons`, a live `mutableStateMapOf` registry. The Compose
  registries below follow its pattern.

## Module layout

Two new API modules and one impl module, slotting into the existing acyclic graph. Naming and dependency
direction follow the repo convention (deps point downward only).

```
platform-core
  └─ plugin-api        (pure Kotlin) lean action model, plugin descriptor/manifest,
       │               Plugin SPI, capability/permission model, the data-driven UI EPs
       ├─ plugin-impl  (pure Kotlin) ActionManager, plugin lifecycle, permission broker;
       │               the dex/URL loader split lives in the launchers (like ArtKotlinPluginLoader)
       └─ ide-ui-api   (Compose Multiplatform commonMain) the Compose-bearing contracts
                       + live registries (ToolWindowRegistry / ScreenRegistry / ViewModeRegistry)

ide-ui      → ide-ui-api      (gains the registries; still no engine/framework deps beyond the API)
ide-core    → plugin-impl, ide-ui-api   (wires built-ins onto the new EPs; exposes them over IdeBackend)
ide-android → plugin-impl     (ArtPluginLoader: D8 + DexClassLoader, mirrors ArtKotlinPluginLoader)
ide-desktop → plugin-impl     (DefaultPluginLoader: URLClassLoader)
```

Why the split into two API modules: the lean action and menu contracts are pure data and pure Kotlin, so they
live in `plugin-api` and use `platform-core` EPs directly. A tool-window body, a custom screen, and a custom
view mode are `@Composable` values, which require the Compose runtime on the declaring module's classpath, so
those contracts and their registries must live in a Compose-aware module (`ide-ui-api`) that `ide-ui` depends
on. The hybrid boundary therefore maps cleanly onto two homes:

- **Data-driven** → `plugin-api` contracts, `platform-core` EPs, `IdeBackend` DTOs.
- **Compose-bearing** → `ide-ui-api` contracts and live registries.

## The lean action model (`plugin-api`)

```kotlin
package dev.ide.plugin.action

/** A place an action can appear: a toolbar, a menu, a context menu, the palette. Open set (string-backed). */
@JvmInline value class ActionPlace(val id: String)

object ActionPlaces {
    val MAIN_TOOLBAR     = ActionPlace("mainToolbar")
    val MAIN_OVERFLOW    = ActionPlace("mainToolbar.overflow")  // compact/mobile collapse target
    val MORE_MENU        = ActionPlace("moreMenu")
    val FILE_CONTEXT     = ActionPlace("fileContext")           // file-tree long-press / right-click
    val EDITOR_TAB       = ActionPlace("editorTab")
    val COMMAND_PALETTE  = ActionPlace("commandPalette")
}

/**
 * A read-only snapshot of what the action acts on, passed to isEnabled/perform. Plugin action code runs
 * host-side (engine side), so the context carries a narrowed engine handle, not the Compose tree.
 */
interface ActionContext {
    val place: ActionPlace
    val projectRoot: String
    val activeFilePath: String?
    val selectionStart: Int?
    val selectionEnd: Int?
    val contextPath: String?            // e.g. the file-tree node the menu opened on
    val services: PluginContext         // narrowed, permission-gated host operations (see trust model)
}

/** One action. Stable [id] is the registry key and the round-trip handle across the UI boundary. */
interface IdeAction {
    val id: String
    val text: String
    val iconId: String? get() = null         // resolved by the UI icon registry, like TreeNode.iconId
    val places: Set<ActionPlace>             // where it may appear
    val order: Int get() = 1000              // built-ins occupy 0..99; plugins default after them
    fun isEnabled(ctx: ActionContext): Boolean = true
    fun isVisible(ctx: ActionContext): Boolean = true
    suspend fun perform(ctx: ActionContext)
}

/** Nesting + separators for menus and the overflow. A group is itself addressable by place. */
interface ActionGroup {
    val id: String
    val text: String
    val iconId: String? get() = null
    val places: Set<ActionPlace>
    val order: Int get() = 1000
    /** Child action ids and group ids, in display order; "---" denotes a separator. */
    fun children(ctx: ActionContext): List<String>
}

val UI_ACTION_EP   = ExtensionPoint<IdeAction>("platform.uiAction")
val ACTION_GROUP_EP = ExtensionPoint<ActionGroup>("platform.actionGroup")
```

`ActionManager` (in `plugin-impl`) resolves a place into a flat, ordered, visibility-filtered list (expanding
groups), evaluates `isEnabled` against a supplied context, and dispatches `perform` on the engine dispatcher.
It is the single consumer of the two EPs, mirroring how `AnalysisEngine` consumes the analyzer/fix/action EPs.

### Crossing the UI boundary (data-driven)

`IdeBackend` gains a small, generic surface (DTOs only, no plugin types leak to `ide-ui`):

```kotlin
// ide-ui/.../backend/IdeBackend.kt
fun actionsFor(place: String, ctx: UiActionContext): List<UiAction>          // resolved + enabled-evaluated
fun menuFor(place: String, ctx: UiActionContext): UiMenuGroup                // nested, for context menus
suspend fun invokeAction(actionId: String, ctx: UiActionContext)            // round-trip by id

data class UiAction(val id: String, val text: String, val iconId: String?, val enabled: Boolean)
data class UiMenuGroup(val items: List<UiMenuItem>)                          // action | submenu | separator
data class UiActionContext(                                                   // serializable snapshot
    val place: String, val activeFilePath: String?,
    val selectionStart: Int?, val selectionEnd: Int?, val contextPath: String?,
)
```

`IdeServicesBackend` builds the full `ActionContext` from the snapshot, calls `ActionManager`, and maps results
to DTOs. This is identical in spirit to `actionsAt`/`applyAction` (the existing editor lightbulb round-trip),
generalized from "editor caret" to "any place."

## The Compose-bearing contracts (`ide-ui-api`)

These three live in a Compose module because their bodies are `@Composable`. Each has a live registry modeled
on `TreeIcons` (a `mutableStateMapOf`, so registration triggers recomposition), populated at plugin-load time.

```kotlin
package dev.ide.ui.api

/** A dockable panel. Anchor decides which existing region hosts it. */
enum class ToolWindowAnchor { LEFT, RIGHT, BOTTOM }   // LEFT = side rail / nav region, BOTTOM = console region

interface ToolWindow {
    val id: String
    val title: String
    val iconId: String
    val anchor: ToolWindowAnchor
    val order: Int get() = 1000
}

object ToolWindowRegistry {
    fun register(tw: ToolWindow, content: @Composable (ToolWindowContext) -> Unit): Disposable
    fun forAnchor(anchor: ToolWindowAnchor): List<Registered>   // observable
}

/** A top-level screen reachable from navigation. Core screens (editor, projects, settings) register too. */
interface IdeScreen { val id: String; val title: String; val iconId: String }
object ScreenRegistry {
    fun register(screen: IdeScreen, content: @Composable (ScreenContext) -> Unit): Disposable
}

/** An editor view mode beyond Text/Blocks/Preview/Split. */
interface EditorViewModeContribution {
    val id: String; val label: String
    fun appliesTo(filePath: String): Boolean
}
object ViewModeRegistry {
    fun register(mode: EditorViewModeContribution, content: @Composable (ViewModeContext) -> Unit): Disposable
}
```

`*Context` objects hand the body what it needs (the `IdeBackend` handle, the active file, the selection) without
the body reaching into app internals. A plugin that ships Compose content compiles against `ide-ui-api` plus a
**pinned Compose runtime**; at load its classes resolve Compose and `ide-ui-api` through the app classloader
parent (the same mechanism the on-device custom-view Compose bridge already uses).

## Plugins: descriptor, packaging, loading

A plugin is a directory or archive under `<storageRoot>/plugins/<id>/` containing a manifest and a code
artifact. The descriptor is intentionally close to the existing `KotlinCompilerPlugin` shape and the project
template's data-driven parameters.

```json5
// plugin.json
{
  "id": "dev.example.logcat",
  "name": "Logcat",
  "version": "1.0.0",
  "apiVersion": "1",                  // host plugin-API/ABI compatibility floor
  "entryPoints": [                    // classes implementing the Plugin SPI
    "dev.example.logcat.LogcatPlugin"
  ],
  "capabilities": ["ui.toolWindow", "fs.read"],   // declared, prompted, enforced (see trust model)
  "minHostVersion": "3.1.0"
}
```

```kotlin
// plugin-api: the entry point the loader instantiates and calls once.
interface Plugin {
    fun register(ctx: PluginRegistration)
    fun unregister() {}                // optional; Disposables from register() are the default unload path
}

interface PluginRegistration {
    val pluginId: PluginId
    val extensions: ExtensionRegistry              // for data-driven EPs (UI_ACTION_EP, ...)
    val ui: UiContributionScope                    // Compose registries, gated by the ui.* capability
    val capabilities: Set<String>
}
```

**Loading reuses the proven path.** A `PluginLoader` interface with two implementations:

- `DefaultPluginLoader` (desktop): `URLClassLoader` over the plugin's jars, parent = app classloader. Mirrors
  `DefaultKotlinPluginLoader`.
- `ArtPluginLoader` (Android): D8-in-process dex of the plugin jars (content-addressed cache), then a
  `DexClassLoader`, parent = app classloader. A near-copy of `ArtKotlinPluginLoader`; that class already proves
  every step (dex, package `classes*.dex`, load, parent-delegate to bundled Compose/Kotlin).

The loader discovers descriptors, checks `apiVersion`/`minHostVersion`, builds the isolated classloader,
instantiates each entry point, and calls `register`. Every contribution returns a `Disposable`; unload disposes
the lot and the registry drops the plugin's `PluginId` in bulk (already supported by `unregisterAll`). A
**Plugins** settings page (dogfooding `SETTINGS_PAGE_EP`) lists installed plugins with enable / disable /
uninstall and surfaces declared capabilities.

## Trust and permissions

Third-party code runs in two distinct risk tiers, and the model treats them differently:

- **Tier 1, data-driven (actions, menus, palette, settings pages).** Plugin code runs host-side and only
  touches the engine through `PluginContext`, a narrowed facade. Capabilities declared in the manifest
  (`fs.read`, `fs.write`, `net`, `process`, `model.write`) are enforced by a `PluginPermissionBroker` modeled
  on the run sandbox's `PermissionBroker`: an undeclared or undecided category blocks and prompts the user
  (Allow once / for this session / always / Deny), reusing the existing `PermissionDialog` flow. This tier is
  cleanly gateable.
- **Tier 2, Compose-bearing (tool windows, screens, view modes).** The plugin renders arbitrary UI in-process;
  in-process Compose cannot be meaningfully sandboxed. This tier is therefore higher-trust by nature: it
  requires the explicit `ui.*` capability, an install-time consent ("this plugin can draw custom UI and runs
  with app privileges"), and is the right place to start first-party / reviewed-only before opening it widely.

The manifest's declared capabilities drive both the install consent and the runtime broker. This mirrors the
established posture: best-effort mediation over a curated surface, honest about not being a hardened sandbox.

## How each hardcoded surface migrates

The migration is mechanical and non-breaking: every existing button and menu item becomes a **built-in**
`IdeAction`/`ToolWindow`/`IdeScreen` registered under `PluginId("ide-core")`, and the composable is rewritten to
render from the registry. No UX changes; the registry simply becomes the source of truth, after which a plugin's
contributions appear alongside the built-ins with zero further host edits.

| Surface | File today | Becomes |
|---|---|---|
| Top-bar buttons (Save, Undo, Find, Palette, Inlay, Console, Preview, Reindex, Run) | `EditorChrome.kt` | built-in `IdeAction`s in `MAIN_TOOLBAR` / `MAIN_OVERFLOW`; bar renders `actionsFor(...)` |
| "More" menu (Settings, Modules, SDK, Re-index, Logs, Theme, Close, Analytics) | `EditorSheets.kt` | `IdeAction`s in `MORE_MENU` |
| File-tree context menu (new/rename/move/copy/delete, module ops) | `FileNavigator.kt` | `IdeAction`s + `ActionGroup`s in `FILE_CONTEXT`; context carries the node path |
| Command palette commands | `CommandPalette.kt` | aggregates `actionsFor(COMMAND_PALETTE)` across places; falls out free |
| Side-rail destinations (`RailDestination` enum) | `SideRail.kt` | `ToolWindowRegistry` LEFT entries; Files/Search registered built-in |
| Build-console tabs (`BuildTab` enum) | `BuildConsole.kt` | `ToolWindowRegistry` BOTTOM entries; Problems/Log/Steps built-in |
| Editor view modes (`EditorViewMode` enum) | `EditorBreadcrumbBar.kt` | `ViewModeRegistry`; Text/Blocks/Preview/Split built-in |
| Top-level screens (`Screen` enum) | `CodeAssistApp.kt` | `ScreenRegistry`; core screens built-in; routing reads the registry |

## Phased plan (each phase ships against the prior phase's interfaces)

**Phase A: action backbone + plugin seams.** Create `plugin-api` (lean action model, EPs) and `plugin-impl`
(`ActionManager`). Add the `actionsFor`/`menuFor`/`invokeAction` surface to `IdeBackend`. Register engine
commands as built-in `IdeAction`s and render the registry from the UI surfaces. *Exit test:* a unit test
registers actions/groups and asserts they resolve into the right place (ordered, visibility-filtered, groups
expanded) and that `invoke` runs them.

*Delivered (this iteration):*
- `plugin-api`: `IdeAction` / `ActionGroup` / `ActionPlace` (+ `ActionPlaces`) / `ActionContext` /
  `ActionResult` + `ActionEffect`, `UI_ACTION_EP` / `ACTION_GROUP_EP`, and `SimpleAction`/`SimpleGroup` helpers.
- `plugin-impl`: `ActionManager` (place resolution, ordering, visibility filter, group expansion with a
  cycle guard, dispatch) + `ResolvedMenuItem`. Eight `ActionManagerTest` cases, green.
- `IdeBackend`: `actionsFor` / `menuFor` / `invokeAction` + the neutral DTOs (`UiActionItem`, `UiMenuGroup`,
  `UiMenuNode`, `UiActionContext`, `UiActionResult`, `UiActionEffect`, `UiActionPlaces`). Implemented in
  `IdeServicesBackend` over `IdeServices.actionManager`.
- Built-in engine commands (`BuiltInActions`: Run / Stop / Re-index) registered on the EP, surfaced in the
  command palette through the registry (the palette's hardcoded engine commands are now registry-sourced).
- Three live, additive plugin seams in the UI: a `mainToolbar` slot in the top bar, a `fileContext` section
  in the file-tree menu, and `commandPalette` commands. Each renders from the registry and dispatches through
  a shared `dispatchAction` helper that applies the returned effects. A string-icon resolver (`actionIcon`)
  maps an action's icon id to a glyph.

*Deferred to Phase B (a deliberate scoping decision):* the navigation-heavy native surfaces (the "More" sheet
and the stateful top-bar buttons: save-when-modified, toggles, the Run split-button) stay native for now.
They change app/UI state rather than running an engine op, which the data-driven model handles awkwardly; they
move to the action model once the **UI-side action registry** lands in `ide-ui-api` (Phase B), where an action
can close over UI navigation/state. The seams above are additive (empty until a plugin or that migration fills
them), so nothing regressed.

**Phase B: the UI-side action registry + Compose registries.** Add the UI-side action registry (for actions
that drive the running UI: navigate, toggle theme), then the Compose-bearing tool-window / screen / view-mode
registries; migrate side-rail destinations, build-console tabs, editor view modes, top-level screens, and the
nav-heavy native action surfaces (the "More" menu, the stateful top-bar buttons) onto them. *Exit test:* a
test registers a tool window (LEFT and BOTTOM), a view mode, and a screen, and they render; built-ins unchanged.

*Delivered (installment 1):* The **UI-side action registry** in `dev.ide.ui.ext` (`UiActionHost`,
`UiHostAction` + `SimpleUiAction`, `UiActionRegistry`, `UiDestinations`, `Registration`) — the half of the
hybrid the data-driven model can't express, because these actions drive the running UI (navigate to a screen,
toggle the theme) rather than the engine. Modeled on `TreeIcons` (process-global, Compose-observable). The
**"More" menu** is migrated onto it: its rows are built-in `UiHostAction`s (`BuiltInUiActions`), bridged to the
app's navigation/theme callbacks by a `UiActionHost` the sheet builds; adding a row is now a registration, not
an edit. This lives in `ide-ui` for now and moves into a standalone `ide-ui-api` module in Phase C, when a
plugin needs to compile against it (the contract is the same one that module will expose).

*Delivered (installment 2):* the **Compose-bearing registries** in `dev.ide.ui.ext` — `ToolWindowRegistry`
(+ `ToolWindowAnchor`/`ToolWindowContext`/`ToolWindowContribution`), `ScreenRegistry`, `ViewModeRegistry` —
the half of the hybrid for contributions that render their own UI (a `@Composable` body can't cross as data),
modeled on `TreeIcons`. **Tool windows are wired end-to-end into the build console**: a plugin BOTTOM tool
window appears as an extra tab after the built-in Problems/Log/Steps (verified by snapshot — a "Logcat" tab
renders), additive so the built-ins are untouched. The **command palette is unified**: its UI-navigation
commands (Open Settings / Manage dependencies / SDK Manager / Toggle theme) now resolve from
`UiActionRegistry` (a `UiActionHost` the palette builds bridges them), merged with the engine commands from
`IdeBackend.actionsFor` and the index-backed search — the hardcoded palette entries are gone.

*Remaining:* wiring the `ScreenRegistry` and `ViewModeRegistry` into the app shell / editor (the SPIs exist),
wiring `ToolWindowRegistry` LEFT into the side rail, and moving the stateful top-bar buttons onto the
registry (these need a richer presentation contract for toggle/enabled state). Each is an app-shell or
core-editor change best done as its own visually-verified pass.

**Phase C: the plugin unit.** Define the descriptor + `Plugin` SPI; build `DefaultPluginLoader` (desktop) and
`ArtPluginLoader` (device, cloning `ArtKotlinPluginLoader`); add lifecycle (load/enable/disable/uninstall), the
`PluginPermissionBroker` + install consent, and the Plugins settings page. Ship one sample plugin that
contributes a toolbar action (Tier 1) and a Logcat tool window (Tier 2). *Exit test:* the sample is discovered,
loaded, contributes both, can be disabled (contributions vanish) and uninstalled; the dex path is verified on
device.

**Phase D (later, behind these contracts):** keymap + user-rebindable shortcuts on actions; `MessageBus`
plugin-facing events (file opened, build started/finished) so plugins react; palette categories; a plugin
marketplace/install-from-URL flow.

## IdeBackend decomposition

`IdeBackend` was a ~100-member fat port. It is now a thin aggregator of 13 concern-segmented services, each
owning its own `StateFlow`s, reached as `backend.editor.complete(...)`, `backend.build.runBuild()`, etc.:
`files`, `editor`, `blocks`, `preview`, `search`, `build`, `deps`, `modules`, `projects`, `sdk`, `settings`,
`actions`, `diagnostics` (defined in `ide-ui/.../backend/BackendServices.kt`). Only `project` (the active-project
identity) stays on the root. Method defaults preserve the historical "unsupported" behaviour, so a partial
backend or a test fake (`StubBackend`) overrides only what it implements.

This is the basis for Phase C plugin scoping: a plugin is handed exactly the services it is permitted, not the
whole port.

*Stage 1 (done, verified):* the service interfaces + aggregator; all ~215 call sites migrated; the test fakes
collapsed onto a shared `StubBackend`. `IdeServicesBackend` implements every service with `get() = this` (its
method bodies untouched). Both launchers compile; `ide-ui:desktopTest` + `plugin-impl:test` green.

*Stage 2 (done):* `IdeServicesBackend` is split into 13 per-service impl classes (`dev.ide.core.backend.*`)
over a shared `BackendContext` that the aggregator implements (so the genuinely-shared infra — the engine
handle, the serialized `engineDispatcher`/`EngineScheduler` lanes, the epoch-keyed flow factory, the
error/analytics surface, the engine `swapEngine`, `recordPerf`/`bumpFileSystemEpoch` — stays in one place).
The aggregator dropped from ~1800 to ~500 lines: it now holds only the `BackendContext` impl, the 13 service
properties, the root `project`, the `LayoutPreviewBackend` methods, and lifecycle (init watchers,
`installCrashReporting`, `close`). Internal-only — no interface or call-site change. The lifecycle-coupled
services (`DiagnosticsBackend`'s error/analytics surface, `ProjectBackend`'s engine swap + epochs) delegate to
`BackendContext` rather than owning that state. Verified: both launchers compile; the backend-construction
tests + `ide-ui:desktopTest` + `plugin-impl:test` pass. (Note: an init-order bug — service props declared
before `engineScope` — caused a runtime NPE in `engineFlow`, fixed by ordering the service-property block
after the shared-state fields.)

## Open questions deferred to implementation

- Whether `PluginContext` (the narrowed Tier-1 facade) is a hand-curated subset of `IdeBackend`/`IdeServices`
  or a generated narrowing; start hand-curated.
- ABI/versioning policy for `ide-ui-api` (Compose-bearing plugins are the strictest consumers); start with a
  single integer `apiVersion` floor and a compatibility check at load.
- Whether action `perform` ever needs to run UI-thread work; the lean model keeps `perform` engine-side and
  routes any UI effect back through `IdeBackend` state, which avoids the question for now.
