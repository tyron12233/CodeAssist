// The C# plugin: .NET on the device, shipped as its own Android app.
//
// It carries a .NET runtime, the Roslyn compiler and a launcher that RUN on Android (tools/dotnet-toolchain).
// Those bytes are NOT in this repository: `tools/dotnet-toolchain/build-dotnet-android.sh` assembles them
// from Microsoft's published packages, and this module packages whatever that build left behind. Building
// the plugin without them produces an APK that installs and reports that it has no .NET for the device.
// Imported rather than fully qualified: the Java plugin's `java` project extension shadows the `java.*`
// package inside a build script, so `java.util.Properties` would parse as `(java extension).util`.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // No Compose compiler plugin: this plugin's UI facet registers editor languages and file templates and
    // contributes no @Composable of its own.
}

/**
 * The device ABI this APK is for. Unlike a compiler that only differs in its executables, the runtime's
 * native libraries live in the asset archive, so one APK serves one ABI: `-Pdotnet.abi=x86_64` builds the
 * other one the runtime is published for.
 */
val dotnetAbi: String = providers.gradleProperty("dotnet.abi").orNull ?: "arm64-v8a"

/** Where `build-dotnet-android.sh` put its output for [dotnetAbi]. Override with `-Pdotnet.toolchain.dir=…`. */
val toolchainOut: File =
    (providers.gradleProperty("dotnet.toolchain.dir").orNull?.let(::File)
        ?: rootProject.file("tools/dotnet-toolchain/build/out")).resolve(dotnetAbi)

val toolchainLibs: File = File(toolchainOut, "jniLibs")
val toolchainAssets: File = File(toolchainOut, "assets")
val hasToolchain: Boolean = File(toolchainAssets, "dotnet-sdk.zip").isFile

if (!hasToolchain) {
    logger.warn(
        "No .NET tree at $toolchainAssets -- the plugin will build without a runtime. " +
            "Run tools/dotnet-toolchain/build-dotnet-android.sh first."
    )
}

android {
    namespace = "dev.codeassist.csharp"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.codeassist.csharp"
        // The IDE's own floor: this code runs inside the IDE's process.
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
        if (hasToolchain) ndk { abiFilters += dotnetAbi }
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

    // The launcher and the tree are static files produced outside this build, so they join the source set
    // directly. The tree stays one archive: the plugin reads it back through its classloader, which can open
    // a named entry but cannot list a directory.
    sourceSets["main"].jniLibs.srcDir(toolchainLibs)
    sourceSets["main"].assets.srcDir(toolchainAssets)

    androidResources {
        // Already compressed; deflating it again costs build time and first-run time for nothing.
        noCompress += "zip"
    }

    packaging {
        // The launcher has to exist as a FILE to be executed. Left compressed in the APK it is mapped
        // straight out of the archive, which serves System.loadLibrary and is useless for exec: there would
        // be nothing on disk to hand a child process.
        jniLibs.useLegacyPackaging = true
    }

    testOptions {
        // The code under test logs through android.util.Log, which the unit-test android.jar only stubs.
        unitTests.isReturnDefaultValues = true
    }
}

// The tests are JUnit 4; the vintage engine runs them on the JUnit Platform the rest of this build uses.
tasks.withType<Test>().configureEach { useJUnitPlatform() }

dependencies {
    // Compiled against, never bundled: the IDE's classloader is the parent of the plugin's, so the SPI, the
    // Kotlin stdlib and coroutines all resolve to the IDE's copies.
    compileOnly(project(":plugin-api"))
    compileOnly(project(":plugin-ui-api"))
    compileOnly(project(":platform-core"))
    compileOnly(project(":project-model-api"))
    compileOnly(project(":model-api"))
    compileOnly(project(":language-api"))
    compileOnly(project(":analysis-api"))
    compileOnly(project(":build-api"))
    compileOnly(project(":vfs-api"))
    compileOnly(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(project(":plugin-api"))
    testImplementation(project(":plugin-ui-api"))
    testImplementation(project(":platform-core"))
    testImplementation(project(":project-model-api"))
    testImplementation(project(":model-api"))
    testImplementation(project(":language-api"))
    testImplementation(project(":analysis-api"))
    testImplementation(project(":build-api"))
    testImplementation(project(":vfs-api"))
    testImplementation(libs.kotlinx.coroutines.core)
}
