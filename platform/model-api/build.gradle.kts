import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// model-api — the value types and interfaces the rest of the SPI is built out of, and the only part of it
// with no platform behind it.
//
// Extracted from :platform-core, :vfs-api and :language-api, packages unchanged, because a symbol model
// that cannot be NAMED from common code cannot be BUILT from common code. `:lang-kotlin-index` is compiler-
// free and would otherwise port to iOS unchanged, except that its `KotlinSymbol` implements
// `dev.ide.lang.resolve.Symbol`, whose `declaration()` returns a `DomNode` and whose origin holds a
// `VirtualFile`. Those three types, plus the `ContentHash` a file hashes to, are the whole closure.
//
// What stayed behind is what actually needs a JVM: the message bus is typed by `java.lang.Class`, and the
// file system, watches and event stream hang off it. Nothing here does.
//
// Its three former homes `api`-depend on it, so every existing `import dev.ide.lang.resolve.TypeRef` and
// `import dev.ide.vfs.VirtualFile` resolves exactly as before and no consumer changes.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Published for plugin authors to compile against, like the modules it was extracted from.
    id("dev.ide.spi-pom")
    `maven-publish`
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    iosSimulatorArm64()

    iosArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        val iosMain = create("iosMain") { dependsOn(getByName("commonMain")) }
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        getByName("iosArm64Main").dependsOn(iosMain)
        val iosTest = create("iosTest") { dependsOn(getByName("commonTest")) }
        getByName("iosSimulatorArm64Test").dependsOn(iosTest)
        getByName("iosArm64Test").dependsOn(iosTest)
    }
}

// Central requires a javadoc artifact. Kotlin produces none without Dokka, and the KDoc travels in the
// sources jar the multiplatform plugin already publishes, so this satisfies the requirement without adding
// a documentation toolchain. The same trade `dev.ide.spi-publish` makes for the JVM-only SPI artifacts.
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(javadocJar)
    }
}

dependencies {
    "jvmTestImplementation"(platform(libs.junit.bom))
    "jvmTestImplementation"(libs.junit.jupiter)
    "jvmTestRuntimeOnly"(libs.junit.platform.launcher)
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
