import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The AI agent's Compose UI, as a self-contained plugin module: the chat panel (AgentUiPlugin's RIGHT
// tool window), the provider/key sheet, and the write-permission overlay. It reuses the :ide-ui shell's
// design system (GlassSurface, the Ca theme, Primitives, markup helpers) and talks to the engine only
// through the :ide-ui-api IdeBackend port. ide-core references AgentUiPlugin from here for the unified
// BuiltInPlugins declaration. Mirrors :ide-ui's desktop (JVM) + Android KMP setup; commonMain is shared.
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
        namespace = "dev.ide.agent.ui"
        compileSdk = 36
        minSdk = 24
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // The IDE shell: GlassSurface, the Ca theme, Primitives, and the markdown helpers the chat reuses.
            // `:ide-ui` re-exposes `:ide-ui-api` (IdeBackend + the UI-contribution model) via `api`, so the
            // UiPlugin / ToolWindow / OverlayContribution SPI comes through transitively.
            implementation(project(":ide-ui"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources) // the chat_* strings live in this module's composeResources/
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

// The module's own generated resource accessor for the chat_* strings (migrated here from :ide-ui). Kept
// non-public and pinned to this module's package so it stays a `dev.ide.agent.ui` detail.
compose.resources {
    publicResClass = false
    packageOfResClass = "dev.ide.agent.ui.generated.resources"
    generateResClass = always
}
