plugins {
    alias(libs.plugins.kotlin.jvm)
}

// zipcli-plugin — the CodePrimer side of the ZipCLI feature: the engine Plugin (identity + enable/disable) and
// the `zipcli` agent tool that runs ZipCLI scripts over the open project. The command engine itself is
// :zipcli-core, which stays free of IDE types so ZipCLI updates can be re-synced without touching this module.
dependencies {
    api(project(":zipcli-core"))
    implementation(project(":plugin-api"))
    implementation(project(":platform-core"))
    implementation(project(":agent-api"))
    implementation(libs.kotlinx.coroutines.core)
}
