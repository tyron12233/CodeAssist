plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// ide-embedded: the backend as a host with no JVM of its own runs it.
//
// The iOS app is Kotlin/Native, and most of the IDE's backend is JVM code that will stay JVM code. That app
// embeds a JVM, and this module is what it starts in it: the same engine the Android and desktop apps run
// (:ide-core), with layoutlib standing in for a device's view framework (:realview-layoutlib), behind entry
// points flat enough to call through JNI. What crosses is the layout services' own byte protocol
// (:android-layout-api's `remote` package), so the far side is an ordinary implementation of those services.
dependencies {
    implementation(project(":ide-core"))
    implementation(project(":realview-layoutlib"))
    implementation(project(":android-layout-api"))
    implementation(project(":layout-preview-impl"))
    implementation(project(":project-model-impl"))  // the store the engine keeps its per-project services in
    implementation(project(":platform-core"))
    implementation(project(":android-support"))     // the seam a host with aapt2 linked in plugs into
    implementation(project(":build-api"))           // where a host's native file hash plugs in
    implementation(project(":lang-kotlin"))         // where a host's linked-in Kotlin compiler plugs in
    implementation(project(":ide-ui-api"))          // the project tree's types and its byte form
    implementation(libs.kotlinx.coroutines.core)

    // layoutlib and its loader, which :realview-layoutlib compiles against but does not carry.
    testImplementation(libs.layoutlib)
    testImplementation(libs.layoutlib.api)
    testImplementation(libs.android.tools.sdk.common)
    testImplementation(libs.kxml2)
    testRuntimeOnly(libs.android.tools.ninepatch)
}

tasks.named<Test>("test") {
    // The runtime home :realview-layoutlib assembles for its own tests.
    dependsOn(":realview-layoutlib:prepareLayoutlibHome")
    systemProperty("layoutlib.home", project(":realview-layoutlib").layout.buildDirectory.dir("layoutlib-home").get().asFile.absolutePath)
    systemProperty("render.out", layout.buildDirectory.dir("renders").get().asFile.absolutePath)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "2g"
}

// ---- what the iOS app bundles ------------------------------------------------------------------------------

// Everything the embedded JVM puts on its class path: this module, the engine under it, and layoutlib with
// its loader, which the engine compiles against but an IDE on a device never carries.
val iosPayload: Configuration by configurations.creating {
    isCanBeConsumed = false
    extendsFrom(configurations.runtimeClasspath.get())
}

dependencies {
    iosPayload(libs.layoutlib)
    iosPayload(libs.layoutlib.api)
    iosPayload(libs.android.tools.sdk.common)
    iosPayload(libs.kxml2)
    iosPayload(libs.android.tools.ninepatch)
    // The dexer and the signer the Android build runs in this process (the engine compiles against them only).
    iosPayload(libs.android.r8)
    iosPayload(libs.android.apksig)
}

/** `build/ios-payload/jars`: one flat folder of jars, which is what the app's JVM is pointed at. */
val assembleIosPayload by tasks.registering(Sync::class) {
    from(tasks.named("jar"))
    from(iosPayload)
    into(layout.buildDirectory.dir("ios-payload/jars"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// A java.desktop the embedded JVM can use: see the one class under src/desktopPatch for why. It is compiled
// into the module it replaces a class of, and the app hands the jar to the JVM as `--patch-module`.
val desktopPatchSources = layout.projectDirectory.dir("src/desktopPatch/java")
val compileDesktopPatch by tasks.registering(JavaCompile::class) {
    source = fileTree(desktopPatchSources)
    classpath = files()
    options.compilerArgs.addAll(listOf("--patch-module", "java.desktop=${desktopPatchSources.asFile.absolutePath}"))
    destinationDirectory.set(layout.buildDirectory.dir("desktop-patch/classes"))
}
val desktopPatchJar by tasks.registering(Jar::class) {
    from(compileDesktopPatch.flatMap { it.destinationDirectory })
    archiveFileName.set("java.desktop.jar")
    destinationDirectory.set(layout.buildDirectory.dir("ios-payload/patch"))
}
assembleIosPayload { dependsOn(desktopPatchJar) }

// ---- the build's tools, compiled ahead of time (see aot/build-ios.sh) --------------------------------------

// The Kotlin compiler as the build drives it (:lang-kotlin's JVM side and everything under it) with the three
// compiler plugins the IDE bundles. An image compiled ahead of time loads no class at run time, so the
// plugins are compiled into it beside the compiler.
val kotlincAot: Configuration by configurations.creating {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute, org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm)
    }
}

dependencies {
    kotlincAot(project(":lang-kotlin"))
    kotlincAot(libs.kotlin.compose.compiler.plugin.ide) { isTransitive = false }
    kotlincAot(libs.kotlin.serialization.compiler.plugin.ide) { isTransitive = false }
    kotlincAot(libs.kotlin.parcelize.compiler.plugin.ide) { isTransitive = false }
}

/** `build/aot/kotlinc/jars`: the class path of the Kotlin compiler's image. */
val assembleKotlincAot by tasks.registering(Sync::class) {
    from(kotlincAot)
    into(layout.buildDirectory.dir("aot/kotlinc/jars"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
