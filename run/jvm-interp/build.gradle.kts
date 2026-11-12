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
    // The compiler the IDE builds user code with. Its bytecode for the same source differs from javac's (how a
    // switch over an enum or a pattern is lowered), so the Java fixtures are also compiled with it and run.
    testImplementation(libs.jdt.ecj)

    // The uniform test stack (JUnit 5 + kotlin-test) is added to every Kotlin/JVM module by the root build.
    // The Java fixtures compiled by the test source set become the real bytecode we interpret, with the real
    // method invocation as the oracle — no compiler is needed at test time (the `.class` is read off the classpath).
}

// The test fixtures live in src/test/java (plain Java → clean bytecode, no Kotlin intrinsics), compiled at
// Java 17 like the rest of the project so the interpreter sees ≤17 class-file features.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

// The test fixtures are the bytecode the VM is checked against, so they are compiled at the newest level it
// supports: a pattern `switch` (Java 21) is what puts a `SwitchBootstraps` call site in a class file. The
// Kotlin tests beside them stay on the project's target, which the JVM-target check would otherwise reject.
tasks.named<JavaCompile>("compileTestJava") {
    options.release.set(21)
}
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    jvmTargetValidationMode.set(org.jetbrains.kotlin.gradle.dsl.jvm.JvmTargetValidationMode.WARNING)
}

// The stand-ins for platform classes a host may lack (dev.ide.jvm.standin, see PlatformStandIns) are ordinary
// sources here, but the VM needs them as BYTECODE to interpret, and on Android a jar's .class entries become
// dex. So their class files are also shipped as resources under another extension, with a list of them.
val standinClassResources = tasks.register<Sync>("standinClassResources") {
    val out = layout.buildDirectory.dir("standin-resources")
    from(tasks.named<JavaCompile>("compileJava").flatMap { it.destinationDirectory }) {
        include("dev/ide/jvm/standin/**/*.class")
        rename { it.removeSuffix(".class") + ".bin" }
        into("standin-classes")
    }
    into(out)
    doLast {
        val root = out.get().asFile.resolve("standin-classes")
        val names = root.walkTopDown().filter { it.isFile && it.extension == "bin" }
            .map { it.relativeTo(root).invariantSeparatorsPath.removeSuffix(".bin") }.sorted().toList()
        root.resolve("index.txt").writeText(names.joinToString("\n"))
    }
}
sourceSets.named("main") {
    resources.srcDir(files(layout.buildDirectory.dir("standin-resources")).builtBy(standinClassResources))
}
