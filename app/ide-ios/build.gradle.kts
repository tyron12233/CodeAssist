// ide-ios — the iOS shell. The third host beside :ide-desktop and :ide-android: it builds an `IdeBackend`,
// hands it to `CodeAssistApp`, and wraps the result in the `UIViewController` an Xcode app loads.
//
// Every target here is iOS, so the shared code lives in `commonMain` rather than an intermediate `iosMain`
// (the default hierarchy template is off project-wide -- see gradle.properties -- and there is no second
// platform to share with anyway).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    // Simulator-only while the port is proven. `iosArm64()` (device) declares the same framework and is the
    // one-line change once the app runs.
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "CodeAssistUi"
            // Static: the Xcode app links one archive and has no dynamic framework to embed or sign.
            isStatic = true
            // The entry point is called from Swift, so it must survive dead-code stripping of the klib.
            export(project(":ide-ui"))
        }
    }

    sourceSets {
        // The backend's file and project handling is real Foundation IO, so its tests run on the simulator:
        // `./gradlew :ide-ios:iosSimulatorArm64Test`.
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test) // the project/file operations are suspend functions
        }

        commonMain.dependencies {
            // `api` so the exported framework carries the shell's surface, not just this module's.
            api(project(":ide-ui"))
            // StubBackend, which `IosBackend` extends so it only implements the concerns this host actually
            // has (files, projects, saving) and inherits the empty/`Unsupported` answers for the rest.
            // `api`, not `implementation`: it is the supertype of a public class here.
            api(project(":ide-ui-testing"))
        }
    }
}
