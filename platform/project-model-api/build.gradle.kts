import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// project-model-api — the spine of the framework: Workspace/Project/Module, the modification
// transactions, the module types, and the project-template SPI.
//
// MULTIPLATFORM, and the split follows what the model IS rather than where it is used. A `Module` is a name,
// a type, a language level and a list of source sets whose roots are RELATIVE paths — none of that is a
// filesystem. What is JVM-bound is the surfaces that hand out `java.nio.file.Path`: resolving a source-set
// base to an absolute path, writing a resource file, detecting a Gradle project on disk. Those stay in
// `jvmMain`, so the core model — and the project TEMPLATES that author it — can be named from common code.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Published for plugin authors to compile against. `dev.ide.spi-publish` applies `java-library`, which
    // the multiplatform plugin rejects, so this takes the coordinate/POM half directly and supplies the
    // javadoc placeholder itself, exactly as :model-api does.
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
        commonMain.dependencies {
            // Coordinate/Exclusion, VirtualFile, ContentHash, ServiceKey and the extension framework all
            // appear in this module's public signatures, so `api`.
            api(project(":model-api"))
            // The VFS and the message bus. Both used to be JVM-bound and both crossed with `Topic`, which
            // no longer carries a `java.lang.Class`; the model's own event topics sit in `commonMain`
            // beside the events they carry because of it.
            api(project(":vfs-api"))
            api(project(":platform-core"))
        }

        // Declared by hand: `kotlin.mpp.applyDefaultHierarchyTemplate=false` (see gradle.properties).
        val iosMain = create("iosMain") { dependsOn(getByName("commonMain")) }
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        getByName("iosArm64Main").dependsOn(iosMain)
    }
}

// Central requires a javadoc artifact. Kotlin produces none without Dokka, and the KDoc travels in the
// sources jar the multiplatform plugin already publishes.
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(javadocJar)
    }
}
