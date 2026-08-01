# Compose preview process isolation

Status: **Phase 0 spike GREEN on ART** (2026-08-02) — the off-screen live-runtime gate is de-risked; the phased
pipeline (1–4) below is the remaining build. Supersedes the in-process Compose `@Preview` render path described in
`docs/compose-interpreter.md` for the Android launcher only.

Phase 0 result (`ComposePreviewIsolationSpike`, `ide-android` androidTest, emulator-5554): a `ComposeView` inside a
`Presentation` on an app-owned `VirtualDisplay` + `ImageReader` (from a Service-style context, minimal RESUMED
`SpikeOwner` for the ViewTree lifecycle/savedstate/viewmodel owners) **recomposed off-screen** (a frame landed in
the `ImageReader`), an **injected `dispatchTouchEvent` reached a `clickable`**, and the click's state write **drove
a new frame** (1 → 2). So the load-bearing assumption holds: `:preview` can host the real Compose runtime off the
IDE thread, stream frames, and receive forwarded input.

## Problem

The Compose `@Preview` render runs **in-process**, inside the IDE's own Compose composition.
`ComposePreviewRenderer.Render` (`interp-compose/.../ComposePreviewRenderer.kt`) is a `@Composable` embedded
in the editor pane; it captures the ambient composer (`dispatcher.composer = currentComposer`,
`ComposePreviewRenderer.kt:154`) and re-runs the tree-walking interpreter **on the UI/composition thread every
recomposition**. Interpreted code emits *real* Compose nodes into the IDE's own live tree — there is no
process boundary and no bitmap.

That coupling is the freeze surface. When a library composable writes a Compose state it also reads during
composition (e.g. a LayoutLib-only preview-overlay library that walks the live host's semantics — see
`preview-runaway-recomposition-guard`), the **real** runtime loops on the IDE's composition thread. The
in-process guards (recomposition storm bound 1000/1s in `ComposeRuntime.recomposeStorm`, the content-lambda
storm listener in `ComposeDispatcher.contentLambdaStorm`, the `interp-core` loop budget, `LocalInspectionMode`)
catch the cases they can see, but a real-runtime loop that never yields a frame can still hang the whole IDE.
A hang leaves Binder unresponsive, so even a `DeathRecipient` wouldn't help — the process is alive but stuck.

The XML/RealView layout preview already solved the analogous problem by running out-of-process: a dedicated
`:preview` OS process (`PreviewRenderService`, `android:process=":preview"`) renders real Android Views to a
bitmap, reached over AIDL, with a `DeathRecipient` + in-process fallback (`RemoteRealViewRuntime`). The Compose
preview is the lone in-process outlier. This doc moves it onto the same process, and keeps it interactive.

## Decision (2026-08-01)

- **Fully isolated, including interactive.** The live Compose runtime + interpreter move entirely into
  `:preview`. The IDE never composes interpreted nodes into its own tree again. Interactivity is preserved by
  **bitmap streaming + input forwarding**: `:preview` renders to an off-screen surface and streams frames back;
  the IDE forwards pointer/key events to `:preview` over AIDL.
- **Android only.** Desktop keeps today's in-process `DesktopComposePreviewHost` path (no `:preview` process
  there; large OOM headroom; a subprocess+shared-window is disproportionate). `ide-core` stays platform-agnostic
  through the `ComposePreviewRunner` port, so only `ide-android` gains the remote implementation.
- Rejected alternatives: `SurfaceControlViewHost`/`SurfacePackage` embedding (better input fidelity, but API 30+
  and a heavier host integration; project minSdk is 26); a tiered "isolated static bitmap + interactive stays
  in-process" model (leaves the interactive freeze risk in place).

## Why interactivity is the hard part

The interactive preview has **no runtime of its own** — it borrows the IDE's live `Composer`, `Recomposer`,
snapshot system, and frame clock. A `Composer` is an in-heap object and cannot cross a process boundary.
Therefore isolation is not "send a bitmap back": the **entire live Compose runtime must move with the
interpreter** into `:preview`, which then owns:

- its own `Composition` + `Recomposer` + `MonotonicFrameClock` (a real one — animations must tick),
- an off-screen render target (a `Surface` we can read frames from),
- input dispatch into that composition,
- the resource resolver, `LocalInspectionMode`, and night/locale `Configuration`, reconstructed remotely.

