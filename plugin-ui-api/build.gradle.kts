plugins {
    alias(libs.plugins.kotlin.jvm)
    // The Compose compiler plugin, for the `@Composable` function types in this module's signatures. A
    // composable lambda's ABI (the synthetic `Composer`/`$changed` parameters) is produced by this plugin, so
    // a host that reads one and a plugin that writes one must both be compiled with it, at the same version.
    alias(libs.plugins.kotlin.compose)
    `java-library`
    // Published for plugin authors to compile against; see the convention plugin for the coordinate.
    id("dev.ide.spi-publish")
}

// plugin-ui-api: the UI half of the plugin SPI, i.e. what a plugin shipped as its own app implements to
// contribute Compose-bearing UI (a tool window, a screen, an overlay).
//
// Deliberately narrow, and deliberately NOT :ide-ui-api. That module's contribution model hands a body the
// whole `IdeBackend` port, which is the IDE's largest and fastest-moving surface; publishing it would freeze
// every concern service and DTO in it forever. What a plugin actually needs is its own engine facet (already
// published: it is in the same APK, so the two facets share a classloader and can call each other directly)
// plus a handful of host operations. That handful is `UiContext`, and this module is nothing else.
//
// The Compose runtime is `compileOnly` on purpose: a loaded plugin binds to the IDE's own copy through
// classloader parent delegation, so this artifact must not put a Compose dependency in a consumer's POM:
// an Android plugin would otherwise resolve a desktop artifact. A plugin declares the Compose it compiles
// against itself (the template does this), and the host provides it at runtime.
dependencies {
    compileOnly(libs.compose.runtime.desktop)

    // The Compose compiler plugin is applied to this module, and it refuses to run without the runtime on
    // the class path. `compileOnly` does not reach the test compilation, so the tests need their own copy.
    // Test-scoped, so it stays out of the published POM the way the main one does.
    testImplementation(libs.compose.runtime.desktop)
}
