plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Test-only support library for the opt-in regression suites (the `regressionTest` task). It provides:
//   - Bench         : a micro-benchmark harness (steady-state ns/op + HotSpot per-thread alloc/op)
//   - MemoryProbe   : gc-settle + used/peak heap sampling, for retained-footprint and leak checks
//   - RegressionSuite/FlatJson : committed baselines (flat JSON) with per-metric threshold gating
//   - CompletionScore : the completion-quality scorer (recall / top-1 / top-5 / MRR over a corpus)
//
// Consumed ONLY via `testImplementation` (see lang-jdt / index-impl) — it is not on any module's runtime
// classpath and depends on nothing but the Kotlin stdlib, so any module's tests can reuse it.
