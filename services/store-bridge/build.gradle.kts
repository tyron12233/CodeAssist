import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// store-bridge — the store transport, as the UI's `StoreService` sees it.
//
// Between :store-api (what the backend can do) and :ide-ui-api (what a screen can draw) sits a layer of
// pure translation: a remote feed onto `UiStoreFeed`, a review page onto `UiReviewPage`, a moderation
// queue onto `UiModerationQueue`, the sign-in phases, the install (download, verify, unpack, remember).
// None of it needs a project, an engine or a host — which is exactly why it is a module rather than part
// of :ide-core. The Android/desktop backend and the iOS one are different orchestrators over the SAME
// pieces, and a store migration that changes a shape should not be translatable twice.
//
// What it deliberately does NOT hold is the orchestration: where the workspace is, which templates are
// bundled, when to count an install. That is the host's, and :ide-core's `StoreBackend` and :ide-ios's
// `IosStoreService` each answer it their own way.
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
            // The store's own contracts and the transport's file/zip seam (the installer unpacks a payload).
            api(project(":store-api"))
            implementation(project(":store-impl"))
            // The Ui* DTOs this maps onto. `api`, because every function here returns one.
            api(project(":ide-ui-api"))
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        // Declared by hand: the build turns the default hierarchy template off (see gradle.properties).
        val iosMain = create("iosMain") { dependsOn(getByName("commonMain")) }
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        getByName("iosArm64Main").dependsOn(iosMain)
        val iosTest = create("iosTest") { dependsOn(getByName("commonTest")) }
        getByName("iosSimulatorArm64Test").dependsOn(iosTest)
        getByName("iosArm64Test").dependsOn(iosTest)
    }
}

// The root build wires JUnit 5 into every `kotlin.jvm` module; a multiplatform one has to say so itself.
dependencies {
    "jvmTestImplementation"(platform(libs.junit.bom))
    "jvmTestImplementation"(libs.junit.jupiter)
    "jvmTestRuntimeOnly"(libs.junit.platform.launcher)
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    // StoreFeedWiringTest parses fixtures captured from a live `store_explore()`, which live in
    // :store-impl's test resources (see its KDoc for why they are read by path rather than off the
    // classpath). Hand the test that directory instead of letting it compose a relative one -- the module
    // layout is the build's business.
    systemProperty(
        "store.impl.testResources",
        project(":store-impl").layout.projectDirectory.dir("src/jvmTest/resources").asFile.absolutePath,
    )
}
