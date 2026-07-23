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

    // The Compose @Preview render surface (the interpreter's render half): on desktop it drives Compose for
    // Desktop, so a @Preview using standard material/foundation composables renders live (the same bridge
    // :ide-android uses on device). Re-exports :interp-core's ResolvedFunction, which the host consumes.
    implementation(project(":interp-compose"))

    // Project-resource resolution for the preview (`stringResource(R.string.x)`/`colorResource`/…) — the merged
    // resource repository + aapt-shaped R ids (:android-support) and the value engine (:layout-preview-impl),
    // wired into a DesktopPreviewResources. Both are pure-JVM and already transitive runtime deps via :ide-core;
    // added directly for compile visibility.
    implementation(project(":android-support"))
    implementation(project(":layout-preview-impl"))

    implementation(compose.desktop.currentOs)
    // material3 for the preview host's progress/empty-state chrome (compose.desktop.currentOs bundles M2;
    // :ide-ui keeps material3 as a non-transitive `implementation`).
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing) // Dispatchers.Main for the Compose/AWT thread

    // bundletool builds the .aab in-process (android-support keeps it compileOnly). The desktop launcher
    // bundles it so the `bundle<Variant>` task works; pure Java, so it just runs on the JVM.
    implementation(libs.android.bundletool)
    // Bouncy Castle: in-process keystore creation (android-support keeps it compileOnly) — same code path on
    // desktop and device, so the desktop launcher bundles it too.
    implementation(libs.bouncycastle.pkix)
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
