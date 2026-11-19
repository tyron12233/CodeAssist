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
