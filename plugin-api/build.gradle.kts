plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// plugin-api — the SPI for UI-contributed plugin features.
//
// The lean action model (IdeAction / ActionGroup + named places) and the data-driven UI extension points,
// so a toolbar button, a menu item, or a command-palette command is a registration against an extension
// point rather than an edit to the host UI. Pure Kotlin: no Compose, no engine, no project model. The
// engine side (ide-core) registers built-ins here; the Compose UI renders them through neutral DTOs over
// the IdeBackend port, exactly like the settings framework.
dependencies {
    // ExtensionPoint/ExtensionRegistry, PluginId, Disposable — the EPs and the ids are part of the SPI.
    api(project(":platform-core"))

    // IdeAction.perform is suspend; tests drive it with runBlocking.
    testImplementation(libs.kotlinx.coroutines.core)
}
