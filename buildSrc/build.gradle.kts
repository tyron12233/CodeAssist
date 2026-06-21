// Build logic shared by the project's Gradle scripts. Currently hosts `RelocateTypesInJar`, the
// dependency-free bytecode relocator used to make Eclipse ecj load on Android/ART (see that task).
//
// NOTE: the Kotlin-compiler-on-ART instrumentation (`dev.ide.kotlinc-art`) does NOT live here — it
// implements AGP types (AsmClassVisitorFactory), which buildSrc's classloader cannot see at runtime, so it
// lives in the `build-logic` included build (applied as a plugin, sharing AGP's classloader).
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
