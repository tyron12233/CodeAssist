import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// index-impl — the pragmatic engine behind index-api: in-memory term dictionary + postings + trigram
// index, per-artifact persistent cache keyed by content hash, prefix/fuzzy queries, incremental source,
// driven as cancellable background activities. Built-in indexes (classNames, packages, sourceSymbols)
// live here; the bytecode-reading `members` index lives in lang-jdt (it needs ecj).
//
// MULTIPLATFORM, in place: it is not published, and its three consumers are JVM, so they resolve the jvm
// variant unchanged. Everything starts in `jvmMain` and crosses to `commonMain` file by file as each loses
// its last JVM dependency, the same way :lang-kotlin-index did, so each move is a diff about one file.
//
// The one that looked hardest is not: BlockCache reads segments through FileChannel at an ABSOLUTE
// position, never a memory map, which is exactly what :kotlin-classfile's FileSource already does.
//
// Segment is the exception to "file by file": it split in half instead. READING a segment needs positioned
// reads and a byte decoder, which every platform has; WRITING one needs an external merge sort that spills
// its runs to temp files, which needs streaming file output the portable seam does not have. Splitting at
// that line got the QUERY path off the JVM without waiting for the build path, which is the half that
// matters -- completion queries a prebuilt index.
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
            // The half of the index SPI an extension implements, plus the symbol model.
            api(project(":model-api"))
            // Positional file reads, archives, and the portable data format the segments are written in.
            implementation(project(":kotlin-classfile"))
            implementation(libs.kotlinx.coroutines.core)
        }

        jvmMain.dependencies {
            implementation(project(":index-api"))
            implementation(project(":platform-core"))
            implementation(project(":language-api")) // for ParsedFile in IndexInput
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmTest.dependencies {
            // Wired by hand because the root build's shared test stack is keyed on the Kotlin/JVM plugin:
            // a multiplatform module gets neither, and :test-support is JVM-only anyway.
            implementation(project(":test-support"))
            // Opt-in regression suites (`regressionTest`): shared benchmark/baseline/memory harness.
            implementation(project(":bench-support"))
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
