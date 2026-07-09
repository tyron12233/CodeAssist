import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    // Resolve all plugin versions once for the whole build; modules apply them themselves. The Kotlin
    // JVM and Multiplatform plugins ship in the same artifact, so both must be declared here (with a
    // single resolved version) or a subproject requesting one clashes with the other on the classpath.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    // Android Gradle Plugin 9.x (pairs with Gradle 9.x); applied by the Android modules themselves.
    // AGP 9 ships built-in Kotlin, so no kotlin-android plugin; KMP modules use the kmp-library plugin.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
}

subprojects {
    group = "dev.ide"
    version = "3.4.0"

    // Shared configuration applied to every module once it applies the Kotlin/JVM plugin.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        // Framework modules are built by desktop tooling but must ultimately load on Android/ART,
        // so target Java 17 bytecode (the level current Android toolchains accept). Keeping the Java
        // and Kotlin targets aligned also avoids KGP's jvm-target consistency error when building on a
        // newer JDK than the target.
        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
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
        }
        // Two kinds of test task:
        //   * `test` (and `check`/`build`) — the fast correctness suite. It EXCLUDES the slow, opt-in
        //     benchmark/quality/memory suites, which are tagged `@Tag("regression")`.
        //   * `regressionTest` — runs ONLY the regression-tagged suites, against committed JSON baselines.
        //     It is deliberately not wired into `check`, so routine builds stay fast; run it on demand:
        //       ./gradlew :lang-jdt:regressionTest :index-impl:regressionTest
        //     Update baselines after a deliberate change with `-Dbench.updateBaselines=true`.
        tasks.withType<Test>().configureEach {
            useJUnitPlatform {
                if (name == "regressionTest") includeTags("regression") else excludeTags("regression")
            }
        }
        tasks.register<Test>("regressionTest") {
            group = "verification"
            description = "Runs the opt-in @Tag(\"regression\") completion benchmark/quality/memory suites."
            val testTask = tasks.named<Test>("test")
            testClassesDirs = testTask.get().testClassesDirs
            classpath = testTask.get().classpath
            // Baselines are committed under <module>/baselines and compared on every run; point the suites
            // at that directory (the Test working dir is the module dir, but be explicit) and give the
            // memory suites headroom. Always re-run (perf isn't an up-to-date-able output) and surface the
            // printed comparison tables without needing --info.
            workingDir = projectDir
            systemProperty("bench.baselineDir", layout.projectDirectory.dir("baselines").asFile.absolutePath)
            System.getProperty("bench.updateBaselines")?.let { systemProperty("bench.updateBaselines", it) }
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
