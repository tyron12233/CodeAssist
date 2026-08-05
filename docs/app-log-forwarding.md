# App-log forwarding (the Logcat tab)

The IDE shows a running **debug** app's logs live, Android-Studio-Logcat style, in a console tab. Because the
built app is a *separate app and OS process* from the IDE, the IDE cannot read its logs from the outside (on a
non-rooted device `READ_LOGS` only ever returns the caller's own process's logs). So the logger runs *inside*
the built app and pushes its logs back to the IDE over **Binder**.

```
Built debug APK (its own process)              IDE (:ide-android)
┌───────────────────────────────┐             ┌──────────────────────────────┐
│ <provider IdeLogBridgeProvider>│   Binder    │ AppLogSinkService (exported) │
│   onCreate() → IdeLogBridge:   │  bindService│   resolved by SINK action     │
│    • logcat --pid=self ────────┼────────────►│   onTransact(TXN_SUBMIT)      │
│    • System.out/err tee        │  (submit    │   → AppLogSinkRegistry.active │
│    • uncaught-exception hook   │   String[]) │   → AppLogChannelImpl         │
│  resolveService(SINK)+bind ────┤             │     .acceptFrames → parse     │
└───────────────────────────────┘             │   → StateFlow<AppLogSnapshot> │
   from bundled applog-runtime.jar,           │  BuildService.appLog          │
   injected into DEBUG builds only            │  → BuildConsole "Logcat" tab  │
                                              └──────────────────────────────┘
```

On-device only — the desktop launcher has no install/launch step, so there is nothing to attach to.

## Why Binder (not a socket)

The original transport was an abstract-namespace `LocalSocket`. On modern Android that cannot work between the
built app and the IDE: SELinux `neverallow`s one **untrusted app** connecting to another untrusted app's
abstract/local socket (`avc: denied { connectto }`), and it also spammed the kernel audit log with a retry
every 500 ms. Binder is the sanctioned cross-app channel, so the built app binds an exported IDE service and
pushes frames through a `oneway` transaction. `bindService` is a single async call (no retry spin), so a
missing/blocked IDE just leaves the bridge dark — no spam.

## The injected runtime (`:applog-runtime`)

A tiny, dependency-free Java jar (`IdeLogBridgeProvider` + `IdeLogBridge`), compiled against a stub
`android.jar` (`com.google.android:android`, `compileOnly`), so it links nothing at runtime and keeps building
with no Android SDK (CI-safe). It is bundled as an asset in `:ide-android` and dexed into a user's debug app.

- **`IdeLogBridgeProvider`** — a no-op `ContentProvider`. Its `onCreate` runs before `Application.onCreate`
  (the androidx-startup / Firebase auto-init pattern), so no user code or `Application` subclass is required.
- **`IdeLogBridge`** — resolves the IDE's exported sink service by the `dev.ide.applog.SINK` intent
  (`PackageManager.resolveService`), `bindService`s it, and pushes batches of log frames over Binder
  (`TXN_SUBMIT`, a `oneway` transaction whose Parcel carries a `String[]` of wire payloads). Sources, in order
  of richness: (1) `logcat --pid=<self>` (all `android.util.Log.*`; best-effort — SELinux may block exec on
  some devices), (2) `System.out`/`System.err` (tee'd, so `println` survives even without logcat), (3) uncaught
  exceptions (then delegates to the previous handler so the app still crashes normally). logcat's own mirror of
  `System.out`/`System.err`/`AndroidRuntime` is filtered out to avoid duplicates. A bounded queue (drop-oldest)
  guarantees the bridge can never OOM or block the host app; the sender waits (bounded) for the binding and
  gives up quietly if the IDE never binds (`BIND_AUTO_CREATE` re-delivers the binder after a transient drop).

## Build injection (`:android-support`)

On a **debuggable, non-minified** build (`AndroidBuildSystem.appLogRuntime` supplied and the "Forward app logs"
setting on — evaluated per build graph), `appendApp` registers `injectAppLogProvider` between `processManifest`
and `aapt2Link`. It splices into a separate *instrumented* manifest (the plain merged manifest is left intact
for the preview relink):

1. a `<provider android:name=… android:authorities="<applicationId>.dev.ide.applog">` (boots the bridge), and
2. a `<queries><intent><action android:name="dev.ide.applog.SINK"/></intent></queries>` so the bridge can SEE
   and bind the IDE's exported service under Android 11+ package visibility (without it, `resolveService`
   returns null on API 30+ and the bridge stays dark).

It also adds the runtime jar to the external dex scope, so it dexes through the normal D8/merge/multidex path
(content-hashed → dexed once) for every `minSdk`.

