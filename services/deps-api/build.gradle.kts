import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// deps-api — the SPI for resolving `group:name:version` coordinates into concrete jars/aars.
//
// MULTIPLATFORM, and it needs nothing to be: a `DependencyResolver` is typed by `Coordinate`, `Exclusion`,
// `VirtualFile` and `ProgressReporter`, all of which live in :model-api's common code. Resolution is a
// function from coordinates and repository URLs to artifacts, and none of that is a JVM notion — the editor
// needs a classpath on every platform it runs on, whether or not that platform also runs a build.
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
        commonMain.dependencies {
            // Coordinate/Exclusion, VirtualFile and ProgressReporter all appear in the SPI, so `api`.
            api(project(":model-api"))
        }

        // Declared by hand: `kotlin.mpp.applyDefaultHierarchyTemplate=false` (see gradle.properties).
        val iosMain = create("iosMain") { dependsOn(getByName("commonMain")) }
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        getByName("iosArm64Main").dependsOn(iosMain)
    }
}