State (`remember`/`mutableStateOf`) then lives in the remote composition's slot table — which is fine, and is
exactly why live-edit incrementality (`ComposePreviewRenderer.kt:109-135`, identity-diff of `ResolvedFunction`
instances) still works: the session stays alive across edits, so state survives.

## Target architecture

```
  MAIN (IDE engine) process                     :preview process
  ─────────────────────────                     ────────────────────────────────────────────
  KotlinSourceAnalyzer.lowerComposePreview  ──▶  ComposePreviewSession
   → LoweredComposePreview                         (persistent, one per open preview)
   (entry + program + classes + param)              │
        │  serialize (ResolvedTree codec)           ├─ Interpreter (interp-core)
        ▼                                           ├─ ComposeDispatcher/ComposeRuntime/ComposableAbi
  RemoteComposePreviewRunner ── AIDL ──────────────▶├─ VmLibraryExecutor  (jvm-interp, over classpath[])
   (implements ComposePreviewRunner)                ├─ PreviewResourceResolver (rebuilt from res roots)
        ▲                                           ├─ PreviewSandboxPolicy (InterpreterHooks)
        │  frames (HardwareBuffer / shared file)    │
        │  ◀─── onFrame callback ────────────────── ├─ live Compose runtime → off-screen Surface
        │  input (oneway dispatchInput) ──────────▶ │   (VirtualDisplay + Presentation + ImageReader)
        ▼                                           │
  PreviewSurface (ide-ui) draws frames,           watchdog / DeathRecipient ── crash/hang kills only :preview
  captures + maps touch → forwards
```

### The process split

- **Main engine keeps lowering.** `lowerComposePreview` (`ComposePreviewService.kt`) needs the classpath model,
  index, and Kotlin symbol service — the exact heavy state we do *not* want to duplicate in `:preview` (that
  RAM is the point of isolating). It already runs off the UI thread on the engine dispatcher.
- **`:preview` receives the lowered program + classpath paths + resource roots** and does interpret + render.
  It stays lean: it never loads the project model or the Kotlin analyzer. This mirrors the existing
  `RealViewRequest` design (main process prepares a self-contained request; `:preview` renders it).

### The off-screen live runtime (load-bearing; spike first)

`:preview` is a `Service` with no visible window, but interactive Compose needs a real window/`ViewRootImpl`
(for the Choreographer frame clock + input routing). Proposed uniform API-26+ approach:

1. `ImageReader.newInstance(w, h, PixelFormat.RGBA_8888 / HardwareBuffer usage, maxImages)`.
2. `DisplayManager.createVirtualDisplay("ca-preview", w, h, densityDpi, imageReader.surface, flags)` — an
   app-private virtual display; no overlay permission needed (it is not the system display).
3. `Presentation(serviceContext, virtualDisplay.display)` whose content view is a `ComposeView` running
   `setContent { ComposePreviewRenderer.Render(entry, program, classes, …) }`. The Presentation supplies a real
   `ViewRootImpl` + Choreographer on that display → recomposition, animation, and input all work as on a device.
4. Frames land in the `ImageReader`; `onImageAvailable` → `acquireLatestImage()` → send back (see transport).
5. Input: forwarded `MotionEvent`s are reconstructed and dispatched via `presentation.window.decorView
   .dispatchTouchEvent(ev)`, with coordinates in the virtual display's space.

**Spike (Phase 0) must confirm:** a `Presentation` on an app-owned `VirtualDisplay` shows from a Service
context, its `ComposeView` recomposes + animates, and an injected `dispatchTouchEvent` reaches a `clickable`.
This is the single riskiest assumption; de-risk it exactly like the ABI spike (`ComposeAbiSpikeTest`) and the
forked-VM heap probe. Optional API-30+ refinement: host the `ComposeView` in a `SurfaceControlViewHost` for
cleaner input routing while still reading frames off its surface (kept internal — no `SurfacePackage` sent).

### Frame transport

- **API 28+ (preferred, zero-copy):** back the `ImageReader` with `HardwareBuffer` usage; on each frame send the
  `HardwareBuffer` (Parcelable) over the AIDL callback. The IDE wraps it with `Bitmap.wrapHardwareBuffer(...)`
  and draws it directly — GPU memory shared, no pixel copy.
