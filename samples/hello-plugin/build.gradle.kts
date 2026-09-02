// A CodeAssist plugin shipped as its own Android app: the packaging described in
// docs/writing-plugins.md, "Ship your plugin as its own app".
//
// It is a module of this build so the plugin SPI comes straight from :plugin-api and the sample cannot
// drift from an SPI change without failing the build. Building it outside this repository takes two
// changes, both covered in README.md: the SPI arrives as jars rather than a project dependency, and the
// Kotlin version has to be pinned by hand.
plugins {
    alias(libs.plugins.android.application)
    // The Compose compiler plugin, for the UI facet's @Composable panel. Under AGP 9 Kotlin is built into
    // `com.android.application`, so this is the only Compose-related plugin a plugin app needs: the Compose
    // runtime itself comes off the IDE at load time.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.hello"
    compileSdk = 36

    defaultConfig {
        // Not the IDE's own package: a plugin app is a separate install.
        applicationId = "com.example.hello"
        // The IDE's own floor. The plugin's code runs inside the IDE's process, so it can be no higher.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        // Never minify a plugin: the IDE loads the entry point by the name in the packaged manifest, and
        // R8 would rename it.
        getByName("debug") { isMinifyEnabled = false }
        getByName("release") { isMinifyEnabled = false }
    }

    // AGP's built-in Kotlin aligns its jvmTarget to these.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The SPI is compiled against, never bundled. The IDE's classloader is the parent of the plugin's, so
    // the SPI, the Kotlin stdlib and the Compose runtime all resolve to the IDE's copies; a second copy in
    // the plugin APK is dead weight at best and a linkage error at worst.
    compileOnly(project(":plugin-api"))
    // The UI facet's SPI (HelloUiPlugin): tool windows, screens, overlays.
    compileOnly(project(":plugin-ui-api"))

    // Compose, at the versions the IDE bundles and in the coordinates an out-of-tree plugin would use, so
    // this sample also checks that those pins are the right ones. compileOnly for the same reason as the
    // SPI: the plugin's @Composable code composes into the IDE's own Compose runtime.
    compileOnly(libs.androidx.compose.runtime)
    compileOnly(libs.androidx.compose.foundation)
    compileOnly(libs.androidx.compose.ui)
    compileOnly(libs.androidx.compose.material3)
    // The @Preview below the panel, so the sample shows a plugin author how to see their UI without
    // installing anything.
    compileOnly(libs.androidx.compose.ui.tooling.preview)
}
