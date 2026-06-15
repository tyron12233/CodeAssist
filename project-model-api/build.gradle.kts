plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// project-model-api -> vfs-api, platform-core. Both appear in public signatures (VirtualFile,
// ContentHash, ServiceKey), so both are `api`.
dependencies {
    api(project(":vfs-api"))
    api(project(":platform-core"))
}
