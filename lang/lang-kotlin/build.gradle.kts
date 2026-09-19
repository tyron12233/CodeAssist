import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// lang-kotlin — the Kotlin LanguageBackend: editor-time code intelligence (member completion, name/type
// resolution, a small inference subset) behind the backend-neutral language-api SPI.
//
// Editor strategy: use a parser ONLY (KtFile, from :kotlin-syntax — the Kotlin compiler's own multiplatform
// grammar), and build our own symbol table, inference, and completion on the neutral DOM. Codegen (K2 ->
// .class) is a separate track: KotlinJvmCompiler/IncrementalKotlinCompiler, driven by the module's own
// compileKotlin build task (KotlinCompileTask) rather than a build-engine port.
//
// MULTIPLATFORM, and the source-set split IS the architecture. `commonMain` is the editor: parse, symbols,
// resolve, inference, completion, diagnostics, highlighting, folding — 25k lines that need a string, not a
// filesystem. `jvmMain` is everything bound to a real build or a real JVM: the K2 compiler driver
// (`compile/`), the build tasks (`build/`), the preview lowering (`interp/`), the analysis-api providers,
// and `KotlinSourceAnalyzer`, the SPI adapter that binds the editor half to a `CompilationContext`.
//
// The line is not "what is portable" but "what names a project": a backend's editor half is a function from
// text and a classpath to symbols, and only the adapter around it knows what a module is.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    iosSimulatorArm64()

    iosArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":language-api"))            // the SPI (+ model-api transitively)
            // The pure symbol/index layer (neutral symbol model + @Metadata/bytecode decoders + KotlinCallableIndex),
            // extracted from this module so it stays compiler-free.
            api(project(":lang-kotlin-index"))
            // The parser the editor actually uses: the Kotlin compiler's own grammar, vendored and multiplatform,
            // behind a `Kt*` facade with the compiler's own accessor names. `api`, because `KtFile` and the rest of
            // that vocabulary are all over this module's public signatures (KotlinParsedFile.ktFile, the symbol and
            // completion entry points), exactly as the PSI types were.
            api(project(":kotlin-syntax"))
            // Reading class files, jars and Kotlin metadata with no JVM behind it. `BuiltinsReader` decodes
            // `.kotlin_builtins` through it, which is what takes the built-in types (`List`, `Int`, `String`)
            // off the compiler's own protobuf classes.
            implementation(project(":kotlin-classfile"))
        }

        jvmMain.dependencies {
            // The half of the index SPI that BUILDS one: `KotlinSourceAnalyzer` holds an `IndexService`, while
            // the symbol service below it names only `IndexQueries` (in :model-api, common).
            implementation(project(":index-api"))
            implementation(project(":analysis-api")) // owns the Kotlin diagnostic + import-fix providers
            // Owns the `compileKotlin` build task (KotlinCompileTask): the build graph drives K2 directly through it,
            // so build-engine carries no KotlinCompile port. Brings build-api transitively.
            implementation(project(":build-engine"))
            // The build's in-process K2JVMCompiler. We never build a BindingContext or run the analyzer for
            // editing; all editor semantics are ours. The compiler comes from :kotlin-compiler-deps - the
            // UNSHADED `-for-ide` split over the real IntelliJ platform, replacing kotlin-compiler-embeddable,
            // so there is one compiler platform in the whole IDE.
            implementation(project(":kotlin-compiler-deps"))
            // Read the @Metadata annotation values (and Java bytecode shape) off classpath .class files, on the
            // compile/codegen side. The EDITOR side decodes through :kotlin-classfile and needs neither.
            implementation(libs.kotlin.metadata.jvm)
            implementation(libs.ow2.asm)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            // The editor suite that runs on EVERY target needs a coroutine test driver (completion is
            // `suspend`) and the portable file system, to put a real source tree under a real walk.
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":kotlin-classfile"))
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            // The root build adds these to every `kotlin.jvm` module's `test` source set; a multiplatform
            // module has no `test` source set for that to reach, so they are named here. Without them the
            // suite fails to COMPILE rather than being skipped, which is at least loud.
            implementation(project(":test-support"))
            // Tests build a real workspace + classpath to exercise the Module -> CompilationContext bridge and the
            // parser/symbol/completion pipeline end to end.
            implementation(project(":project-model-impl"))
            implementation(project(":index-impl")) // wire the real persistent index to reproduce the device path
            // Opt-in regression suites (`regressionTest`): shared benchmark/baseline/memory harness.
            implementation(project(":bench-support"))
            implementation(libs.kotlinx.coroutines.test)
            // Real Compose runtime jar on the test classpath: KotlinComposeBuildTest compiles a @Composable against
            // it (with the bundled Compose plugin) and asserts the synthetic-param transform. The test self-gates
            // (assumeTrue) when the jar isn't resolvable, so CI without the Compose repo just skips it.
            implementation(libs.compose.runtime.desktop)
            // Real kotlinx.serialization runtime on the test classpath: KotlinSerializationBuildTest compiles a
            // @Serializable class against it (with the bundled plugin) and asserts the generated serializer. Self-gates
            // (assumeTrue) when the jar isn't resolvable, so CI without it just skips.
            implementation(libs.kotlinx.serialization.json)
            // Real kotlin-parcelize runtime (the `@Parcelize` annotation) on the test classpath: KotlinParcelizeBuildTest
            // compiles a `@Parcelize class : Parcelable` against it (with the bundled plugin) and asserts the generated
            // Parcelable impl. Self-gates when it (or android.jar) isn't resolvable, so a stripped CI classpath skips.
            implementation("org.jetbrains.kotlin:kotlin-parcelize-runtime:2.4.0")
        }

        // Declared by hand: `kotlin.mpp.applyDefaultHierarchyTemplate=false` (see gradle.properties).
        val iosMain = create("iosMain") { dependsOn(getByName("commonMain")) }
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        getByName("iosArm64Main").dependsOn(iosMain)
        val iosTest = create("iosTest") { dependsOn(getByName("commonTest")) }
        getByName("iosSimulatorArm64Test").dependsOn(iosTest)
        getByName("iosArm64Test").dependsOn(iosTest)
    }
}

