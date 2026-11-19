import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// android-layout-api: what the IDE offers a plugin that works on Android layout XML. A layout editor (the
// built-in one is a plugin, `:layout-editor`) renders through [LayoutRenderer], reads and edits the layout
// through [AndroidLayoutModel], and finds the views it can place through [AndroidViewCatalog]. The host
// implements all of them over its own machinery (the real-view renderer, the XML parser, the SDK attribute
// metadata and the project's indexes); nothing here names an engine type.
//
// IN-TREE ONLY for now: it is not in plugin-bom and not pinned by :spi-compat. It is published once the
// built-in layout editor has proved the shape (see docs/plugin-system.md).
// Multiplatform so the layout editor, which is written against it, can also be built for iOS.
kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    iosSimulatorArm64()

    iosArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":model-api"))
        }

        jvmTest.dependencies {
            implementation(project(":test-support"))
            implementation(libs.kotlin.test)
        }

        val iosMain = create("iosMain") { dependsOn(getByName("commonMain")) }
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        getByName("iosArm64Main").dependsOn(iosMain)
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
