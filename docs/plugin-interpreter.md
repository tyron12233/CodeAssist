# Opening the interpreter to plugins

A plugin can teach the IDE a language, add build tasks, add rows to the Run picker and contribute Compose UI.
What it could not do until now is **run the user's project code**. Everything that does is built in and
shaped for one framework: the Compose `@Preview` renders `@Composable` functions, the console run streams a
program's stdio, the layout preview inflates Android XML. A plugin for a framework with a render loop of its
own has no way in.

This document describes the seams that open the interpreter to plugins, and, just as importantly, the two
things that are deliberately not opened.

The motivating case is a LibGDX plugin: its own preview of a `Game`/`ApplicationListener`, and a Run row that
starts one. Nothing here is specific to it.

## What already existed

Worth knowing before reading the new API, because less of this is new than it looks:

- `RUN_TASK_PROVIDER_EP` (`build-api`) already lets a plugin add a Run-picker row and hand back a `RunAction`
  carrying a `TaskGraph`. The task engine (`Task`, `TaskInputs`, `TaskInputsImpl`, `AlwaysRun`) is published,
  so a plugin can already write its own tasks.
- `ProgramIo` already models a **windowed** program: `frame(path, width, height, seq)` streams raw RGBA
  frames, and `windowed(RunWindow)` hands the host pointer and key injection. This is how the Swing/AWT
  support draws a program's UI (`:awt-toolkit`). A plugin's run does not need new frame plumbing.
- `plugin-ui-api` already publishes tool windows, screens and overlays, all sharing one classloader with the
  plugin's engine facet.
- `PluginRegistration.appServices` already resolves application-scoped services by key.

## What is opened

Three seams, in the order a plugin meets them.

### 1. `interp-api`: running project code

A new published module, `dev.ide.interp.api`. One application-scoped service, resolved by key:

```kotlin
val interp = reg.appServices.getServiceOrNull(CODE_INTERPRETER)
```

It offers two kinds of session, matching the two interpreters the IDE already has.

**A source session** interprets Kotlin source with no compile step, which is what makes an edit-to-preview
loop possible. Lowering is the host's job:

```kotlin
when (val r = interp.lower(LowerRequest(file = path, text = buffer, entry = "MyGame"))) {
    is LowerResult.Lowered -> {
        val session = interp.openSource(r.program, InterpretConfig(libraryLoader = javaClass.classLoader))
        val game = session.instantiate("com.example.MyGame")
        val listener = game.proxy(ApplicationListener::class.java)   // hand it to real code
    }
    is LowerResult.NotReady -> // still indexing; retry
    is LowerResult.Failed -> // r.problems, ready to show the user
}
```

`proxy` is the load-bearing member. An interpreted object crossing out as a real implementation of a real
interface is what lets a plugin hand the user's code to a framework that expects to own the loop.

**A bytecode session** runs compiled classes on the `:jvm-interp` VM, for a plugin that builds first:

```kotlin
val session = interp.openBytecode(BytecodeConfig(classpath = module.runtimeClasspath()))
session.construct("com.example.MyGame").call("create")
```

Both sessions take the same policy knobs: which class-name prefixes to interpret and which to bridge to real
code, the `ClassLoader` real code is resolved against, a hook seam, and the sandbox categories to restrict.

### 2. `build-api`: running a program interpreted

`ProgramInterpreter`, `InterpretRunRequest`, `ProgramIo`, `RunWindow` and `InterpretExecTask` move from the
unpublished `:build-engine` into `build-api`, and `BuildContext` gains `programInterpreter`. A plugin's
`RunTaskProvider` can then put an interpreted program in its own graph:

```kotlin
override fun actionFor(spec: RunTaskSpec, project: Project, module: Module, ctx: BuildContext): RunAction? {
    val interp = ctx.programInterpreter ?: return null
    return RunAction(
        header = "Run ${module.name}",
        graph = graphOf(compileTask, InterpretExecTask(TaskName(":run"), mainClass, { cp }, interp)),
    )
}
```

Because `ProgramIo` already carries `frame`/`windowed`, a plugin that implements it gets a windowed program
for free: the host draws the frames and forwards input.

### 3. `plugin-ui-api`: an editor preview pane

`EditorCenter` chose its preview pane with a hardcoded `when` over the file path (markdown, layout, resource,
Compose). `UiRegistration.editorPreview(EditorPreview)` adds a fifth option a plugin owns:

```kotlin
ui.editorPreview(
    EditorPreview(
        id = "libgdx",
        title = "LibGDX",
        appliesTo = { path -> path.endsWith(".kt") },
        content = { ctx -> GamePreview(ctx.path, ctx.text, ctx.dark, ctx::reportProblems) },
    )
)
```

The context carries the live buffer, not just the path, so the pane re-renders on every keystroke exactly as
the Compose preview does. Contributed panes are considered after the built-ins, so a plugin cannot take the
`.xml` pane away from the layout preview.

## What is deliberately not opened

**`ResolvedTree` stays private.** It is tempting to publish `:interp-core` as it stands and let plugins write
their own `Dispatcher` and `InterpreterHooks`. The blocker is that `interp-core` does
`api(project(":lang-kotlin"))` and `InterpreterHooks.beforeCall` takes an `RNode.Call`, so publishing it
freezes the resolver-to-interpreter contract as plugin ABI. That contract changes with nearly every lowering
fix, and a plugin compiled against last month's tree fails at first call, not at load, which is the worst
failure shape there is. `LoweredProgram` is therefore an opaque handle: a plugin can carry one from `lower`
to `openSource` and read its diagnostics, and nothing else. The hook seam is re-declared in `interp-api`
over strings (`ownerFqn`, `member`) and adapted to `InterpreterHooks` inside the host.