// The root build wires JUnit 5 into every `kotlin.jvm` module; a multiplatform one has to say so itself.
dependencies {
    "jvmTestImplementation"(platform(libs.junit.bom))
    "jvmTestImplementation"(libs.junit.jupiter)
    "jvmTestRuntimeOnly"(libs.junit.platform.launcher)
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

tasks.named<ProcessResources>("jvmProcessResources") {
    from(bundledStdlib) { rename { "kotlin-stdlib.jar" } }
    from(bundledComposePlugin) { rename { "kotlin-compose-compiler-plugin.jar" } }
    from(bundledSerializationPlugin) { rename { "kotlin-serialization-compiler-plugin.jar" } }
    from(bundledParcelizePlugin) { rename { "kotlin-parcelize-compiler-plugin.jar" } }
}

// `src/jvmTest/.../Test.kt` is a scratch file for manually comparing completion against IntelliJ in the IDE.
// Exclude it from compilation so half-typed experiments there (an unresolved `ln`, a bare type, …) never
// break the build. It stays a `.kt` in the source root so IntelliJ still gives it full editing support.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlinJvm") {
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
// `:lang-kotlin:compilerTest --tests '*IncrementalKotlinCompilerTest*'` — a `--tests` filter on `jvmTest`
// can never match those classes, since the exclusion below still applies.
val compilerTestPattern = "dev.ide.lang.kotlin.compile.*"

// `useJUnitPlatform` is NOT set here: the root build configures it for every Test task, WITH the
// regression-tag filter, and calling it again plainly would drop that filter and let the slow benchmark
// suites into the fast gate.
tasks.named<Test>("jvmTest") {
    filter { excludeTestsMatching(compilerTestPattern) }
    maxHeapSize = "1536m"
    // Point the real-foundation reproduction/regression test at the extracted classes.jar (self-gates if absent).
    dependsOn(extractComposeFoundationClasses)
    systemProperty("compose.foundation.classes.jar", composeFoundationJar.get().asFile.absolutePath)
    // `-Dkt.updateDigest=true` re-records the analysis-parity goldens (see KotlinAnalysisDigestTest).
    // Forwarded explicitly, because a `-D` on the command line reaches the GRADLE JVM and the tests run in
    // a forked one -- the same trap :kotlin-syntax's baseline flag documents.
    System.getProperty("kt.updateDigest")?.let { systemProperty("kt.updateDigest", it) }
    // The analysis sweeps, both opt-in. `-Dkt.sweep=true` runs the one over THIS repository's own Kotlin
    // (the root is handed over the way :kotlin-syntax hands its parser sweep the same one);
    // `-Dkt.externalCorpus=<kotlin checkout>` runs the one over the standard library. Opt-in because a sweep
    // is ~3,000 files of analysis, which does not belong in the fast correctness gate.
    val sweeping = System.getProperty("kt.sweep") != null || System.getProperty("kt.externalCorpus") != null
    if (System.getProperty("kt.sweep") != null) systemProperty("kt.repoRoot", rootDir.absolutePath)
    System.getProperty("kt.externalCorpus")?.let { systemProperty("kt.externalCorpus", it) }
    // `-Dkt.sweepOnly=<path substring>` narrows the module sweep to matching files and dumps every error in
    // them with its source line, for working one bucket. Forwarded for the same reason as the flags above.
    System.getProperty("kt.sweepOnly")?.let { systemProperty("kt.sweepOnly", it) }
    if (sweeping) {
        // A sweep needs more heap than the fast gate's 1536m: it holds a whole corpus's source model, and the
        // module sweep stands a second one up over a 68-jar classpath in the same JVM. Measured: the full
        // suite with both sweeps died with "Java heap space" at 1536m and passes at 4g. Raised only when a
        // sweep was asked for, so the ordinary gate keeps its smaller, faster fork.
        maxHeapSize = "4g"
        // The sweep's OUTPUT is the whole point of running it, and a test task swallows stdout by default --
        // asking for the sweep and getting a silent BUILD SUCCESSFUL reads as "no findings" when it actually
        // means "you never saw them". Re-running is likewise not optional: the task is UP-TO-DATE from the
        // previous build, so without this the second sweep of a session prints nothing at all.
        testLogging { showStandardStreams = true }
        outputs.upToDateWhen { false }
    }
}

val jvmTestCompilation = kotlin.jvm().compilations.getByName("test")
val compilerTest = tasks.register<Test>("compilerTest") {
    group = "verification"
    description = "Runs the real-K2-compiler suites ($compilerTestPattern), one fresh JVM per test class."
    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = jvmTestCompilation.runtimeDependencyFiles + jvmTestCompilation.output.allOutputs
    filter { includeTestsMatching(compilerTestPattern) }
    maxHeapSize = "3g"
    setForkEvery(1)
}
tasks.named("check") { dependsOn(compilerTest) }
