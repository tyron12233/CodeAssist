plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// realview-layoutlib — a layout rendered by the real Android view classes with no device: layoutlib, the build
// of the framework Android Studio previews with, driven directly on a JVM. The Android app renders real views
// with the phone's own framework; this is the same picture for a host that has none: the desktop IDE's JVM,
// and the JVM embedded in the iOS app.
//
// layoutlib and its loader are large (a 63 MB framework jar, native graphics, fonts, ICU), so they are
// compileOnly here: the IDE does not carry them. They arrive as a runtime home, a folder assembled from the
// published artifacts (see LayoutlibHome), which is also what the tests render against.
dependencies {
    api(project(":layout-preview-api"))             // PreviewViewNode: the captured tree the Preview inspects
    api(project(":layout-preview-impl"))            // RealViewRuntime: the seam the IDE's real-view path renders through

    compileOnly(libs.layoutlib)
    compileOnly(libs.layoutlib.api)
    compileOnly(libs.android.tools.sdk.common)
    compileOnly(libs.android.tools.common)          // ILogger, which sdk-common's loader takes
    compileOnly(libs.guava)                         // the multimap sdk-common's loader answers with
    compileOnly(libs.kxml2)
    implementation(libs.ow2.asm)                    // R classes for library packages
    implementation(libs.ow2.asm.commons)            // android.os.Build -> the name layoutlib keeps it under

    testImplementation(libs.layoutlib)
    testImplementation(libs.layoutlib.api)
    testImplementation(libs.android.tools.sdk.common)
    testImplementation(libs.kxml2)
    testImplementation(project(":ide-core"))        // the IDE's own real-view path, rendered through this runtime
    testImplementation(project(":test-support"))
    // Not named by this module's code, but layoutlib loads it by name to draw a nine-patch (a Switch's thumb).
    testRuntimeOnly(libs.android.tools.ninepatch)
}

// ---- the runtime home the tests render against -------------------------------------------------------------

val os = System.getProperty("os.name").lowercase()
val arch = System.getProperty("os.arch").lowercase()
val nativeClassifier = when {
    os.contains("mac") && (arch == "aarch64" || arch == "arm64") -> "mac-arm"
    os.contains("mac") -> "mac"
    os.contains("win") -> "win"
    else -> "linux"
}

val layoutlibData: Configuration by configurations.creating { isCanBeConsumed = false; isTransitive = false }
val layoutlibNatives: Configuration by configurations.creating { isCanBeConsumed = false; isTransitive = false }
val layoutlibFrameworkRes: Configuration by configurations.creating { isCanBeConsumed = false; isTransitive = false }

// Libraries a fixture layout uses, resolved as an Android consumer would: AARs, not the JVM variants.
val fixtureLibraries: Configuration by configurations.creating {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.ANDROID))
        attribute(Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java), "androidJvm")
    }
}

dependencies {
    layoutlibData(libs.layoutlib.runtime)
    layoutlibNatives(variantOf(libs.layoutlib.runtime) { classifier(nativeClassifier) })
    layoutlibFrameworkRes(libs.layoutlib.resources)
    fixtureLibraries("com.google.android.material:material:1.12.0")
}

val layoutlibHomeDir = layout.buildDirectory.dir("layoutlib-home")

// build.prop, fonts, ICU, hyphenation and keyboard data, plus this host's native libraries, flattened to
// `native/`. The framework's own resources are copied raw; LayoutlibHome prepares them on first use.
val prepareLayoutlibHome by tasks.registering(Sync::class) {
    into(layoutlibHomeDir)
    from({ layoutlibData.map { zipTree(it) } }) {
        include("build.prop", "data/fonts/**", "data/icu/**", "data/hyphen-data/**", "data/keyboards/**")
    }
    from({ layoutlibNatives.map { zipTree(it) } }) {
        include("data/$nativeClassifier/lib64/**")
        eachFile { relativePath = RelativePath(true, "native", name) }
        includeEmptyDirs = false
    }
    from({ layoutlibFrameworkRes.map { zipTree(it) } }) {
        include("res/**")
        into("framework-res-raw")
    }
}

val fixtureLibrariesDir = layout.buildDirectory.dir("fixture-libraries")

// Each AAR unpacked to a folder of its own (res/, classes.jar, R.txt, AndroidManifest.xml); plain jars beside them.
val unpackFixtureLibraries by tasks.registering {
    val files = fixtureLibraries.incoming.artifactView { isLenient = true }.files
    inputs.files(files)
    outputs.dir(fixtureLibrariesDir)
    doLast {
        val out = fixtureLibrariesDir.get().asFile
        delete(out)
        files.forEach { file ->
            if (file.name.endsWith(".aar")) {
                copy {
                    from(zipTree(file)) { include("res/**", "classes.jar", "R.txt", "AndroidManifest.xml") }
                    into(File(out, "aar/" + file.name.removeSuffix(".aar")))
                }
            } else {
                copy { from(file); into(File(out, "jars")) }
            }
        }
    }
}

tasks.named<Test>("test") {
    dependsOn(prepareLayoutlibHome, unpackFixtureLibraries)
    systemProperty("layoutlib.home", layoutlibHomeDir.get().asFile.absolutePath)
    systemProperty("fixture.libraries", fixtureLibrariesDir.get().asFile.absolutePath)
    systemProperty("render.out", layout.buildDirectory.dir("renders").get().asFile.absolutePath)
    // layoutlib loads its native library with System.load.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "2g"
}
