// The Dart plugin: Dart on the device, shipped as its own Android app.
//
// It carries a Dart SDK that RUNS on Android (tools/dart-sdk), one set of executables per ABI. Those bytes are
// NOT in this repository: `tools/dart-sdk/fetch-dart-android.sh` stages them, once per ABI, and this module
// packages whatever that left behind, as one APK per ABI that has anything staged.
// Building the plugin without them produces an APK that installs and reports "no Dart SDK for this device",
// which is what a user on an unsupported ABI sees too.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // The UI facet's Flutter preview pane is a @Composable.
    alias(libs.plugins.kotlin.compose)
}

/** Where `fetch-dart-android.sh` put its output. Override with `-Pdart.sdk.dir=...`. */
val sdkOut: File =
    (providers.gradleProperty("dart.sdk.dir").orNull?.let(::File) ?: rootProject.file("tools/dart-sdk/build/out"))

val sdkLibs: File = File(sdkOut, "jniLibs")
val sdkAssets: File = File(sdkOut, "assets/dart-sdk")
val generatedAssetsDir: File = layout.buildDirectory.dir("generated/dart-sdk/assets").get().asFile

/**
 * Flutter, from `fetch-flutter.sh`: the engine, the player's bundle, a FLUTTER_ROOT and the base assets. Without
 * them the plugin still builds and runs Dart; a Flutter app's Run then says the plugin carries no Flutter.
 */
val flutterOut: File = File(sdkOut, "flutter")

/**
 * The ABIs a Dart SDK was staged for (its `jniLibs/<abi>` directories); a Flutter engine alone is no use without
 * one. x86 (32-bit) is absent: Termux publishes no Dart for it.
 *
 * One APK is built for each (see `splits` below), carrying only that ABI's executables, instead of one APK with
 * all of them. `-Pdart.abis=arm64-v8a,x86_64` narrows the set; `-Pdart.universalApk` adds the single APK with
 * every ABI as well.
 */
val allAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val stagedAbis: List<String> = (providers.gradleProperty("dart.abis").orNull
    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: allAbis)
    .filter { File(sdkLibs, it).isDirectory }

/** The engine the staged Flutter was built on, which the player's Java embedding has to match. */
val flutterEngine: String = File(flutterOut, "ENGINE").takeIf { it.isFile }?.readText()?.trim()
    ?: "89c39edfdfae2949f6806e4a315c0c08522238b7" // Flutter 3.47.9

/**
 * Pack the SDK's libraries into ONE archive. The plugin reads it back through its classloader, over the
 * installed APK, and a classloader can open a named entry but cannot list a directory, so ~500 loose files
 * would be unreachable for want of a way to enumerate them.
 */
val packSdkAssets by tasks.registering(Zip::class) {
    from(sdkAssets)
    archiveFileName.set("dart-sdk.zip")
    destinationDirectory.set(generatedAssetsDir)
    // Stored: the APK compresses its own entries, and compressing twice costs build time for nothing.
    entryCompression = ZipEntryCompression.STORED
    onlyIf {
        sdkAssets.isDirectory.also {
            if (!it) logger.warn(
                "No Dart SDK at $sdkAssets -- the plugin will build without one. Run tools/dart-sdk/fetch-dart-android.sh first."
            )
        }
    }
}

/** Pack a staged Flutter directory into one stored asset archive (the classloader can only open named entries). */
fun packFlutter(taskName: String, dir: String, archive: String) = tasks.register<Zip>(taskName) {
    from(File(flutterOut, dir))
    archiveFileName.set(archive)
    destinationDirectory.set(generatedAssetsDir)
    entryCompression = ZipEntryCompression.STORED
    onlyIf { File(flutterOut, dir).isDirectory }
}
val packFlutterRoot = packFlutter("packFlutterRoot", "root", "flutter-root.zip")
val packFlutterBaseAssets = packFlutter("packFlutterBaseAssets", "base-assets", "flutter-base-assets.zip")

android {
    namespace = "dev.codeassist.dart"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.codeassist.dart"
        // The IDE's own floor: this code runs inside the IDE's process.
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    // Signed with the IDE's upload key when one is configured (a gitignored keystore.properties at the repo
    // root), as the NDK plugin is; with none the release variant is left unsigned.
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

    // A separate APK for each ABI that has something staged, named like `dart-plugin-arm64-v8a-debug.apk`.
    // With nothing staged there is nothing to split, and the build stays a single SDK-less APK.
    splits {
        abi {
            isEnable = stagedAbis.isNotEmpty()
            reset()
            include(*stagedAbis.toTypedArray())
            isUniversalApk = providers.gradleProperty("dart.universalApk").isPresent
        }
    }

    sourceSets["main"].jniLibs.srcDir(sdkLibs)
    // The engine (libflutter.so), and the player's own bundle as the APK's `flutter_assets`, where the
    // Flutter embedding looks for it.
    sourceSets["main"].jniLibs.srcDir(File(flutterOut, "jniLibs"))
    sourceSets["main"].assets.srcDir(File(flutterOut, "player"))
    // Produced by `packSdkAssets`. AGP refuses a Provider here, so the directory is a plain File and the
    // ordering is an explicit task dependency below.
    sourceSets["main"].assets.srcDir(generatedAssetsDir)

    packaging {
        jniLibs {
            // The executables have to exist as FILES to be run: left in the APK they are mapped straight out
            // of the archive, which serves System.loadLibrary and gives a child process nothing to exec.
            useLegacyPackaging = true
            // Three of these are AOT snapshots, ELF files the Dart VM loads itself. Stripping them as if they
            // were ordinary shared libraries would break them.
            keepDebugSymbols += "**/*.so"
        }
    }
}

tasks.named("preBuild") { dependsOn(packSdkAssets, packFlutterRoot, packFlutterBaseAssets) }

// JUnit 5, like the rest of this build. AGP defaults a unit test task to JUnit 4.
tasks.withType<Test>().configureEach { useJUnitPlatform() }

dependencies {
    // Compiled against, never bundled: the IDE's classloader is the parent of the plugin's.
    compileOnly(project(":plugin-api"))
    compileOnly(project(":plugin-ui-api"))
    compileOnly(project(":build-api"))
    compileOnly(project(":language-api"))
    compileOnly(project(":lsp-api"))
    compileOnly(project(":project-model-api"))
    compileOnly(libs.kotlinx.coroutines.core)
    // Reading the test runner's JSON events. Bundled, as the Calc sample bundles it, rather than borrowed
    // from the IDE.
    implementation(libs.kotlinx.serialization.json)
    // The Flutter player (FlutterPlayerActivity), which runs in this plugin's own process, never the IDE's.
    implementation("io.flutter:flutter_embedding_debug:1.0.0-$flutterEngine")

    // The IDE's Compose, which the preview pane is composed by. Never bundled.
    compileOnly(libs.androidx.compose.runtime)
    compileOnly(libs.androidx.compose.foundation)
    compileOnly(libs.androidx.compose.ui)
    compileOnly(libs.androidx.compose.material3)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(project(":plugin-api"))
    testImplementation(project(":build-api"))
    testImplementation(project(":language-api"))
    testImplementation(project(":lsp-api"))
    testImplementation(project(":project-model-api"))
    testImplementation(libs.kotlinx.coroutines.core)
    // The Compose compiler insists on a runtime wherever it compiles, test sources included.
    testImplementation(libs.androidx.compose.runtime)
}
