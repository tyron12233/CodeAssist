plugins {
    alias(libs.plugins.kotlin.jvm)
}

// analytics-api — the contract for opt-in usage analytics.
//
// A tiny, dependency-free SPI: the event model, the device/environment payload, and two ports —
// AnalyticsService (record + ship, gated on consent) and AnalyticsSink (the transport). The engine
// (ide-core) and hosts talk to these types only; the batching/queue/HTTP live in analytics-impl, and
// the concrete transport (Supabase) is just one AnalyticsSink, so it can be swapped without touching
// call sites. Carries NO collection logic and never sees user content — see docs/analytics.md.
dependencies {
}
