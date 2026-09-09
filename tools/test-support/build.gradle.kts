plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Shared test-only support library (package `dev.ide.testkit`). It provides the cross-module test
// infrastructure that every module used to re-implement: temp-dir lifecycles, source seeding, VirtualFile
// and DocumentSnapshot stubs, classpath/jar locators + an ASM jar builder, CompilationContext builders, a
// fake IndexService, workspace bootstrap helpers, a suspend-to-sync bridge, and completion assertions.
//
// Consumed ONLY via `testImplementation` (auto-wired in the root build), never on any runtime classpath.
// It depends on the framework's lower layers so its fixtures can speak the real domain types; because it is
// always a test-scoped dependency downstream, even the modules it builds on can wire it without a cycle.
// :bench-support (the benchmark/regression primitives) is re-exported so one dependency yields both.
dependencies {
    api(project(":project-model-impl"))
    api(project(":language-api"))
    api(project(":index-api"))
    api(project(":bench-support"))
    api(libs.ow2.asm)
}
