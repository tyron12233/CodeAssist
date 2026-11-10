// Black-box feature tests for the SHIPPING build of :ide-android.
//
// The release build runs R8, and the app reaches most of its toolchain (Kotlin compiler, IntelliJ platform,
// JDT, D8/R8, bundletool, the interpreters' view of Compose and the stdlib) by name. A missing keep rule is
// therefore never a build error: it is a ClassNotFoundException inside one feature, on a user's device.
// These tests exercise each feature end to end on a device against the `profile` variant (release + R8,
// test ad ids, installable signing) and fail on any linkage error the app logs while they run.
//
// Why a separate `com.android.test` module, self-instrumenting: the tests run in THEIR OWN process and drive
// the app purely through UI Automator (the accessibility tree), so they link none of the app's classes. An
// androidTest source set inside :ide-android would run in the app's process against its minified classes and
// need keep rules for everything it touches, which is exactly the kind of keep that hides a missing one.
//
// Run (emulator or device attached; R8 needs a big heap):
//   ./gradlew :ide-android-e2e:connectedProfileAndroidTest -Dorg.gradle.jvmargs=-Xmx9g
// One class: add -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.e2e.ConsoleRunTest
// On failure each test leaves a window-hierarchy dump and the app's logcat under the device's
// /sdcard/Android/media/dev.ide.android.e2e/ (pulled into build/outputs/connected_android_test_additional_output).
plugins {
    // AGP is already on the build classpath (root plugins block), so apply by id without a version.
    id("com.android.test")
}

android {
    namespace = "dev.ide.android.e2e"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":ide-android"
    // Run in our own process and talk to the app only through UI Automator (see the header).
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        // Tests the app's `profile` variant: release with R8, test ads, signed so it installs locally.
        create("profile") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Only the profile variant means anything here; a debug variant would build the app's unminified debug APK.
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { it.enable = false }
}

// PluginTest installs the sample plugin app on the device; ship its APK inside the test APK as an asset.
val stageHelloPlugin = tasks.register<Copy>("stageHelloPluginApk") {
    dependsOn(":samples:hello-plugin:assembleDebug")
    from(project(":samples:hello-plugin").layout.buildDirectory.dir("outputs/apk/debug")) {
        include("*.apk")
        rename { "hello-plugin.apk" }
    }
    into(layout.buildDirectory.dir("plugin-asset"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("plugin-asset").get().asFile)
tasks.matching { it.name.matches(Regex("merge\\w*Assets")) }.configureEach { dependsOn(stageHelloPlugin) }

dependencies {
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}
