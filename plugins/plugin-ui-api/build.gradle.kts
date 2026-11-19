import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The Compose Multiplatform plugin, for the `compose.runtime` / `compose.ui` coordinates an iOS build
    // links against. It adds no dependency by itself.
    alias(libs.plugins.compose)
    // The Compose compiler plugin, for the `@Composable` function types in this module's signatures. A
    // composable lambda's ABI (the synthetic `Composer`/`$changed` parameters) is produced by this plugin, so
    // a host that reads one and a plugin that writes one must both be compiled with it, at the same version.
    alias(libs.plugins.kotlin.compose)
    // Published for plugin authors to compile against; see the convention plugin for the coordinate.
    id("dev.ide.spi-publish-mpp")
}

// plugin-ui-api: the UI half of the plugin SPI, i.e. what a plugin shipped as its own app implements to
// contribute Compose-bearing UI (a tool window, a screen, an overlay).
//
// Deliberately narrow, and deliberately NOT :ide-ui-api. That module's contribution model hands a body the
// whole `IdeBackend` port, which is the IDE's largest and fastest-moving surface; publishing it would freeze
// every concern service and DTO in it forever. What a plugin actually needs is its own engine facet (already
// published: it is in the same APK, so the two facets share a classloader and can call each other directly)
// plus a handful of host operations. That handful is `UiContext`, and this module is nothing else.
//
// The Compose runtime is `compileOnly` on purpose: a loaded plugin binds to the IDE's own copy through
// classloader parent delegation, so this artifact must not put a Compose dependency in a consumer's POM:
// an Android plugin would otherwise resolve a desktop artifact. A plugin declares the Compose it compiles
// against itself (the template does this), and the host provides it at runtime.
//
// Multiplatform: the built-in layout editor is a UI plugin, and it is built for iOS too. There the Compose
// runtime is linked, not provided by a parent class loader, so the iOS source set depends on it for real;
// the JVM artifact keeps it compileOnly and its POM stays as it was.
kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    iosSimulatorArm64()

    iosArm64()

    sourceSets {
        commonMain.dependencies {
            // `ServiceKey`, for `UiContext.service`. This is the one CodeAssist module this artifact depends on, and
            // it is deliberate: a plugin's UI facet resolves the plugin's OWN service, and the key it resolves by has
            // to be the same type the engine facet registered with. Declaring a second key type here instead would
            // mean a plugin holding two keys for one service. `platform-core` is published, is already on every
            // plugin's compile path (settings pages, logging), and depends on nothing itself, so this adds no
            // resolution a plugin author does not already have.
            api(project(":platform-core"))
            // compose-ui, for the editor painter alone: a raw drawing hook IS a `DrawScope`, so that one type has to
            // appear in this module's signature.
            compileOnly(compose.runtime)
            compileOnly(compose.ui)
        }

        jvmTest.dependencies {
            // The Compose compiler plugin is applied to this module, and it refuses to run without the runtime on
            // the class path. `compileOnly` does not reach the test compilation, so the tests need their own copy.
            // Test-scoped, so it stays out of the published POM the way the main one does.
            implementation(libs.compose.runtime.desktop)
            implementation(libs.compose.ui.desktop)
            implementation(project(":test-support"))
            implementation(libs.kotlin.test)
        }

        val iosMain = create("iosMain") {
            dependsOn(getByName("commonMain"))
            dependencies {
                api(compose.runtime)
                api(compose.ui)
            }
        }
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        getByName("iosArm64Main").dependsOn(iosMain)
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
