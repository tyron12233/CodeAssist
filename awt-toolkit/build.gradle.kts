plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// awt-toolkit — an OWNED implementation of the java.awt/javax.swing API surface, drawing through the
// same `RCanvas` the XML layout preview renders into, so one implementation serves desktop and device.
// Android ships no AWT and no Swing at all, and OpenJDK's java.desktop cannot be carried onto ART (native
// java2d pipelines, GPL sources, sun.* internals), so the widgets are reimplemented rather than ported.
//
// The classes deliberately do NOT live in `java.awt`/`javax.swing`: those package names are not app-definable
// (`java.*` is refused by every class loader, ART included). They mirror the real names one package prefix
// over -- `java.awt.Component` -> `dev.ide.awt.Component`, `javax.swing.JFrame` -> `dev.ide.swing.JFrame` --
// and `AwtNameRemapper` rewrites an interpreted program's references at class-load time, the same ASM
// remapping trick `:layout-preview-impl`'s BridgeRemapper uses to reparent a custom View onto BridgeView.
// The program therefore sees the API it wrote against, while the toolkit runs as ordinary bridged code.
dependencies {
    // RCanvas/RPaint/RGraphics: the owned drawing surface every renderer in the IDE already targets, with a
    // Compose backend on both platforms. Graphics2D is implemented over it.
    api(project(":layout-preview-api"))

    // ClassRemapper, for rewriting java.awt/javax.swing references in a program's bytecode.
    implementation(libs.ow2.asm)
    implementation(libs.ow2.asm.commons)

    // The end-to-end test interprets a real compiled Swing program against this toolkit, which is the whole
    // point of the remapping design; the VM is a test dependency only, never a production one.
    testImplementation(project(":jvm-interp"))
}

// The Java fixtures in src/test/java are the "user programs": plain Java compiled against the REAL
// java.awt/javax.swing on the test compile classpath, exactly as a user's module would be, then remapped onto
// this toolkit at interpret time. Compiled at Java 17 like the rest of the project.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

// --- the compile-time java.awt/javax.swing API jar ------------------------------------------------
// A project that uses Swing must COMPILE against those names, and the platform it compiles against usually
// lacks them: android.jar on device, and android.jar on the desktop too whenever an Android SDK is installed
// (IdeServices prefers it). SwingApiStubs runs the name remap BACKWARDS over this module's own classes to
// produce that API, so what a program compiles against and what it runs on can never drift apart.
//
// Compile-only, and shipped as an ordinary resource of :ide-core so one artifact serves desktop and device.
// It must never reach a class loader: nothing may define a class in java.*.
val swingApiStubsJar = tasks.register<JavaExec>("swingApiStubsJar") {
    description = "Generate the compile-time java.awt/javax.swing API jar from the owned toolkit."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.ide.awt.interp.SwingApiStubs")
    val out = layout.buildDirectory.file("swing-api/swing-api-stubs.jar")
    argsProvider(sourceSets.main.get().output.classesDirs, out)
    inputs.files(sourceSets.main.get().output.classesDirs)
    outputs.file(out)
}

/** The generator takes `<classesDir> <outJar>`; the Kotlin output dir is the one holding the toolkit. */
fun JavaExec.argsProvider(classesDirs: FileCollection, out: Provider<RegularFile>) {
    argumentProviders.add {
        listOf(classesDirs.first { it.path.contains("kotlin") }.absolutePath, out.get().asFile.absolutePath)
    }
}

/** Consumed by :ide-core, which embeds it as a resource. */
val swingApiStubs: Configuration by configurations.creating { isCanBeResolved = false }
artifacts.add(swingApiStubs.name, swingApiStubsJar.map { layout.buildDirectory.file("swing-api/swing-api-stubs.jar").get().asFile }) {
    builtBy(swingApiStubsJar)
}
