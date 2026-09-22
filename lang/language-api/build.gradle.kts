import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// language-api — the SPI a language backend implements, and (as of the multiplatform split) mostly a set of
// value types with no platform behind them.
//
// MULTIPLATFORM so that a language backend can be COMPILED for a non-JVM target: `:lang-kotlin`'s editor half
// is otherwise portable, and was pinned to the JVM only because the SPI it implements could not be named from
// common code. In place rather than as a second module, and with everything still in `commonMain` except what
// genuinely needs a JVM, because the consumers are all on the JVM today and resolve the jvm variant unchanged.
//
// What stays in `jvmMain` is the two files that were already named for it: `JvmIndexScopeProvider` and
// `JvmSourceAttachments` both speak `java.nio.file.Path`, i.e. a real filesystem, which is exactly the thing a
// portable SPI must not assume.
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
            // The DOM and the symbol model moved to :model-api (packages unchanged) so that they can be named
            // from common code. Both are all over this module's public signatures, so the dependency is `api`.
            api(project(":model-api"))
        }

        jvmMain.dependencies {
            // ClasspathSnapshot and LanguageLevel appear in the LanguageBackend / CompilationContext SPIs,
            // and the project model behind them is JVM-bound (it is a real filesystem and a real build).
            api(project(":project-model-api"))
            api(project(":vfs-api"))
            api(project(":platform-core"))
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            // The root build adds this to every `kotlin.jvm` module's `test` source set; a multiplatform
            // module has no `test` source set for that to reach, so it is named here. Same shape as the JUnit
            // wiring below, and the same trap: without it the suite fails to COMPILE rather than being skipped.
            implementation(project(":test-support"))
            // ModuleCompilationContext binds analysis to the model, so its test needs a real workspace to bind
            // to. Test-only, so the published module still depends on the api alone (test -> impl stays acyclic).
            implementation(project(":project-model-impl"))
        }

        // Declared by hand: `kotlin.mpp.applyDefaultHierarchyTemplate=false` (see gradle.properties).
        val iosMain = create("iosMain") { dependsOn(getByName("commonMain")) }
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        getByName("iosArm64Main").dependsOn(iosMain)
    }
}

// The root build wires JUnit 5 into every `kotlin.jvm` module; a multiplatform one has to say so itself.
dependencies {
    "jvmTestImplementation"(platform(libs.junit.bom))
    "jvmTestImplementation"(libs.junit.jupiter)
    "jvmTestRuntimeOnly"(libs.junit.platform.launcher)
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
