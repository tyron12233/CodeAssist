plugins {
    alias(libs.plugins.kotlin.jvm)
    // Published for plugin authors to compile against; see the convention plugin for the coordinate.
    id("dev.ide.spi-publish")
}

// index-api — the SPI for the on-device indexing subsystem.
// Declares IndexExtension/IndexService/IndexInput and the platform.index extension point. Depends on
// language-api so an index input can expose a parsed DOM; carries no engine logic.
dependencies {
    // `api`, not `implementation`: IndexInput.dom() returns a language-api ParsedFile, so the type is part
    // of this module's public signature. As `implementation` it reached the published POM at runtime scope,
    // which compiles inside this repository (one classpath) but leaves an external consumer unable to
    // implement the interface without declaring language-api itself.
    api(project(":language-api"))
}
