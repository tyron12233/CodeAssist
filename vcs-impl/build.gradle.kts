plugins {
    alias(libs.plugins.kotlin.jvm)
}

// vcs-impl — the engine behind vcs-api. JGit drives the working copy (status, staging, commit, branches,
// diff, stash, fetch/pull/push, clone); an OkHttp client drives GitHub (device-flow sign-in, repositories,
// pull requests); and the account store keeps tokens outside any project. One implementation for both
// hosts: plain JVM on desktop, dexed like the other in-process tools on ART.
dependencies {
    api(project(":vcs-api"))
    implementation(project(":platform-core"))
    implementation(libs.jgit)
    implementation(libs.okhttp)
    // GitHub responses are read through the JSON tree API, so no @Serializable types or compiler plugin.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}
