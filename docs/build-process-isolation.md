# Build/run process isolation

Status: design + Phase 0 (instrumentation) landed. Phases 1+ not started.

## Motivation

The build and the execution of user code run in-process, in the IDE's own ART heap, alongside the editor
engine (index, analyzers, completion) and the Compose UI. The compile/dex/R8 path is memory-heavy, and an
`OutOfMemoryError` (or a user program crashing) takes down the whole IDE. Moving build + run into a
separate OS process (`android:process=":build"`) gives that work its own heap: an OOM or crash there kills
only that process, and its peak no longer stacks on the editor heap.

This is robustness, not efficiency. Two ART runtimes (and a second warm K2 compiler) use more total RAM,
not less. The gains are: (1) a build/run OOM no longer kills the IDE; (2) each process lives under its own
per-process `largeHeap` ceiling, so a build that fails today because the editor already filled the heap can
succeed; (3) user code runs out-of-process, so its crashes/exits are isolated.

It does not address project-open OOM. The cold-start storm (index build + the two retained Kotlin warm-ups)
runs before any build and is editor-side; see `IdeServices.kt` cold-start sequencing. Phase 0 measures
which OOM dominates before the rest is built.

## Phase 0: measurement (done)

Java-heap instrumentation, because the compile/dex/R8 workload is Java-heap-bound: `largeHeap` raises the
Java-heap ceiling and a compiler growing that heap is what pushes total RSS into the low-memory killer, so
`maxMemory - used` headroom is a faithful proxy. Native/PSS sampling is the next refinement only if the
data shows the heap far from its ceiling at an OOM.

- `MemProbe.kt` — `MemSample` (a heap reading in MB) and `PeakHeap` (worst used / tightest headroom across
  a window, periodically sampled so a long R8 pass's intra-task peak is caught).
- Project-open path (`IdeServices` cold-start) logs heap after index / parser warm-up / compiler warm-up on
  the `ide.mem` tag, logs when a warm-up is skipped for low headroom, and records the open-phase peak.
- Build/run path (`IdeServices.launch`) tracks the build's heap peak (periodic sampler + a sample on each
  task-status event) and logs it.
- Analytics: `index_perf` carries heap at index completion; `build_result` carries the build's
  `peak_heap_mb` / `min_headroom_mb` / `heap_max_mb` (`IdeServicesBackend`). Local logs are the primary
  signal (no consent required); analytics gives fleet aggregates.

Read the `ide.mem` log lines (Logs viewer / logcat) to compare the project-open peak against the build
peak on a given device.

## Architecture (Phases 1+)

A headless build/run daemon in a `:build` process hosts a build/run-only engine (model re-read from disk,
build engine, JDT + its own warm K2, D8/R8/aapt2/apksig, `DexClassLoaderRunner` + the run sandbox). The UI
process keeps the editor engine. The two never share live objects: only the shared filesystem (same UID,
same APK: project files, caches, build cache, index segments, the built APK) and a thin IPC channel. IPC
carries control and small event deltas only; all bulk data travels on disk.

A full second engine (rather than shipping the task graph over IPC) matches how the codebase already works:
everything the build reads is disk-backed (`module.toml`, `libraries.json`, the resolved-deps cache, the
build cache), so a fresh process reconstructs the build context from disk and the IPC payload stays small.

### Runner seam

`ide-core` gains a `BuildRunner` interface mirroring `BuildService`:

- `InProcessBuildRunner` — wraps today's `IdeServices` build/run code. Desktop always uses it; Android uses
  it when the separate-process setting is off. Pure refactor, no behavior change.
- `RemoteBuildRunner` (`:ide-android`) — binds the daemon, forwards calls over AIDL, reassembles
  `BuildState`/`RunConsoleUi` from streamed deltas.

`BuildBackend` delegates to the injected runner. The UI and the `IdeBackend` contract do not change.

### Runner selection: a setting

