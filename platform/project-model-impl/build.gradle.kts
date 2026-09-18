import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// project-model-impl implements project-model-api. Consumers of the impl need the api types, so the
// dependency is `api`. platform-core (model read/write lock, message bus, extension registry) arrives
// transitively through project-model-api.
//
// MULTIPLATFORM: the store, the views, the transactions and the on-disk format all build for iOS.
//
// The line used to be `ProjectModelStore`, because the model views hand out `VirtualFile`s resolved through
// `java.nio.file` and published on a message bus typed by `java.lang.Class`. Three changes moved it: a
// `Topic` now carries its own fan-out (`:platform-core`), the file system and path arithmetic are strings
// and six `expect` functions (`:model-api`), and `LocalFileSystem` is written against those. A JVM caller
// that holds a `Path` is unaffected — see `JvmPaths.kt`.
//
// `jvmMain` keeps what is genuinely a host: JDK detection, the synthetic JDK, and the filtered
// `android.jar` cache. Those look for a Java installation, which is not a thing a phone has.
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
            api(project(":project-model-api"))
        }

        jvmTest.dependencies {
            // The shared JVM test stack the root build wires into every Kotlin/JVM module; a multiplatform
            // module has to ask for it (those artifacts have no common variant).
            implementation(project(":test-support"))
            implementation(libs.kotlin.test)
        }

        // Declared by hand: `kotlin.mpp.applyDefaultHierarchyTemplate=false` (see gradle.properties).
        val iosMain = create("iosMain") { dependsOn(getByName("commonMain")) }
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        getByName("iosArm64Main").dependsOn(iosMain)
        val iosTest = create("iosTest") { dependsOn(getByName("commonTest")) }
        getByName("iosSimulatorArm64Test").dependsOn(iosTest)
        getByName("iosArm64Test").dependsOn(iosTest)
    }
}

dependencies {
    "jvmTestImplementation"(platform(libs.junit.bom))
    "jvmTestImplementation"(libs.junit.jupiter)
    "jvmTestRuntimeOnly"(libs.junit.platform.launcher)
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
