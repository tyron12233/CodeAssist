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
    // The pure symbol/index layer (neutral symbol model + @Metadata/bytecode decoders + KotlinCallableIndex),
    // extracted from this module so it stays compiler-free.
    api(project(":lang-kotlin-index"))
    implementation(project(":analysis-api")) // owns the Kotlin diagnostic + import-fix providers
    // Owns the `compileKotlin` build task (KotlinCompileTask): the build graph drives K2 directly through it,
    // so build-engine carries no KotlinCompile port. Brings build-api transitively.
    implementation(project(":build-engine"))

    // The Kotlin frontend as a PSI host (text -> KtFile) + the build's in-process K2JVMCompiler. We never
    // build a BindingContext or run the analyzer for editing; all editor semantics are ours. The compiler
    // comes from :kotlin-compiler-deps - the UNSHADED `-for-ide` split over the real IntelliJ platform,
    // replacing kotlin-compiler-embeddable, so there is one PSI/compiler platform in the whole IDE.
    implementation(project(":kotlin-compiler-deps"))
    // The shared IntelliJ platform environment (KotlinParserHost delegates its env/lock/forceFullParse to it,
    // so :lang-xml can register XML PSI onto the SAME application environment — one platform in the process).
    implementation(project(":intellij-psi-host"))
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
    // Real kotlinx.serialization runtime on the test classpath: KotlinSerializationBuildTest compiles a
    // @Serializable class against it (with the bundled plugin) and asserts the generated serializer. Self-gates
    // (assumeTrue) when the jar isn't resolvable, so CI without it just skips.
    testImplementation(libs.kotlinx.serialization.json)
    // Real kotlin-parcelize runtime (the `@Parcelize` annotation) on the test classpath: KotlinParcelizeBuildTest
    // compiles a `@Parcelize class : Parcelable` against it (with the bundled plugin) and asserts the generated
    // Parcelable impl. Self-gates when it (or android.jar) isn't resolvable, so a stripped CI classpath skips.
    testImplementation("org.jetbrains.kotlin:kotlin-parcelize-runtime:2.4.0")
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
// The `-for-ide` build of the plugin: it must link the same unshaded compiler world as :kotlin-compiler-deps
// (the `-embeddable` plugin variant references the relocated org.jetbrains.kotlin.com.intellij.* and cannot
// load in the unshaded compiler).
val bundledComposePlugin: Configuration by configurations.creating { isTransitive = false }
dependencies { bundledComposePlugin(libs.kotlin.compose.compiler.plugin.ide) }

// Bundle the kotlinx.serialization compiler-plugin JAR (`/kotlin-serialization-compiler-plugin.jar`) the same
// way: when a module carries the serialization runtime, kotlinc is fed this jar via `-Xplugin` so `@Serializable`
// classes get their generated serializers. `SerializationCompilerPlugin` extracts it; the host applies it
// per-module. The `-for-ide` build, for the same unshaded-compiler reason as the Compose plugin above.
val bundledSerializationPlugin: Configuration by configurations.creating { isTransitive = false }
dependencies { bundledSerializationPlugin(libs.kotlin.serialization.compiler.plugin.ide) }

// Bundle the kotlin-parcelize compiler-plugin JAR (`/kotlin-parcelize-compiler-plugin.jar`) the same way: a
// module with the parcelize runtime (`@Parcelize`, added by the Build Features toggle) is compiled with it so
// `@Parcelize` classes get their generated `Parcelable`. `ParcelizeCompilerPlugin` extracts it. `-for-ide` for
// the same unshaded-compiler reason as the plugins above.
val bundledParcelizePlugin: Configuration by configurations.creating { isTransitive = false }
dependencies { bundledParcelizePlugin(libs.kotlin.parcelize.compiler.plugin.ide) }

// Real androidx compose-foundation classes (LazyListScope + the `items` overloads + LazyColumn) for the
// reproduction/regression test of the unimported-extension-overload gap (`items(list) { }` inside `LazyColumn`,
// which the compiler rejects but the editor accepted). Extracted from the AAR, NON-transitive (only foundation's
// own classes are decoded). The test self-gates (assumeTrue) when it isn't resolvable, so a stripped/offline CI
// classpath just skips it.
val composeFoundationAar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies { composeFoundationAar("androidx.compose.foundation:foundation-android:1.7.5@aar") }

val composeFoundationJar = layout.buildDirectory.file("compose-foundation/foundation-classes.jar")
val extractComposeFoundationClasses = tasks.register<Copy>("extractComposeFoundationClasses") {
    from(composeFoundationAar.elements.map { set -> zipTree(set.single().asFile) }) { include("classes.jar") }
    rename { "foundation-classes.jar" }
    into(composeFoundationJar.get().asFile.parentFile)
}

tasks.processResources {
    from(bundledStdlib) { rename { "kotlin-stdlib.jar" } }
    from(bundledComposePlugin) { rename { "kotlin-compose-compiler-plugin.jar" } }
    from(bundledSerializationPlugin) { rename { "kotlin-serialization-compiler-plugin.jar" } }
    from(bundledParcelizePlugin) { rename { "kotlin-parcelize-compiler-plugin.jar" } }
}

// `src/test/.../Test.kt` is a scratch file for manually comparing completion against IntelliJ in the IDE.
// Exclude it from compilation so half-typed experiments there (an unresolved `ln`, a bare type, …) never
// break the build. It stays a `.kt` in the source root so IntelliJ still gives it full editing support.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    exclude("**/Test.kt")
}

// The compile/ tests run the real in-process K2 compiler (KotlinCoreEnvironment + kotlinc invocations).
// In a shared worker JVM that compiler state accumulates across test classes until the heap is exhausted;
// the OOM then surfaces as a spurious assertion failure (a compile reports success=false, or incremental
// NOOP/INCREMENTAL detection falls back to FULL). They need real heap and a fresh JVM per class.
//
// Nothing else here does: the other ~160 classes are parse/resolve/completion tests that stand up no
// compiler, and a fresh 3 GB JVM per class cost them far more than the tests themselves (a ~0.9 s start
// each — 60% of the task's wall time). So the two kinds run as two tasks, and only the compiler suite pays
// the strict policy. `check` runs both. To filter WITHIN the compiler suite, name its task:
// `:lang-kotlin:compilerTest --tests '*IncrementalKotlinCompilerTest*'` — a `--tests` filter on `test`
// can never match those classes, since the exclusion below still applies.
val compilerTestPattern = "dev.ide.lang.kotlin.compile.*"

tasks.named<Test>("test") {
    filter { excludeTestsMatching(compilerTestPattern) }
    maxHeapSize = "1536m"
    // Point the real-foundation reproduction/regression test at the extracted classes.jar (self-gates if absent).
    dependsOn(extractComposeFoundationClasses)
    systemProperty("compose.foundation.classes.jar", composeFoundationJar.get().asFile.absolutePath)
}

val testSourceSet = the<SourceSetContainer>()["test"]
val compilerTest = tasks.register<Test>("compilerTest") {
    group = "verification"
    description = "Runs the real-K2-compiler suites ($compilerTestPattern), one fresh JVM per test class."
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    filter { includeTestsMatching(compilerTestPattern) }
    maxHeapSize = "3g"
    setForkEvery(1)
}
tasks.named("check") { dependsOn(compilerTest) }
