import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// store-api — the contract for the remote Projects Store.
//
// A dependency-free SPI: the catalog model, and the ports the engine talks to — StoreCatalogSource (fetch
// the catalog / search / count an install), StoreAccountService (sign in, who am I), StoreSubmissionService
// (package and submit a project for review), StoreModerationService and StoreReviewService. The HTTP, the
// zip packing and the offline cache all live in store-impl, so the transport is swappable and the engine
// never sees Supabase. See supabase/README.md for the backend these map onto.
//
// Multiplatform because three hosts implement the same store: the JVM desktop IDE, ART on Android, and iOS.
// Nothing here names a platform type — a destination for a download is a path string, not a `java.io.File`
// — which is what lets one contract serve all three.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    iosSimulatorArm64()

    iosArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
