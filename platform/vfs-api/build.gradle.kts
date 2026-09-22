import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// vfs-api — the file system, its watches, and the one ordered event stream the whole IDE observes.
//
// Multiplatform because the project model reads files through it, and the project model has to reach iOS.
// What kept it on the JVM was never this file: it was `Topic`, which used to be typed by `java.lang.Class`
// (see :platform-core). With that gone, these fifty lines carry nothing a host has to supply — the
// IMPLEMENTATION does, and that lives in :project-model-impl.
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
            // vfs-api -> platform-core. Platform types (ContentHash, Disposable, Topic) appear in vfs-api's
            // public signatures, so the dependency is `api` (transitively visible to consumers).
            api(project(":platform-core"))
            // VirtualFile itself lives in :model-api; the file system, watches and event stream stay here.
            api(project(":model-api"))
        }

        val iosMain = create("iosMain") { dependsOn(getByName("commonMain")) }
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        getByName("iosArm64Main").dependsOn(iosMain)
    }
}