Release / minified builds are never touched — no manifest change, no extra classes. Verified by
`AppLogInjectTest` (SDK-gated): a debug APK carries the provider + the SINK `<queries>` in its manifest + the
runtime in its dex; a release APK carries none of it.

## Wire payload (`AppLogWire`, `:ide-core`)

Each frame is a tab-separated string with a leading kind byte:

```
H \t <protocolVersion> \t <packageName> \t <pid> \t <token>              (one HELLO per connection)
L \t <timestampMs> \t <pid> \t <tid> \t <level> \t <tag> \t <message>    (each LOG record)
```

The message is everything after the sixth tab (may contain tabs/newlines — stack traces). Over Binder the
frames are carried as a `String[]` in the submit transaction (no length-prefix framing). The writer half is in
`:applog-runtime` (`IdeLogBridge`); the reader half (`AppLogWire.parse`) is pure and unit-tested
(`AppLogWireTest`, which still covers the length-prefixed `readFrame` round-trip). Keep the two in sync — the
transport constants (`SINK_ACTION`/`BINDER_DESCRIPTOR`/`TXN_SUBMIT`) live in `AppLogWire` and are duplicated in
`IdeLogBridge`.

## Receiver + UI

- **`AppLogSinkService`** (`:ide-android`) — the exported Binder service resolvable by `SINK_ACTION`. It has no
  `android:process`, so it ALWAYS runs in the UI (main) process (the built app resolves + binds it there). Its
  `onTransact(TXN_SUBMIT)` reads the `String[]` and feeds it DIRECTLY to the UI-process `AppLogChannelImpl`
  (`AppLogSinkRegistry.active`). `onUnbind` marks the stream disconnected. There is no cross-process relay — see
  "Capture is device-global" below.
- **`AppLogChannel`** platform port (`:ide-core`), supplied by `AppLogChannelImpl`, whose `acceptFrames` decodes
  each payload with `AppLogWire.parse` and publishes a ring-buffered `StateFlow<AppLogSnapshot>` (coalesced
  ~10/s). It accepts a HELLO from any applicationId in its **watch set** (`watch(pkgs)`; the open project's
  Android app applicationIds across all variants, computed by `AndroidVariants.candidateApplicationIds`), which
  the engine sets on project open + every config change. A HELLO from a NEW app process (fresh pid) starts a
  fresh session (clears the buffer); a same-pid reconnect keeps it. Records are gated on the session pid.
  `AppLogSnapshot.totalAppended` is a monotonic counter for computing new lines after the ring buffer trims.
- **UI:** a fourth `Logcat` tab in `BuildConsole` (level filter, tag/text search, connection pill, clear,
  tailing) fed by `BuildService.appLog: StateFlow<AppLogUi>` (both build-runners expose the SAME UI-process
  channel, so the tab is identical whether the build ran in-process or in the `:build` daemon).
- **Capture is device-global + decoupled from the Run button.** Capture used to start only in the `androidRun`
  task (`start(pkg=namespace)`), so logs appeared ONLY when you launched from the IDE, in that IDE session, and
  ONLY when the app's applicationId equalled its namespace. Now the engine `watch()`es the project's app IDs on
  open, and the sink feeds the UI channel directly. So logs show whether the app is launched from the IDE's Run
  button OR straight from the **device launcher**, and for apps with an `applicationIdSuffix`/flavor
  `applicationId` (the match is on the effective applicationId, `AndroidVariants.applicationId`, which is also
  what the run installs + launches by). Because the sink and the channel are both in the UI process, build-
  process isolation no longer matters here — the old `UiAppLogRelay` + the daemon's app-log streaming are gone.
- **`Log.*` on strict-SELinux devices.** The bridge captures `android.util.Log.*` by exec'ing `logcat` (own-PID,
  no permission). Where SELinux blocks that exec, the bridge forwards a one-off note (tag `IdeLogBridge`) so an
  otherwise-empty tab isn't read as a total failure — `System.out`/`System.err` (println) and crashes still forward.

## Setting

"Forward app logs" on the **Build Runtime** settings page (`BuiltInSettingsPages.INJECT_APP_LOG`), default on,
read per build (a toggle applies on the next build, no restart).

## Security note / follow-up

The sink service is exported with no permission (the built app is signed with a different key, so a signature
permission can't gate it), so any app could bind it. Acceptable for a debug-only developer tool: the channel
drops every frame whose HELLO package isn't in the open project's app-ID watch set, so a stray bind from an
unrelated app contributes nothing. The HELLO frame already carries a `token` field (currently empty) for a
per-run token to be minted by the IDE and validated on submit — the intended hardening.
