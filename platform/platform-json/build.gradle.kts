import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// platform-json — the dependency-free JSON reader and writer, as a multiplatform module.
//
// These two files used to sit in :platform-core, which is Kotlin/JVM and published as an SPI jar. The
// Projects Store's transport is the only thing in the build that parses JSON on a hot path, and that
// transport now has to compile for iOS as well as the JVM and ART, so the reader had to move somewhere a
// Kotlin/Native target can consume. The package is unchanged (`dev.ide.platform`), so no call site moved
// with them.
//
// Deliberately NOT published: JsonReader was never part of what :platform-core's POM advertises (extension
// points, scoped services, the message bus, logging, the settings model), and a published artifact is a
// promise. Nothing here depends on anything.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Matches the one iOS target the port declares; see app/ide-ios.
    iosSimulatorArm64()
    iosArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
