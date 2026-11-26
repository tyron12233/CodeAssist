import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// playground-ui (EXPERIMENTAL): the Playground with the IDE's own editor.
//
// `:playground-spike` proved the engine in the browser and put CodeMirror in front of it. This puts the real
// thing in front of it instead: `CodeEditor` from `:ide-ui-editor`, Compose Multiplatform compiled for the
// browser, drawing its own text on a canvas. The language engine still lives in the worker `:playground-spike`
// builds; this module's backend forwards completion and diagnostics to it. Depended on by nothing.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig { outputFileName = "playground-ui.js" }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":ide-ui-editor"))
            implementation(project(":ide-ui-testing")) // StubBackend: every service a no-op, so only the editor's are overridden
            implementation(project(":platform-json"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

// The servable app: the Compose UI, plus the engine worker and the jars it fetches, which :playground-spike builds.
//   ./gradlew :playground-ui:assemblePlayground      ->  build/playground-web/
// Development mode (no Binaryen): the production distribution downloads Binaryen, which the settings' repository
// policy refuses, and a localhost demo does not need the smaller binary.
val assemblePlayground by tasks.registering(Sync::class) {
    dependsOn("wasmJsBrowserDevelopmentExecutableDistribution", ":playground-spike:assemblePlayground")
    into(layout.buildDirectory.dir("playground-web"))
    from(layout.buildDirectory.dir("dist/wasmJs/developmentExecutable"))
    val spike = project(":playground-spike").layout.buildDirectory.dir("playground-web")
    from(spike.map { it.dir("worker") }) { into("worker") }
    from(spike.map { it.dir("jars") }) { into("jars") }
}
