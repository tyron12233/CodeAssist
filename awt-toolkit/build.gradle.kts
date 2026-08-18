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
