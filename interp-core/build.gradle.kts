plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// interp-core — the on-device Kotlin interpreter (see docs/compose-interpreter.md). It tree-walks the
// ResolvedTree that :lang-kotlin's KotlinTreeResolver produces (the sound resolver→interpreter contract),
// so a project's Kotlin can run interpreted — no compile, no dex. This is step 3: a plain-Kotlin
// interpreter (values, environment, control flow, intrinsic operators, source-function calls); the Compose
// bridge (interp-compose) layers on top later.
dependencies {
    // ResolvedTree + KotlinTreeResolver + the symbol service live in :lang-kotlin and appear in this
    // module's public API (Interpreter consumes ResolvedFunction), so `api`, not `implementation`.
    api(project(":lang-kotlin"))

    // The interpreter itself is PSI-free (it only walks ResolvedTree). The tests, however, drive lowering by
    // parsing Kotlin source, which needs the compiler's PSI — a test-only dependency (it's `implementation`
    // in :lang-kotlin, so it doesn't leak here).
    testImplementation(libs.kotlin.compiler.embeddable)
}
