plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    // Published for plugin authors to compile against; see the convention plugin for the coordinate.
    id("dev.ide.spi-publish")
}

// build-api -> project-model-api, vfs-api, platform-core. Model, VFS and platform types appear in
// the BuildSystem / Task SPIs, so all three are `api`.
dependencies {
    api(project(":project-model-api"))
    api(project(":vfs-api"))
    api(project(":platform-core"))
}
