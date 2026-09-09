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
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
