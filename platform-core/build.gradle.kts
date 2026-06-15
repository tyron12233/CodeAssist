plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// platform-core depends on no other module (no domain knowledge). Coroutines back the activity
// engine / dispatchers and are an internal implementation detail — not exposed in the public API —
// so they are `implementation`, not `api`.
dependencies {
    implementation(libs.kotlinx.coroutines.core)
}
