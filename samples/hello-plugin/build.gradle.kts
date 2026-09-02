// A CodeAssist plugin shipped as its own Android app: the packaging described in
// docs/writing-plugins.md, "Ship your plugin as its own app".
//
// It is a module of this build so the plugin SPI comes straight from :plugin-api and the sample cannot
// drift from an SPI change without failing the build. Building it outside this repository takes two
// changes, both covered in README.md: the SPI arrives as jars rather than a project dependency, and the
// Kotlin version has to be pinned by hand.
plugins {
    alias(libs.plugins.android.application)
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
}
