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
| `performance` | `cold_start` (`duration_ms` — full on-device bootstrap, once per launch; also the per-launch anchor), `index_perf` (`duration_ms` — per index build/reindex), `build_result` (`ok`, `duration_ms`, `steps` — per build/run), `completion_perf` / `analysis_perf` (`count`, `mean_ms`, `p50_ms`, `p95_ms`, `max_ms`) |
| `crash`       | `app_crash` (scrubbed — see below) |

`completion_perf` and `analysis_perf` are **aggregated**, not one event per keystroke: latencies are
accumulated client-side and emitted as a single summary every 50 samples (and on shutdown), so a
per-keystroke metric costs ~one analytics row per window instead of thousands. Free-form per-event detail
goes in the `props` JSONB column and is **string-only** — never user content.

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

Aggregate raw events into a daily table and delete the raw rows older than 30 days. Run daily via
`pg_cron` (or a scheduled Edge Function).

```sql
create table if not exists public.events_daily (
  day      date not null,
  event    text not null,
  category text not null,
  installs int not null,   -- distinct installs that fired it
  hits     int not null,   -- total occurrences
  primary key (day, event, category)
);

create or replace function public.roll_up_events() returns void language sql as $$
  insert into public.events_daily (day, event, category, installs, hits)
  select date_trunc('day', received_at)::date, event, category,
         count(distinct install_id), count(*)
  from public.events
  where received_at < date_trunc('day', now())
  group by 1, 2, 3
  on conflict (day, event, category)
  do update set installs = excluded.installs, hits = excluded.hits;

  delete from public.events where received_at < now() - interval '30 days';
$$;

-- with pg_cron:
-- select cron.schedule('events-rollup', '17 3 * * *', $$select public.roll_up_events()$$);
```

## Rotating the endpoint / key

Build with `-PANALYTICS_URL=… -PANALYTICS_KEY=…` (or the `ANALYTICS_URL` / `ANALYTICS_KEY` env vars) to
override the baked-in defaults. An empty URL ships the app with analytics inert (no-op service).
