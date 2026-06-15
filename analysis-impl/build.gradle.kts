plugins {
    alias(libs.plugins.kotlin.jvm)
}

// analysis-impl — the engine behind analysis-api. One edit-driven
// pipeline: it runs FileAnalyzers (shared-traversal node-kind gating) and the compiler (as a
// DiagnosticProvider) over the neutral DOM, applies the active profile, filters inline suppressions
// (@Suppress / // noinspection), and publishes a per-document-version diagnostic set to listeners
// (replace, not append). Quick-fixes apply atomically and re-analyze; a whole-project batch lint reuses
// the same analyzers. Per-tier debounce + cancellation via coroutines. Decoupled from any language
// backend / project model through the AnalysisEnvironment port (the host wires it over lang-jdt etc.).
dependencies {
    implementation(project(":analysis-api"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
