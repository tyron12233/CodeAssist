plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    // Published for plugin authors to compile against; see the convention plugin for the coordinate.
    id("dev.ide.spi-publish")
}

// vfs-api -> platform-core. Platform types (ContentHash, Disposable, Topic) appear in vfs-api's
// public signatures, so the dependency is `api` (transitively visible to consumers).
dependencies {
    api(project(":platform-core"))
}
