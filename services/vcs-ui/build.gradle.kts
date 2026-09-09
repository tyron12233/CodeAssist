import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The version-control Compose UI, as a self-contained plugin module: the Git tool window (changes, staging,
// commit, sync), the branches sheet, the history and diff surfaces, and the sign-in / clone / publish
// screens. It reuses the :ide-ui shell's design system (GlassSurface, the Ca theme, Primitives) and talks to
// the engine only through the :ide-ui-api IdeBackend port. ide-core references VcsUiPlugin from here for the
// unified BuiltInPlugins declaration. Mirrors :agent-ui's desktop (JVM) + Android KMP setup.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    android {
        namespace = "dev.ide.vcs.ui"
        compileSdk = 36
        minSdk = 24
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // The IDE shell: GlassSurface, the Ca theme, Primitives. `:ide-ui` re-exposes `:ide-ui-api`
            // (IdeBackend + the UI-contribution model) via `api`, so the UiPlugin / ToolWindow SPI comes
            // through transitively.
            implementation(project(":ide-ui"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources) // the vcs_* strings live in this module's composeResources/
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

// The module's own generated resource accessor for the vcs_* strings. Kept non-public and pinned to this
// module's package so it stays a `dev.ide.vcs.ui` detail.
compose.resources {
    publicResClass = false
    packageOfResClass = "dev.ide.vcs.ui.generated.resources"
    generateResClass = always
}
