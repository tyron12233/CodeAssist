plugins {
    alias(libs.plugins.kotlin.jvm)
}

// index-api — the SPI for the on-device indexing subsystem.
// Declares IndexExtension/IndexService/IndexInput and the platform.index extension point. Depends on
// language-api so an index input can expose a parsed DOM; carries no engine logic.
dependencies {
    implementation(project(":language-api"))
}
