plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// jvm-interp — a standalone, stack-based JVM *bytecode* interpreter (reads `.class`), the proof-of-concept for
// running downloaded library code without handing it to ART's class loader (the Google Play dynamic-code
// restriction — see the interp discussion). It is deliberately NOT wired into the app: it stands alone with
// its own tests so the engine can be proven against real compiler output before the marshalling/bridge and
// host integration are designed. Sibling in spirit to :interp-core (which tree-walks lowered *source*); this
// one executes compiled bytecode and bridges the un-interpreted world (java.*, android.*) through a seam.
dependencies {
    // ASM tree API (ClassNode/MethodNode/InsnList) to decode `.class` into an editable instruction list — the
    // same ASM already used by :build-engine's SandboxGuard and :layout-preview-impl.
    implementation(libs.ow2.asm)
    implementation(libs.ow2.asm.tree)
    // GeneratorAdapter for emitting the real peer subclasses that route platform callbacks into the interpreter.
    implementation(libs.ow2.asm.commons)

    // The uniform test stack (JUnit 5 + kotlin-test) is added to every Kotlin/JVM module by the root build.
    // The Java fixtures compiled by the test source set become the real bytecode we interpret, with the real
    // method invocation as the oracle — no compiler is needed at test time (the `.class` is read off the classpath).
}

// The test fixtures live in src/test/java (plain Java → clean bytecode, no Kotlin intrinsics), compiled at
// Java 17 like the rest of the project so the interpreter sees ≤17 class-file features.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
