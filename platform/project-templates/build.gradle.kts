import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// project-templates — the built-in project templates that describe a source tree and nothing else.
//
// Multiplatform because a template is the one part of "create a project" that has no platform in it: it
// opens a model transaction, adds a module and a source set, and writes a file. Everything it needs
// (`ProjectScaffold`, the model, the portable file system) already builds for iOS, so keeping these in the
// JVM host was what stopped the iOS app from creating a real project — it scaffolded a bare folder with a
// hand-written `Main.kt` and no model at all.
//
// The templates that DO name a platform stay with their host: the Android ones in :android-support, the
// Swing and plugin ones in :ide-core, which is where the toolchain they target lives.
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
            // The template SPI (`ProjectTemplate`/`ProjectScaffold`) and the model a template authors.
            api(project(":project-model-api"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            // A template is only meaningfully tested against a real store: the scaffold IS the model, and
            // asserting on a fake one would assert on the fake.
            implementation(project(":project-model-impl"))
        }

        val iosTest = create("iosTest") { dependsOn(getByName("commonTest")) }
        getByName("iosSimulatorArm64Test").dependsOn(iosTest)
        getByName("iosArm64Test").dependsOn(iosTest)
    }
}

// A multiplatform module has no `test` source set for the root build's JUnit wiring to reach, so `jvmTest`
// names it here.
dependencies {
    "jvmTestImplementation"(platform(libs.junit.bom))
    "jvmTestImplementation"(libs.junit.jupiter)
    "jvmTestRuntimeOnly"(libs.junit.platform.launcher)
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
