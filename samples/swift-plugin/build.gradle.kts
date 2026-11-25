import java.security.MessageDigest

// The Swift plugin: Swift on the device, shipped as its own Android app.
//
// It carries a Swift toolchain that RUNS on Android (tools/swift-sdk): the compiler, the Swift runtime and Foundation,
// as shared libraries, and the standard library's modules and the C headers it reads. Those bytes are NOT in this
// repository: `python3 -I tools/swift-sdk/stage_swift.py <abi>` stages them, and this module packages one ABI's.
//
// One ABI per build, picked with `-Pswift.abi=` (default arm64-v8a): unlike Dart's, the read-only half differs per
// ABI too (each carries its own target's modules and C headers), and an ABI split only splits native libraries.
// Building without a staged toolchain produces an APK that installs and says it has no Swift for the device.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // The UI facet's SwiftUI preview pane is a @Composable.
    alias(libs.plugins.kotlin.compose)
}

val swiftAbi: String = providers.gradleProperty("swift.abi").orNull ?: "arm64-v8a"

/** Where `stage_swift.py` put this ABI's output. Override the root with `-Pswift.sdk.dir=...`. */
val stagedRoot: File =
    providers.gradleProperty("swift.sdk.dir").orNull?.let(::File) ?: rootProject.file("tools/swift-sdk/build/out")
val staged: File = File(stagedRoot, swiftAbi)
val generatedAssetsDir: File = layout.buildDirectory.dir("generated/swift/assets").get().asFile

/**
 * Pack the read-only half into ONE stored archive: the plugin reads it back through its classloader, which can open
 * a named entry but cannot list a directory. The ABI's Swift target triple (TARGET) goes in with it.
 */
val packToolchain by tasks.registering(Zip::class) {
    from(File(staged, "assets/swift"))
    from(File(staged, "TARGET"))
    from(File(staged, "SWIFT_VERSION"))
    archiveFileName.set("swift-toolchain.zip")
    destinationDirectory.set(generatedAssetsDir)
    entryCompression = ZipEntryCompression.STORED
    // The archive's digest beside it: the device unpacks again whenever it changes (a new SwiftUI engine in the same
    // Swift version), not only when the Swift version or the layout does.
    doLast {
        val zip = archiveFile.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        zip.inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        File(zip.parentFile, "swift-toolchain.sha256").writeText(digest.digest().joinToString("") { "%02x".format(it) })
    }
    onlyIf {
        File(staged, "assets/swift").isDirectory.also {
            if (!it) logger.warn("No Swift toolchain staged at $staged: the plugin builds without one. Run tools/swift-sdk/stage_swift.py $swiftAbi first.")
        }
    }
}

android {
    namespace = "dev.codeassist.swift"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.codeassist.swift"
        // The IDE's own floor: this code runs inside the IDE's process.
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        ndk { abiFilters += swiftAbi }
    }

    // Signed with the IDE's upload key when one is configured (a gitignored keystore.properties at the repo root),
    // as the other plugins are; with none the release variant is left unsigned.
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

    sourceSets["main"].jniLibs.srcDir(File(staged, "jniLibs"))
    sourceSets["main"].assets.srcDir(generatedAssetsDir)

    packaging {
        jniLibs {
            // The compiler has to exist as a FILE to be run, and the libraries as files for it to load: left in the
            // APK they are mapped straight out of the archive, which gives a child process nothing to exec.
            useLegacyPackaging = true
            // Termux already stripped them; stripping again only costs build time.
            keepDebugSymbols += "**/*.so"
        }
    }
}

tasks.named("preBuild") { dependsOn(packToolchain) }

base { archivesName.set("swift-plugin-$swiftAbi") }

// JUnit 5, like the rest of this build. AGP defaults a unit test task to JUnit 4.
tasks.withType<Test>().configureEach { useJUnitPlatform() }

dependencies {
    // Compiled against, never bundled: the IDE's classloader is the parent of the plugin's.
    compileOnly(project(":plugin-api"))
    compileOnly(project(":plugin-ui-api"))
    compileOnly(project(":build-api"))
    compileOnly(project(":language-api"))
    compileOnly(project(":project-model-api"))
    compileOnly(libs.kotlinx.coroutines.core)

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
    testImplementation(project(":project-model-api"))
    testImplementation(libs.kotlinx.coroutines.core)
    // The Compose compiler insists on a runtime wherever it compiles, test sources included.
    testImplementation(libs.androidx.compose.runtime)
}
