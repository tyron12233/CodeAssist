import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// kotlin-classfile (EXPERIMENTAL): reading a classpath with no JVM. Jars, `.class` files, and the Kotlin
// metadata inside them.
//
// The second of the two things that pin Kotlin COMPLETION to the JVM. The first was the parser, and that is
// solved: :kotlin-syntax vendors the compiler's own. This is the other half. `:lang-kotlin-index` is 3,244
// lines that are genuinely compiler-free, but they decode class files with ASM, `@kotlin.Metadata` with
// kotlin-metadata-jvm, and jars with java.util.zip. None of the three exists off the JVM.
//
// Unlike the parser, there is no upstream multiplatform version to borrow: `core/metadata` and
// `libraries/kotlinx-metadata` are both `kotlin("jvm")` and sit on JVM protobuf. So this is a real port. What
// makes it tractable is that the generated protobuf reader (34,545 lines) is not the thing to port: the
// SCHEMA is 709 lines, and only a dozen of its messages matter here. The class file, the two signature
// grammars, the zip container and DEFLATE are all fully specified and none of them has an extension point.
//
// Every piece is checked by diffing against the JVM library it replaces, over real corpora: ASM,
// kotlin-metadata-jvm and java.util.zip, run against this build's own output, kotlin-stdlib, android.jar and
// every jar on the test classpath. A decoder for someone else's binary format either agrees with the
// reference on real input or it is quietly wrong; hand-written expectations prove nothing.
//
// NOTHING depends on this module.
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
        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        // The JVM's own reading of the same bytes, to check ours against. Test-only by nature: it is what
        // this module exists to stop needing.
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlin.metadata.jvm)
            implementation(libs.ow2.asm)
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
    // The oracle reads real .class files out of this build's own output.
    systemProperty("kotlinClassfile.repoRoot", rootDir.absolutePath)
}
