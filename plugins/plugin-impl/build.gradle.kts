plugins {
    alias(libs.plugins.kotlin.jvm)
}

// plugin-impl — the engine behind plugin-api's action model.
//
// ActionManager is the single consumer of UI_ACTION_EP / ACTION_GROUP_EP: it resolves a place into an
// ordered, visibility-filtered list (and, for menus, expands groups into a nested tree), and dispatches an
// action by id. Pure Kotlin; the host (ide-core) constructs one over its ExtensionRegistry and exposes it
// across the UI boundary.
dependencies {
    api(project(":plugin-api"))

    // Tests drive the suspend invoke()/perform() path with runBlocking.
    testImplementation(libs.kotlinx.coroutines.core)
}
