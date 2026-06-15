plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// lang-kotlin — the Kotlin LanguageBackend: editor-time code intelligence (member completion, name/type
// resolution, a small inference subset) behind the backend-neutral language-api SPI.
//
// Strategy: use the Kotlin compiler ONLY to parse (PSI/KtFile), discard all of its resolution/FIR, and
// build our own symbol table, inference, and completion on top of the neutral DOM. This is an EDITOR
// backend only — createCompiler() is null; it never emits .class/.dex (Kotlin codegen is a separate
// build track, out of scope here).
dependencies {
    api(project(":language-api"))            // the SPI (+ project-model-api / vfs-api / platform-core, transitively)
    implementation(project(":index-api"))    // Java/Android interop: member & type shape via the shared indexes

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
    testImplementation(libs.kotlinx.coroutines.test)
}

// Bundle the kotlin-stdlib JAR — the SAME version the editor/compiler target (`libs.versions.toml` `kotlin`)
// — as a classpath resource (`/kotlin-stdlib.jar`). On-device (ART) the stdlib must be a real jar we control:
// it is never located from the host runtime via `Unit::class`, whose code source is the app's dex, not a jar
// kotlinc or the symbol reader can open. `BundledKotlinStdlib` extracts it at runtime; `IdeServices` attaches
// the extracted jar as the `kotlin-stdlib` library dependency of every Kotlin module (so it resolves onto the
// compile AND run/dex classpaths through the normal classpath machinery).
val bundledStdlib: Configuration by configurations.creating { isTransitive = false }
dependencies { bundledStdlib(libs.kotlin.stdlib) }
tasks.processResources {
    from(bundledStdlib) { rename { "kotlin-stdlib.jar" } }
}

// `src/test/.../Test.kt` is a scratch file for manually comparing completion against IntelliJ in the IDE.
// Exclude it from compilation so half-typed experiments there (an unresolved `ln`, a bare type, …) never
// break the build. It stays a `.kt` in the source root so IntelliJ still gives it full editing support.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    exclude("**/Test.kt")
}
