rootProject.name = "codeassist"

pluginManagement {
    // build-logic hosts plugins that depend on AGP (the `dev.ide.kotlinc-art` Kotlin-compiler-on-ART
    // instrumentation) — they must share AGP's classloader, which buildSrc can't provide.
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // Modules must not declare their own repositories; all resolution flows through here.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google() // Compose Multiplatform / AndroidX artifacts
        // :kotlin-compiler-deps only: the unshaded `-for-ide` compiler and the un-relocated IntelliJ platform
        // it needs. Not on Maven Central; JetBrains-only repos. Scoped so normal resolution never consults them.
        maven("https://redirector.kotlinlang.org/maven/kotlin-ide-plugin-dependencies") {
            content { includeGroup("org.jetbrains.kotlin") }
        }
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
            content {
                includeGroupByRegex("org\\.jetbrains\\.intellij.*")
                includeGroup("org.jetbrains.kotlin")
                includeModule("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm") // JB coroutines fork (…-intellij-N)
            }
        }
        maven("https://cache-redirector.jetbrains.com/intellij-repository/releases") {
            content {
                includeGroupByRegex("com\\.jetbrains\\.intellij.*")
                includeModule("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm") // JB coroutines fork
            }
        }
    }
}

// Dependency direction points downward only (acyclic) — see README.md / docs.
//   platform-core  <- vfs-api <- project-model-api <- { build-api, language-api }
//   project-model-api <- deps-api
//   language-api <- { index-api, analysis-api } ; index-api <- analysis-api (diagnostics/analyzers/fixes)
//   language-api <- block-api ; block-api <- block-impl (projectional/block editor)
//   ide-ui (Compose Multiplatform UI) <- { ide-desktop (JVM launcher), ide-android (Android launcher) }

// The pure-Kotlin/JVM framework — builds and tests with no Android SDK or Compose toolchain.
include(
    ":platform-core",
    ":vfs-api",
    ":project-model-api",
    ":project-model-impl",
    ":build-api",
    ":build-engine",
    ":android-support",
    ":android-sdk-metadata", // build-time generator: SDK attrs.xml + android.jar → bundled metadata asset
    ":language-api",
    ":index-api",
    ":index-impl",
    ":analysis-api",
    ":analysis-impl",
    ":lang-jdt",
    ":lang-xml",
    ":lang-kotlin-index", // pure, compiler-free Kotlin symbol/index layer shared by the Kotlin editor backend
    ":kotlin-compiler-deps", // the ONE unshaded Kotlin compiler + IntelliJ platform dependency set (no embeddable)
    ":intellij-psi-host", // the ONE shared IntelliJ platform env both lang-kotlin + lang-xml parse against
    ":lang-kotlin", // editor-only Kotlin LanguageBackend (PSI parse + our own symbols/inference/completion)
    ":jvm-build", // JVM-language build system: JavaBuildSystem/JavaPlugin compose lang-jdt+lang-kotlin compile tasks over build-engine
    ":interp-core", // on-device Kotlin interpreter: tree-walks lang-kotlin's ResolvedTree (Compose interpreter, step 3)
    ":deps-api",
    ":deps-impl",
    ":analytics-api", // opt-in usage-analytics SPI (event model + AnalyticsService/AnalyticsSink ports)
    ":analytics-impl", // the engine: durable batch buffer + Supabase PostgREST sink + scrubbed crash reporter
    ":block-api",
    ":block-impl",
    ":plugin-api",  // UI extensibility SPI: the lean action model (IdeAction/ActionGroup + places) + EPs
    ":plugin-impl", // ActionManager: resolves UI_ACTION_EP/ACTION_GROUP_EP into places/menus, dispatches
    ":layout-preview-api",  // owned XML-layout preview: render contracts (RCanvas/RenderNode/Renderer), android-free
    ":layout-preview-impl", // the preview engine: resource value resolver, inflater, built-in renderers, ASM bridge remapper
    ":bench-support", // test-only: shared regression/benchmark harness (consumed via testImplementation)
)

// The IDE shells (Compose Multiplatform + AGP). These apply the Android Gradle plugin / Compose KMP plugin,
// which require the Android SDK even to *configure*. CI sets CI_CORE_ONLY=true to build just the framework
// above (the part with the unit tests + regression suites) without provisioning an Android SDK; a normal
// local build leaves it unset and includes everything. Nothing in the framework depends on these, so
// excluding them is safe and keeps the acyclic graph intact.
if (System.getenv("CI_CORE_ONLY") != "true") {
    include(
        ":interp-compose", // Compose bridge + render surface (KMP: desktop+android) — needs the Compose plugin
        ":ide-ui",
        ":ide-core",
        ":ide-desktop",
        ":ide-android",
    )
}