- **API 26–27 fallback:** copy the acquired image's planes to raw ARGB in a shared-cache file and pass the path,
  reusing the exact convention the XML path already uses (`RemoteRealViewRuntime.parseResult`,
  `copyPixelsFromBuffer`). Lower fps, acceptable on old devices.
- **Throttle:** only push a frame when the composition actually produced one (drive off the `ImageReader`
  callback / an invalidation signal), so an idle preview streams nothing — no 60fps of identical frames.

### Input forwarding

- `PreviewSurface` (`ide-ui/.../editor/preview/PreviewSurface.kt`) already has a **Lock mode** that removes its
  pan/zoom `pointerInput` so events fall through to content. In interactive mode it instead **captures** pointer
  (and key) events, maps screen → preview-content coordinates (it already tracks scale/pan for zoom), and
  forwards them over a `oneway dispatchInput(sessionId, action, x, y, pointerId, eventTimeMillis)` AIDL call.
- `:preview` reconstructs a `MotionEvent` (obtain/recycle) and dispatches it into the Presentation decor view.
  Multi-touch/pointer-id and ACTION_MOVE streaming are handled by forwarding each event in order.

### Serialization: the `ResolvedTree` wire codec (the one new surface)

`LoweredComposePreview` (`IdeServices.kt:318`) = `entry: ResolvedFunction` + `program: Map<String,
ResolvedFunction>` + `classes: List<ResolvedClass>` + optional `parameter`. These are built from
`ResolvedTree.kt` (sealed `RNode`, `Binding`, `DispatchKind`, `ResolvedCallable`, referencing `KotlinType`) —
plain data, no PSI. A compact binary codec (in the style of the existing `.kxt` / typeShape codecs) serializes
the program to a blob that crosses AIDL; `:preview` decodes it back to the same types the interpreter already
consumes. This is the biggest single work item, but bounded and pure-data.

Rejected alternative: re-lower in `:preview`. That needs the full Kotlin symbol service + classpath there — the
RAM we are isolating away. Serialize the already-lowered program instead.

### Resources + libraries

- **Libraries:** pass the module compile-classpath jar paths (`ComposePreviewLibs.jars`) over AIDL; `:preview`
  builds the `VmLibraryExecutor` (jvm-interp) there — it already runs the VM for XML custom views.
- **Resources:** pass the resource roots + R namespace (`ComposePreviewResources.namespace`); `:preview` rebuilds
  the `ResourceRepository` and the `PreviewResourceResolver`. (Or relink a `resources.ap_` like the XML path —
  decide during Phase 1.)

## AIDL surface (new, in `ide-android/src/main/aidl/dev/ide/android/preview/`)

A session-oriented interface, distinct from the one-shot `IPreviewRenderer` (which stays for XML):

```
interface IComposePreviewSession {
    int    pid();
    // Open a persistent session; programBlob = serialized LoweredComposePreview; returns a sessionId or "err\t…".
    String open(String programBlob, in String[] classpath, in String[] resRoots, String packageName,
                int minApi, int widthPx, int heightPx, float density, boolean night,
                in IComposePreviewCallback cb);
    // Live edit: push a re-lowered program into the running session (state survives via identity-diff).
    void   update(int sessionId, String programBlob);
    void   resize(int sessionId, int widthPx, int heightPx, float density, boolean night);
    oneway void dispatchInput(int sessionId, int action, float x, float y, int pointerId, long eventTimeMs);
    void   close(int sessionId);
    // Heartbeat for the hang-watchdog (see below): returns a monotonically-advancing frame/compose counter.
    long   heartbeat(int sessionId);
}
oneway interface IComposePreviewCallback {
    void onFrame(in HardwareBuffer buffer, int w, int h);   // API 28+; else onFrameFile(String path, w, h)
    void onProblems(String json);                            // sandbox findings + partial-render errors
    void onError(String message);                            // fatal render error → IDE shows an error view
}
```

Control travels over Binder; bulk frames travel as `HardwareBuffer` handles (or a shared file on API < 28) —
the same "control over Binder, bulk over shared memory/FS" convention as `:build` and the XML `:preview` path.

## The freeze fix + the watchdog

Relocating the interpreter to `:preview` moves the storm/loop guards there too (they already live in
`interp-core`/`interp-compose`). Now a runaway only pegs `:preview`. Two safety nets in the IDE:

- **`DeathRecipient`** (like `PreviewRenderClient`): a `:preview` crash/OOM → the IDE shows "preview crashed,
  restarting" instead of dying.
