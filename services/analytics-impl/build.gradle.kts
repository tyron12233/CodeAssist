plugins {
    alias(libs.plugins.kotlin.jvm)
}

// analytics-impl — the engine behind analytics-api.
//
// DefaultAnalyticsService: a bounded, disk-backed event buffer with a daemon flush thread, gated on
// consent (every entry point no-ops when disabled; revoking consent drops the buffer). SupabaseSink:
// the default transport — POSTs batched rows to Supabase PostgREST over java.net.HttpURLConnection (the
// same stdlib-only path the dependency resolver uses; present on JVM and ART, no extra dependency).
// CrashReporter: a scrubbed uncaught-exception handler (dev.ide.* frames only; no messages, no paths).
// Pure stdlib — the host wires it into ide-core's IdeServicesBackend.
dependencies {
    implementation(project(":analytics-api"))
    // The logging facade — AnalyticsLogSink is a LogSink that forwards ERROR records to analytics.
    implementation(project(":platform-core"))
}
