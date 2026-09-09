plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// build-engine — the implementation of build-api's generic incremental task engine (fingerprints,
// up-to-date checks, persistent cache, bounded-parallel execution) plus the native Java build system
// (compileJava → jar). Compilation is injected via a JavaCompile port so the engine stays decoupled
// from any language backend; the JDT compiler is wired in by the host (and by tests).
dependencies {
    api(project(":build-api"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":project-model-impl"))
    testImplementation(libs.kotlinx.coroutines.core)
    // The native build system (JavaBuildSystem) + its build-and-run exit test and the build-at-scale
    // regression benchmark moved to :jvm-build (which owns JavaBuildSystem now); their deps moved with them.
}