**`Vm` stays private.** Publishing `:jvm-interp` outright would put `org.ow2.asm:asm-tree` in the plugin ABI
(`VmClass` holds `MethodNode`s) and freeze the VM's own model. `BytecodeSession` is the narrowed alternative,
the same choice already made for `BuildControl`, `SymbolSearch`, `ModuleSources` and `ModuleAnalysis`: expose
the members a plugin needs, keep the engine free to change behind them.

## What a plugin should expect at runtime

**Perf.** Measured on an arm64 emulator (see the VM benchmark spikes): a warm interpreted Material3
composable is ~1.9 ms against ~0.29 ms reflective, and the first call into a large interpreted jar pays a
one-time VM parse (~360 ms for Material3's ~1374 classes). Fully interpreted UI at 60 fps is roughly two
orders of magnitude away. A per-edit render, a static scene, or a low-frame-rate preview is comfortable; a
game loop at 60 fps with the framework itself interpreted is not.

The way around it, and the recommended shape for a framework plugin: **bundle the framework in the plugin
APK**. Those classes are ordinary dexed ART code in the plugin's own classloader, so passing
`InterpretConfig(libraryLoader = javaClass.classLoader)` bridges them as real code at full speed and leaves
only the user's own source interpreted. It also keeps the Play posture unchanged, since nothing is
downloaded and no class loader is handed new code at runtime.

**Isolation.** A plugin session runs **in the IDE process**. The `:preview` process isolation is
Compose-specific: its AIDL surface serializes `LoweredComposePreview`, so there is nothing generic to cross
yet. A runaway interpreted loop in a plugin's preview therefore hangs the IDE, which the built-in Compose
preview no longer does. A session is `Disposable`, and the VM honours cancellation; generalizing the isolated
process is future work.

**Sandbox.** A session defaults to the project's own Compose-preview sandbox categories rather than to an
unrestricted interpreter, so a plugin preview does not quietly get more access to the device than the
built-in one has. A plugin may widen or narrow it through `InterpretConfig.sandbox`.

**Consent.** Running the user's project code inside the IDE is what the new `interp.run` capability declares,
so it appears on the plugin's consent screen before the plugin runs at all.

## How it is wired

`CODE_INTERPRETER` is registered by `InterpreterPlugin` (a non-essential built-in, id `interpreter`) at
**application** scope, because a plugin resolves services through `PluginRegistration.appServices` and holds
no project. It therefore reads whichever project is open, through `ApplicationEnvironment.activeEngine`, and
answers `LowerResult.NotReady` when there is none. A plugin that needs it declares
`dependsOn = ["interpreter"]`, so disabling the interpreter disables what depends on it.

Lowering itself is a workspace service (`INTERPRETER_LOWERING`), sharing `loweredModelFor` with the Compose
`@Preview` path: the cross-module reachable-declaration expansion, the ownership routing that lowers a
dependency module's file against its own classpath, and the dumb-mode gate are the same work for both. What
differs is only which declaration is the entry (a plugin's may be a type, since a framework's entry point
usually is) and that every refusal carries a reason, which a `null` return cannot do across an API boundary.

On device the Android launcher registers `VM_PEER_FACTORY`, so a bytecode session's peers are dexed rather
than defined from class-file bytes, which ART cannot do. A class implementing only interfaces needs no peer
at all, so the default covers the common case everywhere.

## Module layout

```
interp-api    published SPI: the service key, the sessions, the config, the hooks (plain JVM)
interp-impl   the engine: sessions over :interp-core (source) and :jvm-interp (bytecode)
ide-core      lowering (it already owns the analyzers) + the built-in InterpreterPlugin that registers the key
```

`interp-impl` holds the concrete `LoweredProgram`, so ide-core and the session engine share the real lowered
types while `interp-api` sees only the interface. `interp-api` follows the repo's `*-api` / `*-impl`
convention and is the tenth published SPI artifact.

## Limits

- A source session bridges library code **reflectively** against the supplied `ClassLoader`. A project's own
  jar dependencies are not interpreted in a plugin source session; the VM-backed library executor
  (`VmLibraryExecutor`) lives in the Compose-coupled `:interp-compose` and is not reachable from a plain-JVM
  module yet. A bytecode session is the path for compiled project code.
- No isolated process, per above. What stands in for it is the interpreter's own bounds: a call that exceeds
  the recursion depth or the wall-clock deadline aborts, and a bytecode session can be cancelled from another
  thread.
- A bytecode session reports no sandbox findings. The VM's boundary is its `NativeBridge`, not the source
  interpreter's hook seam, so a category sandbox there is something the host installs in the bridge (as the
  console run does through its own guard) rather than something the session can report per call.
- No suspend or coroutine entry points on a session: `call` is synchronous, matching the interpreter's own
  boundary.
- `InterpretedObject.proxy` cannot serve an interface default method the user's class does not override on
  device: `InvocationHandler.invokeDefault` is a JDK 16 API that ART does not have. The failure names the
  limitation rather than looking like a missing member.
