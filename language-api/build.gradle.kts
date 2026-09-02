plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    // Published for plugin authors to compile against; see the convention plugin for the coordinate.
    id("dev.ide.spi-publish")
}

// language-api -> project-model-api, vfs-api, platform-core. ClasspathSnapshot, VirtualFile and
// LanguageLevel appear in the LanguageBackend / CompilationContext SPIs, so all three are `api`.
dependencies {
    api(project(":project-model-api"))
    api(project(":vfs-api"))
    api(project(":platform-core"))
}
