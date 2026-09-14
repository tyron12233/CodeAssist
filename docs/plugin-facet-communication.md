# Talking between a plugin's two facets

A plugin that contributes both engine behaviour and Compose UI is two classes: an **engine facet**
(`dev.ide.plugin.Plugin`) and a **UI facet** (`dev.ide.plugin.ui.UiPlugin`). This document is about the line
between them: what channels exist, which one to reach for, what runs on which thread, and the mistakes that
only show up after the plugin is installed on a device.

It assumes you have read [writing-plugins.md](writing-plugins.md) sections
[2.2](writing-plugins.md#22-the-two-facets) and [10](writing-plugins.md#10-contribute-ui), which cover what
each facet *is* and what each can contribute. This one covers what happens between them.

- [1. Why there are two facets at all](#1-why-there-are-two-facets-at-all)
- [2. The channel is decided by visibility, not taste](#2-the-channel-is-decided-by-visibility-not-taste)
- [3. The four channels](#3-the-four-channels)
- [4. Where the shared state goes](#4-where-the-shared-state-goes)
- [5. Which way things flow](#5-which-way-things-flow)
- [6. Threading](#6-threading)
- [7. Lifetime and teardown](#7-lifetime-and-teardown)
- [8. Built-ins: the same problem, a different channel](#8-built-ins-the-same-problem-a-different-channel)
- [9. Anti-patterns](#9-anti-patterns)
- [10. Testing the seam](#10-testing-the-seam)
- [11. Checklist](#11-checklist)

---

## 1. Why there are two facets at all

The split is not an isolation boundary. It exists because a `@Composable` body cannot live in a module that
knows nothing about Compose, and the engine SPI deliberately knows nothing about Compose: it is consumed by
the headless launcher, the build CLI and the test suites, none of which have a UI.

So the two facets are two *types*, and that is the whole of it. In an installed plugin they are:

- in the same APK,
- loaded by the same classloader,
- running in the same process, under the same UID,
- instantiated within milliseconds of each other at startup.

Nothing is serialized, nothing is marshalled, nothing crosses a boundary as data. A call from one to the
other is an ordinary Kotlin call, and a shared `object` is one instance to both.

The IDE leans on this. `UiContext`, everything a contributed body is handed, has five members:

```kotlin
interface UiContext {
    val projectPath: String?
    val activeFilePath: String?
    fun openFile(path: String, offset: Int = 0)
    fun openScreen(id: String)
    fun <T : Any> service(key: ServiceKey<T>): T?
}
```

That is not an oversight. Publishing the internal model would freeze `IdeBackend`, every concern service on
it and every DTO in it as plugin API, and that is the layer of the IDE that changes most. A UI facet does not
need it: the *engine* facet has the whole engine SPI (the virtual file system, the project model, indexes,
analysis, the interpreter, the message bus) and is a plain function call away. `UiContext` carries only what
is a property of the running UI, which no amount of engine access can answer.

**The rule that follows:** when your panel needs something, the question is never "how do I get that out of
the host". It is "how do I get that from my own engine facet".

---

## 2. The channel is decided by visibility, not taste

There is one fork in this whole document, and it is not a preference. It is whether your two facets can see
each other's classes at compile time.

| | Installed plugin (its own APK) | Built-in (in this repo) |
| --- | --- | --- |
| Engine facet lives in | your one Android module | `ide-core` (`kotlin("jvm")`) |
| UI facet lives in | the same module | a KMP module's `commonMain` (`vcs-ui`, `agent-ui`) |
| Can they share a class? | **Yes.** One classloader, one module. | **No.** `commonMain` cannot see a JVM-only artifact. |
| So the channel is | a service via `UiContext.service`, a direct call, or a shared object | a neutral service interface on `IdeBackend` |
| Worked example | [`samples/hello-plugin`](../samples/hello-plugin) | [the Git plugin](writing-plugins.md#11-case-study-the-git-plugin) |

Sections 3 to 7 are the installed case, which is the one most plugin authors are in and the one with the
cheapest channels. Section 8 is the built-in case.

If you are writing an installed plugin and find yourself building a DTO layer between your own two facets,
stop: you are paying the built-in tier's tax for a constraint you do not have.

---

## 3. The four channels

Ranked by how much machinery they cost. Reach for the first one that answers the question.

### 3.1 A direct call

The default, and most plugins need nothing else. The UI facet calls a function the engine facet owns.

```kotlin
// The panel, in the UI facet.
Button(onClick = { GreetingEngine.greet("the panel") }) { Text("Say hello") }
```

No registration, no indirection, no lifecycle. If the call is cheap and synchronous, this is the answer.

### 3.2 A shared holder, for state the panel watches

When the panel has to *show* something the engine facet owns, the call has to be inverted: the engine writes,
and the panel recomposes. Put the state in an object both facets can reach.

Two forms, and the choice matters:

| | Compose snapshot state | `StateFlow` |
| --- | --- | --- |
| Declared as | `var x by mutableStateOf(...)` | `val x = MutableStateFlow(...)` |
| Panel reads it with | a plain read; the read subscribes | `.collectAsState()` |
| Writes from a background thread | safe, and the panel recomposes | safe |
| Costs the holder a dependency on | `androidx.compose.runtime` | `kotlinx-coroutines-core` |
| Reach for it when | the holder is only ever read by this plugin's UI | the engine half must stay Compose-free (testable headless, or shared with a non-UI consumer) |

Snapshot state is the lighter of the two and the one the sample uses:

```kotlin
/**
 * State shared by this plugin's two facets. They are named by the same packaged manifest, so the IDE
 * instantiates both off this APK on ONE classloader: this object is one instance to both of them.
 */
object GreetingState {
    var greetings: Int by mutableIntStateOf(0)
        private set

    var lastSource: String? by mutableStateOf(null)
        private set

    fun greeted(source: String) {
        greetings++
        lastSource = source
    }
}
```

The engine facet writes to it from an action; the panel reads `GreetingState.lastSource` and recomposes. There
is no listener to register and nothing to poll. Note the `private set`: the holder owns the transition, both
facets call `greeted(...)`, and neither can put the two fields out of step.

`StateFlow` is the right call when the engine half is worth keeping Compose-free. Both versions of the
coordinate are available to a plugin through [`plugin-bom`](writing-plugins.md#15-ship-your-plugin-as-its-own-app),
pinned to what the IDE bundles, because a plugin's `@Composable` code and its `suspend` code both bind to the
host's copies at runtime.

### 3.3 The message bus

The bus is on the engine side only: `PluginRegistration.messageBus` to publish, `PluginRegistration.busConnection()`
to subscribe. A UI facet has no handle to it, by design.

So the bus is not the channel between *your* two facets. It is the channel:

- **into** your engine facet from the IDE (a build finished, diagnostics landed, the project changed), and
- **between** two different plugins, which genuinely cannot see each other's classes: each installed plugin
  gets its own classloader over its own APK.

The pattern that matters here is the join of the two: your engine facet subscribes, and pushes into the
holder the panel is already watching.

```kotlin
override fun register(reg: PluginRegistration) {
    // The listener has to be written as an explicit constructor rather than a bare lambda: subscribe() is
    // generic, and Kotlin does not SAM-convert a type-variable parameter.
    reg.busConnection().subscribe(
        BuildTopics.BUILD,
        BuildEventListener { event ->
            when (event) {
                is BuildEvent.Started -> BuildState.running(event.module)
                is BuildEvent.Finished -> BuildState.finished(event.module, event.succeeded, event.failureKind)
            }
        },
    )
}
```

The panel now follows the IDE's builds without knowing the bus exists. The connection from `busConnection()`
is tracked for unload, so the subscription is removed when the plugin unloads; a raw `messageBus.connect()`
is **not**, and its subscriptions outlive the plugin unless you dispose it yourself via `reg.onDispose`.

The IDE's lifecycle topics each live in the api module that owns their payload:

| Topic | Module | What it carries |
| --- | --- | --- |
| `dev.ide.plugin.editor.EditorTopics.EDITOR` | `plugin-ui-api` | files opening, closing, focus, the caret |
| `dev.ide.build.BuildTopics.BUILD` / `.RUN` | `build-api` | a build's tasks and failure bucket, a run's exit code |
| `dev.ide.analysis.AnalysisTopics.ANALYSIS` | `analysis-api` | a file's merged diagnostics, the stream the editor underlines |
| `dev.ide.index.IndexTopics.INDEXING` | `index-api` | index build progress and the terminal `IndexStatus` |
| `dev.ide.model.event.ProjectTopics.LIFECYCLE` | `project-model-api` | the open project changing |
| `dev.ide.vfs.VfsTopics.CHANGES` | `vfs-api` | raw file changes |
| `dev.ide.model.event.ProjectModelTopics.CHANGES` | `project-model-api` | model commits (modules, dependencies, source sets) |

Delivery is synchronous, in subscription order, on the thread that performed the transition. See
[section 6](#6-threading).

### 3.4 Through the host

Two things genuinely are the host's job, and going around them is wrong even though you could:

- **Navigation.** An engine-side action returns `ActionEffect.Navigate("com.example.myplugin.screen")` and the
  host opens the screen your UI facet registered under that id. Ids pass through verbatim rather than being
  namespaced, which is exactly what makes this work. From the UI side the same move is `ctx.openScreen(id)`.
- **The editor.** `ctx.openFile(path, offset)` puts the caret where you want it. A plugin cannot drive the
  editor itself, and an engine-side action's edits go back as `ActionEffect.ApplyEdits(...)` so the host
  applies them through its normal text path, in one undo step.

Keep the ids as constants on a type both facets import, so the engine's `Navigate` and the UI's
`screen(Screen(id = ...))` cannot drift apart. The Git plugin puts them on `VcsService`; an installed plugin
can put them anywhere both facets can see, which is anywhere.

---

## 4. Where the shared state goes

Two facets that are two objects need somewhere to meet. Three answers, in order of preference:

1. **A service the panel resolves** through `UiContext.service(key)`. The container holds the instance, so it
   is scoped and disposed rather than static. This is the shape the built-in tier has always had and the
   published tier gained in SPI `2.9.0`. See [4.0](#40-preferred-a-service-the-panel-resolves).
2. **One class implementing both interfaces**, so the halves share instance fields and there is no rendezvous
   at all. See [4.1](#41-one-class-two-interfaces).
3. **A shared `object`**, when the halves are separate classes and the state is genuinely app-scoped. It
   works, and it commits you to a classloader lifetime. See [4.2](#42-two-classes-a-holder-and-what-it-commits-you-to).

### 4.0 Preferred: a service the panel resolves

The engine facet registers a service; the panel resolves it by the key both halves share.

```kotlin
// Declared in the plugin's own module. Both facets see it, and the host never names the type.
val MY_CHAT = ServiceKey<MyChatState>("com.example.chat")

// Engine facet: register it like any other scoped service.
override fun register(reg: PluginRegistration) {
    reg.register(SERVICE_EP, ServiceDescriptor(MY_CHAT, ServiceScopeLevel.WORKSPACE) { MyChatState() })
}

// UI facet: resolve it, and read flows off it.
@Composable
fun Panel(ctx: UiContext) {
    val chat = ctx.service(MY_CHAT) ?: return EmptyState()
    val state by chat.messages.collectAsState()
}
```

Why this is first:

- **The lifetime is chosen, not inherited.** Register at `WORKSPACE` and the instance dies with the project,
  which is the staleness problem in [4.3](#43-what-object-actually-commits-you-to) solved rather than managed.
  The host resolves against the open project's container and falls back to the application's, so either scope
  works and the panel does not know which it got.
- **Nothing of yours becomes SPI.** The host resolves the key and hands the instance back without naming
  `MyChatState`. That is what makes this publishable where handing out `IdeBackend` is not.
- **`null` is the normal absent answer.** A disabled plugin, or a host older than the key, resolves to null.
  Render the empty state rather than throwing.

This is the same pattern the IDE's own UI uses. `ChatDrawer` does `backend.agent.chatState.collectAsState()`
and holds no static state at all; `UiPlugin` implementations like `VcsUiPlugin` and `AgentUiPlugin` are
singletons precisely because they carry nothing. The only difference is that a built-in reaches its service
through a fixed slot on `IdeBackend` and a plugin reaches its own through a key.

### 4.1 One class, two interfaces

Nothing says the facets have to be two classes. One class may implement both interfaces and be named in both
manifest lists, and the IDE instantiates it **once**:

```toml
entryPoints   = ["com.example.hello.HelloPlugin"]
uiEntryPoints = ["com.example.hello.HelloPlugin"]     # the same class
```

```kotlin
class MyPlugin : Plugin, UiPlugin {

    override val id = "com.example.myplugin"

    // Ordinary instance fields. Both halves are `this`, so there is no rendezvous to arrange, nothing
    // static, and nothing whose lifetime is longer than this object's.
    private var services: ServiceLookup = ServiceLookup.Empty
    private var last: Outcome by mutableStateOf(Outcome.Idle)

    override fun register(reg: PluginRegistration) {
        services = reg.appServices
        reg.register(UI_ACTION_EP, SimpleAction(/* ... */) { refresh(); ActionResult.message("$last") })
    }

    override fun contribute(ui: UiRegistration) {
        ui.toolWindow(ToolWindow(/* ... */) { ctx -> Panel(last, onRefresh = ::refresh) })
    }
}
```

This is the shape to prefer. The state is instance state, so it is scoped to the facet rather than to the
classloader, and [section 7](#7-lifetime-and-teardown)'s unload gotcha does not apply.

The cost is failure granularity. Two classes **fail independently**: a UI facet that throws while being
constructed is reported against the plugin on the Plugins screen while its engine facet goes on running. One
class means one failure takes both halves. Split when the halves are genuinely separate programs, not for
tidiness.

### 4.2 Two classes: a holder, and what it commits you to

When they really are two classes, they need a rendezvous, and a shared `object` is the only one the published
SPI offers today. It works, and it is worth being exact about what you are signing up for.

Pulling the pieces together. Three files, one job: the panel shows a value computed from the open project,
and a palette command computes the same value.

**The holder.** Owns the state and the work. Depends on neither facet.

```kotlin
/**
 * Sits between the plugin's two facets. The engine facet is the one with access to services; the UI facet is
 * the one that needs an answer to draw. They load off the same APK on the same classloader, so an object like
 * this is all it takes to get from one to the other.
 */
object SymbolCount {

    /** Seeded by the engine facet when it registers. Empty until then. */
    var services: ServiceLookup = ServiceLookup.Empty

    var last: Outcome by mutableStateOf(Outcome.Idle)
        private set

    sealed interface Outcome {
        data object Idle : Outcome
        data class Value(val count: Int) : Outcome
        /** Not yet: the index is still building. The caller retries rather than treating this as failure. */
        data class Waiting(val why: String) : Outcome
        /** This host predates the service. The normal answer on an older IDE. */
        data object Unsupported : Outcome
    }

    /** Blocking; call it off the composition thread. */
    fun refresh(): Outcome {
        // Resolved here rather than at registration: register() runs before any project is open, and the
        // lookup has to follow whichever project is.
        val search = services.getServiceOrNull(SYMBOL_SEARCH) ?: return Outcome.Unsupported.also { last = it }
        val outcome = /* ... query it ... */
        last = outcome
        return outcome
    }
}
```

**The engine facet.** Seeds the holder, registers contributions, and writes to the holder from them.

```kotlin
class MyPlugin : Plugin {
    override fun register(reg: PluginRegistration) {
        val log = reg.logger("MyPlugin")

        // Hand over the lookup; resolution itself stays lazy (see refresh above).
        SymbolCount.services = reg.appServices

        reg.register(
            UI_ACTION_EP,
            SimpleAction(
                id = "com.example.count",
                text = "Count symbols",
                places = setOf(ActionPlaces.COMMAND_PALETTE),
                iconId = "sparkle",
            ) { ctx ->
                if (ctx.projectRoot == null) return@SimpleAction ActionResult.NONE
                ActionResult.message("${SymbolCount.refresh()}")
            },
        )
    }

    /** The classloader outlives an unload, so a shared object's state does too. Clear what you seeded. */
    override fun dispose() {
        SymbolCount.services = ServiceLookup.Empty
    }
}
```

**The UI facet.** Registers a panel; the body reads the holder and runs work off the composition thread.

```kotlin
class MyUiPlugin : UiPlugin {
    override val id = "com.example.myplugin"     // must equal the packaged manifest's id

    override fun contribute(ui: UiRegistration) {
        ui.toolWindow(
            ToolWindow(
                id = "com.example.myplugin.panel",
                title = "Symbols",
                iconId = "sparkle",               // an id in the IDE's registry: a plugin has no drawables
                anchor = ToolWindowAnchor.LEFT,
            ) { ctx -> Panel(ctx) },
        )
    }
}

@Composable
private fun Panel(ctx: UiContext) {
    val scope = rememberCoroutineScope()

    // Reading the holder subscribes; the palette command's write recomposes this.
    Text(
        when (val outcome = SymbolCount.last) {
            SymbolCount.Outcome.Idle -> "Not counted yet."
            is SymbolCount.Outcome.Value -> "${outcome.count} symbols."
            is SymbolCount.Outcome.Waiting -> outcome.why
            SymbolCount.Outcome.Unsupported -> "This IDE has no symbol index."
        },
    )

    // Off the composition thread: the work is bounded but not free, and inline it janks the UI with it.
    Button(onClick = {
        scope.launch { withContext(Dispatchers.Default) { SymbolCount.refresh() } }
    }) { Text("Count") }
}
```

Read that top to bottom and the seam has no ceremony in it at all: one `object`, one assignment in
`register`, and ordinary reads in the body. That is the target. If your version has more moving parts than
this, the extra parts are probably solving the built-in tier's problem.

The same shape with `produceState` instead of a button, keyed on the editor buffer, is what
[`HelloUiPlugin`](../samples/hello-plugin/src/main/kotlin/com/example/hello/HelloUiPlugin.kt) does for its
preview pane: a keystroke re-runs the interpreter and nothing else does.

### 4.3 What `object` actually commits you to

A Kotlin `object` compiles to a class with a static `INSTANCE` field. The scope of that "static" is the
**classloader**, which is exactly why it works as a seam here: an installed plugin's two facets come off one
`PathClassLoader`, and the loader is built that way deliberately so they can share statics.

| | Holds the statics | An `object` there is shared with |
| --- | --- | --- |
| Installed plugin | its own `PathClassLoader` over its own APK | only its own two facets |
| Built-in | the IDE's classloader | the IDE and every other built-in |

That is the upside. The downside is that a static's lifetime is the **classloader's**, which is longer than
either thing you probably mean:

- **It outlives unload.** `PluginManager.unload` disposes the tracked contributions and calls
  `Plugin.dispose()`; it never discards the classloader. Whatever the holder points at is still reachable.
- **It outlives the project, which is the one that bites.** `register()` runs once, before any project is
  open, and never again. A holder caching anything derived from the open project keeps answering with the old
  project's data after the user switches, and nothing tells it.

So treat the `object` as the **address, not the lifetime**. Pick the scope of the contents on purpose:

| State | Where it belongs |
| --- | --- |
| Genuinely app-scoped (a counter, a settings cache, the `ServiceLookup` handle) | the holder, cleared in `Plugin.dispose()` |
| Derived from the open project | the holder, cleared by the engine facet on `ProjectTopics.LIFECYCLE` |
| Should die with the project | a `WORKSPACE`-scoped service via `SERVICE_EP`, so the container disposes it on project close. The holder then keeps the handle, not the data |
| Only meaningful while the panel is on screen | `remember` in the body, not the holder |

### 4.4 Why this is a workaround, and what would remove it

It is worth saying plainly: a mutable static as the channel between two halves of one program is not a good
pattern, and this document recommends it only because the published SPI currently offers nothing better.

**IntelliJ never needs it**, for one structural reason and one API reason. Structurally there is no facet
split: a plugin is one module and one classloader, Swing is available everywhere, so engine and UI code sit
side by side and never have to find each other. And the API hands the UI its scope:

```kotlin
@Service(Service.Level.PROJECT)
class MyState(private val project: Project) { /* ... */ }

class MyToolWindowFactory : ToolWindowFactory {
    // Handed the Project, so it resolves the same instance the engine side uses.
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val state = project.service<MyState>()
    }
}
```

The container owns the lifetime, so a project service dies with its project and the staleness above cannot
happen. IntelliJ in fact documents static plugin state as a **defect**: it retains the plugin classloader and
is the usual reason a plugin fails to unload dynamically.

CodeAssist's **built-in** tier already works the IntelliJ way. `ToolWindowContext.backend` is a locator handed
to the body, which is why the Git panel does `ctx.backend.vcs` and holds no statics (see
[section 8](#8-built-ins-the-same-problem-a-different-channel)).

The published tier does not, because `UiContext` carries `projectPath: String?`, a string rather than a
handle, and has no `getService`. `PluginRegistration.appServices` is APPLICATION-scoped and reachable only
from the engine facet. A panel can therefore resolve nothing, and a static is what is left.

That gap was the cost of a decision that is otherwise right: publishing `IdeBackend` would freeze every
concern service and DTO on it as permanent plugin API, and that is the layer of the IDE that changes most.
What was missing was publishing something narrower in its place.

**That is now `UiContext.service(key)`** (SPI `2.9.0`), which is why [4.0](#40-preferred-a-service-the-panel-resolves)
is the recommendation and this section is history rather than a standing excuse. The published surface is
`ServiceKey` (already in `platform-core`, which every plugin compiles against) plus one method, and the value
type stays the plugin's own, so nothing about a plugin's service is frozen.

What is still outstanding is the **built-in** half. `IdeBackend` carries eight optional concern services
(`notifications`, `store`, `learn`, `challenges`, `agent`, `vcs`, `customize`, `icons`), each defaulting to an
`Unsupported` null object, each gated by hand at the wiring site:

```kotlin
override val agent: AgentService =
    if (manager?.env?.pluginCatalog?.isEnabled(AgentPlugin.ID) != false) AgentBackend(this)
    else AgentService.Unsupported
```

Those are a second service locator with the slots hardcoded, sitting on top of the real one in
`platform-core`. Migrating them to keyed lookup would delete the null objects and the gating (a disabled
plugin registers nothing, so the lookup answers null) along with leaks like the core More menu asking
`backend.agent.ftpServerSupported()`. The thirteen non-optional services stay fields: they are never
plugin-contributed, there is no meaningful absent editor, and they are on the typing hot path.

That migration is blocked on one thing worth deciding deliberately rather than discovering: `ServiceKey` is in
`platform-core`, which is `kotlin("jvm")`, while `IdeBackend` is in `ide-ui-api`, which is KMP `commonMain`.
The published tier had no such problem because `plugin-ui-api` is plain JVM.

---

## 5. Which way things flow

Two rules, and most seam bugs are a violation of one of them.

**The engine facet owns truth. The UI facet owns presentation.**

The panel renders what it is given and reports what the user did. It does not decide whether a build is
stale, whether a symbol resolves, or what a file means. Those answers live where the services are, and a
panel that computes them locally will be wrong as soon as anything else changes them: the palette command,
another plugin, or the IDE itself.

Concretely, a value the panel displays should exist in the holder even when the panel is closed. If it only
exists while the panel is composed, it is UI state (a scroll position, an expanded row, a draft in a text
field) and it belongs in `remember`, not in the holder.

**The UI facet asks its own engine facet, not the host.**

`UiContext` deliberately cannot answer questions about the project, and the reflex to route around that (an
extension point that exists only to ferry data back, a service registered so the panel can resolve it) is the
same mistake wearing different hats. The panel's engine facet is in the same process holding the real thing.
Call it.

The one exception is the pair in [3.4](#34-through-the-host): navigation and the editor are the host's, and
they arrive as `UiContext` members and `ActionEffect`s for exactly that reason.

---

## 6. Threading

| What | Runs on | Notes |
| --- | --- | --- |
| `Plugin.register` | main thread, at startup | Once, before the first frame, before any project is open |
| `UiPlugin.contribute` | main thread, at startup | Once, after the engine facets have loaded. **Register only**; the work belongs in the bodies |
| A `@Composable` body | the composition (UI) thread | Never block it. No I/O, no interpretation, no index queries |
| An action's `invoke` | off the main thread | Which is why writing to snapshot state from it is fine |
| A bus listener | **the thread that performed the transition** | A build listener is on the build coroutine, an analysis listener on the analysis worker, an editor listener on the UI side |
| `Plugin.dispose` | the unload path | See [section 7](#7-lifetime-and-teardown) |

Three rules fall out of that table:

1. **Compose snapshot state is safe to write from any thread.** This is the reason it works as a seam: an
   action running on a background thread writes, and the panel recomposes on its own thread with no handoff
   to arrange.
2. **A bus listener must not block, and must not assume a thread.** It is delivered synchronously, in
   subscription order, on whatever thread published. A listener that touches the UI has to marshal; a
   listener that blocks holds up a build or an analysis pass. Write into the holder and return.
3. **Push work off the composition.** `rememberCoroutineScope().launch { withContext(Dispatchers.Default) { ... } }`
   for an action, `produceState(...) { value = withContext(Dispatchers.Default) { ... } }` for something
   derived from the buffer. Key it on what should actually re-run it (a path, the text) and nothing else.

Publishes from the IDE are guarded: a listener that throws cannot break a build, an analysis pass or the
editor. That is the IDE protecting itself, not a licence to throw. An exception in your listener means your
holder is now missing the transition it was supposed to record.

---

## 7. Lifetime and teardown

**Order.** Engine facets load first, in `dependsOn` order; UI facets are registered afterwards, and only for
plugins whose engine facet loaded cleanly. A UI facet therefore may assume its engine facet's `register` has
already run: the holder is seeded by the time any body composes. The reverse is not true, so `register` must
not expect the UI to exist.

**No project at registration.** Both facets register before any project is open. Resolve project state
lazily, at callback time, never in `register` or `contribute`. This is the single most common cause of an
installed plugin that works in a test and does nothing on a device.

**Gating.** The user's enable/disable and consent decision governs both halves, and it is applied on the next
launch: the manager loads once and does not hot-swap. A disabled plugin contributes no UI at all, and there
is no per-plugin gating in the UI layer for you to work around.

**Teardown.** Unloading a plugin disposes its tracked contributions (everything from `register`, LIFO), its
tracked bus connections, and every `UiHandle` from `contribute`, then calls `Plugin.dispose()`. You do not
need to hold the handles.

**The gotcha.** Unload does **not** discard the classloader, so a shared `object` is still there, still
holding whatever it held. If your holder caches a `ServiceLookup`, a scope, a watcher or a stale result,
clear it in `Plugin.dispose()`. This only bites in a long-lived process (the IDE's own tests, a reload), but
when it bites it looks like a plugin talking to a project that closed.

The sharper version of the same problem is the project, not the unload: a static holder does not notice a
project switch either. [Section 4.3](#43-what-object-actually-commits-you-to) has the scoping table, and
[4.1](#41-one-class-two-interfaces) is the shape that sidesteps both.

---

## 8. Built-ins: the same problem, a different channel

A built-in cannot use anything in sections 3 and 4, and the reason is worth stating plainly because it is
easy to mistake for a design preference: its engine facet is in `ide-core`, a `kotlin("jvm")` module, and its
UI facet is in a KMP module's `commonMain`. **`commonMain` cannot see a JVM-only artifact.** There is no
shared object available, because there is no module both halves can put one in.

So a built-in's channel is a neutral service interface declared in `ide-ui-api`'s `commonMain`, implemented
in `ide-core`, and reached from the body through `ToolWindowContext.backend`. Version control is the complete
worked example:

```kotlin
// ide-ui-api/commonMain: the interface, speaking DTOs only. No JGit type appears here. Abridged; the real
// one has four flows and some forty commands.
interface VcsService {
    val status: StateFlow<UiVcsStatus> get() = MutableStateFlow(UiVcsStatus())
    suspend fun commit(message: String, amend: Boolean = false): UiVcsResult = UNSUPPORTED
    fun supported(): Boolean = false
    object Unsupported : VcsService          // every member is defaulted, so this is the whole of it
}

// ide-core: the implementation, gated on the plugin being enabled.
override val vcs: VcsService =
    if (manager?.env?.pluginCatalog?.isEnabled(VcsPlugin.ID) != false) VcsBackend(this)
    else VcsService.Unsupported

// vcs-ui/commonMain: the panel.
val vcs = ctx.backend.vcs
val status by vcs.status.collectAsState()
```

What generalises from it:

- **`Unsupported` is a real no-op implementation, not a null.** Every member has a default, so the UI never
  branches on nullability: it asks `supported()` and hides the surface.
- **`!= false` rather than `== true`.** A backend with no manager (a test, a harness) has no catalog, so the
  feature stays wired instead of silently vanishing in tests.
- **Shared reads are `StateFlow`s; one-shot commands are `suspend` and return a result carrying a message
  already fit to show.** The panel does not compose error text.
- **Nothing engine-shaped crosses.** The UI never sees a JGit type. That is what makes the interface
  publishable-shaped even though it is not published.

**Choosing the shape.** If you are adding a feature to this repo and it could plausibly ship as an installed
plugin, the installed shape is cheaper: one module, one classloader, no DTO layer, no `IdeBackend` slot. Take
the built-in shape when the feature is on the typing hot path, must be present before any plugin loads, or
genuinely needs the internal UI surfaces (editor view modes, tab decorations, host actions) that are
built-in-only because they hand a body the whole `IdeBackend`.

The AI agent is the cautionary tale in the other direction: `IdeBackend.agent` plus roughly 65 lines of
`UiAgent*` DTOs duplicating `agent-api`'s own types exist because `agent-ui` is `commonMain` and `agent-api`
is JVM-only, not because anyone chose that coupling.

---

## 9. Anti-patterns

| Symptom | Why it hurts | Instead |
| --- | --- | --- |
| A DTO layer between an installed plugin's own two facets | Paying the built-in tier's tax for a constraint you do not have | Share the types directly; one classloader |
| The panel resolving services or reading the project model | `UiContext` cannot, and the workarounds are worse than the gap | Ask your engine facet; it has the whole SPI |
| Work in `contribute` or `register` | Both run at startup on the main thread, before the first frame | Register only; do the work in the body or the callback |
| Project state resolved at registration | There is no open project yet; you capture nothing, forever | Resolve lazily at callback time |
| A bus listener that does real work | It runs on the build coroutine or the analysis worker and holds it up | Write into the holder and return |
| Blocking in a composable | Janks the editor with whatever you are doing | `produceState` / `rememberCoroutineScope` and a background dispatcher |
| A holder that is only written by the UI | It is UI state wearing a holder's clothes | `remember` it in the body |
| Raw `messageBus.connect()` | Not tracked; the subscription outlives the unload | `reg.busConnection()` |
| A shared `object` never cleared | The classloader survives unload, so its state does too | Clear it in `Plugin.dispose()` |
| A shared `object` caching project-derived state | Static outlives the project; it answers with the old one after a switch | Clear on `ProjectTopics.LIFECYCLE`, or put the data in a `WORKSPACE`-scoped service |
| Two classes and a holder, when one class would do | A static you did not need, with a lifetime you did not choose | One class implementing both facets ([4.1](#41-one-class-two-interfaces)) |
| Bundling Compose, coroutines or the SPI in the plugin APK | The parent classloader wins; a mismatched version is a linkage error on the device | `compileOnly`, pinned through `plugin-bom` |
| Screen ids written as literals in both facets | They drift, and navigation silently stops working | A constant both facets import |

---

## 10. Testing the seam

**Preview the panel without installing anything.** `UiContext.preview()` stands in for the host, so the
states worth looking at are previews rather than installs:

```kotlin
@Preview
@Composable
private fun PanelPreview() = Panel(UiContext.preview(activeFilePath = "App.kt"))

@Preview
@Composable
private fun PanelNoFilePreview() = Panel(UiContext.preview())
```

`openFile` and `openScreen` do nothing there, because a preview has no editor to open into. `ScreenUiContext.preview()`
is the same for a full screen, with `back()` inert.

**Test the holder headlessly.** This is the payoff of putting the logic in the holder rather than in the
body: it is a plain object with no Compose and no host in it, so its transitions are ordinary unit tests. A
holder built on `StateFlow` rather than snapshot state tests with no Compose test infrastructure at all.

**Test the engine facet's contributions** the usual way (see
[writing-plugins.md section 14](writing-plugins.md#14-test-your-plugin)): load the plugin, assert what it
registered, unload it, assert the registry is clean. If your `dispose()` clears the holder, assert that too;
it is the thing nothing else will catch.

---

## 11. Checklist

Before shipping a plugin with both facets:

- [ ] The UI facet's `id` equals the packaged manifest's `id`.
- [ ] Both entry points are named in the manifest (`entryPoints`, `uiEntryPoints`), and `capabilities` lists
      what the plugin actually does.
- [ ] Nothing in `register` or `contribute` resolves project state or does work.
- [ ] If the halves are not genuinely separate, they are one class with instance fields rather than two
      classes and a static holder.
- [ ] Every state the panel displays lives in a holder, not only in the composition.
- [ ] Anything in a static holder that is derived from the open project is cleared on a project switch.
- [ ] Every service is resolved lazily, and `NotReady` / `Unsupported` are handled as "come back later" and
      "this host is older", not as failures.
- [ ] Bus subscriptions go through `reg.busConnection()`, and every listener returns promptly.
- [ ] No composable blocks; derived work is on a background dispatcher and keyed on what should re-run it.
- [ ] `Plugin.dispose()` clears whatever the holder was seeded with.
- [ ] Screen and tool-window ids are constants both facets import.
- [ ] The SPI, Compose and coroutines are `compileOnly`, pinned through `plugin-bom`, and the plugin is not
      minified.
- [ ] The panel has a `@Preview` for its empty state as well as its populated one.

---

## See also

- [writing-plugins.md](writing-plugins.md): the full plugin guide, including
  [the two facets](writing-plugins.md#22-the-two-facets),
  [contributing UI](writing-plugins.md#10-contribute-ui),
  [an installed plugin's UI facet](writing-plugins.md#1011-an-installed-plugins-ui-facet), and
  [shipping a plugin as its own app](writing-plugins.md#15-ship-your-plugin-as-its-own-app).
- [plugin-system.md](plugin-system.md): the loader, the manifest contract, and how installed plugins are
  discovered.
- [ui-extensibility-and-plugin-api.md](ui-extensibility-and-plugin-api.md): the design discussion behind the
  narrow published UI surface.
- [`samples/hello-plugin`](../samples/hello-plugin): both facets, a shared holder, an interpreter-backed
  preview pane, and build contributions, in one buildable module.
