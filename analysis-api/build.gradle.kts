plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// analysis-api — the SPI for diagnostics, analyzers, and quick-fixes.
//
// One Diagnostic model + one pipeline: FileAnalyzer/ProjectAnalyzer findings and compiler errors
// (adapted in via a DiagnosticProvider) merge into the same stream the editor underlines + the
// Problems view consume. Everything is expressed over the neutral DOM/resolve (language-api) and the
// index (index-api), so any language backend participates. This module carries NO engine logic —
// the scheduler, per-version cache, suppression checks, and fix application live in analysis-impl.
dependencies {
    // DOM + Severity/TextRange/NodeKind, SourceAnalyzer/LanguageId, DocumentEdit, and (transitively,
    // as language-api re-exports them) Module + VirtualFile + the platform extension framework. All
    // appear in the public SPI, so `api`.
    api(project(":language-api"))
    // IndexService — PROJECT-tier analyzers and many fixes (auto-import) query it.
    api(project(":index-api"))

    // Tests drive the suspend QuickFix.computeEdits path; runBlocking needs the coroutines runtime.
    testImplementation(libs.kotlinx.coroutines.core)
}
