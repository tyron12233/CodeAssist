import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// kotlin-symbols (EXPERIMENTAL): the port probe for :lang-kotlin-index.
//
// :kotlin-classfile proves we can read a classpath without a JVM, and it proves it by agreeing with ASM and
// kotlin-metadata-jvm on what is IN the bytes. That is not the same claim as "the consumer ports". The
// consumer is 400 lines of rules ABOUT those bytes: which members are hidden, how a JVM primitive maps to a
// Kotlin classifier, when a parameter name is real, what a `byte[][]` is called. Those rules are where a
// rewrite actually goes wrong, and no oracle at the ASM level can see them.
//
// So this rewrites that layer against the portable decoder and diffs it, symbol for symbol, against the real
// `JavaBytecode` running with ASM over android.jar and the test classpath. `:lang-kotlin-index` is a jvmTest
// dependency for exactly that reason and for no other.
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
        commonMain.dependencies {
            implementation(project(":kotlin-classfile"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        // The implementation being ported, so the port can be diffed against it. Test-only by nature.
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":lang-kotlin-index"))
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
}