- **Hang watchdog (new, and the real win over today):** a real-runtime loop hangs `:preview` without crashing
  it, so Binder goes unresponsive. The IDE polls `heartbeat(sessionId)` on a timer; if the counter stops
  advancing for N seconds (and frames stopped), it `Process.killProcess` / unbinds+rebinds `:preview` and shows
  the runaway error. The IDE main thread is never blocked because it only ever *displays* frames and *sends*
  input — it never runs interpreted code.

Fallback when isolation is unavailable (setting off, bind fails, or spike-blocked API): keep the in-process
`AndroidComposePreviewHost` renderer as a death/unavailability fallback only — not a user-selectable mode —
mirroring how `RemoteRealViewRuntime` falls back to `AndroidRealViewRuntime`.

## Phased plan

- **Phase 0 — spike (de-risk the off-screen live runtime). ✅ DONE (green on ART, 2026-08-02).**
  `ComposePreviewIsolationSpike`: VirtualDisplay + Presentation + ImageReader; a `ComposeView` recomposes
  off-screen, a frame is read back, and an injected `dispatchTouchEvent` reaches a `clickable` (whose state write
  drives a new frame). The gate is green — the initiative is unblocked.
- **Phase 1 — pipeline, single frame, no input.** `ResolvedTree` wire codec; `IComposePreviewSession.open` +
  `onFrame`; `RemoteComposePreviewRunner` (implements `ComposePreviewRunner`) in `ide-android`;
  `PreviewRenderService` (or a sibling in `:preview`) hosts the session and renders one frame. Prove a static
  `@Preview` renders identically to today, but out-of-process.
- **Phase 2 — continuous frames.** HardwareBuffer transport (+ file fallback), dirty-driven throttle,
  `resize`/night. Animations tick.
- **Phase 3 — input.** `PreviewSurface` interactive-mode capture + coordinate mapping + `dispatchInput`;
  `:preview` MotionEvent reconstruction + dispatch. Clicks/scroll/gestures reach real nodes.
- **Phase 4 — live edit + resilience.** `update(program)` on edit (state survives); hang watchdog +
  `DeathRecipient` fallback; verify the storm/loop guards now only affect `:preview`; migrate the sandbox
  (`PreviewSandboxPolicy`) + problem/finding reporting over `onProblems`.

## Testing

- Device instrumentation: the Phase 0 spike; a session open→frame→input→edit round-trip; a deliberate
  runaway-recomposition composable proving the IDE stays responsive while `:preview` is killed by the watchdog.
- Headless: the `ResolvedTree` codec round-trip (pure JVM, CI); reuse `interp-compose:desktopTest` for the
  interpreter half (unchanged — it now just runs in a different process).

## Open questions

- Resource handoff: rebuild `ResourceRepository` in `:preview` from res roots vs. relink a `resources.ap_` like
  the XML path (decide in Phase 1).
- One `:preview` process shared by XML + Compose, or a second `android:process` for Compose sessions? (Shared
  keeps one warm process; separate isolates a Compose hang from an XML render.)
- Frame pacing / backpressure when the IDE draws slower than `:preview` renders (drop to latest).
- Whether the "separate process" setting (`BuiltInSettingsPages.SEPARATE_PROCESS`) also gates Compose preview,
  or a dedicated toggle.

## References

- `docs/compose-interpreter.md` — the interpreter + in-process render being isolated.
- `docs/build-process-isolation.md` — the `:build` daemon precedent (AIDL + shared-FS + DeathRecipient).
- XML/RealView isolation (the template): `ide-android/.../preview/PreviewRenderService.kt`,
  `PreviewRenderClient.kt`, `ide-android/.../preview/realview/RemoteRealViewRuntime.kt`,
  `ide-android/src/main/aidl/dev/ide/android/preview/IPreviewRenderer.aidl`.
- Seams: `ComposePreviewRunner` (`ide-core/.../IdeServices.kt:366`), `LoweredComposePreview`
  (`IdeServices.kt:318`), `ComposePreviewRenderer.Render` (`interp-compose/.../ComposePreviewRenderer.kt:98`),
  `AndroidComposePreviewHost` (`ide-android/.../AndroidComposePreviewHost.kt`), `ResolvedTree.kt`
  (`lang-kotlin/.../interp/ResolvedTree.kt`).
