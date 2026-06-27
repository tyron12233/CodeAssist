plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// android-support — the Android plugin. It is a pure kotlin("jvm") module with NO
// Android SDK on its own compile classpath: it contributes the `AndroidFacet`, the android-app/-lib
// module types, the variant model (build types x product flavors), and the native Android build system
// (the aapt2 -> R/compile -> D8 dex -> package -> sign task DAG) over build-engine's generic engine.
// The actual SDK tools (aapt2/zipalign native; D8/apksigner pure-Java) are invoked at *runtime* via
// subprocess behind injectable ports, so the core never links android.jar.
dependencies {
    api(project(":build-api"))                 // BuildSystem SPI + Task engine contracts (brings project-model-api)
    implementation(project(":build-engine"))   // TaskGraphImpl / TaskInputsImpl / TaskOutputsImpl + neutral tasks
    implementation(project(":jvm-build"))       // JavaPlugin.registerModule for plain java-lib modules in an Android project
    implementation(project(":lang-jdt"))        // JdtBatchCompiler — the Android compile tasks drive ecj directly
    implementation(project(":lang-kotlin"))     // IncrementalKotlinCompiler + ComposeCompilerPlugin — drive K2 directly
    implementation(project(":project-model-impl")) // FacetCodec / FacetCodecRegistry / ModuleTypeRegistry
    implementation(project(":language-api"))   // SyntheticClassProvider — the light `R` class for completion/analysis
    implementation(project(":index-api"))       // resource-declaration IndexExtension
    implementation(libs.ow2.asm)                 // ClassReader — scan the classpath for custom View subclasses (no class loading)
    implementation(libs.kotlinx.coroutines.core)

    // D8 (r8) + apksigner (apksig) are invoked IN-PROCESS by the on-device wiring, so they're statically
    // linked where needed. Here they're compileOnly — the in-process impls compile against the API, but
    // hosts that only use the subprocess wiring (desktop) don't drag the ~megabytes in. The Android
    // launcher adds them as `implementation` so they dex into the app and run on ART.
    compileOnly(libs.android.r8)
    compileOnly(libs.android.apksig)
    // bundletool builds the .aab in-process (BundletoolInProcess). Not an SDK build-tool, so it is bundled
    // by the hosts that produce bundles (:ide-android, :ide-desktop); here it is compileOnly + test. Its deps
    // are runtime-scoped in its POM, so guava (ImmutableList) must be added explicitly for compilation.
    compileOnly(libs.android.bundletool)
    compileOnly(libs.guava)

    // The end-to-end APK test compiles real Java (R.java + an Activity) through the JDT batch compiler
    // (lang-jdt, now a main dependency) and exercises the in-process tools.
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.android.r8)
    testImplementation(libs.android.apksig)
    testImplementation(libs.android.bundletool)
}

// The core-library-desugaring test needs the desugar runtime + config jars as real files (L8 dexes the
// runtime; D8/R8 read the config). Resolve them into a dedicated configuration and hand the paths to the
// test JVM, so it does not depend on a particular Gradle-cache layout.
val desugarLibTest: Configuration by configurations.creating { isCanBeConsumed = false }
dependencies {
    desugarLibTest(libs.desugar.jdk.libs)
    desugarLibTest(libs.desugar.jdk.libs.configuration)
}
tasks.test {
    val files = desugarLibTest
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Ddesugar.lib.path=${files.asPath}") })
}
