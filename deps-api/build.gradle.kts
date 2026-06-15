plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// deps-api -> project-model-api, platform-core. Coordinate (model) and ProgressReporter (platform)
// appear in the DependencyResolver SPI, so both are `api`.
dependencies {
    api(project(":project-model-api"))
    api(project(":platform-core"))
}
