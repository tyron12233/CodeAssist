# Usage analytics

CodeAssist collects **opt-in, anonymous performance metrics** to see what's slow and what's failing (build,
indexing, and completion timings; crashes). It is **performance-only** — no feature-usage tracking. It is
off by default; nothing is collected until the user explicitly taps **Allow** on the first-launch consent
prompt, and it can be turned off again anytime from the project picker.

This document is the source of truth for **what we collect**, **what we never collect**, and the
**backend setup** (Supabase).

## Architecture

```
ide-ui (consent sheet + toggle, backend.track(...))
   │  (IdeBackend port: analyticsAvailable / analyticsConsent / setAnalyticsConsent / track)
ide-core  IdeServicesBackend ── persists consent in prefs.properties ── gates ──► AnalyticsService
                                                                                      │
analytics-api  AnalyticsService / AnalyticsSink / AnalyticsEvent / DeviceInfo (SPI)   │
analytics-impl DefaultAnalyticsService (durable batch buffer) ──► SupabaseSink ──HTTP──► Supabase
               CrashReporter (scrubbed uncaught-exception handler)
```

- **Consent** is a single preference (`analytics.consent` = `granted` / `denied`; absent = undecided →
  prompt). Persisted in the same `prefs.properties` the onboarding flag uses.
- The **install id** (`analytics.install.id`) is a random UUID generated once. It is **not** tied to any
  account, ad id, or device id. The **session id** is fresh per launch.
- Collection is **Android only** — the desktop launcher wires the no-op service (the desktop is a
  dev/demo harness; collecting from it would pollute the data). `analyticsAvailable()` is false there, so
  no prompt and no toggle appear.
- The transport is behind the `AnalyticsSink` interface; Supabase is just the default implementation, so
  it can be swapped without touching call sites.

## What we collect

We collect **performance metrics only** — no feature-usage tracking (nothing about which screens, files,
or features you open). Every shipped row carries the install id, session id, and the device/environment
context as columns: app version + build, OS API level, device model + manufacturer, CPU ABI, and locale —
so a timing can be attributed to an app version / device.

Events (the `event` column), by category:

