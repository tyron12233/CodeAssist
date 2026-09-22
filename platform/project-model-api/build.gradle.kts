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
