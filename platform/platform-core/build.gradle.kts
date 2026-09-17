plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    // Published for plugin authors to compile against; see the convention plugin for the coordinate.
    id("dev.ide.spi-publish")
}

// platform-core depends on no other module (no domain knowledge). Coroutines back the activity
// engine / dispatchers and are an internal implementation detail — not exposed in the public API —
// so they are `implementation`, not `api`.
dependencies {
    // ContentHash moved to :model-api so that a VirtualFile, and therefore a Symbol, can be named from
    // common code. `api`, not `implementation`: it is still part of this module's public signature.
    api(project(":model-api"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
