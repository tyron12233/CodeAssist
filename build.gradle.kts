import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    // Resolve all plugin versions once for the whole build; modules apply them themselves. The Kotlin
    // JVM and Multiplatform plugins ship in the same artifact, so both must be declared here (with a
    // single resolved version) or a subproject requesting one clashes with the other on the classpath.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
    // Android Gradle Plugin 9.x (pairs with Gradle 9.x); applied by the Android modules themselves.
    // AGP 9 ships built-in Kotlin, so no kotlin-android plugin; KMP modules use the kmp-library plugin.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
}

subprojects {
    group = "dev.ide"
    version = "3.4.0"

    // Applies to EVERY test task in every module — including the multiplatform modules' `desktopTest`, which
    // the `kotlin.jvm`-gated block below never sees.
    tasks.withType<Test>().configureEach {
        // Run the forked workers headless. On macOS a JVM that touches AWT becomes a foreground GUI app, so a
        // test run would raise a Dock icon and steal window focus from whatever the developer is doing —
        // several suites here rasterize Compose content (ImageComposeScene/Skiko) and would do exactly that.
        // Neither flag stops Skiko loading or rendering off-screen; no display is ever opened.
        systemProperty("java.awt.headless", "true")
        systemProperty("apple.awt.UIElement", "true")
    }

    // Shared configuration applied to every module once it applies the Kotlin/JVM plugin.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        // Framework modules are built by desktop tooling but must ultimately load on Android/ART,
        // so target Java 17 bytecode (the level current Android toolchains accept). Keeping the Java
        // and Kotlin targets aligned also avoids KGP's jvm-target consistency error when building on a
        // newer JDK than the target.
        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                // See `-Xjdk-release` below, which every Kotlin/JVM compilation in the build gets.
            }
        }
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        // Uniform test stack: JUnit 5 (platform) + kotlin-test assertions.
        dependencies {
            "testImplementation"(platform(libs.junit.bom))
            "testImplementation"(libs.junit.jupiter)
            "testImplementation"(libs.kotlin.test)
            "testRuntimeOnly"(libs.junit.platform.launcher)
            // Shared test infrastructure (dev.ide.testkit): temp dirs, source seeding, VFS/document stubs,
            // classpath/jar builders, compilation contexts, fake index, workspace helpers. Auto-wired into
            // every framework module so tests never re-implement scaffolding. It is only ever a
            // `testImplementation` dependency, so a module in :test-support's own main-dependency chain
            // (e.g. project-model-impl) still wires it without a cycle (test→main is acyclic). Excluded only
            // from the shared harness modules themselves. :bench-support carries the benchmark/regression
            // primitives and is reached transitively through :test-support.
            if (project.name !in setOf("test-support", "bench-support")) {
                "testImplementation"(project(":test-support"))
            }
        }
    }

    // The Java half of the jvm target, for a multiplatform module that has Java sources (`:lang-kotlin`'s
    // test fixtures do). The Kotlin/JVM guard above sets this through the `java` extension, which a
    // multiplatform module configures differently; without it KGP fails the build with an inconsistent
    // jvm-target between `compileJvmTestJava` (the JDK's default, 21 here) and `compileTestKotlinJvm` (17).
    // Here rather than in the module, because it is the same trap for the next module converted.
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        tasks.withType<JavaCompile>().configureEach {
            sourceCompatibility = JavaVersion.VERSION_17.toString()
            targetCompatibility = JavaVersion.VERSION_17.toString()
        }
    }

    // COMPILE AGAINST THE JAVA 17 API, not merely to Java 17 BYTECODE, in EVERY module whatever plugin
    // created its Kotlin/JVM compilation -- `kotlin.jvm`, `kotlin.multiplatform`, or AGP's built-in Kotlin.
    //
    // `jvmTarget` picks the class-file version; the API the compiler RESOLVES against is still the JDK it
    // runs on, which is 21+ here. On Android that difference is not academic. A JDK method added after 17
    // becomes visible, and where the JDK and the Kotlin stdlib declare the same name, the MEMBER wins over
    // the extension: `MutableList.removeLast()` was Kotlin's extension until JDK 21 gave `java.util.List` a
    // member of its own, after which it compiles to an interface method that does not exist below Android
    // API 35 and throws `NoSuchMethodError` the first time the line runs. It dexes clean, so nothing before
    // the device says a word.
    //
    // That is how the vendored Kotlin lexer came to crash on every Android below 15 -- ordinary string
    // templates reach `popState`, which pops a `MutableList`. Found by `KotlinAnalysisArtParityTest` on an
    // API 26 image; invisible on the JVM, to the source scan, and on a current emulator. Fixing it here
    // rather than at the call site is deliberate: the vendored parser is byte-identical to upstream on
    // purpose (see its VENDOR.md), and this closes the whole class rather than the one method.
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions { freeCompilerArgs.add("-Xjdk-release=17") }
    }

    // Two kinds of test task:
    //   * `test`/`jvmTest` (and `check`/`build`) — the fast correctness suite. It EXCLUDES the slow,
    //     opt-in benchmark/quality/memory suites, which are tagged `@Tag("regression")`.
    //   * `regressionTest` — runs ONLY the regression-tagged suites, against committed JSON baselines.
    //     It is deliberately not wired into `check`, so routine builds stay fast; run it on demand:
    //       ./gradlew :lang-jdt:regressionTest :index-impl:regressionTest
    //     Update baselines after a deliberate change with `-Dbench.updateBaselines=true`.
    //
    // Applied to BOTH plugins. The shared test STACK above (junit, kotlin-test, :test-support) stays
    // JVM-only: a multiplatform module declares its own, since those artifacts have no common variant. They used to live inside the Kotlin/JVM
    // guard above, which meant converting a module to multiplatform silently dropped both: its
    // `regressionTest` task vanished (CI names :index-impl's by hand) and, worse, `jvmTest` stopped
    // EXCLUDING the regression tag, so the slow benchmark suites would have started running in the fast
    // gate. Keyed off whichever test task the module actually has.
    listOf("org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.multiplatform").forEach { pluginId ->
        plugins.withId(pluginId) {
            tasks.withType<Test>().configureEach {
                useJUnitPlatform {
                    if (name == "regressionTest") includeTags("regression") else excludeTags("regression")
                }
            }
            tasks.register<Test>("regressionTest") {
                group = "verification"
                description = "Runs the opt-in @Tag(\"regression\") completion benchmark/quality/memory suites."
                val testTask = tasks.named<Test>(if (pluginId.endsWith("multiplatform")) "jvmTest" else "test")
                testClassesDirs = testTask.get().testClassesDirs
                classpath = testTask.get().classpath
                // Baselines are committed under <module>/baselines and compared on every run; point the suites
                // at that directory (the Test working dir is the module dir, but be explicit) and give the
                // memory suites headroom. Always re-run (perf isn't an up-to-date-able output) and surface the
                // printed comparison tables without needing --info.
                workingDir = projectDir
                systemProperty("bench.baselineDir", layout.projectDirectory.dir("baselines").asFile.absolutePath)
                System.getProperty("bench.updateBaselines")?.let { systemProperty("bench.updateBaselines", it) }
                // `-Dbench.quick=true`: one timed batch instead of five, for iterating on a suite. Gates and
                // baseline writes are off in that mode — see `Bench.quick`.
                System.getProperty("bench.quick")?.let { systemProperty("bench.quick", it) }
                maxHeapSize = "1536m"
                // Benchmarks must always run fresh: never UP-TO-DATE, and never served FROM-CACHE (a cached
                // full run would otherwise replay regardless of a `--tests` filter, and cached perf numbers are
                // meaningless). Together these force a real execution every time.
                outputs.upToDateWhen { false }
                outputs.doNotCacheIf("regression benchmarks must measure a fresh run") { true }
                testLogging {
                    showStandardStreams = true
                    events("passed", "failed", "skipped")
                }
            }
        }
    }

}

// ---------------------------------------------------------------------------------------------------
// Self-hosting: export this build for CodeAssist's own build system.
//
// Gradle is the only thing that can evaluate these scripts, so it exports what it configured and the IDE
// imports the result (`.platform/gradle-model.json` -> `GradleModelImporter`). Run it after a change to any
// module's dependencies or source layout; see docs/self-hosting.md.
// ---------------------------------------------------------------------------------------------------
tasks.register<dev.ide.build.nativemodel.GenerateNativeModel>("generateNativeModel") {
    target.set(providers.gradleProperty("nativeModel.target").orElse("android"))
    outputFile.set(layout.projectDirectory.file(".platform/gradle-model.json"))
}
