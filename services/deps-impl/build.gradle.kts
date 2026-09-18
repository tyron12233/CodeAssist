import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// deps-impl — the engine behind deps-api's DependencyResolver. Resolves `group:name:version`
// coordinates against Maven-layout repositories: fetches + parses .pom metadata (parent/properties/
// dependencyManagement merge), walks transitives with Maven scope-narrowing + exclusions, resolves
// version conflicts (newest-wins / fail / pinned), detects cycles, extracts `classes.jar` from .aar,
// and caches everything on disk under `.platform/caches/resolved-deps` (which doubles as the offline
// store). All network/file I/O is behind the injectable [ArtifactFetcher] port so the engine runs
// fully offline in tests against fixture repositories.
//
// MULTIPLATFORM, because a classpath is not a build. Resolution turns coordinates into URLs, walks POMs
// and writes files, and the EDITOR needs its output on every platform it runs on: without it an iOS host
// can open a project and resolve nothing from AndroidX or the Kotlin ecosystem. Two things are per-platform
// and they are the two the engine was already written around — the socket (`DepsHttp`) and the `.aar`
// reader (`explodeAar`, which is Android's format and has no meaning off it).
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
            implementation(project(":deps-api"))
            // The portable file system and the atomic whole-file write the cache is built on. `java.nio.file`
            // is what this used to be.
            implementation(project(":kotlin-classfile"))
            implementation(libs.kotlinx.coroutines.core) // bounded-parallel POM resolution + artifact downloads
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            // `resolve` is `suspend`, and the offline fixture suite drives it on every target.
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmTest.dependencies {
            // The root build adds these to every `kotlin.jvm` module's `test` source set; a multiplatform
            // module has no `test` source set for that to reach, so they are named here.
            implementation(project(":test-support"))
            implementation(project(":project-model-impl")) // LocalFileSystem for the VirtualFile factory in tests
            implementation(libs.kotlinx.coroutines.test)
        }

        // Declared by hand: `kotlin.mpp.applyDefaultHierarchyTemplate=false` (see gradle.properties).
        val iosMain = create("iosMain") { dependsOn(getByName("commonMain")) }
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        getByName("iosArm64Main").dependsOn(iosMain)
        val iosTest = create("iosTest") { dependsOn(getByName("commonTest")) }
        getByName("iosSimulatorArm64Test").dependsOn(iosTest)
        getByName("iosArm64Test").dependsOn(iosTest)
    }
}

// The root build wires JUnit 5 into every `kotlin.jvm` module; a multiplatform one has to say so itself.
dependencies {
    "jvmTestImplementation"(platform(libs.junit.bom))
    "jvmTestImplementation"(libs.junit.jupiter)
    "jvmTestRuntimeOnly"(libs.junit.platform.launcher)
}
