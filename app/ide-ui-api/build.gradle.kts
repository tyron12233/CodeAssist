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

    // iOS. The DTOs and the `IdeBackend` port are platform-neutral by contract (paths are opaque strings),
    // so the iOS target compiles commonMain unchanged.
    iosSimulatorArm64()
    iosArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            // The published UI facet a plugin implements, and the bridge from it onto the contribution model
            // above (ExternalUiPlugin.kt). Common, since a built-in UI plugin is mounted on iOS too.
            api(project(":plugin-ui-api"))
        }

        // An intermediate JVM-only source set shared by the desktop + android targets (the same pattern
        // :ide-ui uses), for what only a JVM host has.
        val jvmShared = create("jvmShared") {
            dependsOn(getByName("commonMain"))
        }
        getByName("desktopMain").dependsOn(jvmShared)
        getByName("androidMain").dependsOn(jvmShared)

        // `iosMain` is declared rather than inherited: the explicit `dependsOn` edges above turn off the
        // default hierarchy template, so no intermediate apple/ios set is created for us. It holds this module's iOS
        // actuals for both the simulator and the device.
        val iosMain = create("iosMain") { dependsOn(getByName("commonMain")) }
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        getByName("iosArm64Main").dependsOn(iosMain)
    }
}
