plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// vfs-api -> platform-core. Platform types (ContentHash, Disposable, Topic) appear in vfs-api's
// public signatures, so the dependency is `api` (transitively visible to consumers).
dependencies {
    api(project(":platform-core"))
}
