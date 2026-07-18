# App-log forwarding (the Logcat tab)

The IDE shows a running **debug** app's logs live, Android-Studio-Logcat style, in a console tab. Because the
built app is a *separate app and OS process* from the IDE, the IDE cannot read its logs from the outside (on a
non-rooted device `READ_LOGS` only ever returns the caller's own process's logs). So the logger runs *inside*
the built app and opens a channel back to the IDE.

```
Built debug APK (its own process)              IDE (:ide-android)
┌───────────────────────────────┐             ┌──────────────────────────────┐
│ <provider IdeLogBridgeProvider>│  abstract   │ AppLogChannelImpl            │
│   onCreate() → IdeLogBridge:   │  LocalSocket│   LocalServerSocket           │
│    • logcat --pid=self ────────┼────────────►│   "dev.ide.codeassist.applog" │
│    • System.out/err tee        │  (no perm)  │   accept → AppLogWire.parse   │
│    • uncaught-exception hook   │             │   → StateFlow<AppLogSnapshot> │
└───────────────────────────────┘             │        │                      │
   from bundled applog-runtime.jar,           │  BuildService.appLog          │
   injected into DEBUG builds only            │  → BuildConsole "Logcat" tab  │
                                              └──────────────────────────────┘
```

On-device only — the desktop launcher has no install/launch step, so there is nothing to attach to.

## The injected runtime (`:applog-runtime`)

A tiny, dependency-free Java jar (`IdeLogBridgeProvider` + `IdeLogBridge`), compiled against a stub
`android.jar` (`com.google.android:android`, `compileOnly`), so it links nothing at runtime and keeps building
with no Android SDK (CI-safe). It is bundled as an asset in `:ide-android` and dexed into a user's debug app.

- **`IdeLogBridgeProvider`** — a no-op `ContentProvider`. Its `onCreate` runs before `Application.onCreate`
  (the androidx-startup / Firebase auto-init pattern), so no user code or `Application` subclass is required.
- **`IdeLogBridge`** — connects a `LocalSocket` (abstract namespace, no permission) and forwards, in order of
  richness: (1) `logcat --pid=<self>` (all `android.util.Log.*`; best-effort — SELinux may block exec on some
  devices), (2) `System.out`/`System.err` (tee'd, so `println` survives even without logcat), (3) uncaught
  exceptions (then delegates to the previous handler so the app still crashes normally). logcat's own mirror of
  `System.out`/`System.err`/`AndroidRuntime` is filtered out to avoid duplicates. A bounded queue (drop-oldest)
  guarantees the bridge can never OOM or block the host app; a background thread reconnects if the IDE isn't
  listening yet.

## Build injection (`:android-support`)

On a **debuggable, non-minified** build (`AndroidBuildSystem.appLogRuntime` supplied and the "Forward app logs"
setting on — evaluated per build graph), `appendApp`:

1. registers `injectAppLogProvider` between `processManifest` and `aapt2Link` — it splices a
   `<provider android:name=… android:authorities="<applicationId>.dev.ide.applog">` into a separate
   *instrumented* manifest that `aapt2 link` consumes (the plain merged manifest is left intact for the preview
   relink); and
2. adds the runtime jar to the external dex scope, so it dexes through the normal D8/merge/multidex path
   (content-hashed → dexed once) for every `minSdk`.

Release / minified builds are never touched — no manifest change, no extra classes. Verified by
`AppLogInjectTest` (SDK-gated): a debug APK carries the provider in its manifest + dex; a release APK carries
neither.

## Wire protocol (`AppLogWire`, `:ide-core`)

Length-prefixed frames — a 4-byte big-endian length, then that many UTF-8 bytes. The payload is tab-separated
with a leading kind byte:

```
H \t <protocolVersion> \t <packageName> \t <pid> \t <token>              (one HELLO on connect)
L \t <timestampMs> \t <pid> \t <tid> \t <level> \t <tag> \t <message>    (each LOG record)
```

The message is everything after the sixth tab (may contain tabs/newlines — stack traces). The writer half is
in `:applog-runtime` (`IdeLogBridge`); the reader half (`AppLogWire.readFrame`/`parse`) is pure and
unit-tested (`AppLogWireTest`). Keep the two in sync.

## Receiver + UI

- **`AppLogChannel`** platform port (`:ide-core`), supplied by `:ide-android`'s `AppLogChannelImpl`, which hosts
  the `LocalServerSocket`, decodes frames, and publishes a ring-buffered `StateFlow<AppLogSnapshot>` (coalesced
  ~10/s). Only the connection whose HELLO package matches the last-launched app (`start(pkg)`, called just
  before install/launch) contributes. `AppLogSnapshot.totalAppended` is a monotonic counter that lets a
  cross-process consumer compute new lines even after the ring buffer trims.
- **UI:** a fourth `Logcat` tab in `BuildConsole` (level filter, tag/text search, connection pill, clear,
  tailing) fed by `BuildService.appLog: StateFlow<AppLogUi>`.
- **Build-process isolation:** when the build/run runs in the `:build` daemon (the default), the socket is
  hosted there and lines stream to the UI as `IBuildCallback.onAppLog`/`onAppLogState` deltas, reassembled in
  `RemoteBuildRunner` (mirrors the run-console streaming). In-process (isolation off), `BuildService.appLog`
  maps the snapshot directly.

## Setting

"Forward app logs" on the **Build Runtime** settings page (`BuiltInSettingsPages.INJECT_APP_LOG`), default on,
read per build (a toggle applies on the next build, no restart).

## Security note / follow-up

The abstract socket name is fixed and device-visible, so any app on the device could connect (or squat the
name). Acceptable for a debug-only developer tool. The HELLO frame already carries a `token` field (currently
empty) for a per-run token to be minted by the IDE and validated on connect — the intended hardening.
