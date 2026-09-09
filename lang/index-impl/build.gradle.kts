plugins {
    alias(libs.plugins.kotlin.jvm)
}

// index-impl — the pragmatic engine behind index-api: in-memory term dictionary + postings + trigram
// index, per-artifact persistent cache keyed by content hash, prefix/fuzzy queries, incremental source,
// driven as cancellable background activities. Built-in indexes (classNames, packages, sourceSymbols)
// live here; the bytecode-reading `members` index lives in lang-jdt (it needs ecj).
dependencies {
    implementation(project(":index-api"))
    implementation(project(":platform-core"))
    implementation(project(":language-api")) // for ParsedFile in IndexInput
    implementation(libs.kotlinx.coroutines.core)
    // Opt-in regression suites (`regressionTest`): shared benchmark/baseline/memory harness.
    testImplementation(project(":bench-support"))
}
