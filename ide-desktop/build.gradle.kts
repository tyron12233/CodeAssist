import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Desktop launcher: a JVM Compose application that wires the framework together. It initializes
// platform-core, opens a project through project-model-impl, drives analysis/completion through
// lang-jdt (all via the UI-agnostic IdeServices facade), and renders the reusable Compose UI from
// :ide-ui — implementing :ide-ui's IdeBackend port over IdeServices. The Swing layout is gone.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

dependencies {
    // The shared backend + UI port (ide-core api-exposes :ide-ui). The desktop launcher adds only its
    // Compose-window main() on top. The framework impls come transitively from :ide-core.
    implementation(project(":ide-core"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing) // Dispatchers.Main for the Compose/AWT thread
}

compose.desktop {
    application {
        mainClass = "dev.ide.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "CodeAssist"
            packageVersion = "1.0.0"
        }
    }
}
