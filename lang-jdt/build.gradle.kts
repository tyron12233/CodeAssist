plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// The default Java language backend on Eclipse JDT (the batch compiler + DOM). JDT is error-tolerant
// (statement + binding recovery), so completion works on broken code, and its bindings give the
// modifiers/access needed for smart filtering. Implements the backend-neutral language-api SPI.
dependencies {
    api(project(":language-api"))
    implementation(project(":index-api")) // contributes the `members` index (bytecode via ecj)
    implementation(project(":analysis-api")) // owns the Java analyzers + code-action providers
    // Owns the `compileJava` build task (JdtCompileTask): the build graph drives ecj directly through it,
    // so build-engine carries no JavaCompile port. Brings build-api transitively.
    implementation(project(":build-engine"))
    implementation(libs.jdt.core)

    // Tests build a real workspace to exercise the Module -> CompilationContext bridge.
    testImplementation(project(":project-model-impl"))
    // Opt-in regression suites (`regressionTest`): shared benchmark/baseline/memory harness + quality scorer.
    testImplementation(project(":bench-support"))
}
