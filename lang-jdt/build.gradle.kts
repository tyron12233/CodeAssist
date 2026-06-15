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
    implementation(libs.jdt.core)

    // Tests build a real workspace to exercise the Module -> CompilationContext bridge.
    testImplementation(project(":project-model-impl"))
    // Opt-in regression suites (`regressionTest`): shared benchmark/baseline/memory harness + quality scorer.
    testImplementation(project(":bench-support"))
}
