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
    // ASM — rewrites System.exit + sensitive network/file/reflection/exec calls in user/library bytecode for
    // the in-process on-device dex-run (exit ends the run, not the IDE; sensitive calls are permission-gated).
    // asm-tree gives the instruction-list API SandboxGuard needs for the new/dup/invokespecial ctor rewrite.
    // Pure-Java; dexes fine on ART.
    implementation(libs.ow2.asm)
    implementation(libs.ow2.asm.tree)

    testImplementation(project(":project-model-impl"))
    testImplementation(project(":lang-jdt")) // the JDT batch compiler, for the build-and-run exit test
    testImplementation(project(":bench-support")) // opt-in regression suite: build-at-scale benchmark
    testImplementation(libs.kotlinx.coroutines.core)
}
