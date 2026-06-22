plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// jvm-build — the JVM-language build system. JavaBuildSystem + JavaPlugin assemble the task DAG
// (compileKotlin -> compileJava -> classes -> jar) over build-engine's generic incremental engine, wiring
// in each language module's OWN compile task: lang-jdt's JdtCompileTask (ecj) and lang-kotlin's
// KotlinCompileTask (K2). This is the composition layer that used to be build-engine's JavaCompile/
// KotlinCompile ports: the languages now own their compile tasks, and this module just composes them, so
// build-engine stays compiler-free. Android reuses JavaPlugin.registerModule for its plain library modules.
dependencies {
    api(project(":build-engine"))   // the generic engine + neutral tasks/helpers (brings build-api/project-model-api)
    api(project(":lang-kotlin"))    // KotlinCompileTask (K2) + IncrementalKotlinCompiler (exposed in the public ctor)
    implementation(project(":lang-jdt")) // JdtCompileTask (ecj) — constructed internally

    // Builds a real workspace and compiles/runs it through the real ecj/K2, exactly as the host wires it.
    testImplementation(project(":project-model-impl"))
    testImplementation(project(":bench-support")) // opt-in regression suite: build-at-scale benchmark
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
