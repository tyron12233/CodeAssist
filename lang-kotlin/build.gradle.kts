plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// lang-kotlin — the Kotlin LanguageBackend: editor-time code intelligence (member completion, name/type
// resolution, a small inference subset) behind the backend-neutral language-api SPI.
//
// Editor strategy: use the Kotlin compiler ONLY to parse (PSI/KtFile), discard all of its resolution/FIR,
// and build our own symbol table, inference, and completion on the neutral DOM. Codegen (K2 -> .class) is a
// separate track: KotlinJvmCompiler/IncrementalKotlinCompiler, driven by the module's own compileKotlin
// build task (KotlinCompileTask) rather than a build-engine port.
dependencies {
    api(project(":language-api"))            // the SPI (+ project-model-api / vfs-api / platform-core, transitively)
    implementation(project(":index-api"))    // Java/Android interop: member & type shape via the shared indexes
    implementation(project(":analysis-api")) // owns the Kotlin diagnostic + import-fix providers
    // Owns the `compileKotlin` build task (KotlinCompileTask): the build graph drives K2 directly through it,
    // so build-engine carries no KotlinCompile port. Brings build-api transitively.
    implementation(project(":build-engine"))

    // The Kotlin frontend's PARSER only — a resolution-free standalone PSI host (text -> KtFile). We never
    // build a BindingContext or run the analyzer; all semantics are ours. compiler-embeddable bundles the
    // intellij-core PSI machinery the parser needs.
    implementation(libs.kotlin.compiler.embeddable)
    // Decode Kotlin libraries' @kotlin.Metadata to recover real Kotlin signatures (extension functions,
    // properties, default args, nullability) that plain bytecode erases. Small + compiler-free.
    implementation(libs.kotlin.metadata.jvm)
    // Read the @Metadata annotation values (and Java bytecode shape, for the index-free fallback) off
    // classpath .class files.
    implementation(libs.ow2.asm)

    // Tests build a real workspace + classpath to exercise the Module -> CompilationContext bridge and the
    // parser/symbol/completion pipeline end to end.
    testImplementation(project(":project-model-impl"))
    testImplementation(project(":index-impl")) // wire the real persistent index to reproduce the device path
    // Opt-in regression suites (`regressionTest`): shared benchmark/baseline/memory harness.
    testImplementation(project(":bench-support"))
    testImplementation(libs.kotlinx.coroutines.test)
    // Real Compose runtime jar on the test classpath: KotlinComposeBuildTest compiles a @Composable against
    // it (with the bundled Compose plugin) and asserts the synthetic-param transform. The test self-gates
    // (assumeTrue) when the jar isn't resolvable, so CI without the Compose repo just skips it.
    testImplementation(libs.compose.runtime.desktop)
}

// Bundle the kotlin-stdlib JAR — the SAME version the editor/compiler target (`libs.versions.toml` `kotlin`)
// — as a classpath resource (`/kotlin-stdlib.jar`). On-device (ART) the stdlib must be a real jar we control:
// it is never located from the host runtime via `Unit::class`, whose code source is the app's dex, not a jar
// kotlinc or the symbol reader can open. `BundledKotlinStdlib` extracts it at runtime; `IdeServices` attaches
// the extracted jar as the `kotlin-stdlib` library dependency of every Kotlin module (so it resolves onto the
// compile AND run/dex classpaths through the normal classpath machinery).
val bundledStdlib: Configuration by configurations.creating { isTransitive = false }
dependencies { bundledStdlib(libs.kotlin.stdlib) }

// Bundle the Compose compiler-plugin JAR (`/kotlin-compose-compiler-plugin.jar`) the same way: when a module
// depends on the Compose runtime, the in-process K2JVMCompiler is fed this jar via `-Xplugin` so @Composable
// functions get the plugin transform. `ComposeCompilerPlugin` extracts it; the host applies it per-module.
val bundledComposePlugin: Configuration by configurations.creating { isTransitive = false }
dependencies { bundledComposePlugin(libs.kotlin.compose.compiler.plugin) }

tasks.processResources {
    from(bundledStdlib) { rename { "kotlin-stdlib.jar" } }
    from(bundledComposePlugin) { rename { "kotlin-compose-compiler-plugin.jar" } }
}

// `src/test/.../Test.kt` is a scratch file for manually comparing completion against IntelliJ in the IDE.
// Exclude it from compilation so half-typed experiments there (an unresolved `ln`, a bare type, …) never
// break the build. It stays a `.kt` in the source root so IntelliJ still gives it full editing support.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    exclude("**/Test.kt")
}
