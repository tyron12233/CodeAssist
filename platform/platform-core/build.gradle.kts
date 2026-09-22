import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// platform-core — the substrate, in two halves.
//
// `commonMain` is the part with no host behind it: the message bus, the scoped service container, the
// extension registry, the disposer and the model lock. It builds for iOS because the project model sits on
// it, and the project model has to reach a phone that has no build system to describe.
//
// What kept it on the JVM until now was the message bus: a `Topic` was typed by `java.lang.Class` and
// `syncPublisher` returned a `java.lang.reflect.Proxy` over it, and there is no `Proxy` off the JVM. A topic
// now carries its own fan-out function instead (see `Topic`), which is a line per topic and no reflection at
// all. The `Topic(name, Class)` spelling survives in `jvmMain` so existing declarations keep working.
//
// `jvmMain` is everything that is genuinely a host: the crash tombstone reader, storage accounting, the
// forked tool VM, class isolation, the settings store and the engine scheduler.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Published for plugin authors to compile against. The multiplatform convention rather than
    // `dev.ide.spi-publish`: that one applies `java-library`, which a multiplatform module cannot, and a
    // module published as several components needs a javadoc placeholder per publication rather than one
    // shared jar. Same coordinate, same POM, same BOM entry (`PluginBomTest` matches either opt-in).
    id("dev.ide.spi-publish-mpp")
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    iosSimulatorArm64()

    iosArm64()

    sourceSets {
        commonMain.dependencies {
            // ContentHash, Disposable, ExtensionPoint, ServiceKey and the concurrency primitives live in
            // :model-api. `api`, not `implementation`: they are part of this module's public signature.
            api(project(":model-api"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmMain.dependencies {
            // Coroutines back the activity engine / dispatchers and are an internal implementation detail,
            // not exposed in the public API.
            implementation(libs.kotlinx.coroutines.core)
        }

        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            // The shared JVM test stack the root build wires into every Kotlin/JVM module. A multiplatform
            // module has to ask for it: those artifacts have no common variant, so the root's block is
            // guarded on the Kotlin/JVM plugin. `:test-support` brings the testkit (jar builders, temp
            // dirs, ASM helpers) these tests use; test -> main is acyclic, so depending on a module that
            // transitively depends on this one is fine.
            implementation(project(":test-support"))
            implementation(libs.kotlin.test)
        }

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
