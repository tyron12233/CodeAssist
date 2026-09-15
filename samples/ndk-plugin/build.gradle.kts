// The NDK plugin: C and C++ on the device, shipped as its own Android app.
//
// It carries a clang and an lld built to RUN on Android arm64 (tools/ndk-toolchain). Those bytes are NOT in
// this repository: `tools/ndk-toolchain/build-llvm-android.sh` produces them, and this module packages
// whatever that build left behind. Building the plugin without them produces an APK that installs and
// reports "no toolchain for this device's ABI", which is the same thing a user on an unsupported ABI sees.
// Imported rather than fully qualified: the Java plugin's `java` project extension shadows the `java.*`
// package inside a build script, so `java.util.Properties` would parse as `(java extension).util`.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // No Compose compiler plugin: this plugin's UI facet registers editor languages and contributes no
    // @Composable of its own. The Compose artifacts stay on the compile classpath below only because
    // `UiRegistration` names those types in the methods this facet does not call.
}

/** Where `build-llvm-android.sh` put its output. Override with `-Pndk.toolchain.dir=…`. */
val toolchainOut: File =
    (providers.gradleProperty("ndk.toolchain.dir").orNull?.let(::File)
        ?: rootProject.file("tools/ndk-toolchain/build/out"))

val toolchainLibs: File = File(toolchainOut, "jniLibs")
val toolchainAssets: File = File(toolchainOut, "assets/toolchain")
val generatedAssetsDir: File = layout.buildDirectory.dir("generated/toolchain/assets").get().asFile

/**
 * Pack the compiler's headers and link libraries into ONE archive.
 *
 * Not a convenience: the plugin reads them back through its classloader, over the installed APK, and a
 * classloader can open a named entry but cannot LIST a directory. As ~2800 loose files the plugin would have
 * no way to discover what to unpack. As one entry it is a single stream, and it compresses from 52 MB to
 * about 10 MB on the way into the APK.
 */
val packToolchainAssets by tasks.registering(Zip::class) {
    from(toolchainAssets)
    archiveFileName.set("toolchain.zip")
    destinationDirectory.set(generatedAssetsDir)
    // Stored rather than deflated: the APK compresses its own entries, and compressing twice costs build
    // time for nothing.
    entryCompression = ZipEntryCompression.STORED
    onlyIf {
        toolchainAssets.isDirectory.also {
            if (!it) logger.warn(
                "No toolchain at $toolchainAssets -- the plugin will build without a compiler. " +
                    "Run tools/ndk-toolchain/build-llvm-android.sh first."
            )
        }
    }
}

android {
    namespace = "dev.codeassist.ndk"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.codeassist.ndk"
        // The IDE's own floor: this code runs inside the IDE's process.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    // Signed with the same upload key the IDE is, when one is configured, so the published plugin APK is a
    // release build rather than a debuggable one. Resolution order and file are the IDE's (a gitignored
    // keystore.properties at the repo root); with no keystore the release variant is simply left unsigned,
    // which is what a contributor building this sample gets.
    signingConfigs {
        val keystoreProps = Properties().apply {
            val f = rootProject.file("keystore.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        fun signingValue(key: String, prop: String, env: String): String? =
            keystoreProps.getProperty(key) ?: (findProperty(prop) as String?) ?: System.getenv(env)

        val storeFileResolved = signingValue("storeFile", "RELEASE_STORE_FILE", "RELEASE_STORE_FILE")
            ?.let { rootProject.file(it) }
        if (storeFileResolved != null && storeFileResolved.exists()) {
            create("release") {
                storeFile = storeFileResolved
                storePassword = signingValue("storePassword", "RELEASE_STORE_PASSWORD", "RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS", "RELEASE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Never minify a plugin: the IDE loads the entry points by the names in the packaged manifest.
        getByName("debug") { isMinifyEnabled = false }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The executables are static files produced outside this build, so they join the source set directly.
    sourceSets["main"].jniLibs.srcDir(toolchainLibs)
    // The asset archive is produced by `packToolchainAssets` below. AGP refuses a Provider here (it cannot
    // tell generated from static), so the directory is named as a plain File and the ordering is stated as
    // an explicit task dependency instead.
    sourceSets["main"].assets.srcDir(generatedAssetsDir)

    packaging {
        // The compiler has to exist as a FILE to be executed. Left compressed in the APK it is mapped
        // straight out of the archive, which serves System.loadLibrary and is useless for exec: there would
        // be nothing on disk to hand a child process.
        jniLibs.useLegacyPackaging = true
    }
}

// Everything AGP does for a variant starts after preBuild, so this is the one hook that reliably puts the
// archive on disk before the assets are merged.
tasks.named("preBuild") { dependsOn(packToolchainAssets) }

// JUnit 5, like the rest of this build. AGP defaults a unit test task to JUnit 4.
tasks.withType<Test>().configureEach { useJUnitPlatform() }

dependencies {
    // Compiled against, never bundled: the IDE's classloader is the parent of the plugin's, so the SPI, the
    // Kotlin stdlib and Compose all resolve to the IDE's copies.
    compileOnly(project(":plugin-api"))
    compileOnly(project(":plugin-ui-api"))
    compileOnly(project(":build-api"))
    // File types: which suffixes route to the C and C++ languages.
    compileOnly(project(":language-api"))
    // Diagnostics: the provider that reports what clang said.
    compileOnly(project(":analysis-api"))
    // The provider suspends while the compiler runs, so it names the dispatcher it moves to.
    compileOnly(libs.kotlinx.coroutines.core)

    compileOnly(libs.androidx.compose.runtime)
    compileOnly(libs.androidx.compose.foundation)
    compileOnly(libs.androidx.compose.ui)
    compileOnly(libs.androidx.compose.material3)

    // ClangDiagnostics is a pure string-to-data function, so it is tested as one, on the JVM, with no
    // device and no compiler in the loop.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(project(":language-api"))
    testImplementation(project(":analysis-api"))
    testImplementation(project(":project-model-api"))
}
