// `build-logic` — Gradle plugins that depend on AGP. Applied to Android modules via the plugins DSL, so
// they load in the same plugin classloader as AGP (and land on AGP's instrumentation worker classpath),
// which buildSrc cannot do. Currently: `dev.ide.kotlinc-art`, the Kotlin-compiler-on-ART ASM
// instrumentation.
plugins {
    `kotlin-dsl`
}

dependencies {
    // AGP's variant + instrumentation API (AsmClassVisitorFactory, AndroidComponentsExtension), and ASM
    // (which the rewrite passes use). Both compileOnly: AGP ships them on the instrumentation runtime — AGP
    // bundles org.ow2.asm itself — so bundling our own copies would clash. Keep the versions aligned with
    // `agp` / `asm` in gradle/libs.versions.toml.
    compileOnly("com.android.tools.build:gradle-api:9.2.1")
    compileOnly("org.ow2.asm:asm:9.7")
    compileOnly("org.ow2.asm:asm-commons:9.7")
    compileOnly("org.ow2.asm:asm-tree:9.7") // ClassNode/MethodNode — body-replacement passes

    // Unit-test the ASM passes in isolation (no device/APK): feed synthetic bytecode through a pass and read
    // the result back. ASM is compileOnly for main (AGP provides it on the instrumentation worker), so the
    // tests bring their own copy.
    testImplementation("org.ow2.asm:asm:9.7")
    testImplementation("org.ow2.asm:asm-commons:9.7") // ClassRemapper — used by ClassValueArtPass
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

// The `dev.ide.kotlinc-art` plugin id → :ide-android applies it with one line.
gradlePlugin {
    plugins {
        create("kotlincArt") {
            id = "dev.ide.kotlinc-art"
            implementationClass = "dev.ide.build.kotlinc.KotlincArtPlugin"
        }
    }
}
