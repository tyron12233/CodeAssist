import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// StubBackend: an `IdeBackend` that answers with empty, so a test can render a component, an editor or a
// screen without a project, a build or a file system behind it. Thirty-one test files across every UI layer
// use it, which is why it is a module rather than a fixture inside one of them.
//
// It depends on :ide-ui-api and nothing else -- deliberately. A fixture that reaches into a UI module would
// have to sit above it, and then the modules below could not use the fixture.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "dev.ide.ui.testing"
        compileSdk = 36
        minSdk = 24
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":ide-ui-api"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
