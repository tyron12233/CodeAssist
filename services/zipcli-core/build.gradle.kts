plugins {
    alias(libs.plugins.kotlin.jvm)
}

// zipcli-core — ZipCLI's pure-JVM command engine (parser, script runner, file / archive / search commands),
// mounted over a project directory. Everything under src/main/kotlin/com/zipcli is a verbatim copy of the
// ZipCLI app's platform-free sources (re-sync with tools/sync-zipcli.sh); only src/main/kotlin/dev/ide/zipcli
// is CodePrimer's own glue. No Android, Compose or IDE dependency, so it builds and tests on a plain JVM.
dependencies {
    implementation(libs.kotlinx.coroutines.core)
}
