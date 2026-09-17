import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// lang-kotlin-index — the pure, compiler-FREE Kotlin symbol/index layer, shared by the two Kotlin
// backends: lang-kotlin (the hand-rolled editor backend) and lang-kotlin-aa (the K2 Analysis API
// completion runtime). It decodes classpath @kotlin.Metadata and Java bytecode into neutral symbol/type
// shapes and exposes the KotlinCallableIndex / KotlinTypeShapeIndex / KotlinBuiltinsIndex IndexExtensions
// over index-api.
//
// It carries NO PSI / IntelliJ / compiler dependency, so it is safe to load into the isolated AA
// classloader alongside the un-relocated org.jetbrains.kotlin.* -for-ide artifacts.
//
// MULTIPLATFORM as of the model split, and in place rather than as a second module: it is not published,
// and its three consumers are all on the JVM, so they resolve the jvm variant and nothing about them
// changes. Everything still lives in `jvmMain`; the symbols layer moves to `commonMain` file by file as
// each one loses its last JVM dependency, which is what :kotlin-classfile and :model-api exist to let it
// do. Converting the module first, with nothing moved, keeps that a diff about one file at a time.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    iosSimulatorArm64()

    iosArm64()

    sourceSets {
        commonMain.dependencies {
            // The neutral symbol model surface (TypeRef/SymbolKind/Modifier/SymbolOrigin), now common.
            api(project(":model-api"))
            // Jars, class files and `@kotlin.Metadata`, with no JVM behind any of it. What ASM,
            // kotlin-metadata-jvm and java.util.zip did on the JVM-only path, and the reason the decoders
            // below can live in commonMain at all.
            implementation(project(":kotlin-classfile"))
        }

        jvmMain.dependencies {
            api(project(":language-api"))
            api(project(":index-api"))    // IndexExtension SPI + shared value types
            // Decode Kotlin libraries' @kotlin.Metadata to recover real Kotlin signatures (extensions,
            // properties, default args, nullability) that plain bytecode erases. Small + compiler-free.
            implementation(libs.kotlin.metadata.jvm)
            // Read the @Metadata annotation values (and Java bytecode shape) off classpath .class files.
            implementation(libs.ow2.asm)
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