An app-global preference `IdeSettings.buildInSeparateProcess` (default on) selects the runner on Android.
On → `RemoteBuildRunner`; off → `InProcessBuildRunner` (lower memory, no isolation). Surfaced as a toggle
whose effect the backend applies by key, like the other built-in toggles. No automatic RAM-based fallback:
if separate-process is selected and the daemon cannot bind, the build surfaces an explicit error rather
than silently switching. Desktop ignores the flag.

Placement: the toggle is app-global, so it needs an application-scoped home; the existing
"Build & Dependencies" page is `SettingsScope.PROJECT` (its conflict-policy control is per-project). Add an
app-scoped build-runtime group for the toggle.

### IPC

A bound foreground service plus AIDL (Binder): multi-threaded calls, `oneway` streaming callbacks, and
death notification.

- Commands (UI to daemon): `open`, `runTasks`, `runTask`, `runBuild`, `stopBuild`, `sendRunInput`,
  `closeRunInput`, `answerPermission`, `registerCallback`.
- Events (daemon to UI, `oneway`): `onStatus`, `onStep`, `onLog`, `onDiagnostic`, `onRunConsole`,
  `onPermissionRequest`.
- Deltas, not snapshots: the in-process code already emits build-state deltas (`onLog`/`onDiagnostic`),
  which `RemoteBuildRunner` reassembles into `StateFlow<BuildState>`.
- DTO codec: the `ide-ui` DTOs are KMP `commonMain` and cannot be `@Parcelize`d directly, so a thin codec
  lives in `:ide-android`.
- The permission round-trip is already blocking (`PermissionBroker.check` blocks on a queue); over IPC the
  daemon fires `onPermissionRequest` and the program thread blocks on a latch released by `answerPermission`.

### Lifecycle and OOM surfacing

- Foreground service with an ongoing notification (minSdk 26 requires it for long background CPU work).
- `Binder.DeathRecipient`: a daemon OOM-kill surfaces `BuildState(Failed, "Build process ran out of
  memory")`; the UI survives with editor state intact, and the next build restarts the daemon.
- Pre-warm the daemon after project open (off the critical path) so the first build is not cold.
- Idle shutdown to reclaim the daemon heap; both processes get the per-process `largeHeap` ceiling.

### Preconditions (verified)

Flush-on-build is already solved. The build reads source only from disk; `openDocuments` (in-memory editor
working copies) is the only unsaved state, and `IdeServices.runTask` already calls `flushOpenDocuments()`
first, writing every changed buffer to disk (content-diff guarded). All other file mutations write disk and
overlay together. The overlay/flush split maps onto the process split: the UI owns `openDocuments`, the
daemon reads disk only. The only change is that `RemoteBuildRunner` calls the flush UI-side before the RPC.

## Risks

- First IPC in the codebase: net-new AIDL/service/lifecycle/death handling. Mitigated by the phased rollout
  and `InProcessBuildRunner` as the always-working fallback.
- Doubled warm K2 (more total RAM). The setting (default on) lets low-RAM users switch to in-process.
- Does not fix project-open OOM (editor-side). Phase 0 measures whether that dominates.
- Build-start latency (process spawn + K2 cold start). Mitigated by daemon pre-warm.

## Phased rollout

0. Measure (done): heap instrumentation at the open phases and the build peak.
1. Extract `BuildRunner`; refactor current build/run behind `InProcessBuildRunner` (no behavior change).
2. IPC skeleton: AIDL + DTO codec + a `:build` service running one task with log streaming; prove heap
   isolation on-device (force a daemon OOM, confirm the UI survives via `binderDied`).
3. Real builds: headless build-only engine in the daemon; wire `runTask`/`runBuild`/`stopBuild` + delta
   streaming.
4. Run user code: move `DexClassLoaderRunner` + the sandbox into the daemon; wire `runConsole` stdin/stdout
   and the blocking permission round-trip.
5. Hardening: foreground notification, death detection + auto-restart, idle shutdown, pre-warm, and the
   `buildInSeparateProcess` toggle.
6. Conditional: editor-side cold-start OOM, only if Phase 0 shows it dominates.
