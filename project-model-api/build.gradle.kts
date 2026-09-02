plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    // Published for plugin authors to compile against; see the convention plugin for the coordinate.
    id("dev.ide.spi-publish")
}

// project-model-api -> vfs-api, platform-core. Both appear in public signatures (VirtualFile,
// ContentHash, ServiceKey), so both are `api`.
dependencies {
    api(project(":vfs-api"))
    api(project(":platform-core"))
}
