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
    // Published for plugin authors to compile against. The multiplatform convention rather than
    // `dev.ide.spi-publish`: that one applies `java-library`, which a multiplatform module cannot, and a
    // module published as several components needs a javadoc placeholder per publication rather than one
    // shared jar. Same coordinate, same POM, same BOM entry (`PluginBomTest` matches either opt-in).
    id("dev.ide.spi-publish-mpp")
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    iosSimulatorArm64()

    iosArm64()

    sourceSets {
        commonMain.dependencies {
            // EngineCancellation installs its token as a coroutine context element on the JVM, so the
            // primitive has to be nameable from common code even though only the JVM actual uses that path.
            api(libs.kotlinx.coroutines.core)
        }

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

dependencies {
    "jvmTestImplementation"(platform(libs.junit.bom))
    "jvmTestImplementation"(libs.junit.jupiter)
    "jvmTestRuntimeOnly"(libs.junit.platform.launcher)
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
