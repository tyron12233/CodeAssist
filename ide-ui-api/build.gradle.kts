import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ide-ui-api — the neutral contracts the reusable UI (:ide-ui) and its host (:ide-core) share: the `IdeBackend`
// port + its concern-service interfaces + DTOs, and the plugin-facing UI-contribution model (the Compose
// registries + `UiContributionScope`/`UiPlugin`). Split out of :ide-ui so the host depends on the port without
// the whole Compose UI, and so UI contributions are declared against a stable API. Compose Multiplatform
// (desktop + android) like :ide-ui.
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
        namespace = "dev.ide.ui.api"
        compileSdk = 36
        minSdk = 24
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
