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
    api(project(":jvm-interp"))     // the bytecode VM: VmProgramInterpreter runs a module's compiled program
    implementation(libs.kotlinx.coroutines.core) // VmProgramInterpreter runs the program on a cancellable coroutine
    // A test run reads the compiled tests' annotations and GENERATES the runner class that calls them (test/).
    implementation(libs.ow2.asm)
    implementation(libs.ow2.asm.tree)

    // Builds a real workspace and compiles/runs it through the real ecj/K2, exactly as the host wires it.
    testImplementation(project(":project-model-impl"))
    testImplementation(project(":bench-support")) // opt-in regression suite: build-at-scale benchmark
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    // The test runner's fixtures are JUnit 4 classes too (JUnit 5 + kotlin.test come with the shared test stack).
    testImplementation(libs.junit4)
}

// `test/fixtures` holds tests FOR the IDE's test runner, several failing on purpose; the module's own test task
// must not pick them up as its tests.
tasks.named<Test>("test") {
    filter { excludeTestsMatching("dev.ide.build.jvm.test.fixtures.*") }
}
