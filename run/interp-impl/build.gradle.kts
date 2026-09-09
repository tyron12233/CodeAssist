plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// interp-impl: the engine behind the published `:interp-api`. It turns the narrowed SPI a plugin compiles
// against into calls on the two interpreters the IDE already has. A SOURCE session drives :interp-core's
// tree-walking interpreter over a lowered program; a BYTECODE session drives :jvm-interp's VM over compiled
// classes. Lowering itself lives in :ide-core (it needs the project's analyzers); this module owns the
// concrete `LoweredProgram`, so ide-core and the sessions share the real lowered types while a plugin sees
// only the interface. See docs/plugin-interpreter.md.
dependencies {
    // The SPI this implements appears in every public signature here.
    api(project(":interp-api"))

    // The source interpreter. `api` because the concrete LoweredProgram carries :lang-kotlin's ResolvedFunction
    // /ResolvedClass (re-exported by :interp-core) and :ide-core constructs one.
    api(project(":interp-core"))

    // The bytecode VM behind a BytecodeSession. `api` because the optional VM_PEER_FACTORY port is typed to
    // its PeerFactory. The device launcher registers a dexing one (ART cannot define a class from class-file
    // bytes), so that type has to be nameable by whoever registers it.
    api(project(":jvm-interp"))

    // A source session's real test is over real Kotlin, so the tests lower source, which needs the compiler's
    // PSI. Test-only: the interpreter itself never parses (it walks an already-lowered program).
    testImplementation(project(":kotlin-compiler-deps"))
}

// The test fixtures live in src/test/java (plain Java gives clean bytecode with no Kotlin intrinsics),
// compiled at Java 17 like the rest of the project so a session sees no newer class-file features than the
// VM handles. Mirrors :jvm-interp's own fixtures.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