| Category      | Events (props in parens) |
|---------------|--------|
| `performance` | `cold_start` (`duration_ms` — full on-device bootstrap, once per launch; also the per-launch anchor), `index_perf` (`duration_ms` + `heap_*_mb` — one row per finished full index pass; **plus** `trigger` (why it ran: `open`/`reindex`/`model`/`libraries`/`variant`/`files`/`module`), `scope_ms` (assembling the scope) and `queued_ms` (waiting for an earlier pass), `lib_changed` (`0` for a quiet pass: the classpath was unchanged, so only the source side was updated and no build was announced), `coalesced` (resync requests that joined this pass), the phase split `lib_ms`/`src_ms`, the cache-effectiveness counts `artifacts`/`artifacts_built`/`artifacts_reused`, the parallelism `par_io`/`par_build`, rebuilt-artifact counts by coarse kind `built.<kind>` and `rekeyed.<kind>` (rebuilt under a new key this session; kinds are `jdk`/`bundled`/`sources`/`jar.project_build`/`jar.project`/`jar.cache`/`jar.other`/`res.project`/`res.cache`/`res.other`, never a name or path), the source-diff counts `src_files`/`src_parsed`, and a per-indexer breakdown `idx.<index-id>.ms` for each index that cost ≥1ms — e.g. `idx.java.classNames.ms`, `idx.android.resources.ms` — where the id is a fixed index-kind name, never a file/project/package name, and the time is summed across the threads that ran it. From the release after 3.26.2 every pass is counted; before, passes that finished between two samples of the status flow were missed), `build_result` (`ok`, `duration_ms`, `steps`, `peak_heap_mb`/`min_headroom_mb`/`heap_max_mb`, and on failure `failure_kind` = `compile`/`resource`/`tool`/`oom`/`no_diagnostic` — per build/run), `completion_perf` / `analysis_perf` (`count`, `mean_ms`, `p50_ms`, `p95_ms`, `max_ms`, plus the window's peak `heap_used_mb`/`heap_max_mb`/`heap_headroom_mb`), `frame_perf` / `frame_cpu_perf` (same summary shape, plus `over_count`; see below) |
| `crash`       | `app_crash` (scrubbed — see below; also carries the crash-time `heap_*_mb` and always pins at least one `dev.ide.*` frame, walking the cause chain if the top of the stack is all framework code), `error_logged` (a caught ERROR-level failure, scrubbed + throttled) |

`frame_perf` and `frame_cpu_perf` summarize drawn frames, counted only in bursts (a frame drawn within
100ms of the one before it) so that typing, scrolling and animation are measured and the idle caret blink is
not. `frame_perf` is the whole frame, vsync to done, which is what the user feels; `frame_cpu_perf` is the
same frame minus the wait for the display, which is the part this app is responsible for and the one to read
when asking whether a change made the IDE slower.

`over_count` is **frames that missed their deadline**, from `FrameMetrics.DEADLINE` (API 31+; older devices
fall back to one vsync interval). It is not "frames longer than one vsync". A frame can exceed the refresh
interval and still be perfectly smooth, because its total includes blocking in `swapBuffers` for a free
buffer, which is ordinary pipelining. Readings taken before 3.23 used the vsync interval and are therefore
**not comparable**: on a two-core emulator that older test scored a smooth editor scroll 96-100% janky where
the deadline scored it 2-12%, and it was strictest on the fastest panels, since a 120Hz device was being held
to 8ms. Rows from those releases also contain occasional nonsense frame times near `Long.MAX_VALUE`
nanoseconds, which one sample is enough to poison a window with; those are now dropped at the source.

`failure_kind` categorizes a failed build so the ~50% "failure" rate is actionable: a compiler/resource
error is the user's own code (the expected inner loop), whereas `tool`/`no_diagnostic` point at our pipeline
and `oom` at memory pressure. It is a fixed bucket name — never a message, file name, or user content.

`completion_perf` and `analysis_perf` are **aggregated**, not one event per keystroke: latencies are
accumulated client-side and emitted as a single summary every 50 samples (and on shutdown), so a
per-keystroke metric costs ~one analytics row per window instead of thousands. Each window also reports the
worst heap reading taken across its samples, which gives a heap series through ordinary editing sessions:
a crash reports only the heap at the instant it died, always at the ceiling, so it cannot tell a slow climb
apart from one large allocation. Free-form per-event detail goes in the `props` JSONB column and is
**string-only** — never user content.

A native crash (`app_crash` with `kind` = `native`) is a separate path, because a `SIGSEGV` kills the process
below the JVM and no exception is ever thrown. On the next launch the app asks the OS why the previous
process died (`ActivityManager.getHistoricalProcessExitReasons`, API 30+), reports each record once, and
ignores records older than a day so an update cannot re-attribute old history to the new version. Two
sources are combined:

- the engine breadcrumb (`engine_lane`, `thread`, `index_building`, `since_op_ms`), a file naming the editor
  op the engine most recently *started*. It records starts only, so `since_op_ms` (the gap to the process
  death) is what says whether that op was still running or had finished long before.
- the OS tombstone (`arch`, `signal`, `signal_code`, `fault_addr`, `fault_thread`, `cause`, `abort_msg`,
  `native_frames`, `uptime_s`), parsed from `ApplicationExitInfo.getTraceInputStream()`. This names the
  thread that actually faulted, which the breadcrumb cannot: it distinguishes a fault inside the editor
  engine from one on a render, GC or JIT thread. `native_frames` holds native symbols with the binary they
  came from reduced to a basename (`libart.so!art::…`), so no install path is reported, and the parser
  applies the same reduction to any path in `abort_msg`. Everything else about the tombstone (register and
  memory dumps, memory maps, log buffers, open file descriptors) is discarded unparsed. `process` says
  which of our processes died: the IDE, or one of the `build` / `preview` children.

One class of record is deliberately dropped rather than reported: the death of a command-line VM the app
itself forked to run a build tool (`fault_thread` is a `dalvikvm` launcher). To find the heap a device grants
R8 and D8, the app forks VMs at rising `-Xmx` values, and ART answers a heap it cannot reserve by aborting
during runtime init rather than by exiting — an abort the OS files under this package. Those forks are a
capability probe that self-falls back to in-process tools, so counting them as crashes makes a healthy device
look broken. The ladder is also bounded by device RAM up front (`ForkedToolVm`), since ART reserves twice
`-Xmx` of address space, so most devices never reach a rung that aborts at all.

## What we never collect

A hard line, enforced at the call sites (not just by policy):

- **No source code** or file contents.
- **No file, project, or package names**, and **no absolute paths**.
- **No PII**: no email/account, no ad id, no IMEI/serial, no precise location, no clipboard, no keystrokes.
- **Crash reports are scrubbed**: only the exception *type* chain (never `getMessage()`), the crashing
  thread name, and stack frames from our own `dev.ide.*` packages formatted `Class.method:line`. Foreign
  (JDK/third-party) frames are collapsed to a count. No paths, no messages. See `CrashReporter`.

## Supabase setup

The client POSTs batched events to **PostgREST** using the project's **publishable** key (`sb_publishable_…`),
baked into the app as a `BuildConfig` field. That key is safe to ship in an open-source client **only
because Row-Level Security on the table allows INSERT and nothing else** — so the SQL below is required
hardening, not optional. Make sure every other table in the project also has RLS enabled.

```sql
-- 1. The events table.
create table public.events (
  id                   bigint generated always as identity primary key,
  received_at          timestamptz not null default now(),
  install_id           uuid not null,
  session_id           uuid not null,
  event                text not null,
  category             text not null,
  app_version          text,
  app_build            int,
  os_api               int,
  device_model         text,
  device_manufacturer  text,
  abi                  text,
  locale               text,
  props                jsonb not null default '{}'
);

create index events_received_at_idx on public.events (received_at);
create index events_event_idx       on public.events (event);

-- 2. RLS: the publishable/anon key may INSERT only. No select / update / delete.
alter table public.events enable row level security;

create policy "anon_insert_only" on public.events
  for insert to anon
  with check (true);
```

### Rollup + prune (stay under the free-tier 500 MB)

Raw events are kept for **7 whole days**; `events_daily` (per day, event, category and app version: distinct
installs, hits, and duration p50/p95/max) keeps the long-term picture. A daily `pg_cron` job,
`events-rollup` (`17 3 * * *`, `select public.roll_up_events()`), does both. The live definition is
`supabase/migrations/20261013000100_analytics_retention.sql`:

- It aggregates only days not rolled up yet, plus always the last two completed days. `received_at` is the
  server's clock, so a day is final once it ends. Missed runs are caught up by the next one, as far back as raw
  rows exist.
- It deletes raw rows older than 7 days **on a day boundary**, so the oldest raw day left is always whole.

Why it is shaped this way (2026-10-13): the first version re-aggregated every raw row on every run. Its cost
grew with the table until it hit the database's 2-minute statement timeout, and because the prune shares the
function, the failed rollup stopped deletions too; `events` reached 414 MB. It also cut raw rows at the run's
time of day, so the next run re-aggregated a partial oldest day over its complete rollup.

Deleting rows does not shrink the table on disk; after a large one-off delete, `vacuum full analyze
public.events` gives the space back (it locks the table for ~30 s; clients retry from their queue).

## Rotating the endpoint / key

Build with `-PSUPABASE_URL=… -PSUPABASE_KEY=…` (or the `SUPABASE_URL` / `SUPABASE_KEY` env vars) to
override the baked-in defaults. An empty URL ships the app with analytics inert (no-op service).
