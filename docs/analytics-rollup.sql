-- CodeAssist analytics: daily rollup + prune, scheduled with pg_cron.
-- Idempotent: safe to run repeatedly. Paste into Supabase -> SQL Editor -> Run (needs the postgres role;
-- the sb_secret API key CANNOT run DDL, so this can't go through PostgREST).
--
-- Why: the raw `events` table grows ~6k rows/day and nothing was aggregating or pruning it (events_daily was
-- empty -> the cron had never run). This rolls each day up and deletes raw rows older than 30 days so the
-- project stays under the free-tier 500 MB, while KEEPING the two trends that matter after the prune:
-- per-app_version health and per-event duration percentiles.

-- 1. Rollup table (adds app_version to the key + duration percentiles vs. the original docs/analytics.md shape).
--    A prior events_daily may exist with the OLD 3-column shape (day,event,category,installs,hits) and a
--    3-column primary key. It was never populated (the cron never ran), so we DROP and recreate it with the
--    new columns + 4-column key. Safe because it is empty; if you have manually populated it, migrate instead.
drop table if exists public.events_daily;
create table public.events_daily (
  day          date not null,
  event        text not null,
  category     text not null,
  app_version  text not null default '',
  installs     int  not null,   -- distinct installs that fired it that day
  hits         int  not null,   -- total occurrences
  dur_p50_ms   int,             -- percentiles of props->>'duration_ms' (cold_start/index_perf/build_result)
  dur_p95_ms   int,             -- null for the aggregated *_perf events (their stats live in props)
  dur_max_ms   int,
  primary key (day, event, category, app_version)
);

alter table public.events_daily enable row level security;  -- no anon policy => anon cannot read/write it.

-- 2. Rollup + prune. Aggregates every day strictly before today (so today keeps accumulating), then deletes
--    raw rows older than 30 days. Re-running is safe (upsert on the key).
create or replace function public.roll_up_events() returns void language sql as $$
  insert into public.events_daily
    (day, event, category, app_version, installs, hits, dur_p50_ms, dur_p95_ms, dur_max_ms)
  select
    date_trunc('day', received_at)::date as day,
    event, category, coalesce(app_version, '') as app_version,
    count(distinct install_id) as installs,
    count(*) as hits,
    percentile_cont(0.5) within group (order by nullif(props->>'duration_ms','')::numeric)::int,
    percentile_cont(0.95) within group (order by nullif(props->>'duration_ms','')::numeric)::int,
    max(nullif(props->>'duration_ms','')::numeric)::int
  from public.events
  where received_at < date_trunc('day', now())
  group by 1, 2, 3, 4
  on conflict (day, event, category, app_version) do update set
    installs   = excluded.installs,
    hits       = excluded.hits,
    dur_p50_ms = excluded.dur_p50_ms,
    dur_p95_ms = excluded.dur_p95_ms,
    dur_max_ms = excluded.dur_max_ms;

  delete from public.events where received_at < now() - interval '30 days';
$$;

-- 3. Enable pg_cron and (re)schedule the daily job at 03:17 UTC. Unschedule first so re-running doesn't
--    create duplicate jobs.
create extension if not exists pg_cron;

do $$
begin
  perform cron.unschedule('events-rollup');
exception when others then
  null;  -- job didn't exist yet
end $$;

select cron.schedule('events-rollup', '17 3 * * *', $$select public.roll_up_events()$$);

-- 4. Backfill now so events_daily is populated immediately (also prunes anything > 30 days).
select public.roll_up_events();

-- 5. Verify.
select count(*) as daily_rows, min(day) as first_day, max(day) as last_day from public.events_daily;
select jobname, schedule, active from cron.job where jobname = 'events-rollup';
