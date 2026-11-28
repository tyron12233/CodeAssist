import java.security.MessageDigest
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
            implementation(project(":ide-ui")) // the whole desktop shell: CodeAssistApp
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
//   ./gradlew :playground-ui:assemblePlayground            ->  build/playground-web/  (development mode, no Binaryen)
//   ./gradlew :playground-ui:assemblePlaygroundProduction  ->  build/playground-web-prod/  (shrunk with `wasm-opt`)
// The production one needs `wasm-opt` on the machine (see the root build.gradle.kts): Gradle's own Binaryen
// download is a project repository, which the settings' repository policy refuses.
//
// Caching: the worker and the jars live in directories named for a hash of everything the page loads (`worker-<h>/`,
// `jars-<h>/`) and the page asks for exactly those, so a browser can never run a new page against an old worker or
// old jars (that mix fails in odd ways: a run that "finishes" with nothing, a worker that dies on a missing method).
// Those directories, and the wasm files webpack hashes itself, can be cached forever; only `index.html` and
// `playground-ui.js` (fixed names) have to be revalidated on each visit. See docs/playground.md.
fun registerAssemble(name: String, dist: String, distTask: String, out: String) = tasks.register<Sync>(name) {
    dependsOn(distTask, ":playground-spike:assemblePlayground")
    val spike = project(":playground-spike").layout.buildDirectory.dir("playground-web")
    val ui = layout.buildDirectory.dir(dist)
    val outDir = layout.buildDirectory.dir(out)
    into(outDir)
    // Staged as plain directories first; the hash is known only once their content is.
    from(ui) { exclude("index.html") }
    from(spike.map { it.dir("worker") }) { into("worker") }
    from(spike.map { it.dir("jars") }) { into("jars") }
    from(layout.projectDirectory.file("src/wasmJsMain/resources/index.html"))
    doLast {
        val root = outDir.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        listOf("worker", "jars").forEach { d ->
            root.resolve(d).walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).path }.forEach {
                digest.update(it.relativeTo(root).path.toByteArray()); digest.update(it.readBytes())
            }
        }
        digest.update(root.resolve("playground-ui.js").readBytes())
        val hash = digest.digest().joinToString("") { "%02x".format(it) }.take(12)
        root.resolve("worker").renameTo(root.resolve("worker-$hash"))
        root.resolve("jars").renameTo(root.resolve("jars-$hash"))
        val index = root.resolve("index.html")
        index.writeText(index.readText().replace("@ASSETS@", hash))
    }
}
registerAssemble("assemblePlayground", "dist/wasmJs/developmentExecutable", "wasmJsBrowserDevelopmentExecutableDistribution", "playground-web")
registerAssemble("assemblePlaygroundProduction", "dist/wasmJs/productionExecutable", "wasmJsBrowserDistribution", "playground-web-prod")
