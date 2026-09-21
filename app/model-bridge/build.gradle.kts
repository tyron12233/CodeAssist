import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// model-bridge — the project model, as the UI's module screens see it.
//
// Between :project-model-api/:project-model-impl (what a module IS) and :ide-ui-api (what the Module
// Settings and Dependencies screens can draw) sits a layer of pure translation: a module onto
// `UiModuleConfig`, a facet table onto a generic field list, an edit back through a model transaction, a
// resolved dependency closure onto the library table. None of it needs a build, an engine or a host, which
// is why it is a module rather than part of :ide-core.
//
// It exists because the three hosts were about to answer the same questions twice. :ide-core's
// `ModuleService` is the JVM/Android answer and carries everything a BUILD adds (build features, compiler
// plugins, packaging, keep rules, toolchain warnings, a detected main class); the iOS host has none of that
// and needs the same model reading and the same model writing underneath. Whatever is true of the model
// rather than of a toolchain belongs here, once.
//
// What it deliberately does NOT hold is orchestration: which analyzers to invalidate, when to re-index,
// what a build file must also be told. That is the host's, and each one answers it its own way.
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
            // The model this reads and writes. `api`, because the callers hand it modules and stores.
            api(project(":project-model-api"))
            api(project(":project-model-impl"))
            // The Ui* DTOs it maps onto. `api`, because every function here returns one.
            api(project(":ide-ui-api"))
            // Coordinates, the portable filesystem (a created source root is a real directory) and the log.
            implementation(project(":model-api"))
            // Resolved artifacts, for the library attachment: a closure is what fills the library table.
            api(project(":deps-api"))
            implementation(project(":deps-impl"))
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":platform-core"))
            implementation(project(":project-templates"))
        }

        // Declared by hand: the build turns the default hierarchy template off (see gradle.properties).
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

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
